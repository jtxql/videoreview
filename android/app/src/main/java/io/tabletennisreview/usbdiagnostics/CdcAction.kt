package io.tabletennisreview.usbdiagnostics

/** Closed whitelist: no arbitrary requests, BULK OUT, private unlock, or reset. */
enum class CdcAction(val label: String, val request: Int, val value: Int, val expected: Int) {
    A1("A1：设置 115200/8N1", 0x20, 0, 7),
    A2("A2：启用 DTR", 0x22, 1, 0),
    A3("A3：启用 DTR+RTS", 0x22, 3, 0);

    fun payload(): ByteArray? = if (this == A1) byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08) else null
}
