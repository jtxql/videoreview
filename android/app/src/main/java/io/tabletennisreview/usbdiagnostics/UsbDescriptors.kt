package io.tabletennisreview.usbdiagnostics

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

object UsbDescriptors {
    fun hex(value: Int): String = "0x%04X".format(value)

    fun describe(device: UsbDevice, permission: Boolean): String = buildString {
        appendLine("设备名称：${device.deviceName}")
        appendLine("VID=${hex(device.vendorId)}  PID=${hex(device.productId)}")
        appendLine("设备 class=${device.deviceClass} subclass=${device.deviceSubclass} protocol=${device.deviceProtocol}")
        appendLine("接口数量：${device.interfaceCount}  权限：${if (permission) "已授权" else "未授权"}")
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            appendLine("\n接口索引 $index：id=${intf.id} alternate=${intf.alternateSetting}")
            appendLine("  class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol}")
            appendLine("  endpoint 数量：${intf.endpointCount}")
            for (epIndex in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(epIndex)
                val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    else -> "UNKNOWN(${ep.type})"
                }
                appendLine("  endpoint[$epIndex] 地址=0x%02X  $direction  $type  maxPacketSize=${ep.maxPacketSize}".format(ep.address))
            }
        }
    }
}
