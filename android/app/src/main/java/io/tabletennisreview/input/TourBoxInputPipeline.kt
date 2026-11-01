package io.tabletennisreview.input

/** RX-side decoder plus background event diagnostics; no USB or Activity dependencies. */
class TourBoxInputPipeline(private val log: (String) -> Unit, private val sessionIsCurrent: (Long) -> Boolean) : AutoCloseable {
    private val decoder = TourBoxInputDecoder()
    val events = TourBoxEventStream(onFault = { log("[输入故障] $it") }, sessionIsCurrent = sessionIsCurrent)
    private val diagnostics = events.subscribe { log(it.diagnostic()) }
    fun begin(sessionId: Long) { if (sessionIsCurrent(sessionId)) { decoder.begin(sessionId); events.begin(sessionId) } }
    fun expectReply(sessionId: Long, nanos: Long) = decoder.expectInitializationReply(sessionId, nanos)
    fun accept(sessionId: Long, bytes: ByteArray, nanos: Long, millis: Long) {
        val batch = decoder.feed(sessionId, bytes, nanos, millis)
        if (batch.responses > 0) log("[输入协议] session=$sessionId 完整26字节初始化回复已分流，不计输入事件")
        if (batch.boundaryFault) log("[输入协议] session=$sessionId 回复窗口超时；本轮后续字节仅作UnknownRawCode，原始RX保留")
        batch.events.forEach { events.publish(it) }
    }
    fun end() {
        val pending = decoder.end()
        events.end()
        if (pending > 0) log("[输入协议] 会话结束，$pending 字节未完成回复不派发控制动作，原始RX仍保留")
    }
    override fun close() { end(); diagnostics.close(); events.close() }
}
