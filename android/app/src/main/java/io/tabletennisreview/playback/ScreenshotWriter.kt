package io.tabletennisreview.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.util.concurrent.Executors

/** Owner and worker leases prevent recycling a bitmap still used by a save. */
class ShotBitmap(val bitmap: Bitmap) {
    private var owner = true
    private var leases = 0
    @Synchronized fun acquire(): Bitmap { check(owner && !bitmap.isRecycled); leases++; return bitmap }
    @Synchronized fun release() { check(leases > 0); leases--; recycleIfUnused() }
    @Synchronized fun close() { owner = false; recycleIfUnused() }
    private fun recycleIfUnused() { if (!owner && leases == 0 && !bitmap.isRecycled) bitmap.recycle() }
}
data class ShotSaveResult(val saved: Boolean, val message: String)
class ScreenshotWriter(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"Review-PNG-save") }
    var listener: ((ShotSaveResult) -> Unit)? = null
    private var transaction: ShotSaveCommit? = null
    private var closed = false
    var busy = false; private set
    fun save(image: ShotBitmap, shapes: List<MarkShape>, target: Uri, source: Uri?): Boolean {
        if (closed || busy || target.scheme != "content") return false
        val tx = ShotSaveCommit(); transaction = tx; busy = true
        val bitmap = image.acquire()
        worker.execute {
            var output: Bitmap? = null
            var safeTarget = false
            val result = try {
                check(!sameDocument(target,source)) { "拒绝覆盖原视频" }
                safeTarget = true
                output = Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output); canvas.drawBitmap(bitmap,0f,0f,null)
                shapes.forEach { ShapeRenderer.draw(canvas,it,bitmap.width,bitmap.height) }
                val stream = app.contentResolver.openOutputStream(target,"w") ?: error("无法写入目标图片")
                ShotOutput.write(stream) { checkNotNull(output).compress(Bitmap.CompressFormat.PNG,100,it) }
                check(tx.commit()) { "保存已取消，未提交" }
                ShotSaveResult(true,"图片已保存")
            } catch (e: Exception) {
                val deleted = if (safeTarget) runCatching { DocumentsContract.deleteDocument(app.contentResolver,target) }.getOrDefault(false) else true
                ShotSaveResult(false,"保存失败：${e.message}${if (!deleted) "；请检查并删除目标半成品" else ""}")
            } catch (e: OutOfMemoryError) {
                if (safeTarget) runCatching { DocumentsContract.deleteDocument(app.contentResolver,target) }
                ShotSaveResult(false,"保存图片内存不足")
            } finally { output?.recycle(); image.release() }
            main.post { busy = false; transaction = null; if (!closed) listener?.invoke(result) }
        }
        return true
    }
    private fun sameDocument(target: Uri, source: Uri?): Boolean {
        if (source == null) return false
        if (target == source) return true
        if (target.authority == source.authority && runCatching {
            DocumentsContract.getDocumentId(target) == DocumentsContract.getDocumentId(source)
        }.getOrDefault(false)) return true
        return runCatching {
            val canonicalSource = app.contentResolver.canonicalize(source)
            canonicalSource != null && canonicalSource == app.contentResolver.canonicalize(target)
        }.getOrDefault(false)
    }
    fun close() { if (closed) return; closed = true; listener = null; transaction?.cancel(); worker.shutdown() }
}
