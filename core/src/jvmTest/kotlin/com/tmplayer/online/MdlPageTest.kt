package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MyDramaList's pages, read as text: the top result off a search page, and the facts off a
 * title page (OpenGraph first, JSON-LD next, the details list last). Saved shapes only;
 * nothing here reaches the internet.
 */
class MdlPageTest {

    private val searchPage = """
        <html><head><title>Search results for Hidden Moon - MyDramaList</title></head><body>
        <nav><a href="/99999-trending-now">Trending</a><a href="/people/55-some-actor">An Actor</a></nav>
        <h1>Search results for Hidden Moon</h1>
        <div class="box"><h6 class="text-primary title"><a href="/70001-hidden-moon">Hidden Moon</a></h6>
        <span class="text-muted">Chinese Drama - 2023, 24 episodes</span></div>
        <div class="box"><h6 class="text-primary title"><a href="/70002-hidden-moon-special">Hidden Moon Special</a></h6>
        <span class="text-muted">Chinese Special - 2023</span></div>
        </body></html>
    """.trimIndent()

    private val titlePage = """
        <html><head>
        <meta charset="utf-8">
        <meta property="og:title" content="Hidden Moon (2023)">
        <meta property="og:image" content="https://i.mydramalist.com/abc123_4f.jpg">
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"TVSeries","name":"Hidden Moon",
        "startDate":"2023-05-01","genre":["Historical","Romance","Fantasy"]}</script>
        </head><body><h1>Hidden Moon</h1></body></html>
    """.trimIndent()

    /** No JSON-LD: the year, the genres and the kind all come from the details list. */
    private val moviePage = """
        <html><head>
        <title>Moon River Movie (2019) - MyDramaList</title>
        <meta content="Moon River Movie (2019)" property="og:title">
        <meta content="https://i.mydramalist.com/xyz987_4f.jpg" property="og:image">
        </head><body>
        <div class="box-body light-b">
        <div class="row"><div class="col-sm-3"><b>Movie:</b></div><div class="col-sm-9">Moon River Movie</div></div>
        <div class="row"><div class="col-sm-3"><b>Release Date:</b></div><div class="col-sm-9">Mar 3, 2019</div></div>
        <div class="row"><div class="col-sm-3"><b>Genres:</b></div><div class="col-sm-9"><a href="/search?genres=35">Comedy</a>, <a href="/search?genres=18">Drama</a></div></div>
        <div class="row"><div class="col-sm-3"><b>Tags:</b></div><div class="col-sm-9"><a href="/search?tags=1">River</a></div></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun theTopResultIsTheFirstTitleAfterTheHeading() {
        assertEquals("/70001-hidden-moon", MdlPage.topResultPath(searchPage))
    }

    @Test
    fun aSearchPageWithNoTitleResultYieldsNothing() {
        assertNull(MdlPage.topResultPath("<html><body><h1>Search results</h1><p>Nothing found.</p></body></html>"))
        assertNull(MdlPage.topResultPath(""))
        assertNull(MdlPage.topResultPath("<a href=\"/people/9-x\">A Person</a>"))
    }

    @Test
    fun theFactsComeFromOpenGraphAndJsonLd() {
        val facts = MdlPage.facts(titlePage, "/70001-hidden-moon") ?: error("no facts")
        assertEquals("Hidden Moon", facts.title)
        assertEquals("https://i.mydramalist.com/abc123_4f.jpg", facts.posterUrl)
        assertEquals(2023, facts.year)
        assertEquals(listOf("Historical", "Romance", "Fantasy"), facts.genres)
        assertEquals(MetaKind.Show, facts.kind)
        assertEquals("/70001-hidden-moon", facts.path)
    }

    @Test
    fun theDetailsListFillsInWhenThereIsNoJsonLd() {
        val facts = MdlPage.facts(moviePage, "/70003-moon-river-movie") ?: error("no facts")
        assertEquals("Moon River Movie", facts.title)
        assertEquals("https://i.mydramalist.com/xyz987_4f.jpg", facts.posterUrl)
        assertEquals(2019, facts.year)
        assertEquals(listOf("Comedy", "Drama"), facts.genres)
        assertEquals(MetaKind.Film, facts.kind)
    }

    @Test
    fun aPageWithNoPosterOrNoTitleYieldsNothing() {
        val noPoster = titlePage.replace("""<meta property="og:image" content="https://i.mydramalist.com/abc123_4f.jpg">""", "")
        assertNull(MdlPage.facts(noPoster, "/70001-hidden-moon"))
        val noTitle = titlePage.replace("""<meta property="og:title" content="Hidden Moon (2023)">""", "")
            .replace(""""name":"Hidden Moon",""", "")
        assertNull("the heading is not a title source", MdlPage.facts(noTitle, "/70001-hidden-moon"))
        assertNull(MdlPage.facts(titlePage, "/not-a-title-path"))
        assertNull(MdlPage.facts("", "/70001-hidden-moon"))
    }

    @Test
    fun truncatedAndTagSoupPagesNeverThrowAndYieldNothing() {
        val broken = listOf(
            "<meta property=\"og:title\" content=\"Half",
            "<meta property=\"og:image\"",
            "{\"@type\":\"TVSeries\"",
            "<script type=\"application/ld+json\">{broken json</script>",
            "<b>Genres:</b><a href=",
            "<".repeat(500) + ">".repeat(500),
            titlePage.substring(0, titlePage.length / 3),
        )
        for (page in broken) {
            MdlPage.topResultPath(page)
            MdlPage.facts(page, "/70001-hidden-moon")
        }
        assertNull(MdlPage.topResultPath(broken[4]))
    }
}
