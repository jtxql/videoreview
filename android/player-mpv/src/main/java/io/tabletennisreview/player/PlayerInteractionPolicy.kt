package io.tabletennisreview.player

/** Pure screen seek calculations; one seek on release, never one request per move. */
object PlayerInteractionPolicy {
    const val PROGRESS_MAX = 1000
    fun progress(position: Double?, duration: Double?): Int {
        if (position == null || !position.isFinite() || duration == null || !duration.isFinite() || duration <= 0) return 0
        return (position / duration * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
    }
    fun position(progress: Int, duration: Double): Double {
        require(duration.isFinite() && duration > 0)
        return progress.coerceIn(0, PROGRESS_MAX).toDouble() / PROGRESS_MAX * duration
    }
    fun hud(speed: Double?, position: Double?, duration: Double?): String {
        fun number(value: Double?, places: Int): String {
            if (value == null || !value.isFinite() || value < 0) return "--"
            return String.format(java.util.Locale.ROOT, "%.${places}f", value).trimEnd('0').trimEnd('.')
        }
        return "${number(speed, 2)}X  ${number(position, 1)}S/${number(duration, 1)}S"
    }
    fun doubleTap(x: Double, width: Double): PlayerCommand {
        require(x.isFinite() && width.isFinite() && width > 0 && x >= 0 && x <= width)
        return when {
            x < width / 3 -> PlayerCommand.SeekBySpeed(-1)
            x < width * 2 / 3 -> PlayerCommand.TogglePaused
            else -> PlayerCommand.SeekBySpeed(1)
        }
    }
}
