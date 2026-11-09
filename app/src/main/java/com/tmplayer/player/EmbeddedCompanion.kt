package com.tmplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * A second, silent player that reads one embedded subtitle track out of a downloaded
 * video the main player is showing, so its lines can be drawn over the main track's
 * (dual subtitles Phase B, v1.0.22). The main player can only ever render one text
 * track (Media3 1.11.1 keeps one text definition per type), so the companion track
 * is played here, with the picture and the sound switched off, and its cues handed
 * to the activity, which lays them over the main track's in the one subtitle view.
 *
 * Downloaded videos only: the file stands complete on the disk, so this player
 * reads it the plain way, with no part in the Telegram streaming machinery. A
 * streamed video has no whole file to read a second track out of.
 *
 * The low-end rule, the same as the split screen's: this player is built plainly,
 * only when a companion is actually picked, and if its making or its reading
 * fails, only the companion ends ([onUnavailable] says so) and the main player
 * is untouched. Every call happens on the main thread, as the player's own
 * contract asks.
 */
class EmbeddedCompanion(
    context: Context,
    /** The companion track's lines as they come due, already the player's own cue objects. */
    private val onCues: (List<Cue>) -> Unit,
    /** The track could not be read out of this file; the companion is over. */
    private val onUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var exo: ExoPlayer? = null
    private var wantedId: String? = null
    private var picked = false
    private var gone = false

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val player = exo ?: return
            if (!picked) {
                val wanted = wantedId ?: return
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_TEXT) continue
                    for (index in 0 until group.length) {
                        val id = group.getTrackFormat(index).id ?: continue
                        if (id == wanted || ExternalSubtitle.isTrackId(id, wanted)) {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                                .build()
                            picked = true
                            return
                        }
                    }
                }
                // The file is open and its tracks are known, and the wanted one is
                // not among them: this copy of the video does not carry it.
                if (player.playbackState == Player.STATE_READY) unavailable()
            }
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            onCues(cueGroup.cues)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            unavailable()
        }
    }

    /** Opens [uri] and takes the text track named [trackId] on, at [positionMs]. */
    fun start(uri: Uri, trackId: String, positionMs: Long) {
        stop()
        gone = false
        picked = false
        wantedId = trackId
        val selector = DefaultTrackSelector(appContext)
        selector.setParameters(
            selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build(),
        )
        val player = try {
            ExoPlayer.Builder(appContext)
                .setTrackSelector(selector)
                .build()
        } catch (error: Throwable) {
            unavailable()
            return
        }
        exo = player
        player.addListener(listener)
        player.setMediaItem(MediaItem.Builder().setUri(uri).build(), positionMs.coerceAtLeast(0))
        player.prepare()
    }

    /**
     * Keeps this player where the main one is: playing while it plays, at its
     * speed, and pulled back to its position when the two drift apart (a seek
     * the follower missed, a stall on one side). [positionMs] already carries
     * the subtitle sync offset, the way the file companion's follower reads it.
     */
    fun align(positionMs: Long, playing: Boolean, speed: Float) {
        val player = exo ?: return
        if (gone) return
        if (player.playWhenReady != playing) player.playWhenReady = playing
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        val drift = player.currentPosition - positionMs
        if (drift > DRIFT_MS || drift < -DRIFT_MS) player.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Takes the companion player down; the main player never hears about it. */
    fun stop() {
        val player = exo ?: return
        exo = null
        wantedId = null
        runCatching {
            player.removeListener(listener)
            player.release()
        }
    }

    private fun unavailable() {
        if (gone) return
        gone = true
        stop()
        onUnavailable()
    }

    private companion object {
        /** How far the companion may drift from the main player before it is pulled back. */
        const val DRIFT_MS = 700L
    }
}
