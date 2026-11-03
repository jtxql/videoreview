package io.tabletennisreview.playback

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.text.InputFilter
import android.view.View
import android.widget.*
import io.tabletennisreview.player.*
import java.util.Locale
import java.util.UUID

/** Current-video view only. No USB/native access and no file IO on this thread. */
class ClipBookmarkDialog(private val activity: Activity, private val store: ClipBookmarkStore,
    private val source: BookmarkSource, private val usable: () -> Boolean,
    private val onSaveCurrent: () -> Unit, private val onRestore: (ClipBookmark) -> Unit,
    private val onClosed: () -> Unit) {
    private val dialog = object : Dialog(activity) { override fun cancel() { requestClose() } }
    private val message = TextView(activity).apply { textSize = 13f }
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val itemButtons = mutableListOf<Button>()
    private val save = Button(activity).apply { text = "保存当前A/B" }
    private val close = Button(activity).apply { text = "关闭" }
    private var snapshot = store.snapshot()
    private var state = PlayerState()
    private var working = false
    private var disposed = false
    private var child: AlertDialog? = null
    private var nameFinished: (() -> Unit)? = null
    private var request: AutoCloseable? = null
    private var subscription: AutoCloseable? = null
    init {
        dialog.setTitle("当前视频的片段书签")
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(12,12,12,12) }
        root.addView(message)
        root.addView(ScrollView(activity).apply { addView(rows) },LinearLayout.LayoutParams(-1,0,1f))
        root.addView(LinearLayout(activity).apply {
            addView(save,LinearLayout.LayoutParams(0,-2,1f)); addView(close,LinearLayout.LayoutParams(0,-2,1f))
        })
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(false); dialog.setOnDismissListener { dispose() }
        save.setOnClickListener { if (save.isEnabled && usable()) onSaveCurrent() }
        close.setOnClickListener { requestClose() }
        render()
        subscription = store.subscribe { value -> if (!disposed) {
            snapshot = value; render()
            if (value.notice != null) message.text = value.notice
        } }
    }
    fun show() { dialog.show(); resize() }
    fun resize() { dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*.94).toInt(),(activity.resources.displayMetrics.heightPixels*.86).toInt()) }
    fun setPlayerState(value: PlayerState) { if (!disposed) { state = value; controls() } }
    fun setWorking(value: Boolean) { if (!disposed) { working = value; controls() } }
    fun showMessage(text: String) { if (!disposed) message.text = text }
    private fun available() = !disposed && !working && state.loaded && !state.busy && state.seekable && usable()
    private fun controls() {
        val ready = available()
        save.isEnabled = ready && snapshot.writable && snapshot.revision >= 0 && runCatching { state.loop.enabled(state.duration) }.isSuccess
        close.isEnabled = !disposed && !working && !state.busy
        itemButtons.forEach { it.isEnabled = ready && (it.tag != "write" || snapshot.writable) }
    }
    private fun render() {
        rows.removeAllViews(); itemButtons.clear()
        val items = snapshot.catalog.forVideo(source.videoUri)
        message.text = snapshot.notice ?: if (items.isEmpty()) "当前视频暂无书签" else "点击书签，从A点按保存倍速循环播放"
        fun button(parent: LinearLayout,label: String,weight: Float = 1f,write: Boolean = false,action: () -> Unit) = Button(activity).apply {
            text = label; textSize = 13f; tag = if (write) "write" else "read"
            parent.addView(this,LinearLayout.LayoutParams(0,-2,weight)); itemButtons += this
            setOnClickListener { if (isEnabled && usable() && child == null) action() }
        }
        items.forEach { item ->
            val row = LinearLayout(activity)
            button(row,String.format(Locale.ROOT,"%s\n%.2fs–%.2fs  %s×",item.name,item.a,item.b,
                java.math.BigDecimal.valueOf(item.speed).stripTrailingZeros().toPlainString()),2.6f) { onRestore(item) }
            button(row,"改名",write = true) { name(item.name,null,null) { clean,done ->
                mutate(BookmarkMutation.Rename(item.id,source.videoUri,clean),done)
            } }
            button(row,"删除",write = true) { confirmDelete(item) }
            rows.addView(row)
        }
        controls()
    }
    fun promptSave(value: ReviewSnapshot, guard: () -> Boolean, finished: () -> Unit) {
        if (disposed || !guard()) { finished(); return }
        working = false
        val next = snapshot.catalog.forVideo(source.videoUri).size+1
        name("片段$next",guard,finished) { clean,done ->
            if (!guard()) { done(false,"原视频会话已变化"); return@name }
            val item = ClipBookmark(UUID.randomUUID().toString(),source.videoUri,clean,value.preset.a,value.preset.b,value.preset.speed,System.currentTimeMillis())
            mutate(BookmarkMutation.Add(item),done)
        }
    }
    private fun name(initial: String, guard: (() -> Boolean)?, finished: (() -> Unit)?,
        action: (String,(Boolean,String)->Unit) -> Unit) {
        if (disposed || child != null) { finished?.invoke(); return }
        nameFinished = finished
        val input = EditText(activity).apply { setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(80)); setText(initial); selectAll() }
        val editor = AlertDialog.Builder(activity).setTitle(if (guard == null) "重命名书签" else "保存片段书签").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        child = editor
        editor.setOnDismissListener {
            if (child === editor) child = null
            val done = nameFinished; nameFinished = null; done?.invoke()
            if (!disposed) controls()
        }
        editor.setOnShowListener { editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (working || disposed || !usable() || guard?.invoke() == false) return@setOnClickListener
            val clean = input.text.toString().trim()
            try { ClipBookmark.validateName(clean) } catch (e: IllegalArgumentException) { input.error = e.message; return@setOnClickListener }
            if (!snapshot.writable || snapshot.revision < 0) { input.error = "书签当前不可写"; return@setOnClickListener }
            working = true; editor.setCancelable(false)
            editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false; editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false; controls()
            action(clean) { success,reason ->
                if (!disposed && child === editor) {
                    working = false; editor.setCancelable(true)
                    if (success) editor.dismiss() else {
                        input.error = reason
                        editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                    controls()
                }
            }
        } }
        editor.show(); controls()
    }
    private fun mutate(mutation: BookmarkMutation, done: (Boolean,String)->Unit) {
        request = store.apply(snapshot.revision,mutation) { result ->
            request = null
            if (!disposed) { snapshot = result.snapshot; render(); message.text = result.message; done(result.success,result.message) }
        }
    }
    private fun confirmDelete(item: ClipBookmark) {
        val confirm = AlertDialog.Builder(activity).setMessage("删除书签“${item.name}”？不会删除视频。")
            .setNegativeButton("取消",null).setPositiveButton("删除",null).create()
        child = confirm
        confirm.setOnDismissListener { if (child === confirm) child = null; if (!disposed) controls() }
        confirm.setOnShowListener { confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (working || disposed || !usable()) return@setOnClickListener
            working = true; confirm.setCancelable(false); controls()
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false; confirm.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            mutate(BookmarkMutation.Delete(item.id,source.videoUri)) { success,reason ->
                working = false; confirm.setCancelable(true)
                if (success) confirm.dismiss() else {
                    message.text = reason; confirm.dismiss()
                    Toast.makeText(activity,reason,Toast.LENGTH_LONG).show()
                }
                controls()
            }
        } }
        confirm.show()
    }
    private fun requestClose() {
        if (disposed) return
        if (working || state.busy || child != null) { Toast.makeText(activity,"请先结束当前操作",Toast.LENGTH_SHORT).show(); return }
        dispose()
    }
    fun dispose(notify: Boolean = true) {
        if (disposed) return
        disposed = true; subscription?.close(); subscription = null; request?.close(); request = null
        nameFinished = null; child?.dismiss(); child = null; dialog.dismiss()
        if (notify) onClosed()
    }
}
