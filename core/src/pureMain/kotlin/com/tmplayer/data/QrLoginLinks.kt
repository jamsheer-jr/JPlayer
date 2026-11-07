package com.tmplayer.data

/**
 * A Telegram sign in link, the text a sign in QR code carries: `tg://login?token=...`.
 *
 * The television's sign in pane shows this link as a QR code (see AuthState.Qr). Telegram's own
 * app reads it; since v1.0.15 (item 6) J Player on the phone reads it too, and hands it to
 * TDLib's confirmQrCodeAuthentication, so the viewer never has to leave this app to approve a
 * sign in. Only this exact shape is a sign in link: anything else a camera happens to see is
 * not ours to confirm, and is refused here rather than passed to Telegram.
 */
object QrLoginLinks {

    private const val PREFIX = "tg://login?token="

    /** A real token is a few dozen characters; anything past this is not a sign in link. */
    private const val MAX_TOKEN = 512

    /** The whole text, scheme included, bounded the same way. */
    private const val MAX_LENGTH = 600

    /** Base64url, the alphabet Telegram writes the token in, with its padding. */
    private val TOKEN = Regex("^[A-Za-z0-9_-]+={0,2}$")

    /**
     * [text] as a sign in link fit to hand to TDLib, or null when it is not one. The token is
     * case sensitive and is carried through untouched; only the scheme and host, which are
     * not, may differ in case. Reading is garbage safe: anything else is null.
     */
    fun parse(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || t.any { it.isWhitespace() }) return null
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        val token = t.substring(PREFIX.length)
        if (token.isEmpty() || token.length > MAX_TOKEN || !TOKEN.matches(token)) return null
        return t
    }
}
