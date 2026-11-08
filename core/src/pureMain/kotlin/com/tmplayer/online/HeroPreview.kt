package com.tmplayer.online

/**
 * Whether a detail page's hero may play its trailer by itself (owner, v1.0.15, item 24).
 * The decisions the batch made, in one place so every page follows them:
 *
 * - The switch: "Autoplay trailer previews" in Settings, Playback, on by default. It is a
 *   plain SettingsStore switch (`hero_trailer_autoplay`), so it travels in backups like
 *   the other Playback switches.
 * - The metered rule, plain and with no override: a preview never autoplays on a metered
 *   connection. A trailer is data the viewer did not ask for yet; the Trailer button still
 *   plays it on any connection, because that press is the viewer asking.
 * - The television: artwork stays. A television's detail page must land the remote on Play
 *   first and keep its D-pad order, a WebView embed takes no part in that order, and the
 *   weak stick class of television is where embedded trailers have already played as
 *   sound over a black picture (see TrailerPlayer). The TV banner preview of item 11b is
 *   the television's moving hero instead; on the detail page the Trailer button plays the
 *   trailer in the app as it did.
 * - Reduced motion is a no, as it is for the Home banner's own movement.
 * - No trailer, no in-app player (the desktop, a set with no WebView): artwork stays.
 */
object HeroPreview {

    fun autoplay(
        switchOn: Boolean,
        trailer: MetaTrailer?,
        metered: Boolean,
        television: Boolean,
        inAppPlayer: Boolean,
        reducedMotion: Boolean,
    ): Boolean = switchOn && trailer != null && !metered && !television && inAppPlayer && !reducedMotion

    /**
     * Whether the TV Home banner may play the focused card's trailer in place (owner,
     * v1.0.15, item 11b), Netflix style. The same switch, the same metered rule and the
     * same reduced-motion rule as [autoplay]; the television itself is the point here, so
     * it is not an exclusion. What keeps it safe on a television is not this rule but the
     * shape of the surface: the preview is drawn inside the banner the focus already
     * rests on, takes no focus and no keys, and is disposed of the moment focus leaves
     * the carousel, so the D-pad order is the carousel's own throughout. A stick too weak
     * for the embed answers through the player's failure callback, and the banner falls
     * back to its artwork.
     */
    fun bannerPreview(
        switchOn: Boolean,
        trailer: MetaTrailer?,
        metered: Boolean,
        inAppPlayer: Boolean,
        reducedMotion: Boolean,
    ): Boolean = switchOn && trailer != null && !metered && inAppPlayer && !reducedMotion
}
