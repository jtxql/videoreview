package io.tabletennisreview.usbdiagnostics

/** One reservation per connection, based ONLY on actual CDC return values. */
class UnlockCandidate {
    private val results = mutableMapOf<CdcAction, Int>()
    private var reserved = false
    private var lastControl: CdcAction? = null
    @Synchronized fun reset() { results.clear(); reserved = false; lastControl = null }
    @Synchronized fun record(action: CdcAction, result: Int) { results[action] = result; if (action.request == 0x22) lastControl = action }
    @Synchronized fun ready(): Boolean {
        if (reserved || results[CdcAction.A1] != CdcAction.A1.expected) return false
        return if (lastControl == CdcAction.A0) results[CdcAction.A0] == 0
        else listOf(CdcAction.A2, CdcAction.A3).all { results[it] == it.expected }
    }
    @Synchronized fun used(): Boolean = reserved
    @Synchronized fun reserve(): Boolean {
        if (!ready()) return false
        reserved = true
        return true
    }
    @Synchronized fun summary(): String = CdcAction.entries.joinToString(" ") { "${it.name}=${results[it]?.toString() ?: "未确认"}" } + " lastControl=${lastControl?.name ?: "未确认"} candidateAttemptUsed=$reserved"
    companion object {
        const val HEX = "55 00 07 88 94 00 1A FE"
        fun payload() = byteArrayOf(0x55, 0x00, 0x07, 0x88.toByte(), 0x94.toByte(), 0x00, 0x1A, 0xFE.toByte())
    }
}
