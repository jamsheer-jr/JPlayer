package com.tmplayer.player

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tmplayer.data.StreamLink
import com.tmplayer.i18n.Translator

/**
 * The second live stream of a split screen (owner, v1.0.15, item 11c): one more player,
 * and never more than one, in a pane of its own over the player's root. Side by side on a
 * landscape screen (a television always), stacked under the main picture on a portrait
 * phone. It starts silent; a tap on its pane gives it the sound and silences the main
 * player, and a tap back swaps again, so exactly one of the two is ever heard. Its close
 * button takes it down alone, and the main stream plays on.
 *
 * The low-end rule, stated once here: two video decoders at once is more than the weak
 * stick class of television reliably has. The second player is built plainly, with no
 * part in the main player's machinery, and if its making or its playing fails, only its
 * pane closes ([onFailed] says so) and the main stream is untouched. Nothing here is
 * created until a pair is actually asked for.
 */
class SplitStream(
    private val activity: Activity,
    private val root: ViewGroup,
    /** The sound moved: true while the second stream has it, false when the main one does. */
    private val onAudioSwap: (secondHasAudio: Boolean) -> Unit,
    /** The viewer closed the second stream; the main player takes its sound back. */
    private val onClosed: () -> Unit,
    /** The second stream could not be made or played on this device; it is already closed. */
    private val onFailed: () -> Unit,
) {
    private var player: ExoPlayer? = null
    private var pane: FrameLayout? = null
    private var soundLabel: TextView? = null
    private var hasAudio = false

    val isOpen: Boolean get() = player != null

    fun start(link: StreamLink) {
        stop()
        val exo = try {
            ExoPlayer.Builder(activity).build()
        } catch (error: Throwable) {
            onFailed()
            return
        }
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                stop()
                onFailed()
            }
        })
        val view = PlayerView(activity).apply {
            this.player = exo
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        val name = TextView(activity).apply {
            text = link.name
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 13f
        }
        val sound = TextView(activity).apply {
            text = Translator.messages.playerSplitSound
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 13f
            visibility = View.GONE
        }
        soundLabel = sound
        val close = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(20, 8, 20, 8)
            textSize = 15f
            contentDescription = Translator.messages.commonClose
            setOnClickListener {
                stop()
                onClosed()
            }
        }
        val box = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(sound, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            setOnClickListener { swapAudio() }
        }
        val landscape = activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        val params = if (landscape) {
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                width = root.width / 2
            }
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM).apply {
                height = root.height / 2
            }
        }
        // A pane measured before the root has its size would be halved against nothing:
        // the root is the player's own, laid out long before a stream pair can be asked.
        if (params.width == 0 && landscape) params.width = ViewGroup.LayoutParams.MATCH_PARENT
        if (params.height == 0 && !landscape) params.height = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(box, params)
        pane = box
        player = exo
        hasAudio = false
        exo.volume = 0f
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(link.url)
                .apply {
                    if (link.live) setMimeType(MimeTypes.APPLICATION_M3U8)
                    else if (link.dash) setMimeType(MimeTypes.APPLICATION_MPD)
                }
                .build(),
        )
        exo.prepare()
        exo.playWhenReady = true
    }

    /** A tap on the pane: the sound moves to whichever stream is silent, never to both. */
    private fun swapAudio() {
        val exo = player ?: return
        hasAudio = !hasAudio
        exo.volume = if (hasAudio) 1f else 0f
        soundLabel?.visibility = if (hasAudio) View.VISIBLE else View.GONE
        onAudioSwap(hasAudio)
    }

    /** Takes the second player and its pane down; the main stream is not touched. */
    fun stop() {
        player?.let { exo ->
            runCatching { exo.release() }
        }
        player = null
        pane?.let { root.removeView(it) }
        pane = null
        soundLabel = null
        if (hasAudio) {
            hasAudio = false
            onAudioSwap(false)
        }
    }
}
