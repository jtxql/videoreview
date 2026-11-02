package io.tabletennisreview.export

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

data class ClipExportRequest(val sourceSession: Long, val sourceUri: String,
    val startUs: Long, val endUs: Long, val speed: Double, val keepAudio: Boolean) {
    init {
        require(sourceSession > 0 && sourceUri.startsWith("content://")) { "视频来源或会话无效" }
        require(startUs >= 0 && endUs > startUs) { "需满足0≤A<B" }
        require(speed.isFinite() && listOf(0.1, 0.25, 0.5, 0.75, 1.0).any { kotlin.math.abs(it - speed) < 0.000001 }) { "不支持的导出速度" }
        require(((endUs - startUs).toDouble() / speed).isFinite() && (endUs - startUs).toDouble() / speed < Long.MAX_VALUE.toDouble()) { "输出时长过大" }
    }
    val expectedDurationUs: Long get() = ((endUs - startUs) / speed).roundToLong()
    fun matchesDuration(actualUs: Long, sourceFps: Double?): Boolean {
        if (actualUs <= 0) return false
        val frameAllowance = if (sourceFps != null && sourceFps.isFinite() && sourceFps > 0)
            2_000_000.0 / sourceFps / speed else 1_000_000.0
        val tolerance = maxOf(250_000.0, frameAllowance, expectedDurationUs * 0.01)
        return kotlin.math.abs(actualUs.toDouble() - expectedDurationUs) <= tolerance
    }
    companion object {
        fun fromSeconds(session: Long, uri: String, a: Double, b: Double, speed: Double, audio: Boolean): ClipExportRequest {
            require(a.isFinite() && b.isFinite() && a >= 0 && b > a && b < Long.MAX_VALUE.toDouble() / 1_000_000) { "A/B时间无效" }
            return ClipExportRequest(session, uri, (a * 1_000_000).roundToLong(), (b * 1_000_000).roundToLong(), speed, audio)
        }
    }
}
enum class ClipExportPhase { Idle, Preparing, Exporting, Ready, Saving, Saved, Cancelled, Failed }
data class ClipExportState(val id: Long = 0, val phase: ClipExportPhase = ClipExportPhase.Idle,
    val progress: Int? = null, val message: String = "尚未导出", val actualDurationUs: Long? = null)

/** Serialized logical transitions; old completion/progress cannot overwrite a cancelled/new task. */
class ClipExportGate {
    companion object { private val ids = AtomicLong() }
    var state = ClipExportState()
        private set
    private var closed = false
    @Synchronized fun begin(): Long? {
        if (closed || state.phase in listOf(ClipExportPhase.Preparing, ClipExportPhase.Exporting, ClipExportPhase.Ready, ClipExportPhase.Saving)) return null
        val id = ids.incrementAndGet(); state = ClipExportState(id, ClipExportPhase.Preparing, message = "检查源视频与空间")
        return id
    }
    @Synchronized fun move(id: Long, phase: ClipExportPhase, message: String, durationUs: Long? = state.actualDurationUs): Boolean {
        if (closed || state.id != id) return false
        val permitted = when (state.phase) {
            ClipExportPhase.Preparing -> listOf(ClipExportPhase.Exporting, ClipExportPhase.Failed, ClipExportPhase.Cancelled)
            ClipExportPhase.Exporting -> listOf(ClipExportPhase.Ready, ClipExportPhase.Failed, ClipExportPhase.Cancelled)
            ClipExportPhase.Ready -> listOf(ClipExportPhase.Saving, ClipExportPhase.Cancelled)
            ClipExportPhase.Saving -> listOf(ClipExportPhase.Saved, ClipExportPhase.Ready, ClipExportPhase.Cancelled)
            else -> emptyList()
        }
        if (phase !in permitted) return false
        state = ClipExportState(id, phase, message = message, actualDurationUs = durationUs)
        return true
    }
    @Synchronized fun progress(id: Long, value: Int): Boolean {
        if (closed || state.id != id || state.phase != ClipExportPhase.Exporting) return false
        state = state.copy(progress = maxOf(state.progress ?: 0, value.coerceIn(0, 99))); return true
    }
    @Synchronized fun isAt(id: Long, phase: ClipExportPhase): Boolean = !closed && state.id == id && state.phase == phase
    @Synchronized fun close() { closed = true }
}

/** Once output close succeeds, a late cancel cannot claim to have undone the saved document. */
class ClipSaveCommit {
    private var committed = false
    private var cancelled = false
    @Synchronized fun commit(): Boolean { if (cancelled) return false; committed = true; return true }
    @Synchronized fun cancel(): Boolean { if (committed) return false; cancelled = true; return true }
}

/** Process-wide codec/file ownership; a stale cleanup cannot release a newer export. */
class ClipExportLease {
    private val owner = AtomicLong()
    fun acquire(id: Long): Boolean = id > 0 && owner.compareAndSet(0, id)
    fun release(id: Long): Boolean = id > 0 && owner.compareAndSet(id, 0)
}
