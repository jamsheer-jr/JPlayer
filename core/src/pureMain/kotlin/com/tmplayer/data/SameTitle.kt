package com.tmplayer.data

import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata

/**
 * One film or one show, however many times it was posted: what keeps Home's carousel and rows,
 * and the search's cards, from listing the same title twice (user, 2026-10-22: "the same Khalifa
 * movie twice" in the carousel, "Wednesday" twice in Search).
 *
 * [SeriesShelf.sameVideoKey] folds copies whose file names agree; this catches the rest:
 *
 * - The online match: two files matched to the same film or show on the providers are one title,
 *   whatever their names say ([onlineKey]). Read from what is already in memory, never asked for.
 * - The film's name and year, read the way [SeriesShelf.isFilm] reads it: the viewer's own title,
 *   else the file name with its caption. That finds the year a bare "Khalifa.mkv" has only in its
 *   caption, so it folds with "Khalifa (2026) 1080p" posted elsewhere without any online match.
 *   A name without a year never folds by name alone: "Lecture 1080p" in two groups is two lectures.
 *
 * Folded copies keep the best one in front: one on this device, then the highest quality, then
 * the newest ([GlobalSearch.copyOrder]), with every copy in its [MediaItem.versions].
 */
object SameTitle {

    /** The film's normalised name and year, "film:khalifa|2026"; null for anything else. */
    fun filmKey(item: MediaItem): String? {
        // Home keys every tile by this on every build, so the answer for a video the viewer has
        // not corrected is worked out once and kept on the item.
        val corrections = VideoCorrections.current
        val correction = if (corrections.isEmpty()) null else corrections[item.id]
        return if (correction == null) item.uncorrectedFilmKey else filmKeyOf(item, correction)
    }

    /** [filmKey] for [item] under [correction], the viewer's own for it or null for none. */
    internal fun filmKeyOf(item: MediaItem, correction: VideoCorrection?): String? {
        if (item.extra != null) return null
        if (correction?.separate == true) return null
        val renamed = correction?.title
        if (renamed == null && item.fileName.isBlank() && item.caption.isBlank()) return null
        if (renamed == null && item.caption.isBlank() && item.genericFileName) return null
        val name = when {
            renamed != null -> MediaName.parse(renamed)
            // Read once per item where it reads the same name ([MediaItem.parsedName]).
            item.nameForParsing == item.fileName -> item.parsedName
            else -> MediaName.parse(item.fileName, item.caption)
        }
        if (name.isSeries || name.extra != null) return null
        val title = name.titleKey.ifEmpty { return null }
        val year = name.year ?: if (renamed == null) captionYear(item.caption, title) else null
        if (year == null) return null
        // As [SeriesShelf.episodeOf] reads it: the viewer's title where they gave one. A video
        // standing joined from its pieces is the whole film, not its first piece: its key
        // carries no part, so the film's ordinary copies fold with it (owner, v1.0.33: a page
        // opened on a joined copy stood at its own versions while Search, which folds the
        // pieces into one card, counted the film's copies in the dozens).
        val part = if (item.pieces.isNotEmpty()) null
            else if (correction == null) item.parsedName.part
            else SeriesShelf.episodeOf(item).part
        return "film:$title|$year" + if (part != null) "|part$part" else ""
    }

    /**
     * One film's name read for identity, not for show (owner, v1.0.35: "common logic"): the
     * canonical title, the year where the name or its caption states one, and the part where
     * the name writes one. Uploaders write one film many ways ("KGF Chapter 1", "KGF 1",
     * "Tg KGF Chapter 1", "K G F Chapter 2 KGF 2", a site's name worn in front), and the
     * exact [filmKey] reads each spelling as its own film, so a search listed two KGF films
     * as a dozen cards and a detail page gathered only the copies spelled as it was. The
     * canonical reading drops the words that are never a title's (sites, fan tags), joins
     * letter spaced initials into the word they spell, and reads a part however it is
     * written, so every surface that folds copies (search, the detail page, the shelves)
     * folds them by this one reading.
     */
    data class Canon(val title: String, val year: Int?, val part: Int?) {

        /**
         * Whether two readings can be one film: the same canonical title, no year against
         * another (a remake is another film), and the same part, where a name that states
         * no part reads as the first part it usually is. Length is the caller's check
         * ([sameLength]), as with the name and year fold.
         */
        fun matches(other: Canon): Boolean =
            title == other.title &&
                (year == null || other.year == null || year == other.year) &&
                (part == other.part || (part == null && other.part == 1) || (other.part == null && part == 1))
    }

    /** [Canon] for [item], read off the same name [filmKey] reads; null for anything else. */
    fun canonOf(item: MediaItem): Canon? {
        if (item.extra != null) return null
        val corrections = VideoCorrections.current
        val correction = if (corrections.isEmpty()) null else corrections[item.id]
        if (correction?.separate == true) return null
        val renamed = correction?.title
        if (renamed == null && item.fileName.isBlank() && item.caption.isBlank()) return null
        if (renamed == null && item.caption.isBlank() && item.genericFileName) return null
        val name = when {
            renamed != null -> MediaName.parse(renamed)
            item.nameForParsing == item.fileName -> item.parsedName
            else -> MediaName.parse(item.fileName, item.caption)
        }
        if (name.isSeries || name.extra != null) return null
        // One piece of a split film is not the film (owner, v1.0.33's rule): the joined
        // item is, and its name keys with no part, so it reads here as any whole copy.
        if (name.part != null && item.pieces.isEmpty()) return null
        val year = name.year ?: if (renamed == null) captionYear(item.caption, name.titleKey) else null
        // No year, no reading: two lectures that happen to share a name are two
        // videos, the discipline the name and year fold already keeps.
        if (year == null) return null
        return canonOf(name.titleKey, year)
    }

    /** The canonical reading of one parsed title key, with the year the name stated. */
    internal fun canonOf(titleKey: String, year: Int?): Canon? {
        val raw = titleKey.split(' ').filter { it.isNotEmpty() }
        if (raw.isEmpty()) return null
        // The plain case first, in one scan: a title needing none of the readings
        // below (no site word, no part word, no spaced initials, no bare part
        // number at the end, no word written twice) is its own canonical title.
        // Home builds read every film this way, so the common case stays cheap.
        var plain = raw.none { it in TITLE_NOISE || it in PART_WORDS } &&
            (raw.size == 1 || raw.none { it.length == 1 }) &&
            raw.indices.none { index -> (index + 1 until raw.size).any { raw[it] == raw[index] } }
        if (plain && raw.size > 1) {
            val bare = raw.last().takeIf { it.all { char -> char.isDigit() } }?.toIntOrNull()
            if (bare != null && bare in 1..MAX_BARE_PART) plain = false
        }
        if (plain) return Canon(titleKey, year, null)
        var tokens = raw.filter { it !in TITLE_NOISE }
        if (tokens.isEmpty()) return null
        tokens = joinInitials(tokens)
        var part: Int? = null
        val kept = ArrayList<String>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token in PART_WORDS) {
                val number = tokens.getOrNull(index + 1)?.let(::partNumber)
                if (number != null) {
                    part = number
                    index += 2
                } else {
                    // A part word with no number after it ("KGF Chapter (2022)") says nothing.
                    index += 1
                }
                continue
            }
            kept += token
            index += 1
        }
        tokens = kept
        if (part == null && tokens.size > 1) {
            val bare = tokens.last().takeIf { it.all { char -> char.isDigit() } }?.toIntOrNull()
            if (bare != null && bare in 1..MAX_BARE_PART) {
                part = bare
                tokens = tokens.dropLast(1)
            }
        }
        if (part != null && tokens.size > 1) {
            // The part stated twice in one name ("Chapter 2 ... 2") is stated once.
            val last = tokens.last()
            if (last.all { it.isDigit() } && last.toIntOrNull() == part) tokens = tokens.dropLast(1)
        }
        // A title word written twice in one name is the title written once.
        val title = tokens.distinct().joinToString(" ")
        if (title.isEmpty()) return null
        return Canon(title, year, part)
    }

    /** Runs of single letters joined into the word they spell: "k g f" reads "kgf". */
    private fun joinInitials(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) {
                out += run.toString()
                run.clear()
            }
        }
        for (token in tokens) {
            if (token.length == 1 && token[0].isLetter()) run.append(token) else {
                flush()
                out += token
            }
        }
        flush()
        return out
    }

    /** A part's number as written after its word: digits, or a number word ("Chapter One"). */
    private fun partNumber(token: String): Int? =
        if (token.all { it.isDigit() }) token.toIntOrNull()?.takeIf { it in 1..MAX_PART }
        else PART_NUMBER_WORDS[token]

    /** Words that stand in a name without ever being a title's: sites and fan tags. */
    private val TITLE_NOISE = setOf(
        "www", "com", "net", "org", "http", "https", "fans", "tg", "telegram", "kc",
        "1tamilmv", "tamilmv", "1tamilblaster", "tamilblaster", "ibomma", "movierulz",
        "kuttymovies", "isaimini", "filmyzilla", "filmywap", "hdhub4u", "vegamovies",
        "9xmovies", "moviesda", "tamilyogi", "madrasrockers", "jalshamoviez",
        "extramovies", "mp4moviez", "pagalworld", "worldfree4u", "7starhd",
        "downloadhub", "mkvcinemas", "moviesverse", "bolly4u", "skymovieshd",
        "themovieflix", "uwatchfree", "gdflix", "hubcloud",
    )

    /** The words a sequel's part is written with. */
    private val PART_WORDS = setOf("chapter", "part", "vol", "volume")

    /** Number words a part is written with, the ones sequels are actually numbered in. */
    private val PART_NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    /** The highest part a name is read as carrying; a bigger trailing number is a title's own. */
    private const val MAX_PART = 99

    /** The highest bare trailing number read as a part: "Apollo 13" keeps its number. */
    private const val MAX_BARE_PART = 12

    /**
     * Whether [a] and [b] run about as long, so one name and year is one film and not another cut of
     * it: within a sixth of the longer, or either length unknown.
     */
    fun sameLength(a: MediaItem, b: MediaItem): Boolean {
        if (a.durationSec <= 0 || b.durationSec <= 0) return true
        val longer = maxOf(a.durationSec, b.durationSec)
        return kotlin.math.abs(a.durationSec - b.durationSec) * 6 <= longer
    }

    /**
     * The year the caption's first line gives a film whose file name has none, when that line names
     * the same film ("Khalifa (2026)" under "Khalifa.mkv"); null when it names something else.
     */
    private fun captionYear(caption: String, title: String): Int? {
        val line = caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val read = MediaName.parse(line)
        if (read.isSeries || read.year == null) return null
        return read.year.takeIf { SeriesShelf.keyOf(read.title) == title }
    }

    /**
     * The film or show [item] was matched to online, "tmdb:film:123" or "tmdb:show:456", when that
     * answer is already in memory; null when it is not, or nothing matched. An episode gives its
     * show's, so it is only read for a show's tile or a file that is no episode.
     */
    fun onlineKey(item: MediaItem): String? {
        val online = OnlineMetadata.current ?: return null
        val query = item.metaQuery ?: return null
        val found = runCatching { online.peek(query) }.getOrNull() as? MetaResult.Found ?: return null
        val info = found.info
        val kind = if (info.kind == MetaKind.Show) "show" else "film"
        return "${info.provider.name.lowercase()}:$kind:${info.id}"
    }

    /** Every key [entry] is known by: its online match, its film name and year, its show's name. */
    fun keysOf(entry: ShelfEntry, online: (MediaItem) -> String? = ::onlineKey): List<String> = when (entry) {
        is ShelfEntry.Show -> listOfNotNull(
            "show:" + SeriesShelf.keyOf(entry.series.title),
            online(entry.series.cover)?.let { "online:$it" },
        ).filter { it != "show:" }
        is ShelfEntry.File -> {
            val item = entry.item
            if (item.extra != null || SeriesShelf.episodeOf(item).extra != null) {
                emptyList()
            } else {
                val episode = SeriesShelf.episodeOf(item).isEpisode
                listOfNotNull(
                    // An episode's online match is its show's: only a whole show is one with it.
                    if (episode) null else online(item)?.let { "online:$it" },
                    filmKey(item),
                    // The canonical reading (owner, v1.0.35), stated with its year and
                    // part (no part read as the first): the spellings of one film are
                    // one tile on the shelves too, not only in search and on its page.
                    canonShelfKey(item),
                    SeriesShelf.sameVideoKey(item)?.let { "copy:$it" },
                )
            }
        }
    }

    /**
     * [entries] with each title once, where its first entry stood: copies of one film folded into
     * one tile led by the best copy, and a film's file met again as (or after) a whole show it
     * belongs to dropped for that show.
     */
    fun dedupe(entries: List<ShelfEntry>, online: (MediaItem) -> String? = ::onlineKey): List<ShelfEntry> {
        if (entries.size < 2) return entries
        val out = ArrayList<ShelfEntry?>(entries.size)
        val at = HashMap<String, Int>()
        for (entry in entries) {
            val keys = keysOf(entry, online)
            val index = keys.firstNotNullOfOrNull { at[it] }
            if (index == null) {
                keys.forEach { at[it] = out.size }
                out += entry
                continue
            }
            val held = out[index]!!
            out[index] = when {
                held is ShelfEntry.File && entry is ShelfEntry.File -> ShelfEntry.File(fold(held.item, entry.item))
                // A whole show stands for its lone file, never the other way round.
                held is ShelfEntry.File && entry is ShelfEntry.Show -> entry
                else -> held
            }
            keys.forEach { at.getOrPut(it) { index } }
        }
        return out.filterNotNull()
    }

    /** Two tiles of one film as one: every copy, the best leading ([GlobalSearch.copyOrder]). */
    fun fold(a: MediaItem, b: MediaItem): MediaItem {
        val copies = (copiesOf(a) + copiesOf(b)).distinctBy { it.id }
        val ordered = GlobalSearch.copyOrder(copies)
        // The built-in channel's own post leads where there is one (owner, 2026-11-06): the
        // copies all stay in the versions, in the settled order behind it.
        val lead = VideoCopies.builtInCopy(ordered) ?: ordered.first()
        val sequenced = listOf(lead) + (ordered - lead)
        return lead.copy(versions = sequenced.map { it.copy(versions = emptyList()).keptReads(it) }).keptReads(lead)
    }

    /**
     * [item] with every copy of it in [pool], whichever chat holds each (owner, v1.0.14):
     * a detail page opened from inside one chat lists the same copies and versions a
     * Search or Home opening does, where the list it was opened from folded only its own
     * items. Item 18 did the same for a series' episodes; this is the film and episode
     * copies side. The evidence is the shelves' and search's own: for a film, the same
     * [filmKey] at about the same length ([sameLength]); for anything else, the same
     * copy key ([SeriesShelf.sameVideoKey]). Found copies fold in one by one ([fold]),
     * so the order and the lead are the settled ones, and the item comes back unchanged
     * when the pool holds nothing more.
     */
    fun withPoolCopies(item: MediaItem, pool: List<MediaItem>, online: (MediaItem) -> String? = ::onlineKey): MediaItem {
        if (pool.isEmpty()) return item
        val film = filmKey(item)
        val copyKey = if (film == null) SeriesShelf.sameVideoKey(item) else null
        // The canonical reading stands beside the exact key (owner, v1.0.35): a copy
        // spelled another way (a site's name in front, the part written as a bare
        // number, the initials spaced out) is the same film when the canonical
        // readings agree and the lengths do, the same evidence the search folds by.
        val canon = canonOf(item)
        if (film == null && copyKey == null && canon == null) return item
        // The same online tie the search folds by (owner, v1.0.33): a copy whose name carries
        // no year, or a year the film's other copies do not, still belongs to the film when
        // both stand matched to it online, so a detail page gathers what a search gathered.
        // Films only: an episode's online match is its show's, which would tie every episode
        // of one show together as copies of each other.
        val matchedOnline = if (film != null) online(item) else null
        val held = copiesOf(item).mapTo(HashSet()) { it.id }
        var widened = item
        for (candidate in pool) {
            if (candidate.id in held || candidate.extra != null) continue
            val byCanon = canon != null && canonOf(candidate)?.let { canon.matches(it) } == true &&
                sameLength(candidate, item)
            val match = if (film != null) {
                (filmKey(candidate) == film ||
                    (matchedOnline != null && candidate.metaQuery != null && online(candidate) == matchedOnline) ||
                    byCanon) &&
                    sameLength(candidate, item)
            } else {
                SeriesShelf.sameVideoKey(candidate) == copyKey || byCanon
            }
            if (match) {
                widened = fold(widened, candidate)
                held += candidate.id
            }
        }
        return widened
    }

    /**
     * [Canon] as a shelf key, with its year and part (a name stating no part keys as the
     * first part, the fold [Canon.matches] allows): null when the film states no year,
     * since a shelf folds on shared keys alone, with no length to check a yearless
     * reading against.
     */
    private fun canonShelfKey(item: MediaItem): String? {
        val canon = canonOf(item) ?: return null
        val year = canon.year ?: return null
        return "canon:${canon.title}|$year|${canon.part ?: 1}"
    }

    private fun copiesOf(item: MediaItem): List<MediaItem> = item.versions.ifEmpty { listOf(item) }
}
