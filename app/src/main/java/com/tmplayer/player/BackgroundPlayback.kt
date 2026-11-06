package com.tmplayer.player

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.tmplayer.i18n.L

/**
 * The decisions behind background audio ([PlaybackService]) that need no activity to make, kept
 * apart so they can be tested on their own.
 */
@UnstableApi
object BackgroundPlayback {

    /**
     * The track selection for a player nobody can see: the video renderer off, so the decoder
     * (MediaCodec or NextLib's FFmpeg) stops working for pictures that land nowhere, which is
     * most of the battery a backgrounded video costs. Null when video is already off and there
     * is nothing to undo later.
     */
    fun withoutVideo(params: TrackSelectionParameters): TrackSelectionParameters? {
        if (C.TRACK_TYPE_VIDEO in params.disabledTrackTypes) return null
        return params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
    }

    /**
     * The selection with the picture back. ExoPlayer enables the renderer at the playhead and,
     * for a progressive file, seeks the video stream to the keyframe before it on its own, so the
     * picture returns after a short read from the bytes already on the disk; nothing else in the
     * selection (an audio language, captions) is touched.
     */
    fun withVideo(params: TrackSelectionParameters): TrackSelectionParameters =
        params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()

    /** What the end of a video leads to. */
    sealed interface AtTheEnd {
        /** The last frame stays, with Watch again. */
        data object Finished : AtTheEnd

        /** The sleep timer was set to the end of the video: "Still watching?" says so. */
        data object SleepEnded : AtTheEnd

        /** Autoplay has run [StillWatching.AUTOPLAY_LIMIT] episodes in a row: ask first. */
        data object AskFirst : AtTheEnd

        /** The next episode, after the on-screen countdown when [countdown]. */
        data class Next(val countdown: Boolean) : AtTheEnd
    }

    /**
     * The end of a video, seen or not. Unseen there is nobody to count down to, so the next
     * episode starts at once; every other rule (the autoplay setting, a dismissed next-up card,
     * the sleep timer and "Still watching?") is the same either way.
     */
    fun atTheEnd(
        hasNext: Boolean,
        autoplay: Boolean,
        nextUpDismissed: Boolean,
        sleepAtTheEnd: Boolean,
        askBeforeAutoplay: Boolean,
        nextUpShown: Boolean,
        unseen: Boolean,
    ): AtTheEnd = when {
        sleepAtTheEnd -> AtTheEnd.SleepEnded
        !hasNext || !autoplay || nextUpDismissed -> AtTheEnd.Finished
        askBeforeAutoplay -> AtTheEnd.AskFirst
        else -> AtTheEnd.Next(countdown = !nextUpShown && !unseen)
    }

    /**
     * How long the service stays in the foreground once nothing is playing. Media3 counts it
     * from the moment the player stops being busy: with the viewer's pause that should be at
     * once, so the paused notification can be swiped away; with the player still meaning to play
     * (an episode ending into the next, a stream's retry, a wait for the signal) it is the most
     * Media3 allows, because a backgrounded app that drops out of the foreground cannot come back
     * into it by itself.
     */
    fun foregroundGraceMs(playWhenReady: Boolean): Long = if (playWhenReady) GAP_GRACE_MS else 0L

    /**
     * Whether a launch of the app while the sound plays unseen lands on the player rather
     * than on Home: only while the service holds the player's session, and only when the
     * launch asks for nothing of its own. A launch carrying a link, a screen a notification
     * asked for, or a video from a home screen card has its own errand, and the errand wins.
     */
    fun returnsToPlayer(sessionHeld: Boolean, asksForSomething: Boolean): Boolean =
        sessionHeld && !asksForSomething

    /** Media3's own ceiling for [foregroundGraceMs], ten minutes. */
    const val GAP_GRACE_MS = 600_000L

    /**
     * The media notification's buttons before Android 13, where the notification draws them and
     * there is no seek bar: back and forward by the player's own amounts in the compact view
     * either side of play, and the previous and next episode in the expanded one. Empty from
     * Android 13, whose controls take previous, next and a seek bar from the session itself.
     */
    fun notificationButtons(backMs: Long, forwardMs: Long, sdk: Int = Build.VERSION.SDK_INT): List<CommandButton> {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) return emptyList()
        return listOf(
            CommandButton.Builder(backIcon(backMs))
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(L.notifySeekBack(backMs / 1000))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(forwardIcon(forwardMs))
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(L.notifySeekForward(forwardMs / 1000))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName(L.playerPreviousEpisode)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName(L.playerNextEpisode)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    /** The back icon with the number on it, where Media3 has one for the amount. */
    fun backIcon(ms: Long): Int = when (ms) {
        5_000L -> CommandButton.ICON_SKIP_BACK_5
        10_000L -> CommandButton.ICON_SKIP_BACK_10
        15_000L -> CommandButton.ICON_SKIP_BACK_15
        30_000L -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    /** The forward icon with the number on it, where Media3 has one for the amount. */
    fun forwardIcon(ms: Long): Int = when (ms) {
        5_000L -> CommandButton.ICON_SKIP_FORWARD_5
        10_000L -> CommandButton.ICON_SKIP_FORWARD_10
        15_000L -> CommandButton.ICON_SKIP_FORWARD_15
        30_000L -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }
}
