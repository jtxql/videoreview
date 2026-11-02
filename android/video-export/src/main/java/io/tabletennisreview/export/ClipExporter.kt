package io.tabletennisreview.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread owner of Transformer; source inspection, validation, save and cleanup use one IO owner. */
// Experimental setters only explicitly disable trim/edit-list optimizations; no experimental path enabled.
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class ClipExporter(context: Context, listener: (ClipExportState) -> Unit) : AutoCloseable {
    private companion object { val processOwner = ClipExportLease() }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val files = Executors.newSingleThreadExecutor { job -> Thread(job, "Review-export-files") }
    private val gate = ClipExportGate()
    private var listener: ((ClipExportState) -> Unit)? = listener
    private class Task(val id: Long, val request: ClipExportRequest, val file: File) {
        val cancelled = AtomicBoolean()
        val cleanupQueued = AtomicBoolean()
        val saveCommit = ClipSaveCommit()
        var expectAudio = false
        var sourceSize: Pair<Int, Int>? = null
        var sourceFps: Double? = null
    }
    private var task: Task? = null
    private var transformer: Transformer? = null
    private var ticker: Runnable? = null
    private var closed = false
    val state: ClipExportState get() = gate.state
    private fun mainOnly() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun publish() { if (!closed) listener?.invoke(state) }
    private fun current(t: Task, phase: ClipExportPhase) = !closed && task === t && !t.cancelled.get() && gate.isAt(t.id, phase)
    private fun move(t: Task, phase: ClipExportPhase, message: String, duration: Long? = state.actualDurationUs) {
        if (gate.move(t.id, phase, message, duration)) publish()
    }
    fun start(request: ClipExportRequest): Boolean {
        mainOnly(); if (closed) return false
        val id = gate.begin() ?: return false
        if (!processOwner.acquire(id)) {
            gate.move(id, ClipExportPhase.Failed, "已有导出或资源清理进行中，请稍后再试"); publish(); return false
        }
        val file = File(app.cacheDir, "ab-exports/clip-${id}-${UUID.randomUUID()}.mp4")
        val t = Task(id, request, file); task = t; publish()
        files.execute {
            val checked = runCatching {
                if (t.cancelled.get()) throw CancellationException()
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法建立导出缓存" }
                check(app.cacheDir.usableSpace >= 32L * 1024 * 1024) { "缓存可用空间不足32MiB，请先释放空间" }
                t.expectAudio = inspectSource(t)
            }
            main.post {
                if (!current(t, ClipExportPhase.Preparing)) return@post
                checked.onSuccess { beginEncoding(t) }.onFailure { fail(t, "源视频检查失败：${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        return true
    }
    private fun inspectSource(t: Task): Boolean {
        val request = t.request
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, Uri.parse(request.sourceUri), null)
            var video = false
            var audioCount = 0
            var videoCount = 0
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audioCount++
                if (mime.startsWith("video/")) {
                    videoCount++
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) t.sourceFps =
                        runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                            ?: runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                    if (!video && f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT))
                        t.sourceSize = f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
                    video = true
                    val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                    check(mime != "video/dolby-vision" && transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG) {
                        "首版暂不导出HDR/杜比视界，请先使用SDR视频；不会静默转SDR"
                    }
                }
            }
            check(video) { "未发现可访问的视频轨道" }
            check(videoCount == 1) { "首版仅支持单视频轨，避免导出错误轨道" }
            check(!request.keepAudio || audioCount <= 1) { "首版保留音频仅支持单音轨；请选择静音或单音轨源视频" }
            return audioCount > 0 && request.keepAudio
        } finally { extractor.release() }
    }
    private fun beginEncoding(t: Task) {
        if (!current(t, ClipExportPhase.Preparing)) return
        try {
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionUs(t.request.startUs)
                .setEndPositionUs(t.request.endUs).setStartsAtKeyFrame(false).build()
            val media = MediaItem.Builder().setUri(t.request.sourceUri).setClippingConfiguration(clip).build()
            val speed = object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = t.request.speed.toFloat()
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            }
            val edited = EditedMediaItem.Builder(media).setRemoveAudio(!t.request.keepAudio).setSpeed(speed).build()
            val engine = Transformer.Builder(app).setLooper(Looper.getMainLooper())
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(app).setEnableFallback(false).build())
                .experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (!current(t, ClipExportPhase.Exporting)) return
                        stopTicker(); transformer = null
                        files.execute {
                            val validated = runCatching {
                                val duration = validateOutput(t.file, t.expectAudio, t.request.keepAudio, t.sourceSize)
                                check(t.request.matchesDuration(duration, t.sourceFps)) {
                                    "成片时长${duration}us与预期${t.request.expectedDurationUs}us不符，未通过慢动作核验"
                                }
                                duration
                            }
                            main.post {
                                if (!current(t, ClipExportPhase.Exporting)) return@post
                                validated.onSuccess { duration -> move(t, ClipExportPhase.Ready, "临时片段已生成并核验；请另存，尚未保存到目标位置", duration) }
                                    .onFailure { fail(t, "输出核验失败：${it.javaClass.simpleName}: ${it.message}") }
                            }
                        }
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        if (current(t, ClipExportPhase.Exporting)) fail(t, "转码失败：${exception.errorCodeName}: ${exception.message}")
                    }
                    override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest, fallbackTransformationRequest: TransformationRequest) {
                        if (current(t, ClipExportPhase.Exporting)) fail(t, "设备要求改变编码配置；首版禁止静默降级，已停止导出")
                    }
                }).build()
            transformer = engine
            move(t, ClipExportPhase.Exporting, "正在生成A/B慢动作；请保持应用前台")
            engine.start(edited, t.file.absolutePath)
            val progress = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!current(t, ClipExportPhase.Exporting) || transformer !== engine) return
                    try {
                        if (engine.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            gate.progress(t.id, progress.progress); publish()
                        }
                        main.postDelayed(this, 300)
                    } catch (error: Exception) { fail(t, "进度查询失败：${error.message}") }
                }
            }
            ticker = poll; main.postDelayed(poll, 300)
        } catch (error: Exception) { fail(t, "无法启动导出：${error.javaClass.simpleName}: ${error.message}") }
    }
    private fun validateOutput(file: File, expectAudio: Boolean, keepAudio: Boolean, sourceSize: Pair<Int, Int>?): Long {
        check(file.isFile && file.length() > 0) { "输出为空" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var duration = 0L
            var hasVideo = false
            var hasAudio = false
            var outputSize: Pair<Int, Int>? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    hasVideo = true
                    if (f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT))
                        outputSize = f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
                }
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) hasAudio = true
                if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
            }
            check(hasVideo && duration > 0) { "输出缺少有效视频或时长" }
            check(!expectAudio || hasAudio) { "原视频有音轨但输出缺失，不能声称保留音频" }
            check(keepAudio || !hasAudio) { "静音输出意外包含音轨" }
            if (sourceSize != null) check(outputSize != null &&
                listOf(sourceSize.first, sourceSize.second).sorted() == listOf(outputSize.first, outputSize.second).sorted()) {
                "输出尺寸与源视频不符，不能声称保持源分辨率"
            }
            return duration
        } finally { extractor.release() }
    }
    fun save(destination: Uri): Boolean {
        mainOnly()
        val t = task ?: return false
        if (!current(t, ClipExportPhase.Ready)) return false
        if (destination.scheme != "content") { move(t, ClipExportPhase.Saving, "检查保存位置"); move(t, ClipExportPhase.Ready, "保存位置无效，临时片段仍可重试"); return false }
        move(t, ClipExportPhase.Saving, "正在另存；写入和关闭完成前不表示保存成功")
        files.execute {
            var opened = false
            val result = runCatching {
                val source = Uri.parse(t.request.sourceUri)
                check(!sameDocument(source, destination)) { "目标与原视频相同，拒绝覆盖" }
                if (t.cancelled.get()) throw CancellationException()
                val expected = t.file.length()
                val input = t.file.inputStream()
                val output = try { checkNotNull(app.contentResolver.openOutputStream(destination, "wt")) { "无法打开目标文档" } }
                    catch (error: Exception) { input.close(); throw error }
                opened = true
                ClipStreamCopy.copy(input, output, expected, { t.cancelled.get() }, t.saveCommit)

            }
            val cleanupMessage = if (result.isFailure && opened) {
                if (runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }.getOrDefault(false)) "目标半成品已删除"
                else "目标半成品未能删除，请在所选位置手动检查"
            } else ""
            main.post {
                if (!current(t, ClipExportPhase.Saving)) return@post
                result.onSuccess {
                    move(t, ClipExportPhase.Saved, "视频已另存；输出流写入及关闭成功")
                    cleanup(t)
                }.onFailure {
                    move(t, ClipExportPhase.Ready, "另存失败：${it.javaClass.simpleName}: ${it.message}；${cleanupMessage}；临时片段保留，可重新另存")
                }
            }
        }
        return true
    }
    private fun sameDocument(source: Uri, destination: Uri): Boolean {
        if (source.normalizeScheme() == destination.normalizeScheme()) return true
        if (source.authority == destination.authority) {
            val a = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()
            val b = runCatching { DocumentsContract.getDocumentId(destination) }.getOrNull()
            if (a != null && a == b) return true
        }
        val a = runCatching { app.contentResolver.canonicalize(source) }.getOrNull()
        val b = runCatching { app.contentResolver.canonicalize(destination) }.getOrNull()
        return a != null && a == b
    }
    fun cancel(reason: String = "用户取消") {
        mainOnly(); val t = task ?: return
        if (state.phase !in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Ready, ClipExportPhase.Saving)) return
        if (state.phase == ClipExportPhase.Saving && !t.saveCommit.cancel()) {
            move(t, ClipExportPhase.Saved, "另存已完成，晚到的取消未撤销目标文件"); cleanup(t); return
        }
        t.cancelled.set(true); stopTicker()
        val cancelled = runCatching { transformer?.cancel(); transformer = null }
        move(t, ClipExportPhase.Cancelled, if (cancelled.isSuccess) "$reason；已停止，清理临时文件" else "$reason；组件取消异常，回收未确认，禁止新导出")
        if (cancelled.isSuccess) cleanup(t) // IO owner waits behind any preflight/save before deleting.
    }
    private fun fail(t: Task, reason: String) {
        if (task !== t || closed || state.phase !in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting)) return
        t.cancelled.set(true); stopTicker()
        val stopped = runCatching { transformer?.cancel(); transformer = null }.isSuccess
        move(t, ClipExportPhase.Failed, reason + if (stopped) "；未生成成片，不自动重试" else "；组件回收未确认，禁止新导出")
        if (stopped) cleanup(t)
    }
    private fun cleanup(t: Task) {
        if (!t.cleanupQueued.compareAndSet(false, true)) return
        files.execute {
            val deleted = !t.file.exists() || t.file.delete()
            processOwner.release(t.id)
            if (!deleted) main.post { if (!closed && task === t) listener?.invoke(state.copy(message = state.message + "；临时文件未删除，留在应用缓存")) }
        }
    }
    private fun stopTicker() { ticker?.let(main::removeCallbacks); ticker = null }
    override fun close() {
        mainOnly(); if (closed) return
        cancel("宿主销毁")
        closed = true; listener = null; gate.close(); files.shutdown()
    }
}
