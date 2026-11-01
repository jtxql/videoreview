package io.tabletennisreview.input

/** Stateful response boundary, independent of USB read boundaries. No Android APIs. */
class TourBoxInputDecoder {
    data class Batch(val events: List<TourBoxInputEvent>, val responses: Int = 0, val boundaryFault: Boolean = false)
    private var session = -1L
    private var sequence = 0L
    private var waiting = false
    private var unknownOnly = false
    private var deadline = 0L
    private val pending = ArrayDeque<InputStamp>()
    @Synchronized fun begin(sessionId: Long) {
        session = sessionId; sequence = 0; waiting = false; unknownOnly = false; pending.clear()
    }
    @Synchronized fun end(): Int {
        val count = pending.size
        session = -1; waiting = false; pending.clear(); return count
    }
    /** Arm immediately BEFORE the known eight-byte OUT, never for arbitrary long reads. */
    @Synchronized fun expectInitializationReply(sessionId: Long, nowNanos: Long) {
        if (session != sessionId || session < 0) return
        waiting = true; deadline = nowNanos + 3_000_000_000L; pending.clear()
    }
    @Synchronized fun feed(sessionId: Long, bytes: ByteArray, nowNanos: Long, wallMillis: Long): Batch {
        if (session < 0 || sessionId != session) return Batch(emptyList())
        val events = mutableListOf<TourBoxInputEvent>()
        var fault = false
        var responses = 0
        if (waiting && nowNanos > deadline) {
            pending.forEach { events.add(TourBoxInputEvent.UnknownRawCode(it)) }; pending.clear()
            waiting = false; unknownOnly = true; fault = true
        }
        for (byte in bytes) {
            val stamp = InputStamp(session, ++sequence, nowNanos, wallMillis, byte.toInt() and 255)
            if (unknownOnly) { events.add(TourBoxInputEvent.UnknownRawCode(stamp)); continue }
            if (!waiting) { events.add(decode(stamp)); continue }
            pending.addLast(stamp)
            // Hold only a prefix of the EXACT expected reply. Mismatches remain diagnostic
            // unknowns, never control actions. Every raw byte is still logged upstream.
            while (pending.isNotEmpty() && pending.map { it.rawCode } != RESPONSE.take(pending.size))
                events.add(TourBoxInputEvent.UnknownRawCode(pending.removeFirst()))
            if (pending.size == RESPONSE.size) {
                pending.clear(); waiting = false; responses++
            }
        }
        return Batch(events, responses, fault)
    }
    private fun decode(s: InputStamp): TourBoxInputEvent = when (s.rawCode) {
        0x44 -> TourBoxInputEvent.KnobClockwise(s)
        0x04 -> TourBoxInputEvent.KnobCounterClockwise(s)
        0x49 -> TourBoxInputEvent.ScrollUp(s)
        0x09 -> TourBoxInputEvent.ScrollDown(s)
        0x37 -> TourBoxInputEvent.ButtonDown(TourBoxButton.KNOB, s)
        0xB7 -> TourBoxInputEvent.ButtonUp(TourBoxButton.KNOB, s)
        0x0A -> TourBoxInputEvent.ButtonDown(TourBoxButton.SCROLL, s)
        0x8A -> TourBoxInputEvent.ButtonUp(TourBoxButton.SCROLL, s)
        0x02 -> TourBoxInputEvent.ButtonDown(TourBoxButton.TOP, s)
        0x82 -> TourBoxInputEvent.ButtonUp(TourBoxButton.TOP, s)
        0x03 -> TourBoxInputEvent.ButtonDown(TourBoxButton.SHORT, s)
        0x83 -> TourBoxInputEvent.ButtonUp(TourBoxButton.SHORT, s)
        0x22 -> TourBoxInputEvent.ButtonDown(TourBoxButton.C1, s)
        0xA2 -> TourBoxInputEvent.ButtonUp(TourBoxButton.C1, s)
        0x23 -> TourBoxInputEvent.ButtonDown(TourBoxButton.C2, s)
        0xA3 -> TourBoxInputEvent.ButtonUp(TourBoxButton.C2, s)
        0x00 -> TourBoxInputEvent.ButtonDown(TourBoxButton.TALL, s)
        0x80 -> TourBoxInputEvent.ButtonUp(TourBoxButton.TALL, s)
        0x2A -> TourBoxInputEvent.ButtonDown(TourBoxButton.TOURBOX, s)
        0xAA -> TourBoxInputEvent.ButtonUp(TourBoxButton.TOURBOX, s)
        else -> TourBoxInputEvent.UnknownRawCode(s)
    }
    companion object {
        private val RESPONSE = listOf(0x04,0xE0,0x5E,0xBB,0xCF,0xBC,0xC7,0x42,0x9E,0xA7,0xBF,0x37,0x33,0x6C,0xF7,0x0B,0,0,0,2,0,3,3,1,0,0)
    }
}
