package io.tabletennisreview.playback

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.*

/** Static screenshot editor, retained through the system save picker while the Activity stays alive. */
class ScreenshotEditor(private val activity: Activity, private val image: ShotBitmap, private val source: Uri?,
    private val gate: ShotSessionGate, private val token: Long, private val onClosed: () -> Unit,
    private val log: (String) -> Unit) {
    companion object { const val SAVE = 8104 }
    private val document = AnnotationDocument()
    private val canvas = AnnotationView(activity,image.bitmap,document)
    private val writer = ScreenshotWriter(activity.applicationContext)
    private val buttons = mutableListOf<Button>()
    private val status = TextView(activity).apply { setTextColor(android.graphics.Color.WHITE); textSize = 12f; text = "直线 · 红色" }
    private var disposed = false
    private var saveRevision = -1L
    private var discard: AlertDialog? = null
    private val dialog = object : Dialog(activity) { override fun cancel() { requestClose() } }
    init {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(8,8,8,8); setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(canvas,LinearLayout.LayoutParams(-1,0,1f)); root.addView(status)
        fun row() = LinearLayout(activity).also { root.addView(it) }
        fun button(row: LinearLayout, label: String, action: () -> Unit) = Button(activity).apply {
            text = label; textSize = 13f; minHeight = 0; minimumHeight = 0; setPadding(1,0,1,0)
            row.addView(this,LinearLayout.LayoutParams(0,(44*activity.resources.displayMetrics.density).toInt(),1f))
            setOnClickListener { action() }; buttons += this
        }
        val tools = row()
        listOf("直线" to MarkTool.Line,"箭头" to MarkTool.Arrow,"圆圈" to MarkTool.Ellipse).forEach { (label,tool) ->
            button(tools,label) { canvas.cancelStroke(); canvas.tool = tool; describe() }
        }
        val colors = row()
        listOf("红色" to MarkColor.Red,"黄色" to MarkColor.Yellow,"蓝色" to MarkColor.Blue).forEach { (label,color) ->
            button(colors,label) { canvas.cancelStroke(); canvas.color = color; describe() }
        }
        val actions = row()
        button(actions,"撤销") { canvas.cancelStroke(); document.undo(); canvas.invalidate(); describe() }
        button(actions,"清空") { canvas.cancelStroke(); document.clear(); canvas.invalidate(); describe() }
        button(actions,"另存PNG") { pickDestination() }
        button(actions,"关闭") { requestClose() }
        canvas.onChanged = { describe() }
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { dispose() }
        writer.listener = { result ->
            if (!disposed && gate.finishSave(token)) {
                setBusy(false)
                if (result.saved) document.markSaved(saveRevision)
                status.text = result.message; log("SCREENSHOT SAVE saved=${result.saved} ${result.message}")
                Toast.makeText(activity,result.message,Toast.LENGTH_LONG).show()
            }
        }
    }
    fun show() { dialog.show(); resize() }
    fun resize() {
        canvas.cancelStroke()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*.96).toInt(),(activity.resources.displayMetrics.heightPixels*.94).toInt())
    }
    private fun describe() {
        val tool = when(canvas.tool) { MarkTool.Line -> "直线"; MarkTool.Arrow -> "箭头"; MarkTool.Ellipse -> "圆圈" }
        val color = when(canvas.color) { MarkColor.Red -> "红色"; MarkColor.Yellow -> "黄色"; MarkColor.Blue -> "蓝色" }
        status.text = "$tool · $color · ${document.shapes.size}/200${if (document.dirty) " · 未保存" else " · 已保存"}"
    }
    private fun setBusy(value: Boolean) { buttons.forEach { it.isEnabled = !value }; canvas.isEnabled = !value; canvas.cancelStroke() }
    @Suppress("DEPRECATION")
    private fun pickDestination() {
        if (!gate.picking(token)) return
        setBusy(true)
        try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "image/png"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE,"review-shot-${System.currentTimeMillis()}.png")
        },SAVE) } catch(e: Exception) {
            gate.picked(token,false); setBusy(false); status.text = "无法打开保存选择器：${e.message}"
        }
    }
    fun result(resultCode: Int, data: Intent?) {
        if (disposed || !gate.current(token)) return
        val target = data?.data
        val selected = resultCode == Activity.RESULT_OK && target != null && target.scheme == "content"
        if (!gate.picked(token,selected)) return
        if (!selected) { setBusy(false); status.text = "取消另存，编辑内容保留"; return }
        saveRevision = document.revision
        status.text = "正在保存PNG…"
        if (!writer.save(image,document.shapes,checkNotNull(target),source)) {
            gate.finishSave(token); setBusy(false); status.text = "无法开始保存，编辑内容保留"
        }
    }
    private fun requestClose() {
        if (disposed || discard != null) return
        if (writer.busy || gate.phase == ShotPhase.Picking) { Toast.makeText(activity,"请等待保存操作结束",Toast.LENGTH_SHORT).show(); return }
        if (document.dirty) {
            discard = AlertDialog.Builder(activity).setMessage("截图或标注尚未保存，丢弃并关闭？")
                .setNegativeButton("继续编辑",null).setPositiveButton("丢弃") { _,_ -> dispose() }.create().also {
                    it.setOnDismissListener { discard = null }; it.show()
                }
        } else dispose()
    }
    fun dispose() {
        if (disposed) return
        disposed = true; discard?.dismiss(); discard = null
        writer.close(); canvas.onChanged = null; image.close(); dialog.dismiss(); onClosed()
    }
}
