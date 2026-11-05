package io.tabletennisreview.playback

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import android.widget.*

/** User-facing settings only: USB protocol commands never enter this dialog. */
class TourBoxSettingsDialog(private val activity: Activity, private val store: TourBoxMappingStore,
    version: String, initialConnection: String, private val onClosed: () -> Unit) {
    private var edit = MappingEditSession(store.snapshot())
    private val connection = TextView(activity).apply { text = initialConnection; textSize = 14f }
    private val message = TextView(activity).apply { text = edit.initial.notice ?: "修改后点击保存，横竖屏通用"; textSize = 13f }
    private val spinners = linkedMapOf<MappingInput,Spinner>()
    private val sliders = linkedMapOf<RotaryControl,SeekBar>()
    private val sliderLabels = linkedMapOf<RotaryControl,TextView>()
    private val buttons = mutableListOf<Button>()
    private var updating = false
    private var disposed = false
    private var saving: AutoCloseable? = null
    private var subscription: AutoCloseable? = null
    private var discard: AlertDialog? = null
    private val dialog = object : Dialog(activity) { override fun cancel() { requestClose() } }
    init {
        dialog.setTitle("TourBox设置")
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(16,12,16,12) }
        root.addView(connection); root.addView(message)
        root.addView(TextView(activity).apply { text = "TourBox双击启用时，单击约等待400ms；Long/Tall是实体按钮。"; textSize = 12f })
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val density = activity.resources.displayMetrics.density
        rows.addView(TextView(activity).apply {
            text = "灵敏度仅影响旋转逐帧和跳转：低档更细，高档跨度更大；跳转以5秒×倍率计算。其他动作不变。"
            textSize = 12f
        })
        for (control in RotaryControl.entries) {
            val label = TextView(activity).apply { textSize = 14f }
            val slider = SeekBar(activity).apply { max = SensitivityLevel.entries.lastIndex }
            sliderLabels[control] = label; sliders[control] = slider
            rows.addView(label); rows.addView(slider,LinearLayout.LayoutParams(-1,(48*density).toInt()))
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating && !disposed && !edit.saving && edit.revision >= 0) {
                        SensitivityLevel.entries.getOrNull(progress)?.let { level ->
                            edit.draft.setSensitivity(control,level); showSensitivity(control,level)
                            message.text = if (edit.draft.dirty) "有未保存修改" else "修改后点击保存，横竖屏通用"
                        }
                    }
                }
            })
        }
        for(input in MappingInput.entries) {
            val row = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(activity).apply { text = input.label; textSize = 13f },LinearLayout.LayoutParams(0,-2,.42f))
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,MappingAction.entries.map { it.label })
            }
            row.addView(spinner,LinearLayout.LayoutParams(0,(48*density).toInt(),.58f)); rows.addView(row)
            spinners[input] = spinner
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                    if (!updating && !disposed && !edit.saving && edit.revision >= 0) {
                        MappingAction.entries.getOrNull(spinner.selectedItemPosition)?.let { chosen ->
                            if (edit.draft.snapshot().action(input) != chosen) {
                                edit.draft.set(input,chosen)
                                message.text = if (edit.draft.dirty) "有未保存修改" else "修改后点击保存，横竖屏通用"
                            }
                        }
                    }
                }
            }
        }
        root.addView(ScrollView(activity).apply { addView(rows) },LinearLayout.LayoutParams(-1,0,1f))
        val actions = LinearLayout(activity)
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            text = label; textSize = 13f; minHeight = 0; minimumHeight = 0; setPadding(2,0,2,0)
            setOnClickListener { action() }; actions.addView(this,LinearLayout.LayoutParams(0,(44*density).toInt(),1f)); buttons += this
        }
        button("恢复默认") { edit.draft.resetDefaults(); populate(); message.text = "已选择默认映射和1×灵敏度，点击保存后生效" }
        button("保存") { save() }
        button("关闭") { requestClose() }
        root.addView(actions); root.addView(TextView(activity).apply { text = version; textSize = 11f })
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(false); dialog.setOnDismissListener { dispose() }
        populate(); refreshControls()
        subscription = store.subscribe { snapshot ->
            if (!disposed && snapshot.revision >= 0 && !edit.saving && snapshot.revision != edit.revision) {
                if (!edit.draft.dirty || edit.revision < 0) {
                    edit.close(); edit = MappingEditSession(snapshot); populate()
                    message.text = snapshot.notice ?: "修改后点击保存，横竖屏通用"
                } else message.text = "设置已在其他窗口更新，请关闭后重新打开"
                refreshControls()
            }
        }
    }
    fun show() { dialog.show(); resize() }
    fun resize() { dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*.94).toInt(),(activity.resources.displayMetrics.heightPixels*.88).toInt()) }
    fun updateConnection(value: String) { if (!disposed && connection.text.toString() != value) connection.text = value }
    private fun populate() {
        updating = true
        spinners.forEach { (input,spinner) -> spinner.setSelection(MappingAction.entries.indexOf(edit.draft.snapshot().action(input))) }
        sliders.forEach { (control,slider) ->
            val level = edit.draft.snapshot().sensitivity(control)
            slider.progress = SensitivityLevel.entries.indexOf(level); showSensitivity(control,level)
        }
        updating = false
    }
    private fun showSensitivity(control: RotaryControl, level: SensitivityLevel) {
        val label = "${control.name} 灵敏度 ${level.multiplier.toString().removeSuffix(".0")}×"
        sliderLabels[control]?.text = label; sliders[control]?.contentDescription = label
    }
    private fun refreshControls() {
        val enabled = !disposed && !edit.saving && edit.revision >= 0
        spinners.values.forEach { it.isEnabled = enabled }; sliders.values.forEach { it.isEnabled = enabled }; buttons.forEach { it.isEnabled = enabled }
        // Closing is always available while waiting for the initial async read.
        buttons.lastOrNull()?.isEnabled = !disposed && !edit.saving
    }
    private fun save() {
        val token = edit.beginSave(); if (token == 0L) return
        refreshControls(); message.text = "正在保存…"
        saving = store.save(edit.revision,edit.draft.snapshot()) { result ->
            if (!disposed && edit.complete(token,result)) { saving = null; populate(); refreshControls(); message.text = result.message }
        }
    }
    private fun requestClose() {
        if (disposed || discard != null) return
        if (edit.saving) { Toast.makeText(activity,"请等待保存完成",Toast.LENGTH_SHORT).show(); return }
        if (edit.draft.dirty) {
            discard = AlertDialog.Builder(activity).setMessage("设置尚未保存，丢弃修改并关闭？")
                .setNegativeButton("继续编辑",null).setPositiveButton("丢弃") { _,_ -> dispose() }.create().also {
                    it.setOnDismissListener { discard = null }; it.show()
                }
        } else dispose()
    }
    fun dispose() {
        if (disposed) return
        disposed = true; edit.close(); saving?.close(); saving = null; subscription?.close(); subscription = null
        discard?.dismiss(); discard = null; dialog.dismiss(); onClosed()
    }
}
