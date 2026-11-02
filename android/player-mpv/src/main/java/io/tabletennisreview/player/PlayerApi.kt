package io.tabletennisreview.player

/** Discrete controls. No Activity, View, mpv or USB types cross this interface. */
sealed interface PlayerCommand {
    data class SetPaused(val paused: Boolean) : PlayerCommand
    data class SetSpeed(val speed: Double) : PlayerCommand
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
/** All entry points/listeners are main-thread owned; commands perform native work off the UI. */
interface PlayerController {
    val state: PlayerState
    fun setStateListener(listener: ((PlayerState) -> Unit)?)
    fun setLogListener(listener: ((String) -> Unit)?)
    fun initialize(contentUri: String): CommandAdmission
    fun execute(command: PlayerCommand): CommandAdmission
    fun pauseForPageChange()
    fun release(reason: String)
    fun close()
}
