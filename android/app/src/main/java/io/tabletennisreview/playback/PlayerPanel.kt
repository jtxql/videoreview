package io.tabletennisreview.playback

import android.app.Activity
import android.app.AlertDialog
import io.tabletennisreview.export.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.view.ViewConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.tabletennisreview.input.TourBoxInputEvents
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import io.tabletennisreview.player.*
import java.util.ArrayDeque
import java.util.Locale

/** Host UI only. It has no USB or mpv import and never initializes a controller device. */
class PlayerPanel(private val activity: Activity) : LinearLayout(activity) {
    companion object { private const val PICK = 8101; private const val EXPORT = 8102; private const val SAVE_CLIP = 8103 }
    private val journal = PlayerLog(activity.applicationContext)
    private val clipExporter = ClipExporter(activity.applicationContext, ::onClipState)
    private val clipStatus = TextView(activity).apply { textSize = 12f }
    private val clipStart: Button
    private val clipSave: Button
    private val clipCancel: Button
    private var clipPreparing = false
    private var clipUiEpoch = 0L
    private var clipConfirmation: AlertDialog? = null
    private var clipRequest: ClipExportRequest? = null
    private var lastClipLog: ClipExportState? = null
    private val clipHolding: Boolean get() = clipPreparing || clipConfirmation != null || clipExporter.state.phase in
        listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Ready, ClipExportPhase.Saving)
    private val recent = ArrayDeque<String>()
    private val video = ReviewPlayerView(activity)
    private val controller = video.controller
    private var binding: TourBoxPlayerBinding? = null
    var onFullscreenChanged: (Boolean) -> Unit = {}
    var onDiagnosticsRequested: () -> Unit = {}
    private val diagnosticContent = LinearLayout(activity).apply { orientation = VERTICAL }
    fun diagnosticsView(): View = diagnosticContent
    private var fullscreen = false
    private val fullSwitch = CheckBox(activity).apply { text = "全屏" }
    private val progress = AbMarkedSeekBar(activity).apply { max = PlayerInteractionPolicy.PROGRESS_MAX }
    private val progressLabel = TextView(activity).apply { textSize = 13f }
    private val reviewSummary = TextView(activity).apply {
        textSize = 12f; gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        text = "--×  A:--  B:--  AB:关"
    }
    private val progressContainer = LinearLayout(activity).apply { orientation = VERTICAL; isClickable = true }
    private val progressOverlay = PlayerProgressOverlay()
    private val hud = TextView(activity).apply {
        textSize = 14f; setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(0x99000000.toInt()); setPadding(8, 4, 8, 4)
        isClickable = false; isFocusable = false
    }
    private val audio = activity.getSystemService(AudioManager::class.java)
    private var verticalGesture: PlayerVerticalGesture? = null
    private var volumeBase = 0
    private var lastVolume = -1
    private var volumeFailed = false
    private var touchSession = 0L
    private var touchValid = false
    private var gestures: GestureDetector? = null
    private var scaling: ScaleGestureDetector? = null
    private var multiTouch = false
    private var panning = false
    private var panLastX = 0f
    private var panLastY = 0f
    private var panStartX = 0f
    private var panStartY = 0f
    private var viewportSession = 0L
    private var lastPlayerError: String? = null
    private val resetViewport = Button(activity).apply { text = "恢复画面"; visibility = View.GONE }
    private var scrubbing = false
    private var pendingAutoLoad = false
    private var choosingDocument = false
    private val tourBoxStatus = TextView(activity).apply { textSize = 12f }
    private var usbSession: Long? = null
    private var usbAuthorized = false
    private val controlsScroll: ScrollView
    private val logScroll: ScrollView
    private val title: TextView
    private val touchOverlay: View
    private val mappingStatus = TextView(activity).apply { textSize = 12f }
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
        title = TextView(activity).apply { text = "TableTennis Review · 本地复盘"; textSize = 18f }
        diagnosticContent.addView(title)
        diagnosticContent.addView(status); diagnosticContent.addView(loopStatus)
        addView(video, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val progressHeader = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        progressHeader.addView(progressLabel, LayoutParams(-2, -2))
        progressHeader.addView(reviewSummary, LayoutParams(0, -2, 1f).apply { marginStart = (8 * resources.displayMetrics.density).toInt() })
        progressContainer.addView(progressHeader); progressContainer.addView(progress)
        addView(progressContainer)
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; binding?.cancelPending("进度拖动") }
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                if (user) controller.state.duration?.takeIf { it.isFinite() && it > 0 }?.let {
                    progressLabel.text = "跳转到 ${seconds(PlayerInteractionPolicy.position(value, it))} / ${seconds(it)}"
                }
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                controller.state.duration?.takeIf { it.isFinite() && it > 0 }?.let {
                    submit(PlayerCommand.SeekAbsolute(PlayerInteractionPolicy.position(bar.progress, it)))
                }
            }
        })
        touchOverlay = object : View(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean = handleVideoTouch(event)
            override fun performClick(): Boolean {
                super.performClick()
                progressOverlay.tap(); updateProgressVisibility()
                return true
            }
        }.apply {
            visibility = View.GONE; isClickable = true
            contentDescription = "视频：双指缩放1到4倍，放大后单指拖动；双击左中右跳转或播放；原大小横屏左右上下来调速度和音量"
        }
        video.addView(touchOverlay, FrameLayout.LayoutParams(-1, -1))
        gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { touchOverlay.performClick(); return true }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (touchOverlay.width > 0) runCatching {
                    PlayerInteractionPolicy.doubleTap(event.x.toDouble(), touchOverlay.width.toDouble())
                }.onSuccess(::submit).onFailure { record("双击区域无效，未执行动作") }
                return true
            }
            // Video seeking stays double-tap only; vertical controls are handled separately.
        }).apply { setIsLongpressEnabled(false) }
        scaling = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = foreground && pageVisible && controller.state.loaded
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                video.zoom(detector.scaleFactor.toDouble(), detector.focusX.toDouble(), detector.focusY.toDouble())
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        video.addView(resetViewport, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START))
        resetViewport.setOnClickListener { video.resetViewport(); record("VIEWPORT RESET") }
        video.onViewportChanged = {
            resetViewport.visibility = if (video.viewport.enlarged) View.VISIBLE else View.GONE
            resetViewport.text = String.format(Locale.US, "恢复画面 %.1f×", video.viewport.scale)
        }
        video.addView(hud, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            val margin = (8 * resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, 0)
        })
        val controls = LinearLayout(activity).apply { orientation = VERTICAL }
        controlsScroll = ScrollView(activity).apply { addView(controls) }
        addView(controlsScroll, LayoutParams(-1, -2))
        fullSwitch.setOnCheckedChangeListener { _, checked -> setFullscreen(checked) }
        fun row() = LinearLayout(activity).also { controls.addView(it) }
        fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(activity).apply {
            text = label; textSize = 13f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(2, 0, 2, 0); maxLines = 1
            setOnClickListener { action() }
            parent.addView(this, LayoutParams(0, compactButtonHeight(), 1f))
        }
        fun command(parent: LinearLayout, label: String, value: PlayerCommand) {
            actions += button(parent, label) { submit(value) }
        }
        val tools = row()
        tools.addView(fullSwitch, LayoutParams(0, compactButtonHeight(), 1f))
        val file = row()
        choose = button(file, "选择视频") { pick() }
        initialize = button(file, "重新载入") {
            val uri = selected
            pendingAutoLoad = false
            suspendMapping()
            if (uri != null) admission(controller.initialize(uri.toString()))
        }
        release = button(file, "释放") { pendingAutoLoad = false; suspendMapping(); controller.release("用户释放") }
        diagnosticContent.addView(tourBoxStatus); diagnosticContent.addView(mappingStatus)
        val playback = row()
        command(playback, "播放", PlayerCommand.SetPaused(false))
        command(playback, "暂停", PlayerCommand.SetPaused(true))
        command(playback, "快退5秒", PlayerCommand.SeekRelative(-5.0))
        command(playback, "快进5秒", PlayerCommand.SeekRelative(5.0))
        val frames = row()
        command(frames, "后退一帧", PlayerCommand.StepBackward)
        command(frames, "前进一帧", PlayerCommand.StepForward)

        val speeds = row()
        for (speed in PlayerPolicy.speeds) command(speeds, "${speed}×", PlayerCommand.SetSpeed(speed))
        val ab = row()
        command(ab, "记A", PlayerCommand.MarkA); command(ab, "记B", PlayerCommand.MarkB)
        command(ab, "启用AB", PlayerCommand.EnableLoop); command(ab, "清除AB", PlayerCommand.ClearLoop)
        val clips = row()
        clipStart = button(clips, "导出A/B慢动作") { prepareClip() }
        clipSave = button(clips, "另存视频") { saveClipPicker() }
        clipCancel = button(clips, "取消/丢弃片段") { cancelClip() }
        controls.addView(clipStatus)
        clipStatus.text = "标记A/B后可按当前倍速导出；生成后需另存"
        clipStatus.visibility = View.GONE
        button(row(), "USB诊断") { onDiagnosticsRequested() }
        val logs = LinearLayout(activity).also { diagnosticContent.addView(it) }
        button(logs, "添加标记") { record("MARK session=${controller.state.sessionId} ${controller.state}") }
        button(logs, "复制摘要") {
            (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("播放器日志摘要", recent.joinToString("\n")))
            record("复制最近100条摘要；完整记录请导出")
        }
        button(logs, "导出播放器日志") { export() }
        logScroll = ScrollView(activity).apply { addView(logView) }
        diagnosticContent.addView(logScroll, LayoutParams(-1, (160 * resources.displayMetrics.density).toInt()))
        updateProgressLayout()
        controller.setLogListener(::record)
        controller.setStateListener { value ->
            if (value.sessionId != viewportSession) {
                viewportSession = value.sessionId; touchValid = false; video.resetViewport()
            }
            if (value.phase == PlayerPhase.Error && value.message != lastPlayerError) {
                lastPlayerError = value.message
                Toast.makeText(activity, "播放失败，请打开USB诊断查看详情", Toast.LENGTH_LONG).show()
            } else if (value.phase != PlayerPhase.Error) lastPlayerError = null
            status.text = "${value.phase} ${seconds(value.position)} / ${seconds(value.duration)}  ${value.speed?.toString() ?: "未知"}×\n${value.message}"
            hud.text = PlayerInteractionPolicy.hud(value.speed, value.position, value.duration)
            fun compactTime(time: Double?) = time?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
            val rate = value.speed?.takeIf { it.isFinite() && it > 0 }?.let { String.format(Locale.US, "%s×", java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()) } ?: "--×"
            progress.setMarkers(value.loop.a, value.loop.b, value.duration)
            reviewSummary.text = "$rate  A:${compactTime(value.loop.a)}  B:${compactTime(value.loop.b)}  AB:${if (value.loop.active) "开" else "关"}"
            loopStatus.text = "A=${seconds(value.loop.a)} B=${seconds(value.loop.b)} AB=${if (value.loop.active) "启用" else "关闭"} seekable=${value.seekable}（控件区可滑动）"
            if (pageVisible && foreground && value.phase == PlayerPhase.Playing) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding?.updateTarget(value, foreground && pageVisible)
            if (!scrubbing) {
                progress.progress = PlayerInteractionPolicy.progress(value.position, value.duration)
                progressLabel.text = "${seconds(value.position)} / ${seconds(value.duration)}"
            }
            syncMapping()
            refresh()
            maybeAutoLoad()
        }
        record("PLAYER PANEL created; libmpvKt0.3.0; TourBox auto-init/default mapping; selected video auto-loads paused")
    }

    fun setVersionLabel(value: String) { title.text = "TableTennis Review $value" }

    fun bindInput(events: TourBoxInputEvents) {
        check(binding == null)
        val handler = Handler(Looper.getMainLooper())
        binding = TourBoxPlayerBinding(events, controller, Executor { task ->
            if (!handler.post(task)) throw RejectedExecutionException("UI handler stopped")
        }, { SystemClock.elapsedRealtimeNanos() }, { journal.append(it) }, { value ->
            if (!destroyed) {
                mappingStatus.text = "接纳=${value.admitted} 待办=${value.pending} 取消=${value.cancelled} 拒绝=${value.rejected} 忽略=${value.ignored}\n${value.fault ?: value.notice ?: "Knob逐帧/按下播放；Scroll±5秒/按下切速；C1记A/C2记B/Top切AB"}"
            }
        }, { journal.failure.get() == null })
        binding?.approveUsbSession(usbSession)
        binding?.updateTarget(controller.state, foreground && pageVisible)
    }
    fun setTourBoxStatus(value: String) { if (tourBoxStatus.text.toString() != value) tourBoxStatus.text = value }
    fun setTourBoxState(authorized: Boolean, session: Long?) {
        val changed = usbSession != session
        usbAuthorized = authorized
        usbSession = session
        if (changed) binding?.approveUsbSession(session)
        if (changed && session != null) binding?.resetFault()
        if (!authorized) binding?.setEnabled(false)
        syncMapping()
        refresh() // Authorization callbacks must refresh even when session is still null.
    }
    private fun syncMapping() {
        val bridge = binding ?: return
        if (!clipHolding && TourBoxControlUiPolicy.automatic(usbAuthorized, usbSession != null, bridge.status().fault != null) &&
            foreground && pageVisible && controller.state.loaded && !bridge.status().enabled) bridge.setEnabled(true)
    }
    private fun suspendMapping() { binding?.setEnabled(false) }
    private fun maybeAutoLoad() {
        if (!pendingAutoLoad || clipHolding || choosingDocument || destroyed || !PlayerPolicy.canInitialize(selected != null, foreground, pageVisible, controller.state)) return
        pendingAutoLoad = false
        record("AUTO LOAD selected local video; initial pause=yes; first frame requires visual verification")
        admission(controller.initialize(checkNotNull(selected).toString()))
    }
    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        val visibility = if (value) View.GONE else View.VISIBLE
        listOf(controlsScroll).forEach { it.visibility = visibility }
        updateProgressLayout()
        setPadding(if (value) 0 else 12, if (value) 0 else 8, if (value) 0 else 12, if (value) 0 else 8)
        onFullscreenChanged(value)
        record("FULLSCREEN=$value; no USB/session recreation requested")
    }
    fun exitFullscreen(): Boolean {
        if (!fullscreen) return false
        fullSwitch.isChecked = false
        return true
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        fun resizeButtons(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Button) child.layoutParams = child.layoutParams.apply { height = compactButtonHeight() }
                if (child is ViewGroup) resizeButtons(child)
            }
        }
        resizeButtons(controlsScroll)
        updateProgressLayout()
    }
    private fun handleVideoTouch(event: MotionEvent): Boolean {
        val landscape = fullscreen || resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            verticalGesture = null; volumeFailed = false; touchValid = true
            multiTouch = false; panning = false
            panLastX = event.x; panLastY = event.y; panStartX = event.x; panStartY = event.y
            touchSession = controller.state.sessionId
            if (!video.viewport.enlarged && landscape && foreground && pageVisible && touchOverlay.width > 0) {
                verticalGesture = PlayerVerticalGesture(event.x.toDouble(), event.y.toDouble(), touchOverlay.width.toDouble(),
                    48.0 * resources.displayMetrics.density, ViewConfiguration.get(activity).scaledTouchSlop.toDouble())
                runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
                    .onSuccess { volumeBase = it; lastVolume = it }
                    .onFailure { volumeFailed = true; record("读取媒体音量失败：${it.message}") }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || !foreground || !pageVisible || touchSession != controller.state.sessionId) {
            verticalGesture = null; touchValid = false
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL; gestures?.onTouchEvent(cancel); cancel.recycle()
            }
            return true
        }
        if (!touchValid) return true
        if (event.pointerCount > 1) {
            multiTouch = true; verticalGesture = null
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL; gestures?.onTouchEvent(cancel); cancel.recycle()
            }
        }
        scaling?.onTouchEvent(event)
        // A pinch owns its entire stream, including the remaining finger and final UP.
        if (multiTouch) return true
        if (video.viewport.enlarged) {
            verticalGesture = null
            val slop = ViewConfiguration.get(activity).scaledTouchSlop
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                (panning || kotlin.math.hypot(event.x - panStartX, event.y - panStartY) > slop)) {
                if (!panning) MotionEvent.obtain(event).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL; gestures?.onTouchEvent(cancel); cancel.recycle()
                }
                panning = true
                video.pan((event.x - panLastX).toDouble(), (event.y - panLastY).toDouble())
            }
            panLastX = event.x; panLastY = event.y
            if (!panning) gestures?.onTouchEvent(event)
            return true
        }
        if (!landscape) verticalGesture = null
        val change = if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
            verticalGesture?.update(event.x.toDouble(), event.y.toDouble()) else null
        if (change != null) {
            // Cancel tap recognition even when the last motion arrives only as UP.
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL; gestures?.onTouchEvent(cancel); cancel.recycle()
            }
            if (change.control == VerticalControl.Volume && !volumeFailed) adjustVolume(change.steps)
            if (event.actionMasked == MotionEvent.ACTION_UP && change.control == VerticalControl.Speed && change.steps != 0)
                submit(PlayerCommand.ShiftSpeed(change.steps.coerceIn(-4, 4)))
        } else gestures?.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) verticalGesture = null
        return true
    }
    private fun adjustVolume(steps: Int) {
        try {
            check(!audio.isVolumeFixed) { "当前设备媒体音量固定" }
            val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val value = (volumeBase + steps).coerceIn(minimum, maximum)
            if (value != lastVolume) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
                lastVolume = value
                record("MEDIA VOLUME requested=$value max=$maximum；系统媒体音量")
            }
        } catch (error: Exception) {
            volumeFailed = true; record("媒体音量调整失败：${error.javaClass.simpleName}: ${error.message}")
            Toast.makeText(activity, "无法调整媒体音量", Toast.LENGTH_SHORT).show()
        }
    }
    private fun updateProgressLayout() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        progressOverlay.layout(landscape, fullscreen)
        hud.visibility = if (landscape || fullscreen) View.VISIBLE else View.GONE
        hud.text = PlayerInteractionPolicy.hud(controller.state.speed, controller.state.position, controller.state.duration)
        val parent = if (progressOverlay.overlay) video else this
        if (progressContainer.parent !== parent) {
            (progressContainer.parent as? ViewGroup)?.removeView(progressContainer)
            if (progressOverlay.overlay) {
                val margin = (12 * resources.displayMetrics.density).toInt()
                video.addView(progressContainer, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply {
                    setMargins(margin, 0, margin, margin)
                })
            } else addView(progressContainer, indexOfChild(video) + 1, LayoutParams(-1, -2))
        }
        progressContainer.setBackgroundColor(if (progressOverlay.overlay) 0xB0000000.toInt() else android.graphics.Color.TRANSPARENT)
        progressLabel.setTextColor(if (progressOverlay.overlay) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        reviewSummary.setTextColor(if (progressOverlay.overlay) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        touchOverlay.visibility = View.VISIBLE // Same double-tap zones in portrait, landscape and fullscreen.
        updateProgressVisibility()
    }
    private fun updateProgressVisibility() {
        progressContainer.visibility = if (progressOverlay.visible) View.VISIBLE else View.GONE
    }
    private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3fs", it) } ?: "未知"
    private fun admission(value: CommandAdmission) {
        if (value is CommandAdmission.Rejected) {
            record("REJECT ${value.reason}")
            Toast.makeText(activity, value.reason, Toast.LENGTH_SHORT).show()
        }
    }
    private fun submit(command: PlayerCommand) {
        if (!foreground || !pageVisible || clipHolding) return
        if (journal.failure.get() != null) { record("播放器日志写入失败，请停止测试并保留证据"); return }
        binding?.cancelPending("屏幕操作优先")
        admission(controller.execute(command))
    }
    private fun compactButtonHeight(): Int = ((if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 36 else 44) * resources.displayMetrics.density).toInt()
    private fun refresh() {
        clipStatus.visibility = if (clipHolding) View.VISIBLE else View.GONE
        val value = controller.state
        progress.isEnabled = !clipHolding && foreground && pageVisible && value.loaded && !value.busy && value.seekable && (value.duration?.let { it.isFinite() && it > 0 } == true)
        choose.isEnabled = !clipHolding && value.phase != PlayerPhase.Initializing && !value.busy
        initialize.isEnabled = !clipHolding && PlayerPolicy.canInitialize(selected != null, foreground, pageVisible, value)
        release.isEnabled = !clipHolding && (value.sessionId != 0L || value.busy)
        actions.forEach { it.isEnabled = !clipHolding && foreground && pageVisible && value.loaded && !value.busy }
        clipStart.isEnabled = !clipHolding && foreground && pageVisible && value.loaded && !value.busy && selected != null &&
            runCatching { value.loop.enabled(value.duration) }.isSuccess
        clipSave.isEnabled = foreground && clipExporter.state.phase == ClipExportPhase.Ready
        clipCancel.isEnabled = clipHolding
        val exportBusy = clipExporter.state.phase in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Saving)
        if (foreground && (exportBusy || (pageVisible && value.phase == PlayerPhase.Playing))) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun prepareClip() {
        val source = selected ?: return
        if (clipHolding || !foreground || !pageVisible || journal.failure.get() != null) return
        val uiToken = ++clipUiEpoch
        clipPreparing = true; clipStatus.text = "正在暂停并冻结A/B与实际倍速"; suspendMapping(); refresh()
        val admission = controller.prepareExport { preparation ->
            if (destroyed || !clipPreparing || uiToken != clipUiEpoch || !foreground || selected != source) return@prepareExport
            when (preparation) {
                is ExportPreparation.Rejected -> {
                    clipPreparing = false; clipStatus.text = preparation.reason; record(preparation.reason)
                    syncMapping(); refresh()
                }
                is ExportPreparation.Ready -> {
                    try {
                        val frozen = preparation.snapshot
                        check(controller.state.sessionId == frozen.sessionId)
                        val request = ClipExportRequest.fromSeconds(frozen.sessionId, source.toString(), frozen.a, frozen.b, frozen.speed, true)
                        val audioChoice = CheckBox(activity).apply { text = "保留源音频并同步变速（无音轨则无声）；取消勾选为静音"; isChecked = true }
                        val dialog = AlertDialog.Builder(activity).setTitle("导出A/B慢动作")
                            .setMessage("A=${seconds(frozen.a)} B=${seconds(frozen.b)}\n倍速=${frozen.speed}× 预计成片=${seconds(request.expectedDurationUs / 1_000_000.0)}\nMP4 H.264/AAC，原分辨率优先；首版暂不支持HDR。\n声音音调/帧边界须实际确认。请保持前台，生成后点击另存。")
                            .setView(audioChoice).setNegativeButton("取消", null)
                            .setPositiveButton("开始生成") { _, _ ->
                                clipConfirmation = null; clipPreparing = true
                                if (!foreground || selected != source || controller.state.sessionId != frozen.sessionId || journal.failure.get() != null) {
                                    clipStatus.text = "来源会话/日志已变化，未开始导出"
                                } else {
                                    clipRequest = request.copy(keepAudio = audioChoice.isChecked)
                                    record("CLIP REQUEST session=${request.sourceSession} A_us=${request.startUs} B_us=${request.endUs} speed=${request.speed} audio=${audioChoice.isChecked}")
                                    if (!clipExporter.start(checkNotNull(clipRequest))) clipStatus.text = clipExporter.state.message
                                }
                                clipPreparing = false; syncMapping(); refresh()
                            }.create()
                        clipConfirmation = dialog; clipPreparing = false
                        dialog.setOnDismissListener {
                            if (clipConfirmation === dialog) { clipConfirmation = null; syncMapping(); refresh() }
                        }
                        dialog.show(); refresh()
                    } catch (error: Exception) {
                        clipPreparing = false; clipConfirmation = null; clipStatus.text = "无法准备导出：${error.message}"
                        record(clipStatus.text.toString()); syncMapping(); refresh()
                    }
                }
            }
        }
        if (admission is CommandAdmission.Rejected) {
            clipPreparing = false; clipStatus.text = admission.reason; record("CLIP REJECT ${admission.reason}")
            syncMapping(); refresh()
        }
    }
    private fun onClipState(value: ClipExportState) {
        if (destroyed) return
        val phase = when (value.phase) {
            ClipExportPhase.Idle -> "尚未导出"
            ClipExportPhase.Preparing -> "准备中"
            ClipExportPhase.Exporting -> "生成中"
            ClipExportPhase.Ready -> "待另存"
            ClipExportPhase.Saving -> "保存中"
            ClipExportPhase.Saved -> "已保存"
            ClipExportPhase.Cancelled -> "已取消"
            ClipExportPhase.Failed -> "导出失败"
        }
        clipStatus.text = "$phase ${value.progress?.let { "$it%" } ?: ""}"
        if (value != lastClipLog) {
            lastClipLog = value
            record("CLIP id=${value.id} phase=${value.phase} progress=${value.progress} ${value.message} durationUs=${value.actualDurationUs}")
            if (value.phase == ClipExportPhase.Failed) Toast.makeText(activity, "导出失败：${value.message}", Toast.LENGTH_LONG).show()
            if (value.phase == ClipExportPhase.Saved) Toast.makeText(activity, "视频已保存", Toast.LENGTH_SHORT).show()
        }
        if (value.phase in listOf(ClipExportPhase.Saved, ClipExportPhase.Cancelled, ClipExportPhase.Failed)) syncMapping()
        refresh(); maybeAutoLoad()
    }
    private fun cancelClip() {
        clipUiEpoch++; clipPreparing = false
        clipConfirmation?.dismiss(); clipConfirmation = null
        clipExporter.cancel("用户取消/丢弃")
        syncMapping(); refresh(); maybeAutoLoad()
    }
    @Suppress("DEPRECATION")
    private fun saveClipPicker() {
        if (!foreground || clipExporter.state.phase != ClipExportPhase.Ready) return
        runCatching {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "video/mp4"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "review-ab-${System.currentTimeMillis()}-${clipRequest?.speed ?: 1.0}x.mp4")
            }, SAVE_CLIP)
        }.onFailure { clipStatus.text = "无法打开保存选择器：${it.message}；临时片段保留"; record(clipStatus.text.toString()) }
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
        choosingDocument = true
        runCatching { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK) }.onFailure { choosingDocument = false; record("选片失败 ${it.message}") }
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
        if (requestCode == SAVE_CLIP) {
            val destination = data?.data
            if (resultCode == Activity.RESULT_OK && destination != null) {
                if (!clipExporter.save(destination)) clipStatus.text = "无法开始另存：${clipExporter.state.message}"
            } else { clipStatus.text = "保存已取消，临时片段仍在；可另存或丢弃"; record(clipStatus.text.toString()) }
            refresh(); return true
        }
        if (requestCode != PICK && requestCode != EXPORT) return false
        if (requestCode == PICK) choosingDocument = false
        if (resultCode != Activity.RESULT_OK) { record("选片/导出已取消，不自动恢复播放"); return true }
        val uri = data?.data ?: return true
        if (requestCode == PICK) {
            if (uri.scheme != "content") { record("拒绝非content文档"); return true }
            if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0 && (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onFailure { record("持久授权未取得 ${it.javaClass.simpleName}；临时权限另行验证") }
            }
            pendingAutoLoad = false
            suspendMapping()
            controller.release("选择新文件")
            video.resetViewport()
            selected = uri
            pendingAutoLoad = true
            record("已选择本地文档，自动初始化并保持暂停（不记录完整URI）")
            maybeAutoLoad()
            refresh() // Required after replacing selected; covers the first-pick regression.
        } else {
            journal.export(activity.applicationContext.contentResolver, uri) { message ->
                activity.runOnUiThread { if (!destroyed) record(message) }
            }
        }
        return true
    }
    fun restoreState(bundle: Bundle?) { selected = bundle?.getString("review.selectedUri")?.let(Uri::parse); pendingAutoLoad = selected != null; refresh() }
    fun saveState(bundle: Bundle) { selected?.let { bundle.putString("review.selectedUri", it.toString()) } }
    fun onHostStart() { foreground = true; binding?.updateTarget(controller.state, pageVisible); record("HOST START; selected video auto-loads paused"); syncMapping(); maybeAutoLoad(); refresh() }
    fun onHostStop() {
        foreground = false; verticalGesture = null; clipUiEpoch++; clipPreparing = false
        clipConfirmation?.dismiss(); clipConfirmation = null
        if (clipExporter.state.phase in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Saving)) clipExporter.cancel("应用进入后台")
        suspendMapping(); pendingAutoLoad = selected != null
        binding?.updateTarget(controller.state, false); controller.release("宿主进入后台"); refresh()
    }
    fun setPageVisible(value: Boolean) {
        val leaving = !value && pageVisible
        pageVisible = value
        binding?.updateTarget(controller.state, foreground && pageVisible)
        if (!value) suspendMapping()
        if (leaving && !clipHolding) controller.pauseForPageChange()
        if (value) { syncMapping(); maybeAutoLoad() }
        if (!value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()
    }
    fun close() {
        if (destroyed) return
        destroyed = true; foreground = false; clipPreparing = false
        clipUiEpoch++; clipConfirmation?.dismiss(); clipConfirmation = null
        clipExporter.close()
        binding?.close(); binding = null
        controller.close(); destroyed = true; journal.shutdown()
    }
}
