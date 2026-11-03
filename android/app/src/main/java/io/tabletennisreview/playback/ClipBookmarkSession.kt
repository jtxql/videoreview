package io.tabletennisreview.playback

import java.util.concurrent.atomic.AtomicLong

data class BookmarkSource(val videoUri: String, val playerSession: Long)
enum class BookmarkPhase { Idle, Snapshotting, Restoring, Closed }
/** Main/UI and player worker share only this synchronized immutable-source lease. */
class ClipBookmarkSession(val source: BookmarkSource) {
    companion object { private val serial = AtomicLong() }
    @Volatile var phase = BookmarkPhase.Idle; private set
    private var token = 0L
    @Synchronized private fun begin(next: BookmarkPhase): Long {
        if (phase != BookmarkPhase.Idle) return 0
        token = serial.incrementAndGet(); phase = next; return token
    }
    fun beginSnapshot(): Long = begin(BookmarkPhase.Snapshotting)
    fun beginRestore(): Long = begin(BookmarkPhase.Restoring)
    @Synchronized fun isCurrent(candidate: Long, observed: BookmarkSource): Boolean = candidate > 0 && candidate == token &&
        source == observed && phase != BookmarkPhase.Idle && phase != BookmarkPhase.Closed
    @Synchronized fun isOpen(observed: BookmarkSource) = source == observed && phase != BookmarkPhase.Closed
    @Synchronized fun finish(candidate: Long): Boolean {
        if (!isCurrent(candidate,source)) return false
        token = 0; phase = BookmarkPhase.Idle; return true
    }
    @Synchronized fun close() { token = 0; phase = BookmarkPhase.Closed }
}
