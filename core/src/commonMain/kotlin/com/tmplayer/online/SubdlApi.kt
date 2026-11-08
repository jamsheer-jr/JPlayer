package com.tmplayer.online

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * SubDL, the fallback when OpenSubtitles has nothing or is unavailable: a free key of the viewer's
 * own (2,000 requests a day), searched by name and year, season and episode. Its files are zip archives;
 * [SubtitleText.fromArchive] takes the subtitle out.
 *
 * See https://subdl.com/api-doc.
 */
class SubdlApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun search(
        key: String,
        name: String,
        season: Int?,
        episode: Int?,
        languages: List<String>,
        // The title's year where it is known (item 11, v1.0.16): SubDL takes it as its
        // own parameter, so same-name remakes stop mixing in the answers.
        year: Int? = null,
        // The title's own ids where the online details found them (owner, v1.0.18):
        // an id names one title exactly, where a name shared with other titles
        // answers with a stranger's subtitles or none at all.
        imdbId: String? = null,
        tmdbId: String? = null,
    ): Reply<List<SubtitleHit>> {
        val params = sortedMapOf(
            "api_key" to key,
            "film_name" to name,
            "languages" to languages.map { it.uppercase(Locale.ROOT) }.distinct().joinToString(","),
            "subs_per_page" to "30",
            "type" to if (season != null || episode != null) "tv" else "movie",
        )
        season?.let { params["season_number"] = it.toString() }
        episode?.let { params["episode_number"] = it.toString() }
        year?.let { params["year"] = it.toString() }
        imdbId?.takeIf { it.isNotBlank() }?.let { params["imdb_id"] = it }
        tmdbId?.takeIf { it.isNotBlank() }?.let { params["tmdb_id"] = it }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        limiter.acquire()
        val response = http.sendLogged(
            PROVIDER,
            HttpRequest("GET", "$API?$query", mapOf("User-Agent" to userAgent, "Accept" to "application/json")),
        ) ?: return Reply.Failed(0)
        // Status only for SubDL: its address carries the viewer's key, and ProviderLog drops it.
        if (response.code !in 200..299) ProviderLog.failed(PROVIDER, "GET", API, "HTTP ${response.code}")
        if (response.code == 401 || response.code == 403) return Reply.Unauthorized
        if (response.code == 429) return Reply.Throttled(1_000)
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return Reply.Failed(response.code)
        if (!json.optBoolean("status")) {
            val error = json.optString("error")
            // A refused key is its own answer. Anything else is this query turned
            // down (a year beside a season and an episode, say), which is a failure
            // the search's ladder answers with a plainer query: returning an empty
            // list here read as "no subtitles" and stopped it (owner, v1.0.18).
            return if (error.contains("key", ignoreCase = true)) Reply.Unauthorized else Reply.Failed(400)
        }
        // The year lives on the results, not on the subtitles (SubDL's own shape):
        // the subtitles belong to the first result, so its year is theirs where a
        // subtitle does not state its own.
        val results = json.optJSONArray("results")
        val resultYear = (if (results != null && results.length() > 0) results.optJSONObject(0)?.optInt("year", 0) else null)
            ?.takeIf { it > 0 }
        val subtitles = json.optJSONArray("subtitles") ?: return Reply.Ok(emptyList())
        return Reply.Ok(
            (0 until subtitles.length()).mapNotNull { index ->
                val item = subtitles.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SubtitleHit(
                    provider = SubtitleProvider.Subdl,
                    id = path,
                    language = languageCode(item.optString("lang"), item.optString("language")),
                    release = item.optString("release_name").ifBlank { item.optString("name") },
                    fileName = item.optString("name"),
                    hearingImpaired = item.optBoolean("hi"),
                    // The season and episode the subtitle is for, where it states
                    // them: the caller's ordering puts the right episode first, and
                    // until these were read every SubDL hit looked episode-less.
                    featureSeason = item.optInt("season", 0).takeIf { it > 0 },
                    featureEpisode = item.optInt("episode", 0).takeIf { it > 0 },
                    // Stated where the answer carries it; the caller's ordering prefers
                    // a hit whose year is the title's (item 11, v1.0.16).
                    featureYear = item.optInt("year", 0).takeIf { it > 0 } ?: resultYear,
                )
            },
        )
    }

    /** The zip behind a hit's download path. */
    suspend fun fetch(path: String, onProgress: ((Long, Long) -> Unit)? = null): Reply<ByteArray> {
        limiter.acquire()
        val url = if (path.startsWith("http")) path else DOWNLOAD + "/" + path.trimStart('/')
        val response = http.sendLogged(PROVIDER, HttpRequest("GET", url, mapOf("User-Agent" to userAgent), onProgress = onProgress))
            ?: return Reply.Failed(0)
        if (response.code in 200..299) return Reply.Ok(response.body)
        ProviderLog.failed(PROVIDER, "GET", url, "HTTP ${response.code}")
        return Reply.Failed(response.code)
    }

    /** SubDL answers "english" in `lang` and "EN" in `language`; the two letter code is what we keep. */
    private fun languageCode(lang: String, language: String): String =
        language.takeIf { it.length == 2 }?.lowercase(Locale.ROOT)
            ?: Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(lang, ignoreCase = true) }?.language
            ?: lang

    companion object {
        const val API = "https://api.subdl.com/api/v1/subtitles"
        const val DOWNLOAD = "https://dl.subdl.com"
        private const val PROVIDER = "SubDL"
    }
}
