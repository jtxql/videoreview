package io.tabletennisreview.usbdiagnostics

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class EndpointStats {
    val reads = AtomicLong()
    val bytes = AtomicLong()
    val errors = AtomicLong()
    val timeouts = AtomicLong()
    val ambiguous = AtomicLong()
    fun summary(endpoint: String) = "$endpoint reads=${reads.get()} bytes=${bytes.get()} errors=${errors.get()} timeouts=${timeouts.get()} timeoutOrError=${ambiguous.get()}"
}

/** One requestWait owner; synchronous BULK reader never consumes async completions. */
class DualEndpointSession(
    private val connection: UsbDeviceConnection,
    private val interruptIn: UsbEndpoint,
    private val bulkIn: UsbEndpoint,
    private val controlStats: EndpointStats,
    private val dataStats: EndpointStats,
    private val cancelled: () -> Boolean,
    private val devicePresent: () -> Boolean,
    private val unavailableReason: () -> UsbStopReason,
    private val received: (String, ByteArray, EndpointStats) -> Unit,
    private val log: (String) -> Unit,
    private val logicalFailure: () -> Unit,
) {
    private val failed = AtomicBoolean()
    @Volatile private var failureReason = UsbStopReason.UserStop
    @Volatile var requestResources: UsbRequestResources? = null
        private set
    private val bulkStarted = AtomicBoolean()
    private val interruptStarted = AtomicBoolean()
    // Runs only on the connection coordinator, never on an IN reader or UI thread.
    var onCoordinatorTick: () -> Unit = {}
    fun ownsConnection(candidate: UsbDeviceConnection?): Boolean = connection === candidate
    val acceptingControls: Boolean get() = !stopping() && bulkStarted.get() && interruptStarted.get()
    private fun stopping() = failed.get() || cancelled()
    private fun fail(endpoint: String, stats: EndpointStats, message: String) {
        stats.errors.incrementAndGet()
        failureReason = UsbStopReason.IoException
        log("[$endpoint] $message；停止双端点会话。")
        if (failed.compareAndSet(false, true)) logicalFailure()
    }

    /** Called by connection coordinator; returns only after BOTH readers exit. */
    fun run(): UsbRequestResources? {
        val bulk = Thread({ readBulk() }, "TourBox-IN-82")
        val interrupt = Thread({ readInterrupt() }, "TourBox-IN-81")
        val started = mutableListOf<Thread>()
        try {
            bulk.start(); started.add(bulk)
            interrupt.start(); started.add(interrupt)
            while (started.any { it.isAlive }) {
                if (acceptingControls) onCoordinatorTick()
                for (thread in started) thread.join(25)
            }
        } catch (e: Exception) {
            failed.set(true)
            log("双端点协调异常：${e.javaClass.simpleName}: ${e.message}；仍等待两任务回收后返回安全状态。")
        } finally {
            // If a coordinator action throws, stop the readers before joining.
            failed.set(true)
            if (started.size != 2) failed.set(true)
            // No UI join and no interrupt/shutdownNow. Never close under a reader.
            for (thread in started) {
                while (thread.isAlive) {
                    try { thread.join() } catch (_: InterruptedException) { failed.set(true) }
                }
            }
        }
        requestResources?.lifecycle?.takeOver(readersJoined = true, controlsFinished = true)
        log("双端点读取任务均已退出；async state=${requestResources?.lifecycle?.state} reason=${requestResources?.lifecycle?.reason}; owner=coordinator")
        return requestResources
    }

    private fun readBulk() {
        val buffer = ByteArray(64)
        var fastFailures = 0
        var reported = false
        try {
            while (!stopping()) {
                val before = SystemClock.elapsedRealtime()
                bulkStarted.set(true)
                val count = connection.bulkTransfer(bulkIn, buffer, buffer.size, 200)
                val elapsed = SystemClock.elapsedRealtime() - before
                if (count > 0) {
                    received("0x82 BULK IN", buffer.copyOf(count), dataStats)
                    fastFailures = 0
                } else {
                    if (!devicePresent()) { fail("0x82", dataStats, "设备/权限失效"); break }
                    if (count < 0) {
                        dataStats.ambiguous.incrementAndGet()
                        if (!reported) {
                            log("[0x82] 负返回可能是超时或 I/O 错误，不计为已确诊错误；后续只统计。")
                            reported = true
                        }
                    }
                    fastFailures = if (elapsed < 100) fastFailures + 1 else 0
                    if (fastFailures >= 5) { fail("0x82", dataStats, "连续快速失败，具体原因未知"); break }
                    if (elapsed < 20) Thread.sleep(20)
                }
            }
        } catch (e: Exception) { fail("0x82", dataStats, "${e.javaClass.simpleName}: ${e.message}") }
        finally { bulkStarted.set(false) }
    }

    private fun readInterrupt() {
        val resources = UsbRequestResources(connection)
        requestResources = resources
        val life = resources.lifecycle
        val buffer = resources.buffer
        fun emit(source: String) {
            val bytes = resources.payload()
            if (bytes.isNotEmpty()) received(source, bytes, controlStats)
        }
        try {
            if (!resources.request.initialize(connection, interruptIn)) {
                life.stopped(UsbStopReason.IoException)
                fail("0x81", controlStats, "UsbRequest.initialize=false")
                return
            }
            while (!stopping()) {
                if (life.state != AsyncCleanupState.Pending) {
                    buffer.clear()
                    // Conservatively reserve Pending before entering native queue: an
                    // exception must not permit freeing a possibly submitted request.
                    life.queued()
                    if (!resources.request.queue(buffer)) { life.queueRejected(); life.stopped(UsbStopReason.IoException); fail("0x81", controlStats, "queue=false"); break }
                    interruptStarted.set(true)
                }
                when (resources.waitFor(200)) {
                    WaitResult.Timeout -> {
                        controlStats.timeouts.incrementAndGet()
                        if (!devicePresent()) { life.stopped(unavailableReason()); fail("0x81", controlStats, "等待超时；${life.reason}"); break }
                    }
                    WaitResult.Null -> { life.stopped(UsbStopReason.WaitNull); fail("0x81", controlStats, "requestWait=null (error, not timeout)"); break }
                    WaitResult.OtherRequest -> { life.stopped(UsbStopReason.WaitOtherRequest); fail("0x81", controlStats, "requestWait=other request; original still unconfirmed"); break }
                    WaitResult.Original -> {
                        life.completed()
                        if (buffer.position() > 0) emit("0x81 INTERRUPT IN")
                        else { fail("0x81", controlStats, "原请求已取回，但无有效载荷；传输状态未知"); break }
                    }
                }
            }
        } catch (e: Exception) {
            life.stopped(UsbStopReason.IoException)
            fail("0x81", controlStats, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            interruptStarted.set(false)
            try {
                if (life.reason == UsbStopReason.UserStop) life.stopped(failureReason)
                if (life.state == AsyncCleanupState.Pending) {
                    if (!devicePresent()) life.stopped(unavailableReason())
                    if (life.drain(resources, AndroidCleanupClock, false, false) { log("[0x81 cleanup] $it") }) emit("0x81 INTERRUPT IN (停止时完成)")
                }
                life.close(resources) { log("[0x81 cleanup] $it") }
            } finally { life.readerFinished() }
        }
    }
}
