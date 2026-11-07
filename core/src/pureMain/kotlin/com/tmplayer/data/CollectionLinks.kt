package com.tmplayer.data

/**
 * A collection as a link (item 8d, v1.0.15): the collection's name and the progress keys of its
 * titles, in this app's own link scheme beside the Watch party link (`jplayer://collection`).
 *
 * The link carries only references: a title's key is its chat and post ids, the same on every
 * account. Whoever opens it gets the collection joined with their own, and a title shows only
 * where their account can see its chat, exactly the Watch party rule: the link is never a copy
 * of a video and never anybody's session. A name is carried percent encoded, the way addresses
 * carry text that is not address shaped.
 */
object CollectionLinks {

    private const val PREFIX = "jplayer://collection"

    private val KEY_SHAPE = Regex("""-?\d+_-?\d+""")

    /** A link past this is not a collection anybody made: the caps [Collections] keeps, encoded. */
    private const val MAX_LENGTH = 8192

    /** The link for [collection]: its name, then its titles' keys in their order. */
    fun build(collection: VideoCollection): String = buildString {
        append(PREFIX)
        append("?n=").append(percentEncode(collection.name.take(Collections.MAX_NAME)))
        val keys = collection.keys.filter { KEY_SHAPE.matches(it) }.take(Collections.MAX_KEYS)
        if (keys.isNotEmpty()) append("&k=").append(keys.joinToString(","))
    }

    /** [text] as a collection link, when the whole of it is one; null otherwise. Garbage safe. */
    fun parse(text: String): VideoCollection? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || t.any { it.isWhitespace() }) return null
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        if (t.length > PREFIX.length && t[PREFIX.length] != '?') return null
        val query = queryOf(t.substring(PREFIX.length))
        val name = query["n"]?.let(::percentDecode)?.trim()?.take(Collections.MAX_NAME).orEmpty()
        if (name.isEmpty()) return null
        val keys = query["k"].orEmpty().split(',')
            .map { it.trim() }
            .filter { KEY_SHAPE.matches(it) }
            .distinct()
            .take(Collections.MAX_KEYS)
        return VideoCollection(name, keys)
    }

    /** The query of a link, read the way [PartyLinks] reads one: lower cased keys, first wins. */
    private fun queryOf(text: String): Map<String, String> {
        val q = text.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (key.isEmpty()) null else key to value
        }.toMap()
    }

    private const val HEX = "0123456789ABCDEF"

    /** [value] with every byte outside the address safe set percent encoded, as UTF-8. */
    private fun percentEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
            }
        }
        return out.toString()
    }

    /** The text a percent encoded value carries, or null when the encoding itself is broken. */
    private fun percentDecode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = HEX.indexOf(value[i + 1].uppercaseChar())
                val lo = HEX.indexOf(value[i + 2].uppercaseChar())
                if (hi < 0 || lo < 0) return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                // Anything unencoded rides as its own UTF-8, the way a pasted name arrives.
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
        }
        return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }
}
