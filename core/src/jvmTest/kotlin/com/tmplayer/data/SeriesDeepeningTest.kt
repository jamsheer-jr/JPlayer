package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Series row's deeper read (owner, v1.0.15): [SeriesDeepening]'s discovery and
 * merge, and the shelf [HomeRows.build] builds once the deeper pool joins it. The
 * owner's case in miniature: a show whose window holds 3 episodes of 2 seasons
 * while its chat holds 13 episodes of 3.
 */
class SeriesDeepeningTest {

    private fun video(id: Long, name: String, chatId: Long = 1, date: Int = id.toInt()) = MediaItem(
        chatId = chatId,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 2_600,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = name,
    )

    private fun walking(id: Long, season: Int, episode: Int) =
        video(id, "The.Walking.Dead.S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}.1080p.WEB-DL.mkv")

    /** The window: the newest 3 episodes, of seasons 1 and 2. */
    private val window = listOf(walking(30, 2, 2), walking(29, 2, 1), walking(28, 1, 3))

    /** The rest of the show in the chat, older than the window. */
    private val older = (1..8).map { walking(it.toLong(), 1, it) } + (11..13).map { walking(it.toLong(), 3, it - 10) }

    @Test
    fun `a show with two distinct episodes in the window is discovered, a lone episode is not`() {
        val shows = SeriesDeepening.showsIn(window + video(40, "Harbour.Notes.S01E01.mkv") + video(41, "Some.Film.2019.1080p.mkv"))
        assertEquals(1, shows.size)
        assertEquals("the walking dead", shows.single().key)
        assertEquals("The Walking Dead", shows.single().title)
    }

    @Test
    fun `the search word is the title's longest word, which every spelling of the name holds`() {
        assertEquals("Walking", SeriesDeepening.queryOf(SeriesDeepening.Show("the walking dead", "The Walking Dead")))
    }

    @Test
    fun `only the show's own episodes belong to it`() {
        val key = SeriesDeepening.showsIn(window).single().key
        assertTrue(SeriesDeepening.isEpisodeOf(walking(9, 1, 1), key))
        assertFalse(SeriesDeepening.isEpisodeOf(video(50, "The.Walking.Dead.2010.1080p.WEB-DL.mkv"), key))
        assertFalse(SeriesDeepening.isEpisodeOf(video(51, "Harbour.Notes.S01E01.mkv"), key))
    }

    @Test
    fun `the merge holds each video once, newest first, capped`() {
        val merged = SeriesDeepening.merge(window, older + window.first())
        assertEquals((window + older).size, merged.size)
        assertEquals(merged.map { it.date }.sortedDescending(), merged.map { it.date })
        val capped = SeriesDeepening.merge((1..SeriesDeepening.MAX_ITEMS_PER_CHAT + 50).map { walking(it.toLong(), 1, it) }, emptyList())
        assertEquals(SeriesDeepening.MAX_ITEMS_PER_CHAT, capped.size)
    }

    @Test
    fun `the shelf counts the deeper pool, the chat row keeps its window`() {
        val chat = ChatSummary(1, "Series Chat", null, 0, ChatKind.Channel)
        // The shelf stands under the layout the store hands Home, shelves on: the
        // bare layout builds no catalog rows at all (see HomeLayout).
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat),
            loaded = mapOf(1L to window),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            seriesPool = SeriesDeepening.merge(window, older),
        )
        val series = rows.filterIsInstance<HomeRow.Catalog>().first { it.kind == "series" }
        val show = (series.entries.single() as ShelfEntry.Show).series
        // 3 seasons, 13 episodes (S01 has 8, S02 has 2, S03 has 3; the window's
        // S01E03 is already among the 8): the counts the channels really hold.
        assertEquals(3, show.seasons.size)
        assertEquals(13, show.episodeCount)
        // The chat's own row still stands on its window alone: 3 episodes.
        val chatRow = rows.filterIsInstance<HomeRow.Chat>().single()
        val rowShow = (chatRow.entries.single() as ShelfEntry.Show).series
        assertEquals(3, rowShow.episodeCount)
    }

    @Test
    fun `without the deeper pool the shelf stands on the window, as before`() {
        val chat = ChatSummary(1, "Series Chat", null, 0, ChatKind.Channel)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat),
            loaded = mapOf(1L to window),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val series = rows.filterIsInstance<HomeRow.Catalog>().first { it.kind == "series" }
        val show = (series.entries.single() as ShelfEntry.Show).series
        assertEquals(2, show.seasons.size)
        assertEquals(3, show.episodeCount)
    }
}
