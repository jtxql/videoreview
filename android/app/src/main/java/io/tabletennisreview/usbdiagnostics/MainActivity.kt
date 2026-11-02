package io.tabletennisreview.usbdiagnostics

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.*
import java.io.File
import io.tabletennisreview.playback.PlayerPanel

class MainActivity : Activity() {
    private lateinit var playerPanel: PlayerPanel
    private var playerPageVisible = true
    private lateinit var usb: TourBoxUsbManager
    private val diagnostics = DiagnosticLog()
    private val uiLogWriter = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "TourBox-ui-log") }
    private lateinit var deviceSpinner: Spinner
    private lateinit var interfaceSpinner: Spinner
    private lateinit var details: TextView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var permissionButton: Button
    private lateinit var connectButton: Button
    private lateinit var forceClaim: CheckBox
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var counters: TextView
    private lateinit var dualButton: Button
    private var dualConfirmation: AlertDialog? = null
    private val cdcButtons = mutableListOf<Button>()
    private lateinit var configButton: Button
    private var configConfirmation: AlertDialog? = null
    private lateinit var unlockButton: Button
    private lateinit var exportButton: Button
    private lateinit var evidenceView: TextView
    private var unlockConfirmation: AlertDialog? = null
    @Volatile private var lastExperiment: File? = null
    private var exportFile: File? = null
    private val captureFailureReported = java.util.concurrent.atomic.AtomicBoolean()
    private val ui = Handler(Looper.getMainLooper())
    private val renderQueued = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var destroyed = false
    private val render = Runnable {
        renderQueued.set(false)
        if (!destroyed) {
            logView.text = diagnostics.text()
            evidenceView.text = diagnostics.evidence()
            showSelected()
        }
    }
    private var devices = emptyList<UsbDevice>()
    private var selected: UsbDevice? = null
    private var pendingPermission: String? = null
    private var permissionIntent: PendingIntent? = null
    private var receiversRegistered = false
    private val permissionAction get() = "$packageName.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.usbDevice()
            if (device == null || device.deviceName != pendingPermission) {
                log("忽略过期或无对应请求的 USB 权限回调。")
                return
            }
            pendingPermission = null
            permissionIntent?.cancel()
            permissionIntent = null
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("权限结果：${device.deviceName}，回调=${if (granted) "允许" else "拒绝"}，当前 hasPermission=${usb.system.hasPermission(device)}")
            showSelected()
        }
    }
    private val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                log("USB 拔出：${device.deviceName}")
                usb.detached(device)
                if (pendingPermission == device.deviceName) cancelPermission()
            } else {
                usb.attached(device)
                log("USB 接入：${device.deviceName}；未自动申请权限或连接。")
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = TourBoxUsbManager(this, ::log) { diagnostics.captureFailure == null && lastExperiment?.isFile == true }
        lastExperiment = File(filesDir, "unlock-experiments").listFiles()?.filter { it.isFile && it.extension == "txt" }?.maxByOrNull { it.lastModified() }
        playerPageVisible = savedInstanceState?.getBoolean("review.playerPage", true) ?: true
        buildUi()
        playerPanel.restoreState(savedInstanceState)
        // Capture CDC and pre-send failures too, even if the unlock button stays disabled.
        val sessionFile = File(filesDir, "unlock-experiments/diagnostics-${System.currentTimeMillis()}-${System.nanoTime()}.txt")
        if (diagnostics.startCapture(sessionFile)) lastExperiment = sessionFile
        val permissionFilter = IntentFilter(permissionAction)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(attachmentReceiver, attachFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
            registerReceiver(attachmentReceiver, attachFilter)
        }
        receiversRegistered = true
        usb.observing(true)
        log("诊断启动：保留 IN/CDC 实验；候选八字节仅本连接核实 CDC 返回值后手动二次确认发送一次。")
        log("后台/旋转会关闭连接；权限结果不会触发自动连接。")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(20 + bars.left, 12 + bars.top, 20 + bars.right, 12 + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(20, 12 + insets.systemWindowInsetTop, 20, 12 + insets.systemWindowInsetBottom)
            }
            insets
        }
        fun text(value: String) = TextView(this).apply { text = value; root.addView(this) }
        text("TourBox USB Diagnostics · 006Q-B 94字节实验版").textSize = 21f
        text("选择设备 → 申请权限 → 选择接口（可不选）→ 点击连接")
        Button(this).apply { text = "刷新设备"; setOnClickListener { refresh() }; root.addView(this) }
        status = text("未连接")
        text("关键返回值（独立保留，不随 RX 滚动或停止连接清空）")
        evidenceView = text(diagnostics.evidence()).apply { setTextIsSelectable(true) }
        deviceSpinner = Spinner(this).also { root.addView(it) }
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = devices.getOrNull(position)
                if (selected?.deviceName != next?.deviceName) usb.close("切换设备")
                selected = next
                updateInterfaces()
                showSelected()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        permissionButton = Button(this).apply {
            text = "申请所选设备权限"
            setOnClickListener { requestPermission() }
            root.addView(this)
        }
        interfaceSpinner = Spinner(this).also { root.addView(it) }
        forceClaim = CheckBox(this).apply {
            text = getString(R.string.force_claim_option)
            isChecked = false
            isSaveEnabled = false
            root.addView(this)
        }
        text(getString(R.string.force_claim_warning))
        connectButton = Button(this).apply {
            text = "连接所选设备 / 接口"
            setOnClickListener {
                val index = (interfaceSpinner.selectedItemPosition - 1).takeIf { it >= 0 }
                val force = forceClaim.isChecked
                forceClaim.isChecked = false // Opt-in applies to this click only.
                if (force && index == null) {
                    log("未连接：force=true 测试必须先选择一个接口；选项已复位。")
                } else {
                    selected?.let { usb.connect(it, index, force) }
                }
                showSelected()
            }
            root.addView(this)
        }
        Button(this).apply {
            text = "关闭连接"
            setOnClickListener { usb.close("用户操作"); showSelected() }
            root.addView(this)
        }
        startButton = Button(this).apply {
            text = "开始监听 Interface 1 / 0x82"
            setOnClickListener { usb.startListening(); showSelected() }
            root.addView(this)
        }
        stopButton = Button(this).apply {
            text = "停止监听"
            setOnClickListener { usb.stopListening(); showSelected() }
            root.addView(this)
        }
        dualButton = Button(this).apply {
            text = "双接口只读监听（实验）"
            setOnClickListener {
                val device = selected ?: return@setOnClickListener
                dualConfirmation = AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认双接口 force=true 实验")
                    .setMessage("将关闭现有连接，用同一个连接强制占用 Lite 接口 0 和 1，并监听 0x81/0x82。可能临时断开系统驱动。停止会释放两接口并关闭连接。是否明确同意两个接口均 force=true？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认并开始只读监听") { _, _ ->
                        forceClaim.isChecked = false
                        usb.startDualListening(device, confirmedForce = true)
                        showSelected()
                    }.show()
            }
            root.addView(this)
        }
        text("006P：新会话先A1（返回7），再A0（返回0），不要点击A2/A3，再二次确认八字节。A0关闭DTR/RTS，可能影响设备状态。旧A1/A2/A3流程仍保留；传输成功不代表旋转成功，不自动恢复。")
        for (action in CdcAction.entries) {
            cdcButtons.add(Button(this).apply {
                text = action.label
                isEnabled = false
                setOnClickListener { usb.queueCdc(action); showSelected() }
                root.addView(this)
            })
        }
        unlockButton = Button(this).apply {
            text = "单次候选解锁实验（需二次确认）"
            setOnClickListener {
                val token = usb.unlockToken() ?: return@setOnClickListener
                unlockConfirmation = AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认单次候选命令")
                    .setMessage("固定 TX：${UnlockCandidate.HEX}\n接口1 BULK OUT 0x02，1000ms，当前连接最多一次。\n${usb.cdcEvidence()}\n006P应仅执行A1→A0；若混入A2/A3请停止并新建会话。来源为 TuxBox 的 Elite 逆向序列，尚未确认适用于 Lite；可能改变设备状态，无法保证断电恢复。短写/失败不补发，不发送其他配置。确认后保持 IN 接收并保存实验文件。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认发送这8字节一次") { _, _ ->
                        usb.queueUnlock(token, {
                            lastExperiment?.isFile == true && diagnostics.captureFailure == null
                        }, { diagnostics.captureFailure == null })
                        showSelected()
                    }.show()
            }
            root.addView(this)
        }
        configButton = Button(this).apply {
            text = "单次94字节配置实验（需二次确认）"
            isEnabled = false
            setOnClickListener {
                val token = usb.configToken() ?: return@setOnClickListener
                configConfirmation = AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认一次94字节配置")
                    .setMessage("仅本会话A1→A0、八字节发送成功且完整26字节回复核实后允许。\n${usb.configEvidence()}\n接口1 OUT02，94字节，1000ms。\nSHA256=${Config94Experiment.SHA256}\nHEX=${Config94Experiment.HEX}\n可能改变输入模式或设备配置，持久化未知，关闭应用或断电不保证恢复。短写/失败不补发。请先完成发送前基线并加标记；确认后仅一次，保持原始RX。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认发送这94字节一次") { _, _ ->
                        usb.queueConfig94(token, confirmed = true) { lastExperiment?.isFile == true && diagnostics.captureFailure == null }
                        showSelected()
                    }.show()
            }
            root.addView(this)
        }
        exportButton = Button(this).apply {
            text = "导出当前/历史日志（未发送也可导出，请先停止）"
            setOnClickListener {
                if (usb.isListening) { log("请先停止并等待读取退出，再导出文件。"); return@setOnClickListener }
                val history = File(filesDir, "unlock-experiments").listFiles()?.filter { it.isFile && it.extension == "txt" }?.sortedByDescending { it.lastModified() }.orEmpty()
                AlertDialog.Builder(this@MainActivity).setTitle("选择日志：最新在上，含旋转/重启前文件")
                    .setItems(history.map { it.name }.toTypedArray()) { _, index ->
                        exportFile = history[index]
                        try {
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, exportFile!!.name)
                            }, 702)
                        } catch (e: Exception) { log("导出选择器失败：${e.message}") }
                    }.setNegativeButton("取消", null).show()
            }
            root.addView(this)
        }
        val marker = EditText(this).apply {
            hint = "旋转测试标记，例如 A1后：滚轮顺时针10格"
            maxLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
            root.addView(this)
        }
        Button(this).apply {
            text = "添加测试日志标记（不发送 USB）"
            setOnClickListener {
                val value = marker.text.toString().trim().replace('\n', ' ')
                log("[用户测试标记] ${value.ifEmpty { "未填写内容" }}\n${usb.endpointSummary()}")
            }
            root.addView(this)
        }
        counters = text("累计读取：0 字节 / 0 次")
        Button(this).apply {
            text = "复制诊断日志（含累计统计）"
            setOnClickListener {
                try {
                    val snapshot = "关键返回值（本轮保留）\n${diagnostics.evidence()}\n累计 bytes=${usb.totalBytes.get()} reads=${usb.readCount.get()}\n${usb.endpointSummary()}\n${diagnostics.text()}"
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TourBox USB Diagnostics", snapshot))
                    Toast.makeText(this@MainActivity, "已复制保留的完整记录；旧记录淘汰情况见日志首行", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { log("复制失败：${e.javaClass.simpleName}: ${e.message}") }
            }
            root.addView(this)
        }
        details = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val panelHeight = (220 * resources.displayMetrics.density).toInt()
        root.addView(ScrollView(this).apply { addView(details) }, LinearLayout.LayoutParams(-1, panelHeight))
        text("诊断日志（最近 200 条）")
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, panelHeight))
        val diagnosticsPage = ScrollView(this).apply { isFillViewport = true; addView(root) }
        playerPanel = PlayerPanel(this)
        val pages = FrameLayout(this).apply {
            addView(diagnosticsPage, FrameLayout.LayoutParams(-1, -1))
            addView(playerPanel, FrameLayout.LayoutParams(-1, -1))
        }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val tabs = LinearLayout(this)
        fun showPlayer(show: Boolean) {
            playerPanel.setPageVisible(show)
            playerPageVisible = show
            playerPanel.visibility = if (show) View.VISIBLE else View.GONE
            diagnosticsPage.visibility = if (show) View.GONE else View.VISIBLE
        }
        tabs.addView(Button(this).apply { text = "播放器"; setOnClickListener { showPlayer(true) } }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(Button(this).apply { text = "USB诊断"; setOnClickListener { showPlayer(false) } }, LinearLayout.LayoutParams(0, -2, 1f))
        host.addView(tabs)
        host.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        showPlayer(playerPageVisible)
        setContentView(host)
    }

    private fun refresh() {
        try {
            val oldName = selected?.deviceName
            devices = usb.devices()
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { "${UsbDescriptors.hex(it.vendorId)}:${UsbDescriptors.hex(it.productId)} ${it.deviceName}" }.ifEmpty { listOf("未检测到 USB 设备") })
            val index = devices.indexOfFirst { it.deviceName == oldName }.coerceAtLeast(0)
            selected = devices.getOrNull(index)
            deviceSpinner.setSelection(index)
            updateInterfaces()
            showSelected()
            log("枚举完成：${devices.size} 个 USB 设备。")
        } catch (e: Exception) {
            log("枚举失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun updateInterfaces() {
        forceClaim.isChecked = false
        val device = selected
        val labels = mutableListOf("仅 openDevice，不 claim 接口（默认）")
        if (device != null) for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            labels.add("接口[$index] id=${intf.id} alt=${intf.alternateSetting} class=${intf.interfaceClass}")
        }
        interfaceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        interfaceSpinner.setSelection(0)
    }

    private fun showSelected() {
        val device = selected
        val hasPermission = device != null && usb.system.hasPermission(device)
        details.text = if (device == null) "未检测到 USB 设备" else UsbDescriptors.describe(device, hasPermission)
        permissionButton.isEnabled = device != null && pendingPermission == null
        connectButton.isEnabled = device != null && hasPermission
        startButton.isEnabled = usb.canListen && !usb.isListening
        stopButton.isEnabled = usb.isListening
        dualButton.isEnabled = device != null && hasPermission && !usb.isListening
        cdcButtons.forEach { it.isEnabled = usb.canCdc }
        unlockButton.isEnabled = usb.canUnlock
        configButton.isEnabled = usb.canConfig94
        exportButton.isEnabled = lastExperiment != null && !usb.isListening
        counters.text = getString(R.string.read_counters, usb.totalBytes.get(), usb.readCount.get(), if (usb.isListening) "监听中/正在停止" else "已停止") + "\n" + usb.endpointSummary()
        status.text = listOf(getString(R.string.connection_status, usb.connectedName ?: "未连接", pendingPermission ?: "无待处理请求"), usb.cleanupStatus, usb.cdcEvidence()).joinToString("\n")
    }

    private fun requestPermission() {
        val device = selected ?: return
        if (usb.system.hasPermission(device)) {
            log("所选设备已授权；仍需点击连接。")
            return
        }
        try {
            // Immutable token is sufficient: result is checked against UsbManager,
            // and Android USB service supplies the response extras itself.
            val token = PendingIntent.getBroadcast(this, device.deviceId,
                Intent(permissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            permissionIntent = token
            pendingPermission = device.deviceName
            usb.system.requestPermission(device, token)
            log("已请求权限：${device.deviceName}，等待系统结果。")
        } catch (e: Exception) {
            cancelPermission()
            log("申请权限异常：${e.javaClass.simpleName}: ${e.message}")
        }
        showSelected()
    }

    private fun cancelPermission() {
        permissionIntent?.cancel()
        permissionIntent = null
        pendingPermission = null
    }
    private fun log(message: String) {
        // UI interaction messages must not append files on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!uiLogWriter.isShutdown) uiLogWriter.execute { recordLog(message) }
        } else recordLog(message)
    }
    private fun recordLog(message: String) {
        diagnostics.add(message)
        val failure = diagnostics.captureFailure
        if (failure != null && captureFailureReported.compareAndSet(false, true)) {
            diagnostics.add("[实验捕获失败] $failure；停止 USB 实验，勿将该文件当作完整记录。")
            ui.post { if (!destroyed) usb.close("实验文件写入失败") }
        }
        // One pending UI update, not one Runnable per received packet.
        if (!destroyed && renderQueued.compareAndSet(false, true)) ui.postDelayed(render, 100)
    }
    override fun onStart() {
        super.onStart()
        playerPanel.onHostStart()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("review.playerPage", playerPageVisible)
        playerPanel.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        refresh()
    }
    override fun onStop() {
        runCatching { playerPanel.onHostStop() }.onFailure { log("播放器后台清理异常：${it.message}") }
        configConfirmation?.dismiss()
        configConfirmation = null
        unlockConfirmation?.dismiss()
        unlockConfirmation = null
        dualConfirmation?.dismiss()
        dualConfirmation = null
        forceClaim.isChecked = false
        usb.close("Activity 进入后台")
        showSelected()
        super.onStop()
    }
    override fun onDestroy() {
        runCatching { playerPanel.close() }.onFailure { log("播放器销毁异常：${it.message}") }
        destroyed = true
        ui.removeCallbacks(render)
        cancelPermission()
        usb.shutdown()
        uiLogWriter.shutdown()
        if (receiversRegistered) {
            unregisterReceiver(permissionReceiver)
            unregisterReceiver(attachmentReceiver)
            usb.observing(false)
        }
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (playerPanel.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode != 702 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val source = exportFile ?: return
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("无法打开导出目标")
                log("实验文件导出完成：${source.name}；如有 CAPTURE FAILED 标记则不完整。")
            } catch (e: Exception) { log("实验文件导出失败：${e.message}") }
        }.start()
    }
    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
