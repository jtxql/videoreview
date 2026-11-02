package io.tabletennisreview.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import io.github.yuroyami.libmpvkt.view.MpvView

/** Surface remains outside diagnostic scroll views. No USB dependency. */
class ReviewPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val video = MpvView(context)
    val controller: PlayerController = MpvPlayerController(video)
    init { addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
}
