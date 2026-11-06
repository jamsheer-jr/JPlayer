package com.tmplayer.data

/**
 * A stream link found in a channel post (v1.0.10): where it plays and the name it was posted
 * with. A live stream ([live] true) is an HLS link: the player joins it at its live edge, with
 * a LIVE badge and no saved place. A DASH link ([dash] true) is an .mpd address. A plain file
 * link plays like any other video file. [source] names where the link came from (the channel
 * it was posted in, or the site a pasted link points at), for the line under its name.
 * [chatId] and [messageId] name the post that carried the link, where there was a post: the
 * Live row's hidden set ([HiddenStreams]) knows a stream by its post, so a repost (a new
 * post, a new id) shows again and the same post never comes back once removed.
 */
data class StreamLink(
    val url: String,
    val name: String,
    val live: Boolean,
    val dash: Boolean = false,
    val source: String = "",
    val chatId: Long = 0,
    val messageId: Long = 0,
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

    /**
     * A Google Drive share address turned into the direct address the player streams
     * (owner, 2026-11-06): a public file's /file/d/<id> page, an ?id= link, or a uc link all
     * name the same file, and Drive serves the file itself from the uc download address.
     * Only a public file answers there; a private one refuses, and the player says so.
     * Null for any address that is not a Drive file address.
     */
    fun driveDirect(token: String): String? {
        val cleaned = token.trimEnd(')', '.', ',', ';', ']', '>', '"', '\'')
        val lower = cleaned.lowercase()
        if ("drive.google.com" !in lower && "docs.google.com" !in lower) return null
        val id = Regex("""/d/([A-Za-z0-9_-]{10,})""").find(cleaned)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(cleaned)?.groupValues?.getOrNull(1)
            ?: return null
        return "https://drive.google.com/uc?export=download&id=$id"
    }

    /** Hosts whose pages hide the file address behind a page of their own (GDFlix, HubCloud). */
    private val PAGE_HOSTS = listOf("gdflix", "hubcloud")

    /**
     * Whether [url] points at a file host page the app should read through for the file
     * address inside it, rather than play as it stands. Best effort by design: those pages
     * are built by script and change often, so a page that yields nothing is a plain failure.
     */
    fun isPageLink(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
        return PAGE_HOSTS.any { it in host }
    }

    /** The first stream link in one post's [text], or null when the post carries none. */
    fun inPost(text: String, fallbackName: String): StreamLink? {
        for (match in CANDIDATE.findAll(text)) {
            driveDirect(match.value)?.let { direct ->
                val lineStart = text.lastIndexOf('\n', match.range.first - 1) + 1
                val lineEnd = text.indexOf('\n', match.range.last + 1).let { if (it < 0) text.length else it }
                val piped = text.substring(lineStart, lineEnd).substringAfter('|', "").trim()
                val before = text.substring(0, match.range.first)
                    .lineSequence().firstOrNull()?.trim().orEmpty()
                return StreamLink(
                    url = direct,
                    name = piped.ifBlank { before }.ifBlank { fallbackName },
                    live = false,
                    source = fallbackName,
                )
            }
            val found = streamOf(match.value, wider = false) ?: continue
            // The owner's post format (2026-11-06): "Link | Name", the name after the pipe on
            // the link's own line wins; without a pipe, the text before the link, first line.
            val lineStart = text.lastIndexOf('\n', match.range.first - 1) + 1
            val lineEnd = text.indexOf('\n', match.range.last + 1).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            val piped = line.substringAfter('|', "").trim()
            val before = text.substring(0, match.range.first)
                .lineSequence().firstOrNull()?.trim().orEmpty()
            val name = piped.ifBlank { before }
            return StreamLink(
                url = found.url,
                name = name.ifBlank { fallbackName },
                live = found.live,
                dash = found.dash,
                source = fallbackName,
            )
        }
        return null
    }

    /**
     * The stream links in [posts] (newest first), at most [LIMIT] of them. Each carries the
     * post it was read from ([chatId] and the post's own message id), so the Live row can
     * tell one post of a stream from a later repost of the same address.
     */
    fun inPosts(posts: List<ChannelPost>, chatId: Long, channelName: String): List<StreamLink> =
        posts.mapNotNull { post ->
            inPost(post.text, channelName)?.copy(chatId = chatId, messageId = post.messageId)
        }.take(LIMIT)

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
            driveDirect(match.value)?.let { direct ->
                return StreamLink(url = direct, name = "Google Drive video", live = false, source = "drive.google.com")
            }
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

    /** The shapes a pasted link may carry: live HLS, DASH, and the usual direct file shapes. */
    private val PASTED_SHAPES = listOf(
        Shape("m3u8", live = true),
        Shape("mpd", dash = true),
        Shape("mp4"), Shape("mkv"), Shape("webm"), Shape("mov"), Shape("avi"), Shape("ts"), Shape("m4v"),
    )

    /**
     * The shapes a channel post may carry (owner, 2026-11-06): the same reading as a pasted
     * link. His posts carry .mkv and .mpd links as well as live and mp4 ones, and a post read
     * narrowly left those streams off the Live row entirely.
     */
    private val POST_SHAPES = PASTED_SHAPES

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
