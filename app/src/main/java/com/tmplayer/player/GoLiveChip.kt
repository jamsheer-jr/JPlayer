package com.tmplayer.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.Motion

/**
 * "LIVE": the way back to a live stream's edge once the viewer has fallen behind it, by pausing
 * or by drifting while the stream ran on. It stands only while the playhead is behind the edge
 * by more than a few seconds; at the edge there is nowhere to go back to, so it is gone.
 *
 * Bottom left, in [UndoSeekChip]'s corner and drawn the same way, so the corner offers read as
 * one family. The two never stand together: a fresh "Back to" offer is the urgent one for its
 * few seconds, and the activity stands this one down while it is up and raises it again once
 * the offer goes.
 *
 * A phone taps it. A television can walk to it with the D-pad while the transport row is up: it
 * is focusable there and takes the row's own focus treatment, a solid white seat with dark
 * words. It never asks for focus itself, so its appearing under a viewer who is steering the
 * row changes nothing under their thumb; while the row is down the remote's keys keep their
 * meanings, as they do for the other chips.
 *
 * It slides in from its edge, sinks a little under a finger and goes once taken; with reduced
 * motion it only fades. The activity owns when it shows and what pressing does.
 */
class GoLiveChip(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    onGoLive: () -> Unit,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val label = TextView(context).apply {
        text = L.homeLiveBadge
        textSize = if (tv) 18f else 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (tv) {
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(Color.BLACK, context.getColor(R.color.text_primary)),
                ),
            )
        } else {
            setTextColor(context.getColor(R.color.text_primary))
        }
    }

    /** The recording dot the word is known by, in the seek bar's own red. */
    private val dot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.seek_played))
        }
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(22), px(if (tv) 12 else 11), px(22), px(if (tv) 10 else 11))
        visibility = View.GONE
        if (tv) {
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = px(22).toFloat()
                    },
                )
                addState(intArrayOf(), context.getDrawable(R.drawable.bg_player_chip))
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { onGoLive() }
        } else {
            background = context.getDrawable(R.drawable.bg_player_chip)
            isFocusable = false
            isClickable = true
            setOnClickListener { onGoLive() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (moving()) v.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MS).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).start()
                }
                false
            }
        }
        addView(dot, LinearLayout.LayoutParams(px(DOT_DP), px(DOT_DP)).apply { marginEnd = px(DOT_GAP_DP) })
        addView(label)
    }

    init {
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM,
            ).apply {
                leftMargin = NextUpCard.marginPx(root)
                marginStart = NextUpCard.marginPx(root)
                bottomMargin = NextUpCard.bottomPx(root)
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Puts the chip up, sliding in from its edge; a no-op while it already stands. */
    fun show() {
        if (isShown) return
        view.visibility = View.VISIBLE
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        if (moving()) {
            view.alpha = 0f
            view.translationX = -px(SLIDE_DP).toFloat() * if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            view.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
        } else {
            view.alpha = 1f
            view.translationX = 0f
        }
    }

    /** Takes it away: [taken] shrinks it into the press, otherwise it just fades. */
    fun hide(taken: Boolean = false) {
        if (!isShown) return
        view.animate().cancel()
        if (!moving()) {
            view.visibility = View.GONE
            return
        }
        val shrink = if (taken) PRESSED else 1f
        view.animate().alpha(0f).scaleX(shrink).scaleY(shrink).setDuration(OUT_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            .start()
    }

    private fun moving(): Boolean = !Motion.reduced && com.tmplayer.ui.theme.systemAnimatorsEnabled(context)

    private companion object {
        const val DOT_DP = 8
        const val DOT_GAP_DP = 8
        const val SLIDE_DP = 24
        const val IN_MS = 220L
        const val OUT_MS = 160L
        const val PRESS_MS = 90L
        const val PRESSED = 0.94f
    }
}
