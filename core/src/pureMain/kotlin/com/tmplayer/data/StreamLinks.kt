package com.tmplayer.data

/**
 * A stream link found in a channel post (v1.0.10): where it plays and the name it was posted
 * with. A live stream ([live] true) is an HLS link: the player joins it at its live edge, with
 * a LIVE badge and no saved place. A plain file link plays like any other video file.
 */
data class StreamLink(
    val url: String,
    val name: String,
    val live: Boolean,
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
            val found = streamOf(match.value) ?: continue
            val name = text.substring(0, match.range.first)
                .lineSequence().firstOrNull()?.trim().orEmpty()
            return StreamLink(url = found.first, name = name.ifBlank { fallbackName }, live = found.second)
        }
        return null
    }

    /** The stream links in [posts] (newest first), at most [LIMIT] of them. */
    fun inPosts(posts: List<ChannelPost>, channelName: String): List<StreamLink> =
        posts.mapNotNull { inPost(it.text, channelName) }.take(LIMIT)

    /**
     * The stream address in one candidate token, and whether it is live; null when the token
     * is not a stream link. Sentence punctuation stuck to the address in prose is not part of
     * it, and the extension must end the address or be followed only by a query string: a page
     * whose name merely contains .mp4 is not a video file.
     */
    private fun streamOf(token: String): Pair<String, Boolean>? {
        val cleaned = token.trimEnd(')', '.', ',', ';', ']', '>', '"', '\'')
        val lower = cleaned.lowercase()
        val hls = lower.indexOf(".m3u8")
        val file = lower.indexOf(".mp4")
        val (end, live) = when {
            hls >= 0 && (file < 0 || hls < file) -> (hls + 5) to true
            file >= 0 -> (file + 4) to false
            else -> return null
        }
        val rest = cleaned.substring(end)
        if (rest.isNotEmpty() && !rest.startsWith("?")) return null
        return cleaned to live
    }
}
