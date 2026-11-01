package io.tabletennisreview.usbdiagnostics

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

internal object AndroidCleanupClock : CleanupClock {
    override fun millis() = SystemClock.elapsedRealtime()
    override fun pause(millis: Long) = Thread.sleep(millis)
}
/** Deliberately contains no Activity, Manager, session, executor or logging callback. */
class UsbRequestResources(val connection: UsbDeviceConnection) : RequestIo {
    val request = UsbRequest()
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(8)
    val lifecycle = UsbAsyncCleanupState()
    override fun cancel() = request.cancel()
    override fun waitFor(timeoutMillis: Long): WaitResult = try {
        val result = connection.requestWait(timeoutMillis)
        when { result === request -> WaitResult.Original; result == null -> WaitResult.Null; else -> WaitResult.OtherRequest }
    } catch (_: TimeoutException) { WaitResult.Timeout }
    override fun closeRequest() = request.close()
    fun payload(): ByteArray {
        val count = buffer.position()
        buffer.flip()
        return ByteArray(count).also { buffer.get(it) }
    }
}
/** A quarantined record holds only native resources and immutable identity, never UI closures. */
class UsbConnectionResources(
    val token: Long, val deviceName: String, val deviceId: Int,
    val connection: UsbDeviceConnection, val interfaces: List<UsbInterface>,
    val async: UsbRequestResources?,
) {
    @Volatile var detachConfirmed = false
    private val closer = ConnectionCloseOnce()
    fun finish(report: (String) -> Unit): Boolean {
        if (async != null && !async.lifecycle.close(async, report)) return false
        return closer.finish(object : ConnectionCleanupIo {
            override fun releaseInterfaces(report: (String) -> Unit) {
                for (intf in interfaces.asReversed()) {
                    try { report("releaseInterface(id=${intf.id})=${connection.releaseInterface(intf)}") }
                    catch (e: Exception) { report("releaseInterface(id=${intf.id}) exception=${e.message}") }
                }
            }
            override fun closeConnection() = connection.close()
        }, report)
    }
}
