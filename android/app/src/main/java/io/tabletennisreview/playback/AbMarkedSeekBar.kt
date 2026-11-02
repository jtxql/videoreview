package io.tabletennisreview.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.SeekBar

/** Display-only markers on the same progress track; standard seeking/touch handling is unchanged. */
class AbMarkedSeekBar(context: Context) : SeekBar(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var a: Double? = null
    private var b: Double? = null
    init { minimumHeight = (48 * density).toInt() }
    fun setMarkers(start: Double?, end: Double?, duration: Double?) {
        val nextA = ProgressMarkers.fraction(start, duration)
        val nextB = ProgressMarkers.fraction(end, duration)
        if (a != nextA || b != nextB) { a = nextA; b = nextB; invalidate() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = progressDrawable?.bounds
        val left = paddingLeft.toFloat() + (bounds?.left ?: 0)
        val right = paddingLeft.toFloat() + (bounds?.right ?: (width - paddingLeft - paddingRight))
        if (right <= left) return
        val middle = height / 2f
        fun marker(fraction: Double, label: String, color: Int, baseline: Float) {
            val ratio = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 - fraction else fraction
            val x = left + (right - left) * ratio.toFloat()
            ink.style = Paint.Style.STROKE; ink.strokeWidth = 5 * density; ink.color = Color.BLACK
            canvas.drawLine(x, middle - 6 * density, x, middle + 6 * density, ink)
            ink.strokeWidth = 3 * density; ink.color = color
            canvas.drawLine(x, middle - 6 * density, x, middle + 6 * density, ink)
            ink.textSize = 11 * resources.displayMetrics.scaledDensity; ink.textAlign = Paint.Align.CENTER
            val half = ink.measureText(label) / 2
            val textX = x.coerceIn(half, (width - half).coerceAtLeast(half))
            ink.style = Paint.Style.STROKE; ink.strokeWidth = 2 * density; ink.color = Color.BLACK
            canvas.drawText(label, textX, baseline, ink)
            ink.style = Paint.Style.FILL; ink.color = color
            canvas.drawText(label, textX, baseline, ink)
        }
        // Separate label lanes keep coincident/nearby A and B readable.
        a?.let { marker(it, "A", Color.rgb(80, 190, 255), middle - 8 * density) }
        b?.let { marker(it, "B", Color.rgb(255, 180, 60), middle + 18 * density) }
    }
}
