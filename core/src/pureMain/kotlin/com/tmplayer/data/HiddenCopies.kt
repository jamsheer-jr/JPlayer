package com.tmplayer.data

/**
 * The copies a viewer chose never to be shown again (item 8b, v1.0.15): a bad copy, hidden from
 * the copies lists everywhere, until the viewer brings it back.
 *
 * A copy is known by the post that carried it: its chat and message ids, the shape of a
 * progress key, the same reading [HiddenStreams] gives a stream. The same file posted again
 * elsewhere is another post and another copy: hiding one post of a file never hides the file
 * itself wherever else it was posted. Read and written whole, the way the other small stores
 * in this layer are: every parse step is lenient, so a damaged line is dropped and the rest
 * kept, and the cap keeps an absurd value from growing without bound.
 */
object HiddenCopies {

    const val MAX_HIDDEN = 500

    private val KEY_SHAPE = Regex("""-?\d+_-?\d+""")

    /** The name a copy is hidden under: the post that carried it. */
    fun keyOf(chatId: Long, messageId: Long): String = "${chatId}_${messageId}"

    /** The name [copy] is hidden under. */
    fun keyOf(copy: MediaItem): String = keyOf(copy.chatId, copy.messageId)

    fun decode(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_HIDDEN) break
            val key = line.trim()
            if (KEY_SHAPE.matches(key)) out += key
        }
        return out
    }

    fun encode(keys: Collection<String>): String =
        keys.distinct().take(MAX_HIDDEN).joinToString("\n")

    /** Whether [copy] is among the hidden. */
    fun hides(keys: Set<String>, copy: MediaItem): Boolean = keyOf(copy) in keys

    /** The hidden set with [copy] in it; past the cap, one already hidden is still added by hand. */
    fun plus(keys: Set<String>, copy: MediaItem): Set<String> {
        val key = keyOf(copy)
        return if (key in keys || keys.size < MAX_HIDDEN) keys + key else keys
    }

    /** The hidden set with [copy] brought back. */
    fun minus(keys: Set<String>, copy: MediaItem): Set<String> = keys - keyOf(copy)

    /**
     * The copies a list may show: [copies] with the hidden ones left out. [exempt] names one
     * copy that stays whatever the set says: the copy playing, which the player's sheet always
     * shows, matching the cam and short hides' own exemption.
     */
    fun visible(copies: List<MediaItem>, keys: Set<String>, exempt: MediaItem? = null): List<MediaItem> =
        if (keys.isEmpty()) copies else copies.filterNot { copy ->
            copy !== exempt && hides(keys, copy)
        }
}
