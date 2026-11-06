package com.tmplayer.data

/**
 * One collection of the viewer's own: a name and the progress keys ([ProgressKeys]) of the
 * titles in it, in the order they were added. Collections are the viewer's own lists (a
 * "Family films" row of their own making), drawn on Home under the other shelves.
 */
data class VideoCollection(val name: String, val keys: List<String>)

/**
 * Collections kept as one settings value: a line per collection, the name (escaped) and a
 * tab, then the keys comma separated. Read and written whole, the way the other small stores
 * in this layer are; every parse step is lenient, so a damaged value loses the damaged line
 * and keeps the rest, and the caps keep an absurd value from growing without bound.
 */
object Collections {

    const val MAX_COLLECTIONS = 50
    const val MAX_KEYS = 200
    const val MAX_NAME = 60

    private val KEY_SHAPE = Regex("""-?\d+_-?\d+""")

    fun encode(collections: List<VideoCollection>): String =
        collections.take(MAX_COLLECTIONS).joinToString("\n") { collection ->
            escape(collection.name) + "\t" + collection.keys.take(MAX_KEYS).joinToString(",")
        }

    fun decode(raw: String?): List<VideoCollection> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<VideoCollection>()
        val names = HashSet<String>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_COLLECTIONS) break
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val name = unescape(line.substring(0, tab)).trim().take(MAX_NAME)
            if (name.isEmpty() || !names.add(name.lowercase())) continue
            val keys = line.substring(tab + 1).split(',')
                .map { it.trim() }
                .filter { KEY_SHAPE.matches(it) }
                .distinct()
                .take(MAX_KEYS)
            out += VideoCollection(name, keys)
        }
        return out
    }

    /** Whether [key] (a title's progress key) is in the collection called [name]. */
    fun contains(collections: List<VideoCollection>, name: String, key: String): Boolean =
        collections.firstOrNull { it.name.equals(name, ignoreCase = true) }?.keys?.contains(key) == true

    /**
     * [key] in or out of the collection called [name]: in when it was out, out when it was in.
     * A name nobody has yet starts a new collection holding just this title; a collection
     * emptied by the toggle goes away, so Home never draws a row with nothing in it.
     */
    fun toggled(collections: List<VideoCollection>, name: String, key: String): List<VideoCollection> {
        val wanted = name.trim().take(MAX_NAME)
        if (wanted.isEmpty() || !KEY_SHAPE.matches(key)) return collections
        val index = collections.indexOfFirst { it.name.equals(wanted, ignoreCase = true) }
        if (index < 0) {
            if (collections.size >= MAX_COLLECTIONS) return collections
            return collections + VideoCollection(wanted, listOf(key))
        }
        val current = collections[index]
        val keys = if (key in current.keys) current.keys - key else (current.keys + key).take(MAX_KEYS)
        return if (keys.isEmpty()) {
            collections - current
        } else {
            collections.toMutableList().also { it[index] = current.copy(keys = keys) }
        }
    }

    private fun escape(name: String): String =
        name.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(raw[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
