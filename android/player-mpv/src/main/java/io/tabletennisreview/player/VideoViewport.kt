package io.tabletennisreview.player

/** Display-only transform, independent of playback time, USB and export. Coordinates are viewport pixels. */
class VideoViewport {
    var scale = 1.0; private set
    var x = 0.0; private set
    var y = 0.0; private set
    val enlarged: Boolean get() = scale > 1.0001
    fun reset() { scale = 1.0; x = 0.0; y = 0.0 }
    fun zoom(factor: Double, focusX: Double, focusY: Double, width: Double, height: Double) {
        if (!listOf(factor, focusX, focusY, width, height).all { it.isFinite() } || factor <= 0 || width <= 0 || height <= 0) return
        val next = (scale * factor).coerceIn(1.0, 4.0)
        val ratio = next / scale
        x = focusX - width / 2 - (focusX - width / 2 - x) * ratio
        y = focusY - height / 2 - (focusY - height / 2 - y) * ratio
        scale = next
        clamp(width, height)
    }
    fun pan(dx: Double, dy: Double, width: Double, height: Double) {
        if (!listOf(dx, dy, width, height).all { it.isFinite() } || width <= 0 || height <= 0) return
        x += dx; y += dy; clamp(width, height)
    }
    private fun clamp(width: Double, height: Double) {
        x = x.coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2)
        y = y.coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2)
        if (!enlarged) reset()
    }
}
