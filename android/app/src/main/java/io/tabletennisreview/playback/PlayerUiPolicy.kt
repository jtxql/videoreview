package io.tabletennisreview.playback

/** No user preference: initialized/authorized sessions enable control automatically; faults stay stopped. */
object TourBoxControlUiPolicy {
    fun automatic(authorized: Boolean, sessionReady: Boolean, faulted: Boolean): Boolean =
        authorized && sessionReady && !faulted
}
/** Overlay visibility survives periodic playback updates, but resets on entering landscape. */
class PlayerProgressOverlay {
    var overlay = false
        private set
    var visible = true
        private set
    fun layout(landscape: Boolean, fullscreen: Boolean) {
        val next = landscape || fullscreen
        if (next != overlay) visible = true
        overlay = next
    }
    fun tap() { if (overlay) visible = !visible }
}

enum class VerticalControl { Speed, Volume }
data class VerticalChange(val control: VerticalControl, val steps: Int)
/** Gesture locks to its DOWN region/dominant axis. Up is positive; central/horizontal drags do nothing. */
class PlayerVerticalGesture(private val x: Double, private val y: Double,
    width: Double, private val stepPixels: Double, private val slop: Double) {
    private val control: VerticalControl?
    private var locked = false
    private var rejected = false
    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && width > 0 && x in 0.0..width)
        require(stepPixels.isFinite() && stepPixels > 0 && slop.isFinite() && slop >= 0)
        control = when { x < width / 3 -> VerticalControl.Speed; x >= width * 2 / 3 -> VerticalControl.Volume; else -> null }
    }
    fun update(currentX: Double, currentY: Double): VerticalChange? {
        if (rejected || control == null || !currentX.isFinite() || !currentY.isFinite()) return null
        val dx = kotlin.math.abs(currentX - x)
        val dy = kotlin.math.abs(currentY - y)
        if (!locked) {
            if (dx <= slop && dy <= slop) return null
            if (dy <= dx) { rejected = true; return null }
            locked = true
        }
        return VerticalChange(control, ((y - currentY) / stepPixels).toInt().coerceIn(-1000, 1000))
    }
}

/** Unknown duration or out-of-range marks are not presented as real positions. */
object ProgressMarkers {
    fun fraction(position: Double?, duration: Double?): Double? {
        if (position == null || duration == null || !position.isFinite() || !duration.isFinite() ||
            duration <= 0 || position < 0 || position > duration) return null
        return position / duration
    }
}

/** Native TogglePaused resolves the actual pause property; this label follows observed playback state. */
object PlaybackButtonUi {
    fun label(phase: io.tabletennisreview.player.PlayerPhase): String = when (phase) {
        io.tabletennisreview.player.PlayerPhase.Playing, io.tabletennisreview.player.PlayerPhase.Buffering -> "暂停"
        else -> "播放"
    }
}

object PlayerResumePolicy {
    fun pausedForRecreation(pendingPause: Boolean, screenshotHeld: Boolean, playerVisible: Boolean): Boolean = pendingPause || screenshotHeld || !playerVisible
    fun pausedAfterStop(screenshotHeld: Boolean, playerVisible: Boolean): Boolean = screenshotHeld || !playerVisible
}
object SettingsUiPolicy {
    fun canOpen(phase: io.tabletennisreview.player.PlayerPhase, busy: Boolean, held: Boolean): Boolean =
        !busy && !held && phase != io.tabletennisreview.player.PlayerPhase.Initializing && phase != io.tabletennisreview.player.PlayerPhase.Loading
}

object BookmarkUiPolicy {
    fun canOpen(state: io.tabletennisreview.player.PlayerState, foreground: Boolean, pageVisible: Boolean,
        held: Boolean, scrubbing: Boolean, hasUri: Boolean): Boolean = foreground && pageVisible && !held && !scrubbing &&
        hasUri && state.loaded && !state.busy && state.seekable && state.sessionId > 0
}
