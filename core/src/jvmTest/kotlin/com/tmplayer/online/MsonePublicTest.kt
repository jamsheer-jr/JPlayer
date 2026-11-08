package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MSone read from its public site, in a build without the gateway key: the search page turned
 * into the picker's list, the answers cached, and the automatic flow that downloads the name
 * and year match (episode wise for a series) ready to apply. Against a fake HTTP layer serving
 * pages in the site's own shape, as recorded on 2026-11-08; the download step's anchor shape is
 * the one [MsoneSite.downloadHref] reads for, unverified against the live site, whose release
 * pages are bot gated from the build machine.
 */
class MsonePublicTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        private val routes = mutableMapOf<String, ArrayDeque<() -> HttpResponse>>()

        fun on(pathPart: String, vararg responses: HttpResponse) {
            routes.getOrPut(pathPart) { ArrayDeque() }.addAll(responses.map { r -> { r } })
        }

        fun fail(pathPart: String) {
            routes.getOrPut(pathPart) { ArrayDeque() }.add { throw java.io.IOException("offline") }
        }

        fun count(pathPart: String) = requests.count { pathPart in it.url }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val route = routes.entries.firstOrNull { request.url.contains(it.key) }
                ?: error("unexpected request ${request.method} ${request.url}")
            return (route.value.removeFirstOrNull() ?: error("no answer left for ${route.key}"))()
        }
    }

    private var clock = 1_800_000_000_000L
    private val dir: File = Files.createTempDirectory("tm-msone-site").toFile()
    private val http = FakeHttp()
    private val store = OnlineSubtitlesStore(File(dir, "online.properties"))
    private val cache = SubtitleCache(File(dir, "cache"), now = { clock })

    /** A keyless build: an OpenSubtitles key and no gateway key, the J Player shape. */
    private val online = OnlineSubtitles("", store, cache, "1.0.0", http, now = { clock }, sleep = { clock += it })

    private fun html(code: Int, body: String) = HttpResponse(code, emptyMap(), body.toByteArray())

    private fun card(post: Int, kind: String, slug: String, language: String, heading: String): String = """
        <article class="entry content-bg loop-entry post-$post post type-post hentry release-type-$kind">
            <a class="post-thumbnail" href="https://malayalamsubtitles.org/languages/$language/$slug/"></a>
            <div class="entry-content-wrap"><header class="entry-header">
            <h2 class="entry-title"><a href="https://malayalamsubtitles.org/languages/$language/$slug/" rel="bookmark">$heading</a></h2>
            </header></div>
        </article>
    """.trimIndent()

    private val filmsPage = """
        <html><body><div id="archive-container">
        ${card(11, "movie", "resident-evil-2026", "english", "Resident Evil / റെസിഡന്റ് ഈവിൾ (2026)")}
        ${card(12, "movie", "resident-evil-afterlife-2010", "english", "Resident Evil: Afterlife (2010)")}
        </div></body></html>
    """.trimIndent()

    private val seriesPage = """
        <html><body><div id="archive-container">
        ${card(21, "series", "squid-game-2021", "korean", "Squid Game / സ്ക്വിഡ് ഗെയിം (2021)")}
        ${card(22, "series", "squid-game-season-02", "korean", "Squid Game / സ്ക്വിഡ് ഗെയിം (2021)")}
        </div></body></html>
    """.trimIndent()

    private fun srt(text: String) = "1\n00:00:01,000 --> 00:00:02,000\n$text\n"

    private val film = SubtitleTarget(fileName = "Resident.Evil.2026.1080p.WEB-DL.mkv", sizeBytes = 1_000_000)
    private val episode = SubtitleTarget(fileName = "Squid.Game.S02E03.2025.1080p.WEB.mkv", sizeBytes = 1_000_000)

    // ---- the surface ---------------------------------------------------------------------------

    @Test
    fun `a keyless build offers the site, a keyed build does not`() {
        assertTrue(online.msoneSiteShown)
        assertFalse(online.msoneShown)
        val keyed = OnlineSubtitles("", store, cache, "1.0.0", http, now = { clock }, sleep = { clock += it }, msoneKey = "gateway-key")
        assertFalse(keyed.msoneSiteShown)
        assertTrue(keyed.msoneShown)
    }

    @Test
    fun `hidden on this device hides the site too`() = runBlocking {
        assertEquals(false, online.setSubdlKey("msone"))
        assertFalse(online.msoneSiteShown)
        assertNull(online.autoMsoneSite(film))
        assertEquals(0, http.requests.size)
    }

    // ---- search ----------------------------------------------------------------------------------

    @Test
    fun `the search page becomes the picker's list, the match first`() = runBlocking {
        http.on("?s=", html(200, filmsPage))
        val result = online.searchMsoneSite(film)
        assertEquals(2, result.hits.size)
        val first = result.hits[0]
        assertEquals(SubtitleProvider.Msone, first.provider)
        assertEquals("https://malayalamsubtitles.org/languages/english/resident-evil-2026/", first.pageUrl)
        assertEquals(2026, first.featureYear)
        assertNull(result.notice)
    }

    @Test
    fun `a search is asked once and then read from the cache`() = runBlocking {
        http.on("?s=", html(200, filmsPage))
        online.searchMsoneSite(film)
        val again = online.searchMsoneSite(film)
        assertTrue(again.fromCache)
        assertEquals(1, http.count("?s="))
    }

    @Test
    fun `no answer from the site is an offline notice, not a failure`() = runBlocking {
        http.fail("?s=")
        val result = online.searchMsoneSite(film)
        assertTrue(result.hits.isEmpty())
        assertEquals(SubtitleNotice.Offline, result.notice)
    }

    @Test
    fun `a name too short to search says so without asking`() = runBlocking {
        val result = online.searchMsoneSite(SubtitleTarget(fileName = "a.mkv", sizeBytes = 1))
        assertEquals(SubtitleNotice.MsoneTooShort, result.notice)
        assertEquals(0, http.requests.size)
    }

    // ---- the automatic flow -----------------------------------------------------------------------

    @Test
    fun `on open the matching release downloads itself`() = runBlocking {
        http.on("?s=", html(200, filmsPage))
        http.on(
            "/languages/",
            html(200, """<a href="https://malayalamsubtitles.org/files/Resident-Evil-2026.srt">Download</a>"""),
        )
        http.on("/files/", HttpResponse(200, emptyMap(), srt("malayalam line").toByteArray()))
        val done = online.autoMsoneSite(film)
        assertTrue(done is DownloadResult.Done)
        val file = (done as DownloadResult.Done).file
        assertTrue(file.readText().contains("malayalam line"))
        assertEquals(1, http.count("/languages/"))
        // A second open spends nothing: the file is kept.
        val again = online.autoMsoneSite(film)
        assertTrue(again is DownloadResult.Done && again.fromCache)
        assertEquals(1, http.count("/files/"))
    }

    @Test
    fun `an episode takes its own season's post and its own file inside it`() = runBlocking {
        http.on("?s=", html(200, seriesPage))
        http.on(
            "/languages/",
            html(
                200,
                """
                <a href="/files/Squid.Game.S02E01.srt">E1</a>
                <a href="/files/Squid.Game.S02E02.srt">E2</a>
                <a href="/files/Squid.Game.S02E03.srt">E3</a>
                """.trimIndent(),
            ),
        )
        http.on("/files/", HttpResponse(200, emptyMap(), srt("episode three").toByteArray()))
        val done = online.autoMsoneSite(episode)
        assertTrue(done is DownloadResult.Done)
        assertTrue((done as DownloadResult.Done).file.readText().contains("episode three"))
        assertEquals(1, http.count("squid-game-season-02"))
        // Exactly one file was fetched: the third episode's own, not the pack in page order.
        assertEquals(1, http.count("/files/"))
        assertTrue(http.requests.any { "S02E03" in it.url })
    }

    @Test
    fun `no plain match downloads nothing`() = runBlocking {
        http.on("?s=", html(200, filmsPage))
        val other = SubtitleTarget(fileName = "Jailer.2023.1080p.mkv", sizeBytes = 1_000_000)
        assertNull(online.autoMsoneSite(other))
        assertEquals(0, http.count("/languages/"))
    }

    @Test
    fun `a release page the site will not give answers as a failed download`() = runBlocking {
        http.on("?s=", html(200, filmsPage))
        val hit = online.searchMsoneSite(film).hits[0]
        assertNotNull(hit)
        // The bot gate's answer, as recorded from the build machine: the download fails honestly
        // and the file pick in the panel is the fallback.
        http.on("/languages/", html(403, "<title>Just a moment...</title>"))
        val done = online.download(hit, film)
        assertEquals(DownloadResult.Failed(SubtitleNotice.Offline), done)
    }
}
