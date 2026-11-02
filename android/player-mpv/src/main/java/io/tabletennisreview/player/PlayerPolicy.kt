package io.tabletennisreview.player

import java.util.concurrent.atomic.AtomicLong

class PlayerEpoch {
    companion object { private val sequence = AtomicLong() }
    @Volatile private var token = 0L
    @Volatile private var live = false
    @Synchronized fun begin(): Long { token = sequence.incrementAndGet(); live = true; return token }
    @Synchronized fun invalidate() { live = false }
    @Synchronized fun isCurrent(candidate: Long): Boolean = live && candidate == token
}

data class LoopPoints(val a: Double? = null, val b: Double? = null, val active: Boolean = false) {
    fun markA(position: Double): LoopPoints {
        require(!active) { "请先清除AB" }; PlayerPolicy.position(position)
        return LoopPoints(a = position)
    }
    fun markB(position: Double): LoopPoints {
        require(!active) { "请先清除AB" }
        val start = requireNotNull(a) { "请先记A" }; PlayerPolicy.position(position)
        require(position > start) { "B必须晚于A" }
        return copy(b = position)
    }
    fun enabled(duration: Double?): LoopPoints {
        val start = requireNotNull(a) { "请先记A" }
        val end = requireNotNull(b) { "请先记B" }
        PlayerPolicy.position(start); PlayerPolicy.position(end)
        require(duration != null && duration.isFinite() && duration > 0) { "文件时长未知" }
        require(start < end && end <= duration) { "须满足0≤A<B≤时长" }
        return copy(active = true)
    }
}
sealed interface NativeRequest {
    data class Property(val name: String, val value: String) : NativeRequest
    data class Command(val arguments: List<String>) : NativeRequest
}
object PlayerPolicy {
    val speeds = listOf(0.1, 0.25, 0.5, 0.75, 1.0)
    fun canInitialize(hasUri: Boolean, foreground: Boolean, pageVisible: Boolean, state: PlayerState): Boolean =
        hasUri && foreground && pageVisible && !state.busy && state.phase != PlayerPhase.Initializing
    fun position(value: Double): Double {
        require(value.isFinite() && value >= 0) { "时间无效" }; return value
    }
    fun needsSeek(command: PlayerCommand): Boolean = when (command) {
        PlayerCommand.StepForward, PlayerCommand.StepBackward, PlayerCommand.MarkA,
        PlayerCommand.MarkB, PlayerCommand.EnableLoop, PlayerCommand.SeekA -> true
        is PlayerCommand.SeekRelative -> true
        else -> false
    }
    fun requests(command: PlayerCommand, loop: LoopPoints = LoopPoints()): List<NativeRequest> {
        fun property(name: String, value: String) = NativeRequest.Property(name, value)
        fun cmd(vararg args: String) = NativeRequest.Command(args.toList())
        return when (command) {
            is PlayerCommand.SetPaused -> listOf(property("pause", if (command.paused) "yes" else "no"))
            is PlayerCommand.SetSpeed -> {
                require(command.speed.isFinite() && command.speed in speeds) { "不支持的速度档位" }
                listOf(property("speed", command.speed.toString()))
            }
            is PlayerCommand.SeekRelative -> {
                require(command.seconds.isFinite() && command.seconds != 0.0) { "跳转量无效" }
                listOf(cmd("seek", command.seconds.toString(), "relative+exact"))
            }
            PlayerCommand.StepForward, PlayerCommand.StepBackward -> {
                require(!loop.active) { "逐帧前请清除AB" }
                listOf(property("pause", "yes"), cmd(if (command == PlayerCommand.StepForward) "frame-step" else "frame-back-step"))
            }
            PlayerCommand.ClearLoop -> listOf(property("ab-loop-b", "no"), property("ab-loop-a", "no"))
            PlayerCommand.SeekA -> listOf(property("pause", "yes"), cmd("seek", position(requireNotNull(loop.a) { "请先记A" }).toString(), "absolute+exact"))
            PlayerCommand.EnableLoop -> {
                require(loop.active && loop.a != null && loop.b != null) { "先验证AB边界" }
                position(loop.a); position(loop.b); require(loop.a < loop.b) { "AB边界顺序无效" }
                listOf(property("ab-loop-b", "no"), property("ab-loop-a", loop.a.toString()),
                    property("ab-loop-count", "inf"), property("ab-loop-b", loop.b.toString()),
                    property("pause", "yes"), cmd("seek", loop.a.toString(), "absolute+exact"))
            }
            PlayerCommand.MarkA, PlayerCommand.MarkB -> emptyList()
        }
    }
}
