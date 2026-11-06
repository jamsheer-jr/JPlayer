package com.tmplayer.player

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.player.BackgroundPlayback.AtTheEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The decisions behind background audio: the video track, the end of a video, the buttons. */
@RunWith(RobolectricTestRunner::class)
class BackgroundPlaybackTest {

    private val defaults = TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext()).build()

    @Test
    fun `going unseen turns only the video off, and coming back only turns it on`() {
        val chosen = defaults.buildUpon()
            .setPreferredAudioLanguage("ja")
            .setPreferredTextLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val hidden = BackgroundPlayback.withoutVideo(chosen)!!
        assertTrue(C.TRACK_TYPE_VIDEO in hidden.disabledTrackTypes)
        assertTrue(C.TRACK_TYPE_TEXT in hidden.disabledTrackTypes)
        assertFalse(C.TRACK_TYPE_AUDIO in hidden.disabledTrackTypes)
        assertEquals(listOf("ja"), hidden.preferredAudioLanguages)

        val back = BackgroundPlayback.withVideo(hidden)
        assertEquals(chosen, back)
    }

    @Test
    fun `video already off is left alone, with nothing to undo`() {
        val off = defaults.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        assertNull(BackgroundPlayback.withoutVideo(off))
    }

    private fun end(
        hasNext: Boolean = true,
        autoplay: Boolean = true,
        dismissed: Boolean = false,
        sleep: Boolean = false,
        ask: Boolean = false,
        cardShown: Boolean = false,
        unseen: Boolean = false,
    ) = BackgroundPlayback.atTheEnd(hasNext, autoplay, dismissed, sleep, ask, cardShown, unseen)

    @Test
    fun `out of sight the next episode starts at once, in sight after the countdown`() {
        assertEquals(AtTheEnd.Next(countdown = false), end(unseen = true))
        assertEquals(AtTheEnd.Next(countdown = true), end(unseen = false))
        // The card already counted down in front of the viewer.
        assertEquals(AtTheEnd.Next(countdown = false), end(cardShown = true))
    }

    @Test
    fun `the autoplay setting holds out of sight as well`() {
        assertEquals(AtTheEnd.Finished, end(autoplay = false, unseen = true))
        assertEquals(AtTheEnd.Finished, end(hasNext = false, unseen = true))
        assertEquals(AtTheEnd.Finished, end(dismissed = true, unseen = true))
    }

    @Test
    fun `the sleep timer and Still watching come before the next episode`() {
        assertEquals(AtTheEnd.SleepEnded, end(sleep = true, unseen = true))
        assertEquals(AtTheEnd.SleepEnded, end(sleep = true, hasNext = false))
        assertEquals(AtTheEnd.AskFirst, end(ask = true, unseen = true))
    }

    @Test
    fun `a pause lets the foreground go at once, a gap while meaning to play keeps it`() {
        assertEquals(0L, BackgroundPlayback.foregroundGraceMs(playWhenReady = false))
        assertEquals(BackgroundPlayback.GAP_GRACE_MS, BackgroundPlayback.foregroundGraceMs(playWhenReady = true))
    }

    @Test
    fun `before Android 13 the compact notification seeks by the player's amounts`() {
        val buttons = BackgroundPlayback.notificationButtons(Skip.BACK_MS, Skip.FORWARD_MS, sdk = Build.VERSION_CODES.S_V2)
        val back = buttons.single { it.slots.contains(CommandButton.SLOT_BACK) }
        val forward = buttons.single { it.slots.contains(CommandButton.SLOT_FORWARD) }
        assertEquals(Player.COMMAND_SEEK_BACK, back.playerCommand)
        assertEquals(Player.COMMAND_SEEK_FORWARD, forward.playerCommand)
        assertEquals(CommandButton.ICON_SKIP_BACK_5, back.icon)
        assertEquals(CommandButton.ICON_SKIP_FORWARD_10, forward.icon)
        // The episodes stay, in the expanded view.
        val overflow = buttons.filter { it.slots.contains(CommandButton.SLOT_OVERFLOW) }.map { it.playerCommand }
        assertEquals(listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT), overflow)
        assertTrue(buttons.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `from Android 13 the system's own controls are left as they are`() {
        assertTrue(BackgroundPlayback.notificationButtons(5_000, 10_000, sdk = Build.VERSION_CODES.TIRAMISU).isEmpty())
    }

    @Test
    fun `a plain return lands on the player only while its session is held and it asks for nothing`() {
        assertTrue(BackgroundPlayback.returnsToPlayer(sessionHeld = true, asksForSomething = false))
        // Nothing playing unseen: the launch is just the app opening.
        assertFalse(BackgroundPlayback.returnsToPlayer(sessionHeld = false, asksForSomething = false))
        // A link, a screen or a video the launch carries is its own errand and wins.
        assertFalse(BackgroundPlayback.returnsToPlayer(sessionHeld = true, asksForSomething = true))
        assertFalse(BackgroundPlayback.returnsToPlayer(sessionHeld = false, asksForSomething = true))
    }

    @Test
    fun `an amount with no numbered icon gets the plain one`() {
        assertEquals(CommandButton.ICON_SKIP_BACK_30, BackgroundPlayback.backIcon(30_000))
        assertEquals(CommandButton.ICON_SKIP_BACK, BackgroundPlayback.backIcon(7_000))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_15, BackgroundPlayback.forwardIcon(15_000))
        assertEquals(CommandButton.ICON_SKIP_FORWARD, BackgroundPlayback.forwardIcon(20_000))
    }
}
