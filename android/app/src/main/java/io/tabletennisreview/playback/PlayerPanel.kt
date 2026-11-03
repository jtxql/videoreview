package io.tabletennisreview.playback

import android.app.Activity
import android.app.AlertDialog
import io.tabletennisreview.export.*
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
import java.util.Locale

/** Host UI only. It has no USB or mpv import and never initializes a controller device. */
class PlayerPanel(private val activity: Activity) : LinearLayout(activity) {
    companion object { private const val PICK = 8101; private const val SAVE_CLIP = 8103 }
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
    private val video = ReviewPlayerView(activity)
    private val controller = video.controller
    private val shotGate = ShotSessionGate()
    private val shotCapture = ViewportCapture(video)
    private var shotEditor: ScreenshotEditor? = null
    private var shotSourceSession = 0L
    private var reloadPaused = false
    private val shotHolding: Boolean get() = shotGate.phase != ShotPhase.Idle || shotEditor != null
    private val bookmarkStore = ClipBookmarkStore.get(activity.applicationContext)
    private var bookmarkLease: ClipBookmarkSession? = null
    private var bookmarkDialog: ClipBookmarkDialog? = null
    private val bookmarkHolding: Boolean get() = bookmarkLease != null
    private val bookmarkEntry: Button
    private val floatingBookmark = Button(activity).apply { text = "片段书签"; textSize = 12f; visibility = View.GONE }
    private val controlHolding: Boolean get() = clipHolding || shotHolding || bookmarkHolding
    private val settingsEntry: Button
    private val screenshotEntry: Button
    private val floatingShot = Button(activity).apply { text = "截图"; textSize = 12f; visibility = View.GONE }
    private var binding: TourBoxPlayerBinding? = null
    var onFullscreenChanged: (Boolean) -> Unit = {}
    var onSettingsRequested: () -> Unit = {}
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
    private var usbSession: Long? = null
    private var usbAuthorized = false
    private val controlsScroll: ScrollView
    private val touchOverlay: View
    private val choose: Button
    private val playPause: Button
    private val actions = mutableListOf<Button>()
    private var selected: Uri? = null
    private var foreground = false
    private var pageVisible = true
    private var destroyed = false

    init {
        orientation = VERTICAL
        setPadding(12, 8, 12, 8)
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
        choose = button(tools, "选择视频") { pick() }
        val playback = row()
        playPause = button(playback, "播放") { submit(PlayerCommand.TogglePaused) }
        actions += playPause
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
        val extras = row()
        screenshotEntry = button(extras, "截图标注") { takeScreenshot() }
        bookmarkEntry = button(extras, "片段书签") { openBookmarks() }
        settingsEntry = button(extras, "TourBox设置") { if (SettingsUiPolicy.canOpen(controller.state.phase,controller.state.busy,controlHolding)) onSettingsRequested() }
        floatingShot.setOnClickListener { takeScreenshot() }
        video.addView(floatingShot, FrameLayout.LayoutParams((72 * resources.displayMetrics.density).toInt(),
            (40 * resources.displayMetrics.density).toInt(), android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            rightMargin = (8 * resources.displayMetrics.density).toInt(); bottomMargin = (84 * resources.displayMetrics.density).toInt()
        })
        floatingBookmark.setOnClickListener { openBookmarks() }
        video.addView(floatingBookmark,FrameLayout.LayoutParams((88 * resources.displayMetrics.density).toInt(),
            (40 * resources.displayMetrics.density).toInt(),android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            rightMargin = (88 * resources.displayMetrics.density).toInt(); bottomMargin = (84 * resources.displayMetrics.density).toInt()
        })
        updateProgressLayout()
        controller.setLogListener(::record)
        controller.setStateListener { value ->
            if (bookmarkLease?.let { it.source.playerSession != value.sessionId || !value.loaded } == true) cancelBookmarks("播放器会话变化")
            bookmarkDialog?.setPlayerState(value)
            if (shotGate.phase == ShotPhase.Capturing && value.sessionId != shotSourceSession) abortShotCapture("播放器会话变化")
            if (value.sessionId != viewportSession) {
                viewportSession = value.sessionId; touchValid = false; video.resetViewport()
            }
            if (value.phase == PlayerPhase.Error && value.message != lastPlayerError) {
                lastPlayerError = value.message
                Toast.makeText(activity, "播放失败，请重新选择视频后重试", Toast.LENGTH_LONG).show()
            } else if (value.phase != PlayerPhase.Error) lastPlayerError = null
            hud.text = PlayerInteractionPolicy.hud(value.speed, value.position, value.duration)
            fun compactTime(time: Double?) = time?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
            val rate = value.speed?.takeIf { it.isFinite() && it > 0 }?.let { String.format(Locale.US, "%s×", java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()) } ?: "--×"
            progress.setMarkers(value.loop.a, value.loop.b, value.duration)
            reviewSummary.text = "$rate  A:${compactTime(value.loop.a)}  B:${compactTime(value.loop.b)}  AB:${if (value.loop.active) "开" else "关"}"
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
        record("PLAYER PANEL created; libmpvKt0.3.0; TourBox auto-init/default mapping; selected video auto-loads and starts playback")
    }

    private var savedMappings = MappingSnapshot(MappingConfig.defaults(),-1,null)
    fun applyMappings(snapshot: MappingSnapshot) {
        if (destroyed || snapshot.revision < savedMappings.revision) return
        savedMappings = snapshot
        if (snapshot.revision >= 0) binding?.updateMappings(snapshot.config,snapshot.revision)
        else binding?.setEnabled(false)
        syncMapping()
    }

    fun bindInput(events: TourBoxInputEvents) {
        check(binding == null)
        val handler = Handler(Looper.getMainLooper())
        binding = TourBoxPlayerBinding(events, controller, Executor { task ->
            if (!handler.post(task)) throw RejectedExecutionException("UI handler stopped")
        }, { SystemClock.elapsedRealtimeNanos() }, { journal.append(it) }, { _ -> }, { journal.failure.get() == null },
            screenshot = { guard,origin -> takeScreenshot(guard,origin) })
        if (savedMappings.revision >= 0) binding?.updateMappings(savedMappings.config,savedMappings.revision)
        binding?.approveUsbSession(usbSession)
        binding?.updateTarget(controller.state, foreground && pageVisible)
    }
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
        if (savedMappings.revision >= 0 && !controlHolding && TourBoxControlUiPolicy.automatic(usbAuthorized, usbSession != null, bridge.status().fault != null) &&
            foreground && pageVisible && controller.state.loaded && !bridge.status().enabled) bridge.setEnabled(true)
    }
    private fun suspendMapping() { binding?.setEnabled(false) }
    private fun maybeAutoLoad() {
        if (!pendingAutoLoad || controlHolding || choosingDocument || destroyed || !PlayerPolicy.canInitialize(selected != null, foreground, pageVisible, controller.state)) return
        pendingAutoLoad = false
        record("AUTO LOAD selected local video; initial pause=no; playback requires visual verification")
        val uri = checkNotNull(selected).toString()
        admission(if (reloadPaused) controller.initializePaused(uri) else controller.initialize(uri))
        reloadPaused = false
    }
    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        val visibility = if (value) View.GONE else View.VISIBLE
        listOf(controlsScroll).forEach { it.visibility = visibility }
        updateProgressLayout()
        setPadding(if (value) 0 else 12, if (value) 0 else 8, if (value) 0 else 12, if (value) 0 else 8)
        onFullscreenChanged(value)
        refresh()
        record("FULLSCREEN=$value; no USB/session recreation requested")
    }
    fun exitFullscreen(): Boolean {
        if (!fullscreen) return false
        fullSwitch.isChecked = false
        return true
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        abortShotCapture("截图期间方向变化")
        shotEditor?.resize()
        bookmarkDialog?.resize()
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
        if (shotHolding || bookmarkHolding) return true
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
        floatingBookmark.visibility = if (fullscreen && progressOverlay.visible) View.VISIBLE else View.GONE
    }
    private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3fs", it) } ?: "未知"
    private fun admission(value: CommandAdmission) {
        if (value is CommandAdmission.Rejected) {
            record("REJECT ${value.reason}")
            Toast.makeText(activity, value.reason, Toast.LENGTH_SHORT).show()
        }
    }
    private fun submit(command: PlayerCommand) {
        if (!foreground || !pageVisible || controlHolding) return
        if (journal.failure.get() != null) { record("播放器日志写入失败，请停止测试并保留证据"); return }
        binding?.cancelPending("屏幕操作优先")
        admission(controller.execute(command))
    }
    private fun compactButtonHeight(): Int = ((if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 36 else 44) * resources.displayMetrics.density).toInt()
    private fun refresh() {
        clipStatus.visibility = if (clipHolding) View.VISIBLE else View.GONE
        val value = controller.state
        playPause.text = PlaybackButtonUi.label(value.phase)
        settingsEntry.isEnabled = SettingsUiPolicy.canOpen(value.phase,value.busy,controlHolding)
        screenshotEntry.isEnabled = !controlHolding && !scrubbing && foreground && pageVisible && value.loaded && !value.busy
        bookmarkEntry.isEnabled = BookmarkUiPolicy.canOpen(value,foreground,pageVisible,controlHolding,scrubbing,selected != null)
        floatingBookmark.isEnabled = bookmarkEntry.isEnabled
        floatingBookmark.visibility = if (fullscreen && progressOverlay.visible) View.VISIBLE else View.GONE
        floatingShot.isEnabled = screenshotEntry.isEnabled
        floatingShot.visibility = if (fullscreen) View.VISIBLE else View.GONE
        fullSwitch.isEnabled = !controlHolding
        progress.isEnabled = !controlHolding && foreground && pageVisible && value.loaded && !value.busy && value.seekable && (value.duration?.let { it.isFinite() && it > 0 } == true)
        choose.isEnabled = !controlHolding && value.phase != PlayerPhase.Initializing && !value.busy
        actions.forEach { it.isEnabled = !controlHolding && foreground && pageVisible && value.loaded && !value.busy }
        clipStart.isEnabled = !controlHolding && foreground && pageVisible && value.loaded && !value.busy && selected != null &&
            runCatching { value.loop.enabled(value.duration) }.isSuccess
        clipSave.isEnabled = foreground && clipExporter.state.phase == ClipExportPhase.Ready
        clipCancel.isEnabled = clipHolding
        val exportBusy = clipExporter.state.phase in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Saving)
        if (foreground && (exportBusy || (pageVisible && value.phase == PlayerPhase.Playing))) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun openBookmarks() {
        val state = controller.state
        if (!BookmarkUiPolicy.canOpen(state,foreground,pageVisible,controlHolding,scrubbing,selected != null)) return
        val source = BookmarkSource(checkNotNull(selected).toString(),state.sessionId)
        val lease = ClipBookmarkSession(source)
        bookmarkLease = lease; suspendMapping(); verticalGesture = null; touchValid = false
        val window = ClipBookmarkDialog(activity,bookmarkStore,source,
            usable = { bookmarkLease === lease && lease.isOpen(source) && !destroyed && foreground && pageVisible &&
                selected?.toString() == source.videoUri && controller.state.sessionId == source.playerSession },
            onSaveCurrent = { saveCurrentBookmark(lease) }, onRestore = { restoreBookmark(lease,it) },
            onClosed = { if (bookmarkLease === lease) { cancelBookmarks("关闭列表"); syncMapping(); refresh() } })
        bookmarkDialog = window; window.setPlayerState(state)
        val accepted = controller.executeGuarded(PlayerCommand.SetPaused(true),{ lease.isOpen(source) },"bookmark-list")
        if (accepted is CommandAdmission.Rejected) { cancelBookmarks("无法暂停"); admission(accepted); syncMapping(); refresh(); return }
        window.setPlayerState(controller.state); window.show(); refresh()
    }
    private fun saveCurrentBookmark(lease: ClipBookmarkSession) {
        if (bookmarkLease !== lease || controller.state.busy || !foreground || !pageVisible) return
        val source = lease.source; val token = lease.beginSnapshot(); if (token == 0L) return
        val window = bookmarkDialog ?: run { lease.finish(token); return }
        window.setWorking(true)
        fun finished() {
            if (bookmarkLease === lease && lease.finish(token)) { window.setWorking(false); refresh() }
        }
        val accepted = controller.prepareReviewSnapshot({ lease.isCurrent(token,source) }) { result ->
            if (bookmarkLease !== lease || !lease.isCurrent(token,source)) return@prepareReviewSnapshot
            when(result) {
                is ReviewPreparation.Rejected -> { window.showMessage(result.reason); finished() }
                is ReviewPreparation.Ready -> {
                    if (result.snapshot.sessionId != source.playerSession) { window.showMessage("原视频会话已失效"); finished() }
                    else window.promptSave(result.snapshot,{ lease.isCurrent(token,source) },::finished)
                }
            }
        }
        if (accepted is CommandAdmission.Rejected) { window.showMessage(accepted.reason); finished() }
    }
    private fun restoreBookmark(lease: ClipBookmarkSession, selectedItem: ClipBookmark) {
        if (bookmarkLease !== lease || controller.state.busy || !foreground || !pageVisible) return
        val source = lease.source
        val item = bookmarkStore.snapshot().catalog.forVideo(source.videoUri).singleOrNull { it.id == selectedItem.id } ?: return
        val token = lease.beginRestore(); if (token == 0L) return
        val window = bookmarkDialog; bookmarkDialog = null; window?.dispose(false)
        binding?.cancelPending("恢复片段书签"); refresh()
        fun finished(reason: String?) {
            if (bookmarkLease !== lease || !lease.isCurrent(token,source)) return
            lease.finish(token); lease.close(); bookmarkLease = null
            if (reason != null && foreground && !destroyed) Toast.makeText(activity,reason,Toast.LENGTH_LONG).show()
            syncMapping(); refresh()
        }
        val accepted = controller.restoreReview(ReviewPreset(item.a,item.b,item.speed),{ lease.isCurrent(token,source) }) { result ->
            finished((result as? ReviewRestoreResult.Rejected)?.reason)
        }
        if (accepted is CommandAdmission.Rejected) finished(accepted.reason)
    }
    private fun cancelBookmarks(reason: String) {
        val owned = bookmarkLease ?: return
        owned.close(); bookmarkLease = null
        val window = bookmarkDialog; bookmarkDialog = null; window?.dispose(false)
        record("BOOKMARK CLOSE reason=$reason；原视频来源授权失效")
    }
    private fun abortShotCapture(reason: String) {
        if (shotGate.phase != ShotPhase.Capturing) return
        shotGate.end(); shotCapture.cancel(); record("SCREENSHOT CANCEL $reason")
        if (!destroyed) { syncMapping(); refresh() }
    }
    private fun takeScreenshot(authorization: () -> Boolean = { true }, origin: String = "screen"): CommandAdmission {
        val source = selected ?: return CommandAdmission.Rejected("请先选择视频")
        val state = controller.state
        if (destroyed || controlHolding || scrubbing || !foreground || !pageVisible || !state.loaded || state.busy || !runCatching { authorization() }.getOrDefault(false)) return CommandAdmission.Rejected("截图未就绪或来源已失效")
        if (journal.failure.get() != null) { Toast.makeText(activity,"日志异常，截图未开始",Toast.LENGTH_SHORT).show(); return CommandAdmission.Rejected("运行日志异常") }
        val token = shotGate.begin(state.sessionId,video.width,video.height)
        if (token == 0L) return CommandAdmission.Rejected("已有截图操作")
        shotSourceSession = state.sessionId
        suspendMapping(); verticalGesture = null; touchValid = false; refresh()
        record("SCREENSHOT BEGIN session=$shotSourceSession token=$token origin=$origin")
        fun current() = runCatching { authorization() }.getOrDefault(false) && !destroyed && foreground && pageVisible && selected == source &&
            shotGate.matches(token,controller.state.sessionId,video.width,video.height)
        fun failure(reason: String) {
            if (!shotGate.current(token)) return
            shotGate.end(); record("SCREENSHOT FAILED $reason")
            if (foreground && !destroyed) Toast.makeText(activity,reason,Toast.LENGTH_LONG).show()
            if (!destroyed) { syncMapping(); refresh() }
        }
        val admission = controller.prepareScreenshotGuarded(authorization) { prepared ->
            if (!current()) { failure("截图会话已变化"); return@prepareScreenshotGuarded }
            when(prepared) {
                is ScreenshotPreparation.Rejected -> failure(prepared.reason)
                is ScreenshotPreparation.Ready -> {
                    if (prepared.sessionId != shotSourceSession) { failure("截图来源已失效"); return@prepareScreenshotGuarded }
                    if (!shotCapture.capture(::current) { captured ->
                        val bitmap = captured.getOrNull()
                        if (!current()) { bitmap?.recycle(); failure("截图已过期"); return@capture }
                        if (bitmap == null) { failure("截图失败：${captured.exceptionOrNull()?.message}"); return@capture }
                        if (!shotGate.accept(token)) { bitmap.recycle(); return@capture }
                        val image = ShotBitmap(bitmap)
                        try {
                            shotEditor = ScreenshotEditor(activity,image,source,shotGate,token,{
                                if (shotGate.current(token)) {
                                    shotEditor = null; shotGate.end()
                                    if (!destroyed) { syncMapping(); maybeAutoLoad(); refresh() }
                                }
                            },::record).also { it.show() }
                            record("SCREENSHOT EDIT token=$token size=${bitmap.width}x${bitmap.height}")
                        } catch(e: Exception) { image.close(); shotEditor?.dispose(); shotEditor = null; failure("无法打开编辑：${e.message}") }
                        catch(e: OutOfMemoryError) { image.close(); shotEditor?.dispose(); shotEditor = null; failure("编辑图片内存不足") }
                        refresh()
                    }) failure("前次截图仍未回收，请稍后再试")
                }
            }
        }
        if (admission is CommandAdmission.Rejected) failure(admission.reason)
        return admission
    }

    private fun prepareClip() {
        val source = selected ?: return
        if (controlHolding || !foreground || !pageVisible || journal.failure.get() != null) return
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

    private fun record(message: String) { journal.append(message) }
    @Suppress("DEPRECATION")
    private fun pick() {
        choosingDocument = true
        runCatching { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, PICK) }.onFailure { choosingDocument = false; record("选片失败 ${it.message}") }
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == ScreenshotEditor.SAVE) { shotEditor?.result(resultCode,data); refresh(); return true }
        if (requestCode == SAVE_CLIP) {
            val destination = data?.data
            if (resultCode == Activity.RESULT_OK && destination != null) {
                if (!clipExporter.save(destination)) clipStatus.text = "无法开始另存：${clipExporter.state.message}"
            } else { clipStatus.text = "保存已取消，临时片段仍在；可另存或丢弃"; record(clipStatus.text.toString()) }
            refresh(); return true
        }
        if (requestCode != PICK) return false
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
            reloadPaused = false
            suspendMapping()
            controller.release("选择新文件")
            video.resetViewport()
            selected = uri
            pendingAutoLoad = true
            record("已选择本地文档，自动初始化并开始播放（不记录完整URI）")
            maybeAutoLoad()
            refresh() // Required after replacing selected; covers the first-pick regression.
        }

        return true
    }
    fun restoreState(bundle: Bundle?) {
        selected = bundle?.getString("review.selectedUri")?.let(Uri::parse)
        reloadPaused = bundle?.getBoolean("review.reloadPaused",false) ?: false
        pendingAutoLoad = selected != null; refresh()
    }
    fun saveState(bundle: Bundle) {
        selected?.let { bundle.putString("review.selectedUri",it.toString()) }
        bundle.putBoolean("review.reloadPaused",PlayerResumePolicy.pausedForRecreation(reloadPaused,shotHolding || bookmarkHolding,pageVisible))
    }
    fun onHostStart() { foreground = true; binding?.updateTarget(controller.state, pageVisible); record("HOST START; selected video auto-loads and starts playback"); syncMapping(); maybeAutoLoad(); refresh() }
    fun onHostStop() {
        if (PlayerResumePolicy.pausedAfterStop(shotHolding || bookmarkHolding,pageVisible)) reloadPaused = true
        foreground = false
        cancelBookmarks("应用进入后台")
        abortShotCapture("应用进入后台")
        foreground = false; verticalGesture = null; clipUiEpoch++; clipPreparing = false
        clipConfirmation?.dismiss(); clipConfirmation = null
        if (clipExporter.state.phase in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Saving)) clipExporter.cancel("应用进入后台")
        suspendMapping(); pendingAutoLoad = selected != null
        binding?.updateTarget(controller.state, false); controller.release("宿主进入后台"); refresh()
    }
    fun setPageVisible(value: Boolean) {
        if (!value) cancelBookmarks("离开播放器页")
        val leaving = !value && pageVisible
        pageVisible = value
        binding?.updateTarget(controller.state, foreground && pageVisible)
        if (!value) suspendMapping()
        if (leaving && !controlHolding) controller.pauseForPageChange()
        if (value) { syncMapping(); maybeAutoLoad() }
        if (!value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()
    }
    fun close() {
        if (destroyed) return
        destroyed = true; foreground = false; clipPreparing = false
        cancelBookmarks("宿主销毁")
        abortShotCapture("宿主销毁")
        shotEditor?.dispose(); shotEditor = null; shotGate.end()
        clipUiEpoch++; clipConfirmation?.dismiss(); clipConfirmation = null
        clipExporter.close()
        binding?.close(); binding = null
        controller.close(); destroyed = true; journal.shutdown()
    }
}
