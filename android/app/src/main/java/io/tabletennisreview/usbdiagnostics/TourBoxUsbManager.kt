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
class TourBoxUsbManager(context: Context, private val log: (String) -> Unit, private val captureHealthy: () -> Boolean = { false }) {
    private companion object {
        val leases = ExclusiveUsbLease<UsbConnectionResources>()
        val generations = UsbGenerationTracker<UsbDeviceConnection>()
        val managerIds = AtomicLong()
        val quarantined: UsbConnectionResources? get() = leases.retained
    }
    val system: UsbManager = context.applicationContext.getSystemService(UsbManager::class.java)
    private val managerId = managerIds.incrementAndGet()
    // Strict is the approved fallback; policy is not a physical-generation assertion.
    private val detachPolicy = DetachPolicy.Strict
    @Volatile private var identity: UsbOpenGeneration<UsbDeviceConnection>? = null
    private val detachObservation = AtomicReference<DetachObservation<UsbDeviceConnection>?>(null)
    fun observing(active: Boolean) = generations.observing(managerId, active)
    fun attached(device: UsbDevice) { generations.attached(device.deviceName) }
    private fun owns(key: UsbOpenGeneration<UsbDeviceConnection>?, opened: UsbDeviceConnection?): Boolean =
        key != null && opened != null && identity === key && key.connection === opened && leases.owns(key.token)
    private var leaseToken: Long? = null
    private var resourceRecord: UsbConnectionResources? = null
    private var asyncResources: UsbRequestResources? = null
    @Volatile private var cleanupLabel = "未连接"
    var cleanupStatus: String
        get() = if (identity?.logicallyDisconnected == true && cleanupLabel == "已连接") "已逻辑断开；等待回收结果" else cleanupLabel
        private set(value) { cleanupLabel = value }
    private fun acquireConnection(device: UsbDevice): UsbDeviceConnection? {
        val token = leases.acquire() ?: error("进程中已有连接/隔离所有者，拒绝新 openDevice")
        leaseToken = token
        var opened: UsbDeviceConnection? = null
        try {
            opened = system.openDevice(device)
            if (opened == null) { leases.release(token); leaseToken = null; return null }
            val key = generations.opened(token, managerId, opened, device.deviceName, device.deviceId, SystemClock.elapsedRealtimeNanos())
            identity = key
            detachObservation.set(null)
            cleanupStatus = "已连接"
            log("[open generation] token=$token origin=${key.origin}；应用代次，不是物理插拔代次")
            return opened
        } catch (e: Exception) {
            // If open succeeded but bookkeeping failed, retain this unqueued resource
            // through the ordinary coordinator cleanup instead of releasing ownership.
            if (opened != null) {
                connection = opened
                if (identity == null) {
                    val retained = UsbConnectionResources(token, device.deviceName, device.deviceId, opened, emptyList(), null)
                    resourceRecord = retained
                    leases.retain(token, retained)
                    cleanupStatus = "已逻辑断开；open记录建立异常（隔离）"
                }
            }
            else { leases.release(token); leaseToken = null }
            throw e
        }
    }
    private fun observeMissing(key: UsbOpenGeneration<UsbDeviceConnection>, name: String, id: Int,
        source: ObservationSource, presence: DevicePresence) {
        val snapshot = generations.capture(key, epoch.get(), name, id, source,
            SystemClock.elapsedRealtimeNanos(), presence, asyncResources?.lifecycle?.state)
        val decision = generations.association(snapshot, key.token.takeIf { leases.owns(it) }, key.connection, detachPolicy)
        log("[detach observation] token=${key.token} observation=${snapshot.number} receivedNanos=${snapshot.receivedNanos} openedNanos=${key.openedNanos} epoch=${snapshot.managerEpoch} source=$source phase=${snapshot.phase} request=${snapshot.requestState} presence=$presence classification=${decision.association} reason=${decision.reason}")
        if (decision.association == DetachAssociation.Unrelated) return
        // Every observation is immutable. Do not overwrite an earlier snapshot;
        // contradictions are latched on the generation by the process ledger.
        if (source == ObservationSource.DetachBroadcast) detachObservation.compareAndSet(null, snapshot)
        if (!generations.disconnect(snapshot, key.token.takeIf { leases.owns(it) }, key.connection)) return
        if (!disposed) worker.execute {
            if (owns(key, key.connection)) {
                advanceEpoch(); stopRequested = true
                closeInternal("原连接缺失观察 token=${key.token}")
            }
        }
    }
    fun detached(device: UsbDevice) {
        val key = identity ?: return
        val presence = try {
            if (system.deviceList.containsKey(key.name)) DevicePresence.Present else DevicePresence.Absent
        } catch (_: Exception) { DevicePresence.QueryFailed }
        observeMissing(key, device.deviceName, device.deviceId, ObservationSource.DetachBroadcast, presence)
    }
    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var claimedIndex: Int? = null
    private var connectedDevice: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val input = io.tabletennisreview.input.TourBoxInputPipeline(log) { session -> epoch.get() == session && identity?.logicallyDisconnected != true }
    val inputEvents: io.tabletennisreview.input.TourBoxInputEvents get() = input.events
    private fun advanceEpoch(): Long {
        val next = epoch.incrementAndGet()
        input.end()
        return next
    }
    private val readerActive = AtomicBoolean()
    @Volatile private var stopRequested = false
    @Volatile private var disposed = false
    @Volatile private var listenAvailable = false
    var canListen: Boolean
        get() = listenAvailable && identity?.logicallyDisconnected != true
        private set(value) { listenAvailable = value }
    val isListening: Boolean get() = readerActive.get()
    val totalBytes = AtomicLong()
    val readCount = AtomicLong()
    val controlStats = EndpointStats()
    val dataStats = EndpointStats()
    private val dualClaims = mutableListOf<UsbInterface>()
    @Volatile private var cdcReady = false
    private val cdcBusy = AtomicBoolean()
    private val pendingCdc = AtomicReference<Pair<Long, CdcAction>?>(null)
    private val candidate = UnlockCandidate()
    private data class UnlockRequest(val epoch: Long, val startCapture: () -> Boolean, val captureHealthy: () -> Boolean)
    private val pendingUnlock = AtomicReference<UnlockRequest?>(null)
    private val config94 = Config94Experiment()
    private data class ConfigRequest(val epoch: Long, val healthy: () -> Boolean)
    private val pendingConfig = AtomicReference<ConfigRequest?>(null)
    val canConfig94: Boolean get() = cdcReady && !cdcBusy.get() && !stopRequested && !disposed && identity?.logicallyDisconnected != true && quarantined == null && config94.ready(epoch.get())
    fun configToken(): Long? = if (canConfig94) epoch.get() else null
    fun configEvidence() = config94.summary()
    fun queueConfig94(token: Long, confirmed: Boolean, healthy: () -> Boolean) {
        if (token != epoch.get() || !canConfig94 || !cdcBusy.compareAndSet(false, true)) return
        if (!config94.reserve(token, confirmed)) { cdcBusy.set(false); return }
        pendingConfig.set(ConfigRequest(token, healthy))
        log("[配置94] 用户二次确认，本连接机会已消耗，不重试。")
    }
    private fun runPendingConfig(sessionEpoch: Long, session: DualEndpointSession) {
        val command = pendingConfig.getAndSet(null) ?: return
        try {
            val device = connectedDevice
            val opened = connection
            check(owns(identity, opened) && session.ownsConnection(opened)) { "控制操作的token/原连接所有权失效" }
            check(command.epoch == sessionEpoch && epoch.get() == sessionEpoch && !stopRequested && !disposed && cdcReady && session.acceptingControls && quarantined == null && config94.validReserved(sessionEpoch)) { "会话或回复证据失效" }
            check(device != null && opened != null && device.vendorId == 0xCAFE && device.productId == 0x4001 && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "设备/权限失效" }
            check(dualClaims.size == 2 && dualClaims[0].id == 0 && dualClaims[1].id == 1) { "双接口未占用" }
            val intf = dualClaims[1]
            val endpoint = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                intf.id == 1 && intf.interfaceClass == 10 && intf.alternateSetting == 0 && it.address == 0x02 && it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.maxPacketSize == 64
            } ?: error("接口1 OUT02描述符不符")
            check(command.healthy()) { "完整日志不可用" }
            val bytes = Config94Experiment.payload()
            check(bytes.size == 94 && java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == Config94Experiment.SHA256) { "配置常量校验失败" }
            log("[配置94 BEGIN] endpoint=0x02 bytes=94 timeout=1000ms SHA256=${Config94Experiment.SHA256} HEX=${Config94Experiment.HEX}\n${config94.summary()}")
            check(epoch.get() == sessionEpoch && !stopRequested && session.acceptingControls && command.healthy() && system.hasPermission(device)) { "发送前状态失效" }
            val before = SystemClock.elapsedRealtime()
            val result = opened.bulkTransfer(endpoint, bytes, bytes.size, 1000)
            log("[配置94 END] result=$result expected=94 elapsed=${SystemClock.elapsedRealtime() - before}ms；仅主机侧传输结果，不表示配置生效")
            check(result == 94 && command.healthy()) { "短写/失败/日志故障，不补发" }
            check(epoch.get() == sessionEpoch && session.acceptingControls && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "完成时会话失效" }
            log("[配置94] 保持原始IN观察，本连接不再发送任何初始化。")
        } catch (e: Exception) {
            log("[配置94 FAILED] ${e.javaClass.simpleName}: ${e.message}；停止，不重试或恢复")
            stopRequested = true; cdcReady = false; advanceEpoch()
        } finally { cdcBusy.set(false) }
    }
    val canCdc: Boolean get() = cdcReady && !cdcBusy.get() && !candidate.used() && !stopRequested && !disposed && identity?.logicallyDisconnected != true && quarantined == null
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
            check(owns(identity, opened) && session.ownsConnection(opened)) { "控制操作的token/原连接所有权失效" }
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
            input.expectReply(sessionEpoch, SystemClock.elapsedRealtimeNanos())
            config94.begin(sessionEpoch, SystemClock.elapsedRealtime())
            val result = opened.bulkTransfer(endpoint, bytes, bytes.size, 1000)
            config94.sent(result)
            log("[候选 TX END] result=$result expected=8 elapsed=${SystemClock.elapsedRealtime() - before}ms；${if (result == 8) "仅主机侧完整发送，未证明解锁" else "发送失败/短写，禁止补发"}")
            check(result == 8) { "非完整发送 $result，设备可能已收到部分数据" }
            check(command.captureHealthy()) { "实验日志不完整，停止采集" }
            check(epoch.get() == sessionEpoch && session.acceptingControls && system.deviceList[device.deviceName]?.deviceId == device.deviceId && system.hasPermission(device)) { "完成时设备/监听已失效" }
            log("[候选 TX] 继续原始 RX 观察；本连接禁止再发候选或追加 CDC，不解释事件。")
        } catch (e: Exception) {
            log("[候选 TX FAILED] ${e.javaClass.simpleName}: ${e.message}；停止实验，不重试、不补发、不恢复。")
            cdcReady = false
            stopRequested = true
            advanceEpoch()
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
            check(owns(identity, opened) && session.ownsConnection(opened)) { "控制操作的token/原连接所有权失效" }
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
            config94.record(action, result)
            log("[CDC 返回核实] ${candidate.summary()}")
        } catch (e: Exception) {
            log("[CDC ${action.name} FAILED] ${e.javaClass.simpleName}: ${e.message}；停止本轮，不自动重试或恢复。")
            cdcReady = false
            stopRequested = true
            advanceEpoch()
            pendingCdc.set(null)
        } finally {
            cdcBusy.set(false)
        }
    }
    fun endpointSummary() = controlStats.summary("0x81") + "\n" + dataStats.summary("0x82")
    @Volatile private var connectionName: String? = null
    var connectedName: String?
        get() = if (identity?.logicallyDisconnected == true) null else connectionName
        private set(value) { connectionName = value }

    fun devices(): List<UsbDevice> {
        val key = identity // Capture BEFORE querying, never close a newer generation.
        val devices = try { system.deviceList.values.sortedBy { it.deviceName } }
        catch (e: Exception) {
            if (key != null) observeMissing(key, key.name, key.id, ObservationSource.Enumeration, DevicePresence.QueryFailed)
            throw e
        }
        if (key != null) {
            val present = devices.any { it.deviceName == key.name }
            if (!present || key.logicallyDisconnected)
                observeMissing(key, key.name, key.id, ObservationSource.Enumeration, if (present) DevicePresence.Present else DevicePresence.Absent)
        }
        return devices
    }

    fun connect(device: UsbDevice, interfaceIndex: Int?, force: Boolean = false) {
        if (disposed) return
        identity?.let { generations.stopping(it, true) }
        val request = advanceEpoch()
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
            val opened = acquireConnection(current)
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
        identity?.let { generations.stopping(it, true) }
        advanceEpoch()
        stopRequested = true
        cdcReady = false
        canListen = false
        connectedName = null
        cleanupStatus = "已逻辑断开；等待回收结果"
        worker.execute { closeInternal(reason) }
    }

    fun shutdown() {
        if (disposed) return
        close("Activity 销毁")
        disposed = true
        // Already queued read/cleanup runs to completion; no shutdownNow/interrupt race.
        worker.execute { input.close() }
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
                val generation = identity
                if (!owns(generation, opened) || epoch.get() != request || stopRequested || opened == null || endpoint == null) {
                    log("监听取消：连接或权限状态已变化。")
                    return@execute
                }
                input.begin(request)
                log("手动开始 BULK IN：interface=1 endpoint=0x82 timeout=200ms；不解析事件。")
                val buffer = ByteArray(64)
                var negativeCount = 0L
                var fastFailures = 0
                while (!stopRequested && epoch.get() == request && owns(generation, opened) && generation?.logicallyDisconnected != true) {
                    val before = SystemClock.elapsedRealtime()
                    val size = opened.bulkTransfer(endpoint, buffer, buffer.size, 200)
                    val elapsed = SystemClock.elapsedRealtime() - before
                    if (size > 0) {
                        val receivedNanos = SystemClock.elapsedRealtimeNanos()
                        val receivedMillis = System.currentTimeMillis()
                        dataStats.reads.incrementAndGet()
                        dataStats.bytes.addAndGet(size.toLong())
                        totalBytes.addAndGet(size.toLong())
                        val count = readCount.incrementAndGet()
                        log("[0x82 BULK IN] RX #$count bytes=$size HEX=" + buffer.take(size).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                        input.accept(request, buffer.copyOf(size), receivedNanos, receivedMillis)
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
                input.end()
                readerActive.set(false)
                log("读取任务已退出；累计 bytes=${totalBytes.get()} reads=${readCount.get()}。")
            }
        }
    }

    fun stopListening() {
        identity?.let { generations.stopping(it, true) }
        advanceEpoch()
        stopRequested = true
        cdcReady = false
        if (readerActive.get()) log("已请求停止；等待当前有限超时读取返回，期间不关闭连接。")
    }

    fun startDualListening(device: UsbDevice, confirmedForce: Boolean) {
        if (disposed || !confirmedForce) return
        if (!readerActive.compareAndSet(false, true)) { log("请先停止现有读取任务，再启动双接口模式。"); return }
        identity?.let { generations.stopping(it, true) }
        val request = advanceEpoch()
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
                connection = acquireConnection(current) ?: error("openDevice=null")
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
                input.begin(request)
                val sessionKey = identity ?: error("连接身份丢失")
                val session = DualEndpointSession(opened, ep81, ep82, controlStats, dataStats,
                    { stopRequested || epoch.get() != request || sessionKey.logicallyDisconnected },
                    { system.deviceList[current.deviceName]?.deviceId == current.deviceId && system.hasPermission(current) },
                    { when {
                        system.deviceList[current.deviceName]?.deviceId != current.deviceId -> UsbStopReason.DeviceMissingUnconfirmed
                        !system.hasPermission(current) -> UsbStopReason.PermissionChanged
                        else -> UsbStopReason.IoException } },
                    { source, bytes, stats ->
                        val receivedNanos = SystemClock.elapsedRealtimeNanos()
                        val receivedMillis = System.currentTimeMillis()
                        if (source == "0x82 BULK IN" && config94.observe(request, bytes, SystemClock.elapsedRealtime()))
                            log("[配置94 回复证据] 完整26字节已按流匹配（可跨读取块），RX结束序号=${readCount.get() + 1}；原始块继续完整记录")
                        stats.reads.incrementAndGet(); stats.bytes.addAndGet(bytes.size.toLong())
                        readCount.incrementAndGet(); totalBytes.addAndGet(bytes.size.toLong())
                        log("[$source] RX bytes=${bytes.size} HEX=" + bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                        if (source.startsWith("0x82")) input.accept(request, bytes, receivedNanos, receivedMillis)
                    }, log, failure@ {
                        if (!owns(sessionKey, opened)) return@failure
                        generations.ioFailed(sessionKey, opened, SystemClock.elapsedRealtimeNanos())
                        stopRequested = true; cdcReady = false; canListen = false
                        connectedName = null; cleanupStatus = "已逻辑断开；等待回收结果"
                        advanceEpoch()
                    })
                session.onCoordinatorTick = {
                    runPendingCdc(request, session)
                    runPendingUnlock(request, session)
                    runPendingConfig(request, session)
                }
                cdcReady = true
                log("[CDC] 双接口已占用，A1/A2/A3 可分别手动触发；不会自动初始化。")
                asyncResources = session.run()
                // run returns only after both readers AND the last control action finish.
                connectedName = null
                cleanupStatus = "已逻辑断开；等待回收结果"
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
        input.end()
        cdcReady = false
        candidate.reset()
        config94.reset()
        if (pendingConfig.getAndSet(null) != null) log("[配置94] 取消未发送请求：$reason")
        if (pendingUnlock.getAndSet(null) != null) log("[候选 TX] 已取消未发送请求，不重发：$reason")
        if (pendingCdc.getAndSet(null) != null) log("[CDC] 取消尚未发送的手动请求：$reason")
        cdcBusy.set(false)
        canListen = false
        connectedName = null
        val key = identity
        val opened = connection
        if (resourceRecord == null && opened != null && key != null) {
            resourceRecord = UsbConnectionResources(key.token, key.name, key.id, opened,
                dualClaims.toList() + listOfNotNull(claimed), asyncResources)
        }
        val record = resourceRecord
        if (record == null) {
            if (opened != null) {
                val token = leaseToken
                if (token != null && leases.owns(token)) {
                    val retained = UsbConnectionResources(token, connectedDevice?.deviceName ?: "unregistered", connectedDevice?.deviceId ?: -1,
                        opened, dualClaims.toList() + listOfNotNull(claimed), asyncResources)
                    resourceRecord = retained
                    leases.retain(token, retained)
                }
                cleanupStatus = "已逻辑断开；连接记录不完整（隔离）"
                log(cleanupStatus)
                return // Never release an unregistered native open.
            }
            leaseToken?.let { leases.release(it) }
            leaseToken = null; identity = null
            cleanupStatus = if (quarantined == null) "已逻辑断开；无待回收资源" else "已逻辑断开；进程中有隔离资源"
            return
        }
        if (!owns(key, record.connection) || key?.token != record.token) {
            if (leases.owns(record.token)) leases.retain(record.token, record)
            cleanupStatus = "已逻辑断开；清理所有权不匹配（隔离）"
            log(cleanupStatus)
            return // Refuse native work, including closing a possibly newer connection.
        }
        val async = record.async
        // This coordinator only runs after session.run joined both readers and
        // returned from the final control/OUT operation. No other wait consumer exists.
        val snapshot = detachObservation.get()
        val association = snapshot?.let { generations.association(it, record.token.takeIf { token -> leases.owns(token) }, record.connection, detachPolicy) }
        if (async != null && async.lifecycle.canSupplement) {
            val stillAbsent = try { !system.deviceList.containsKey(record.deviceName) } catch (_: Exception) { false }
            val readiness = SupplementReadiness(
                sameResources = async.connection === record.connection && owns(key, record.connection),
                readersJoined = async.lifecycle.readerExited,
                controlsFinished = true, // serialized worker, after session.run returns
                exclusiveWaitOwner = async.lifecycle.coordinatorOwns,
                requestUnconfirmed = async.lifecycle.canSupplement,
                deviceAbsent = stillAbsent,
                logHealthy = captureHealthy(),
            )
            val eligible = readiness.eligible(association ?: AssociationDecision(DetachAssociation.Unknown, "no bound detach snapshot"))
            log("[supplement gate] token=${record.token} eligible=$eligible association=${association?.association ?: DetachAssociation.Unknown} reason=${association?.reason ?: "no bound detach snapshot"} readiness=$readiness")
            if (eligible) {
                log("[cleanup] token=${record.token} 应用层观察关联；一次有限交接，不证明物理代次，不再cancel/queue")
                if (readiness.observe(checkNotNull(association), async.lifecycle, async, AndroidCleanupClock) { log("[0x81 handoff] $it") }) {
                    val bytes = async.payload()
                    if (bytes.isNotEmpty()) {
                        controlStats.reads.incrementAndGet(); controlStats.bytes.addAndGet(bytes.size.toLong())
                        readCount.incrementAndGet(); totalBytes.addAndGet(bytes.size.toLong())
                        log("[0x81 INTERRUPT IN (交接完成)] RX bytes=${bytes.size} HEX=" + bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) })
                    }
                }
            }
        }
        val finished = record.finish { log("[cleanup token=${record.token}] $it") }
        // Clear Manager's operational fields even when native resources must be retained.
        connection = null; connectedDevice = null; claimed = null; claimedIndex = null
        dualClaims.clear(); asyncResources = null
        if (finished) {
            val receipt = generations.closed(checkNotNull(key), record.connection, SystemClock.elapsedRealtimeNanos(), key.normalStopRequested)
            log("[close receipt] token=${receipt.token} normal=${receipt.normal} request/connection close completed")
            leases.release(record.token)
            detachObservation.set(null)
            resourceRecord = null; identity = null; leaseToken = null
            cleanupStatus = "已逻辑断开；资源回收完成"
            log("连接已关闭：$reason；$cleanupStatus")
        } else {
            generations.quarantine(checkNotNull(key))
            leases.retain(record.token, record)
            cleanupStatus = "已逻辑断开；回收状态未知（隔离）"
            log("$cleanupStatus token=${record.token} state=${async?.lifecycle?.state} reason=${async?.lifecycle?.reason} lastWait=${async?.lifecycle?.lastWaitReason}；不强制释放，不允许新连接")
        }
    }
}
