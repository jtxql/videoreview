package io.tabletennisreview.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Serial file writes/export; no synchronous disk I/O on the UI thread. */
class PlayerLog(context: Context) {
 private val folder = File(context.filesDir, "player-sessions")
 private val file = File(folder, "player-${System.currentTimeMillis()}.txt")
 private val writer = Executors.newSingleThreadExecutor()
 val failure = AtomicReference<String?>(null)
 fun append(message: String) {
  val line = "${Instant.now()} $message\n"
  runCatching { writer.execute {
   runCatching { folder.mkdirs(); file.appendText(line) }.onFailure {
    failure.set(it.javaClass.simpleName); Log.e("ReviewPlayer", "Diagnostic log write failed", it)
   }
  } }
 }
 fun export(resolver: ContentResolver, uri: Uri, done: (String) -> Unit) {
  writer.execute {
   val result = runCatching {
    check(failure.get() == null) { "此前日志写入失败，不能声称完整导出" }
    check(file.exists()) { "尚无日志文件" }
    checkNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开目标文档" }.use { out -> file.inputStream().use { it.copyTo(out) } }
    "日志导出完成"
   }.fold({ it }, { "日志导出失败：${it.javaClass.simpleName}: ${it.message}" })
   done(result)
  }
 }
 fun shutdown() { writer.shutdown() }
}
