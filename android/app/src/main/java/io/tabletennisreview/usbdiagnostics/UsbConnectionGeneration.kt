package io.tabletennisreview.usbdiagnostics

/** App generations and observation sequence numbers are NOT physical USB generations. */
enum class OpenPhase { Open, Stopping, Quarantined, Closed }
enum class OpenOrigin { FirstObservedOpen, NormalReopen, ObservationUnknown }
enum class DevicePresence { Present, Absent, QueryFailed }
enum class ObservationSource { DetachBroadcast, Enumeration }
enum class DetachAssociation { Correlated, Unknown, Unrelated }
enum class DetachPolicy { Strict, CorroboratedApplicationEvidence }
data class AssociationDecision(val association: DetachAssociation, val reason: String)
data class CloseReceipt(val token: Long, val closedAtNanos: Long, val normal: Boolean)
class UsbOpenGeneration<C : Any>(
    val token: Long, val owner: Long, val connection: C, val name: String, val id: Int,
    val openedNanos: Long, val coverage: Long, val origin: OpenOrigin,
) {
    @Volatile var logicallyDisconnected = false; internal set
    @Volatile var phase = OpenPhase.Open; internal set
    @Volatile var normalStopRequested = false; internal set
    @Volatile var ioFailureNanos: Long? = null; internal set
    @Volatile var observationConflict = false; internal set
}
data class DetachObservation<C : Any>(
    val number: Long, val captured: UsbOpenGeneration<C>, val capturedToken: Long,
    val connection: C, val managerEpoch: Long, val observedName: String, val observedId: Int,
    val source: ObservationSource, val receivedNanos: Long, val presence: DevicePresence,
    val coverage: Long, val phase: OpenPhase, val requestState: AsyncCleanupState?,
    val ioFailureNanos: Long?,
)

/** Small process-wide ledger: only the active/isolated connection is retained.
 * No Context, executor, Activity or callback is stored. Native work happens outside its lock. */
class UsbGenerationTracker<C : Any> {
    private val observers = mutableSetOf<Long>()
    private var coverage = 0L
    private var observation = 0L
    private var current: UsbOpenGeneration<C>? = null
    private var previousName: String? = null
    private var previousReceipt: CloseReceipt? = null
    @Volatile var lastReceipt: CloseReceipt? = null; private set
    @Synchronized fun observing(owner: Long, active: Boolean) {
        val wasEmpty = observers.isEmpty()
        if (active) observers.add(owner) else observers.remove(owner)
        if (wasEmpty != observers.isEmpty()) {
            coverage++
            current?.observationConflict = true
        }
    }
    @Synchronized fun opened(token: Long, owner: Long, connection: C, name: String, id: Int, now: Long): UsbOpenGeneration<C> {
        check(current == null) { "a generation still owns resources" }
        val origin = when {
            previousName == null -> OpenOrigin.FirstObservedOpen
            previousName == name && previousReceipt?.normal == true -> OpenOrigin.NormalReopen
            else -> OpenOrigin.ObservationUnknown
        }
        return UsbOpenGeneration(token, owner, connection, name, id, now, coverage, origin).also {
            if (!observers.contains(owner)) it.observationConflict = true
            current = it
        }
    }
    @Synchronized fun attached(name: String) {
        observation++
        current?.takeIf { it.name == name }?.observationConflict = true
        // An observed attachment is not a normal open/close transition.
        if (previousName == name) previousReceipt = null
    }
    @Synchronized fun stopping(record: UsbOpenGeneration<C>, normal: Boolean) {
        if (current === record && record.phase != OpenPhase.Closed) {
            if (normal) record.normalStopRequested = true
            if (record.phase == OpenPhase.Open) record.phase = OpenPhase.Stopping
        }
    }
    @Synchronized fun ioFailed(record: UsbOpenGeneration<C>, connection: C, now: Long) {
        if (current === record && record.connection === connection && record.phase != OpenPhase.Closed) {
            if (record.ioFailureNanos == null) record.ioFailureNanos = now
            if (record.phase == OpenPhase.Open) record.phase = OpenPhase.Stopping
        }
    }
    @Synchronized fun capture(record: UsbOpenGeneration<C>, epoch: Long, name: String, id: Int,
        source: ObservationSource, now: Long, presence: DevicePresence, requestState: AsyncCleanupState?): DetachObservation<C> {
        if (current === record && name == record.name && id == record.id &&
            (presence == DevicePresence.QueryFailed || (presence == DevicePresence.Present && record.logicallyDisconnected)))
            record.observationConflict = true
        return DetachObservation(++observation, record, record.token, record.connection, epoch, name, id,
            source, now, presence, coverage, record.phase, requestState, record.ioFailureNanos)
    }

    @Synchronized fun association(snapshot: DetachObservation<C>, token: Long?, connection: C?, policy: DetachPolicy): AssociationDecision {
        val record = snapshot.captured
        if (current !== record || record.token != token || snapshot.capturedToken != token ||
            record.connection !== connection || snapshot.connection !== connection || record.phase == OpenPhase.Closed)
            return AssociationDecision(DetachAssociation.Unrelated, "stale token/record/connection")
        if (snapshot.observedName != record.name || snapshot.observedId != record.id || snapshot.presence == DevicePresence.Present)
            return AssociationDecision(DetachAssociation.Unrelated, "different descriptor or device still present")
        if (snapshot.receivedNanos < record.openedNanos || snapshot.coverage != record.coverage ||
            snapshot.coverage != coverage || record.observationConflict || !observers.contains(record.owner))
            return AssociationDecision(DetachAssociation.Unknown, "observation gap/topology conflict")
        if (snapshot.presence != DevicePresence.Absent || snapshot.source != ObservationSource.DetachBroadcast)
            return AssociationDecision(DetachAssociation.Unknown, "absence query failed or no detach broadcast")
        if (policy == DetachPolicy.Strict)
            return AssociationDecision(DetachAssociation.Unknown, "broadcast has no physical generation proof")
        val io = snapshot.ioFailureNanos
        if (io == null || io < record.openedNanos || io > snapshot.receivedNanos)
            return AssociationDecision(DetachAssociation.Unknown, "no independent I/O failure before snapshot")
        return AssociationDecision(DetachAssociation.Correlated, "original connection I/O failure + absence + bound snapshot; not physical proof")
    }
    @Synchronized fun disconnect(snapshot: DetachObservation<C>, token: Long?, connection: C?): Boolean {
        val r = snapshot.captured
        if (current !== r || r.token != token || r.connection !== connection ||
            snapshot.observedName != r.name || snapshot.observedId != r.id || snapshot.presence != DevicePresence.Absent) return false
        r.logicallyDisconnected = true
        if (r.phase == OpenPhase.Open) r.phase = OpenPhase.Stopping
        return true
    }
    @Synchronized fun quarantine(record: UsbOpenGeneration<C>) { check(current === record); record.phase = OpenPhase.Quarantined }
    @Synchronized fun closed(record: UsbOpenGeneration<C>, connection: C, now: Long, normal: Boolean): CloseReceipt {
        check(current === record && record.connection === connection)
        record.phase = OpenPhase.Closed
        val receipt = CloseReceipt(record.token, now, normal && record.normalStopRequested && !record.logicallyDisconnected && record.ioFailureNanos == null && !record.observationConflict)
        lastReceipt = receipt; previousReceipt = receipt; previousName = record.name
        current = null // Receipt contains no connection/request strong references.
        return receipt
    }
}

/** All resource prerequisites are mandatory and tested independently of Android. */
data class SupplementReadiness(
    val sameResources: Boolean, val readersJoined: Boolean, val controlsFinished: Boolean,
    val exclusiveWaitOwner: Boolean, val requestUnconfirmed: Boolean,
    val deviceAbsent: Boolean, val logHealthy: Boolean,
) {
    fun eligible(association: AssociationDecision): Boolean = association.association == DetachAssociation.Correlated &&
        sameResources && readersJoined && controlsFinished && exclusiveWaitOwner && requestUnconfirmed && deviceAbsent && logHealthy
    fun observe(association: AssociationDecision, lifecycle: UsbAsyncCleanupState, io: RequestIo,
        clock: CleanupClock, report: (String) -> Unit): Boolean {
        if (!eligible(association) || !lifecycle.canSupplement || !lifecycle.coordinatorOwns) return false
        return lifecycle.drain(io, clock, true, true, report)
    }
}
