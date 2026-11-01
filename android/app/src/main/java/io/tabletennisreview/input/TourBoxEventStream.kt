package io.tabletennisreview.input

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface TourBoxInputEvents {
    fun subscribe(listener: (TourBoxInputEvent) -> Unit): AutoCloseable
    fun isCurrent(event: TourBoxInputEvent): Boolean
    fun withCurrent(event: TourBoxInputEvent, action: () -> Unit): Boolean
}

/** Bounded discrete-event fan-out; one worker, no conflation and no replay. */
class TourBoxEventStream(
    private val capacity: Int = 1024,
    private val onFault: (String) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "TourBox-events") },
    private val sessionIsCurrent: (Long) -> Boolean = { true },
) : TourBoxInputEvents, AutoCloseable {
    private data class Delivery(val event: TourBoxInputEvent, val listeners: List<Long>)
    private val lock = Any()
    private val deliveryGate = Any()
    private val queue = ArrayDeque<Delivery>()
    private val listeners = linkedMapOf<Long, (TourBoxInputEvent) -> Unit>()
    private var nextListener = 0L
    @Volatile private var active = -1L
    private var scheduled = false
    private var closed = false
    init { require(capacity > 0) }
    override fun subscribe(listener: (TourBoxInputEvent) -> Unit): AutoCloseable {
        val id = synchronized(lock) { check(!closed); (++nextListener).also { listeners[it] = listener } }
        return AutoCloseable {
            synchronized(lock) { listeners.remove(id) }
            synchronized(deliveryGate) { /* wait only for an already-entered callback */ }
        }
    }
    fun begin(sessionId: Long) { end(); synchronized(lock) { if (!closed) active = sessionId } }
    /** Waits for an already-entered callback; does NOT interrupt consumer code. */
    fun end() {
        synchronized(lock) { active = -1; queue.clear() }
        synchronized(deliveryGate) { /* no queued callback can enter while we wait */ }
    }
    override fun isCurrent(event: TourBoxInputEvent): Boolean = active >= 0 && event.stamp.sessionId == active && sessionIsCurrent(event.stamp.sessionId)
    /** Use again at the point of action if a subscriber schedules further async work. */
    override fun withCurrent(event: TourBoxInputEvent, action: () -> Unit): Boolean = synchronized(deliveryGate) {
        if (!isCurrent(event)) false else { action(); true }
    }
    fun publish(event: TourBoxInputEvent): Boolean {
        var schedule = false
        var fault: String? = null
        synchronized(lock) {
            if (closed || !isCurrent(event)) return false
            if (queue.size >= capacity) {
                val discarded = queue.size + 1
                active = -1; queue.clear()
                fault = "输入队列溢出 capacity=$capacity，明确取消${discarded}条待分发事件；本轮语义分发失效，原始USB采集保留"
            } else {
                queue.addLast(Delivery(event, listeners.keys.toList()))
                if (!scheduled) { scheduled = true; schedule = true }
            }
        }
        fault?.let { onFault(it); return false }
        if (schedule) try { executor.execute(::drain) } catch (e: RuntimeException) {
            synchronized(lock) { active = -1; queue.clear(); scheduled = false }
            onFault("输入工作队列不可用：${e.javaClass.simpleName}；语义分发失效"); return false
        }
        return true
    }
    private fun drain() {
        while (true) {
            val delivery = synchronized(lock) {
                if (queue.isEmpty()) { scheduled = false; return }
                queue.removeFirst()
            }
            for (id in delivery.listeners) {
                try { withCurrent(delivery.event) {
                    val listener = synchronized(lock) { listeners[id] }
                    listener?.invoke(delivery.event)
                } }
                catch (e: Exception) {
                    synchronized(lock) { active = -1; queue.clear() }
                    onFault("输入消费者异常：${e.javaClass.simpleName}；本轮语义分发失效"); break
                }
            }
        }
    }
    override fun close() {
        end()
        synchronized(lock) { closed = true; active = -1; queue.clear(); listeners.clear() }
        (executor as? ExecutorService)?.shutdown()
    }
}
