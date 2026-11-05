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
        val kept = words.filterNot { word -> isJunk(word) }
        if (kept.isEmpty() || kept.size == words.size) return title
        return kept.joinToString(" ").trim(' ', '-', ':', '|')
    }

    /** A word is junk when its letters alone spell a capture token, brackets and all. */
    private fun isJunk(word: String): Boolean {
        val bare = word.trim { !it.isLetterOrDigit() }.uppercase()
        return bare in JUNK || bare.replace("-", "") in JUNK_SQUASHED
    }

    /** The capture tokens, as a release name writes them. HDRip is absent on purpose. */
    private val JUNK = setOf(
        "CAM", "CAMRIP", "HDCAM", "HDTS", "HDTC", "TS", "TC",
        "TELESYNC", "TELECINE", "PREDVD", "DVDSCR", "SCREENER", "WORKPRINT",
    )

    /** The same tokens with their dash taken out, for "HD-CAM" and "Pre-DVD". */
    private val JUNK_SQUASHED = setOf("HDCAM", "PREDVD")

    private val WHITESPACE = Regex("""\s+""")
}
