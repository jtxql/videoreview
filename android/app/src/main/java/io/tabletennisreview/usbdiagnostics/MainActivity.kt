package io.tabletennisreview.usbdiagnostics

import android.app.Activity
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
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.tabletennisreview.playback.*
import java.io.File

/** Playback host and automatic USB lifecycle. User settings contain no USB experiment commands. */
class MainActivity : Activity() {
    private lateinit var playerPanel: PlayerPanel
    private lateinit var usb: TourBoxUsbManager
    private lateinit var mappingStore: TourBoxMappingStore
    private var mappingSubscription: AutoCloseable? = null
    private var settingsDialog: TourBoxSettingsDialog? = null
    private var hostActive = false
    private var fullscreen = false
    private var fullscreenBack: android.window.OnBackInvokedCallback? = null
    private var discoveryFailed = false
    private var permissionDenied = false
    private val autoOpened = mutableSetOf<String>()
    private val permissionAsked = mutableSetOf<String>()
    private val diagnostics = DiagnosticLog()
    private val uiLogWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"TourBox-ui-log") }
    private val captureFailureReported = java.util.concurrent.atomic.AtomicBoolean()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var lastExperiment: File? = null
    private var pendingPermission: String? = null
    private var pendingPermissionId: Int? = null
    private var permissionIntent: PendingIntent? = null
    private var receiversRegistered = false
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private fun deviceKey(device: UsbDevice) = "${device.deviceName}:${device.deviceId}"
    private val autoTick = object : Runnable {
        override fun run() {
            if (!hostActive || destroyed) return
            try {
                val lite = usb.system.deviceList.values.filter { it.vendorId == 0xCAFE && it.productId == 0x4001 }
                val awaiting = lite.singleOrNull { it.deviceName == pendingPermission && it.deviceId == pendingPermissionId }
                if (awaiting != null && usb.system.hasPermission(awaiting)) {
                    cancelPermission(); permissionDenied = false
                    log("[权限核实] 系统已授权当前请求设备，继续自动连接")
                }
                playerPanel.setTourBoxState(lite.size == 1 && usb.system.hasPermission(lite.single()),usb.inputReadySession)
                if (!discoveryFailed && !usb.isListening && usb.connectedName == null && pendingPermission == null && lite.size == 1) {
                    val device = lite.single(); val key = deviceKey(device)
                    if (!usb.system.hasPermission(device)) {
                        if (permissionAsked.add(key)) requestPermission(device)
                    } else if (autoOpened.add(key)) {
                        log("[自动连接] 唯一Lite；force=true双接口及既有初始化顺序；失败不重试")
                        usb.startDualListening(device,confirmedForce = true,autoInitialize = true)
                    }
                }
                settingsDialog?.updateConnection(connectionLabel())
            } catch(e: Exception) { discoveryFailed = true; log("[自动连接失败] ${e.javaClass.simpleName}: ${e.message}") }
            ui.postDelayed(this,200)
        }
    }
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.usbDevice()
            if (device == null || device.deviceName != pendingPermission || device.deviceId != pendingPermissionId) {
                log("忽略过期USB权限回调"); return
            }
            cancelPermission()
            val granted = usb.system.hasPermission(device)
            permissionDenied = !granted
            log("USB权限结果：回调=${intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)} 当前=$granted")
            refresh()
            if (hostActive) { ui.removeCallbacks(autoTick); ui.post(autoTick) }
        }
    }
    private val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                log("USB拔出：${device.deviceName}")
                autoOpened.remove(deviceKey(device)); permissionAsked.remove(deviceKey(device)); permissionDenied = false
                usb.detached(device)
                if (pendingPermission == device.deviceName) cancelPermission()
            } else { usb.attached(device); permissionDenied = false; log("USB接入：${device.deviceName}") }
            refresh()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = TourBoxUsbManager(this,::log) { diagnostics.captureFailure == null && lastExperiment?.isFile == true }
        lastExperiment = File(filesDir,"unlock-experiments").listFiles()?.filter { it.isFile && it.extension == "txt" }?.maxByOrNull { it.lastModified() }
        title = "TableTennis Review ${installedVersion()}"
        mappingStore = TourBoxMappingStore.get(applicationContext)
        buildUi()
        playerPanel.restoreState(savedInstanceState); playerPanel.bindInput(usb.inputEvents)
        val sessionFile = File(filesDir,"unlock-experiments/diagnostics-${System.currentTimeMillis()}-${System.nanoTime()}.txt")
        if (diagnostics.startCapture(sessionFile)) lastExperiment = sessionFile
        val permissions = IntentFilter(permissionAction)
        val attachments = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver,permissions,RECEIVER_NOT_EXPORTED)
            registerReceiver(attachmentReceiver,attachments,RECEIVER_NOT_EXPORTED)
        } else { registerReceiver(permissionReceiver,permissions); registerReceiver(attachmentReceiver,attachments) }
        receiversRegistered = true; usb.observing(true)
        log("启动：Lite自动连接A1→A0→8字节→完整26字节→94字节；用户设置不提供USB实验命令")
    }
    @Suppress("DEPRECATION")
    private fun installedVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName,0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "v${info.versionName ?: "未知"}（构建 $code）"
    }.getOrDefault("版本未知")
    private fun buildUi() {
        playerPanel = PlayerPanel(this)
        val pages = FrameLayout(this).apply { addView(playerPanel,FrameLayout.LayoutParams(-1,-1)) }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            if (fullscreen) view.setPadding(0,0,0,0) else view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        playerPanel.onSettingsRequested = {
            if (settingsDialog == null) {
                playerPanel.setPageVisible(false)
                settingsDialog = TourBoxSettingsDialog(this,mappingStore,title.toString(),connectionLabel()) {
                    settingsDialog = null
                    if (!destroyed && hostActive) playerPanel.setPageVisible(true)
                }.also { it.show() }
            }
        }
        playerPanel.onFullscreenChanged = { value ->
            fullscreen = value
            if (Build.VERSION.SDK_INT >= 33) {
                if (value) {
                    val callback = fullscreenBack ?: android.window.OnBackInvokedCallback { playerPanel.exitFullscreen() }.also { fullscreenBack = it }
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
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
        host.addView(pages,LinearLayout.LayoutParams(-1,0,1f)); playerPanel.setPageVisible(true); setContentView(host)
    }
    private fun connectionLabel(): String {
        if (diagnostics.captureFailure != null) return "运行记录异常，连接已停止，请重新启动应用"
        if (usb.cleanupStatus.contains("隔离") || usb.cleanupStatus.contains("未知")) return "连接回收未确认，请完全结束应用进程后重新打开"
        val lite = usb.system.deviceList.values.filter { it.vendorId == 0xCAFE && it.productId == 0x4001 }
        if (lite.size > 1) return "请只连接一台TourBox Lite"
        if (usb.inputReadySession != null) return "TourBox Lite 已连接"
        if (pendingPermission != null) return "请在系统提示中允许访问TourBox Lite"
        if (lite.isEmpty()) return "未连接TourBox Lite，接入后自动连接"
        if (permissionDenied) return "USB权限未允许，请重新接入并允许访问"
        if (usb.automaticRunning) return "正在连接TourBox Lite…"
        if (discoveryFailed || usb.automaticStatus.contains("Failed") || usb.automaticStatus.contains("失败")) return "连接失败，请重新接入设备；若仍失败请重启应用"
        return "正在等待TourBox Lite就绪"
    }
    private fun refresh() {
        if (destroyed) return
        try {
            val lite = usb.system.deviceList.values.filter { it.vendorId == 0xCAFE && it.productId == 0x4001 }
            playerPanel.setTourBoxState(lite.size == 1 && usb.system.hasPermission(lite.single()),usb.inputReadySession)
            settingsDialog?.updateConnection(connectionLabel())
        } catch(e: Exception) { discoveryFailed = true; log("枚举失败：${e.message}") }
    }
    private fun requestPermission(device: UsbDevice) {
        if (usb.system.hasPermission(device)) return
        try {
            val token = PendingIntent.getBroadcast(this,device.deviceId,Intent(permissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            permissionIntent = token; pendingPermission = device.deviceName; pendingPermissionId = device.deviceId
            permissionDenied = false; usb.system.requestPermission(device,token)
            log("已请求USB权限：${device.deviceName}")
        } catch(e: Exception) { cancelPermission(); permissionDenied = true; log("申请USB权限失败：${e.message}") }
    }
    private fun cancelPermission() {
        permissionIntent?.cancel(); permissionIntent = null; pendingPermission = null; pendingPermissionId = null
    }
    private fun log(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!uiLogWriter.isShutdown) uiLogWriter.execute { recordLog(message) }
        } else recordLog(message)
    }
    private fun recordLog(message: String) {
        diagnostics.add(message)
        val failure = diagnostics.captureFailure
        if (failure != null && captureFailureReported.compareAndSet(false,true)) {
            diagnostics.add("[实验捕获失败] $failure；停止USB实验，不把此文件当完整记录")
            ui.post { if (!destroyed) { usb.close("实验文件写入失败"); refresh() } }
        }
    }
    override fun onStart() {
        super.onStart(); hostActive = true; autoOpened.clear(); discoveryFailed = false
        mappingSubscription = mappingStore.subscribe { snapshot -> if (!destroyed && hostActive) playerPanel.applyMappings(snapshot) }
        playerPanel.setPageVisible(settingsDialog == null); playerPanel.onHostStart()
        ui.removeCallbacks(autoTick); ui.postDelayed(autoTick,200)
    }
    override fun onSaveInstanceState(outState: Bundle) { playerPanel.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); refresh() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); settingsDialog?.resize()
    }
    override fun onStop() {
        hostActive = false; mappingSubscription?.close(); mappingSubscription = null
        // Preserve the unsaved settings draft while merely backgrounded; destroy still disposes it.
        ui.removeCallbacks(autoTick); playerPanel.setTourBoxState(false,null)
        runCatching { playerPanel.onHostStop() }.onFailure { log("播放器后台清理异常：${it.message}") }
        usb.close("Activity 进入后台"); super.onStop()
    }
    override fun onDestroy() {
        destroyed = true; mappingSubscription?.close(); mappingSubscription = null; settingsDialog?.dispose(); settingsDialog = null
        runCatching { playerPanel.close() }.onFailure { log("播放器销毁异常：${it.message}") }
        ui.removeCallbacks(autoTick); cancelPermission(); usb.shutdown(); uiLogWriter.shutdown()
        if (receiversRegistered) { unregisterReceiver(permissionReceiver); unregisterReceiver(attachmentReceiver); usb.observing(false) }
        super.onDestroy()
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    @Deprecated("Legacy back for API 26–32")
    override fun onBackPressed() { if (Build.VERSION.SDK_INT < 33 && playerPanel.exitFullscreen()) return; super.onBackPressed() }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data); playerPanel.onActivityResult(requestCode,resultCode,data)
    }
    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
