package io.tabletennisreview.playback

import io.tabletennisreview.input.*

data class GestureDispatch(val input: MappingInput, val event: TourBoxInputEvent)
data class GestureDecision(val dispatches: List<GestureDispatch>, val deadlineNanos: Long?)
interface GestureTimer { fun schedule(deadlineNanos: Long, task: () -> Unit): AutoCloseable }
/** Caller serializes all access. No synthetic raw events, sleeps or player calls. */
class TourBoxButtonGesture {
    private var first: TourBoxInputEvent? = null
    private var deadline: Long? = null
    private var held = false
    private var released = false
    private var lastTime = Long.MIN_VALUE
    fun reset() { first = null; deadline = null; held = false; released = false; lastTime = Long.MIN_VALUE }
    fun expire(nowNanos: Long): GestureDecision {
        if (nowNanos < 0 || nowNanos < lastTime) { reset(); return GestureDecision(emptyList(),null) }
        lastTime = nowNanos
        val event = first
        if (event != null && nowNanos >= checkNotNull(deadline)) {
            first = null; deadline = null; released = false
            return GestureDecision(listOf(GestureDispatch(MappingInput.TourBox,event)),null)
        }
        return GestureDecision(emptyList(),deadline)
    }
    fun accept(event: TourBoxInputEvent, doubleEnabled: Boolean, nowNanos: Long): GestureDecision {
        if (!doubleEnabled) {
            reset()
            return GestureDecision(MappingInput.fromEvent(event)?.let { listOf(GestureDispatch(it,event)) } ?: emptyList(),null)
        }
        val dispatches = expire(nowNanos).dispatches.toMutableList()
        if (nowNanos < 0 || nowNanos > Long.MAX_VALUE-400_000_000L) return GestureDecision(dispatches,deadline)
        when {
            event is TourBoxInputEvent.ButtonUp && event.button == TourBoxButton.TOURBOX -> {
                held = false; if (first != null) released = true
            }
            event is TourBoxInputEvent.ButtonDown && event.button == TourBoxButton.TOURBOX -> {
                if (!held) {
                    held = true
                    if (first != null && released && nowNanos < checkNotNull(deadline)) {
                        dispatches += GestureDispatch(MappingInput.TourBoxDouble,event)
                        first = null; deadline = null; released = false
                    } else { first = event; deadline = nowNanos+400_000_000L; released = false }
                }
            }
            else -> MappingInput.fromEvent(event)?.let { dispatches += GestureDispatch(it,event) }
        }
        return GestureDecision(dispatches,deadline)
    }
}
