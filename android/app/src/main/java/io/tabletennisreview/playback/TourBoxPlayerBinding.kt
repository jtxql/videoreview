package io.tabletennisreview.playback

import io.tabletennisreview.input.*
import io.tabletennisreview.player.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

object TourBoxPlayerMap {
    fun command(event: TourBoxInputEvent): PlayerCommand? = when (event) {
        is TourBoxInputEvent.KnobClockwise -> PlayerCommand.StepForward
        is TourBoxInputEvent.KnobCounterClockwise -> PlayerCommand.StepBackward
        is TourBoxInputEvent.ScrollUp -> PlayerCommand.SeekRelative(5.0)
        is TourBoxInputEvent.ScrollDown -> PlayerCommand.SeekRelative(-5.0)
        is TourBoxInputEvent.ButtonDown -> when (event.button) {
            TourBoxButton.KNOB -> PlayerCommand.TogglePaused
            TourBoxButton.SCROLL -> PlayerCommand.CycleSpeed
            TourBoxButton.C1 -> PlayerCommand.MarkA
            TourBoxButton.C2 -> PlayerCommand.MarkB
            TourBoxButton.TOP -> PlayerCommand.ToggleLoop
            else -> null
        }
        else -> null // ButtonUp/unknown/other buttons retain original diagnostics, never act.
    }
}
data class MappingStatus(val enabled: Boolean, val received: Long, val ignored: Long,
    val admitted: Long, val rejected: Long, val cancelled: Long, val pending: Int, val fault: String?, val notice: String?)

/** Only UI executor may call PlayerController. Audit must be a short thread-safe async sink. */
class TourBoxPlayerBinding(
    private val events: TourBoxInputEvents,
    private val player: PlayerController,
    private val ui: Executor,
    private val now: () -> Long, // same elapsedRealtimeNanos clock as the RX stamps
    private val auditSink: (String) -> Unit,
    private val publish: (MappingStatus) -> Unit,
    private val logHealthy: () -> Boolean,
    private val capacity: Int = 32,
    private val maxAgeNanos: Long = 2_000_000_000L,
) : AutoCloseable {
    private data class Target(val revision: Long, val enabled: Boolean, val visible: Boolean,
        val session: Long, val ready: Boolean, val busy: Boolean)
    private data class Pending(val event: TourBoxInputEvent, val command: PlayerCommand,
        val target: Target, val cancellationRecorded: AtomicBoolean = AtomicBoolean())
    private data class InputGate(val enforced: Boolean, val session: Long?)
    @Volatile private var inputGate = InputGate(false, null)
    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    @Volatile private var target = Target(0, false, false, 0, false, false)
    private var closed = false
    private var scheduled = false
    private var fault: String? = null
    private var notice: String? = null
    private var received = 0L
    private var ignored = 0L
    private var admitted = 0L
    private var rejected = 0L
    private var cancelled = 0L
    private val subscription: AutoCloseable
    init {
        require(capacity > 0 && maxAgeNanos > 0)
        subscription = events.subscribe(::receive)
    }
    fun status(): MappingStatus = synchronized(lock) {
        MappingStatus(target.enabled, received, ignored, admitted, rejected, cancelled, queue.size, fault, notice)
    }
    private fun audit(text: String) {
        try { auditSink("[映射] $text") } catch (_: Exception) {
            synchronized(lock) { disableLocked("映射日志回调失败") }
        }
    }
    private fun discardLocked(): Int {
        val n = queue.size
        queue.forEach { it.cancellationRecorded.set(true) }
        cancelled += n; queue.clear()
        return n
    }
    private fun disableLocked(reason: String) {
        target = target.copy(revision = target.revision + 1, enabled = false)
        discardLocked(); fault = reason
    }
    /** UI calls this after publishing player state or changing foreground/page visibility. */
    fun updateTarget(state: PlayerState, visible: Boolean) {
        var discarded = 0
        synchronized(lock) {
            if (closed) return
            val ready = state.loaded && state.sessionId != 0L
            val invalidate = target.session != state.sessionId || (target.visible && !visible) || (target.ready && !ready)
            val revision = target.revision + if (invalidate) 1 else 0
            if (invalidate) discarded = discardLocked()
            target = Target(revision, target.enabled && visible && ready && target.session == state.sessionId, visible, state.sessionId, ready, state.busy)
        }
        if (discarded > 0) audit("CANCEL count=$discarded reason=播放器代次/页面/就绪状态变化")
        schedule()
    }
    /** Host supplies the exact initialized USB epoch; null blocks all input during initialization. */
    fun approveUsbSession(session: Long?) {
        val count = synchronized(lock) {
            if (closed) return
            inputGate = InputGate(true, session)
            target = target.copy(revision = target.revision + 1, enabled = false)
            discardLocked()
        }
        audit("USB READY session=$session cancelled=$count"); schedule()
    }
    private fun inputReady(event: TourBoxInputEvent): Boolean = inputGate.let { !it.enforced || it.session == event.stamp.sessionId }
    fun resetFault() { synchronized(lock) { if (!closed) fault = null } }
    fun setEnabled(value: Boolean): Boolean {
        var count = 0
        val accepted = synchronized(lock) {
            if (closed) return false
            val allowed = value && target.visible && target.ready && logHealthy()
            count = discardLocked()
            target = target.copy(revision = target.revision + 1, enabled = allowed)
            if (allowed) { fault = null; notice = null }
            allowed
        }
        audit("ENABLE=$accepted requested=$value cancelled=$count")
        schedule(); return accepted
    }
    /** A screen action supersedes queued controller work; future inputs may join the new revision. */
    fun cancelPending(reason: String) {
        val count = synchronized(lock) {
            if (closed) return
            target = target.copy(revision = target.revision + 1)
            discardLocked()
        }
        audit("CANCEL count=$count reason=$reason"); schedule()
    }
    private fun receive(event: TourBoxInputEvent) {
        val command = TourBoxPlayerMap.command(event)
        var message: String? = null
        synchronized(lock) {
            if (closed) return
            received++
            if (command == null || !inputReady(event) || !target.enabled || !target.visible || !target.ready) ignored++
            else if (!logHealthy()) { disableLocked("诊断文件写入失败"); cancelled++; message = "FAULT 日志失败；本次输入取消" }
            else {
                var pressure: String? = null
                if (queue.size >= capacity) {
                    // Preserve button actions and order. Rotation work is disposable under pressure,
                    // but the original USB/semantic event is never modified or suppressed.
                    val oldestRotation = queue.firstOrNull { isRotation(it.event) }
                    if (oldestRotation != null) {
                        queue.remove(oldestRotation)
                        if (oldestRotation.cancellationRecorded.compareAndSet(false, true)) cancelled++
                        pressure = "PRESSURE evicted=${origin(oldestRotation.event)} capacity=$capacity"
                    } else {
                        cancelled++
                        pressure = "PRESSURE rejected=${origin(event)} capacity=$capacity；队内均为按键，不挤占"
                    }
                    notice = "积压已跳过旧旋转/拒绝超量动作，控制保持启用；累计取消=$cancelled"
                }
                if (queue.size < capacity) {
                    queue.addLast(Pending(event, command, target))
                    message = listOfNotNull(pressure, "QUEUED ${origin(event)} playerSession=${target.session} command=$command").joinToString("\n")
                } else message = pressure
            }
        }
        message?.let(::audit)
        schedule() // at most one pending Runnable, not an unbounded Handler queue
    }
    private fun schedule() {
        val post = synchronized(lock) { if (closed || scheduled) false else { scheduled = true; true } }
        if (post) try { ui.execute(::drain) } catch (_: RuntimeException) {
            synchronized(lock) { scheduled = false; disableLocked("主线程调度被拒绝") }
            audit("FAULT 主线程调度拒绝，映射停用")
        }
    }
    private fun cancelled(item: Pending, reason: String) {
        if (item.cancellationRecorded.compareAndSet(false, true)) {
            synchronized(lock) { cancelled++ }
            audit("CANCEL ${origin(item.event)} playerSession=${item.target.session} reason=$reason")
        }
    }
    private fun permit(item: Pending): Boolean {
        fun matches(t: Target) = t.enabled && t.visible && t.ready && t.revision == item.target.revision && t.session == item.target.session
        if (!matches(target) || !inputReady(item.event)) { cancelled(item, "桥接/播放器代次失效"); return false }
        val age = now() - item.event.stamp.receivedNanos
        if (age < 0 || !logHealthy()) {
            synchronized(lock) {
                if (matches(target)) disableLocked(if (!logHealthy()) "日志写入失败" else "单调时钟异常")
            }
            cancelled(item, "时钟/日志失效")
            audit("FAULT invalid-input ageNanos=$age")
            schedule(); return false
        }
        if (age > maxAgeNanos) {
            cancelled(item, "过期动作 ageNanos=$age；控制保持启用")
            synchronized(lock) { notice = "已跳过过期动作，控制保持启用；累计取消=$cancelled" }
            schedule(); return false
        }
        // This critical section only registers authorization. No mpv/UI/disk work under the USB gate.
        var allowed = false
        try { events.withCurrent(item.event) { allowed = matches(target) && inputReady(item.event) } }
        catch (_: Exception) {
            synchronized(lock) { if (matches(target)) disableLocked("输入会话核验异常") }
            schedule()
        }
        if (!allowed) cancelled(item, "USB输入会话失效")
        return allowed
    }
    private fun drain() {
        try {
            while (true) {
                val item = synchronized(lock) {
                    if (closed || !target.enabled || target.busy || queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                if (!permit(item)) continue
                val result = try { player.executeGuarded(item.command, { permit(item) }, origin(item.event)) }
                catch (error: Exception) {
                    synchronized(lock) { rejected++; disableLocked("播放器接纳异常:${error.javaClass.simpleName}") }
                    audit("FAULT player-admission ${origin(item.event)} ${error.message}"); break
                }
                synchronized(lock) { if (result is CommandAdmission.Accepted) admitted++ else rejected++ }
                audit("ADMISSION ${origin(item.event)} playerSession=${item.target.session} command=${item.command} result=$result")
                // A correctly admitted controller publishes busy synchronously. Never spin on a rejection.
                if (result is CommandAdmission.Rejected) {
                    synchronized(lock) { notice = "播放器拒绝本次动作：${result.reason}；控制保持启用" }
                    break // Drop this admission, never retry it or disable a healthy input source.
                }
            }
        } finally {
            synchronized(lock) { scheduled = false }
            publish(status())
            val again = synchronized(lock) { !closed && target.enabled && !target.busy && queue.isNotEmpty() }
            if (again) schedule()
        }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            disableLocked("宿主销毁"); closed = true
        }
        // Never wait for the source callback while holding our lock.
        subscription.close(); audit("CLOSE：旧来源授权与待办已撤销")
    }
    private fun isRotation(event: TourBoxInputEvent): Boolean = event is TourBoxInputEvent.KnobClockwise ||
        event is TourBoxInputEvent.KnobCounterClockwise || event is TourBoxInputEvent.ScrollUp || event is TourBoxInputEvent.ScrollDown
    private fun origin(event: TourBoxInputEvent): String =
        "TourBox usbSession=${event.stamp.sessionId} seq=${event.stamp.sequence} raw=0x%02X".format(event.stamp.rawCode)
}
