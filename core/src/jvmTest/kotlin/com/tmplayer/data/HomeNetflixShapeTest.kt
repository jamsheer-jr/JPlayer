package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Netflix shape rows (owner, v1.0.15, items 16 and 29): the chips' cut of Home's
 * rows ([HomeChipFilter]), the dubbed language shelves and the Top picks shelf in
 * [HomeRows.build]. Every row here stands only on what the chats and the lookups
 * already hold, so each test names its evidence.
 */
class HomeNetflixShapeTest {

    private var nextId = 1L

    private fun video(chatId: Long, fileName: String) = MediaItem(
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

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    private fun resume(item: MediaItem) = ResumeRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 1,
        title = item.title,
        chatTitle = "",
        sizeBytes = 1,
        durationSec = 60,
        positionMs = 10_000,
        durationMs = 60_000,
        updatedAt = 0,
    )

    private fun later(item: MediaItem, show: Boolean = false) = WatchLaterRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 1,
        title = item.title,
        fileName = item.fileName,
        chatTitle = "",
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        addedAt = 0,
        series = if (show) "show-key" else "",
    )

    private fun keys(rows: List<HomeRow>): List<String> = rows.map { it.key }

    @Test
    fun `the Movies chip keeps only films, the Shows chip only shows`() {
        val film = video(1, "Night Train 2024.mp4")
        val episode = video(1, "Harbour Notes S01E01.mkv")
        val rows = listOf(
            HomeRow.Continue(listOf(resume(film), resume(episode))),
            HomeRow.WatchLater(listOf(later(film), later(episode, show = true))),
            HomeRow.NewEpisodes(listOf(NewEpisode(episode, "Harbour Notes", "S01E01"))),
            HomeRow.Chat(1, "Chat 1", listOf(ShelfEntry.File(film), ShelfEntry.File(episode)), loaded = true),
            HomeRow.Recent(listOf(ShelfEntry.File(film), ShelfEntry.File(episode)), loaded = true),
            HomeRow.Catalog("movies", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
            HomeRow.Catalog("series", "", listOf(ShelfEntry.File(episode)), all = listOf(ShelfEntry.File(episode))),
        )

        val movies = HomeChipFilter.apply(rows, HomeChip.Movies)
        assertEquals(listOf("continue", "watch-later", "chat-1", "recent", "catalog-movies"), keys(movies))
        val movieContinue = movies.filterIsInstance<HomeRow.Continue>().single()
        assertEquals(listOf(film.messageId), movieContinue.records.map { it.messageId })
        val movieChat = movies.filterIsInstance<HomeRow.Chat>().single()
        assertEquals(listOf(film.messageId), movieChat.entries.map { (it as ShelfEntry.File).item.messageId })

        val shows = HomeChipFilter.apply(rows, HomeChip.Shows)
        assertEquals(listOf("continue", "watch-later", "new-episodes", "chat-1", "recent", "catalog-series"), keys(shows))
        val showLater = shows.filterIsInstance<HomeRow.WatchLater>().single()
        assertEquals(listOf(episode.messageId), showLater.records.map { it.messageId })

        // All is the full Home, untouched.
        assertEquals(rows, HomeChipFilter.apply(rows, HomeChip.All))
    }

    @Test
    fun `the New and Hot chip keeps only new arrivals`() {
        val film = video(1, "Night Train 2024.mp4")
        val episode = video(1, "Harbour Notes S01E02.mkv")
        val rows = listOf(
            HomeRow.Continue(listOf(resume(film))),
            HomeRow.NewEpisodes(listOf(NewEpisode(episode, "Harbour Notes", "S01E02"))),
            HomeRow.Chat(1, "Chat 1", listOf(ShelfEntry.File(film)), loaded = true),
            HomeRow.Recent(listOf(ShelfEntry.File(film)), loaded = true),
            HomeRow.Catalog("movies", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
            HomeRow.Catalog("weekly", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
            HomeRow.Catalog("trending", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
            HomeRow.Catalog("top10", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
        )
        val hot = HomeChipFilter.apply(rows, HomeChip.NewHot)
        assertEquals(listOf("new-episodes", "recent", "catalog-weekly", "catalog-trending"), keys(hot))
    }

    @Test
    fun `a chip with nothing of its kind in the chats shows no row for it`() {
        val film = video(1, "Night Train 2024.mp4")
        val rows = listOf(
            HomeRow.Chat(1, "Chat 1", listOf(ShelfEntry.File(film)), loaded = true),
            HomeRow.Catalog("movies", "", listOf(ShelfEntry.File(film)), all = listOf(ShelfEntry.File(film))),
        )
        assertTrue(HomeChipFilter.apply(rows, HomeChip.Shows).isEmpty())
    }

    @Test
    fun `dubbed titles form their own language row from their names`() {
        val films = listOf(
            video(1, "Alpha Malayalam Dubbed 2024.mp4"),
            video(1, "Beta Malayalam Dubbed 2024.mp4"),
            video(1, "Gamma Malayalam Dubbed 2024.mp4"),
            video(1, "Delta Hindi 2024.mp4"),
            video(1, "Epsilon Hindi 2024.mp4"),
            video(1, "Zeta Hindi 2024.mp4"),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalog = rows.filterIsInstance<HomeRow.Catalog>()
        val dubbed = catalog.filter { it.kind.startsWith("dub-") }
        // The Malayalam names say Dubbed, so there is a dub-ml row; the Hindi names
        // name a language and no dub, so there is no dub-hi row invented for them.
        assertEquals(listOf("dub-ml"), dubbed.map { it.kind })
        assertEquals("Malayalam", dubbed.single().label)
        assertEquals(3, dubbed.single().entries.size)
        // The dubbed rows belong to the picker's language group, with the shelves.
        assertEquals("lang", HomeRows.catalogGroupOf("dub-ml"))
    }

    @Test
    fun `top picks joins what was watched with what rates well`() {
        val sharedRated = video(1, "Shared Rated 2024.mp4")
        val sharedPlain = video(1, "Shared Plain 2024.mp4")
        val ratedOnly = video(1, "Rated Only 2024.mp4")
        val neither = video(1, "Neither 2024.mp4")
        val films = listOf(sharedRated, sharedPlain, ratedOnly, neither)
        val meta = mapOf(
            sharedRated.id to TitleMeta(listOf("Action"), 6.0),
            sharedPlain.id to TitleMeta(listOf("Action"), null),
            ratedOnly.id to TitleMeta(listOf("Drama"), 8.5),
            neither.id to TitleMeta(listOf("Comedy"), 5.0),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
            watchedGenres = setOf("Action"),
        )
        val picks = rows.filterIsInstance<HomeRow.Catalog>().filter { it.kind == "top-picks" }
        assertEquals(1, picks.size)
        // Most shared genres first, then best rated: the two Action titles lead, the
        // well rated Drama follows, and the Comedy title stands in neither reading.
        assertEquals(
            listOf(sharedRated.messageId, sharedPlain.messageId, ratedOnly.messageId),
            picks.single().entries.map { (it as ShelfEntry.File).item.messageId },
        )

        // With nothing watched there is nothing to pick from: no row, rather than a
        // second copy of Top Rated.
        val unwatched = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        assertFalse(unwatched.filterIsInstance<HomeRow.Catalog>().any { it.kind == "top-picks" })
    }
}
