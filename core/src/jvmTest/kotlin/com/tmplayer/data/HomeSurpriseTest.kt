package com.tmplayer.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSurpriseTest {

    private var nextId = 1L

    private fun video(chatId: Long = 7, fileName: String = "Holiday ${nextId}.mp4") = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = fileName,
    )

    private fun show(vararg episodes: MediaItem): ShelfEntry.Show {
        val eps = episodes.mapIndexed { index, item -> SeriesEpisode(item, season = 1, episode = index + 1) }
        return ShelfEntry.Show(Series(key = "show-${episodes.first().messageId}", title = "Show", seasons = listOf(SeriesSeason(1, eps))))
    }

    private val nothingFinished: (MediaItem) -> Boolean = { false }
    private val nothingStarted: (MediaItem) -> Float = { 0f }

    @Test
    fun `an unwatched film is offered and a finished or started one is not`() {
        val fresh = video()
        val done = video()
        val partway = video()
        val entries = listOf(ShelfEntry.File(fresh), ShelfEntry.File(done), ShelfEntry.File(partway))
        val picked = SurprisePick.candidates(
            entries,
            finished = { it.messageId == done.messageId },
            position = { if (it.messageId == partway.messageId) 0.4f else 0f },
        )
        assertEquals(listOf(fresh.messageId), picked.map { it.messageId })
    }

    @Test
    fun `a film counts as started when any of its copies is`() {
        val small = video()
        val big = video()
        val film = small.copy(versions = listOf(big))
        val picked = SurprisePick.candidates(
            listOf(ShelfEntry.File(film)),
            finished = nothingFinished,
            position = { if (it.messageId == big.messageId) 0.2f else 0f },
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `an extra is never offered`() {
        val trailer = video().copy(extra = ExtraKind.Trailer)
        assertTrue(SurprisePick.candidates(listOf(ShelfEntry.File(trailer)), nothingFinished, nothingStarted).isEmpty())
    }

    @Test
    fun `a show offers its next unwatched episode`() {
        val one = video(); val two = video(); val three = video()
        val picked = SurprisePick.candidates(
            listOf(show(one, two, three)),
            finished = { it.messageId == one.messageId },
            position = nothingStarted,
        )
        assertEquals(listOf(two.messageId), picked.map { it.messageId })
    }

    @Test
    fun `a show whose next episode is under way is left to Continue watching`() {
        val one = video(); val two = video()
        val picked = SurprisePick.candidates(
            listOf(show(one, two)),
            finished = nothingFinished,
            position = { if (it.messageId == one.messageId) 0.5f else 0f },
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a finished show offers nothing`() {
        val one = video()
        val picked = SurprisePick.candidates(listOf(show(one)), finished = { true }, position = nothingStarted)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `pick returns null when nothing is unwatched and a candidate otherwise`() {
        val fresh = video()
        assertNull(SurprisePick.pick(emptyList(), nothingFinished, nothingStarted, Random(1)))
        assertEquals(
            fresh.messageId,
            SurprisePick.pick(listOf(ShelfEntry.File(fresh)), nothingFinished, nothingStarted, Random(1))?.messageId,
        )
    }
}
