package io.tabletennisreview.playback

import android.os.Handler
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the existing main looper; scheduling never invokes callbacks inline. */
class HandlerGestureTimer(private val handler: Handler, private val now: () -> Long) : GestureTimer {
    override fun schedule(deadlineNanos: Long, task: () -> Unit): AutoCloseable {
        val cancelled = AtomicBoolean()
        val remaining = (deadlineNanos-now()).coerceAtLeast(0)
        val delay = remaining/1_000_000 + if (remaining%1_000_000 == 0L) 0 else 1
        val runnable = Runnable { if (!cancelled.get()) task() }
        if (!handler.postDelayed(runnable,delay)) throw RejectedExecutionException("gesture timer stopped")
        return AutoCloseable { cancelled.set(true); handler.removeCallbacks(runnable) }
    }
}
