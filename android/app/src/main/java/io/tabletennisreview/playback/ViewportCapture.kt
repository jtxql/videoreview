package io.tabletennisreview.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import io.tabletennisreview.player.ReviewPlayerView
import java.lang.ref.WeakReference

/** One native copy at a time. A timed-out Bitmap is retained until PixelCopy returns. */
class ViewportCapture(view: ReviewPlayerView) {
    private val viewRef = WeakReference(view)
    private val main = Handler(Looper.getMainLooper())
    private var operation: Operation? = null
    private class Operation(val geometry: ShotGeometry, var valid: (() -> Boolean)?, var callback: ((Result<Bitmap>) -> Unit)?) {
        var done = false
        var pending = false
        var raw: Bitmap? = null
    }
    fun capture(valid: () -> Boolean, callback: (Result<Bitmap>) -> Unit): Boolean {
        if (operation != null) return false
        val view = viewRef.get() ?: return false
        val geometry = try { ShotGeometry(view.width, view.height, view.viewport.scale, view.viewport.x, view.viewport.y) }
            catch (e: IllegalArgumentException) { callback(Result.failure(e)); return true }
        if (geometry.width.toLong() * geometry.height > 8_000_000) {
            callback(Result.failure(IllegalStateException("截图视口过大"))); return true
        }
        val op = Operation(geometry, valid, callback); operation = op
        main.postDelayed({ if (operation === op && !op.done) finish(op, Result.failure(IllegalStateException("截图超时，不自动重试"))) }, 3_000)
        // Two display callbacks after pause acknowledgement, not a guessed video-frame interval.
        view.postOnAnimation { viewRef.get()?.postOnAnimation { copy(op) } }
        return true
    }
    fun cancel() { operation?.let { if (!it.done) finish(it, Result.failure(IllegalStateException("截图已取消"))) } }
    private fun valid(op: Operation): Boolean {
        val view = viewRef.get() ?: return false
        val g = op.geometry
        return operation === op && !op.done && op.valid?.invoke() == true && view.isShown &&
            view.width == g.width && view.height == g.height && view.viewport.scale == g.scale && view.viewport.x == g.x && view.viewport.y == g.y
    }
    private fun copy(op: Operation) {
        if (!valid(op)) { if (!op.done) finish(op, Result.failure(IllegalStateException("截图会话或布局已变化"))); return }
        val view = viewRef.get() ?: return
        val layer = view.captureLayer()
        if (layer == null || layer.width != op.geometry.width || layer.height != op.geometry.height || layer.left != 0 || layer.top != 0) {
            finish(op, Result.failure(IllegalStateException("不支持的渲染层布局"))); return
        }
        try {
            val raw = Bitmap.createBitmap(op.geometry.width, op.geometry.height, Bitmap.Config.ARGB_8888)
            op.raw = raw
            when (layer) {
                is SurfaceView -> {
                    op.pending = true
                    try { PixelCopy.request(layer, raw, { code ->
                        op.pending = false
                        if (op.done) { raw.recycle(); op.raw = null; if (operation === op) operation = null }
                        else if (code == PixelCopy.SUCCESS) compose(op)
                        else finish(op, Result.failure(IllegalStateException("PixelCopy失败 code=$code")))
                    }, main) } catch (e: Exception) { op.pending = false; throw e }
                }
                is TextureView -> {
                    check(layer.isAvailable && layer.getBitmap(raw) != null) { "视频纹理尚无有效画面" }; compose(op)
                }
                else -> finish(op, Result.failure(IllegalStateException("未知视频渲染类型")))
            }
        } catch (e: Exception) { finish(op, Result.failure(e)) }
        catch (e: OutOfMemoryError) { finish(op, Result.failure(IllegalStateException("截图内存不足", e))) }
    }
    private fun compose(op: Operation) {
        if (!valid(op)) { finish(op, Result.failure(IllegalStateException("截图来源已过期"))); return }
        var output: Bitmap? = null
        try {
            val g = op.geometry
            output = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output); canvas.drawColor(Color.BLACK)
            canvas.translate((g.width / 2.0 + g.x).toFloat(), (g.height / 2.0 + g.y).toFloat())
            canvas.scale(g.scale.toFloat(), g.scale.toFloat())
            canvas.drawBitmap(checkNotNull(op.raw), -g.width / 2f, -g.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
            finish(op, Result.success(output))
        } catch (e: Exception) { output?.recycle(); finish(op, Result.failure(e)) }
        catch (e: OutOfMemoryError) { output?.recycle(); finish(op, Result.failure(IllegalStateException("合成截图内存不足", e))) }
    }
    private fun finish(op: Operation, result: Result<Bitmap>) {
        if (op.done) { result.getOrNull()?.recycle(); return }
        op.done = true
        if (!op.pending) { op.raw?.recycle(); op.raw = null; if (operation === op) operation = null }
        val callback = op.callback; op.callback = null; op.valid = null
        callback?.invoke(result) ?: result.getOrNull()?.recycle()
    }
}
