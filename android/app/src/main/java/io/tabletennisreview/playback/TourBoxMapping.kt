package io.tabletennisreview.playback

import io.tabletennisreview.input.*
import io.tabletennisreview.player.*

enum class MappingInput(val id: String, val label: String) {
    KnobCW("knob-cw","Knob 顺时针"), KnobCCW("knob-ccw","Knob 逆时针"),
    ScrollUp("scroll-up","Scroll 上滚"), ScrollDown("scroll-down","Scroll 下滚"),
    Knob("knob","Knob 按下"), Scroll("scroll","Scroll 按下"), Top("top","Top 按下"),
    C1("c1","C1 按下"), C2("c2","C2 按下"), Short("short","Short 按下"),
    Tall("tall","Long / Tall 按下"), TourBox("tourbox","TourBox 按下");
    companion object {
        @JvmStatic fun fromEvent(event: TourBoxInputEvent): MappingInput? = when(event) {
            is TourBoxInputEvent.KnobClockwise -> KnobCW
            is TourBoxInputEvent.KnobCounterClockwise -> KnobCCW
            is TourBoxInputEvent.ScrollUp -> ScrollUp
            is TourBoxInputEvent.ScrollDown -> ScrollDown
            is TourBoxInputEvent.ButtonDown -> when(event.button) {
                TourBoxButton.KNOB -> Knob; TourBoxButton.SCROLL -> Scroll; TourBoxButton.TOP -> Top
                TourBoxButton.C1 -> C1; TourBoxButton.C2 -> C2; TourBoxButton.SHORT -> Short
                TourBoxButton.TALL -> Tall; TourBoxButton.TOURBOX -> TourBox
            }
            else -> null
        }
    }
}
enum class MappingAction(val id: String, val label: String, val repeatable: Boolean = false) {
    None("none","不执行"), TogglePause("toggle-pause","播放 / 暂停"), Play("play","播放"), Pause("pause","暂停"),
    FrameForward("frame-forward","前进一帧",true), FrameBackward("frame-backward","后退一帧",true),
    SeekForward("seek-forward-5","快进5秒",true), SeekBackward("seek-backward-5","快退5秒",true),
    SpeedUp("speed-up","速度提高一档",true), SpeedDown("speed-down","速度降低一档",true), CycleSpeed("cycle-speed","循环切换速度",true),
    Speed01("speed-01","设为0.1倍",true), Speed025("speed-025","设为0.25倍",true), Speed05("speed-05","设为0.5倍",true),
    Speed075("speed-075","设为0.75倍",true), Speed1("speed-1","设为1倍",true),
    MarkA("mark-a","记A"), MarkB("mark-b","记B"), EnableLoop("enable-ab","启用AB"), ClearLoop("clear-ab","清除AB"),
    ToggleLoop("toggle-ab","启用 / 清除AB"), SeekA("seek-a","回A"), Screenshot("screenshot","截图标注");
    fun command(): PlayerCommand? = when(this) {
        None, Screenshot -> null
        TogglePause -> PlayerCommand.TogglePaused; Play -> PlayerCommand.SetPaused(false); Pause -> PlayerCommand.SetPaused(true)
        FrameForward -> PlayerCommand.StepForward; FrameBackward -> PlayerCommand.StepBackward
        SeekForward -> PlayerCommand.SeekRelative(5.0); SeekBackward -> PlayerCommand.SeekRelative(-5.0)
        SpeedUp -> PlayerCommand.ShiftSpeed(1); SpeedDown -> PlayerCommand.ShiftSpeed(-1); CycleSpeed -> PlayerCommand.CycleSpeed
        Speed01 -> PlayerCommand.SetSpeed(.1); Speed025 -> PlayerCommand.SetSpeed(.25); Speed05 -> PlayerCommand.SetSpeed(.5)
        Speed075 -> PlayerCommand.SetSpeed(.75); Speed1 -> PlayerCommand.SetSpeed(1.0)
        MarkA -> PlayerCommand.MarkA; MarkB -> PlayerCommand.MarkB; EnableLoop -> PlayerCommand.EnableLoop
        ClearLoop -> PlayerCommand.ClearLoop; ToggleLoop -> PlayerCommand.ToggleLoop; SeekA -> PlayerCommand.SeekA
    }
}
data class MappingLoad(val config: MappingConfig, val notice: String?)
class MappingConfig private constructor(private val values: Map<MappingInput,MappingAction>,
    private val sensitivities: Map<RotaryControl,SensitivityLevel> = RotaryControl.entries.associateWith { SensitivityLevel.Normal }) {
    fun action(input: MappingInput): MappingAction = checkNotNull(values[input])
    fun with(input: MappingInput, action: MappingAction) = MappingConfig(values + (input to action),sensitivities)
    fun sensitivity(control: RotaryControl): SensitivityLevel = checkNotNull(sensitivities[control])
    fun withSensitivity(control: RotaryControl, level: SensitivityLevel) = MappingConfig(values,sensitivities + (control to level))
    fun encode(): String = "version=1\n" + MappingInput.entries.joinToString("\n") { "${it.id}=${action(it).id}" } +
        "\n" + RotaryControl.entries.joinToString("\n") { "${it.id}-sensitivity=${sensitivity(it).id}" }
    override fun equals(other: Any?) = other is MappingConfig && values == other.values && sensitivities == other.sensitivities
    override fun hashCode() = 31 * values.hashCode() + sensitivities.hashCode()
    companion object {
        @JvmStatic fun defaults() = MappingConfig(MappingInput.entries.associateWith { when(it) {
            MappingInput.KnobCW -> MappingAction.FrameForward; MappingInput.KnobCCW -> MappingAction.FrameBackward
            MappingInput.ScrollUp -> MappingAction.SeekForward; MappingInput.ScrollDown -> MappingAction.SeekBackward
            MappingInput.Knob -> MappingAction.TogglePause; MappingInput.Scroll -> MappingAction.CycleSpeed
            MappingInput.Top -> MappingAction.ToggleLoop; MappingInput.C1 -> MappingAction.MarkA; MappingInput.C2 -> MappingAction.MarkB
            else -> MappingAction.None
        } })
        @JvmStatic fun decode(text: String?): MappingLoad {
            val defaults = defaults()
            if (text == null) return MappingLoad(defaults,null)
            if (text.length > 8192) return MappingLoad(defaults,"配置无法读取，已使用默认映射")
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.count { it == "version=1" } != 1 || lines.any { it.startsWith("version=") && it != "version=1" })
                return MappingLoad(defaults,"配置版本不支持，已使用默认映射")
            val fields = lines.filter { '=' in it }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
            var restored = false; var config = defaults
            for (input in MappingInput.entries) {
                val items = fields[input.id]
                val action = items?.singleOrNull()?.let { id -> MappingAction.entries.singleOrNull { it.id == id } }
                if (action == null) restored = true else config = config.with(input,action)
            }
            for (control in RotaryControl.entries) {
                val items = fields["${control.id}-sensitivity"] ?: continue // legacy configuration
                val level = items.singleOrNull()?.let { id -> SensitivityLevel.entries.singleOrNull { it.id == id } }
                if (level == null) restored = true else config = config.withSensitivity(control,level)
            }
            return MappingLoad(config,if(restored) "部分设置已恢复默认，请检查后保存" else null)
        }
    }
}
class MappingDraft(private var base: MappingConfig) {
    private var working = base
    val dirty: Boolean get() = working != base
    fun set(input: MappingInput, action: MappingAction) { working = working.with(input,action) }
    fun setSensitivity(control: RotaryControl, level: SensitivityLevel) { working = working.withSensitivity(control,level) }
    fun resetDefaults() { working = MappingConfig.defaults() }
    fun snapshot() = working
    fun accept(config: MappingConfig) { base = config; working = config }
}
data class MappingSnapshot(val config: MappingConfig, val revision: Long, val notice: String?)
data class MappingSaveResult(val success: Boolean, val snapshot: MappingSnapshot, val message: String)
interface MappingPreferencesPort { fun read(): String?; fun write(value: String?): Boolean }
/** All writes run on the store worker; volatile snapshots never block UI behind disk IO. */
class MappingSaveCoordinator(private val preferences: MappingPreferencesPort) {
    private var stored: String? = null
    @Volatile private var current: MappingSnapshot
    init {
        val loaded = try { stored = preferences.read(); MappingConfig.decode(stored) }
            catch (_: Exception) { MappingLoad(MappingConfig.defaults(),"设置读取失败，使用默认映射") }
        current = MappingSnapshot(loaded.config,0,loaded.notice)
    }
    fun snapshot() = current
    @Synchronized fun save(expectedRevision: Long, config: MappingConfig): MappingSaveResult {
        if (current.revision != expectedRevision) return MappingSaveResult(false,current,"设置已更新，请关闭后重新打开")
        val encoded = config.encode()
        val saved = try { preferences.write(encoded) } catch (_: Exception) { false }
        if (!saved) {
            // SharedPreferences may already have changed its memory cache even when commit fails.
            runCatching { preferences.write(stored) }
            return MappingSaveResult(false,current,"保存失败，当前映射未更改，请重试")
        }
        stored = encoded; current = MappingSnapshot(config,current.revision + 1,null)
        return MappingSaveResult(true,current,"映射已保存")
    }
}
class MappingEditSession(val initial: MappingSnapshot) {
    val draft = MappingDraft(initial.config)
    var revision = initial.revision; private set
    var saving = false; private set
    private var closed = false
    private var serial = 0L
    fun beginSave(): Long { if (closed || saving || revision < 0) return 0; saving = true; return ++serial }
    fun complete(token: Long, result: MappingSaveResult): Boolean {
        if (closed || !saving || serial != token) return false
        saving = false
        if (result.success) { revision = result.snapshot.revision; draft.accept(result.snapshot.config) }
        return true
    }
    fun close() { closed = true; saving = false; serial++ }
}
