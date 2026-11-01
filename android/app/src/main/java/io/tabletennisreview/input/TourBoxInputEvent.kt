package io.tabletennisreview.input

/** Host receive times, not device action times. rawCode is always unsigned 0..255. */
data class InputStamp(val sessionId: Long, val sequence: Long, val receivedNanos: Long, val wallTimeMillis: Long, val rawCode: Int)
enum class TourBoxButton { KNOB, SCROLL, TOP, SHORT, C1, C2, TALL, TOURBOX }
sealed interface TourBoxInputEvent {
    val stamp: InputStamp
    data class ButtonDown(val button: TourBoxButton, override val stamp: InputStamp) : TourBoxInputEvent
    data class ButtonUp(val button: TourBoxButton, override val stamp: InputStamp) : TourBoxInputEvent
    data class KnobClockwise(override val stamp: InputStamp) : TourBoxInputEvent
    data class KnobCounterClockwise(override val stamp: InputStamp) : TourBoxInputEvent
    data class ScrollUp(override val stamp: InputStamp) : TourBoxInputEvent
    data class ScrollDown(override val stamp: InputStamp) : TourBoxInputEvent
    data class UnknownRawCode(override val stamp: InputStamp) : TourBoxInputEvent
}
fun TourBoxInputEvent.diagnostic(): String {
    val name = when (this) {
        is TourBoxInputEvent.ButtonDown -> "ButtonDown($button)"
        is TourBoxInputEvent.ButtonUp -> "ButtonUp($button)"
        else -> javaClass.simpleName
    }
    return "[输入] session=${stamp.sessionId} seq=${stamp.sequence} receivedNanos=${stamp.receivedNanos} wallMs=${stamp.wallTimeMillis} RAW 0x%02X → $name".format(stamp.rawCode)
}
