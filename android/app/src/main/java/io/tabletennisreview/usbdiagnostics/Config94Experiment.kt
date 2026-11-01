package io.tabletennisreview.usbdiagnostics

/** Pure session evidence; never performs USB I/O. All transitions are serialized. */
class Config94Experiment {
    private var epoch = -1L
    private var stage = 0
    private var invalid = false
    private var started = false
    private var sent = false
    private var matched = false
    private var reserved = false
    private var deadline = 0L
    private val window = ArrayDeque<Byte>()
    @Synchronized fun reset() { epoch = -1; stage = 0; invalid = false; started = false; sent = false; matched = false; reserved = false; window.clear() }
    @Synchronized fun record(action: CdcAction, result: Int) {
        if (started || result != action.expected || action == CdcAction.A2 || action == CdcAction.A3) invalid = true
        if (action == CdcAction.A1) { if (stage != 0) invalid = true; stage = 1 }
        if (action == CdcAction.A0) { if (stage != 1) invalid = true; stage = 2 }
    }
    @Synchronized fun begin(session: Long, now: Long) {
        epoch = session; started = true; deadline = now + 3000; window.clear()
        if (stage != 2) invalid = true
    }
    @Synchronized fun sent(result: Int) { sent = result == 8; if (!sent) invalid = true }
    @Synchronized fun observe(session: Long, bytes: ByteArray, now: Long): Boolean {
        if (!started || invalid || matched || session != epoch || now > deadline) return false
        for (byte in bytes) {
            window.addLast(byte)
            if (window.size > RESPONSE.size) window.removeFirst()
            if (window.size == RESPONSE.size && window.toList() == RESPONSE.toList()) { matched = true; return true }
        }
        return false
    }
    @Synchronized fun ready(session: Long): Boolean = session == epoch && started && sent && matched && !invalid && !reserved
    @Synchronized fun reserve(session: Long, confirmed: Boolean): Boolean {
        if (!confirmed || !ready(session)) return false
        reserved = true; return true
    }
    @Synchronized fun validReserved(session: Long): Boolean = session == epoch && reserved && sent && matched && !invalid
    @Synchronized fun summary() = "A1A0Stage=$stage invalid=$invalid tx8=$sent response26=$matched attemptUsed=$reserved"
    companion object {
        const val HEX = "B5 00 5D 04 00 05 00 06 00 07 00 08 00 09 00 0B 02 0C 08 0D 08 0E 00 0F 00 26 00 27 00 28 00 29 00 3B 00 3C 00 3D 00 3E 00 3F 00 40 00 41 00 42 00 43 00 44 00 45 00 46 00 47 00 48 00 49 00 4A 00 4B 00 4C 00 4D 00 4E 00 4F 00 50 00 51 00 52 00 53 00 54 00 A8 00 A9 00 AA 00 AB 00 FE"
        const val SHA256 = "bd5c3ea003dc6cee06182d710381f71844a4f7a38183f15e952224f21f8414a1"
        private fun decode(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        fun payload(): ByteArray = decode(HEX)
        private val RESPONSE = decode("04 E0 5E BB CF BC C7 42 9E A7 BF 37 33 6C F7 0B 00 00 00 02 00 03 03 01 00 00")
    }
}
