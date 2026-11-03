package io.tabletennisreview.playback

/** Stable persistence IDs; physical detents are not implied by an event. */
enum class RotaryControl(val id: String) { Knob("knob"), Scroll("scroll") }
enum class SensitivityLevel(val id: String, val multiplier: Double) {
    Quarter("quarter",.25), Half("half",.5), Normal("normal",1.0), Double("double",2.0), Quadruple("quadruple",4.0)
}

/** A plan describes derived commands, never additional raw/semantic events. */
data class RotationPlan(val repeats: Int, val commandOverride: io.tabletennisreview.player.PlayerCommand? = null)

/** Called under the binding lock. No clocks, threads, USB or player side effects. */
class RotationProcessor {
    private data class Remainder(val input: MappingInput, val action: MappingAction,
        val level: SensitivityLevel, val time: Long, val count: Int)
    private val states = mutableMapOf<RotaryControl,Remainder>()
    fun reset() { states.clear() }
    fun plan(input: MappingInput, action: MappingAction, level: SensitivityLevel, nowNanos: Long): RotationPlan {
        val control = when(input) {
            MappingInput.KnobCW, MappingInput.KnobCCW -> RotaryControl.Knob
            MappingInput.ScrollUp, MappingInput.ScrollDown -> RotaryControl.Scroll
            else -> return RotationPlan(1)
        }
        if (action == MappingAction.SeekForward || action == MappingAction.SeekBackward) {
            states.remove(control)
            return RotationPlan(1,io.tabletennisreview.player.PlayerCommand.SeekRelative(
                (if (action == MappingAction.SeekForward) 5.0 else -5.0) * level.multiplier))
        }
        if (action != MappingAction.FrameForward && action != MappingAction.FrameBackward) {
            states.remove(control); return RotationPlan(1)
        }
        val previous = states[control]
        val elapsed = if (previous == null || nowNanos < previous.time) -1 else nowNanos - previous.time
        val carry = if (previous != null && previous.input == input && previous.action == action && previous.level == level &&
            elapsed in 0..1_000_000_000L) previous.count else 0
        val threshold = when(level) { SensitivityLevel.Quarter -> 4; SensitivityLevel.Half -> 2; else -> 1 }
        val count = carry + 1
        states[control] = Remainder(input,action,level,nowNanos,count % threshold)
        return RotationPlan(if (count < threshold) 0 else when(level) {
            SensitivityLevel.Double -> 2; SensitivityLevel.Quadruple -> 4; else -> 1
        })
    }
}
