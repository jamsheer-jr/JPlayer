package com.tmplayer.data


/**
 * What the detail panel can say about a video without opening it.
 *
 * Telegram states a picture's size for a video sent as a video and nothing at all for one sent as
 * a file, and it never names the codecs or the tracks. Releases do, in their file names
 * ("Show.S01E02.1080p.WEB-DL.DDP5.1.x265.mkv"), so everything past the size is read from there.
 * Opening the container would be exact, but it costs a download per panel; the name is free and
 * right for the uploads people actually keep.
 *
 * Every field is null (or empty) when the name does not say, and the panel leaves that line out:
 * a guessed codec is worse than none.
 */
data class MediaFacts(
    /** "4K", "1080p", "720p", "SD": from the stated size when there is one, else from the name. */
    val resolution: String? = null,
    /** "1920 × 1080", only when Telegram stated it. */
    val pixels: String? = null,
    /** "HEVC", "H.264", "AV1", "VP9", "XviD". */
    val videoCodec: String? = null,
    /** "Dolby Vision", "HDR10+", "HDR10", "HDR", "SDR", or "HDR DV" when both are named. */
    val dynamicRange: String? = null,
    /**
     * The OTT service the release came from, when its name carries one of the owner's tags
     * ("Netflix", "Disney+ Hotstar", "ZEE5", ...): what the Home OTT rows group by, with
     * TMDB's watch providers as the other source. See [PLATFORMS].
     */
    val platform: String? = null,
    /** The name carries the AI token in the owner's exact shapes: an AI enhanced upscale. */
    val aiEnhanced: Boolean = false,
    /** The name says the release is a remaster, in any of its common misspellings. */
    val remastered: Boolean = false,
    /** "E-AC3 5.1", "AAC 2.0", "TrueHD Atmos": codec, then the channel layout when named. */
    val audio: String? = null,
    /** What the name says about the tracks inside, beyond the first audio. */
    val tracks: List<Track> = emptyList(),
    /** "WEB-DL", "WEBRip", "BDRip", "BRRip", "Blu-ray", "HDTV", "HDRip", "PreDVD", "PDVD", "PreHD", "HDTS", "HDTC", "TC", "TS", "HDcam", "CAM", "DVDcam", "DVDScr", "BDScr", "Screener", "Workprint", "DVD". */
    val source: String? = null,
    /**
     * The name carries the "clean audio" tag: a cam family copy whose sound was replaced
     * or cleaned, named as its own tag by uploaders ("HDTS Clean Audio"). The tag alone
     * marks the copy as cam family, whatever source the name otherwise reads (owner,
     * v1.0.16). See [isCam].
     */
    val cleanAudio: Boolean = false,
    /** "MKV", "MP4": the extension, upper case. */
    val container: String? = null,
    /**
     * The audio languages the name states after its title, as ISO 639-1 codes ("ta", "hi"): what
     * tells a Tamil copy of a film from its Hindi dub in a copy picker. See [MediaName.languages].
     */
    val languages: List<String> = emptyList(),
) {
    /** Kinds of track a release name announces. The UI words them in the viewer's language. */
    enum class Track { DualAudio, MultiAudio, Subtitles }

    companion object {
        fun of(item: MediaItem): MediaFacts = read(item.fileName, item.width, item.height)

        fun read(fileName: String, width: Int = 0, height: Int = 0): MediaFacts {
            // A joined video stands under its first piece's name, and the piece suffix is
            // not a fact of the video (owner, 2026-11-08: a two part set's page read
            // "Video: 001", the suffix standing as the container). The facts are the
            // whole video's, read off the name the pieces share.
            val base = SplitPieces.baseOf(fileName)
            val name = base.lowercase()
            return MediaFacts(
                resolution = resolutionOf(width, height) ?: MediaText.qualityTags(base).firstOrNull(),
                pixels = if (width > 0 && height > 0) "$width × $height" else null,
                videoCodec = first(name, VIDEO_CODECS),
                dynamicRange = dynamicRangeOf(name),
                platform = first(name, PLATFORMS),
                aiEnhanced = AI_ENHANCED.containsMatchIn(base),
                remastered = REMASTERED.containsMatchIn(name),
                audio = audioOf(name),
                tracks = TRACKS.filter { (_, pattern) -> pattern.containsMatchIn(name) }.map { it.first },
                source = first(name, SOURCES),
                cleanAudio = CLEAN_AUDIO.containsMatchIn(name),
                container = containerOf(base),
                languages = MediaName.languages(base),
            )
        }

        /**
         * The common name for a stated picture size. Either side may decide it: a scope film at
         * 3840 × 1600 is 4K although it is not 2160 tall, and a portrait clip is judged by its width.
         */
        internal fun resolutionOf(width: Int, height: Int): String? {
            if (width <= 0 || height <= 0) return null
            val long = maxOf(width, height)
            val short = minOf(width, height)
            return when {
                long >= 3800 || short >= 2100 -> "4K"
                long >= 1900 || short >= 1060 -> "1080p"
                long >= 1260 || short >= 700 -> "720p"
                else -> "SD"
            }
        }

        /**
         * How the codecs rank against each other for the copy order (item 13, v1.0.16,
         * the owner's ranking): HEVC above H.264, the other modern codecs with HEVC,
         * an unstated codec above the old MPEG-4 pair and below everything named
         * modern. Only the names [of] itself states are ranked; nothing is guessed.
         */
        fun codecRank(codec: String?): Int = when (codec) {
            "HEVC", "AV1", "VP9" -> 5
            "H.264" -> 3
            null -> 2
            "XviD", "DivX" -> 1
            else -> 2
        }

        private fun audioOf(name: String): String? {
            val codec = first(name, AUDIO_CODECS)
            val atmos = ATMOS.containsMatchIn(name) && codec != null
            val channels = CHANNELS.find(name)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            if (codec == null) return null
            return listOfNotNull(codec, "Atmos".takeIf { atmos }, channels).joinToString(" ")
        }

        private fun containerOf(fileName: String): String? {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return null
            val extension = fileName.substring(dot + 1)
            if (extension.length !in 2..4 || !extension.all { it.isLetterOrDigit() }) return null
            if (!MediaText.looksLikeVideo(fileName, "")) return null
            return extension.uppercase()
        }

        private fun first(name: String, table: List<Pair<String, Regex>>): String? =
            table.firstOrNull { (_, pattern) -> pattern.containsMatchIn(name) }?.first

        /** A marker standing on its own: not inside a longer word or number. */
        private fun word(body: String) = Regex("""(?<![a-z0-9])(?:$body)(?![a-z0-9])""")

        /** First match wins, so the more specific spelling of each family comes first. */
        private val VIDEO_CODECS = listOf(
            "HEVC" to word("""x265|h\.?265|hevc"""),
            "H.264" to word("""x264|h\.?264|avc"""),
            "AV1" to word("av1"),
            "VP9" to word("vp9"),
            "XviD" to word("xvid"),
            "DivX" to word("divx"),
        )

        private val DYNAMIC_RANGE = listOf(
            "Dolby Vision" to word("""dv|dovi|dolby[ ._-]?vision"""),
            "HDR10+" to Regex("""(?<![a-z0-9])hdr10(?:\+|plus)"""),
            "HDR10" to word("hdr10"),
            "HDR" to word("hdr"),
            "SDR" to word("sdr"),
        )

        /** HDR and DV named as one combined marker: "HDRDV", "HDR.DV", "DV HDR". */
        private val HDR_DV_COMBINED = word("""hdr[ ._-]?dv|dv[ ._-]?hdr""")

        /**
         * The dynamic range as the owner groups it (2026-11-06): the combined marker reads
         * as the one kind "HDR DV". Separate markers are not combined: a name carrying DV
         * and HDR10 as two facts reads Dolby Vision, as the table order gives it. Otherwise
         * the table order stands: Dolby Vision, then HDR10+, HDR10, HDR, SDR.
         */
        private fun dynamicRangeOf(name: String): String? {
            if (HDR_DV_COMBINED.containsMatchIn(name)) return "HDR DV"
            return first(name, DYNAMIC_RANGE)
        }

        /**
         * The owner's OTT tag dictionary (2026-11-06), first match wins. His words, not the
         * services' marketing: the Hotstar family in India is HS, JHS and DSNP (Disney+ goes
         * out through Hotstar there), JC and JIO are Jio, and SONYLIV is one word, no space.
         * Matching is whole word by [word], so a tag inside a title never fires.
         */
        private val PLATFORMS = listOf(
            "Netflix" to word("nf|netflix"),
            "Prime Video" to word("amzn|amazon|prime[ ._-]?video"),
            "Disney+ Hotstar" to word("hs|jhs|dsnp|hotstar|disney[ ._-]?plus"),
            "ZEE5" to word("z5|zee5"),
            "SonyLIV" to word("sliv|sonyliv"),
            "SunNXT" to word("sunxt|snxt|sunnext|sunnxt"),
            "ManoramaMAX" to word("mmax|manorama[ ._-]?max"),
            "Simply South" to word("ss|simply[ ._-]?south"),
            "Jio" to word("jc|jio|jiocinema"),
            "Aha" to word("aha"),
            "iQIYI" to word("iq|iqiyi"),
            "Rakuten Viki" to word("viki|rakuten[ ._-]?viki"),
        )

        /**
         * The owner's AI rule, exactly as he stated it (2026-11-06): AI counts ONLY as
         * "space AI space" or "dot AI dot", delimited on both sides. No brackets, no string
         * edges, and nothing inside a longer word (Chennai and Airlift are safe, DS4K alone
         * does not count). Case as written in the name: scene tags are upper case, titles are
         * not, so the match is case sensitive on purpose.
         */
        private val AI_ENHANCED = Regex("""(?<=[ .])AI(?=[ .])""")

        /**
         * Remastered and the spellings uploaders actually type (owner, 2026-11-06):
         * REMASTERED, REAMSTER, RMASTRD, REMASTERD all read as the one kind.
         */
        private val REMASTERED = word("""remaster(?:ed)?|reamster|rmastrd|remasterd""")

        /** A codec may run straight into its channels ("DDP5.1", "AAC2.0"), so only the left is bounded. */
        private fun audio(body: String) = Regex("""(?<![a-z0-9])(?:$body)(?![a-z])""")

        private val AUDIO_CODECS = listOf(
            "TrueHD" to audio("truehd"),
            "DTS-HD MA" to audio("""dts[ ._-]?hd[ ._-]?ma"""),
            "DTS" to audio("dts"),
            "E-AC3" to audio("""e[ ._-]?ac[ ._-]?3|ddp|dd\+"""),
            "AC3" to audio("""ac[ ._-]?3|dd(?=[ ._-]?[257]\.[01])"""),
            "FLAC" to audio("flac"),
            "Opus" to audio("opus"),
            "AAC" to audio("aac"),
            "MP3" to audio("mp3"),
        )

        private val ATMOS = word("atmos")

        /** "5.1", "7.1", "2.0", alone or straight after a codec, never a piece of "H.264". */
        private val CHANNELS = Regex("""(?<![0-9])(?<![0-9]\.)([1-8])\.([01])(?![0-9])""")

        /**
         * First match wins. BDRip and BRRip read as what the file says, before the wider
         * Blu-ray label. The cam family sits before DVD, so the DVD inside PreDVD, DVDcam
         * and DVDScr is never read on its own, and HDTS and HDTC sit before TS, so a TS
         * inside them is never read on its own. None of them overlaps HDRip or HDTV: their
         * patterns need rip or tv after hd, which no cam spelling carries. Every pattern
         * is a whole token by [word], so a tag inside a longer word never fires ("Camera"
         * is not CAM, "Scr" in a title is not DVDScr: the DVD must stand right before it).
         */
        private val SOURCES = listOf(
            "WEB-DL" to word("""web[ ._-]?dl"""),
            "WEBRip" to word("""web[ ._-]?rip"""),
            "BDRip" to word("""bd[ ._-]?rip"""),
            "BRRip" to word("""br[ ._-]?rip"""),
            "Blu-ray" to word("""blu[ ._-]?ray|bdremux|remux"""),
            "HDTV" to word("hdtv"),
            "HDRip" to word("""hd[ ._-]?rip"""),
            "PreDVD" to word("""pre[ ._-]?dvd(?:[ ._-]?rip)?"""),
            "PDVD" to word("""p[ ._-]?dvd(?:[ ._-]?rip)?"""),
            "PreHD" to word("""pre[ ._-]?hd|prehd"""),
            "HDTS" to word("""hd[ ._-]?ts(?:[ ._-]?rip)?"""),
            "HDTC" to word("""hd[ ._-]?tc(?:[ ._-]?rip)?|telecine"""),
            "TC" to word("""t[ ._-]?c(?:[ ._-]?rip)?"""),
            "TS" to word("""telesync|ts(?:[ ._-]?rip)?"""),
            "HDcam" to word("""hd[ ._-]?cam(?:[ ._-]?rip)?"""),
            "DVDcam" to word("""dvd[ ._-]?cam(?:[ ._-]?rip)?"""),
            "CAM" to word("""cam[ ._-]?rip|cam|theat(?:re|er)[ ._-]?print"""),
            "DVDScr" to word("""dvd[ ._-]?scr(?:eener)?"""),
            "BDScr" to word("""bd[ ._-]?scr(?:eener)?"""),
            "Screener" to word("""web[ ._-]?scr(?:eener)?|screener|scr"""),
            "Workprint" to word("""work[ ._-]?print|wp"""),
            "DVD" to word("""dvd[ ._-]?rip|dvd"""),
        )

        /**
         * The cam family sources: copies filmed or captured before a retail disc exists.
         * The owner's full list (v1.0.16): PreDVD, DVDcam, DVDScr, HDTS, HDTC, HDcam, and
         * the TS and CAM the family started with. PreHD joins in v1.0.18 (owner: a PRE-HD
         * copy of a title stood unhidden in the Copies list): a pre-release capture, kin
         * to PreDVD. HDRip is not here and never joins: it is a rip of a broadcast, not
         * a camera copy.
         *
         * Completed in v1.0.24 (owner: every theatre print hidden, the kinds looked up):
         * the scene's own table of pirated release types gives Cam (CAM, CAMRip, HDCAM),
         * Telesync (TS, HDTS, TELESYNC, PDVD, PreDVDRip), Telecine (TC, HDTC, TELECINE),
         * Screener (SCR, SCREENER, DVDSCR, BDSCR, WEBSCREENER) and Workprint (WP,
         * WORKPRINT), and the patterns now take the Rip uploaders glue onto them
         * (PreDVDRip itself stood unhidden on the owner's phone, read as no source at
         * all). R5, DDC and PPVRip stay out: a retail disc, a sent review file and a
         * pay per view capture are not theatre prints.
         */
        val CAM_SOURCES: Set<String> = setOf("PreDVD", "PDVD", "PreHD", "DVDcam", "DVDScr", "BDScr", "Screener", "Workprint", "HDTS", "HDTC", "TC", "HDcam", "TS", "CAM")

        /** Whether [source] is one of the cam family labels in [CAM_SOURCES]. */
        fun isCamSource(source: String?): Boolean = source != null && source in CAM_SOURCES

        /** The "clean audio" tag, standing on its own as tags do: see [MediaFacts.cleanAudio]. */
        private val CLEAN_AUDIO = word("""clean[ ._-]?audio""")

        /**
         * Whether [facts] mark a cam family copy: a cam source, or the "clean audio" tag
         * on any source. Read from the file name's own tags, so a copy counts even where
         * a surface never shows its name.
         */
        fun isCam(facts: MediaFacts): Boolean = isCamSource(facts.source) || facts.cleanAudio

        /**
         * Whether [item] is a cam family copy, by its raw file name (the title only where
         * the file name is blank, which is all a surface would have): the one reading
         * every surface's cam hiding shares (Copies, Versions, chat lists, Search, Home).
         *
         * A name that carries a source of its own settles it, cam or not. Only a name
         * silent about its source falls through to the post's caption, the other place a
         * copy says what it is: the owner's HDTS that stood unhidden (2026-11-08) was
         * named in its caption while its file name said nothing at all.
         */
        fun isCam(item: MediaItem): Boolean {
            val named = read(item.fileName.ifBlank { item.title }, item.width, item.height)
            if (named.source != null || named.cleanAudio) return isCam(named)
            if (item.caption.isBlank()) return false
            return isCam(read(item.caption, item.width, item.height))
        }

        private val TRACKS = listOf(
            Track.DualAudio to word("""dual[ ._-]?audio"""),
            Track.MultiAudio to word("""multi[ ._-]?audio|multi"""),
            Track.Subtitles to word("""e?subs?|m?subs|esub|msubs?|subbed|hardsubs?|softsubs?"""),
        )
    }
}
