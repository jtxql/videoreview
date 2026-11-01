package io.tabletennisreview.usbdiagnostics

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
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
    private val received: (String, ByteArray, EndpointStats) -> Unit,
    private val log: (String) -> Unit,
) {
    private val failed = AtomicBoolean()
    private val safeToClose = AtomicBoolean(true)
    private val bulkStarted = AtomicBoolean()
    private val interruptStarted = AtomicBoolean()
    // Runs only on the connection coordinator, never on an IN reader or UI thread.
    var onCoordinatorTick: () -> Unit = {}
    val acceptingControls: Boolean get() = !stopping() && bulkStarted.get() && interruptStarted.get()
    private fun stopping() = failed.get() || cancelled()
    private fun fail(endpoint: String, stats: EndpointStats, message: String) {
        stats.errors.incrementAndGet()
        log("[$endpoint] $message；停止双端点会话。")
        failed.set(true)
    }

    /** Called by connection coordinator; returns only after BOTH readers exit. */
    fun run(): Boolean {
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
        log("双端点读取任务均已退出；async 安全回收=${safeToClose.get()}")
        return safeToClose.get()
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
        val request = UsbRequest()
        val buffer = ByteBuffer.allocateDirect(8)
        var pending = false
        try {
            if (!request.initialize(connection, interruptIn)) {
                fail("0x81", controlStats, "UsbRequest.initialize=false")
                return
            }
            while (!stopping()) {
                if (!pending) {
                    buffer.clear()
                    if (!request.queue(buffer)) { fail("0x81", controlStats, "queue=false"); break }
                    pending = true
                    interruptStarted.set(true)
                }
                val completed = try {
                    connection.requestWait(200)
                } catch (_: TimeoutException) {
                    controlStats.timeouts.incrementAndGet()
                    if (!devicePresent()) { fail("0x81", controlStats, "等待超时且设备/权限失效"); break }
                    continue // Keep the SAME request queued; never queue it twice.
                }
                if (completed !== request) {
                    fail("0x81", controlStats, "requestWait 返回 null 或未知请求，停止并撤销")
                    break
                }
                pending = false
                val count = buffer.position()
                if (count > 0) {
                    buffer.flip()
                    val bytes = ByteArray(count)
                    buffer.get(bytes)
                    received("0x81 INTERRUPT IN", bytes, controlStats)
                } else {
                    fail("0x81", controlStats, "异步完成无有效载荷，无法由 Java API 可靠判断传输状态")
                    break
                }
            }
        } catch (e: Exception) {
            fail("0x81", controlStats, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            interruptStarted.set(false)
            if (pending) {
                try {
                    log("[0x81] cancel=${request.cancel()}；等待回收完成")
                    for (attempt in 1..5) {
                        val completed = try { connection.requestWait(200) } catch (_: TimeoutException) { null }
                        if (completed === request) {
                            pending = false
                            // A completion may race with Stop/cancel. Preserve its payload too.
                            val count = buffer.position()
                            if (count > 0) {
                                buffer.flip()
                                val bytes = ByteArray(count)
                                buffer.get(bytes)
                                received("0x81 INTERRUPT IN (停止时完成)", bytes, controlStats)
                            }
                            break
                        }
                    }
                } catch (e: Exception) { log("[0x81] 撤销/回收异常：${e.javaClass.simpleName}: ${e.message}") }
            }
            if (pending) {
                // Retain request and buffer with this session. No unsafe free/close.
                retainedRequest = request
                retainedBuffer = buffer
                safeToClose.set(false)
                fail("0x81", controlStats, "无法确认请求已回收，隔离连接；禁止释放/关闭或重新测试，请拔插设备并重启应用")
            } else {
                try { request.close() }
                catch (e: Exception) { fail("0x81", controlStats, "请求资源释放异常：${e.message}") }
            }
        }
    }

    private var retainedRequest: UsbRequest? = null
    private var retainedBuffer: ByteBuffer? = null
}
