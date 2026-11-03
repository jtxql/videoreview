package io.tabletennisreview.playback

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Both preview and PNG use this exact image-coordinate renderer. */
object ShapeRenderer {
    fun draw(canvas: Canvas, shape: MarkShape, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shape.color.argb; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = max(2f, min(width, height) * .006f)
        }
        val ax = (shape.start.x * width).toFloat(); val ay = (shape.start.y * height).toFloat()
        val bx = (shape.end.x * width).toFloat(); val by = (shape.end.y * height).toFloat()
        if (shape.tool == MarkTool.Ellipse) {
            canvas.drawOval(min(ax,bx),min(ay,by),max(ax,bx),max(ay,by),paint)
        } else {
            canvas.drawLine(ax,ay,bx,by,paint)
            if (shape.tool == MarkTool.Arrow) {
                val angle = atan2((by-ay).toDouble(),(bx-ax).toDouble())
                val length = min(hypot((bx-ax).toDouble(),(by-ay).toDouble()) * .35, min(width,height) * .055)
                for (side in listOf(-.5,.5)) canvas.drawLine(bx,by,
                    (bx-length*cos(angle+side)).toFloat(),(by-length*sin(angle+side)).toFloat(),paint)
            }
        }
    }
}
class AnnotationView(context: Context, private val image: Bitmap, val document: AnnotationDocument) : View(context) {
    var tool = MarkTool.Line
    var color = MarkColor.Red
    var onChanged: (() -> Unit)? = null
    private val gesture = AnnotationGesture()
    private var draft: MarkShape? = null
    private fun fit() = ImageFit(width,height,image.width,image.height)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(30,30,30))
        if (width <= 0 || height <= 0 || image.isRecycled) return
        val f = fit(); val save = canvas.save()
        canvas.translate(f.left.toFloat(),f.top.toFloat()); canvas.scale(f.scale.toFloat(),f.scale.toFloat())
        canvas.clipRect(0,0,image.width,image.height); canvas.drawBitmap(image,0f,0f,null)
        document.shapes.forEach { ShapeRenderer.draw(canvas,it,image.width,image.height) }
        draft?.let { ShapeRenderer.draw(canvas,it,image.width,image.height) }; canvas.restoreToCount(save)
    }
    fun cancelStroke() { gesture.cancel(); draft = null; invalidate() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); cancelStroke() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 0 || height <= 0) return false
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) { cancelStroke(); return true }
        val point = fit().point(event.x.toDouble(),event.y.toDouble())
        val history = (0 until event.historySize).map { fit().point(event.getHistoricalX(it).toDouble(),event.getHistoricalY(it).toDouble()) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gesture.down(point); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (!gesture.moveBatch(history,point,event.pointerCount)) cancelStroke()
                else gesture.start?.let { draft = MarkShape(tool,color,it,checkNotNull(point)); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                gesture.moveBatch(history,point,event.pointerCount)
                val ends = gesture.up(point,event.pointerCount); cancelStroke(); performClick()
                if (ends.size == 2 && document.commit(tool,color,ends[0],ends[1])) onChanged?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
