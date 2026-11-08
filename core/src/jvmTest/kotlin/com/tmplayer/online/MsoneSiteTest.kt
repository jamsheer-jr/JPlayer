package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSone's public site read as pages: the search results grid into releases, the owner's simple
 * name and year match (episode wise for a series), and the download anchor read off a release
 * page. The card markup is the site's own shape, as served on 2026-11-08.
 */
class MsoneSiteTest {

    private fun card(post: Int, kind: String, slug: String, language: String, heading: String): String = """
        <article class="entry content-bg loop-entry post-$post post type-post status-publish format-standard has-post-thumbnail hentry category-$language certificates-rated-r genres-action release-type-$kind">
            <a class="post-thumbnail" href="https://malayalamsubtitles.org/languages/$language/$slug/">
                <div class="post-thumbnail-inner"><img src="https://malayalamsubtitles.org/wp-content/uploads/poster.jpg" alt="$heading" /></div>
            </a>
            <div class="entry-content-wrap">
                <header class="entry-header">
                    <h2 class="entry-title"><a href="https://malayalamsubtitles.org/languages/$language/$slug/" rel="bookmark">$heading</a></h2>
                </header>
            </div>
        </article>
    """.trimIndent()

    private val searchPage = """
        <html><body>
        <header><article class="entry loop-entry post-1 release-type-movie">
            <h2 class="entry-title"><a href="https://malayalamsubtitles.org/languages/english/furniture-1999/">Furniture (1999)</a></h2>
        </article></header>
        <div id="archive-container">
        ${card(53742, "movie", "resident-evil-2026", "english", "Resident Evil / റെസിഡന്റ് ഈവിൾ (2026)")}
        ${card(13361, "movie", "resident-evil-retribution-2012", "english", "Resident Evil: Retribution / റെസിഡന്റ്: ഈവിൾ റെട്രിബ്യുഷൻ (2012)")}
        ${card(2599, "movie", "resident-evil-afterlife-2010", "english", "Resident Evil: Afterlife (2010)")}
        </div>
        </body></html>
    """.trimIndent()

    private val seriesPage = """
        <html><body>
        <div id="archive-container">
        ${card(9001, "series", "squid-game-2021", "korean", "Squid Game / സ്ക്വിഡ് ഗെയിം (2021)")}
        ${card(9002, "series", "squid-game-season-02", "korean", "Squid Game / സ്ക്വിഡ് ഗെയിം (2021)")}
        ${card(9003, "series", "squid-game-season-03", "korean", "Squid Game / സ്ക്വിഡ് ഗെയിം (2021)")}
        </div>
        </body></html>
    """.trimIndent()

    // ---- the search page ---------------------------------------------------------------------

    @Test
    fun `the grid reads into releases with title year and kind`() {
        val releases = MsoneSite.parseSearch(searchPage)
        assertEquals(3, releases.size)
        val first = releases[0]
        assertEquals("Resident Evil", first.title)
        assertEquals(2026, first.year)
        assertEquals(false, first.series)
        assertNull(first.season)
        assertEquals("https://malayalamsubtitles.org/languages/english/resident-evil-2026/", first.pageUrl)
        assertEquals("Resident Evil: Retribution", releases[1].title)
        assertEquals(2012, releases[1].year)
    }

    @Test
    fun `cards outside the grid are furniture, not results`() {
        assertTrue(MsoneSite.parseSearch(searchPage).none { it.title == "Furniture" })
    }

    @Test
    fun `a series is one release per season, the first post season one`() {
        val releases = MsoneSite.parseSearch(seriesPage)
        assertEquals(3, releases.size)
        assertTrue(releases.all { it.series })
        assertEquals(listOf(1, 2, 3), releases.map { it.season })
        assertEquals(2021, releases[0].year)
    }

    @Test
    fun `a card with no heading falls back to its slug`() {
        val page = """
            <div id="archive-container">
            <article class="entry loop-entry release-type-movie">
                <a class="post-thumbnail" href="https://malayalamsubtitles.org/languages/english/the-assassin-2015/"></a>
            </article>
            </div>
        """.trimIndent()
        val releases = MsoneSite.parseSearch(page)
        assertEquals(1, releases.size)
        assertEquals("the assassin", releases[0].title)
        assertEquals(2015, releases[0].year)
    }

    @Test
    fun `cards that are not releases or point off the site are skipped`() {
        val page = """
            <div id="archive-container">
            <article class="entry loop-entry">
                <h2 class="entry-title"><a href="https://malayalamsubtitles.org/languages/english/no-kind-2020/">No Kind (2020)</a></h2>
            </article>
            <article class="entry loop-entry release-type-movie">
                <h2 class="entry-title"><a href="https://elsewhere.example/languages/english/off-site-2020/">Off Site (2020)</a></h2>
            </article>
            <article class="entry loop-entry release-type-movie">
                <h2 class="entry-title"><a href="http://malayalamsubtitles.org/languages/english/plain-http-2020/">Plain Http (2020)</a></h2>
            </article>
            </div>
        """.trimIndent()
        assertTrue(MsoneSite.parseSearch(page).isEmpty())
    }

    @Test
    fun `an empty or results free page reads as no releases`() {
        assertTrue(MsoneSite.parseSearch("").isEmpty())
        assertTrue(MsoneSite.parseSearch("<html><body>Sorry, but nothing matched your search terms</body></html>").isEmpty())
    }

    // ---- the match ---------------------------------------------------------------------------

    @Test
    fun `a film matches by name and year`() {
        val releases = MsoneSite.parseSearch(searchPage)
        val matched = MsoneSite.match(releases, "Resident Evil", 2026, null, null)
        assertEquals("https://malayalamsubtitles.org/languages/english/resident-evil-2026/", matched?.pageUrl)
    }

    @Test
    fun `a wrong year or an unknown title matches nothing`() {
        val releases = MsoneSite.parseSearch(searchPage)
        assertNull(MsoneSite.match(releases, "Resident Evil", 1999, null, null))
        assertNull(MsoneSite.match(releases, "Jailer", 2023, null, null))
        assertNull(MsoneSite.match(releases, "", null, null, null))
    }

    @Test
    fun `name matching shrugs off case and punctuation`() {
        val releases = MsoneSite.parseSearch(searchPage)
        assertEquals(2010, MsoneSite.match(releases, "resident evil:  afterlife", 2010, null, null)?.year)
    }

    @Test
    fun `an episode matches its season's post, episode wise`() {
        val releases = MsoneSite.parseSearch(seriesPage)
        assertEquals(1, MsoneSite.match(releases, "Squid Game", 2021, 1, 4)?.season)
        assertEquals(2, MsoneSite.match(releases, "Squid Game", 2021, 2, 1)?.season)
        assertEquals(3, MsoneSite.match(releases, "Squid Game", 2024, 3, 7)?.season)
        assertNull("no fourth season post", MsoneSite.match(releases, "Squid Game", 2021, 4, 1))
    }

    @Test
    fun `a film never matches a series post, nor an episode a film`() {
        val series = MsoneSite.parseSearch(seriesPage)
        assertNull(MsoneSite.match(series, "Squid Game", 2021, null, null))
        val films = MsoneSite.parseSearch(searchPage)
        assertNull(MsoneSite.match(films, "Resident Evil", 2026, 1, 1))
    }

    // ---- the release page ----------------------------------------------------------------------

    private val releaseUrl = "https://malayalamsubtitles.org/languages/english/resident-evil-2026/"

    @Test
    fun `a direct srt anchor on the release page is the download`() {
        val page = """
            <html><body>
            <a href="https://malayalamsubtitles.org/about/">About</a>
            <a href="https://malayalamsubtitles.org/wp-content/uploads/2026/10/Resident-Evil-2026.srt">Download subtitle</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://malayalamsubtitles.org/wp-content/uploads/2026/10/Resident-Evil-2026.srt",
            MsoneSite.downloadHref(page, releaseUrl, null, null),
        )
    }

    @Test
    fun `a zip is taken when there is no direct file, and a file beats a zip`() {
        val zipOnly = """<a href="/wp-content/uploads/2026/10/resident-evil-2026.zip">Download</a>"""
        assertEquals(
            "https://malayalamsubtitles.org/wp-content/uploads/2026/10/resident-evil-2026.zip",
            MsoneSite.downloadHref(zipOnly, releaseUrl, null, null),
        )
        val both = """
            <a href="/files/pack.zip">Pack</a>
            <a href="/files/one.srt">One</a>
        """.trimIndent()
        assertEquals("https://malayalamsubtitles.org/files/one.srt", MsoneSite.downloadHref(both, releaseUrl, null, null))
    }

    @Test
    fun `among several files the one numbering the episode wins`() {
        val page = """
            <a href="/files/Squid.Game.S02E01.srt">E1</a>
            <a href="/files/Squid.Game.S02E02.srt">E2</a>
            <a href="/files/Squid.Game.S02E03.srt">E3</a>
        """.trimIndent()
        assertEquals(
            "https://malayalamsubtitles.org/files/Squid.Game.S02E02.srt",
            MsoneSite.downloadHref(page, releaseUrl, 2, 2),
        )
        assertEquals(
            "https://malayalamsubtitles.org/files/Squid.Game.S02E01.srt",
            MsoneSite.downloadHref(page, releaseUrl, 2, null),
        )
    }

    @Test
    fun `the download buttons own package address is the download`() {
        // The site's real shape (read from its own pages, 2026-11-08): the button's href is
        // "#" and its click code names a download manager package; menu links carrying the
        // same word stand earlier in the page and are not the download.
        val page = """
            <html><body>
            <a href="https://malayalamsubtitles.org/send/">Send a subtitle</a>
            <a id="review-button"
               class="button" rel="nofollow" href="#"
               onclick="window.location.href='https://malayalamsubtitles.org/?wpdmdl=24881'; return false;">
               Subtitle </a>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://malayalamsubtitles.org/?wpdmdl=24881",
            MsoneSite.downloadHref(page, releaseUrl, null, null),
        )
    }

    @Test
    fun `the button beats a stray archive and a direct file beats a looser package`() {
        val buttonAndZip = """
            <a href="/wp-content/uploads/2026/10/other.zip">Other</a>
            <a id="review-button" href="#" onclick="window.location.href='https://malayalamsubtitles.org/?wpdmdl=77'; return false;">Subtitle</a>
        """.trimIndent()
        assertEquals(
            "https://malayalamsubtitles.org/?wpdmdl=77",
            MsoneSite.downloadHref(buttonAndZip, releaseUrl, null, null),
        )
        val fileAndLoose = """
            <a href="/files/one.srt">One</a>
            <a href="#" onclick="window.location.href='https://malayalamsubtitles.org/?wpdmdl=78'; return false;">Subtitle</a>
        """.trimIndent()
        assertEquals(
            "https://malayalamsubtitles.org/files/one.srt",
            MsoneSite.downloadHref(fileAndLoose, releaseUrl, null, null),
        )
    }

    @Test
    fun `a package with no proper number gives nothing`() {
        assertNull(MsoneSite.downloadHref("""<a id="review-button" href="#" onclick="go('?wpdmdl=abc')">x</a>""", releaseUrl, null, null))
        assertNull(MsoneSite.downloadHref("""<a id="review-button" href="#" onclick="go('?wpdmdl=123456789012345')">x</a>""", releaseUrl, null, null))
        assertNull(MsoneSite.downloadHref("""<a id="review-button" href="#" onclick="go('?other=12')">x</a>""", releaseUrl, null, null))
    }

    @Test
    fun `a page with no trusted download anchor gives nothing`() {
        assertNull(MsoneSite.downloadHref("<a href=\"https://elsewhere.example/x.srt\">x</a>", releaseUrl, null, null))
        assertNull(MsoneSite.downloadHref("<a href=\"/about/\">About</a>", releaseUrl, null, null))
        assertNull(MsoneSite.downloadHref("", releaseUrl, null, null))
        assertNull(MsoneSite.downloadHref("<p>no links</p>", "not a url", null, null))
    }
}
