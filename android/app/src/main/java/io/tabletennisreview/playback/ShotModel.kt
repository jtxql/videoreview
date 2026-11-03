package io.tabletennisreview.playback

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot

data class ImagePoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) }
}
class ShotGeometry(val width: Int, val height: Int, val scale: Double, val x: Double, val y: Double) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000)
        require(scale.isFinite() && scale in 1.0..4.0 && x.isFinite() && y.isFinite())
        require(abs(x) <= width * (scale - 1) / 2 + .01 && abs(y) <= height * (scale - 1) / 2 + .01)
    }
    fun sourceAt(px: Double, py: Double): ImagePoint = ImagePoint(
        ((px - width / 2.0 - x) / scale + width / 2.0) / width,
        ((py - height / 2.0 - y) / scale + height / 2.0) / height)
}
class ImageFit(viewWidth: Int, viewHeight: Int, val imageWidth: Int, val imageHeight: Int) {
    init { require(viewWidth > 0 && viewHeight > 0 && imageWidth > 0 && imageHeight > 0) }
    val scale = minOf(viewWidth.toDouble() / imageWidth, viewHeight.toDouble() / imageHeight)
    val left = (viewWidth - imageWidth * scale) / 2
    val top = (viewHeight - imageHeight * scale) / 2
    fun point(x: Double, y: Double): ImagePoint? {
        val px = (x - left) / scale / imageWidth; val py = (y - top) / scale / imageHeight
        return if (px.isFinite() && py.isFinite() && px in 0.0..1.0 && py in 0.0..1.0) ImagePoint(px, py) else null
    }
}
enum class MarkTool { Line, Arrow, Ellipse }
enum class MarkColor(val argb: Int) { Red(0xFFFF4040.toInt()), Yellow(0xFFFFFF00.toInt()), Blue(0xFF40BFFF.toInt()) }
data class MarkShape(val tool: MarkTool, val color: MarkColor, val start: ImagePoint, val end: ImagePoint)
class AnnotationDocument {
    private val items = mutableListOf<MarkShape>()
    val shapes: List<MarkShape> get() = items.toList()
    var revision = 0L; private set
    private var saved = -1L
    val dirty: Boolean get() = saved != revision
    fun commit(tool: MarkTool, color: MarkColor, start: ImagePoint, end: ImagePoint): Boolean {
        if (items.size >= 200 || hypot(end.x - start.x, end.y - start.y) < .003) return false
        if (tool == MarkTool.Ellipse && (abs(end.x - start.x) < .003 || abs(end.y - start.y) < .003)) return false
        items += MarkShape(tool, color, start, end); revision++; return true
    }
    fun undo() { if (items.isNotEmpty()) { items.removeAt(items.lastIndex); revision++ } }
    fun clear() { if (items.isNotEmpty()) { items.clear(); revision++ } }
    fun markSaved(value: Long) { saved = value }
}
enum class ShotPhase { Idle, Capturing, Editing, Picking, Saving }
class ShotSessionGate {
    companion object { private val sequence = AtomicLong() }
    var phase = ShotPhase.Idle; private set
    private var token = 0L
    private var session = 0L
    private var width = 0
    private var height = 0
    @Synchronized fun begin(source: Long, w: Int, h: Int): Long {
        if (phase != ShotPhase.Idle || source <= 0 || w <= 0 || h <= 0) return 0
        token = sequence.incrementAndGet(); session = source; width = w; height = h; phase = ShotPhase.Capturing; return token
    }
    @Synchronized fun current(id: Long): Boolean = id > 0 && token == id && phase != ShotPhase.Idle
    @Synchronized fun matches(id: Long, source: Long, w: Int, h: Int): Boolean = current(id) && source == session && width == w && height == h
    @Synchronized private fun move(id: Long, from: ShotPhase, to: ShotPhase): Boolean {
        if (!current(id) || phase != from) return false
        phase = to; return true
    }
    fun accept(id: Long) = move(id, ShotPhase.Capturing, ShotPhase.Editing)
    fun picking(id: Long) = move(id, ShotPhase.Editing, ShotPhase.Picking)
    fun picked(id: Long, selected: Boolean) = move(id, ShotPhase.Picking, if (selected) ShotPhase.Saving else ShotPhase.Editing)
    fun finishSave(id: Long) = move(id, ShotPhase.Saving, ShotPhase.Editing)
    @Synchronized fun end() { token = 0; phase = ShotPhase.Idle }
}
object ShotOutput {
    @Throws(java.io.IOException::class)
    fun write(output: OutputStream, encode: (OutputStream) -> Boolean) {
        output.use { check(encode(it)) { "PNG编码失败" }; it.flush() }
    }
}

/** A failed or cancelled save cannot commit; a committed file is never removed by a late cancel. */
class ShotSaveCommit {
    private var cancelled = false
    private var committed = false
    @Synchronized fun cancel(): Boolean { if (committed) return false; cancelled = true; return true }
    @Synchronized fun commit(): Boolean { if (cancelled || committed) return false; committed = true; return true }
}

/** Once a stroke leaves the image or gains a second pointer, reentry cannot resurrect it. */
class AnnotationGesture {
    var start: ImagePoint? = null; private set
    fun down(point: ImagePoint?) { start = point }
    fun cancel() { start = null }
    fun move(point: ImagePoint?, pointers: Int): Boolean {
        if (point == null || pointers != 1) cancel()
        return start != null
    }
    fun moveBatch(history: List<ImagePoint?>, point: ImagePoint?, pointers: Int): Boolean {
        for (past in history) if (!move(past,pointers)) return false
        return move(point,pointers)
    }
    fun up(point: ImagePoint?, pointers: Int): List<ImagePoint> {
        val first = start; cancel()
        return if (first != null && point != null && pointers == 1) listOf(first,point) else emptyList()
    }
}
