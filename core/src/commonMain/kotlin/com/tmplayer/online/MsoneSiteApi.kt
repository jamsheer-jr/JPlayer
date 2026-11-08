package com.tmplayer.online

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * MSone's public site as a client: the polite half of reading malayalamsubtitles.org directly,
 * for builds without the subtitle gateway's key. Searches go to the site's own search page
 * (`/?s=`), the plain WordPress search, because the REST route is firewalled; the page is read
 * by [MsoneSite]. Calls are spaced a second apart by the caller's limiter, and the caller
 * ([OnlineSubtitles]) caches every answer, so a title is searched once, not once per open.
 *
 * A failure to answer (no connection, a refused status, a page too big to be a page) is a null,
 * never an exception: the screens read that as MSone being unreachable right now.
 */
class MsoneSiteApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
    /**
     * The platform's browser pass for a release page whose download exists only once
     * its own script runs (item 5, v1.0.16); null where the platform has none (the
     * desktop, the tests). It is handed the release page's address and answers the
     * subtitle file's bytes, or null where the page would not give them up.
     */
    private val browserPass: (suspend (String) -> ByteArray?)? = null,
) {
    /**
     * The releases the site's search lists for [terms] (a title, with its year where known), in
     * the site's order. An empty list is a real answer (the site lists nothing for it); null is
     * no answer at all.
     */
    suspend fun search(terms: String): List<MsoneRelease>? {
        val query = terms.trim().take(MAX_QUERY).takeIf { it.isNotEmpty() } ?: return null
        val url = "${MsoneSite.BASE}/?s=${URLEncoder.encode(query, "UTF-8")}"
        val response = get(url) ?: return null
        if (response.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", url, response)
            return null
        }
        if (response.body.size > MAX_PAGE_BYTES) {
            ProviderLog.failed(PROVIDER, "GET", url, "${response.body.size} bytes, over $MAX_PAGE_BYTES")
            return null
        }
        return MsoneSite.parseSearch(response.text)
    }

    /**
     * The one download step, kept behind this narrow shape on purpose: a release page address
     * in, the subtitle file's bytes out (a `.srt`, or a `.zip` the caller opens with
     * [SubtitleText.from], which picks the episode's file inside), null when anything on the
     * way fails.
     *
     * Two ways, in order. The plain read first: the page is read for the address
     * [MsoneSite.downloadHref] trusts, and that address is fetched. The site's release pages
     * carry no plain download link in an href; the address stands in the download button's
     * click code as a download manager package, and the reader takes it from there (found
     * 2026-11-08 from the page's own HTML, after downloads on a phone never started). Then,
     * where the platform offers one, the browser pass (item 5, v1.0.16) stays as the second
     * way: it loads the page in a WebView, triggers the control and fetches what it asks
     * for; its bytes are checked the same way before they are believed.
     */
    suspend fun fetchSubtitle(pageUrl: String, season: Int?, episode: Int?, onProgress: ((Long, Long) -> Unit)? = null): ByteArray? {
        if (!releasePage(pageUrl)) return null
        plainFetch(pageUrl, season, episode, onProgress)?.let { return it }
        // The browser pass fetches inside a WebView, whose arrival no progress is
        // read from: the page keeps its plain "Downloading" there (owner, v1.0.18).
        val browsed = browserPass?.invoke(pageUrl) ?: return null
        if (browsed.isEmpty() || browsed.size > SubtitleText.MAX_BYTES) return null
        return browsed
    }

    /** The plain read of [fetchSubtitle]: the page's trusted anchor, fetched. */
    private suspend fun plainFetch(pageUrl: String, season: Int?, episode: Int?, onProgress: ((Long, Long) -> Unit)? = null): ByteArray? {
        val page = get(pageUrl) ?: return null
        if (page.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", pageUrl, page)
            return null
        }
        if (page.body.size > MAX_PAGE_BYTES) return null
        val href = MsoneSite.downloadHref(page.text, pageUrl, season, episode) ?: return null
        val file = get(href, onProgress) ?: return null
        if (file.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", href, file)
            return null
        }
        if (file.body.isEmpty() || file.body.size > SubtitleText.MAX_BYTES) return null
        return file.body
    }

    private suspend fun get(url: String, onProgress: ((Long, Long) -> Unit)? = null): HttpResponse? {
        limiter.acquire()
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml",
        )
        return http.sendLogged(PROVIDER, HttpRequest("GET", url, headers, onProgress = onProgress))
    }

    /** A release page address on the site itself: https, its host, under `/languages/`. */
    private fun releasePage(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host == MsoneSite.HOST || host.endsWith(".${MsoneSite.HOST}")) &&
            uri.path.orEmpty().startsWith("/languages/")
    }

    private companion object {
        const val PROVIDER = "MSone site"

        /** The longest search sent; the site caps nothing, but a file name is not a query. */
        const val MAX_QUERY = 120

        /** A search or release page is a few hundred KB; anything past this is not one. */
        const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    }
}
