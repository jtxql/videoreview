package io.tabletennisreview.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import io.github.yuroyami.libmpvkt.view.MpvView

/** Surface remains outside diagnostic scroll views. No USB dependency. */
class ReviewPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val video = MpvView(context)
    val controller: PlayerController = MpvPlayerController(video)
    val viewport = VideoViewport()
    var onViewportChanged: (() -> Unit)? = null
    init { clipChildren = true; clipToPadding = true; addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    fun zoom(factor: Double, focusX: Double, focusY: Double) {
        viewport.zoom(factor, focusX, focusY, width.toDouble(), height.toDouble()); applyViewport()
    }
    fun pan(dx: Double, dy: Double) {
        viewport.pan(dx, dy, width.toDouble(), height.toDouble()); applyViewport()
    }
    fun resetViewport() { viewport.reset(); applyViewport() }
    private fun applyViewport() {
        video.pivotX = width / 2f; video.pivotY = height / 2f
        video.scaleX = viewport.scale.toFloat(); video.scaleY = viewport.scale.toFloat()
        video.translationX = viewport.x.toFloat(); video.translationY = viewport.y.toFloat()
        onViewportChanged?.invoke()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Layout/orientation changes reset pixel coordinates instead of reusing stale offsets.
        resetViewport()
    }
}
