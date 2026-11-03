package io.tabletennisreview.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Process owner, application context only. No file content/URI is logged. */
class ClipBookmarkStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ClipBookmarkStore? = null
        fun get(context: Context): ClipBookmarkStore = instance ?: synchronized(this) {
            instance ?: ClipBookmarkStore(context.applicationContext).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"Review-bookmarks") }
    private val listeners = CopyOnWriteArrayList<AtomicReference<((BookmarkSnapshot) -> Unit)?>>()
    @Volatile private var current = BookmarkSnapshot(BookmarkCatalog(emptyList()),-1,false,"正在读取书签…")
    private var coordinator: BookmarkSaveCoordinator? = null
    init { worker.execute {
        val port = object : BookmarkFilePort {
            private val file by lazy { AtomicFile(File(app.filesDir,"video-bookmarks.v1")) }
            override fun read(): String? {
                val input = try { file.openRead() } catch (e: FileNotFoundException) {
                    if (!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists() && !File(file.baseFile.path+".new").exists()) return null
                    throw e
                }
                val bytes = input.use { stream ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break
                        require(out.size()+count <= BookmarkCodec.MAX_BYTES); out.write(buffer,0,count) }
                    out.toByteArray()
                }
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
            override fun write(text: String) {
                val output = file.startWrite(); var committing = false
                try {
                    output.write(text.toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync()
                    committing = true; file.finishWrite(output)
                    if (read() != text) throw BookmarkCommitUncertain()
                } catch (error: Exception) {
                    if (!committing) runCatching { file.failWrite(output) } else runCatching { output.close() }
                    if (committing) throw BookmarkCommitUncertain() else throw error
                }
            }
        }
        coordinator = BookmarkSaveCoordinator(port); publish(checkNotNull(coordinator).snapshot())
    } }
    fun snapshot() = current
    fun subscribe(callback: (BookmarkSnapshot) -> Unit): AutoCloseable {
        val ref = AtomicReference<((BookmarkSnapshot) -> Unit)?>(callback); listeners += ref
        main.post { ref.get()?.invoke(current) }
        return AutoCloseable { ref.set(null); listeners.remove(ref) }
    }
    private fun publish(value: BookmarkSnapshot) {
        current = value
        main.post { listeners.forEach { ref -> runCatching { ref.get()?.invoke(value) } } }
    }
    fun apply(expectedRevision: Long, mutation: BookmarkMutation, callback: (BookmarkSaveResult) -> Unit): AutoCloseable {
        val ref = AtomicReference<((BookmarkSaveResult) -> Unit)?>(callback)
        worker.execute {
            val result = coordinator?.apply(expectedRevision,mutation) ?: BookmarkSaveResult(false,current,"书签尚未就绪")
            if (result.snapshot != current) publish(result.snapshot)
            main.post { ref.getAndSet(null)?.invoke(result) }
        }
        return AutoCloseable { ref.set(null) }
    }
}
