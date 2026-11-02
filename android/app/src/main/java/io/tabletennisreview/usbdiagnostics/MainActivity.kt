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
    private var diagnosticsDialog: AlertDialog? = null
    private var playerPageVisible = true
    private var hostActive = false
    private var fullscreen = false
    private var fullscreenBack: android.window.OnBackInvokedCallback? = null
    private var discoveryFailed = false
    private val autoOpened = mutableSetOf<String>()
    private val permissionAsked = mutableSetOf<String>()
    private fun deviceKey(device: UsbDevice) = "${device.deviceName}:${device.deviceId}"
    private val autoTick = object : Runnable {
        override fun run() {
            if (!hostActive || destroyed) return
            try {
                val lite = usb.system.deviceList.values.filter { it.vendorId == 0xCAFE && it.productId == 0x4001 }
                val awaiting = lite.singleOrNull { it.deviceName == pendingPermission && it.deviceId == pendingPermissionId }
                if (awaiting != null && usb.system.hasPermission(awaiting)) {
                    cancelPermission()
                    log("[权限核实] 系统已授权当前请求设备，刷新控制偏好并继续自动连接")
                }
                playerPanel.setTourBoxState(lite.size == 1 && usb.system.hasPermission(lite.single()), usb.inputReadySession)
                playerPanel.setTourBoxStatus("TourBox：${usb.automaticStatus} / ${usb.cleanupStatus}")
                if (!discoveryFailed && !usb.isListening && usb.connectedName == null && pendingPermission == null) {
                    if (lite.isEmpty()) playerPanel.setTourBoxStatus("未检测到TourBox Lite；接入后自动连接")
                    if (lite.size > 1) playerPanel.setTourBoxStatus("检测到多个Lite；请仅保留一台或使用USB诊断手动操作")
                    if (lite.size == 1) {
                        val device = lite.single()
                        val key = deviceKey(device)
                        if (!usb.system.hasPermission(device)) {
                            if (permissionAsked.add(key)) requestPermission(device)
                        } else if (autoOpened.add(key)) {
                            log("[自动连接] 匹配唯一Lite；force=true双接口与已验证初始化顺序；失败不重试")
                            usb.startDualListening(device, confirmedForce = true, autoInitialize = true)
                        }
                    }
                }
            } catch (error: Exception) { discoveryFailed = true; log("[自动连接失败] ${error.javaClass.simpleName}: ${error.message}") }
            ui.postDelayed(this, 200)
        }
    }
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
    private var pendingPermissionId: Int? = null
    private var permissionIntent: PendingIntent? = null
    private var receiversRegistered = false
    private val permissionAction get() = "$packageName.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.usbDevice()
            if (device == null || device.deviceName != pendingPermission || device.deviceId != pendingPermissionId) {
                log("忽略过期或无对应请求的 USB 权限回调。")
                return
            }
            cancelPermission()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("权限结果：${device.deviceName}，回调=${if (granted) "允许" else "拒绝"}，当前 hasPermission=${usb.system.hasPermission(device)}")
            publishTourBoxAvailability()
            if (hostActive) { ui.removeCallbacks(autoTick); ui.post(autoTick) }
            showSelected()
        }
    }
    private val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                log("USB 拔出：${device.deviceName}")
                autoOpened.remove(deviceKey(device)); permissionAsked.remove(deviceKey(device))
                usb.detached(device)
                if (pendingPermission == device.deviceName) cancelPermission()
            } else {
                usb.attached(device)
                log("USB 接入：${device.deviceName}；前台自动识别Lite并申请系统权限/初始化。")
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = TourBoxUsbManager(this, ::log) { diagnostics.captureFailure == null && lastExperiment?.isFile == true }
        lastExperiment = File(filesDir, "unlock-experiments").listFiles()?.filter { it.isFile && it.extension == "txt" }?.maxByOrNull { it.lastModified() }
        playerPageVisible = true // Diagnostics is now a transient dialog, never a restored page.
        val version = installedVersion()
        title = "TableTennis Review $version"
        buildUi()
        playerPanel.setVersionLabel(version)
        playerPanel.restoreState(savedInstanceState)
        playerPanel.bindInput(usb.inputEvents)
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
        log("启动：用户已授权Lite前台自动初始化，A1→A0→8字节→完整26字节→94字节；保留手动诊断。")
        log("后台关闭连接；返回前台可建立新会话；全屏旋转不重建Activity。首次系统USB权限仍需用户允许。")
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "v${info.versionName ?: "未知"}（构建 $code）"
    }.getOrDefault("版本未知")

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
        text("$title · USB诊断").textSize = 21f
        text("Lite前台自动连接初始化；系统权限需允许。以下保留手动诊断；强制占用可能临时断开系统驱动。")
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
        root.addView(playerPanel.diagnosticsView(), 0)
        val pages = FrameLayout(this).apply { addView(playerPanel, FrameLayout.LayoutParams(-1, -1)) }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            if (fullscreen) view.setPadding(0, 0, 0, 0) else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        playerPanel.onDiagnosticsRequested = {
            if (diagnosticsDialog == null) {
                playerPanel.setPageVisible(false); playerPageVisible = false
                val dialog = AlertDialog.Builder(this).setTitle("USB诊断 / $title")
                    .setView(diagnosticsPage).setPositiveButton("关闭", null).create()
                diagnosticsDialog = dialog
                dialog.setOnDismissListener {
                    (diagnosticsPage.parent as? android.view.ViewGroup)?.removeView(diagnosticsPage)
                    diagnosticsDialog = null; playerPageVisible = true
                    if (!destroyed && hostActive) playerPanel.setPageVisible(true)
                }
                dialog.show()
                dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(),
                    (resources.displayMetrics.heightPixels * 0.88).toInt())
            }
        }
        playerPanel.onFullscreenChanged = { value ->
            fullscreen = value
            if (Build.VERSION.SDK_INT >= 33) {
                if (value) {
                    val callback = fullscreenBack ?: android.window.OnBackInvokedCallback { playerPanel.exitFullscreen() }.also { fullscreenBack = it }
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                } else fullscreenBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            }
            requestedOrientation = if (value) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.apply {
                    systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (value) hide(android.view.WindowInsets.Type.systemBars()) else show(android.view.WindowInsets.Type.systemBars())
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (value) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_VISIBLE
            }
            host.requestApplyInsets()
        }
        host.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        playerPanel.setPageVisible(true)
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
        cdcButtons.forEach { it.isEnabled = usb.canCdc && !usb.automaticRunning }
        unlockButton.isEnabled = usb.canUnlock && !usb.automaticRunning
        configButton.isEnabled = usb.canConfig94 && !usb.automaticRunning
        exportButton.isEnabled = lastExperiment != null && !usb.isListening
        counters.text = getString(R.string.read_counters, usb.totalBytes.get(), usb.readCount.get(), if (usb.isListening) "监听中/正在停止" else "已停止") + "\n" + usb.endpointSummary()
        status.text = listOf(getString(R.string.connection_status, usb.connectedName ?: "未连接", pendingPermission ?: "无待处理请求"), usb.cleanupStatus, usb.automaticStatus, usb.cdcEvidence()).joinToString("\n")
    }

    private fun requestPermission(target: UsbDevice? = selected) {
        val device = target ?: return
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
            pendingPermissionId = device.deviceId
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
        pendingPermissionId = null
    }
    private fun publishTourBoxAvailability() {
        val lite = usb.system.deviceList.values.filter { it.vendorId == 0xCAFE && it.productId == 0x4001 }
        playerPanel.setTourBoxState(lite.size == 1 && usb.system.hasPermission(lite.single()), usb.inputReadySession)
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
        hostActive = true; autoOpened.clear(); discoveryFailed = false
        playerPanel.setPageVisible(diagnosticsDialog == null)
        playerPanel.onHostStart()
        ui.removeCallbacks(autoTick); ui.postDelayed(autoTick, 200)
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
        hostActive = false // Dismiss must not reactivate playback while entering the background.
        diagnosticsDialog?.dismiss()
        ui.removeCallbacks(autoTick); playerPanel.setTourBoxState(false, null)
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
        ui.removeCallbacks(autoTick)
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
    // API33+ registers OnBackInvokedCallback only while full screen; this is solely the API26–32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    @Deprecated("Legacy back for API 26–32")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33 && playerPanel.exitFullscreen()) return
        super.onBackPressed()
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
