package com.tmplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings search's matching (v1.0.11): a query finds a row by its own words, title
 * first, then the words its on and off lines carry, then only its page's name, and every
 * word of the query must match somewhere.
 */
class SettingsSearchTest {

    private val ripTags = SettingsSearchEntry(
        key = "playback.hide_rip_tags",
        page = SettingsPage.Playback,
        title = "Hide cam and rip tags in titles",
        also = "On: HDCam, CAMRip, TS and PreDVD are dropped from a shown name. HDRip stays",
    )

    private val entries = listOf(
        SettingsSearchEntry("appearance.theme", SettingsPage.Appearance, "Theme"),
        SettingsSearchEntry("playback.autoplay", SettingsPage.Playback, "Autoplay the next video"),
        ripTags,
        SettingsSearchEntry("playback.speed", SettingsPage.Playback, "Playback speed"),
    )

    private fun pageTitle(page: SettingsPage): String = when (page) {
        SettingsPage.Appearance -> "Appearance"
        SettingsPage.Playback -> "Playback"
        else -> ""
    }

    private fun find(query: String): List<SettingsSearchEntry> =
        SettingsSearch.results(entries, ::pageTitle, query)

    @Test
    fun `cam finds the rip tag row by its title`() {
        assertEquals(listOf(ripTags), find("cam"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(listOf(ripTags), find("CAM"))
    }

    @Test
    fun `words only the on and off lines carry still find the row`() {
        assertEquals(listOf(ripTags), find("hdrip"))
        assertEquals(listOf(ripTags), find("predvd"))
    }

    @Test
    fun `every word of the query must match`() {
        assertEquals(listOf(ripTags), find("rip tags"))
        assertTrue(find("rip nonsense").isEmpty())
    }

    @Test
    fun `a title match stands above a page name only match`() {
        val found = find("playback")
        assertEquals("playback.speed", found.first().key)
        assertTrue(found.any { it.key == "playback.autoplay" })
    }

    @Test
    fun `no match is an empty list`() {
        assertTrue(find("zzzz").isEmpty())
    }

    @Test
    fun `a blank query finds nothing`() {
        assertTrue(find("  ").isEmpty())
        assertTrue(find("").isEmpty())
    }
}
