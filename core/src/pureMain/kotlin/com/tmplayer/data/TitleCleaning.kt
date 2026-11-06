package com.tmplayer.data

/**
 * Taking the cam and rip junk out of a title that is about to be shown, when the viewer has
 * asked for it ([hideTags]): the tokens a release name carries for how a copy was captured
 * (a camera in a cinema, a telesync, a pre-release DVD), which say nothing about the video
 * itself and read as noise on a tile.
 *
 * Only the capture junk goes. The facts the app reads off a name and shows as tags (the
 * quality, WEB-DL, WEBRip, Blu-ray, HDTV, DVD, the codec, the audio) are a different list in
 * a different place ([MediaFacts]) and are never touched here, and HDRip is not in this
 * list either: it names a legitimate high definition rip, not a capture, so it stays on a
 * title even with the switch on (the owner's call).
 *
 * This works on the title a tile already goes by ([SeriesShelf.tileTitle] and its film
 * form), not on the file name: the name keeps every token, and only the words on screen
 * change. A title left with nothing once the junk is gone is returned as it was.
 */
object TitleCleaning {

    /** Set from the stored setting at start and whenever it changes; off until then. */
    @Volatile
    var hideTags: Boolean = false

    /**
     * The viewer's own words on top of the built in ones (Settings, Playback, under the same
     * switch), as typed. They are judged by exactly the rule the built in tokens are
     * ([isJunk]'s two prongs: the bare form, then the form with its dashes taken out), and
     * they only ever reach a title through [strip], so they hide nothing while [hideTags]
     * is off. HDRip is still not a built in token; a viewer who types it here has chosen
     * to hide it themselves, which is theirs to choose.
     */
    @Volatile
    var customWords: Set<String> = emptySet()

    /** How many of the viewer's own words are kept; past it the stored tail is dropped. */
    const val MAX_CUSTOM_WORDS = 50

    /** [title] as it is shown: cleaned while [hideTags] is on, untouched while it is off. */
    fun shown(title: String): String = if (hideTags) strip(title) else title

    /**
     * [title] without its capture junk: each word is judged on its own, so a junk token is
     * dropped wherever it stands, and a word that merely contains one ("Camera", "Tsar")
     * is kept whole.
     */
    fun strip(title: String): String {
        if (title.isBlank()) return title
        val words = title.split(WHITESPACE)
        val full = HashSet<String>()
        val squashed = HashSet<String>()
        for (custom in customWords) {
            val form = custom.trim { !it.isLetterOrDigit() }.uppercase()
            if (form.isEmpty()) continue
            full += form
            squashed += form.replace("-", "")
        }
        val kept = words.filterNot { word -> isJunk(word) || isCustom(word, full, squashed) }
        if (kept.isEmpty() || kept.size == words.size) return title
        return kept.joinToString(" ").trim(' ', '-', ':', '|')
    }

    /** A word is junk when its letters alone spell a capture token, brackets and all. */
    private fun isJunk(word: String): Boolean {
        val bare = word.trim { !it.isLetterOrDigit() }.uppercase()
        return bare in JUNK || bare.replace("-", "") in JUNK_SQUASHED
    }

    /** A viewer's word judged exactly as a built in one is: its bare form, or its squashed. */
    private fun isCustom(word: String, full: Set<String>, squashed: Set<String>): Boolean {
        if (full.isEmpty()) return false
        val bare = word.trim { !it.isLetterOrDigit() }.uppercase()
        return bare in full || bare.replace("-", "") in squashed
    }

    /**
     * The stored text of the viewer's words read back (Settings keeps them as one value,
     * one word a line): a line is a word once trimmed, a word already kept in another case
     * is not kept twice, blank and damaged lines are skipped, and the tail past
     * [MAX_CUSTOM_WORDS] falls away. The order the words were added in is the order kept.
     */
    fun parseCustomWords(stored: String?): Set<String> {
        if (stored.isNullOrBlank()) return emptySet()
        val words = LinkedHashSet<String>()
        val seen = HashSet<String>()
        for (line in stored.split('\n')) {
            val word = line.trim()
            if (word.isEmpty() || !seen.add(word.uppercase())) continue
            words += word
            if (words.size >= MAX_CUSTOM_WORDS) break
        }
        return words
    }

    /** The viewer's words as stored text: one word a line, blank and repeated ones left out. */
    fun encodeCustomWords(words: Collection<String>): String =
        words.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.uppercase() }
            .take(MAX_CUSTOM_WORDS)
            .joinToString("\n")

    /** The capture tokens, as a release name writes them. HDRip is absent on purpose. */
    private val JUNK = setOf(
        "CAM", "CAMRIP", "HDCAM", "HDTS", "HDTC", "TS", "TC",
        "TELESYNC", "TELECINE", "PREDVD", "DVDSCR", "SCREENER", "WORKPRINT",
    )

    /** The same tokens with their dash taken out, for "HD-CAM" and "Pre-DVD". */
    private val JUNK_SQUASHED = setOf("HDCAM", "PREDVD")

    private val WHITESPACE = Regex("""\s+""")
}
