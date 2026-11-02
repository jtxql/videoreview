package io.tabletennisreview.playback

import android.app.Activity
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
    private var binding: TourBoxPlayerBinding? = null
    var onFullscreenChanged: (Boolean) -> Unit = {}
    private var fullscreen = false
    private val fullSwitch = CheckBox(activity).apply { text = "横屏全屏（返回键退出）" }
    private val progress = SeekBar(activity).apply { max = PlayerInteractionPolicy.PROGRESS_MAX }
    private val progressLabel = TextView(activity)
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
    private var scrubbing = false
    private var pendingAutoLoad = false
    private var choosingDocument = false
    private val tourBoxStatus = TextView(activity).apply { textSize = 12f }
    private var usbSession: Long? = null
    private var usbAuthorized = false
    private var mappingWanted = false
    private var updatingMapping = false
    private val controlsScroll: ScrollView
    private val logScroll: ScrollView
    private val title: TextView
    private val touchOverlay: View
    private val mapping = CheckBox(activity).apply { text = "启用TourBox控制（仅播放器页）" }
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
        addView(title)
        addView(status); addView(loopStatus)
        addView(video, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        progressContainer.addView(progressLabel); progressContainer.addView(progress)
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
            contentDescription = "视频：双击左中右跳转或播放；横屏左侧上滑加速下滑减速，右侧上滑增音量下滑减音量"
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
        video.addView(hud, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            val margin = (8 * resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, 0)
        })
        val controls = LinearLayout(activity).apply { orientation = VERTICAL }
        val fraction = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0.3f else 0.4f
        val heightDp = (resources.configuration.screenHeightDp * fraction).toInt().coerceIn(100, 300)
        controlsScroll = ScrollView(activity).apply { addView(controls) }
        addView(controlsScroll, LayoutParams(-1, (heightDp * resources.displayMetrics.density).toInt()))
        controls.addView(fullSwitch)
        fullSwitch.setOnCheckedChangeListener { _, checked -> setFullscreen(checked) }
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
            pendingAutoLoad = false
            suspendMapping()
            if (uri != null) admission(controller.initialize(uri.toString()))
        }
        release = button(file, "释放") { pendingAutoLoad = false; suspendMapping(); controller.release("用户释放") }
        controls.addView(tourBoxStatus); controls.addView(mapping); controls.addView(mappingStatus)
        mapping.setOnCheckedChangeListener { _, checked ->
            if (!updatingMapping) { mappingWanted = checked; binding?.setEnabled(false); if (checked) binding?.resetFault(); syncMapping(manual = checked) }
        }
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
        logScroll = ScrollView(activity).apply { addView(logView) }
        addView(logScroll, LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        updateProgressLayout()
        controller.setLogListener(::record)
        controller.setStateListener { value ->
            status.text = "${value.phase} ${seconds(value.position)} / ${seconds(value.duration)}  ${value.speed?.toString() ?: "未知"}×\n${value.message}"
            hud.text = PlayerInteractionPolicy.hud(value.speed, value.position, value.duration)
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
                if (value.fault != null && mappingWanted) {
                    mappingWanted = false; updatingMapping = true; mapping.isChecked = false; updatingMapping = false
                }
                mappingStatus.text = "接纳=${value.admitted} 待办=${value.pending} 取消=${value.cancelled} 拒绝=${value.rejected} 忽略=${value.ignored}\n${value.fault ?: value.notice ?: "Knob逐帧/按下播放；Scroll±5秒/按下切速；C1记A/C2记B/Top切AB"}"
            }
        }, { journal.failure.get() == null })
        binding?.approveUsbSession(usbSession)
        binding?.updateTarget(controller.state, foreground && pageVisible)
    }
    fun setTourBoxStatus(value: String) { if (tourBoxStatus.text.toString() != value) tourBoxStatus.text = value }
    fun setTourBoxState(authorized: Boolean, session: Long?) {
        val next = TourBoxControlUiPolicy.wanted(usbAuthorized, authorized, mappingWanted)
        val changed = usbSession != session
        usbAuthorized = authorized
        usbSession = session
        if (changed) binding?.approveUsbSession(session)
        mappingWanted = next
        if (changed && session != null) binding?.resetFault()
        updatingMapping = true; mapping.isChecked = mappingWanted; updatingMapping = false
        if (!authorized) binding?.setEnabled(false)
        syncMapping(manual = changed && session != null)
        refresh() // Authorization callbacks must refresh even when session is still null.
    }
    private fun syncMapping(manual: Boolean = false) {
        val bridge = binding ?: return
        if (mappingWanted && usbSession != null && foreground && pageVisible && controller.state.loaded &&
            !bridge.status().enabled && (manual || bridge.status().fault == null)) bridge.setEnabled(true)
    }
    private fun suspendMapping() { binding?.setEnabled(false) }
    private fun maybeAutoLoad() {
        if (!pendingAutoLoad || choosingDocument || destroyed || !PlayerPolicy.canInitialize(selected != null, foreground, pageVisible, controller.state)) return
        pendingAutoLoad = false
        record("AUTO LOAD selected local video; initial pause=yes; first frame requires visual verification")
        admission(controller.initialize(checkNotNull(selected).toString()))
    }
    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        val visibility = if (value) View.GONE else View.VISIBLE
        listOf(title, status, loopStatus, controlsScroll, logScroll).forEach { it.visibility = visibility }
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
        val fraction = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0.3f else 0.4f
        controlsScroll.layoutParams = controlsScroll.layoutParams.apply {
            height = ((configuration.screenHeightDp * fraction).toInt().coerceIn(100, 300) * resources.displayMetrics.density).toInt()
        }
        updateProgressLayout()
    }
    private fun handleVideoTouch(event: MotionEvent): Boolean {
        val landscape = fullscreen || resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            verticalGesture = null; volumeFailed = false; touchValid = true
            touchSession = controller.state.sessionId
            if (landscape && foreground && pageVisible && touchOverlay.width > 0) {
                verticalGesture = PlayerVerticalGesture(event.x.toDouble(), event.y.toDouble(), touchOverlay.width.toDouble(),
                    48.0 * resources.displayMetrics.density, ViewConfiguration.get(activity).scaledTouchSlop.toDouble())
                runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
                    .onSuccess { volumeBase = it; lastVolume = it }
                    .onFailure { volumeFailed = true; record("读取媒体音量失败：${it.message}") }
            }
        }
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL || !foreground || !pageVisible || touchSession != controller.state.sessionId) {
            verticalGesture = null; touchValid = false
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL; gestures?.onTouchEvent(cancel); cancel.recycle()
            }
            return true
        }
        if (!touchValid) return true
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
        touchOverlay.visibility = View.VISIBLE // Same double-tap zones in portrait, landscape and fullscreen.
        updateProgressVisibility()
    }
    private fun updateProgressVisibility() {
        progressContainer.visibility = if (progressOverlay.visible) View.VISIBLE else View.GONE
    }
    private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3fs", it) } ?: "未知"
    private fun admission(value: CommandAdmission) { if (value is CommandAdmission.Rejected) record("REJECT ${value.reason}") }
    private fun submit(command: PlayerCommand) {
        if (!foreground || !pageVisible) return
        if (journal.failure.get() != null) { record("播放器日志写入失败，请停止测试并保留证据"); return }
        binding?.cancelPending("屏幕操作优先")
        admission(controller.execute(command))
    }
    private fun refresh() {
        val value = controller.state
        mapping.isEnabled = TourBoxControlUiPolicy.selectable(foreground, pageVisible, usbAuthorized, binding != null)
        progress.isEnabled = foreground && pageVisible && value.loaded && !value.busy && value.seekable && (value.duration?.let { it.isFinite() && it > 0 } == true)
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
    fun onHostStop() { verticalGesture = null; suspendMapping(); pendingAutoLoad = selected != null; foreground = false; binding?.updateTarget(controller.state, false); controller.release("宿主进入后台"); refresh() }
    fun setPageVisible(value: Boolean) {
        val leaving = !value && pageVisible
        pageVisible = value
        binding?.updateTarget(controller.state, foreground && pageVisible)
        if (!value) suspendMapping()
        if (leaving) controller.pauseForPageChange()
        if (value) { syncMapping(); maybeAutoLoad() }
        if (!value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh()
    }
    fun close() {
        if (destroyed) return
        binding?.close(); binding = null
        controller.close(); destroyed = true; journal.shutdown()
    }
}
