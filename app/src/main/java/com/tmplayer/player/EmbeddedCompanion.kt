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
 * A second, silent player that reads one embedded subtitle track out of the video
 * the main player is showing, so its lines can be drawn over the main track's
 * (dual subtitles, v1.0.22). The main player can only ever render one text
 * track (Media3 1.11.1 keeps one text definition per type), so the companion track
 * is played here, with the picture and the sound switched off, and its cues handed
 * to the activity, which lays them over the main track's in the one subtitle view.
 *
 * Any source the main player reads (v1.0.23): a downloaded file the plain way, a
 * stream link over the network, a Telegram video through the same TDLib reading
 * chain the main player uses, handed in as a media source factory. A split video
 * joined from pieces stands apart: its pieces are the main player's own joining,
 * and the companion declines it.
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
    /** When the last corrective seek was sent, to damp them (see [align]). */
    private var lastSeekMs = 0L

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

    /**
     * Opens [uri] and takes the text track named [trackId] on, at [positionMs].
     * [factory] reads the source where the plain data source cannot (a Telegram
     * video); null reads it the plain way, as a downloaded file or a stream link.
     */
    fun start(
        uri: Uri,
        factory: androidx.media3.exoplayer.source.MediaSource.Factory?,
        trackId: String,
        positionMs: Long,
    ) {
        stop()
        gone = false
        picked = false
        wantedId = trackId
        lastSeekMs = 0L
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
                .apply { if (factory != null) setMediaSourceFactory(factory) }
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
        if (drift <= DRIFT_MS && drift >= -DRIFT_MS) return
        // Seeks are damped (owner, v1.0.23, the lag beside a companion): a
        // shadow still waiting on the stream sits far from the main player for
        // seconds at a time, and a corrective seek every follower beat only
        // makes it re-read and re-buffer without closing the gap. One seek a
        // couple of seconds apart, unless the drift is far past any stall.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSeekMs < SEEK_DAMP_MS && drift <= FAR_DRIFT_MS && drift >= -FAR_DRIFT_MS) return
        lastSeekMs = now
        player.seekTo(positionMs.coerceAtLeast(0))
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

        /** The least time between two corrective seeks while the drift stays ordinary. */
        const val SEEK_DAMP_MS = 2_000L

        /** A drift past this is a jump (the viewer seeked), never a stall: it is followed at once. */
        const val FAR_DRIFT_MS = 5_000L
    }
}
