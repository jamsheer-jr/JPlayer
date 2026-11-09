package com.tmplayer.online

import com.tmplayer.data.MediaName
import com.tmplayer.data.ParsedName
import com.tmplayer.data.valueOrNull
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.i18n.TrackCodes
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Subtitles from the internet, for a video that has none in the language wanted: the one place
 * phone, TV and desktop all go through, so the rules below hold the same everywhere.
 *
 * - **One app key, an account if the viewer wants one.** The key is TMPlayer's, injected at build
 *   time (`OPENSUBTITLES_API_KEY`); a build without one has [inBuild] false and shows nothing of
 *   this. OpenSubtitles' terms forbid asking viewers for keys of their own.
 * - **Downloads work without signing in.** A download with only the app key (no Authorization)
 *   is answered like a signed in one, against an allowance of its own: 100 a day when this was
 *   tried, whether counted per app key or per address OpenSubtitles does not say. What it reports
 *   ([OnlineAccount.anonRemaining], [OnlineAccount.anonResetAt]) is respected before asking, and
 *   once it is spent ([SubtitleNotice.FreeQuotaUsed]) the lists say when it renews and that a sign
 *   in brings the viewer's own quota. A viewer signed in under Settings, Online subtitles downloads
 *   with their token against their own quota ([OnlineAccount.remaining], [OnlineAccount.resetAt]),
 *   exactly as before.
 * - **Search** by the file hash first, then by the parsed title (with season and episode). Machine
 *   and AI translations are left out unless the viewer has switched them on.
 * - **Never in the way.** A refused key turns the provider off with a sentence saying so
 *   ([SubtitleNotice.Unavailable]) until an update brings a new key; a 429 backs off and tries
 *   once more; whatever is cached keeps working; an optional SubDL key, the viewer's own or the
 *   build's, is the fallback.
 * - **Polite.** Five requests a second at most and one sign in a second ([RateLimiter]); one
 *   search per video at a time; everything kept in [cache] for up to six months.
 */
/** One rung of the SubDL ladder in [OnlineSubtitles.searchNow]: what one query carries. */
private data class SubdlAsk(
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val imdbId: String?,
    val tmdbId: String?,
)

class OnlineSubtitles(
    private val apiKey: String,
    val store: OnlineSubtitlesStore,
    val cache: SubtitleCache,
    appVersion: String,
    http: HttpTransport = UrlConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** The build's key for TMPlayer's subtitle gateway; blank and MSone is not offered at all. */
    msoneKey: String = "",
    /**
     * A SubDL key of the build's own, the fallback for a viewer who has not entered one of
     * their own under Settings. Blank and only a viewer's own key works, as before.
     */
    private val buildSubdlKey: String = "",
    /**
     * The platform's browser pass for MSone's release pages (item 5, v1.0.16), handed to
     * [MsoneSiteApi]: a release page address in, the subtitle file's bytes out, null where
     * the page would not give them up. Null where the platform has none.
     */
    private val pageBrowser: (suspend (String) -> ByteArray?)? = null,
) {
    private val userAgent = "TMPlayer v$appVersion"
    private val api = OpenSubtitlesApi(
        apiKey = apiKey,
        userAgent = userAgent,
        http = http,
        limiter = RateLimiter(200, now, sleep),
        loginLimiter = RateLimiter(1_000, now, sleep),
    )
    private val subdl = SubdlApi(http, RateLimiter(200, now, sleep), userAgent)
    private val msone = MsoneApi(http, RateLimiter(500, now, sleep), userAgent, msoneKey)
    private val msoneSite = MsoneSiteApi(http, RateLimiter(1_000, now, sleep), userAgent, pageBrowser)
    private val translator = SubtitleTranslator(http)

    /** Whether this build can reach MSone through the gateway: it has the gateway's key. */
    val msoneInBuild: Boolean = msoneKey.isNotBlank()

    /** Whether the screens offer MSone: a build with the key, and not hidden on this device. */
    val msoneShown: Boolean get() = msoneInBuild && store.now.msoneShown

    /**
     * Whether the screens offer MSone read from its public site instead (item 10, v1.0.15): a
     * build without the gateway key gets the public page surface, gated by the same device
     * switch as the gateway's, so a viewer who hid MSone keeps it hidden. A build with the key
     * keeps its gateway line and never reads the site this way.
     */
    val msoneSiteShown: Boolean get() = !msoneInBuild && store.now.msoneShown
    private val msoneLock = Mutex()

    /** When the last MSone check failed, so a gateway that is down is asked once a minute, not on every open. */
    @Volatile
    private var msoneFailedAt = 0L
    private val locks = HashMap<String, Mutex>()

    /** A short, one way fingerprint of the key, which is all [OnlineAccount.rejectedKey] keeps. */
    private val keyPrint: String = MessageDigest.getInstance("SHA-256")
        .digest(apiKey.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /**
     * Whether this build carries any provider key at all: OpenSubtitles, a build SubDL key or
     * the MSone gateway. Without one the feature is not drawn. A build may carry SubDL only
     * (J Player does): the feature shows, and OpenSubtitles is simply never asked.
     */
    val inBuild: Boolean get() = apiKey.isNotBlank() || buildSubdlKey.isNotBlank() || msoneInBuild

    val account: StateFlow<OnlineAccount> get() = store.account

    /** True while OpenSubtitles is refusing this build's key. Retried once a day in case it was a blip. */
    fun keyRefused(a: OnlineAccount = store.now): Boolean =
        a.rejectedKey == keyPrint && now() - a.rejectedAt < REFUSED_RETRY_MS

    // ---- account ----------------------------------------------------------------------------

    sealed interface SignIn {
        data object Ok : SignIn
        data object WrongPassword : SignIn
        data class Failed(val notice: SubtitleNotice) : SignIn
    }

    suspend fun signIn(username: String, password: String): SignIn {
        if (keyRefused()) return SignIn.Failed(SubtitleNotice.Unavailable)
        return when (val reply = api.login(username.trim(), password)) {
            is Reply.Ok -> {
                val session = reply.value
                val quota = (api.userInfo(session.token, session.baseUrl) as? Reply.Ok)?.value
                store.update {
                    it.copy(
                        username = username.trim(),
                        token = session.token,
                        host = session.baseUrl,
                        allowed = quota?.allowed ?: session.allowed,
                        remaining = quota?.remaining ?: session.allowed,
                        resetAt = 0,
                        expired = false,
                    )
                }
                SignIn.Ok
            }
            Reply.Unauthorized -> SignIn.WrongPassword
            Reply.Forbidden -> {
                refuseKey()
                SignIn.Failed(SubtitleNotice.Unavailable)
            }
            is Reply.Throttled -> SignIn.Failed(SubtitleNotice.Busy)
            is Reply.Quota, is Reply.Failed -> SignIn.Failed(SubtitleNotice.Offline)
        }
    }

    /** Ends the session here and, as a courtesy, at OpenSubtitles. Never fails from the viewer's side. */
    suspend fun signOut() {
        val a = store.now
        if (a.signedIn && !keyRefused(a)) runCatching { api.logout(a.token, a.host) }
        store.update {
            it.copy(username = "", token = "", host = OpenSubtitlesApi.DEFAULT_HOST, allowed = 0, remaining = -1, resetAt = 0, expired = false)
        }
    }

    fun setIncludeMachine(value: Boolean) = store.update { it.copy(includeMachine = value) }

    /** Keeps the language subtitles are translated into as they arrive; blank turns it off. */
    fun setTranslateTo(code: String) = store.update { it.copy(translateTo = code.trim().lowercase(Locale.ROOT)) }

    /**
     * The translate choice made in the player, for the video on screen only (owner,
     * v1.0.17): held in memory and never written to the store, so turning translation
     * on for one video does not make it the default for every other video. Null while
     * the player has made no choice of its own; a blank code is a choice too, off for
     * this video even where the kept setting names a language. The player clears it
     * as it opens and as it closes, so it can never outlive its video.
     */
    private val playerTranslate = MutableStateFlow<String?>(null)

    /** Keeps the player's own translate choice for this video: a language, blank for off, null for no choice. */
    fun setPlayerTranslateTo(code: String?) {
        playerTranslate.value = code?.trim()?.lowercase(Locale.ROOT)
    }

    /** The language subtitles are carried into now: the player's choice for this video first, the kept setting after it. */
    fun translateToNow(): String = playerTranslate.value ?: store.now.translateTo

    /**
     * The language [hit] is to be translated into, or null: translation off, or the subtitle is
     * already in that language (translating English into English only spends time).
     */
    private fun translateTarget(hit: SubtitleHit): String? {
        val target = translateToNow()
        if (target.isBlank()) return null
        return target.takeUnless { SubtitleTranslator.sameLanguage(it, iso1(hit.language) ?: hit.language) }
    }

    /** The cache id of [hit]'s translated file: the hit's own id and the language, kept apart. */
    private fun translatedKey(hit: SubtitleHit, target: String): String = "${hit.id}|$target"

    /**
     * The cache key a download of [hit] for [target] is kept under (owner,
     * v1.0.23): a season pack is ONE hit holding every episode's file, so the
     * key names the episode the file was taken for. Under the hit's own key,
     * the first episode downloaded stood for the whole pack, and the next
     * episode was handed the first one's subtitle out of the cache. Only the
     * pack paths key this way (the site's season zips, SubDL's packs): the
     * MSone gateway hands out one file per hit, so its hits keep the hit's
     * own key, as do hits that are one file for one video and asks without
     * an episode.
     */
    private fun episodeKey(hit: SubtitleHit, target: SubtitleTarget?): String {
        val packPath = hit.provider == SubtitleProvider.Subdl ||
            (hit.provider == SubtitleProvider.Msone && !msoneInBuild)
        if (!packPath) return hit.id
        val parsed = target?.let { MediaName.parse(it.fileName, it.caption) } ?: return hit.id
        val episode = parsed.episode ?: return hit.id
        return "${hit.id}|s${parsed.season ?: hit.featureSeason ?: 0}e$episode"
    }

    /** The track name of a translated download: its new language, and where it came from. */
    private fun translatedLabel(hit: SubtitleHit, target: String): String =
        L.onlineTrackLabel(languageName(target), hit.provider.label)

    /**
     * Keeps [key] as the viewer's SubDL key. The word `msone` is not a key: it hides MSone's search
     * on this device, or shows it again, and the answer is whether MSone is now shown; null for an
     * ordinary key.
     */
    fun setSubdlKey(key: String): Boolean? {
        if (key.trim().equals(MSONE_CODE, ignoreCase = true)) {
            val shown = !store.now.msoneShown
            store.update { it.copy(msoneShown = shown) }
            return shown
        }
        store.update { it.copy(subdlKey = key.trim(), subdlRefused = false) }
        return null
    }

    /** What Settings says under "Online subtitles". */
    fun status(a: OnlineAccount = store.now): OnlineStatus = when {
        !inBuild -> OnlineStatus.NotInBuild
        keyRefused(a) -> OnlineStatus.Unavailable
        a.signedIn && a.quotaUsed(now()) -> OnlineStatus.QuotaUsed(a.username, a.resetAt.takeIf { it > 0 })
        a.signedIn -> OnlineStatus.SignedIn(a.username, remainingNow(a), a.allowed)
        a.expired -> OnlineStatus.Expired(a.username)
        a.anonQuotaUsed(now()) -> OnlineStatus.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 })
        else -> OnlineStatus.SignedOut
    }

    /** The last remaining figure, or the full allowance once the reset time has passed. */
    private fun remainingNow(a: OnlineAccount): Int =
        if (a.remaining == 0 && a.resetAt > 0 && now() >= a.resetAt) a.allowed else a.remaining

    // ---- search ----------------------------------------------------------------------------

    /**
     * The subtitles on offer for [target], best first: hash matches, then the wanted languages in
     * order, then the most downloaded. One search per video at a time; a second caller waits and
     * then reads the first one's answer from the cache.
     */
    suspend fun search(target: SubtitleTarget): SearchResult {
        val lock = synchronized(locks) { locks.getOrPut(target.hash ?: target.fileName) { Mutex() } }
        return lock.withLock { searchNow(target) }
    }

    private suspend fun searchNow(target: SubtitleTarget): SearchResult {
        val a = store.now
        val parsed = MediaName.parse(target.fileName, target.caption)
        val languages = target.languages.mapNotNull(::iso1).distinct().ifEmpty { listOf("en") }
        val machine = a.includeMachine
        val tail = "|${languages.joinToString(",")}|${if (machine) "m" else "-"}"
        val name = (if (parsed.isSeries) parsed.title else parsed.query).trim()

        val queries = buildList {
            target.hash?.let { add("os|hash:$it$tail" to OpenSubtitlesApi.Query(hash = it, languages = languages, includeMachine = machine)) }
            // OpenSubtitles answers a title under three letters with a 400, "Query is too short".
            if (name.length >= MIN_QUERY) {
                add(
                    "os|name:${name.lowercase(Locale.ROOT)}|${parsed.year}|${parsed.season}|${parsed.episode}$tail" to
                        OpenSubtitlesApi.Query(name = name, season = parsed.season, episode = parsed.episode, languages = languages, includeMachine = machine, year = parsed.year),
                )
            }
        }

        var hits = emptyList<SubtitleHit>()
        var fromCache = false
        var problem: SubtitleNotice? = null
        // A search that matches nothing of this video can still answer, with other people's
        // films: a hash nobody has seen brings back whatever is recent in the languages asked for,
        // and a name with a year in it brings back every release of that year. Only an answer
        // with something in it for this video ends the search; anything else is kept, lets the
        // next query have its turn, and is judged on its words with the rest below.
        fun settles(found: List<SubtitleHit>): Boolean = relevant(found, parsed).isNotEmpty()
        if (keyRefused(a)) {
            problem = SubtitleNotice.Unavailable
        } else if (apiKey.isNotBlank()) {
            for ((key, query) in queries) {
                val cached = cache.search(key)
                if (cached != null) {
                    if (cached.isEmpty()) continue
                    hits = (hits + cached).distinctBy { it.id }
                    fromCache = true
                    if (settles(cached)) break
                    continue
                }
                when (val reply = withBackoff { api.search(query, a.host) }) {
                    is Reply.Ok -> {
                        cache.putSearch(key, reply.value)
                        hits = (hits + reply.value).distinctBy { it.id }
                        if (settles(reply.value)) break
                    }
                    Reply.Forbidden, Reply.Unauthorized -> {
                        refuseKey()
                        problem = SubtitleNotice.Unavailable
                        break
                    }
                    is Reply.Throttled, is Reply.Quota -> {
                        problem = SubtitleNotice.Busy
                        break
                    }
                    is Reply.Failed -> {
                        // The service answered and turned this one query down: the next one may
                        // still find something, and the connection is not what failed.
                        if (reply.refusedRequest) continue
                        problem = SubtitleNotice.Offline
                        break
                    }
                }
            }
        }
        hits = hits.filter { machine || !it.machine }

        // SubDL, when there is a key for it and OpenSubtitles came back empty handed or cannot
        // hand over a file today. The key is the viewer's own when they have entered one, and
        // the build's own key otherwise.
        var subdlProblem: SubtitleNotice? = null
        val subdlKey = a.subdlKey.ifBlank { buildSubdlKey }
        val fallback = subdlKey.isNotBlank() && !a.subdlRefused && name.isNotBlank() &&
            (hits.isEmpty() || problem != null || a.quotaUsed(now()) || a.anonQuotaUsed(now()))
        if (fallback) {
            val key = "subdl|${name.lowercase(Locale.ROOT)}|${parsed.year}|${parsed.season}|${parsed.episode}$tail"
            val cached = cache.search(key)
            if (cached != null) {
                hits = hits + cached
                fromCache = fromCache || hits.isNotEmpty()
            } else {
                // The ladder (owner, v1.0.18): SubDL turns a query down whole when
                // its parts do not fit together (a year beside a season and an
                // episode, a language it holds nothing in), and that empty answer
                // read as "no subtitles found" for titles it holds plenty of, a
                // series episode most of all. So the queries go from the surest to
                // the plainest, and the first answer with anything in it ends the
                // ladder: the title's own ids where the details found them, the
                // full query as it always was, then without the year, then the
                // season without the episode, then the name alone. The ladder's
                // answer is cached under the one key above, so a second search of
                // the same video asks nothing.
                val ladder = buildList {
                    if (!target.imdb.isNullOrBlank() || !target.tmdb.isNullOrBlank()) {
                        add(SubdlAsk(parsed.season, parsed.episode, parsed.year, target.imdb, target.tmdb))
                    }
                    add(SubdlAsk(parsed.season, parsed.episode, parsed.year, null, null))
                    if (parsed.year != null) add(SubdlAsk(parsed.season, parsed.episode, null, null, null))
                    if (parsed.episode != null) add(SubdlAsk(parsed.season, null, null, null, null))
                    if (parsed.season != null || parsed.episode != null) add(SubdlAsk(null, null, null, null, null))
                }
                var found: List<SubtitleHit>? = null
                var answeredOk = false
                for (ask in ladder) {
                    when (val reply = withBackoff {
                        subdl.search(subdlKey, name, ask.season, ask.episode, languages, year = ask.year, imdbId = ask.imdbId, tmdbId = ask.tmdbId)
                    }) {
                        is Reply.Ok -> {
                            answeredOk = true
                            if (reply.value.isNotEmpty()) {
                                found = reply.value
                                break
                            }
                        }
                        Reply.Unauthorized, Reply.Forbidden -> {
                            store.update { it.copy(subdlRefused = true) }
                            subdlProblem = SubtitleNotice.SubdlKeyRefused
                            found = emptyList()
                            break
                        }
                        // A query turned down, or no connection: the next,
                        // plainer one has its turn.
                        else -> Unit
                    }
                }
                // Cached only where SubDL answered (an empty answer included, as
                // it always was): a ladder that failed outright end to end is
                // asked again next time, as a failed search always was.
                val answer = found ?: if (answeredOk) emptyList() else null
                if (answer != null) {
                    cache.putSearch(key, answer)
                    hits = hits + answer
                }
            }
        }

        val order = languages.withIndex().associate { (i, code) -> code to i }
        val sorted = relevant(hits, parsed).sortedWith(
            compareByDescending<SubtitleHit> { it.hashMatch }
                // A hit whose stated year is the title's year stands over one that does
                // not say, or says another (item 11, v1.0.16); series keep their season
                // and episode through relevance below, as they always have.
                .thenByDescending { parsed.year != null && it.featureYear == parsed.year }
                .thenByDescending { relevance(it, parsed) }
                .thenBy { order[iso1(it.language)] ?: order.size }
                .thenBy { it.provider.ordinal }
                .thenByDescending { it.downloads },
        )
        val anyOpenSubtitles = sorted.any { it.provider == SubtitleProvider.OpenSubtitles }
        val notice = when {
            sorted.isEmpty() -> problem ?: subdlProblem
            problem != null -> problem
            anyOpenSubtitles -> accountNotice(a) ?: subdlProblem
            else -> subdlProblem
        }
        return SearchResult(sorted, notice, fromCache)
    }

    /**
     * The line above OpenSubtitles results: a spent quota, the viewer's or the free one, or a sign
     * in that ran out. An expired sign in does not block anything, downloads carry on without an
     * account meanwhile; [downloadBlock] is what does.
     */
    private fun accountNotice(a: OnlineAccount): SubtitleNotice? =
        downloadBlock(a) ?: SubtitleNotice.SignInExpired.takeIf { !a.signedIn && a.expired }

    /** What stands between the viewer and a download from OpenSubtitles, before asking. */
    private fun downloadBlock(a: OnlineAccount): SubtitleNotice? = when {
        a.quotaUsed(now()) -> SubtitleNotice.QuotaUsed(a.resetAt.takeIf { it > 0 })
        a.anonQuotaUsed(now()) -> SubtitleNotice.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 })
        else -> null
    }

    // ---- download --------------------------------------------------------------------------

    /**
     * [hit] as a UTF-8 file the player can load. A file fetched before comes from the cache and
     * spends nothing; otherwise this is the call that uses one of the day's downloads.
     */
    suspend fun download(
        hit: SubtitleHit,
        target: SubtitleTarget? = null,
        // The file's arrival, for a page that shows its percent (owner, v1.0.18):
        // reported where the fetch itself is a plain read (SubDL, MSone's site);
        // a step that cannot say (a cached file, the browser pass) never calls it.
        onProgress: ((Long, Long) -> Unit)? = null,
    ): DownloadResult {
        val wanted = translateTarget(hit)
        val keptKey = episodeKey(hit, target)
        if (wanted == null) {
            cache.file(hit.provider, keptKey)?.let { return DownloadResult.Done(it, label(hit), fromCache = true) }
        } else {
            cache.file(hit.provider, translatedKey(hit.copy(id = keptKey), wanted))?.let {
                return DownloadResult.Done(it, translatedLabel(hit, wanted), fromCache = true)
            }
            // The subtitle itself is already here: translating it now spends no download.
            cache.file(hit.provider, keptKey)?.let { cached ->
                val translated = runCatching {
                    SubtitleText.Text(SubtitleText.extensionOf(cached.name), cached.readText())
                }.getOrNull()?.let { translator.translate(it, wanted) }
                val file = translated?.let {
                    runCatching { cache.putFile(hit.provider, translatedKey(hit.copy(id = keptKey), wanted), it) }.getOrNull()
                }
                if (file != null) return DownloadResult.Done(file, translatedLabel(hit, wanted), fromCache = true)
                return DownloadResult.Done(cached, label(hit), fromCache = true)
            }
        }
        return when (hit.provider) {
            SubtitleProvider.OpenSubtitles -> downloadOpenSubtitles(hit)
            SubtitleProvider.Subdl -> downloadSubdl(hit, target, onProgress)
            // A keyless build's MSone hits came from the public site, and are fetched from it;
            // a build with the key only ever holds gateway hits.
            SubtitleProvider.Msone -> if (msoneInBuild) downloadMsone(hit, target) else downloadMsoneSite(hit, target, onProgress)
        }
    }

    /**
     * Why [hit] cannot be downloaded right now, or null when it can: kept on this device already,
     * from SubDL, or from OpenSubtitles with downloads left (the account's, or the free ones
     * without one) and a key accepted.
     * The lists grey out what this names, and say why above them.
     */
    fun blockedFor(hit: SubtitleHit, a: OnlineAccount = store.now): SubtitleNotice? {
        // Kept here in either shape, the subtitle as it came or its translation: both download
        // for nothing, so neither is blocked by a quota or a refused key.
        val kept = cache.file(hit.provider, hit.id) != null ||
            translateTarget(hit)?.let { cache.file(hit.provider, translatedKey(hit, it)) != null } == true
        if (hit.provider == SubtitleProvider.Msone) {
            // The public site's hits answer to no gateway state: nothing blocks them but the fetch itself.
            if (!msoneInBuild) return null
            return SubtitleNotice.MsoneOff(a.msoneState, a.msone.message)
                .takeIf { a.msoneState != MsoneState.On && !kept }
        }
        if (hit.provider != SubtitleProvider.OpenSubtitles || kept) return null
        if (keyRefused(a)) return SubtitleNotice.Unavailable
        return downloadBlock(a)
    }

    private suspend fun downloadOpenSubtitles(hit: SubtitleHit): DownloadResult {
        val a = store.now
        if (keyRefused(a)) return DownloadResult.Failed(SubtitleNotice.Unavailable)
        if (!a.signedIn) return downloadAnonymously(hit, a)
        if (a.quotaUsed(now())) return DownloadResult.Failed(SubtitleNotice.QuotaUsed(a.resetAt.takeIf { it > 0 }))
        return when (val reply = withBackoff { api.download(hit.id, a.token, a.host) }) {
            is Reply.Ok -> {
                val link = reply.value
                store.update {
                    it.copy(
                        remaining = if (link.remaining >= 0) link.remaining else it.remaining,
                        resetAt = link.resetAt ?: it.resetAt,
                    )
                }
                when (val body = api.fetch(link.url)) {
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }, language = hit.language))
                    else -> DownloadResult.Failed(SubtitleNotice.Offline)
                }
            }
            is Reply.Quota -> {
                val reset = reply.resetAt ?: (now() + SubtitleCache.DAY_MS)
                store.update { it.copy(remaining = 0, resetAt = reset) }
                DownloadResult.Failed(SubtitleNotice.QuotaUsed(reset))
            }
            Reply.Unauthorized -> {
                store.update { it.copy(token = "", expired = true) }
                DownloadResult.Failed(SubtitleNotice.SignInExpired)
            }
            Reply.Forbidden -> {
                refuseKey()
                DownloadResult.Failed(SubtitleNotice.Unavailable)
            }
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            is Reply.Failed -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    /**
     * The same download with the app key alone, against the allowance OpenSubtitles gives without
     * an account. Its figures are kept apart from the account's, and a spent allowance says when it
     * renews and that signing in brings the viewer's own.
     */
    private suspend fun downloadAnonymously(hit: SubtitleHit, a: OnlineAccount): DownloadResult {
        if (a.anonQuotaUsed(now())) return DownloadResult.Failed(SubtitleNotice.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 }))
        return when (val reply = withBackoff { api.download(hit.id, null, OpenSubtitlesApi.DEFAULT_HOST) }) {
            is Reply.Ok -> {
                val link = reply.value
                store.update {
                    val remaining = if (link.remaining >= 0) link.remaining else it.anonRemaining
                    it.copy(
                        anonRemaining = remaining,
                        // A spent allowance with no time given is tried again a day later rather than never.
                        anonResetAt = link.resetAt ?: if (remaining == 0) now() + SubtitleCache.DAY_MS else it.anonResetAt,
                    )
                }
                when (val body = api.fetch(link.url)) {
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }, language = hit.language))
                    else -> DownloadResult.Failed(SubtitleNotice.Offline)
                }
            }
            is Reply.Quota -> {
                val reset = reply.resetAt ?: (now() + SubtitleCache.DAY_MS)
                store.update { it.copy(anonRemaining = 0, anonResetAt = reset) }
                DownloadResult.Failed(SubtitleNotice.FreeQuotaUsed(reset))
            }
            // OpenSubtitles asking for an account after all: signing in is the way through.
            Reply.Unauthorized -> DownloadResult.Failed(SubtitleNotice.SignInToDownload)
            Reply.Forbidden -> {
                refuseKey()
                DownloadResult.Failed(SubtitleNotice.Unavailable)
            }
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            is Reply.Failed -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    private suspend fun downloadSubdl(hit: SubtitleHit, target: SubtitleTarget?, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val parsed = target?.let { MediaName.parse(it.fileName, it.caption) }
        return when (val body = withBackoff { subdl.fetch(hit.id, onProgress) }) {
            is Reply.Ok -> save(
                hit,
                SubtitleText.from(body.value, hit.fileName, parsed?.season, parsed?.episode, hit.language, strict = parsed?.episode != null),
                episodeKey(hit, target),
            )
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            else -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    private suspend fun save(hit: SubtitleHit, text: SubtitleText.Text?, key: String = hit.id): DownloadResult {
        if (text == null) return DownloadResult.Failed(SubtitleNotice.Unreadable)
        // Translation sits here, on the way into the cache, so the translated file is what is
        // kept and what every platform reads, under a key of its own: the original stays cached
        // under the hit's key for the day translation is off or the language changes. A failed
        // translation keeps the original rather than costing the viewer the subtitle.
        val wanted = translateTarget(hit)
        val translated = if (wanted != null) translator.translate(text, wanted) else null
        if (translated != null) {
            // The original is kept under its own key as well, best effort, so turning
            // translation off does not spend another of the day's downloads fetching it again.
            runCatching { cache.putFile(hit.provider, key, text) }
        }
        val keptText = translated ?: text
        val keptKey = if (translated != null && wanted != null) translatedKey(hit.copy(id = key), wanted) else key
        // A full disk or a folder gone is a failed download (HX10), not an exception for the picker.
        val file = runCatching { cache.putFile(hit.provider, keptKey, keptText) }
            .onFailure { com.tmplayer.platform.Logger.w("OnlineSubtitles", "subtitle not saved: ${it.message}") }
            .getOrNull() ?: return DownloadResult.Failed(SubtitleNotice.Unreadable)
        // A file taken out of a pack stands under its own name (owner, v1.0.23:
        // the MSone file's name under the subtitles), not the pack's label.
        val name = if (translated != null && wanted != null) {
            translatedLabel(hit, wanted)
        } else {
            text.entryName?.takeIf { it.isNotBlank() } ?: label(hit)
        }
        return DownloadResult.Done(file, name, fromCache = false)
    }

    // ---- MSone -----------------------------------------------------------------------------

    /**
     * MSone's state at the gateway, asked at most every [MSONE_CHECK_MS] (or a minute after a
     * [force]d check, the "Check again" button). A check that fails changes nothing: the last
     * answer stays, so `stopped` is never lost to a bad connection, and a device that never had an
     * answer stays [MsoneState.Unknown]. Turning `stopped` deletes the MSone subtitles kept here;
     * `paused` keeps them for watching offline.
     */
    suspend fun msoneStatus(force: Boolean = false): MsoneStatus = msoneLock.withLock {
        val a = store.now
        val t = now()
        val age = t - a.msoneCheckedAt
        val due = a.msoneCheckedAt == 0L || age !in 0 until MSONE_CHECK_MS || (force && age !in 0 until MSONE_FORCE_MS)
        val failedRecently = t - msoneFailedAt in 0 until MSONE_FORCE_MS
        if (!due || failedRecently) return@withLock a.msone
        when (val answer = msone.health()) {
            is MsoneApi.Answer.Ok -> {
                msoneFailedAt = 0
                setMsone(answer.value.state, answer.value.message)
            }
            is MsoneApi.Answer.Failed -> msoneFailedAt = t
        }
        store.now.msone
    }

    /** Keeps what the gateway said, and acts on a stop. */
    private fun setMsone(state: MsoneState, message: String?) {
        store.update { it.copy(msoneState = state, msoneMessage = message.orEmpty(), msoneCheckedAt = now()) }
        if (state == MsoneState.Stopped) {
            runCatching { cache.purgeFiles(SubtitleProvider.Msone) }
                .onFailure { Logger.w(TAG, "MSone subtitles not deleted: ${it.message}") }
        }
    }

    /**
     * MSone's subtitles for [target], searched by its IMDb id when known, else by title, year,
     * season and episode. Only answers while the gateway says MSone is on; otherwise the
     * [SubtitleNotice.MsoneOff] the picker shows where the results would be, with the subtitles
     * already downloaded from a paused MSone still listed.
     */
    suspend fun searchMsone(target: SubtitleTarget): SearchResult {
        val status = msoneStatus()
        val parsed = MediaName.parse(target.fileName, target.caption)
        val query = msoneQuery(target)
        val key = msoneSearchKey(query)
        if (status.state != MsoneState.On) {
            val kept = if (status.state == MsoneState.Paused) {
                cache.search(key).orEmpty().filter { cache.file(it.provider, it.id) != null }
            } else {
                emptyList()
            }
            return SearchResult(kept, SubtitleNotice.MsoneOff(status.state, status.message), fromCache = kept.isNotEmpty())
        }
        if (query.imdb == null && query.tmdb == null && query.title == null) return SearchResult(emptyList(), SubtitleNotice.MsoneTooShort)
        cache.search(key)?.takeIf { it.isNotEmpty() }?.let { return SearchResult(rankMsone(it, parsed), fromCache = true) }
        return when (val answer = msone.search(query)) {
            is MsoneApi.Answer.Ok -> {
                // An empty answer is not kept: MSone's volunteers may finish this one tomorrow.
                if (answer.value.isNotEmpty()) cache.putSearch(key, answer.value)
                SearchResult(rankMsone(answer.value, parsed))
            }
            is MsoneApi.Answer.Failed -> SearchResult(emptyList(), msoneNotice(answer.failure))
        }
    }

    private fun msoneQuery(target: SubtitleTarget): MsoneApi.Query {
        val parsed = MediaName.parse(target.fileName, target.caption)
        return MsoneApi.Query(
            imdb = target.imdb,
            tmdb = target.tmdb,
            title = parsed.title.trim().takeIf { it.length >= MIN_QUERY },
            year = parsed.year,
            season = parsed.season,
            episode = parsed.episode,
        )
    }

    private fun msoneSearchKey(query: MsoneApi.Query): String =
        "msone|${query.imdb ?: query.tmdb?.let { "tmdb$it" } ?: query.title?.lowercase(Locale.ROOT)}|${query.year}|${query.season}|${query.episode}"

    /** The right episode first, then the right year, then the order MSone gave. */
    private fun rankMsone(hits: List<SubtitleHit>, parsed: ParsedName): List<SubtitleHit> =
        hits.sortedWith(
            compareByDescending<SubtitleHit> { parsed.episode != null && it.featureEpisode == parsed.episode && (it.featureSeason == null || it.featureSeason == parsed.season) }
                .thenByDescending { parsed.year != null && it.featureYear == parsed.year },
        )

    private fun msoneNotice(failure: MsoneApi.Failure): SubtitleNotice = when (failure) {
        is MsoneApi.Failure.State -> {
            setMsone(failure.state, failure.message)
            SubtitleNotice.MsoneOff(failure.state, failure.message)
        }
        MsoneApi.Failure.BadQuery -> SubtitleNotice.MsoneTooShort
        // Not kept as the state: the next /health says on again, and a newer build has a new key.
        MsoneApi.Failure.Refused -> SubtitleNotice.MsoneOff(MsoneState.Off)
        MsoneApi.Failure.Gone -> SubtitleNotice.MsoneGone
        is MsoneApi.Failure.Busy -> SubtitleNotice.MsoneBusy
        MsoneApi.Failure.Offline -> SubtitleNotice.Offline
    }

    /**
     * One MSone subtitle, a single file per video or episode from MSone's own link. The download
     * is what MSone counts, so it is made once and then kept on the device.
     */
    private suspend fun downloadMsone(hit: SubtitleHit, target: SubtitleTarget?): DownloadResult {
        val a = store.now
        if (a.msoneState != MsoneState.On) return DownloadResult.Failed(SubtitleNotice.MsoneOff(a.msoneState, a.msone.message))
        return when (val answer = msone.fetch(hit, SubtitleText.MAX_BYTES)) {
            is MsoneApi.Answer.Ok -> {
                // MSone's links end in a query (`?wpdmdl=`), so the name the gateway gave comes first.
                val name = hit.fileName.ifBlank { hit.link.substringBefore('?').substringAfterLast('/') }
                val text = SubtitleText.from(answer.value, name.ifBlank { "msone.srt" }, language = hit.language)
                save(hit, text, hit.id)
            }
            is MsoneApi.Answer.Failed -> {
                // A link MSone stopped honouring: forget the kept search, so the next one asks afresh.
                if (answer.failure == MsoneApi.Failure.Gone && target != null) cache.removeSearch(msoneSearchKey(msoneQuery(target)))
                DownloadResult.Failed(msoneNotice(answer.failure))
            }
        }
    }

    // ---- MSone, from its public site (builds without the gateway key) ------------------------

    /**
     * MSone's releases for [target], read from the site's own search page: this title's
     * own releases, best first (the name and year match, then the season's post for a
     * series, then the site's order), so the panel pick can stand in where the plain
     * match cannot. Searches are cached like the gateway's; an empty answer is not
     * kept, since the site's catalogue grows.
     */
    /**
     * The site's releases for [target], ranked with this title's match first. [terms]
     * is the viewer's own typed ask (owner, v1.0.17: the manual search on the MSone
     * page), for the videos whose own names read wrong; the ranking still follows the
     * video's parsed name, so the episode's release keeps its place at the head.
     */
    suspend fun searchMsoneSite(target: SubtitleTarget, terms: String? = null): SearchResult {
        val parsed = MediaName.parse(target.fileName, target.caption)
        val title = parsed.title.trim()
        val typed = terms?.trim()?.takeIf { it.isNotEmpty() }
        if (title.length < MIN_QUERY && typed == null) return SearchResult(emptyList(), SubtitleNotice.MsoneTooShort)
        val key = msoneSiteKey(parsed) + (typed?.let { "|q:${it.lowercase(Locale.ROOT)}" } ?: "")
        cache.search(key)?.takeIf { it.isNotEmpty() }?.let { return SearchResult(rankMsoneSite(it, parsed), fromCache = true) }
        val ask = typed ?: if (parsed.year != null) "$title ${parsed.year}" else title
        val found = msoneSite.search(ask) ?: return SearchResult(emptyList(), SubtitleNotice.Offline)
        // Only this title's own releases stand on the untyped ask (owner, v1.0.25):
        // the site's search answers with near names too, and another series'
        // release (Valhalla for Vikings) tapped here was remembered for the whole
        // series. A typed ask is the viewer's own naming for a wrongly read name,
        // so it keeps the site's full answer, as before.
        val releases = if (typed == null) found.filter { MsoneSite.sameTitle(it, title) } else found
        val hits = releases.map(::msoneSiteHit)
        if (hits.isNotEmpty()) cache.putSearch(key, hits)
        return SearchResult(rankMsoneSite(hits, parsed))
    }

    /**
     * The automatic half of item 10: the release [MsoneSite.match] settles on for [target],
     * downloaded and ready to apply as the track, or null when this build or device does not
     * offer the site, the search finds nothing, or no release is plainly this title. A matched
     * release that will not download answers with its failure, so the caller can simply let it
     * go: the panel pick remains the manual way to the same list.
     */
    suspend fun autoMsoneSite(target: SubtitleTarget): DownloadResult? {
        if (!msoneSiteShown) return null
        val parsed = MediaName.parse(target.fileName, target.caption)
        val title = parsed.title.trim()
        if (title.length < MIN_QUERY) return null
        val key = msoneSiteKey(parsed)
        val cached = cache.search(key)?.takeIf { it.isNotEmpty() }
        val hits = cached ?: run {
            val terms = if (parsed.year != null) "$title ${parsed.year}" else title
            val releases = msoneSite.search(terms) ?: return null
            releases.map(::msoneSiteHit).also { if (it.isNotEmpty()) cache.putSearch(key, it) }
        }
        val matched = MsoneSite.match(hits.map(::msoneSiteRelease), parsed.title, parsed.year, parsed.season, parsed.episode)
            ?: return null
        val hit = hits.firstOrNull { it.pageUrl == matched.pageUrl } ?: return null
        return download(hit, target)
    }

    private fun msoneSiteKey(parsed: ParsedName): String =
        "msonesite|${parsed.title.trim().lowercase(Locale.ROOT)}|${parsed.year}|${parsed.season}|${parsed.episode}"

    /** A site release as a hit: its page is both its id and its credit link; the file comes from the page. */
    private fun msoneSiteHit(release: MsoneRelease): SubtitleHit = SubtitleHit(
        provider = SubtitleProvider.Msone,
        id = release.pageUrl,
        language = "ml",
        release = buildString {
            append(release.title)
            release.year?.let { append(" ($it)") }
            if (release.series) release.season?.let { append(" S%02d".format(it)) }
        },
        featureTitle = release.title,
        featureYear = release.year,
        featureSeason = if (release.series) release.season else null,
        pageUrl = release.pageUrl,
    )

    /** A hit back as the release it came from, for matching: a kept season means a series post. */
    private fun msoneSiteRelease(hit: SubtitleHit): MsoneRelease = MsoneRelease(
        title = hit.featureTitle,
        year = hit.featureYear,
        series = hit.featureSeason != null,
        season = hit.featureSeason,
        pageUrl = hit.pageUrl.ifBlank { hit.id },
    )

    /** The match first, then the same title's other releases, then the site's order. */
    private fun rankMsoneSite(hits: List<SubtitleHit>, parsed: ParsedName): List<SubtitleHit> {
        val releases = hits.map(::msoneSiteRelease)
        val matched = MsoneSite.match(releases, parsed.title, parsed.year, parsed.season, parsed.episode)
        return hits.sortedWith(
            compareByDescending<SubtitleHit> { matched != null && it.pageUrl == matched.pageUrl }
                .thenByDescending { MsoneSite.sameTitle(msoneSiteRelease(it), parsed.title) },
        )
    }

    /**
     * One subtitle from the public site: the release page read for its download, through
     * [MsoneSiteApi.fetchSubtitle], and the bytes read as a subtitle (a zip is opened for the
     * episode's file inside). A page the site will not give the app (its bot gate) answers
     * [SubtitleNotice.Offline] like any other no answer; the panel keeps its Open row beside
     * the list, which is the fallback the owner was told about.
     */
    private suspend fun downloadMsoneSite(hit: SubtitleHit, target: SubtitleTarget?, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val parsed = target?.let { MediaName.parse(it.fileName, it.caption) }
        // Bounded as a whole (owner's phone, v1.0.17; the bound widened to the reading
        // and the saving too, v1.0.19): the plain read and the browser pass are bounded
        // step by step, but one after the other they left the panel sitting on
        // "Downloading" past a minute. Past the bound the answer is the honest failure,
        // whose page offers the release in the browser instead. The bound can land
        // because every read under it now ends by its own deadline (the transport's).
        return withTimeoutOrNull(MSONE_SITE_DOWNLOAD_MS) {
            val bytes = runCatching {
                msoneSite.fetchSubtitle(
                    hit.pageUrl.ifBlank { hit.id },
                    parsed?.season ?: hit.featureSeason,
                    parsed?.episode,
                    onProgress,
                )
            }.getOrNull() ?: return@withTimeoutOrNull DownloadResult.Failed(SubtitleNotice.Offline)
            val name = hit.pageUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "msone" } + ".srt"
            // Strict for an episode (v1.0.23): the site's season pack holds every
            // episode's file, and this episode's is the only one that may answer;
            // the first file in the pack is another episode's subtitle.
            val text = SubtitleText.from(bytes, name, parsed?.season ?: hit.featureSeason, parsed?.episode, language = hit.language, strict = parsed?.episode != null)
            save(hit, text, episodeKey(hit, target))
        } ?: DownloadResult.Failed(SubtitleNotice.Offline)
    }

    // ---- shared ----------------------------------------------------------------------------

    /**
     * [hits] without the ones that are plainly another video: an OpenSubtitles release that
     * shares not one word with the title. A hash match always stays, whatever it is called, and
     * so does SubDL, which is asked for a film by name and answers with releases of that film.
     *
     * A title with no Latin letters in it ("ഹാർബർ നോട്ട്സ്") cannot be compared with release
     * names, which are written in Latin letters, so nothing is dropped for one of those.
     */
    private fun relevant(hits: List<SubtitleHit>, parsed: ParsedName): List<SubtitleHit> {
        val title = titleWords(parsed.title)
        if (title.none { word -> word.any { it in 'a'..'z' } }) return hits
        return hits.filter { hit ->
            if (hit.hashMatch || hit.provider != SubtitleProvider.OpenSubtitles) return@filter true
            val theirs = releaseTitle(hit)
            if (hit.featureTitle.isBlank()) return@filter title.any { it in theirs }
            // OpenSubtitles' own filing: the show or film has to be this one, word for word, or
            // one name has to hold the other whole ("Thor & Loki Blood Brothers" stays, ranked
            // under "Loki"; "Entourage" goes, whatever its release group is called).
            title.any { it in theirs } && (theirs.containsAll(title) || title.containsAll(theirs))
        }
    }

    /**
     * The words of the title a hit is for. OpenSubtitles' own filing ([SubtitleHit.featureTitle])
     * when it gave one; otherwise the release name read the way a file name is ([MediaName.parse]),
     * what comes before its episode code or year, without a release group. "Entourage.S1E04.DVDRip-LOKi"
     * is Entourage, and "Season 1 whole (AC3.DVDRip.XviD-LOKi)" names no show at all.
     */
    private fun releaseTitle(hit: SubtitleHit): Set<String> {
        if (hit.featureTitle.isNotBlank()) return titleWords(hit.featureTitle)
        return buildSet {
            listOf(hit.release, hit.fileName).filter { it.isNotBlank() }.forEach {
                addAll(titleWords(MediaName.parse(it.replace(SUBTITLE_EXTENSION, "").replace(RELEASE_GROUP, ""), maxYear = 2099).title))
            }
        }
    }

    /**
     * How well [hit] fits the video, for the order of the list after hash matches. First the
     * release's own title ([releaseTitle]) against the video's: the share of the video's title
     * words it carries, then how little else it carries, so "Thor & Loki Blood Brothers" sits
     * under "Loki" when the video is Loki, and a title that is exactly the video's gets a bonus on
     * top. Then the right episode and the right year; a wrong one counts against it, so
     * "Show S01E03" sits under "Show S01E02" when the video is the second episode. The exact title
     * with the right episode outranks everything but a hash match.
     */
    private fun relevance(hit: SubtitleHit, parsed: ParsedName): Int {
        val title = titleWords(parsed.title)
        val release = hit.release.ifBlank { hit.fileName }
        val words = releaseTitle(hit)
        var score = 0
        if (title.isNotEmpty()) {
            val shared = title.count { it in words }
            score += 100 * shared / title.size
            score += 50 * shared / (title + words).size
            if (words == title) score += 40
        }
        val read = MediaName.parse(release, maxYear = 2099)
        // OpenSubtitles' own season, episode and year first, the release name's where it gave none.
        val theirs = read.copy(
            season = hit.featureSeason ?: read.season,
            episode = hit.featureEpisode ?: read.episode,
            year = hit.featureYear ?: read.year,
        )
        if (parsed.episode != null && theirs.episode != null) {
            val sameSeason = parsed.season == null || theirs.season == null || parsed.season == theirs.season
            // A wrong episode weighs more than the exact title, so it sits under a release that names no episode.
            score += if (sameSeason && parsed.episode == theirs.episode) 50 else -100
        }
        if (parsed.year != null && theirs.year != null) {
            score += if (parsed.year == theirs.year) 20 else -20
        }
        return score
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(NOT_WORD).filter { it.isNotEmpty() }.toSet()

    /** The title's words that say something: "the" and "of" are in every other release name. */
    private fun titleWords(title: String): Set<String> {
        val all = wordsOf(title)
        return (all - FILLER).ifEmpty { all }
    }

    /** The cache's size on the disk, for the Settings row. */
    fun cacheBytes(): Long = cache.bytes()

    /** Settings' "Clear online subtitles": every search and file kept. The account stays. */
    fun purge() = cache.purge()

    /** One retry after the wait a 429 asks for. */
    private suspend fun <T> withBackoff(call: suspend () -> Reply<T>): Reply<T> {
        val first = call()
        if (first !is Reply.Throttled) return first
        sleep(first.retryAfterMs)
        return call()
    }

    private fun refuseKey() {
        Logger.w(TAG, "OpenSubtitles refused the app key; online subtitles are off until it changes")
        store.update { it.copy(rejectedKey = keyPrint, rejectedAt = now()) }
    }

    companion object {
        private const val TAG = "OnlineSubtitles"

        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** A release group: the word after the last dash, at the end or before a closing bracket. */
        private val RELEASE_GROUP = Regex("""-[A-Za-z0-9]+(?=\s*(?:$|[)\]]))""")

        /** A subtitle file's own extension, off before the release group is looked for. */
        private val SUBTITLE_EXTENSION = Regex("""\.(srt|ass|ssa|vtt|sub|txt)$""", RegexOption.IGNORE_CASE)

        /** Words too common in release names to say that a release is this video. */
        private val FILLER = setOf("the", "a", "an", "of", "and", "in", "on", "to", "with")

        /** The shortest title OpenSubtitles accepts as a query. */
        internal const val MIN_QUERY = 3

        /** The whole of an MSone site download, plain read and browser pass together. */
        private const val MSONE_SITE_DOWNLOAD_MS = 50_000L

        /** What typed as the SubDL key shows or hides MSone's search instead. */
        private const val MSONE_CODE = "msone"

        /** How often MSone's state is asked at most, and how soon a manual "Check again" may ask again. */
        const val MSONE_CHECK_MS = 6 * 60 * 60 * 1000L
        const val MSONE_FORCE_MS = 60 * 1000L

        /** How long a refused key keeps the feature off before one more try. */
        const val REFUSED_RETRY_MS = SubtitleCache.DAY_MS

        /** The instance each app sets up at start, or null in a build or a test that has none. */
        @Volatile
        var current: OnlineSubtitles? = null

        /** Whether the feature exists in this build, for About and Settings. */
        val available: Boolean get() = current?.inBuild == true

        /** The track name a downloaded subtitle gets: its language and where it came from. */
        fun label(hit: SubtitleHit): String =
            L.onlineTrackLabel(languageName(hit.language), hit.provider.label)

        fun languageName(code: String): String =
            Translator.messages.formatter.trackLanguage(code) ?: code.ifBlank { L.onlineUnknownLanguage }

        /**
         * The languages to search in, best first: the one the viewer last chose subtitles in, then
         * the UI language, then English.
         */
        fun languagesFor(preferred: String?): List<String> =
            listOfNotNull(preferred, Translator.messages.tag, "en").mapNotNull(::iso1).distinct()

        /**
         * The search's languages with the viewer's preferred order leading (item 14,
         * v1.0.16), then the playing track's language, the app's and English: [search]
         * orders its hits by this list, so a top-language result stands first.
         */
        fun languagesInOrder(order: List<String>, preferred: String?): List<String> =
            (order + listOfNotNull(preferred, Translator.messages.tag, "en")).mapNotNull(::iso1).distinct()

        /** A track or UI language as OpenSubtitles' two letter code; Portuguese keeps its two kinds. */
        internal fun iso1(code: String?): String? {
            val raw = code?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            if (raw == "pt-br" || raw == "pob") return "pt-br"
            val base = raw.substringBefore('-')
            if (base in setOf("und", "mul", "zxx", "qaa", "xa")) return null
            return TrackCodes.toIso1(base) ?: base.takeIf { it.length == 2 }
        }

        /**
         * The OpenSubtitles hash of a Telegram file, read from the parts already on the disk.
         * Null unless both ends are there: asking TDLib for them would move the download the
         * player is streaming from, so a file not yet that far along is searched by name.
         */
        suspend fun hashFromTdlib(td: TdlClient, fileId: Int, size: Long): String? {
            if (!MovieHash.hashable(size)) return null
            val chunk = MovieHash.CHUNK.toLong()
            val tail = MovieHash.tailOffset(size)
            val headReady = td.getFileDownloadedPrefixSize(fileId, 0).valueOrNull?.size ?: 0
            val tailReady = td.getFileDownloadedPrefixSize(fileId, tail).valueOrNull?.size ?: 0
            if (headReady < chunk || tailReady < chunk) return null
            val head = td.readFilePart(fileId, 0, chunk).valueOrNull?.data ?: return null
            val end = td.readFilePart(fileId, tail, chunk).valueOrNull?.data ?: return null
            if (head.size < chunk || end.size < chunk) return null
            return MovieHash.of(size, head, end)
        }

        /** The hash of a local file, the same as [MovieHash.of]. */
        fun hashOf(file: File): String? = MovieHash.of(file)
    }
}

/** What Settings shows for the feature, from top to bottom of how much of it works. */
sealed interface OnlineStatus {
    data object NotInBuild : OnlineStatus
    data object Unavailable : OnlineStatus
    data object SignedOut : OnlineStatus
    data class Expired(val username: String) : OnlineStatus
    data class SignedIn(val username: String, val remaining: Int, val allowed: Int) : OnlineStatus
    data class QuotaUsed(val username: String, val resetAt: Long?) : OnlineStatus

    /** Not signed in, and the downloads allowed without an account are spent until [resetAt]. */
    data class FreeQuotaUsed(val resetAt: Long?) : OnlineStatus
}
