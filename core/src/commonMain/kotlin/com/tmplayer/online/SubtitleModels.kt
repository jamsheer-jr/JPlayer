package com.tmplayer.online

import org.json.JSONArray
import org.json.JSONObject

/** Where an online subtitle comes from. [label] is the provider's own name, never translated. */
enum class SubtitleProvider(val label: String) {
    OpenSubtitles("OpenSubtitles"),
    Subdl("SubDL"),

    /** Malayalam subtitles from malayalamsubtitles.org, through TMPlayer's gateway; see [MsoneApi]. */
    Msone("MSone"),
}

/**
 * What a search is for: the video's name (and caption) to parse, its size, the OpenSubtitles hash
 * when both ends of the file were on the disk to compute it, and the languages wanted, best first.
 */
data class SubtitleTarget(
    val fileName: String,
    val sizeBytes: Long,
    val hash: String? = null,
    val caption: String? = null,
    val languages: List<String> = listOf("en"),
    /** The video's IMDb id when the online details found one, which MSone searches by first. */
    val imdb: String? = null,
    /** Its TMDB id, which MSone's gateway turns into the IMDb id when that is not known here. */
    val tmdb: String? = null,
)

/** One subtitle a provider offers. [id] is OpenSubtitles' file id, or SubDL's download path. */
data class SubtitleHit(
    val provider: SubtitleProvider,
    val id: String,
    val language: String,
    val release: String,
    val fileName: String = "",
    val downloads: Int = 0,
    val hashMatch: Boolean = false,
    val hearingImpaired: Boolean = false,
    /** Machine or AI translated, which a search leaves out unless the viewer has asked for them. */
    val machine: Boolean = false,
    /**
     * What OpenSubtitles itself files the subtitle under (`feature_details`): the show for an
     * episode (its parent title), the film otherwise, with the season, episode and year it gives.
     * Blank and null where it said nothing, or for a hit cached before these were kept.
     */
    val featureTitle: String = "",
    val featureSeason: Int? = null,
    val featureEpisode: Int? = null,
    val featureYear: Int? = null,
    /** MSone: who translated it, and their page (https only, blank when none). */
    val translator: String = "",
    val translatorUrl: String = "",
    /** MSone: the release's page on malayalamsubtitles.org, for the credit (https only). */
    val pageUrl: String = "",
    /** MSone: the file's own download address (https only); [MsoneApi.fetch] decides whether to use it. */
    val link: String = "",
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("provider", provider.name)
        .put("id", id)
        .put("language", language)
        .put("release", release)
        .put("file", fileName)
        .put("downloads", downloads)
        .put("hash", hashMatch)
        .put("hi", hearingImpaired)
        .put("machine", machine)
        .put("feature", featureTitle)
        .apply {
            featureSeason?.let { put("feature_season", it) }
            featureEpisode?.let { put("feature_episode", it) }
            featureYear?.let { put("feature_year", it) }
            if (translator.isNotEmpty()) put("translator", translator)
            if (translatorUrl.isNotEmpty()) put("translator_url", translatorUrl)
            if (pageUrl.isNotEmpty()) put("page", pageUrl)
            if (link.isNotEmpty()) put("link", link)
        }

    internal companion object {
        fun fromJson(o: JSONObject): SubtitleHit? = runCatching {
            SubtitleHit(
                provider = SubtitleProvider.valueOf(o.getString("provider")),
                id = o.getString("id"),
                language = o.optString("language"),
                release = o.optString("release"),
                fileName = o.optString("file"),
                downloads = o.optInt("downloads"),
                hashMatch = o.optBoolean("hash"),
                hearingImpaired = o.optBoolean("hi"),
                machine = o.optBoolean("machine"),
                featureTitle = o.optString("feature"),
                featureSeason = o.optInt("feature_season", 0).takeIf { it > 0 },
                featureEpisode = o.optInt("feature_episode", 0).takeIf { it > 0 },
                featureYear = o.optInt("feature_year", 0).takeIf { it > 0 },
                translator = o.optString("translator"),
                translatorUrl = MsoneApi.https(o.optString("translator_url")).orEmpty(),
                pageUrl = MsoneApi.https(o.optString("page")).orEmpty(),
                link = MsoneApi.https(o.optString("link")).orEmpty(),
            )
        }.getOrNull()

        fun listToJson(hits: List<SubtitleHit>): JSONArray = JSONArray().apply { hits.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray): List<SubtitleHit> =
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
    }
}

/**
 * One series' MSone pick, remembered (owner, v1.0.23): the release an episode of
 * the series was watched with, and the episode it last stood for, kept as the one
 * stored string under the series' settings key. The next episode asks the same
 * release for its own file, so a season pack picked once serves the season. The
 * pick was the viewer's own, made in the picker on an earlier episode; nothing
 * is remembered for a series they never chose MSone for.
 */
data class MsoneSeriesPick(
    val hit: SubtitleHit,
    /** The episode the pick last stood for; null where it has not stood yet. */
    val appliedSeason: Int? = null,
    val appliedEpisode: Int? = null,
) {
    /** This pick as the stored string: the hit's own JSON, with the applied episode beside it. */
    fun encode(): String = JSONObject(hit.toJson().toString()).apply {
        appliedSeason?.let { put("applied_season", it) }
        appliedEpisode?.let { put("applied_episode", it) }
    }.toString()

    /** The pick standing for another episode now. */
    fun applied(season: Int?, episode: Int?): MsoneSeriesPick = copy(appliedSeason = season, appliedEpisode = episode)

    companion object {
        /** A stored string read back; null for anything that is not an MSone pick. */
        fun decode(stored: String?): MsoneSeriesPick? {
            if (stored.isNullOrBlank()) return null
            val json = runCatching { JSONObject(stored) }.getOrNull() ?: return null
            val hit = SubtitleHit.fromJson(json) ?: return null
            if (hit.provider != SubtitleProvider.Msone) return null
            return MsoneSeriesPick(
                hit = hit,
                appliedSeason = json.optInt("applied_season", -1).takeIf { it >= 0 },
                appliedEpisode = json.optInt("applied_episode", -1).takeIf { it >= 0 },
            )
        }
    }
}

/**
 * Why a search or a download did not give everything it might have, said once in words every
 * screen shows the same way. A [SearchResult] can carry one beside its hits: a list found by name
 * is still worth showing when downloading is what is blocked.
 */
sealed interface SubtitleNotice {
    /**
     * OpenSubtitles turned down a download made without an account (a 401 where it usually
     * answers): signing in under Settings is the way through.
     */
    data object SignInToDownload : SubtitleNotice

    /** The signed in viewer's downloads for the day are spent; [resetAt] is when more arrive, if OpenSubtitles said. */
    data class QuotaUsed(val resetAt: Long?) : SubtitleNotice

    /**
     * The downloads OpenSubtitles allows without an account are spent until [resetAt]. Signing in
     * under Settings brings the viewer's own daily quota.
     */
    data class FreeQuotaUsed(val resetAt: Long?) : SubtitleNotice

    /** The viewer's sign in ran out, and has to be done again. */
    data object SignInExpired : SubtitleNotice

    /** The app's key was refused: the provider is off until an update brings a new one. */
    data object Unavailable : SubtitleNotice

    /** Too many requests a moment ago; the next try a minute later will go through. */
    data object Busy : SubtitleNotice

    /** No answer from the provider at all. */
    data object Offline : SubtitleNotice

    /** The SubDL key the viewer typed in was refused. */
    data object SubdlKeyRefused : SubtitleNotice

    /** The file arrived but held no subtitle in a format the players read. */
    data object Unreadable : SubtitleNotice

    /**
     * MSone is not answering searches: not set up yet, paused or stopped at MSone's request, or
     * not checked yet ([MsoneState]). [message] is the gateway's own text, shown as is under ours.
     */
    data class MsoneOff(val state: MsoneState, val message: String? = null) : SubtitleNotice

    /** MSone no longer offers the file behind a hit (an expired link, a removed file): search again. */
    data object MsoneGone : SubtitleNotice

    /** MSone's gateway asked for a minute's rest. */
    data object MsoneBusy : SubtitleNotice

    /** The video's name gave nothing MSone could search with. */
    data object MsoneTooShort : SubtitleNotice
}

/** A search's answer: the hits, best first, and why there may be fewer than hoped. */
data class SearchResult(
    val hits: List<SubtitleHit>,
    val notice: SubtitleNotice? = null,
    val fromCache: Boolean = false,
)

/** A download's answer: the subtitle written as a UTF-8 file ready for the player, or why not. */
sealed interface DownloadResult {
    data class Done(val file: java.io.File, val label: String, val fromCache: Boolean) : DownloadResult
    data class Failed(val notice: SubtitleNotice) : DownloadResult
}
