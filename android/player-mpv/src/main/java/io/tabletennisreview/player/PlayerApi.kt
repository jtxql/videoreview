package io.tabletennisreview.player

/** Discrete controls. No Activity, View, mpv or USB types cross this interface. */
sealed interface PlayerCommand {
    data object TogglePaused : PlayerCommand
    data object CycleSpeed : PlayerCommand
    data object ToggleLoop : PlayerCommand
    data class SetPaused(val paused: Boolean) : PlayerCommand
    data class SetSpeed(val speed: Double) : PlayerCommand
    data class SeekAbsolute(val seconds: Double) : PlayerCommand
    data class ShiftSpeed(val steps: Int) : PlayerCommand
    data class SeekBySpeed(val direction: Int) : PlayerCommand
    data class SeekRelative(val seconds: Double) : PlayerCommand
    data object StepForward : PlayerCommand
    data object StepBackward : PlayerCommand
    data object MarkA : PlayerCommand
    data object MarkB : PlayerCommand
    data object EnableLoop : PlayerCommand
    data object ClearLoop : PlayerCommand
    data object SeekA : PlayerCommand
}
enum class PlayerPhase { Released, Initializing, Ready, Loading, Paused, Playing, Buffering, Ended, Error }
data class PlayerState(
    val sessionId: Long = 0,
    val phase: PlayerPhase = PlayerPhase.Released,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val position: Double? = null,
    val duration: Double? = null,
    val speed: Double? = null,
    val seekable: Boolean = false,
    val loop: LoopPoints = LoopPoints(),
    val message: String = "请选择本地视频并初始化",
)
sealed interface CommandAdmission {
    data object Accepted : CommandAdmission
    data class Rejected(val reason: String) : CommandAdmission
}
data class PlayerExportSnapshot(val sessionId: Long, val a: Double, val b: Double, val speed: Double, val duration: Double) {
    init {
        require(sessionId > 0)
        LoopPoints(a, b).enabled(duration)
        require(speed.isFinite() && PlayerPolicy.speeds.any { kotlin.math.abs(it - speed) < 0.000001 }) { "导出速度无效" }
    }
}
sealed interface ExportPreparation {
    data class Ready(val snapshot: PlayerExportSnapshot) : ExportPreparation
    data class Rejected(val reason: String) : ExportPreparation
}
sealed interface ScreenshotPreparation {
    data class Ready(val sessionId: Long) : ScreenshotPreparation
    data class Rejected(val reason: String) : ScreenshotPreparation
}
/** All entry points/listeners are main-thread owned; commands perform native work off the UI. */
interface PlayerController {
    val state: PlayerState
    fun setStateListener(listener: ((PlayerState) -> Unit)?)
    fun setLogListener(listener: ((String) -> Unit)?)
    fun initialize(contentUri: String): CommandAdmission
    fun initializePaused(contentUri: String): CommandAdmission
    fun prepareScreenshot(callback: (ScreenshotPreparation) -> Unit): CommandAdmission
    fun prepareScreenshotGuarded(authorization: () -> Boolean, callback: (ScreenshotPreparation) -> Unit): CommandAdmission
    fun execute(command: PlayerCommand): CommandAdmission
    /** Short authorization, rechecked before each native request; never hold USB locks around native code. */
    fun executeGuarded(command: PlayerCommand, authorization: () -> Boolean, origin: String): CommandAdmission
    fun prepareReviewSnapshot(authorization: () -> Boolean, callback: (ReviewPreparation) -> Unit): CommandAdmission
    fun prepareReviewSnapshotKeepingPlayback(authorization: () -> Boolean, callback: (ReviewPreparation) -> Unit): CommandAdmission
    fun restoreReview(preset: ReviewPreset, authorization: () -> Boolean, callback: (ReviewRestoreResult) -> Unit): CommandAdmission
    fun prepareExport(callback: (ExportPreparation) -> Unit): CommandAdmission
    fun pauseForPageChange()
    fun release(reason: String)
    fun close()
}
