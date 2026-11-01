package io.tabletennisreview.usbdiagnostics

/** One reservation per connection, based ONLY on actual CDC return values. */
class UnlockCandidate {
    private val results = mutableMapOf<CdcAction, Int>()
    private var reserved = false
    @Synchronized fun reset() { results.clear(); reserved = false }
    @Synchronized fun record(action: CdcAction, result: Int) { results[action] = result }
    @Synchronized fun ready(): Boolean = !reserved && CdcAction.entries.all { results[it] == it.expected }
    @Synchronized fun used(): Boolean = reserved
    @Synchronized fun reserve(): Boolean {
        if (!ready()) return false
        reserved = true
        return true
    }
    @Synchronized fun summary(): String = CdcAction.entries.joinToString(" ") { "${it.name}=${results[it]?.toString() ?: "未确认"}" } + " candidateAttemptUsed=$reserved"
    companion object {
        const val HEX = "55 00 07 88 94 00 1A FE"
        fun payload() = byteArrayOf(0x55, 0x00, 0x07, 0x88.toByte(), 0x94.toByte(), 0x00, 0x1A, 0xFE.toByte())
    }
}
