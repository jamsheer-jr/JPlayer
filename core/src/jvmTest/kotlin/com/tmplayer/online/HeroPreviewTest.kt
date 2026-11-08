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

    // ---- the TV Home banner preview (item 11b, v1.0.15) --------------------------------------

    private fun banner(
        switchOn: Boolean = true,
        trailer: MetaTrailer? = this.trailer,
        metered: Boolean = false,
        inAppPlayer: Boolean = true,
        reducedMotion: Boolean = false,
    ) = HeroPreview.bannerPreview(switchOn, trailer, metered, inAppPlayer, reducedMotion)

    @Test
    fun theBannerPlaysOnATelevisionWithTheSwitchOn() {
        assertTrue(banner())
    }

    @Test
    fun theBannerFollowsTheSameSwitchMeteredAndMotionRules() {
        assertFalse(banner(switchOn = false))
        assertFalse(banner(trailer = null))
        assertFalse(banner(metered = true))
        assertFalse(banner(inAppPlayer = false))
        assertFalse(banner(reducedMotion = true))
    }

    @Test
    fun reducedMotionKeepsTheArtwork() {
        assertFalse(autoplay(reducedMotion = true))
    }
}
