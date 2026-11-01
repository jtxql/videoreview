package io.tabletennisreview.usbdiagnostics

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbConstants
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Coordinator owns lifecycle/control; separate IN readers finish before cleanup. */
class TourBoxUsbManager(context: Context, private val log: (String) -> Unit) {
    private companion object {
        // At most one quarantined session per process. Retain native request/buffer
        // until process exit; a recreated Activity must not silently bypass it.
        @Volatile var quarantined: Pair<UsbDeviceConnection, DualEndpointSession>? = null
    }
    val system: UsbManager = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var claimedIndex: Int? = null
    private var connectedDevice: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val readerActive = AtomicBoolean()
    @Volatile private var stopRequested = false
    @Volatile private var disposed = false
    @Volatile var canListen = false
        private set
    val isListening: Boolean get() = readerActive.get()
    val totalBytes = AtomicLong()
    val readCount = AtomicLong()
    val controlStats = EndpointStats()
    val dataStats = EndpointStats()
    private val dualClaims = mutableListOf<UsbInterface>()
    private var unsafeSession: DualEndpointSession? = null
    @Volatile private var cdcReady = false
    private val cdcBusy = AtomicBoolean()
    private val pendingCdc = AtomicReference<Pair<Long, CdcAction>?>(null)
    private val candidate = UnlockCandidate()
    private data class UnlockRequest(val epoch: Long, val startCapture: () -> Boolean, val captureHealthy: () -> Boolean)
    private val pendingUnlock = AtomicReference<UnlockRequest?>(null)
    val canCdc: Boolean get() = cdcReady && !cdcBusy.get() && !candidate.used() && !stopRequested && !disposed && quarantined == null
    val canUnlock: Boolean get() = canCdc && candidate.ready()
    fun unlockToken(): Long? = if (canUnlock) epoch.get() else null
    fun cdcEvidence() = candidate.summary()

    fun queueUnlock(token: Long, startCapture: () -> Boolean, captureHealthy: () -> Boolean) {
        if (token != epoch.get() || !canUnlock || !cdcBusy.compareAndSet(false, true)) {
            log("[候选 TX] 未发送：确认已过期，或 CDC 返回值/监听会话不满足条件。")
            return
        }
        if (!candidate.reserve()) { cdcBusy.set(false); return }
        pendingUnlock.set(UnlockRequest(token, startCapture, captureHealthy))
        log("[候选 TX] 用户二次确认；本连接机会已占用，取消/失败也不会自动补发。")
    }

    private fun runPendingUnlock(sessionEpoch: Long, session: DualEndpointSession) {
        val command = pendingUnlock.getAndSet(null) ?: return
        try {
            val device = connectedDevice
            val opened = connection
            check(command.epoch == sessionEpoch && epoch.get() == sessionEpoch && !stopRequested && !disposed && cdcReady && session.acceptingControls && quarantined == null) { "会话/监听已失效" }
            check(device != null && opened != null && device.vendorId == 0xCAFE && device.productId == 0x4001 && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "设备/权限/连接已失效" }
            check(dualClaims.size == 2 && dualClaims[0].id == 0 && dualClaims[1].id == 1) { "双接口未占用" }
            val intf = dualClaims[1]
            val endpoint = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                intf.id == 1 && intf.interfaceClass == 10 && intf.alternateSetting == 0 && it.address == 0x02 && it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.maxPacketSize == 64
            } ?: error("接口1缺少已核实的 BULK OUT 0x02")
            check(command.startCapture()) { "完整实验日志无法建立，禁止发送" }
            log("[候选 TX BEGIN] endpoint=0x02 bytes=8 timeout=1000ms HEX=${UnlockCandidate.HEX}\n实际 CDC 返回记录：${candidate.summary()}；IN 已启动并保持接收")
            // Recheck after capture/logging; lifecycle changes cancel unsent requests.
            check(epoch.get() == sessionEpoch && !stopRequested && session.acceptingControls && system.hasPermission(device) && command.captureHealthy()) { "发送前状态或实验日志变化" }
            val before = SystemClock.elapsedRealtime()
            val bytes = UnlockCandidate.payload()
            val result = opened.bulkTransfer(endpoint, bytes, bytes.size, 1000)
            log("[候选 TX END] result=$result expected=8 elapsed=${SystemClock.elapsedRealtime() - before}ms；${if (result == 8) "仅主机侧完整发送，未证明解锁" else "发送失败/短写，禁止补发"}")
            check(result == 8) { "非完整发送 $result，设备可能已收到部分数据" }
            check(command.captureHealthy()) { "实验日志不完整，停止采集" }
            check(epoch.get() == sessionEpoch && session.acceptingControls && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "完成时设备/监听已失效" }
            log("[候选 TX] 继续原始 RX 观察；本连接禁止再发候选或追加 CDC，不解释事件。")
        } catch (e: Exception) {
            log("[候选 TX FAILED] ${e.javaClass.simpleName}: ${e.message}；停止实验，不重试、不补发、不恢复。")
            cdcReady = false
            stopRequested = true
            epoch.incrementAndGet()
        } finally { cdcBusy.set(false) }
    }

    fun queueCdc(action: CdcAction) {
        if (!canCdc || !cdcBusy.compareAndSet(false, true)) {
            log("[CDC] 未发送：需已授权 Lite 的双接口监听处于有效状态，且没有待执行请求。")
            return
        }
        pendingCdc.set(epoch.get() to action)
        log("[CDC ${action.name}] 用户手动请求已排队；不会执行其他步骤。")
    }

    private fun runPendingCdc(sessionEpoch: Long, session: DualEndpointSession) {
        val command = pendingCdc.getAndSet(null) ?: return
        val action = command.second
        try {
            val device = connectedDevice
            val opened = connection
            check(command.first == sessionEpoch && epoch.get() == sessionEpoch && !stopRequested && !disposed && session.acceptingControls && cdcReady && quarantined == null) { "会话已停止或失效" }
            check(device != null && device.vendorId == 0xCAFE && device.productId == 0x4001 && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device) && opened != null) { "设备/权限/连接失效" }
            check(dualClaims.size == 2 && dualClaims[0].id == 0 && dualClaims[0].interfaceClass == 2 && dualClaims[0].interfaceSubclass == 2 && dualClaims[1].id == 1 && dualClaims[1].interfaceClass == 10) { "双接口占用状态不正确" }
            val payload = action.payload()
            val hex = payload?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "无数据 payload"
            log("[CDC ${action.name} BEGIN] EP0 bmRequestType=0x21 bRequest=0x%02X wValue=0x%04X wIndex=0 wLength=${action.expected} timeout=1000ms TX=$hex；RX 保持监听\n${endpointSummary()}".format(action.request, action.value))
            val before = SystemClock.elapsedRealtime()
            val result = opened.controlTransfer(0x21, action.request, action.value, 0, payload, action.expected, 1000)
            log("[CDC ${action.name} END] result=$result expected=${action.expected} elapsed=${SystemClock.elapsedRealtime() - before}ms；仅表示传输结果，不表示旋转功能成功\n${endpointSummary()}")
            check(result == action.expected) { "负返回/短传输/非预期返回：$result，不自动重试" }
            check(epoch.get() == sessionEpoch && session.acceptingControls && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "请求完成时会话/设备已失效" }
            candidate.record(action, result)
            log("[CDC 返回核实] ${candidate.summary()}")
        } catch (e: Exception) {
            log("[CDC ${action.name} FAILED] ${e.javaClass.simpleName}: ${e.message}；停止本轮，不自动重试或恢复。")
            cdcReady = false
            stopRequested = true
            epoch.incrementAndGet()
            pendingCdc.set(null)
        } finally {
            cdcBusy.set(false)
        }
    }
    fun endpointSummary() = controlStats.summary("0x81") + "\n" + dataStats.summary("0x82")
    @Volatile
    var connectedName: String? = null
        private set

    fun devices(): List<UsbDevice> = system.deviceList.values.sortedBy { it.deviceName }

    fun connect(device: UsbDevice, interfaceIndex: Int?, force: Boolean = false) {
        if (disposed) return
        val request = epoch.incrementAndGet()
        stopRequested = true
        cdcReady = false
        canListen = false
        log("已排队连接；先等待读取结束并关闭旧连接。")
        worker.execute {
            if (quarantined != null) { log("连接已隔离，禁止复用；请拔插并重启应用。以前的请求回收未确认。"); return@execute }
            closeInternal("开始新的连接尝试")
            if (epoch.get() == request) connectInternal(device, interfaceIndex, force)
        }
    }

    private fun connectInternal(device: UsbDevice, interfaceIndex: Int?, force: Boolean) {
        if (force && interfaceIndex == null) {
            log("未执行强制占用：请明确选择一个接口。")
            return
        }
        var claimContext = "interfaceIndex=$interfaceIndex, force=$force"
        try {
            val current = system.deviceList[device.deviceName]
            if (current == null || current.deviceId != device.deviceId) {
                log("连接失败：设备已不在枚举列表中，请刷新。")
                return
            }
            if (!system.hasPermission(current)) {
                log("连接失败：尚未取得设备权限，请先申请。")
                return
            }
            if (interfaceIndex != null && interfaceIndex !in 0 until current.interfaceCount) {
                log("连接失败：接口索引无效，请刷新。")
                return
            }
            val opened = system.openDevice(current)
            if (opened == null) {
                log("openDevice 返回 null：设备可能已断开，或系统无法开放访问。")
                return
            }
            connection = opened
            connectedDevice = current
            connectedName = current.deviceName
            log("openDevice 成功：${current.deviceName}")
            if (interfaceIndex == null) {
                log("仅打开设备；未 claim 任何接口，未调用 USB 数据传输。")
                return
            }
            val intf = current.getInterface(interfaceIndex)
            claimContext = "index=$interfaceIndex, id=${intf.id}, alternate=${intf.alternateSetting}, force=$force"
            log("开始 claimInterface($claimContext)")
            val ok = opened.claimInterface(intf, force)
            log("claimInterface($claimContext)=$ok")
            if (ok) {
                claimed = intf
                claimedIndex = interfaceIndex
                canListen = readEndpoint() != null
                log("连接完成；可监听 Interface 1 / 0x82=$canListen；不会自动开始。")
            } else {
                log("系统未提供详细 errno；可能为接口被占用、设备断开或系统限制，不能据此确定原因。")
                closeInternal("claim 失败，关闭已打开的设备")
            }
        } catch (e: Exception) {
            log("连接异常（$claimContext）：${e.javaClass.simpleName}: ${e.message}")
            closeInternal("连接异常后清理")
        }
    }

    fun close(reason: String) {
        if (disposed) return
        epoch.incrementAndGet()
        stopRequested = true
        cdcReady = false
        canListen = false
        worker.execute { closeInternal(reason) }
    }

    fun shutdown() {
        if (disposed) return
        close("Activity 销毁")
        disposed = true
        // Already queued read/cleanup runs to completion; no shutdownNow/interrupt race.
        worker.shutdown()
    }

    private fun readEndpoint(): android.hardware.usb.UsbEndpoint? {
        val device = connectedDevice ?: return null
        val intf = claimed ?: return null
        if (device.vendorId != 0xCAFE || device.productId != 0x4001 ||
            claimedIndex != 1 || intf.id != 1 || intf.alternateSetting != 0 ||
            intf.interfaceClass != 10 || !system.hasPermission(device)) return null
        return (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
            it.address == 0x82 && it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
    }

    fun startListening() {
        if (disposed || !canListen || !readerActive.compareAndSet(false, true)) {
            log("未启动监听：需要 Lite CAFE:4001 的接口索引/ID 1 成功占用，且没有正在退出的读取任务。")
            return
        }
        val request = epoch.get()
        stopRequested = false
        worker.execute {
            try {
                val opened = connection
                val endpoint = readEndpoint()
                if (epoch.get() != request || stopRequested || opened == null || endpoint == null) {
                    log("监听取消：连接或权限状态已变化。")
                    return@execute
                }
                log("手动开始 BULK IN：interface=1 endpoint=0x82 timeout=200ms；不解析事件。")
                val buffer = ByteArray(64)
                var negativeCount = 0L
                var fastFailures = 0
                while (!stopRequested && epoch.get() == request) {
                    val before = SystemClock.elapsedRealtime()
                    val size = opened.bulkTransfer(endpoint, buffer, buffer.size, 200)
                    val elapsed = SystemClock.elapsedRealtime() - before
                    if (size > 0) {
                        dataStats.reads.incrementAndGet()
                        dataStats.bytes.addAndGet(size.toLong())
                        totalBytes.addAndGet(size.toLong())
                        val count = readCount.incrementAndGet()
                        log("[0x82 BULK IN] RX #$count bytes=$size HEX=" + buffer.take(size).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                        fastFailures = 0
                    } else {
                        val device = connectedDevice
                        if (device == null || system.deviceList[device.deviceName]?.deviceId != device.deviceId || !system.hasPermission(device)) {
                            dataStats.errors.incrementAndGet()
                            log("读取停止：设备已断开或权限失效。")
                            canListen = false
                            break
                        }
                        if (size < 0) {
                            dataStats.ambiguous.incrementAndGet()
                            negativeCount++
                            if (negativeCount == 1L) log("读取返回 $size：可能超时或 I/O 错误；重复负返回仅统计，不刷屏。")
                        }
                        fastFailures = if (elapsed < 100) fastFailures + 1 else 0
                        if (fastFailures >= 5) {
                            dataStats.errors.incrementAndGet()
                            log("连续 5 次快速无数据/错误返回，停止监听；无法由返回值确定具体原因。")
                            break
                        }
                        if (elapsed < 20) Thread.sleep(20)
                    }
                }
                log("本轮监听结束：负返回=$negativeCount（超时/错误不可可靠区分）。")
            } catch (e: Exception) {
                dataStats.errors.incrementAndGet()
                canListen = false
                log("读取异常：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (!canListen) closeInternal("读取结束后清理失效连接")
                readerActive.set(false)
                log("读取任务已退出；累计 bytes=${totalBytes.get()} reads=${readCount.get()}。")
            }
        }
    }

    fun stopListening() {
        epoch.incrementAndGet()
        stopRequested = true
        cdcReady = false
        if (readerActive.get()) log("已请求停止；等待当前有限超时读取返回，期间不关闭连接。")
    }

    fun startDualListening(device: UsbDevice, confirmedForce: Boolean) {
        if (disposed || !confirmedForce) return
        if (!readerActive.compareAndSet(false, true)) { log("请先停止现有读取任务，再启动双接口模式。"); return }
        val request = epoch.incrementAndGet()
        stopRequested = true
        canListen = false
        worker.execute {
            try {
                if (quarantined != null) { log("隔离状态，拒绝双端点测试。"); return@execute }
                closeInternal("切换到明确确认的双接口只读实验")
                if (epoch.get() != request || disposed) return@execute
                val current = system.deviceList[device.deviceName]
                require(current != null && current.deviceId == device.deviceId && current.vendorId == 0xCAFE && current.productId == 0x4001 && system.hasPermission(current)) { "Lite 不在列表或未授权" }
                require(current.interfaceCount >= 2) { "缺少双接口" }
                val control = current.getInterface(0)
                val data = current.getInterface(1)
                require(control.id == 0 && control.alternateSetting == 0 && control.interfaceClass == 2 && control.interfaceSubclass == 2 && data.id == 1 && data.alternateSetting == 0 && data.interfaceClass == 10) { "接口描述符不符合已知 Lite" }
                fun endpoint(intf: UsbInterface, address: Int, type: Int) = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                    it.address == address && it.direction == UsbConstants.USB_DIR_IN && it.type == type
                } ?: error("缺少 IN endpoint=$address")
                val ep81 = endpoint(control, 0x81, UsbConstants.USB_ENDPOINT_XFER_INT)
                val ep82 = endpoint(data, 0x82, UsbConstants.USB_ENDPOINT_XFER_BULK)
                require(ep81.maxPacketSize == 8 && ep82.maxPacketSize == 64) { "端点包长与已知描述符不符，停止实验" }
                connection = system.openDevice(current) ?: error("openDevice=null")
                connectedDevice = current
                connectedName = current.deviceName
                val opened = connection!!
                for ((index, intf) in listOf(control, data).withIndex()) {
                    log("双接口 claim 开始 index=$index id=${intf.id} force=true")
                    val ok = opened.claimInterface(intf, true)
                    log("双接口 claim index=$index id=${intf.id} force=true result=$ok")
                    check(ok) { "接口 $index claim 失败，回滚所有已占用接口" }
                    dualClaims.add(intf)
                }
                if (epoch.get() != request) return@execute
                stopRequested = false
                val session = DualEndpointSession(opened, ep81, ep82, controlStats, dataStats,
                    { stopRequested || epoch.get() != request },
                    { system.deviceList[current.deviceName]?.deviceId == current.deviceId && system.hasPermission(current) },
                    { source, bytes, stats ->
                        stats.reads.incrementAndGet(); stats.bytes.addAndGet(bytes.size.toLong())
                        readCount.incrementAndGet(); totalBytes.addAndGet(bytes.size.toLong())
                        log("[$source] RX bytes=${bytes.size} HEX=" + bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                    }, log)
                session.onCoordinatorTick = {
                    runPendingCdc(request, session)
                    runPendingUnlock(request, session)
                }
                cdcReady = true
                log("[CDC] 双接口已占用，A1/A2/A3 可分别手动触发；不会自动初始化。")
                if (!session.run()) {
                    unsafeSession = session
                    quarantined = opened to session
                }
            } catch (e: Exception) {
                log("双接口实验异常：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                closeInternal("双端点会话结束，读取任务已退出")
                readerActive.set(false)
                log("双端点会话结束\n${endpointSummary()}")
            }
        }
    }

    private fun closeInternal(reason: String) {
        cdcReady = false
        candidate.reset()
        if (pendingUnlock.getAndSet(null) != null) log("[候选 TX] 已取消未发送请求，不重发：$reason")
        if (pendingCdc.getAndSet(null) != null) log("[CDC] 取消尚未发送的手动请求：$reason")
        cdcBusy.set(false)
        canListen = false
        if (unsafeSession != null) { log("隔离连接未关闭：仍不能确认 async 请求已回收。禁止继续访问。"); return }
        val opened = connection ?: return
        connection = null
        connectedName = null
        connectedDevice = null
        val intf = claimed
        claimed = null
        claimedIndex = null
        for (dual in dualClaims.asReversed()) {
            try { log("双接口 releaseInterface(id=${dual.id})=${opened.releaseInterface(dual)}") }
            catch (e: Exception) { log("双接口释放 id=${dual.id} 异常：${e.message}") }
        }
        dualClaims.clear()
        try {
            if (intf != null) log("releaseInterface(id=${intf.id})=${opened.releaseInterface(intf)}")
        } catch (e: Exception) {
            log("释放接口异常：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                opened.close()
                log("连接已关闭：$reason")
            } catch (e: Exception) {
                log("关闭连接异常：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
