package com.tmplayer.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail hero's autoplay rules (owner, v1.0.15, item 24): see [HeroPreview]. */
class HeroPreviewTest {

    private val trailer = MetaTrailer("abc123")

    private fun autoplay(
        switchOn: Boolean = true,
        trailer: MetaTrailer? = this.trailer,
        metered: Boolean = false,
        television: Boolean = false,
        inAppPlayer: Boolean = true,
        reducedMotion: Boolean = false,
    ) = HeroPreview.autoplay(switchOn, trailer, metered, television, inAppPlayer, reducedMotion)

    @Test
    fun aPhoneOnWifiWithTheSwitchOnPlays() {
        assertTrue(autoplay())
    }

    @Test
    fun theSwitchOffKeepsTheArtwork() {
        assertFalse(autoplay(switchOn = false))
    }

    @Test
    fun noTrailerKeepsTheArtwork() {
        assertFalse(autoplay(trailer = null))
    }

    @Test
    fun aMeteredConnectionNeverAutoplays() {
        assertFalse(autoplay(metered = true))
    }

    @Test
    fun aTelevisionKeepsTheArtwork() {
        assertFalse(autoplay(television = true))
    }

    @Test
    fun noInAppPlayerKeepsTheArtwork() {
        assertFalse(autoplay(inAppPlayer = false))
    }

    @Test
    fun reducedMotionKeepsTheArtwork() {
        assertFalse(autoplay(reducedMotion = true))
    }
}
