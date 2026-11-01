package io.tabletennisreview.usbdiagnostics

/** Pure JVM policy: cancellation is not completion; only the original request is proof. */
enum class AsyncCleanupState { NotQueued, Pending, CompletionRetrieved, CleanupUnconfirmed, Closed, Quarantined }
enum class UsbStopReason { UserStop, PhysicalDetachConfirmed, DetachCorrelated, DeviceMissingUnconfirmed, PermissionChanged, WaitNull, WaitOtherRequest, Timeout, IoException }
enum class WaitResult { Original, Null, OtherRequest, Timeout }
interface RequestIo {
    fun cancel(): Boolean
    fun waitFor(timeoutMillis: Long): WaitResult
    fun closeRequest()
}
interface CleanupClock {
    fun millis(): Long
    fun pause(millis: Long)
}
class UsbAsyncCleanupState {
    @Volatile var state = AsyncCleanupState.NotQueued; private set
    @Volatile var reason = UsbStopReason.UserStop; private set
    @Volatile var readerExited = false; private set
    // getId is supported at minSdk26; do not retain a Thread/its callback graph.
    @Suppress("DEPRECATION")
    private fun currentOwnerId() = Thread.currentThread().id
    private var owner = currentOwnerId()
    @Volatile var lastWaitReason: UsbStopReason? = null; private set
    private var handedOff = false
    val coordinatorOwns: Boolean get() = handedOff && owner == currentOwnerId()
    private var initialDrain = false
    private var cancelled = false
    private var supplemental = false
    private var closeAttempted = false
    val canSupplement: Boolean get() = state == AsyncCleanupState.Quarantined && !closeAttempted && !supplemental
    private fun owned() = check(owner == currentOwnerId()) { "requestWait ownership violation" }
    fun queued() { owned(); check(state == AsyncCleanupState.NotQueued || state == AsyncCleanupState.CompletionRetrieved); state = AsyncCleanupState.Pending }
    fun queueRejected() { owned(); check(state == AsyncCleanupState.Pending); state = AsyncCleanupState.NotQueued }
    fun completed() { owned(); check(state == AsyncCleanupState.Pending || state == AsyncCleanupState.CleanupUnconfirmed || state == AsyncCleanupState.Quarantined); state = AsyncCleanupState.CompletionRetrieved }
    fun stopped(why: UsbStopReason) { owned(); reason = why }
    fun readerFinished() { owned(); readerExited = true }
    @Synchronized fun takeOver(readersJoined: Boolean, controlsFinished: Boolean) {
        check(!handedOff && readersJoined && controlsFinished && readerExited) { "reader/control still owns resources" }
        handedOff = true
        owner = currentOwnerId()
    }
    fun drain(io: RequestIo, clock: CleanupClock, confirmedDetach: Boolean, supplement: Boolean, report: (String) -> Unit): Boolean {
        owned()
        if (state == AsyncCleanupState.Closed) return true
        if (closeAttempted) return false
        if (state == AsyncCleanupState.NotQueued || state == AsyncCleanupState.CompletionRetrieved) return true
        if (supplement) {
            if (!confirmedDetach || supplemental) return false
            check(readerExited && handedOff)
            supplemental = true
            reason = UsbStopReason.DetachCorrelated
        } else {
            if (initialDrain) return false
            initialDrain = true
            check(!cancelled)
            cancelled = true // Consumed even when cancel throws; never retry it.
            try { report("cancel=${io.cancel()} (not completion proof)") }
            catch (e: Exception) { report("cancel exception=${e.javaClass.simpleName}: ${e.message}") }
        }
        state = AsyncCleanupState.CleanupUnconfirmed
        val start = clock.millis()
        for (attempt in 1..5) {
            val remaining = 1000 - (clock.millis() - start)
            if (remaining <= 0) break
            val before = clock.millis()
            val result = try { io.waitFor(minOf(200, remaining)) } catch (e: Exception) {
                lastWaitReason = UsbStopReason.IoException
                report("drain exception=${e.javaClass.simpleName}: ${e.message}")
                null
            }
            report("drain supplement=$supplement attempt=$attempt result=$result elapsed=${clock.millis() - before}ms")
            if (result == WaitResult.Original) { completed(); return true }
            lastWaitReason = when (result) {
                WaitResult.Null -> UsbStopReason.WaitNull
                WaitResult.OtherRequest -> UsbStopReason.WaitOtherRequest
                WaitResult.Timeout -> UsbStopReason.Timeout
                else -> UsbStopReason.IoException
            }
            val left = 1000 - (clock.millis() - start)
            if (clock.millis() - before < 20 && left > 0) clock.pause(minOf(20, left))
        }
        state = AsyncCleanupState.Quarantined
        return false
    }
    fun close(io: RequestIo, report: (String) -> Unit): Boolean {
        owned()
        if (state == AsyncCleanupState.Closed) return true
        if (closeAttempted || (state != AsyncCleanupState.NotQueued && state != AsyncCleanupState.CompletionRetrieved)) return false
        closeAttempted = true
        return try { io.closeRequest(); state = AsyncCleanupState.Closed; report("request.close success"); true }
        catch (e: Exception) { state = AsyncCleanupState.Quarantined; report("request.close exception=${e.message}; no retry"); false }
    }
}

/** Acquire BEFORE openDevice. An unresolved owner cannot be replaced by another Manager. */
class ExclusiveUsbLease<T> {
    private var next = 0L
    private var owner: Long? = null
    @Volatile var retained: T? = null; private set
    @Synchronized fun acquire(): Long? { if (owner != null) return null; return (++next).also { owner = it } }
    @Synchronized fun retain(token: Long, value: T) { check(owner == token); check(retained == null || retained === value); retained = value }
    @Synchronized fun release(token: Long) { check(owner == token); retained = null; owner = null }
    @Synchronized fun owns(token: Long) = owner == token
}

/** A broadcast does not carry generation. Reused addresses remain ambiguous. */
data class UsbDetachIdentity(val token: Long, val address: String, val deviceId: Int, val reusedAddress: Boolean) {
    fun matches(observedToken: Long, address: String, deviceId: Int, present: Boolean): Boolean =
        !reusedAddress && token == observedToken && this.address == address && this.deviceId == deviceId && !present
}
interface ConnectionCleanupIo {
    fun releaseInterfaces(report: (String) -> Unit)
    fun closeConnection()
}
class ConnectionCloseOnce {
    private var attempted = false
    private var completed = false
    fun finish(io: ConnectionCleanupIo, report: (String) -> Unit): Boolean {
        if (attempted) return completed
        attempted = true
        try { io.releaseInterfaces(report) } catch (e: Exception) { report("release exception=${e.message}") }
        return try { io.closeConnection(); completed = true; report("connection.close success"); true }
        catch (e: Exception) { report("connection.close exception=${e.message}; no retry"); false }
    }
}
