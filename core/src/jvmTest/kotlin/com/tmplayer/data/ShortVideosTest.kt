package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The under 5 minute rule (owner, v1.0.15): what [ShortVideos] calls short, and the
 * Home rows built with its switch on ([HomeRows.build]'s hideShortVideos).
 */
class ShortVideosTest {

    private fun video(id: Long, durationSec: Int, name: String = "Holiday Film $id.mkv") = MediaItem(
        chatId = 1,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = durationSec,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    @Test
    fun `only a stated length under five minutes is short`() {
        assertTrue(ShortVideos.isShort(1))
        assertTrue(ShortVideos.isShort(299))
        assertFalse(ShortVideos.isShort(300))
        assertFalse(ShortVideos.isShort(3_600))
    }

    @Test
    fun `an unstated length is never short`() {
        // Telegram states 0 for a video whose length it does not know: unknown is
        // not under 5 minutes, and nonsense lengths from outside data are not either.
        assertFalse(ShortVideos.isShort(0))
        assertFalse(ShortVideos.isShort(-1))
        assertFalse(ShortVideos.isShort(video(1, 0)))
    }

    @Test
    fun `home rows leave short videos out and keep the unstated ones`() {
        val short = video(1, 240)
        val unstated = video(2, 0)
        val feature = video(3, 3_600)
        val chat = ChatSummary(1, "Chat", null, 0, ChatKind.Channel)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat),
            loaded = mapOf(1L to listOf(short, unstated, feature)),
            recent = emptyList(),
            hideShortVideos = true,
        )
        val row = rows.filterIsInstance<HomeRow.Chat>().single()
        val shown = row.entries.flatMap { entry ->
            when (entry) {
                is ShelfEntry.File -> listOf(entry.item.messageId)
                is ShelfEntry.Show -> entry.series.episodes.map { it.item.messageId }
            }
        }
        assertEquals(listOf(feature.messageId, unstated.messageId).sorted(), shown.sorted())
    }

    @Test
    fun `home rows show short videos while the switch is off`() {
        val short = video(1, 240)
        val chat = ChatSummary(1, "Chat", null, 0, ChatKind.Channel)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat),
            loaded = mapOf(1L to listOf(short)),
            recent = emptyList(),
            hideShortVideos = false,
        )
        val row = rows.filterIsInstance<HomeRow.Chat>().single()
        assertEquals(1, row.entries.size)
    }

    @Test
    fun `a series shelf counts only the episodes that stay`() {
        // Three episodes of one show, the middle one a short clip: with the switch on
        // the show tile stands on the two full episodes alone.
        val episodes = listOf(
            video(1, 2_600, "Harbour Notes S01E01.mkv"),
            video(2, 120, "Harbour Notes S01E02.mkv"),
            video(3, 2_600, "Harbour Notes S01E03.mkv"),
        )
        val chat = ChatSummary(1, "Chat", null, 0, ChatKind.Channel)
        // The shelf stands under the layout the store hands Home, shelves on: the
        // bare layout builds no catalog rows at all (see HomeLayout).
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat),
            loaded = mapOf(1L to episodes),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            hideShortVideos = true,
        )
        val series = rows.filterIsInstance<HomeRow.Catalog>().first { it.kind == "series" }
        val show = (series.entries.single() as ShelfEntry.Show).series
        assertEquals(2, show.episodeCount)
        assertEquals(listOf(1, 3), show.episodes.map { it.episode })
    }
}
