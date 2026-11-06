package com.tmplayer.data

/**
 * The small stores behind the link features (owner, 2026-11-06): the addresses the viewer
 * opened before, the place a file link was left at, and the channel streams already seen.
 * Each is kept as one settings value and read and written whole, the way the other small
 * stores in this layer are ([HiddenStreams]): every parse step is lenient, a damaged line is
 * dropped and the rest kept, and the caps keep an absurd value from growing without bound.
 */
object LinkHistory {

    const val MAX_ENTRIES = 20

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_ENTRIES) break
            val url = line.trim()
            if (url.startsWith("http") && url !in out) out += url
        }
        return out
    }

    fun encode(urls: List<String>): String =
        urls.distinct().take(MAX_ENTRIES).joinToString("\n")

    /** The history with [url] opened most recently at its head. */
    fun plus(urls: List<String>, url: String): List<String> =
        (listOf(url) + urls.filterNot { it == url }).take(MAX_ENTRIES)
}

/**
 * Where a file link was left, by its address: a stream has no chat or message to carry a
 * resume record, so the place is kept against the address itself. A line is the position in
 * milliseconds, a tab, then the address; live addresses are never stored (a live stream has
 * no place), and the player clears a link's place once it is watched to the end.
 */
object StreamResume {

    const val MAX_ENTRIES = 100

    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_ENTRIES) break
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val ms = line.substring(0, tab).toLongOrNull() ?: continue
            val url = line.substring(tab + 1).trim()
            if (ms > 0 && url.startsWith("http")) out[url] = ms
        }
        return out
    }

    fun encode(places: Map<String, Long>): String =
        places.entries.take(MAX_ENTRIES).joinToString("\n") { (url, ms) -> "$ms\t$url" }

    /** The places with [url]'s set to [ms]; a place of zero or less clears the entry. */
    fun put(places: Map<String, Long>, url: String, ms: Long): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        if (ms > 0) out[url] = ms
        for ((key, value) in places) {
            if (out.size >= MAX_ENTRIES) break
            if (key != url) out[key] = value
        }
        return out
    }
}

/**
 * The channel streams the viewer has already opened, by the post that carried each one:
 * Home's Live row marks a stream new until it is first opened. Same key shape as
 * [HiddenStreams], kept apart because the two choices mean different things.
 */
object SeenStreams {

    const val MAX_SEEN = 200

    fun decode(raw: String?): Set<String> = HiddenStreams.decode(raw)

    fun encode(keys: Collection<String>): String =
        keys.distinct().take(MAX_SEEN).joinToString("\n")

    /** Whether [link]'s post has been opened before. */
    fun seen(keys: Set<String>, link: StreamLink): Boolean = HiddenStreams.keyOf(link) in keys

    /** The seen set with [link] in it. */
    fun plus(keys: Set<String>, link: StreamLink): Set<String> {
        val key = HiddenStreams.keyOf(link)
        return if (key in keys || keys.size < MAX_SEEN) keys + key else keys
    }
}
