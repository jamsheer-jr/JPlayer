package com.tmplayer.data

/**
 * The streams a viewer took out of Home's Live row (v1.0.11): the posts they came from, kept
 * as one settings value so the row leaves them out on every later load.
 *
 * A stream is known by the post that carried it: its chat and message ids, the shape of a
 * progress key. A post deleted in the channel and posted again is a new post with a new id,
 * so it shows again; the same post, read fresh from Telegram on every Home load, matches its
 * key every time and never comes back. A link whose post is unknown (a pasted link, say) is
 * known by its address instead, so the reading holds for every shape of stream.
 *
 * Read and written whole, the way the other small stores in this layer are ([Collections]):
 * every parse step is lenient, so a damaged line is dropped and the rest kept, and the cap
 * keeps an absurd value from growing without bound.
 */
object HiddenStreams {

    const val MAX_HIDDEN = 200

    private val POST_SHAPE = Regex("""-?\d+_-?\d+""")

    /** The name a stream is hidden under: its post, or its address when the post is unknown. */
    fun keyOf(link: StreamLink): String =
        if (link.messageId != 0L) "${link.chatId}_${link.messageId}" else link.url

    fun decode(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_HIDDEN) break
            val key = line.trim()
            if (key.isEmpty()) continue
            if (POST_SHAPE.matches(key) || key.startsWith("http")) out += key
        }
        return out
    }

    fun encode(keys: Collection<String>): String =
        keys.distinct().take(MAX_HIDDEN).joinToString("\n")

    /** Whether [link] is among the hidden: the same post stays out, load after load. */
    fun hides(keys: Set<String>, link: StreamLink): Boolean = keyOf(link) in keys

    /** The hidden set with [link] in it; past the cap, one already hidden is still added. */
    fun plus(keys: Set<String>, link: StreamLink): Set<String> {
        val key = keyOf(link)
        return if (key in keys || keys.size < MAX_HIDDEN) keys + key else keys
    }

    /** The streams the Live row may show: [links] with the hidden ones left out. */
    fun visible(links: List<StreamLink>, keys: Set<String>): List<StreamLink> =
        if (keys.isEmpty()) links else links.filterNot { hides(keys, it) }
}
