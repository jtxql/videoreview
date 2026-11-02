package io.tabletennisreview.playback

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import io.tabletennisreview.player.*
import java.util.ArrayDeque
import java.util.Locale

/** Host UI only. It has no USB or mpv import and never initializes a controller device. */
class PlayerPanel(private val activity: Activity) : LinearLayout(activity) {
    companion object { private const val PICK = 8101; private const val EXPORT = 8102 }
    private val journal = PlayerLog(activity.applicationContext)
    private val recent = ArrayDeque<String>()
    private val video = ReviewPlayerView(activity)
    private val controller = video.controller
    private val status = TextView(activity)
    private val loopStatus = TextView(activity)
    private val logView = TextView(activity).apply { textSize = 11f; setTextIsSelectable(true) }
    private val initialize: Button
    private val choose: Button
    private val release: Button
    private val actions = mutableListOf<Button>()
    private var selected: Uri? = null
    private var foreground = false
    private var pageVisible = true
    private var destroyed = false

    init {
        orientation = VERTICAL
        setPadding(12, 8, 12, 8)
        addView(TextView(activity).apply { text = "TableTennis Review · 本地复盘"; textSize = 18f })
        addView(status); addView(loopStatus)
        addView(video, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(activity).apply { orientation = VERTICAL }
        val fraction = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0.3f else 0.4f
        val heightDp = (resources.configuration.screenHeightDp * fraction).toInt().coerceIn(100, 300)
        addView(ScrollView(activity).apply { addView(controls) }, LayoutParams(-1, (heightDp * resources.displayMetrics.density).toInt()))
        fun row() = LinearLayout(activity).also { controls.addView(it) }
        fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(activity).apply {
            text = label; setOnClickListener { action() }; parent.addView(this, LayoutParams(0, -2, 1f))
        }
        fun command(parent: LinearLayout, label: String, value: PlayerCommand) {
            actions += button(parent, label) { submit(value) }
        }
        val file = row()
        choose = button(file, "选择视频") { pick() }
        initialize = button(file, "初始化/重新载入") {
            val uri = selected
            if (uri != null) admission(controller.initialize(uri.toString()))
        }
        release = button(file, "释放") { controller.release("用户释放") }
        val playback = row()
        command(playback, "播放", PlayerCommand.SetPaused(false))
        command(playback, "暂停", PlayerCommand.SetPaused(true))
        command(playback, "快退5秒", PlayerCommand.SeekRelative(-5.0))
        command(playback, "快进5秒", PlayerCommand.SeekRelative(5.0))
        val frames = row()
        command(frames, "后退一帧", PlayerCommand.StepBackward)
        command(frames, "前进一帧", PlayerCommand.StepForward)
        command(frames, "回A", PlayerCommand.SeekA)
        val speeds = row()
        for (speed in PlayerPolicy.speeds) command(speeds, "${speed}×", PlayerCommand.SetSpeed(speed))
        val ab = row()
        command(ab, "记A", PlayerCommand.MarkA); command(ab, "记B", PlayerCommand.MarkB)
        command(ab, "启用AB", PlayerCommand.EnableLoop); command(ab, "清除AB", PlayerCommand.ClearLoop)
        val logs = row()
        button(logs, "添加标记") { record("MARK session=${controller.state.sessionId} ${controller.state}") }
        button(logs, "复制摘要") {
            (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("播放器日志摘要", recent.joinToString("\n")))
            record("复制最近100条摘要；完整记录请导出")
        }
        button(logs, "导出播放器日志") { export() }
        addView(ScrollView(activity).apply { addView(logView) }, LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        controller.setLogListener(::record)
        controller.setStateListener { value ->
            status.text = "${value.phase} ${seconds(value.position)} / ${seconds(value.duration)}  ${value.speed?.toString() ?: "未知"}×\n${value.message}"
            loopStatus.text = "A=${seconds(value.loop.a)} B=${seconds(value.loop.b)} AB=${if (value.loop.active) "启用" else "关闭"} seekable=${value.seekable}（控件区可滑动）"
            if (pageVisible && foreground && value.phase == PlayerPhase.Playing) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            refresh()
        }
        record("PLAYER PANEL created; libmpvKt0.3.0; no TourBox mapping or automatic playback")
    }

    private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3fs", it) } ?: "未知"
    private fun admission(value: CommandAdmission) { if (value is CommandAdmission.Rejected) record("REJECT ${value.reason}") }
    private fun submit(command: PlayerCommand) {
        if (!foreground || !pageVisible) return
        if (journal.failure.get() != null) { record("播放器日志写入失败，请停止测试并保留证据"); return }
        admission(controller.execute(command))
    }
    private fun refresh() {
        val value = controller.state
        choose.isEnabled = value.phase != PlayerPhase.Initializing && !value.busy
        initialize.isEnabled = PlayerPolicy.canInitialize(selected != null, foreground, pageVisible, value)
        release.isEnabled = value.sessionId != 0L || value.busy
        actions.forEach { it.isEnabled = foreground && pageVisible && value.loaded && !value.busy }
    }
    private fun record(message: String) {
        journal.append(message)
        if (destroyed) return
        recent.addLast("${java.time.LocalTime.now()} $message")
        while (recent.size > 100) recent.removeFirst()
        logView.text = recent.joinToString("\n")
        journal.failure.get()?.let { status.text = "诊断文件写入失败：$it" }
    }
    @Suppress("DEPRECATION")
    private fun pick() {
        runCatching { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK) }.onFailure { record("选片失败 ${it.message}") }
    }
    @Suppress("DEPRECATION")
    private fun export() {
        record("EXPORT requested; picker may background Activity and release player/stop USB")
        runCatching { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "review-player-${System.currentTimeMillis()}.txt")
        }, EXPORT) }.onFailure { record("导出选择器失败 ${it.message}") }
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK && requestCode != EXPORT) return false
        if (resultCode != Activity.RESULT_OK) { record("选片/导出已取消，不自动恢复播放"); return true }
        val uri = data?.data ?: return true
        if (requestCode == PICK) {
            if (uri.scheme != "content") { record("拒绝非content文档"); return true }
            if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0 && (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onFailure { record("持久授权未取得 ${it.javaClass.simpleName}；临时权限另行验证") }
            }
            controller.release("选择新文件")
            selected = uri
            record("已选择本地文档，请手动初始化（不记录完整URI）")
            refresh() // Required after replacing selected; covers the first-pick regression.
        } else {
            journal.export(activity.applicationContext.contentResolver, uri) { message ->
                activity.runOnUiThread { if (!destroyed) record(message) }
            }
        }
        return true
    }
    fun restoreState(bundle: Bundle?) { selected = bundle?.getString("review.selectedUri")?.let(Uri::parse); refresh() }
    fun saveState(bundle: Bundle) { selected?.let { bundle.putString("review.selectedUri", it.toString()) } }
    fun onHostStart() { foreground = true; record("HOST START; no automatic initialize/playback"); refresh() }
    fun onHostStop() { foreground = false; controller.release("宿主进入后台"); refresh() }
    fun setPageVisible(value: Boolean) {
        if (!value && pageVisible) controller.pauseForPageChange()
        pageVisible = value
        if (!value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()
    }
    fun close() {
        if (destroyed) return
        controller.close(); destroyed = true; journal.shutdown()
    }
}
