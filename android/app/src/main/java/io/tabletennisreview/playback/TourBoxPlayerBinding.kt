package io.tabletennisreview.playback

import io.tabletennisreview.input.*
import io.tabletennisreview.player.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

object TourBoxPlayerMap {
    private val defaults = MappingConfig.defaults()
    fun command(event: TourBoxInputEvent): PlayerCommand? = MappingInput.fromEvent(event)?.let { defaults.action(it).command() }
}

data class MappingStatus(val enabled: Boolean, val received: Long, val ignored: Long,
    val admitted: Long, val rejected: Long, val cancelled: Long, val pending: Int, val fault: String?, val notice: String?)

/** Only UI executor may call PlayerController. Audit must be a short thread-safe async sink. */
class TourBoxPlayerBinding @JvmOverloads constructor(
    private val events: TourBoxInputEvents,
    private val player: PlayerController,
    private val ui: Executor,
    private val now: () -> Long, // same elapsedRealtimeNanos clock as the RX stamps
    private val gestureTimer: GestureTimer,
    private val auditSink: (String) -> Unit,
    private val publish: (MappingStatus) -> Unit,
    private val logHealthy: () -> Boolean,
    private val capacity: Int = 32,
    private val maxAgeNanos: Long = 2_000_000_000L,
    private val screenshot: (((() -> Boolean), String) -> CommandAdmission)? = null,
    private val bookmarkAction: ((MappingAction, () -> Boolean, String) -> CommandAdmission)? = null,
) : AutoCloseable {
    private data class Target(val revision: Long, val enabled: Boolean, val visible: Boolean,
        val session: Long, val ready: Boolean, val busy: Boolean)
    private data class Pending(val event: TourBoxInputEvent, val action: MappingAction, val configEpoch: Long,
        val target: Target, val command: PlayerCommand?, val part: Int, val parts: Int, val cancellationRecorded: AtomicBoolean = AtomicBoolean())
    private data class InputGate(val enforced: Boolean, val session: Long?)
    @Volatile private var inputGate = InputGate(false, null)
    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    @Volatile private var target = Target(0, false, false, 0, false, false)
    @Volatile private var closed = false
    private val rotationProcessor = RotationProcessor()
    private val gesture = TourBoxButtonGesture()
    private var gestureCancellation: AutoCloseable? = null
    private var gestureDeadline: Long? = null
    private var gestureSerial = 0L
    private var mappings = MappingConfig.defaults()
    private var mappingRevision = 0L
    private var configurationApplied = false
    @Volatile private var configEpoch = 0L
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
        rotationProcessor.reset(); cancelGestureLocked(); gesture.reset()
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
    /** UI applies only committed snapshots. Every real change invalidates all old work/leases. */
    fun updateMappings(config: MappingConfig, revision: Long): Boolean {
        val count: Int
        synchronized(lock) {
            if (closed || revision < 0 || revision < mappingRevision ||
                (configurationApplied && revision == mappingRevision && config != mappings)) return false
            configurationApplied = true
            if (revision == mappingRevision && config == mappings) return true
            mappings = config; mappingRevision = revision; configEpoch++
            target = target.copy(revision = target.revision + 1); count = discardLocked()
        }
        audit("CONFIG revision=$revision cancelled=$count"); schedule(); return true
    }
    /** Accepted screenshot owns its own pause; source identity must remain valid after that pause. */
    private fun captureLease(item: Pending): Boolean {
        val t = target
        if (closed || configEpoch != item.configEpoch || t.session != item.target.session || !t.visible || !t.ready || !inputReady(item.event) || !logHealthy()) return false
        var valid = false
        try { events.withCurrent(item.event) {
            val current = target
            valid = !closed && configEpoch == item.configEpoch && current.session == item.target.session && current.visible && current.ready && inputReady(item.event)
        } } catch (_: Exception) { return false }
        return valid
    }
    private fun cancelGestureLocked() {
        gestureSerial++; gestureDeadline = null
        val cancel = gestureCancellation; gestureCancellation = null; runCatching { cancel?.close() }
    }
    private fun armGestureLocked(deadline: Long?) {
        if (deadline == gestureDeadline && (deadline == null || gestureCancellation != null)) return
        cancelGestureLocked()
        if (deadline == null || closed || !target.enabled) return
        gestureDeadline = deadline
        val serial = gestureSerial; val revision = target.revision; val epoch = configEpoch
        try { gestureCancellation = gestureTimer.schedule(deadline) {
            val messages = mutableListOf<String>()
            synchronized(lock) {
                if (closed || serial != gestureSerial || revision != target.revision || epoch != configEpoch ||
                    !target.enabled || !target.visible || !target.ready) return@synchronized
                gestureCancellation = null; gestureDeadline = null
                val result = gesture.expire(now())
                result.dispatches.forEach { enqueueLocked(it,messages) }
                armGestureLocked(result.deadlineNanos)
            }
            if (messages.isNotEmpty()) audit(messages.joinToString("\n"))
            schedule()
        } } catch (_: RuntimeException) { disableLocked("双击定时器调度失败") }
    }
    private fun receive(event: TourBoxInputEvent) {
        val messages = mutableListOf<String>()
        synchronized(lock) {
            if (closed) return
            received++
            if (!inputReady(event) || !target.enabled || !target.visible || !target.ready) ignored++
            else if (!logHealthy()) { disableLocked("诊断文件写入失败"); cancelled++; messages += "FAULT 日志失败；本次输入取消" }
            else {
                val result = gesture.accept(event,mappings.action(MappingInput.TourBoxDouble) != MappingAction.None,now())
                if (MappingInput.fromEvent(event) == null) ignored++
                result.dispatches.forEach { enqueueLocked(it,messages) }
                if (result.dispatches.isEmpty() && event is TourBoxInputEvent.ButtonDown && event.button == TourBoxButton.TOURBOX)
                    messages += "GESTURE WAIT ${origin(event)} deadline=${result.deadlineNanos}"
                armGestureLocked(result.deadlineNanos)
            }
        }
        if (messages.isNotEmpty()) audit(messages.joinToString("\n"))
        schedule()
    }
    private fun enqueueLocked(dispatch: GestureDispatch, messages: MutableList<String>) {
        val event = dispatch.event; val input = dispatch.input
        if (!inputReady(event)) { ignored++; return }
        val action = mappings.action(input)
        messages += "GESTURE input=${input.id} ${origin(event)}"
        if (action == MappingAction.None) {
            rotationProcessor.plan(input,action,SensitivityLevel.Normal,event.stamp.receivedNanos)
            ignored++; return
        }
    val control = when(input) {
        MappingInput.KnobCW, MappingInput.KnobCCW -> RotaryControl.Knob
        MappingInput.ScrollUp, MappingInput.ScrollDown -> RotaryControl.Scroll
        else -> null
    }
    val plan = rotationProcessor.plan(input,action,control?.let { mappings.sensitivity(it) } ?: SensitivityLevel.Normal,event.stamp.receivedNanos)
    if (plan.repeats == 0) {
        ignored++
        messages += "ACCUMULATED ${origin(event)} action=${action.id} reason=低灵敏度累计，尚未形成播放器命令"
    }
    repeat(plan.repeats) { index ->
        if (queue.size >= capacity) {
            // Retain the established bounded queue policy for EACH derived command.
            val oldestRotation = queue.firstOrNull { isRotation(it.event) && it.action.repeatable }
            if (oldestRotation != null) {
                queue.remove(oldestRotation)
                if (oldestRotation.cancellationRecorded.compareAndSet(false, true)) cancelled++
                messages += "PRESSURE evicted=${origin(oldestRotation)} capacity=$capacity"
            } else {
                cancelled++
                messages += "PRESSURE rejected=${origin(event)} part=${index+1}/${plan.repeats} capacity=$capacity；队内均为受保护动作，不挤占"
            }
            notice = "积压已跳过旧旋转/拒绝超量动作，控制保持启用；累计取消=$cancelled"
        }
        if (queue.size < capacity) {
            val item = Pending(event,action,configEpoch,target,plan.commandOverride ?: action.command(),index+1,plan.repeats)
            queue.addLast(item)
            messages += "QUEUED ${origin(item)} playerSession=${target.session} action=${action.id} command=${item.command}"
        }
    }
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
            audit("CANCEL ${origin(item)} playerSession=${item.target.session} reason=$reason")
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
                val result = try {
                    if (item.action == MappingAction.Screenshot) {
                        if (!captureLease(item)) CommandAdmission.Rejected("截图来源已失效")
                        else screenshot?.invoke({ captureLease(item) },origin(item)) ?: CommandAdmission.Rejected("截图入口不可用")
                    } else if (item.action in listOf(MappingAction.SaveBookmark,MappingAction.PreviousBookmark,MappingAction.NextBookmark)) {
                        if (!captureLease(item)) CommandAdmission.Rejected("书签来源已失效")
                        else bookmarkAction?.invoke(item.action,{ captureLease(item) },origin(item)) ?: CommandAdmission.Rejected("书签入口不可用")
                    } else player.executeGuarded(checkNotNull(item.command), { permit(item) }, origin(item))
                }
                catch (error: Exception) {
                    synchronized(lock) { rejected++; disableLocked("播放器接纳异常:${error.javaClass.simpleName}") }
                    audit("FAULT player-admission ${origin(item)} ${error.message}"); break
                }
                synchronized(lock) { if (result is CommandAdmission.Accepted) admitted++ else rejected++ }
                audit("ADMISSION ${origin(item)} playerSession=${item.target.session} action=${item.action.id} result=$result")
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
    private fun origin(item: Pending): String = "${origin(item.event)} part=${item.part}/${item.parts}"
    private fun origin(event: TourBoxInputEvent): String =
        "TourBox usbSession=${event.stamp.sessionId} seq=${event.stamp.sequence} raw=0x%02X".format(event.stamp.rawCode)
}
