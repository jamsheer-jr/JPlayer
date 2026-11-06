package com.tmplayer.data

/**
 * A stream link found in a channel post (v1.0.10): where it plays and the name it was posted
 * with. A live stream ([live] true) is an HLS link: the player joins it at its live edge, with
 * a LIVE badge and no saved place. A DASH link ([dash] true) is an .mpd address. A plain file
 * link plays like any other video file. [source] names where the link came from (the channel
 * it was posted in, or the site a pasted link points at), for the line under its name.
 */
data class StreamLink(
    val url: String,
    val name: String,
    val live: Boolean,
    val dash: Boolean = false,
    val source: String = "",
)

/**
 * Reads the stream links out of channel posts. The owner posts a stream as one message whose
 * text names it and carries the address: an http or https link ending in .m3u8 (live) or .mp4
 * (a plain file), with a query string allowed after the extension. The name is the text
 * before the link, first line, trimmed; a post that names nothing falls back to the channel's
 * name. Reading is garbage safe: any text may be scanned, and only well shaped links come out.
 */
object StreamLinks {

    /** The most stream cards Home lists: the newest few, not the channel's whole history. */
    const val LIMIT = 10

    private val CANDIDATE = Regex("""https?://\S+""")

    /** The first stream link in one post's [text], or null when the post carries none. */
    fun inPost(text: String, fallbackName: String): StreamLink? {
        for (match in CANDIDATE.findAll(text)) {
            val found = streamOf(match.value, wider = false) ?: continue
            val name = text.substring(0, match.range.first)
                .lineSequence().firstOrNull()?.trim().orEmpty()
            return StreamLink(url = found.url, name = name.ifBlank { fallbackName }, live = found.live, source = fallbackName)
        }
        return null
    }

    /** The stream links in [posts] (newest first), at most [LIMIT] of them. */
    fun inPosts(posts: List<ChannelPost>, channelName: String): List<StreamLink> =
        posts.mapNotNull { inPost(it.text, channelName) }.take(LIMIT)

    /**
     * The link a viewer pastes to play (v1.0.11): the first address in [text] the player can
     * be pointed at. Wider than a channel post's reading, since the viewer typed it on
     * purpose: an .mpd address is DASH, and a direct file in one of the usual video shapes
     * (mp4, mkv, webm, mov, avi, ts, m4v) is attempted through the same stream mode, which
     * answers with its own error where the address is not a playable file after all. The
     * name is the address's own last part; the source is its host. Null where no address in
     * the text is one of those shapes: the caller says so instead of opening the player.
     */
    fun pasted(text: String): StreamLink? {
        for (match in CANDIDATE.findAll(text)) {
            val found = streamOf(match.value, wider = true) ?: continue
            val name = found.url.substringBefore('?').substringAfterLast('/').ifBlank { found.url }
            val source = found.url.substringAfter("://").substringBefore('/').substringBefore('?')
            return StreamLink(url = found.url, name = name, live = found.live, dash = found.dash, source = source)
        }
        return null
    }

    /** One address shape: its ending, and the kind of stream it makes. */
    private class Shape(val ending: String, val live: Boolean = false, val dash: Boolean = false)

    /** What a stream address is when read. */
    private class Found(val url: String, val live: Boolean, val dash: Boolean)

    /** The shapes a channel post may carry: live HLS, or an mp4 file. */
    private val POST_SHAPES = listOf(Shape("m3u8", live = true), Shape("mp4"))

    /** The shapes a pasted link may carry: a post's shapes, DASH, and the usual file shapes. */
    private val PASTED_SHAPES = POST_SHAPES + listOf(
        Shape("mpd", dash = true),
        Shape("mkv"), Shape("webm"), Shape("mov"), Shape("avi"), Shape("ts"), Shape("m4v"),
    )

    /**
     * The stream address in one candidate token, and its kind; null when the token is not a
     * stream link. Sentence punctuation stuck to the address in prose is not part of it, and
     * the extension must end the address or be followed only by a query string: a page whose
     * name merely contains .mp4 is not a video file.
     */
    private fun streamOf(token: String, wider: Boolean): Found? {
        val cleaned = token.trimEnd(')', '.', ',', ';', ']', '>', '"', '\'')
        val lower = cleaned.lowercase()
        var best: Pair<Int, Shape>? = null
        for (shape in if (wider) PASTED_SHAPES else POST_SHAPES) {
            val at = lower.indexOf(".${shape.ending}")
            if (at >= 0 && (best == null || at < best.first)) best = at to shape
        }
        val (at, shape) = best ?: return null
        val end = at + shape.ending.length + 1
        val rest = cleaned.substring(end)
        if (rest.isNotEmpty() && !rest.startsWith("?")) return null
        return Found(cleaned, shape.live, shape.dash)
    }
}
