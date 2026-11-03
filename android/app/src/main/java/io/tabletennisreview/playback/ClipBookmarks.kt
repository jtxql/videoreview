package io.tabletennisreview.playback

import io.tabletennisreview.player.PlayerPolicy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** File association is the exact SAF URI, never a filename heuristic. */
data class ClipBookmark(val id: String, val videoUri: String, val name: String,
    val a: Double, val b: Double, val speed: Double, val createdAtMillis: Long) {
    init {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "书签ID无效" }
        require(videoUri.length <= 8192 && runCatching { URI(videoUri).let { it.scheme == "content" && !it.rawAuthority.isNullOrEmpty() } }.getOrDefault(false)) { "视频来源无效" }
        validateName(name)
        require(a.isFinite() && b.isFinite() && a >= 0 && a < b) { "A/B区间无效" }
        require(speed in PlayerPolicy.speeds && createdAtMillis >= 0) { "倍速或时间无效" }
    }
    companion object {
        fun validateName(name: String) {
            require(name == name.trim() && name.isNotEmpty() && name.codePointCount(0,name.length) <= 40 &&
                name.codePoints().noneMatch { Character.isISOControl(it) || it == 0x2028 || it == 0x2029 }) { "名称须为1～40字，不能含换行或控制字符" }
        }
    }
}
class BookmarkCatalog(items: List<ClipBookmark>) {
    val items: List<ClipBookmark> = Collections.unmodifiableList(ArrayList(items))
    init {
        require(items.size <= 2000 && items.map { it.id }.distinct().size == items.size) { "书签过多或ID重复" }
        require(items.groupingBy { it.videoUri }.eachCount().values.all { it <= 200 }) { "每视频最多200个书签" }
    }
    fun forVideo(uri: String): List<ClipBookmark> = items.filter { it.videoUri == uri }
        .sortedWith(compareByDescending<ClipBookmark> { it.createdAtMillis }.thenBy { it.id })
    fun add(item: ClipBookmark) = BookmarkCatalog(items + item)
    private fun requireOwned(id: String, uri: String): ClipBookmark = requireNotNull(items.singleOrNull { it.id == id && it.videoUri == uri }) { "书签不存在或来源不符" }
    fun rename(id: String, uri: String, name: String): BookmarkCatalog {
        requireOwned(id,uri); val clean = name.trim(); ClipBookmark.validateName(clean)
        return BookmarkCatalog(items.map { if (it.id == id) it.copy(name = clean) else it })
    }
    fun delete(id: String, uri: String): BookmarkCatalog { requireOwned(id,uri); return BookmarkCatalog(items.filterNot { it.id == id }) }
    override fun equals(other: Any?) = other is BookmarkCatalog && items == other.items
    override fun hashCode() = items.hashCode()
}
data class BookmarkLoad(val catalog: BookmarkCatalog, val writable: Boolean, val notice: String?)
object BookmarkCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val HEADER = "bookmarks-version=1"
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decoded(value: String): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getDecoder().decode(value))).toString()
    @JvmStatic fun encode(catalog: BookmarkCatalog): String {
        val text = (listOf(HEADER) + catalog.items.map { listOf(it.id,encoded(it.videoUri),encoded(it.name),
            it.a.toString(),it.b.toString(),it.speed.toString(),it.createdAtMillis.toString()).joinToString("\t") }).joinToString("\n")
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "书签文件已达容量上限" }
        return text
    }
    @JvmStatic fun decode(text: String?): BookmarkLoad {
        if (text == null) return BookmarkLoad(BookmarkCatalog(emptyList()),true,null)
        return try {
            require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val lines = text.split('\n'); require(lines.first() == HEADER && lines.size <= 2001)
            val items = lines.drop(1).map { line ->
                val f = line.split('\t'); require(f.size == 7)
                ClipBookmark(f[0],decoded(f[1]),decoded(f[2]),f[3].toDouble(),f[4].toDouble(),f[5].toDouble(),f[6].toLong())
            }
            BookmarkLoad(BookmarkCatalog(items),true,null)
        } catch (_: Exception) { BookmarkLoad(BookmarkCatalog(emptyList()),false,"书签文件损坏、超限或版本不支持；已停止写入并保留原文件") }
    }
}

sealed interface BookmarkMutation {
    data class Add(val item: ClipBookmark) : BookmarkMutation
    data class Rename(val id: String, val uri: String, val name: String) : BookmarkMutation
    data class Delete(val id: String, val uri: String) : BookmarkMutation
}
data class BookmarkSnapshot(val catalog: BookmarkCatalog, val revision: Long, val writable: Boolean, val notice: String?)
data class BookmarkSaveResult(val success: Boolean, val snapshot: BookmarkSnapshot, val message: String)
interface BookmarkFilePort { fun read(): String?; fun write(text: String) }
class BookmarkCommitUncertain : IllegalStateException()
class BookmarkSaveCoordinator(private val port: BookmarkFilePort) {
    @Volatile private var current: BookmarkSnapshot
    init {
        val loaded = try { BookmarkCodec.decode(port.read()) }
            catch (_: Exception) { BookmarkLoad(BookmarkCatalog(emptyList()),false,"无法读取书签，已保留原文件并停止写入") }
        current = BookmarkSnapshot(loaded.catalog,0,loaded.writable,loaded.notice)
    }
    fun snapshot() = current
    @Synchronized fun apply(expectedRevision: Long, mutation: BookmarkMutation): BookmarkSaveResult {
        if (!current.writable) return BookmarkSaveResult(false,current,current.notice ?: "书签不可写")
        if (expectedRevision != current.revision) return BookmarkSaveResult(false,current,"书签列表已更新，请重试")
        val candidate = try { when(mutation) {
            is BookmarkMutation.Add -> current.catalog.add(mutation.item)
            is BookmarkMutation.Rename -> current.catalog.rename(mutation.id,mutation.uri,mutation.name)
            is BookmarkMutation.Delete -> current.catalog.delete(mutation.id,mutation.uri)
        } } catch (e: IllegalArgumentException) { return BookmarkSaveResult(false,current,e.message ?: "书签修改无效") }
        val encoded = try { BookmarkCodec.encode(candidate) }
            catch (_: Exception) { return BookmarkSaveResult(false,current,"书签文件已达容量上限") }
        try { port.write(encoded) } catch (_: BookmarkCommitUncertain) {
            current = current.copy(writable = false,notice = "无法确认书签保存结果，已停止写入；请保留应用数据")
            return BookmarkSaveResult(false,current,checkNotNull(current.notice))
        } catch (_: Exception) { return BookmarkSaveResult(false,current,"保存失败，原列表未更改，请重试") }
        current = BookmarkSnapshot(candidate,current.revision+1,true,null)
        return BookmarkSaveResult(true,current,"书签已保存")
    }
}
