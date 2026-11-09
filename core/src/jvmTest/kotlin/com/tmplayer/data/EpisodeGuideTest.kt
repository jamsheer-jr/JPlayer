package com.tmplayer.data

import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeGuideTest {

    private fun video(id: Long, name: String, chat: Long = 1) = MediaItem(
        chatId = chat,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 100,
        durationSec = 60,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    private val chat = listOf(
        video(1, "Harbour.Notes.S01E01.mkv"),
        video(2, "Harbour.Notes.S01E02.mkv"),
        video(3, "Harbour.Notes.S02E01.mkv"),
        video(4, "Something.Else.2020.mkv"),
        video(5, "Harbour.Notes.S01E03.mkv", chat = 2),
    )

    @Test
    fun `the show is found across the chats, not only its own`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat) ?: error("no series")
        // S01E03 stands in chat 2: held, as a search across the chats would gather it.
        assertEquals(listOf(1 to 3, 2 to 1), series.seasons.map { it.number to it.episodes.size })
        assertNull(EpisodeGuide.seriesOf(chat[3], chat))
    }

    @Test
    fun `seasons the chat lacks are missing once aired and upcoming before`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val listed = listOf(MetaSeason(1, 3, "2021-01-01"), MetaSeason(2, 3, "2022-01-01"), MetaSeason(3, 8, "2023-01-01"))
        val next = MetaAiring(4, 1, "2027-03-12")
        val seasons = EpisodeGuide.seasons(series, listed, next, today = "2026-10-06")
        assertEquals(listOf(1, 2, 3, 4), seasons.map { it.number })
        assertEquals(
            listOf(EpisodeGuide.State.InChat, EpisodeGuide.State.InChat, EpisodeGuide.State.Missing, EpisodeGuide.State.Upcoming),
            seasons.map { it.state },
        )
        assertEquals(3, seasons[0].inChat)
        assertEquals(3, seasons[0].total)
        assertEquals("2027-03-12", seasons[3].airDate)
    }

    @Test
    fun `episodes are in chat, missing, or upcoming`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val listed = listOf(
            MetaEpisode(1, 1, "One", airDate = "2021-01-01"),
            MetaEpisode(1, 2, "Two", airDate = "2021-01-08"),
            MetaEpisode(1, 3, "Three", airDate = "2021-01-15"),
            MetaEpisode(1, 4, "Four", airDate = "2026-12-01"),
            MetaEpisode(1, 5, "Five"),
        )
        val episodes = EpisodeGuide.episodes(1, series, listed, today = "2026-10-06")
        assertEquals(
            listOf(
                EpisodeGuide.State.InChat,
                EpisodeGuide.State.InChat,
                EpisodeGuide.State.InChat,
                EpisodeGuide.State.Upcoming,
                EpisodeGuide.State.Upcoming,
            ),
            episodes.map { it.state },
        )
        assertEquals("S01E03", episodes[2].code)
        assertEquals("One", episodes[0].meta?.name)
    }

    @Test
    fun `with no provider list the held episodes still show`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val episodes = EpisodeGuide.episodes(1, series, null, today = "2026-10-06")
        assertEquals(listOf(1, 2, 3), episodes.map { it.number })
        val seasons = EpisodeGuide.seasons(series, emptyList(), null, today = "2026-10-06")
        assertEquals(listOf(1, 2), seasons.map { it.number })
    }


    @Test
    fun `a season pack holds the episodes no file names on its own`() {
        val items = listOf(
            video(11, "Make.Me.Tingle.S01E01.mkv"),
            video(12, "Make.Me.Tingle.S01E02.mkv"),
            video(13, "Make Me Tingle S01 720p HEVC COMBINED AMZN WEB-DL.mkv"),
        )
        val series = EpisodeGuide.seriesOf(items[0], items) ?: error("no series")
        assertEquals(1, series.packs.size)
        val listed = (1..5).map { MetaEpisode(1, it, "Ep $it", airDate = "2021-01-0$it") }
        val episodes = EpisodeGuide.episodes(1, series, listed, today = "2026-10-06")
        assertEquals(List(5) { EpisodeGuide.State.InChat }, episodes.map { it.state })
        // The first two play their own files; the rest play the pack.
        assertEquals("1:11", episodes[0].inChat?.item?.id)
        assertEquals("1:13", episodes[4].inChat?.item?.id)
        val seasons = EpisodeGuide.seasons(series, listOf(MetaSeason(1, 5, "2021-01-01")), null, today = "2026-10-06")
        assertEquals(5, seasons.single().inChat)
        // The pack's own page finds its show too.
        assertEquals(series.key, EpisodeGuide.seriesOf(items[2], items)?.key)
    }
}
