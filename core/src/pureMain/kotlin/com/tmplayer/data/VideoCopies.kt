package com.tmplayer.data

import com.tmplayer.platform.concurrentMap
import kotlin.concurrent.Volatile

import kotlin.math.abs

/**
 * What says two videos are one, for [VideoCopies]: a key shared by every copy of a video, or null
 * for a video that is only ever a copy of itself. A seam, so the grouping the browse pages use
 * (titles, years, lengths, and the viewer's own "Not the same video") decides here as well.
 */
fun interface VideoGrouping {
    fun keyOf(item: MediaItem): String?
}

/**
 * One video posted more than once, in one chat or several: which copy to play, and whose place
 * counts.
 *
 * A film in 720p, 1080p and 4K is one film to the viewer. What is stored stays per message (a
 * place, a tick, a bookmark belong to the file they were written for), and "the same video" is
 * worked out when it is read: opening the 1080p after watching half the 720p carries on at the
 * same moment, Continue watching holds one card, and finishing or starting over in one copy
 * clears the others' places. Nothing is rewritten under another key, so a wrong grouping costs a
 * wrong suggestion, never somebody's place.
 */
object VideoCopies {

    /**
     * How copies are recognised. [SeriesShelf.sameVideoKey] (the title read off the name and the
     * year, or the show and episode) until the browse pages' grouping is plugged in here.
     */
    @Volatile
    var grouping: VideoGrouping = VideoGrouping { SeriesShelf.sameVideoKey(it) }

    fun keyOf(item: MediaItem): String? = grouping.keyOf(item)

    /**
     * [copies] with the very same file posted more than once (forwarded, or uploaded again: the
     * same Telegram file, or the same name and size) as one entry, the first of them leading, in
     * the order given. A list of copies to choose from offers a file once, however often it was
     * posted; the rest are the entry's second half, for "posted twice" or "in 2 chats".
     *
     * Copies in a chat the gate hides ([allowed], see [visible]) are never offered.
     */
    fun distinctFiles(
        copies: List<MediaItem>,
        allowed: Set<Long>? = ChatGate.browsing.value,
    ): List<Pair<MediaItem, List<MediaItem>>> {
        val groups = mutableListOf<MutableList<MediaItem>>()
        for (copy in splitScreen(visible(copies, allowed))) {
            val same = groups.firstOrNull { group -> group.any { sameFile(it, copy) } }
            if (same != null) same += copy else groups += mutableListOf(copy)
        }
        // A file the built-in channel posted leads with that channel's own post (owner,
        // 2026-11-06): the same file in another chat never stands in its place.
        return groups.map { group -> (builtInCopy(group) ?: group.first()) to group.toList() }
    }

    /**
     * The copy among [copies] posted by the built-in J MOVIES channel, once its chat id is
     * known ([BuiltInChannels.jMoviesChatId]); null before that, and where it posted none of
     * them. Folds lead with it so the channel's copies are never the ones folded away.
     */
    fun builtInCopy(copies: List<MediaItem>): MediaItem? {
        val chatId = BuiltInChannels.jMoviesChatId ?: return null
        return copies.firstOrNull { it.chatId == chatId }
    }

    /**
     * Split part files are not copies (owner, v1.0.18: ".001, .002 angane .011 vare
     * hidden akiyekk, part files aanath"): a cut video's pieces fold into the one
     * joined video ([SplitPieces.join]), which stands as the copy, and a piece left
     * on its own is dropped: a lone piece, or a set too small or too unlike in size
     * to read as cut, cannot play alone and never stands as a copy of the film. The
     * copy playing is kept by [oneOfEachFile] as it always is, listed or not.
     */
    private fun splitScreen(items: List<MediaItem>): List<MediaItem> =
        SplitPieces.join(items)
            .filter { it.splitPiece == null || it.pieces.size >= 2 }
            // A joined set its own first bytes have proven an archive is not a video
            // either (see [ArchiveHeads]): its name denied it in every spelling, so
            // the bytes were asked, and the verdict is read here like any other.
            .filter { !ArchiveHeads.hides(it, ArchiveHeads.proven.value) }

    /** [distinctFiles], one item per file: the one [playing] where it is among a file's posts. */
    fun oneOfEachFile(
        copies: List<MediaItem>,
        playing: String,
        allowed: Set<Long>? = ChatGate.browsing.value,
    ): List<MediaItem> {
        val shown = distinctFiles(copies, allowed).map { (lead, same) -> same.firstOrNull { it.id == playing } ?: lead }
        // The video playing stays listed, ticked, whatever chat it came from.
        return if (shown.any { it.id == playing }) shown else copies.filter { it.id == playing } + shown
    }

    /**
     * [copies] without those in chats the gate hides: [allowed] is [ChatGate.browsing], null when
     * neither the kids lock nor a choice of chats narrows anything. A copy of the same video in a
     * chat the viewer never added is not offered, so Play opens one of their own chats.
     */
    fun visible(copies: List<MediaItem>, allowed: Set<Long>? = ChatGate.browsing.value): List<MediaItem> =
        if (allowed == null) copies else copies.filter { it.chatId in allowed }

    /** One file posted twice: the same Telegram file, or the same file name and size. */
    fun sameFile(a: MediaItem, b: MediaItem): Boolean = when {
        a.id == b.id -> true
        a.fileId > 0 && a.fileId == b.fileId -> true
        else -> a.fileName.isNotBlank() && a.fileName == b.fileName && a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes
    }

    /**
     * Which video a stored record names. A record that kept its file name ([ResumeRecord.fileName])
     * is read like the live video. One written before records kept it has only its title, so it
     * counts only when that title names a year or an episode: a quality marker alone ("Lecture
     * 1080p") is too little to call two old records one video.
     */
    fun keyOf(record: ResumeRecord): String? = when {
        // "Not the same video" on it: a copy of nothing, however its title reads.
        VideoCorrections.current["${record.chatId}:${record.messageId}"]?.separate == true -> null
        record.fileName != null -> keyOf(record.toMediaItem())
        else -> keyOfTitle(record.title)
    }

    /** The cautious key of a bare title, as [keyOf] reads an old record. */
    fun keyOfTitle(title: String): String? {
        if (title.isBlank()) return null
        val item = MediaItem(
            chatId = 0, messageId = 0, fileId = 0, title = title, sizeBytes = 0, durationSec = 0,
            mimeType = "", thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = title,
        )
        val name = SeriesShelf.episodeOf(item)
        if (name.year == null && name.episode == null) return null
        // A caption's bare "S02E04" reads as a show called "S02E04": no show at all.
        val show = SeriesShelf.keyOf(name.title)
        if (show.isEmpty() || show == SeriesShelf.keyOf(name.episodeCode.orEmpty())) return null
        return SeriesShelf.sameVideoKey(item)
    }

    /**
     * Whether [record] was written for a copy of [item]: the same message, the same Telegram file
     * (a forward shares it, the strongest evidence there is), or the same video by [keyOf].
     */
    fun sameVideo(item: MediaItem, record: ResumeRecord): Boolean {
        if (record.chatId == item.chatId && record.messageId == item.messageId) return true
        if (item.fileId > 0 && record.fileId == item.fileId) return true
        val key = keyOf(item)
        if (key != null && key == keyOf(record)) return true
        // An old record against a live video: both read the cautious way.
        return record.fileName == null && keyOfTitle(item.title)?.let { it == keyOfTitle(record.title) } == true
    }

    /**
     * The record [item] resumes from: its own, or the newest among the copies of the same video,
     * whichever was written last, with its place carried over to [item]'s length ([carryOver]).
     * Null when none was started, or the newest was started in a different cut.
     */
    fun resumeOf(item: MediaItem, records: List<ResumeRecord>): ResumeRecord? {
        val newest = records
            .filter { sameVideo(item, it) }
            .maxWithOrNull(compareBy<ResumeRecord> { it.updatedAt }.thenBy { it.chatId == item.chatId && it.messageId == item.messageId })
            ?: return null
        if (newest.chatId == item.chatId && newest.messageId == item.messageId) return newest
        val at = carryOver(newest.positionMs, newest.durationMs, item.durationSec * 1000L) ?: return null
        return newest.copy(positionMs = at)
    }

    /**
     * A place in one copy, in another: the same moment where the lengths agree within
     * [SAME_LENGTH]; scaled by the lengths where they differ by up to [SCALED_LENGTH] (a 25 fps
     * copy of a 23.976 fps film runs 4% short); null past that, since a copy that much longer or
     * shorter is a different cut and the same timestamp is a different scene. Taken as it is where
     * either length is unknown.
     */
    fun carryOver(positionMs: Long, fromMs: Long, toMs: Long): Long? {
        if (fromMs <= 0 || toMs <= 0) return positionMs
        val ratio = toMs.toDouble() / fromMs
        val gap = abs(1 - ratio)
        return when {
            gap <= SAME_LENGTH -> positionMs
            gap <= SCALED_LENGTH -> (positionMs * ratio).toLong()
            else -> null
        }
    }

    /** Lengths this close are the same cut, the difference being the container or the credits. */
    const val SAME_LENGTH = 0.03

    /** Lengths this close are the same cut at another frame rate. */
    const val SCALED_LENGTH = 0.05

    /**
     * The languages a copy's name announces, as track codes ("ta", "hi"): the audio its uploader
     * named. Empty for a name that names none, which is most of them.
     */
    fun languagesOf(item: MediaItem): Set<String> = languagesIn(item.fileName.ifBlank { item.title })

    /** [languagesOf] for a bare name. */
    fun languagesIn(name: String): Set<String> =
        name.lowercase().split(NOT_WORD).mapNotNullTo(LinkedHashSet()) { LANGUAGES[it] }

    /**
     * The picture a copy is, as a badge names it ("4K", "1080p"), read as the copies list reads it
     * ([heightOf]: the name's marker first, then the size Telegram states), so a video's facts line
     * and the copy named under Play never give one file two qualities (a "720p" badge over
     * "1080p  ·  HEVC" on a TV, 2026-10-23). Null when neither says.
     */
    fun qualityLabel(item: MediaItem): String? = heightOf(item)?.let { if (it >= 2160) "4K" else "${it}p" }

    /**
     * The picture's height a copy was made at, 2160 for 4K: from the name where it says ("1080p",
     * "4K"), else from the size Telegram states, the shorter side. Null when neither says.
     */
    fun heightOf(item: MediaItem): Int? {
        val name = item.fileName.ifBlank { item.title }
        val marked = EpisodeCopies.markers(name).firstNotNullOfOrNull { it.toIntOrNull() }
        if (marked != null) return marked
        if (item.width > 0 && item.height > 0) return snap(minOf(item.width, item.height))
        // "HDRip - 1080.mkv": the height with its "p" left off, which the release reader allows
        // where the rest of the name is release words.
        return when (MediaText.qualityTags(name).firstOrNull()) {
            "4K" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            else -> null
        }
    }

    /**
     * The player top bar's badge for a copy playing at [height], the height read the way this
     * machinery reads it ([heightOf], or Telegram's copy height while one of those plays in
     * place of the upload): "UHD" for a 2160 picture, "HD" for 1080, "SD" for 720 and anything
     * under it. Null when the height is not known, so the badge stays away rather than guessing.
     */
    fun badgeForHeight(height: Int): String? = when {
        height >= 2000 -> "UHD"
        height >= 1080 -> "HD"
        height > 0 -> "SD"
        else -> null
    }

    /** The badge's tiers, lowest first: the order a tap on the badge climbs them. */
    private val TIERS = listOf("SD", "HD", "UHD")

    /**
     * The copy a tap on the quality badge switches to (J Player, 2026-11-06): the next tier
     * up from the one playing that [copies] actually has, wrapping from the top tier back
     * to the lowest, so SD climbs to HD, HD to UHD and UHD wraps to SD. Within the tier,
     * the tier's own best by the order copies are always offered in: the tallest picture,
     * then the largest file. Null when every copy whose height is known sits in one tier:
     * the badge then only names what is playing, and a tap changes nothing.
     */
    fun nextTierCopy(copies: List<MediaItem>, currentId: String): MediaItem? {
        fun tierOf(item: MediaItem): String? = heightOf(item)?.let { badgeForHeight(it) }
        val present = copies.mapNotNull { tierOf(it) }.distinct()
        if (present.size < 2) return null
        val current = copies.firstOrNull { it.id == currentId }?.let { tierOf(it) } ?: return null
        val start = TIERS.indexOf(current)
        val nextTier = (1..TIERS.size)
            .map { TIERS[(start + it) % TIERS.size] }
            .firstOrNull { it in present } ?: return null
        return copies
            .filter { tierOf(it) == nextTier }
            .maxWithOrNull(
                compareBy<MediaItem> { heightOf(it) ?: 0 }
                    .thenBy { MediaFacts.codecRank(MediaFacts.of(it).videoCodec) }
                    .thenBy { it.sizeBytes },
            )
    }

    /** One row of the quality chooser: a level, the copy choosing it plays, and whether it is playing. */
    data class TierChoice(val tier: String, val copy: MediaItem, val current: Boolean)

    /**
     * The quality chooser's rows (J Player, 2026-11-06: the badge tap opens a chooser instead of
     * cycling blind). The rows are the owner's levels (owner, v1.0.18: the levels he named
     * belong here, in this chooser, judged on each resolution's size by [levelOf], or by
     * [seriesLevelOf] for an episode): every level [copies] actually has, lowest first, each
     * with its own best copy by the order copies are always offered in (tallest picture, then
     * the better codec, then largest file), and the level playing marked. A copy under a
     * movie's floor ([CopyLevel.Below]) still stands here at SD: the chooser offers what the
     * video has, and a film whose only copy is 360p still has a quality to name. Copies whose
     * height is not known take no part.
     */
    fun tierChoices(copies: List<MediaItem>, currentId: String, series: Boolean = false): List<TierChoice> {
        val level: (MediaItem) -> CopyLevel = if (series) ::seriesLevelOf else ::levelOf
        fun shown(item: MediaItem): CopyLevel? = when (val found = level(item)) {
            CopyLevel.Unknown -> null
            CopyLevel.Below -> CopyLevel.Sd
            else -> found
        }
        val current = copies.firstOrNull { it.id == currentId }?.let { shown(it) }
        return listOf(CopyLevel.Sd, CopyLevel.Hd, CopyLevel.HdPlus, CopyLevel.Fhd, CopyLevel.Qhd, CopyLevel.Uhd).mapNotNull { wanted ->
            val best = copies
                .filter { shown(it) == wanted }
                .maxWithOrNull(
                    compareBy<MediaItem> { heightOf(it) ?: 0 }
                        .thenBy { MediaFacts.codecRank(MediaFacts.of(it).videoCodec) }
                        .thenBy { it.sizeBytes },
                )
                ?: return@mapNotNull null
            TierChoice(wanted.label, best, wanted == current)
        }
    }

    /**
     * [rows] in the order copies are always offered (best picture first, the better codec
     * of it next, then the biggest: item 13, v1.0.16), grouped under the quality of the copy
     * each row stands for: one group per picture height ([heightOf]), tallest first, each
     * group holding its rows in that order. The rows
     * whose copy states no quality (sent as a file, its name carrying no marker) form the last
     * group, keyed by a null height: no quality is read into them from their size or anything
     * else. The one grouping both copies choosers draw (the detail page's Versions and the
     * player's Copies), so the two lists never disagree about the order or the groups.
     */
    fun <T> tiersOf(rows: List<T>, copyOf: (T) -> MediaItem): List<Pair<Int?, List<T>>> {
        val sorted = rows.sortedWith(
            compareByDescending<T> { heightOf(copyOf(it)) ?: 0 }
                .thenByDescending { MediaFacts.codecRank(MediaFacts.of(copyOf(it)).videoCodec) }
                .thenByDescending { copyOf(it).sizeBytes },
        )
        return sorted.groupBy { heightOf(copyOf(it)) }.entries
            .sortedBy { it.key == null }
            .map { it.key to it.value }
    }

    /**
     * The owner's levels for a copy (owner, 2026-11-08, final table given the same
     * evening), best first: UHD is 4K; QHD is 1440p; at 1080p the high copies
     * (2 GB and up) are FHD and the lighter ones HD+; at 720p a copy of 1.5 GB
     * and up is HD and the rest SD; 480p and under is SD at whatever size, as
     * before; under 480p a movie's copy is [Below], the lowest reading it gets.
     * A copy whose height nothing states is [Unknown]: no level is read into
     * it from its size.
     */
    enum class CopyLevel(val label: String) {
        Uhd("UHD"),
        Qhd("QHD"),
        Fhd("FHD"),
        HdPlus("HD+"),
        Hd("HD"),
        Sd("SD"),
        Below(""),
        Unknown(""),
    }

    /** 2 GB in bytes: at 1080p, the size at and over which a copy reads FHD, not HD+. */
    private const val LEVEL_HIGH_BYTES = 2L * 1024 * 1024 * 1024

    /** 1.5 GB in bytes: at 720p, the size at and over which a copy reads HD, not SD. */
    private const val LEVEL_720_BYTES = 1536L * 1024 * 1024

    /** Which of the owner's levels [item] stands at, by its stated height, then its size. */
    fun levelOf(item: MediaItem): CopyLevel {
        val height = heightOf(item) ?: return CopyLevel.Unknown
        return when {
            height >= 2000 -> CopyLevel.Uhd
            height >= 1300 -> CopyLevel.Qhd
            height >= 1000 -> if (item.sizeBytes >= LEVEL_HIGH_BYTES) CopyLevel.Fhd else CopyLevel.HdPlus
            height >= 600 -> if (item.sizeBytes >= LEVEL_720_BYTES) CopyLevel.Hd else CopyLevel.Sd
            height >= 400 -> CopyLevel.Sd
            else -> CopyLevel.Below
        }
    }

    /**
     * Which level an episode's copy stands at (owner, 2026-11-08): the same table
     * as a movie's, on the same heights and sizes, but with no floor: an episode
     * is small by nature, and a 360p episode still reads SD, still the episode.
     */
    fun seriesLevelOf(item: MediaItem): CopyLevel {
        val height = heightOf(item) ?: return CopyLevel.Unknown
        return when {
            height >= 2000 -> CopyLevel.Uhd
            height >= 1300 -> CopyLevel.Qhd
            height >= 1000 -> if (item.sizeBytes >= LEVEL_HIGH_BYTES) CopyLevel.Fhd else CopyLevel.HdPlus
            height >= 600 -> if (item.sizeBytes >= LEVEL_720_BYTES) CopyLevel.Hd else CopyLevel.Sd
            else -> CopyLevel.Sd
        }
    }

    /**
     * Which of [copies] to play without asking, in this order:
     * 1. the one the viewer chose for this video before ([chosen], an [MediaItem.id]);
     * 2. one already on this device, a download before a cached copy, so nothing is fetched;
     * 3. the one the viewer started ([started]), so a resume lands in the same file;
     * 4. among the rest, those in the viewer's audio [language], where any copy names one;
     * 5. the one nearest the quality the viewer usually plays ([habit]), lower before higher on a
     *    tie, so someone who watches 1080p is not handed a 4K file;
     * 6. the newest.
     *
     * Steps 2 to 6 leave out the copies [tooSlow] says this device would decode too slowly to
     * watch ([DecodeJudge]: AV1 on a TV stick with no AV1 silicon), as long as one copy is left:
     * a smooth copy is the one played without asking. The viewer's own pick (1) is kept whatever
     * it is.
     *
     * With [saveData] (Save mobile data in force: the setting on, on a metered connection),
     * steps 5 and 6 give way to [smallestAdequate] within the language pool: the lightest copy
     * that is still watchable, since every byte of it is fetched. Steps 1 to 3 stand: a chosen
     * copy is the viewer's decision, a copy on the device costs no data, and a resume belongs
     * to the file it was made in.
     */
    fun pick(
        copies: List<MediaItem>,
        chosen: String? = null,
        started: (MediaItem) -> Boolean = { false },
        habit: QualityHabit = QualityHabit(),
        language: String? = null,
        tooSlow: (MediaItem) -> Boolean = { false },
        saveData: Boolean = false,
    ): MediaItem? {
        if (copies.size <= 1) return copies.firstOrNull()
        chosen?.let { id -> copies.firstOrNull { it.id == id } }?.let { return it }
        val smooth = copies.filterNot(tooSlow).ifEmpty { copies }
        return pickAmong(smooth, started, habit, language, saveData)
    }

    /**
     * The copy [pick] would have played if no copy were too slow here, when that is not the one it
     * plays: what the player names when it says it played a smoother copy instead. Null otherwise.
     */
    fun passedOver(
        copies: List<MediaItem>,
        chosen: String? = null,
        started: (MediaItem) -> Boolean = { false },
        habit: QualityHabit = QualityHabit(),
        language: String? = null,
        tooSlow: (MediaItem) -> Boolean = { false },
        saveData: Boolean = false,
    ): MediaItem? {
        if (copies.size <= 1) return null
        if (chosen != null && copies.any { it.id == chosen }) return null
        val plain = pickAmong(copies, started, habit, language, saveData) ?: return null
        val picked = pick(copies, chosen, started, habit, language, tooSlow, saveData) ?: return null
        return plain.takeIf { it.id != picked.id && tooSlow(it) }
    }

    /**
     * The test the app sets for [pick]: whether this device would decode a copy too slowly to
     * watch, as far as its name and size say ([DecodeJudge.pictureOfName]). False until set.
     */
    @Volatile
    var tooSlowHere: (MediaItem) -> Boolean = { false }

    /**
     * Whether Save mobile data is in force on this device right now (the setting on, and the
     * connection metered), as the app keeps it: [pick]'s data saving input, the way
     * [tooSlowHere] is its smoothness one. False until set, and never set on a television
     * ([MeteredPolicy] keeps the setting off there).
     */
    @Volatile
    var saveDataHere: Boolean = false

    /** The lowest height, in pixels, that still reads as the video itself: the data saver's floor. */
    const val ADEQUATE_HEIGHT = 360

    /**
     * The lightest of [copies] that is still watchable, for [pick] when data is being saved:
     * the lowest known height at or above [ADEQUATE_HEIGHT] (the 360p and 480p copies first),
     * the smallest file on a tie, then the newest. When no copy reaches the floor, the highest
     * below it: the best of what there is beats a bigger guess. A copy whose height is not
     * known stands behind every known one; among unknown heights alone, the smallest file
     * wins, then the newest. Null only for an empty list.
     */
    fun smallestAdequate(copies: List<MediaItem>): MediaItem? {
        if (copies.isEmpty()) return null
        val known = copies.mapNotNull { copy -> heightOf(copy)?.let { copy to it } }
        if (known.isEmpty()) {
            return copies.sortedWith(compareBy<MediaItem> { it.sizeBytes }.thenByDescending { it.date }).first()
        }
        val adequate = known.filter { it.second >= ADEQUATE_HEIGHT }
        return if (adequate.isNotEmpty()) {
            adequate.sortedWith(
                compareBy<Pair<MediaItem, Int>> { it.second }
                    .thenBy { it.first.sizeBytes }
                    .thenByDescending { it.first.date },
            ).first().first
        } else {
            known.sortedWith(
                compareByDescending<Pair<MediaItem, Int>> { it.second }
                    .thenBy { it.first.sizeBytes }
                    .thenByDescending { it.first.date },
            ).first().first
        }
    }

    /**
     * Whether this device plays a copy smoothly as far as its name says: true, false (it may
     * stutter), or null when not known, for the copies lists' "Smooth on this TV" line. Null for
     * every copy until the app sets it.
     */
    @Volatile
    var playsSmoothlyHere: (MediaItem) -> Boolean? = { null }

    /** The device word those lines use: `tv`, `phone` or `computer`. */
    @Volatile
    var deviceWord: String = "phone"

    private fun pickAmong(
        copies: List<MediaItem>,
        started: (MediaItem) -> Boolean,
        habit: QualityHabit,
        language: String?,
        saveData: Boolean = false,
    ): MediaItem? {
        if (copies.size <= 1) return copies.firstOrNull()
        copies.firstOrNull { it.locality == MediaItem.Locality.Downloaded }?.let { return it }
        copies.firstOrNull { it.onDevice }?.let { return it }
        copies.firstOrNull(started)?.let { return it }
        val pool = language?.let { code -> copies.filter { code in languagesOf(it) }.ifEmpty { null } } ?: copies
        if (saveData) return smallestAdequate(pool)
        val newest = pool.maxWith(compareBy<MediaItem> { it.date }.thenBy { it.messageId })
        val usual = habit.usual ?: return newest
        return pool
            .mapNotNull { copy -> heightOf(copy)?.let { copy to it } }
            .minWithOrNull(
                compareBy<Pair<MediaItem, Int>> { abs(it.second - usual) }
                    .thenBy { it.second }
                    .thenByDescending { it.first.date },
            )?.first ?: newest
    }

    /** Whether [copies] come from more than one chat, which is when each names its chat. */
    fun spansChats(copies: List<MediaItem>): Boolean = copies.distinctBy { it.chatId }.size > 1

    /** Telegram's stated sizes are rarely the round figure: 1920x800 is a 1080p release cropped wide. */
    private fun snap(shortSide: Int): Int = STEPS.firstOrNull { shortSide <= it * 1.05 } ?: shortSide

    private val STEPS = listOf(240, 360, 480, 576, 720, 1080, 1440, 2160, 4320)

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The audio languages release names spell out, as [MediaName] strips them from titles.
     * The second block is the tags drama and region releases actually carry (owner,
     * v1.0.14): a file named for its catalogue ("KDrama", "CDrama") or with the short
     * codes ("KOR", "CHI") announces its language as plainly as the word "Korean" does,
     * and Home's language rows read no other signal from an unmatched title's name.
     */
    private val LANGUAGES = mapOf(
        "tamil" to "ta", "tam" to "ta", "telugu" to "te", "tel" to "te", "hindi" to "hi", "hin" to "hi",
        "malayalam" to "ml", "mal" to "ml", "kannada" to "kn", "kan" to "kn", "marathi" to "mr",
        "bengali" to "bn", "punjabi" to "pa", "english" to "en", "eng" to "en", "korean" to "ko",
        "japanese" to "ja", "chinese" to "zh", "spanish" to "es", "french" to "fr", "russian" to "ru",
        "german" to "de", "italian" to "it", "turkish" to "tr", "arabic" to "ar",
        "kor" to "ko", "kdrama" to "ko", "cdrama" to "zh", "jdrama" to "ja",
        "chi" to "zh", "mandarin" to "zh", "cantonese" to "zh",
    )
}

/**
 * The qualities the viewer has played, counted, for [VideoCopies.pick]: "1080 twelve times, 720
 * three times". Kept small and plain, a line of `height:count` pairs, since one setting is all it is.
 */
data class QualityHabit(val counts: Map<Int, Int> = emptyMap()) {

    /** The quality played most, the higher on a tie, or null before anything was played. */
    val usual: Int?
        get() = counts.entries.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key

    /**
     * One more play at [height]. Counts are halved once one passes [CAP], so a habit that changes
     * (a new television) wins within a few weeks rather than never.
     */
    fun noted(height: Int?): QualityHabit {
        if (height == null || height <= 0) return this
        val next = counts + (height to (counts[height] ?: 0) + 1)
        val trimmed = if (next.values.any { it > CAP }) next.mapValues { it.value / 2 }.filterValues { it > 0 } else next
        return QualityHabit(trimmed)
    }

    fun encode(): String = counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        const val CAP = 40

        fun decode(text: String?): QualityHabit = QualityHabit(
            text.orEmpty().split(',').mapNotNull { pair ->
                val (height, count) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val h = height.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val c = count.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                h to c
            }.toMap(),
        )
    }
}

/** The short name a copy goes by on a button: "1080p", "4K", else its size. */
fun MediaItem.copyQuality(): String = VideoCopies.qualityLabel(this) ?: MediaText.formatSize(sizeBytes)

/**
 * The copies a page knew of, handed to the player it opens: a film's [MediaItem.versions], which
 * may span chats when they came from a search across them. The player only gets the one copy it
 * plays through its start arguments, and asking the chat again would miss the other chats.
 * Kept by every copy's [MediaItem.id], so a switch to another copy finds the same list.
 */
object CopyHandoff {
    private class Offer(val copies: List<MediaItem>, val chatTitles: Map<Long, String>)

    private val offers = concurrentMap<String, Offer>()

    /**
     * Remembers [copies], with the name of each chat they are in where known. Names offered for
     * these copies before are kept: the player re-offers what a search across chats handed it with
     * only its own chat's name.
     */
    fun offer(copies: List<MediaItem>, chatTitles: Map<Long, String> = emptyMap()) {
        if (copies.size <= 1) return
        // This lives as long as the process does: a long session starts over rather than grows.
        if (offers.size > MAX) offers.clear()
        val known = copies.mapNotNull { offers[it.id]?.chatTitles }.fold(emptyMap<Long, String>()) { all, more -> all + more }
        val offer = Offer(copies, known + chatTitles)
        copies.forEach { offers[it.id] = offer }
    }

    /** The copies offered for [item], itself included, or just [item] when none were. */
    fun copiesOf(item: MediaItem): List<MediaItem> = offers[item.id]?.copies ?: listOf(item)

    /** The chat a copy is in, by name, where the page that offered it said. */
    fun chatTitleOf(item: MediaItem): String? = offers[item.id]?.chatTitles?.get(item.chatId)

    private const val MAX = 400
}
