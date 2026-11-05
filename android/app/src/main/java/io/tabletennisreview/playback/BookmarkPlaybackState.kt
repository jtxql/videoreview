package io.tabletennisreview.playback

import io.tabletennisreview.player.*

/** UI owned. A navigation cursor is not proof that its clip is currently playing. */
class BookmarkPlaybackState {
    private var source: BookmarkSource? = null
    private var loop = LoopPoints()
    private var cursorId: String? = null
    private var activeId: String? = null
    fun reset() { source = null; loop = LoopPoints(); cursorId = null; activeId = null }
    fun observe(source: BookmarkSource?, loop: LoopPoints) {
        if (source == null) { reset(); return }
        if (source != this.source) { reset(); this.source = source }
        if (!loop.active || loop.a != this.loop.a || loop.b != this.loop.b) activeId = null
        this.loop = loop
    }
    fun candidate(catalog: BookmarkCatalog, direction: Int): ClipBookmark? {
        require(direction == -1 || direction == 1)
        val items = catalog.forVideo(source?.videoUri ?: return null)
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.id == cursorId }
        return if (index < 0) if (direction > 0) items.first() else items.last()
            else items[(index+direction+items.size)%items.size]
    }
    fun restored(id: String, source: BookmarkSource) {
        if (this.source != source) return
        cursorId = id; activeId = id
    }
    fun saved(item: ClipBookmark, source: BookmarkSource, loop: LoopPoints) {
        if (this.source != source || item.videoUri != source.videoUri) return
        cursorId = item.id; this.loop = loop
        activeId = if (loop.active && loop.a == item.a && loop.b == item.b) item.id else null
    }
    fun acceptSave(result: BookmarkSaveResult, item: ClipBookmark, source: BookmarkSource, loop: LoopPoints): Boolean {
        if (!result.success || this.source != source || !result.snapshot.catalog.items.contains(item)) return false
        saved(item,source,loop); return true
    }
    fun acceptRestore(result: ReviewRestoreResult, id: String, source: BookmarkSource): Boolean {
        if (this.source != source || result !is ReviewRestoreResult.Ready || result.sessionId != source.playerSession) return false
        restored(id,source); return true
    }
    fun activeName(catalog: BookmarkCatalog): String? {
        val items = catalog.forVideo(source?.videoUri ?: return null)
        if (items.none { it.id == cursorId }) cursorId = null
        val item = items.singleOrNull { it.id == activeId }
        if (item == null || !loop.active || loop.a != item.a || loop.b != item.b) { activeId = null; return null }
        return item.name
    }
}

data class BookmarkHudContent(val name: String?, val detail: String?, val visible: Boolean)
object BookmarkHudPolicy {
    fun compose(name: String?, landscape: Boolean, fullscreen: Boolean, speed: Double?, position: Double?, duration: Double?): BookmarkHudContent {
        val title = name?.takeIf { it.isNotEmpty() }
        val detail = if (landscape || fullscreen) PlayerInteractionPolicy.hud(speed,position,duration) else null
        return BookmarkHudContent(title,detail,title != null || detail != null)
    }
}
