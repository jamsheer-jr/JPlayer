package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The MyDramaList fallback's place in a lookup, over a fake network: asked only when TMDB
 * has nothing, never when TMDB has a match, its facts kept in the same cache, and a miss on
 * both sides still a plain no match. Nothing here reaches the internet.
 */
class MdlMetadataTest {

    /** Answers by the first rule whose text the URL contains; records every request. */
    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Pair<String, () -> Pair<Int, String>>>()

        fun on(part: String, code: Int = 200, body: () -> String) {
            rules += part to { code to body() }
        }

        fun count(part: String) = requests.count { part in it.url }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val rule = rules.firstOrNull { it.first in request.url } ?: return HttpResponse(404, body = "{}".toByteArray())
            val (code, body) = rule.second()
            return HttpResponse(code, body = body.toByteArray())
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-mdl").toFile()

    private fun metadata(): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        return OnlineMetadata(
            buildKey = "build-key",
            store = store,
            cache = MetadataCache(File(dir, "cache"), now = { clock }),
            appVersion = "test",
            http = http,
            now = { clock },
            sleep = { clock += it },
            language = { "en" },
        )
    }

    private fun query(name: String) = MetaQuery.of(name) ?: error("no query for $name")

    private val mdlSearch = """
        <html><body><h1>Search results for Hidden Moon</h1>
        <div class="box"><h6 class="text-primary title"><a href="/70001-hidden-moon">Hidden Moon</a></h6>
        <span class="text-muted">Chinese Drama - 2023, 24 episodes</span></div>
        </body></html>
    """.trimIndent()

    private val mdlPage = """
        <html><head>
        <meta property="og:title" content="Hidden Moon (2023)">
        <meta property="og:image" content="https://i.mydramalist.com/abc123_4f.jpg">
        <script type="application/ld+json">{"@type":"TVSeries","name":"Hidden Moon","startDate":"2023-05-01","genre":["Historical","Romance"]}</script>
        </head><body></body></html>
    """.trimIndent()

    /** TMDB answers nothing, for films and for shows, with and without a year. */
    private fun tmdbHasNothing() {
        http.on("search/movie") { """{"results":[]}""" }
        http.on("search/tv") { """{"results":[]}""" }
    }

    private fun mdlHasHiddenMoon() {
        http.on("mydramalist.com/search") { mdlSearch }
        http.on("/70001-hidden-moon") { mdlPage }
    }

    @Test
    fun aTmdbMissFallsBackToMyDramaList() = runBlocking {
        tmdbHasNothing()
        mdlHasHiddenMoon()
        val online = metadata()
        val info = (online.lookup(query("Hidden.Moon.2023.1080p.WEB-DL.mkv")) as MetaResult.Found).info
        assertEquals(MetaProvider.Mdl, info.provider)
        assertEquals("/70001-hidden-moon", info.id)
        assertEquals(MetaKind.Show, info.kind)
        assertEquals("Hidden Moon", info.title)
        assertEquals(2023, info.year)
        assertEquals("https://i.mydramalist.com/abc123_4f.jpg", info.posterUrl)
        assertEquals("one search fetch and one page fetch", 2, http.count("mydramalist.com"))
        // Kept like a TMDB match: the second look asks nobody again.
        val asked = http.requests.size
        assertTrue(online.lookup(query("Hidden.Moon.2023.1080p.WEB-DL.mkv")) is MetaResult.Found)
        assertEquals(asked, http.requests.size)
    }

    @Test
    fun aTmdbHitMeansMyDramaListIsNeverFetched() = runBlocking {
        http.on("search/movie") {
            """{"results":[{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny",
            "release_date":"2008-04-10","overview":"A giant rabbit has had enough.","poster_path":"/bbb.jpg"}]}"""
        }
        mdlHasHiddenMoon()
        val info = (metadata().lookup(query("Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv")) as MetaResult.Found).info
        assertEquals(MetaProvider.Tmdb, info.provider)
        assertEquals(0, http.count("mydramalist.com"))
    }

    @Test
    fun aMissOnBothSidesIsAPlainNoMatch() = runBlocking {
        tmdbHasNothing()
        http.on("mydramalist.com/search") { "<html><body><h1>Search results</h1><p>Nothing found.</p></body></html>" }
        val online = metadata()
        assertEquals(MetaResult.NoMatch, online.lookup(query("Hidden.Moon.2023.1080p.mkv")))
        assertEquals(1, http.count("mydramalist.com"))
    }

    @Test
    fun aBlockedMyDramaListIsAlsoAPlainNoMatch() = runBlocking {
        tmdbHasNothing()
        http.on("mydramalist.com", code = 403) { "blocked" }
        assertEquals(MetaResult.NoMatch, metadata().lookup(query("Hidden.Moon.2023.1080p.mkv")))
    }

    @Test
    fun aLikeNamedResultOfTheWrongYearIsNotTaken() = runBlocking {
        tmdbHasNothing()
        mdlHasHiddenMoon()
        // The file says 2019; the page's drama is 2023. Same words are not enough for a film.
        assertEquals(MetaResult.NoMatch, metadata().lookup(query("Hidden.Moon.2019.1080p.mkv")))
    }

    @Test
    fun anEpisodeOfAnMdlShowStandsOnTheShowsOwnWords() = runBlocking {
        tmdbHasNothing()
        mdlHasHiddenMoon()
        val info = (metadata().lookup(query("Hidden.Moon.S01E02.1080p.mkv")) as MetaResult.Found).info
        assertEquals(MetaProvider.Mdl, info.provider)
        assertEquals(MetaKind.Show, info.kind)
        assertEquals(null, info.episode)
    }

    @Test
    fun theGenresReachTheDetailPagesExtras() = runBlocking {
        tmdbHasNothing()
        mdlHasHiddenMoon()
        val online = metadata()
        val info = (online.lookup(query("Hidden.Moon.2023.1080p.WEB-DL.mkv")) as MetaResult.Found).info
        val extras = online.extras(info) ?: error("no extras")
        assertEquals(listOf("Historical", "Romance"), extras.genres)
        assertEquals(2023, extras.year)
    }
}
