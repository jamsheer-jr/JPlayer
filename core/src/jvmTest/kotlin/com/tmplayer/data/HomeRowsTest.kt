package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {

    private var nextId = 1L

    /** A name with no number in it, so no file is mistaken for an episode unless a test says so. */
    private fun plainName(n: Long) = "Holiday " + ('a' + (n % 26).toInt()) + ('a' + (n / 26 % 26).toInt()) + ".mp4"

    private fun video(chatId: Long, fileName: String = plainName(nextId), date: Int = nextId.toInt()) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    private fun chat(id: Long, title: String = "Chat $id") = ChatSummary(id, title, null, 0, ChatKind.Channel)

    private fun resume(item: MediaItem, updatedAt: Long = 0) = ResumeRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 1,
        title = item.title,
        chatTitle = "",
        sizeBytes = 1,
        durationSec = 60,
        positionMs = 10_000,
        durationMs = 60_000,
        updatedAt = updatedAt,
    )

    private fun ids(row: HomeRow): List<Long> = when (row) {
        is HomeRow.Continue -> row.records.map { it.messageId }
        is HomeRow.WatchLater -> row.records.map { it.messageId }
        is HomeRow.Chat -> row.entries.flatMap(::idsOf)
        is HomeRow.NewEpisodes -> row.episodes.map { it.item.messageId }
        is HomeRow.Recent -> row.entries.flatMap(::idsOf)
        is HomeRow.Catalog -> row.entries.flatMap(::idsOf)
    }

    private fun idsOf(entry: ShelfEntry): List<Long> = when (entry) {
        is ShelfEntry.File -> listOf(entry.item.messageId)
        is ShelfEntry.Show -> entry.series.episodes.map { it.item.messageId }
    }

    @Test
    fun `catalog shelves group the films and one language at the foot`() {
        val films = listOf(
            video(1, "Alpha Malayalam 2024.mp4"),
            video(1, "Beta Malayalam 2024.mp4"),
            video(1, "Gamma Malayalam 2024.mp4"),
            video(1, "Delta 2024.mp4"),
            video(1, "Epsilon 2024.mp4"),
            video(1, "Zeta 2024.mp4"),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalog = rows.filterIsInstance<HomeRow.Catalog>()
        assertEquals(listOf("catalog-movies", "catalog-lang-ml"), catalog.map { it.key })
        assertEquals(6, catalog[0].entries.size)
        assertEquals(3, catalog[1].entries.size)
        assertEquals("Malayalam", catalog[1].label)
        // Off in the layout, no shelves: the rows are the ones they have always been.
        val plain = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
        )
        assertTrue(plain.none { it is HomeRow.Catalog })
    }

    @Test
    fun `genre shelves and top rated group by the titles meta`() {
        val action = listOf(
            video(1, "Action One 2024.mp4"),
            video(1, "Action Two 2024.mp4"),
            video(1, "Action Three 2024.mp4"),
            video(1, "Action Four 2024.mp4"),
        )
        val drama = listOf(
            video(1, "Drama One 2024.mp4"),
            video(1, "Drama Two 2024.mp4"),
            video(1, "Drama Three 2024.mp4"),
        )
        val comedy = listOf(
            video(1, "Comedy One 2024.mp4"),
            video(1, "Comedy Two 2024.mp4"),
        )
        val unrated = video(1, "Quiet One 2024.mp4")
        val films = action + drama + comedy + unrated
        val meta = HashMap<String, TitleMeta>()
        listOf(8.1, 7.9, 7.5, 6.0).forEachIndexed { i, rating -> meta[action[i].id] = TitleMeta(listOf("Action"), rating) }
        listOf(8.4, 8.0, 7.6).forEachIndexed { i, rating -> meta[drama[i].id] = TitleMeta(listOf("Drama"), rating) }
        listOf(9.0, 8.8).forEachIndexed { i, rating -> meta[comedy[i].id] = TitleMeta(listOf("Comedy"), rating) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        val catalog = rows.filterIsInstance<HomeRow.Catalog>()
        // Two titles make no Comedy shelf (fewer than three), and the unrated film is in none.
        assertEquals(
            listOf("catalog-movies", "catalog-genre-action", "catalog-genre-drama", "catalog-top-rated"),
            catalog.map { it.key },
        )
        assertEquals("Action", catalog[1].label)
        assertEquals(4, catalog[1].entries.size)
        assertEquals(3, catalog[2].entries.size)
        // Top Rated: everything at 7.5 or above, best first, the 6.0 and the unrated left out.
        assertEquals(
            listOf(comedy[0], comedy[1], drama[0], action[0], drama[1], action[1], drama[2], action[2]).map { it.messageId },
            catalog[3].entries.flatMap(::idsOf),
        )
        // No meta handed in: no genre and no Top Rated shelf, the rest as before.
        val bare = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        assertTrue(bare.filterIsInstance<HomeRow.Catalog>().none { it.kind.startsWith("genre-") || it.kind == "top-rated" })
    }

    @Test
    fun `the series row stands under continue watching, fed by every chat read`() {
        val started = video(1)
        val episodes = (1..2).map { video(2, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(started), 2L to episodes),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        // Chat 2 is not one of Home's chats, and the show is the only one there is: the row
        // stands anyway, directly under Continue watching, and only once on the screen.
        assertEquals("continue", rows[0].key)
        val series = rows[1] as HomeRow.Catalog
        assertEquals("series", series.kind)
        assertEquals(1, series.entries.size)
        assertTrue(series.entries.single() is ShelfEntry.Show)
        assertEquals(1, rows.count { it is HomeRow.Catalog && it.kind == "series" })
    }

    @Test
    fun `the series row is fed by the search across chats too`() {
        val episodes = (1..2).map { video(9, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(video(1))),
            recent = episodes,
            layout = HomeLayout(catalogRows = true),
        )
        val series = rows.filterIsInstance<HomeRow.Catalog>().single { it.kind == "series" }
        assertEquals(1, series.entries.size)
        assertTrue(series.entries.single() is ShelfEntry.Show)
    }

    @Test
    fun `series stand in language rows under the series row, fullest first, one show enough`() {
        val seoul = (1..2).map { video(2, "Seoul.Diary.S01E%02d.Korean.mkv".format(it)) }
        val busan = (1..2).map { video(2, "Busan.Diary.S01E%02d.Korean.mkv".format(it)) }
        val beijing = (1..2).map { video(3, "Beijing.Diary.S01E%02d.Chinese.mkv".format(it)) }
        val harbour = (1..2).map { video(4, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val film = video(2, "Seoul.Film.2024.Korean.1080p.mkv")
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(video(1)), 2L to seoul + busan + film, 3L to beijing, 4L to harbour),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        val kinds = catalogs.map { it.kind }
        // The all-series row first, then the language rows, the Korean one (two shows) ahead
        // of the Chinese one (a single show, which is enough).
        assertEquals(listOf("series", "series-lang-ko", "series-lang-zh"), kinds.take(3))
        val korean = catalogs.single { it.kind == "series-lang-ko" }
        assertEquals("K-Drama", korean.label)
        assertEquals(2, korean.entries.size)
        assertTrue(korean.entries.all { it is ShelfEntry.Show })
        val chinese = catalogs.single { it.kind == "series-lang-zh" }
        assertEquals("C-Drama", chinese.label)
        assertEquals(1, chinese.entries.size)
        // The show no name gives a language to stands in the all-series row and in no
        // language row; the film stands in none of them.
        val all = catalogs.single { it.kind == "series" }
        assertEquals(4, all.entries.size)
        val languageRows = catalogs.filter { it.kind.startsWith("series-lang-") }
        assertEquals(3, languageRows.sumOf { it.entries.size })
    }

    @Test
    fun `the uhd shelf holds the titles with a 4k copy and no others`() {
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(
                1L to listOf(
                    video(1, "First.Film.2026.2160p.mkv"),
                    video(1, "Second.Film.2026.1080p.mkv"),
                    video(1, "Second.Film.2026.2160p.mkv"),
                    video(1, "Third.Film.2026.720p.mkv"),
                    video(1, "Fourth.Film.2026.2160p.WEB-DL.mkv"),
                ),
            ),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        // Three titles carry a 4K copy (one of them only as a second copy); the 720p
        // title stands in Movies but never here.
        assertEquals(3, catalogs.single { it.kind == "uhd" }.entries.size)
    }

    @Test
    fun `series stand in language rows by their drama tags alone`() {
        // No online match hands these a language: the names' own catalogue tags are the
        // only signal, as most of the owner's drama files carry them (owner, v1.0.14).
        val seoul = (1..2).map { video(2, "Seoul.Diary.S01E%02d.KDrama.mkv".format(it)) }
        val beijing = (1..2).map { video(3, "Beijing.Diary.S01E%02d.CDrama.mkv".format(it)) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(video(1)), 2L to seoul, 3L to beijing),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        assertEquals(1, catalogs.single { it.kind == "series-lang-ko" }.entries.size)
        assertEquals(1, catalogs.single { it.kind == "series-lang-zh" }.entries.size)
    }

    @Test
    fun `no series row while the catalog rows are off`() {
        val episodes = (1..2).map { video(2, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(2L to episodes),
            recent = emptyList(),
        )
        assertTrue(rows.none { it is HomeRow.Catalog })
    }

    @Test
    fun `trending shelf follows the given order and only ranked titles`() {
        val films = listOf(
            video(1, "Alpha 2024.mp4"),
            video(1, "Beta 2024.mp4"),
            video(1, "Gamma 2024.mp4"),
            video(1, "Delta 2024.mp4"),
            video(1, "Unranked 2024.mp4"),
        )
        val ranks = mapOf(films[2].id to 0, films[0].id to 1, films[3].id to 2, films[1].id to 3)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            trending = ranks,
        )
        val catalog = rows.filterIsInstance<HomeRow.Catalog>()
        // Trending leads the shelves, in TMDB's order, and the unranked film is not in it.
        assertEquals("catalog-trending", catalog.first().key)
        assertEquals(
            listOf(films[2], films[0], films[3], films[1]).map { it.messageId },
            catalog.first().entries.flatMap(::idsOf),
        )
        // Two ranked titles are not a shelf.
        val thin = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            trending = mapOf(films[0].id to 0, films[1].id to 1),
        )
        assertTrue(thin.filterIsInstance<HomeRow.Catalog>().none { it.kind == "trending" })
    }

    @Test
    fun `because shelf suggests unwatched titles sharing the watched genres`() {
        val both = video(1, "Both Genres 2024.mp4")
        val actionLow = video(1, "Action Low 2024.mp4")
        val actionHigh = video(1, "Action High 2024.mp4")
        val seenAction = video(1, "Action Seen 2024.mp4")
        val drama = video(1, "Drama One 2024.mp4")
        val films = listOf(both, actionLow, actionHigh, seenAction, drama)
        val meta = mapOf(
            both.id to TitleMeta(listOf("Action", "Thriller"), 7.0),
            actionLow.id to TitleMeta(listOf("Action"), 6.5),
            actionHigh.id to TitleMeta(listOf("Action"), 9.0),
            seenAction.id to TitleMeta(listOf("Action"), 9.5),
            drama.id to TitleMeta(listOf("Drama"), 9.9),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
            watchedGenres = setOf("Action", "Thriller"),
            watchedKeys = setOf(seenAction.progressKey),
        )
        val because = rows.filterIsInstance<HomeRow.Catalog>().single { it.kind == "because" }
        // Most shared genres first, then the best rated; the watched one and the drama stay out,
        // however high they rate.
        assertEquals(
            listOf(both, actionHigh, actionLow).map { it.messageId },
            because.entries.flatMap(::idsOf),
        )
        // Nothing watched, nothing suggested.
        val bare = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        assertTrue(bare.filterIsInstance<HomeRow.Catalog>().none { it.kind == "because" })
    }

    @Test
    fun `rows come in order continue, recent, then starred chats`() {
        val a = video(1)
        val b = video(2)
        val c = video(3)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(video(9))),
            favourites = listOf(chat(2), chat(1)),
            loaded = mapOf(1L to listOf(a), 2L to listOf(b)),
            recent = listOf(c),
        )
        assertEquals(listOf("continue", "recent", "chat-2", "chat-1"), rows.map { it.key })
    }

    @Test
    fun `the recent row waits while a starred chat is still loading, and goes once all are in and empty`() {
        val waiting = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), loaded = mapOf(1L to emptyList()), recent = emptyList(), recentMinStarred = 1)
        val recent = waiting.filterIsInstance<HomeRow.Recent>().single()
        assertEquals(false, recent.loaded)
        val done = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), loaded = mapOf(1L to emptyList(), 2L to emptyList()), recent = emptyList(), recentMinStarred = 1)
        assertEquals(emptyList<HomeRow.Recent>(), done.filterIsInstance<HomeRow.Recent>())
    }

    @Test
    fun `the recent row holds its place while the chat list is still coming in after a cold start`() {
        val kept = listOf(video(1), video(2))
        val pending = HomeRows.build(listOf(resume(video(9))), emptyList(), emptyMap(), recent = kept, recentMinStarred = 1, autoFill = true, chatsPending = true)
        assertEquals(listOf("continue", "recent"), pending.map { it.key })
        assertEquals(false, pending.filterIsInstance<HomeRow.Recent>().single().loaded)
        // Once the chats are known the row is the real one, and with no chats at all it goes.
        val none = HomeRows.build(listOf(resume(video(9))), emptyList(), emptyMap(), recent = kept, recentMinStarred = 1, autoFill = true)
        assertEquals(listOf("continue"), none.map { it.key })
    }

    @Test
    fun `starred chats follow the chat list order, not the order they were starred`() {
        val chats = listOf(chat(5), chat(3), chat(8), chat(1))
        val starred = HomeRows.favouriteChats(chats, linkedSetOf(1L, 8L, 5L))
        assertEquals(listOf(5L, 8L, 1L), starred.map { it.id })
    }

    @Test
    fun `each row is newest first`() {
        val old = video(1, date = 10)
        val new = video(1, date = 30)
        val mid = video(1, date = 20)
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to listOf(old, new, mid)), recent = emptyList())
        assertEquals(listOf(new.messageId, mid.messageId, old.messageId), ids(rows.single()))
    }

    @Test
    fun `no row holds more than ten tiles`() {
        val many = (1..25).map { video(1) }
        val history = (1..14).map { resume(video(4)) }
        val rows = HomeRows.build(history, listOf(chat(1)), mapOf(1L to many), recent = (1..30).map { video(2) })
        rows.forEach { row ->
            val size = when (row) {
                is HomeRow.Continue -> row.records.size
                is HomeRow.WatchLater -> row.records.size
                is HomeRow.Chat -> row.entries.size
                is HomeRow.NewEpisodes -> row.episodes.size
                is HomeRow.Recent -> row.entries.size
                is HomeRow.Catalog -> row.entries.size
            }
            assertEquals(row.key, HomeRows.LIMIT, size)
        }
    }

    @Test
    fun `a folded show counts as one tile, so a row of ten can hold more than ten files`() {
        // The loose files first, so the show is the newest upload and sits at the front of the row.
        val loose = (1..12).map { video(1) }
        val episodes = (1..8).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to episodes + loose), recent = emptyList())
        val row = rows.single() as HomeRow.Chat
        assertEquals(HomeRows.LIMIT, row.entries.size)
        assertEquals(1, row.entries.count { it is ShelfEntry.Show })
    }

    @Test
    fun `a show's episodes always fold into one tile`() {
        val episodes = (1..3).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to episodes), recent = emptyList())
        assertTrue((rows.single() as HomeRow.Chat).entries.single() is ShelfEntry.Show)
    }

    @Test
    fun `a video part way through is only in continue`() {
        val started = video(1)
        val other = video(1)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(started, other)),
            recent = listOf(started, video(2)),
        )
        val shown = rows.flatMap(::ids)
        assertEquals(1, shown.count { it == started.messageId })
        assertEquals(listOf(started.messageId), ids(rows.first()))
    }

    @Test
    fun `recent holds only the starred chats, newest first across them`() {
        val older = video(1, date = 10)
        val newer = video(2, date = 30)
        val fromSearch = video(2, date = 20)
        val groupChat = video(7, date = 40)
        val started = video(1, date = 50)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to listOf(older, started), 2L to listOf(newer)),
            recent = listOf(groupChat, fromSearch, newer, started),
        )
        assertEquals(listOf("continue", "recent", "chat-1", "chat-2"), rows.map { it.key })
        val recent = rows[1] as HomeRow.Recent
        // Nothing from an unstarred chat, nothing that is in Continue, and the search and the
        // chats' own fetches merged without a repeat.
        assertEquals(listOf(newer.messageId, fromSearch.messageId, older.messageId), ids(recent))
    }

    @Test
    fun `recent needs two starred chats, as one already has its own row`() {
        val one = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to listOf(video(1))), recent = listOf(video(1)))
        assertEquals(listOf("chat-1"), one.map { it.key })
        val loading = HomeRows.build(emptyList(), listOf(chat(1)), emptyMap(), recent = null)
        assertEquals(listOf("chat-1"), loading.map { it.key })
    }

    @Test
    fun `no favourites means no chat rows and no recent`() {
        val rows = HomeRows.build(emptyList(), favourites = emptyList(), loaded = emptyMap(), recent = listOf(video(1), video(2)))
        assertTrue(rows.isEmpty())
        assertTrue(HomeRows.favouriteChats(listOf(chat(1)), emptySet()).isEmpty())
    }

    @Test
    fun `a starred chat still loading is a placeholder row, and one with nothing is left out`() {
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(2L to emptyList()),
            recent = null,
        )
        assertEquals(listOf("recent", "chat-1"), rows.map { it.key })
        assertFalse((rows[0] as HomeRow.Recent).loaded)
        assertFalse((rows[1] as HomeRow.Chat).loaded)
    }

    @Test
    fun `nothing anywhere is no rows at all`() {
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = emptyList()).isEmpty())
    }

    @Test
    fun `See all is offered only when a row leaves something out`() {
        val few = (1..3).map { video(1) }
        val many = (1..14).map { video(2) }
        val history = (1..12).map { resume(video(3)) }
        val rows = HomeRows.build(history, listOf(chat(1), chat(2)), mapOf(1L to few, 2L to many), recent = emptyList())

        val resume = rows[0] as HomeRow.Continue
        assertTrue(resume.hasMore)
        assertEquals(12, resume.total)
        assertEquals(2, resume.more.size)

        val chats = rows.filterIsInstance<HomeRow.Chat>()
        val small = chats[0]
        assertFalse(small.hasMore)
        assertEquals(3, small.total)

        val big = chats[1]
        assertTrue(big.hasMore)
        assertEquals(14, big.total)
        assertFalse(big.totalAtLeast)
        // The four oldest, newest first: what the tile's picture is made of.
        assertEquals(many.take(4).map { it.id }.reversed(), big.more.map { it.id })
    }

    @Test
    fun `a chat fetched only in part still offers See all, and counts at least what it has`() {
        val few = (1..3).map { video(1) }
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), mapOf(1L to few), recent = emptyList(), partial = setOf(1L))
        val row = rows[0] as HomeRow.Chat
        assertTrue(row.more.isEmpty())
        assertTrue(row.hasMore)
        assertTrue(row.totalAtLeast)
    }

    @Test
    fun `watch later follows Continue, leaves out what was started, and is not repeated below`() {
        val saved = (1..3).map { video(1) }
        val started = saved[0]
        val later = saved.mapIndexed { i, item -> WatchLaterRecord.of(item, "Chat 1", addedAt = 10L - i) }
        val rows = HomeRows.build(
            listOf(resume(started)),
            listOf(chat(1)),
            mapOf(1L to saved + video(1)),
            recent = emptyList(),
            watchLater = later,
        )
        assertEquals(listOf("continue", "watch-later", "chat-1"), rows.map { it.key })
        assertEquals(saved.drop(1).map { it.messageId }, ids(rows[1]))
        assertEquals(1, ids(rows[2]).size)
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = emptyList(), watchLater = emptyList()).isEmpty())
    }

    @Test
    fun `two copies of one film in continue watching are one card, the one watched last`() {
        val hd = video(1, "Bethlehem Kudumba Unit (2024) 1080p WEB-DL x264.mkv")
        val sd = video(1, "Bethlehem Kudumba Unit (2024) 720p WEB-DL x264.mkv")
        val other = video(1, "Night Train (2019) 1080p.mkv")
        val rows = HomeRows.build(
            continueWatching = listOf(resume(sd, updatedAt = 30), resume(other, updatedAt = 20), resume(hd, updatedAt = 10)),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(hd, sd, other)),
            recent = null,
        )
        val continuing = rows.first() as HomeRow.Continue
        assertEquals(listOf(sd.messageId, other.messageId), continuing.records.map { it.messageId })
        // The other copy is not offered again in the chat's row either.
        assertTrue(rows.drop(1).none { row -> hd.messageId in ids(row) })
    }

    @Test
    fun `continue watching keeps apart two copies the viewer said are not the same video`() {
        val hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val sd = video(2, "Loki.S02E05.720p.WEB.x264.mkv")
        // An old record keeps only its title; a new one its file name too.
        val records = listOf(resume(hd, updatedAt = 20), resume(sd, updatedAt = 10).copy(fileName = sd.fileName))
        try {
            assertEquals(1, HomeRows.foldCopies(records).size)
            VideoCorrections.set(mapOf(hd.id to VideoCorrection(separate = true)))
            assertEquals(2, HomeRows.foldCopies(records).size)
        } finally {
            VideoCorrections.set(emptyMap())
        }
    }

    @Test
    fun `the watched list folds copies like continue watching, and unmarking the card covers them all`() {
        val hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val sd = video(2, "Loki.S02E05.720p.WEB.x264.mkv")
        val next = video(1, "Loki.S02E06.720p.WEB.x264.mkv")
        val records = listOf(
            WatchedRecord.of(hd, "Chat 1", watchedAt = 30, manual = false),
            WatchedRecord.of(next, "Chat 1", watchedAt = 20, manual = false),
            WatchedRecord.of(sd, "Chat 2", watchedAt = 10, manual = true),
        )
        assertEquals(listOf(hd.messageId, next.messageId), HomeRows.foldWatched(records).map { it.messageId })
        val card = HomeRows.watchedItem(records[0], records)
        assertEquals(setOf(hd.messageId, sd.messageId), card.versions.map { it.messageId }.toSet())
        assertTrue(HomeRows.watchedItem(records[1], records).versions.isEmpty())
    }

    @Test
    fun `copies of one episode fold, in this chat and others, and other episodes stay apart`() {
        val e1hd = video(1, "Loki.S02E05.1080p.WEB.x265.mkv")
        val e1sd = video(1, "Loki.S02E05.720p.WEB.x264.mkv")
        val e2 = video(1, "Loki.S02E06.720p.WEB.x264.mkv")
        val elsewhere = video(2, "Loki.S02E05.480p.mkv")
        val clip = video(1, "Holiday clip.mp4")
        val clip2 = video(1, "Holiday clip.mp4")
        val records = listOf(
            resume(e1hd, updatedAt = 50),
            resume(e2, updatedAt = 40),
            resume(e1sd, updatedAt = 30),
            resume(elsewhere, updatedAt = 20),
            resume(clip, updatedAt = 10),
            resume(clip2, updatedAt = 5),
        )
        val folded = HomeRows.foldCopies(records)
        // The 480p posted in another chat is the same episode: one card for it, as for the 720p.
        assertEquals(listOf(e1hd, e2, clip, clip2).map { it.messageId }, folded.map { it.messageId })
        // Out of order, the newest copy still wins, in the place the first of them held.
        val kept = HomeRows.foldCopies(listOf(resume(e1sd, updatedAt = 1), resume(e1hd, updatedAt = 9)))
        assertEquals(listOf(e1hd.messageId), kept.map { it.messageId })
        // Removing the folded card takes every copy, and nothing else.
        assertEquals(
            setOf(e1hd.messageId, e1sd.messageId, elsewhere.messageId),
            HomeRows.copiesOf(records[0], records).map { it.messageId }.toSet(),
        )
        assertEquals(listOf(clip.messageId), HomeRows.copiesOf(records[4], records).map { it.messageId })
    }

    @Test
    fun `a film started in one copy keeps its other copies out of the rows below`() {
        val sd = video(1, "Thunderbolts.2025.720p.HS.WEB-DL.x264.mkv")
        val hd = video(1, "Thunderbolts.2025.1080p.HS.WEB-DL.x265.mkv")
        val e1 = video(1, "Loki.S01E01.720p.mkv")
        val e1hd = video(1, "Loki.S01E01.1080p.mkv")
        val e2 = video(1, "Loki.S01E02.720p.mkv")
        val other = video(1, "Night Train (2019) 1080p.mkv")
        val loaded = listOf(sd, hd, e1, e1hd, e2, other)
        val rows = HomeRows.build(
            continueWatching = listOf(resume(sd, updatedAt = 20), resume(e1, updatedAt = 10)),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to loaded, 2L to emptyList()),
            recent = loaded,
        )
        val below = rows.drop(1).flatMap(::ids)
        assertFalse(hd.messageId in below)
        assertTrue(other.messageId in below)
        // An episode in progress still counts towards its show below, and is never a tile alone.
        val show = rows.drop(1).flatMap { row ->
            when (row) {
                is HomeRow.Recent -> row.entries
                is HomeRow.Chat -> row.entries
                else -> emptyList()
            }
        }.filterIsInstance<ShelfEntry.Show>().first()
        assertEquals(2, show.series.episodeCount)
        assertTrue(rows.drop(1).none { row -> row is HomeRow.Recent && row.entries.any { it is ShelfEntry.File && it.item.messageId == e1.messageId } })
    }

    private fun later(item: MediaItem, at: Long = 0) = WatchLaterRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 1,
        title = item.title,
        fileName = item.fileName,
        chatTitle = "",
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        addedAt = at,
    )

    @Test
    fun phoneOrderPutsWatchLaterAndFavouritesUnderContinue() {
        val films = listOf(video(1), video(1), video(1))
        val rows = HomeRows.build(
            continueWatching = listOf(resume(films[0])),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            watchLater = listOf(later(films[1])),
            layout = HomeLayout(catalogRows = true),
            autoFill = true,
            phoneOrder = true,
        )
        val keys = rows.map { it.key }
        assertEquals("continue", keys[0])
        assertEquals("watch-later", keys[1])
        assertEquals("chat-1", keys[2])
        // A television keeps its order: no Watch later row where Home fills itself.
        val tv = HomeRows.build(
            continueWatching = listOf(resume(films[0])),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            watchLater = listOf(later(films[1])),
            layout = HomeLayout(catalogRows = true),
            autoFill = true,
        )
        assertFalse(tv.any { it.key == "watch-later" })
    }

    @Test
    fun newShelvesGroupByPlatformAndWeek() {
        val films = listOf(
            video(1, "Alpha HS 2026.mkv", date = 1_700_000_000),
            video(1, "Beta HS 2026.mkv", date = 1_700_000_100),
            video(1, "Gamma HS 2026.mkv", date = 1_700_000_200),
            video(1, "Delta NF 2025.mkv", date = 1_600_000_000),
            video(1, "Epsilon NF 2025.mkv", date = 1_600_000_100),
            video(1, "Zeta NF 2025.mkv", date = 1_600_000_200),
        )
        // The same titles again in a second chat, as their own posts: presence is what
        // the Top 10 row ranks, and one copy each of anything is no ranking at all.
        val copies = films.map { video(2, it.fileName, it.date) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1), chat(2)),
            loaded = mapOf(1L to films, 2L to copies),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            nowMs = 1_700_000_200_000L,
        )
        val kinds = rows.filterIsInstance<HomeRow.Catalog>().map { it.kind }
        assertTrue("ott-disney-hotstar" in kinds)
        assertTrue("ott-netflix" in kinds)
        assertFalse(kinds.any { it.startsWith("year-") })
        assertTrue("weekly" in kinds)
        assertTrue("top10" in kinds)
        val ott = rows.filterIsInstance<HomeRow.Catalog>().first { it.kind == "ott-netflix" }
        assertEquals(3, ott.all.size)
        assertEquals("Netflix", ott.label)
    }

    @Test
    fun hiddenCategoriesAreLeftOut() {
        val films = listOf(video(1), video(1), video(1), video(1))
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true, hiddenCatalog = setOf("movies", "top10")),
        )
        val kinds = rows.filterIsInstance<HomeRow.Catalog>().map { it.kind }
        assertFalse("movies" in kinds)
        assertFalse("top10" in kinds)
    }

    @Test
    fun moviesAndSeriesShelvesComeFromNameParsingAloneWithNoMetadata() {
        // The first cold start build (v1.0.14) hands in no cached facts at all: a series
        // is still a series because its names parse as episodes, and a file that does
        // not parse as an episode is still a movie candidate, so both shelves stand
        // before any online matching over the pool has been read.
        val films = listOf(
            video(1, "Alpha Peak 2024.mp4"),
            video(1, "Beta Peak 2024.mp4"),
            video(1, "Gamma Peak 2024.mp4"),
        )
        val episodes = (1..3).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films + episodes),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = emptyMap(),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        val movies = catalogs.single { it.kind == "movies" }
        assertEquals(films.map { it.messageId }.toSet(), ids(movies).toSet())
        val series = catalogs.single { it.kind == "series" }
        assertEquals(episodes.map { it.messageId }.toSet(), ids(series).toSet())
        // Once matching completes the same pool is classified by the facts as well:
        // a film the match calls Japanese Animation leaves Movies for Anime, which is
        // the full build's call, unchanged by the early one.
        val meta = HashMap<String, TitleMeta>()
        films.forEach { meta[it.id] = TitleMeta(listOf("Animation"), 8.0, "ja") }
        val matched = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films + episodes),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        val matchedCatalogs = matched.filterIsInstance<HomeRow.Catalog>()
        assertTrue(matchedCatalogs.none { it.kind == "movies" })
        val anime = matchedCatalogs.single { it.kind == "anime" }
        assertEquals(films.map { it.messageId }.toSet(), ids(anime).toSet())
        assertEquals(
            episodes.map { it.messageId }.toSet(),
            ids(matchedCatalogs.single { it.kind == "series" }).toSet(),
        )
    }

    @Test
    fun phoneOrderPutsRecentAboveTop10MoviesAndTheSeriesGroup() {
        val started = video(1, "Started Peak 2024.mp4")
        val films = listOf(
            video(1, "Alpha Peak 2024.mp4"),
            video(1, "Beta Peak 2024.mp4"),
            video(1, "Gamma Peak 2024.mp4"),
        )
        val copies = films.map { video(2, it.fileName) }
        val episodes = (1..2).map { video(1, "Harbour.Notes.S01E%02d.mkv".format(it)) }
        val seoul = (1..2).map { video(1, "Seoul.Diary.S01E%02d.Korean.mkv".format(it)) }
        val saved = video(1, "Saved Peak 2024.mp4")
        val loaded = mapOf(1L to listOf(started) + films + episodes + seoul, 2L to copies)
        val phone = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1), chat(2)),
            loaded = loaded,
            recent = films + copies,
            watchLater = listOf(later(saved)),
            layout = HomeLayout(catalogRows = true),
            phoneOrder = true,
        )
        // Recently added stands right after Continue watching, Watch later and the starred
        // chats, before any shelf; Top 10 leads the shelves, then Movies, then the shows
        // with their language row.
        assertEquals(
            listOf(
                "continue", "watch-later", "chat-1", "chat-2", "recent",
                "catalog-top10", "catalog-movies", "catalog-series", "catalog-series-lang-ko",
            ),
            phone.map { it.key },
        )
        // The same build for a television keeps the order it has always had: the series
        // shelves above Recently added, and the foot shelves in their built order.
        val tv = HomeRows.build(
            continueWatching = listOf(resume(started)),
            favourites = listOf(chat(1), chat(2)),
            loaded = loaded,
            recent = films + copies,
            watchLater = listOf(later(saved)),
            layout = HomeLayout(catalogRows = true),
        )
        assertEquals(
            listOf(
                "continue", "catalog-series", "catalog-series-lang-ko", "recent", "watch-later",
                "chat-1", "chat-2", "catalog-top10", "catalog-movies",
            ),
            tv.map { it.key },
        )
    }

    @Test
    fun animeTitlesStandInTheirOwnShelfAndOutOfMoviesSeriesAndLanguageRows() {
        val animeFilms = listOf(
            video(1, "Sky Song 2024.mp4"),
            video(1, "Sea Song 2024.mp4"),
            video(1, "Star Song 2024.mp4"),
        )
        val toon = video(1, "Western Toon 2024.mp4")
        val plain = listOf(video(1, "Plain One 2024.mp4"), video(1, "Plain Two 2024.mp4"))
        val moon = (1..2).map { video(1, "Moon.Blade.S01E%02d.mkv".format(it)) }
        val sun = (1..2).map { video(1, "Sun.Blade.S01E%02d.mkv".format(it)) }
        val rain = (1..2).map { video(1, "Rain.Blade.S01E%02d.mkv".format(it)) }
        val tokyo = (1..2).map { video(1, "Tokyo.Diary.S01E%02d.mkv".format(it)) }
        val meta = HashMap<String, TitleMeta>()
        animeFilms.forEach { meta[it.id] = TitleMeta(listOf("Animation"), 8.0, "ja") }
        (moon + sun + rain).forEach { meta[it.id] = TitleMeta(listOf("Animation"), 8.0, "ja") }
        meta[toon.id] = TitleMeta(listOf("Animation"), 8.0, "en")
        tokyo.forEach { meta[it.id] = TitleMeta(listOf("Drama"), 8.0, "ja") }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to animeFilms + toon + plain + moon + sun + rain + tokyo),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        val anime = catalogs.single { it.kind == "anime" }
        assertEquals("", anime.label)
        // Films and shows together: three films and three shows.
        assertEquals(6, anime.all.size)
        assertTrue(animeFilms.all { it.messageId in ids(anime) })
        assertTrue((moon + sun + rain).all { it.messageId in ids(anime) })
        // Movies keeps the non-Japanese Animation title and the plain films, and no anime.
        val movies = catalogs.single { it.kind == "movies" }
        assertEquals((listOf(toon) + plain).map { it.messageId }.toSet(), ids(movies).toSet())
        assertFalse(toon.messageId in ids(anime))
        // The Series row and its Japanese language row hold only the drama.
        val series = catalogs.single { it.kind == "series" }
        assertEquals(tokyo.map { it.messageId }.toSet(), ids(series).toSet())
        val japanese = catalogs.single { it.kind == "series-lang-ja" }
        assertEquals(1, japanese.all.size)
        assertEquals(tokyo.map { it.messageId }.toSet(), ids(japanese).toSet())
        // No mixed language shelf holds an anime title either.
        val langRows = catalogs.filter { it.kind.startsWith("lang-") }
        assertTrue(langRows.none { row -> (animeFilms + moon + sun + rain).any { it.messageId in ids(row) } })
    }

    @Test
    fun anAnimeCatalogueMatchCountsAsAnimeWhateverItsGenresSay() {
        // An AniList match (v1.0.13): its genres are AniList's own (Action, Adventure) with no
        // Animation among them and no language read, and the match alone marks the title anime.
        val films = listOf(
            video(1, "Paper Cranes 2024.mp4"),
            video(1, "Paper Boats 2024.mp4"),
            video(1, "Paper Moons 2024.mp4"),
        )
        val meta = HashMap<String, TitleMeta>()
        films.forEach { meta[it.id] = TitleMeta(genres = listOf("Action", "Adventure"), anime = true) }
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            titleMeta = meta,
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        val anime = catalogs.single { it.kind == "anime" }
        assertEquals(3, anime.all.size)
        assertTrue(catalogs.none { it.kind == "movies" })
    }

    @Test
    fun anUnmatchedTitleWithAnimeInItsNameCountsAsAnime() {
        val named = listOf(
            video(1, "Star Anime Tale 2024.mp4"),
            video(1, "Moon Anime Tale 2024.mp4"),
            video(1, "Rain Anime Tale 2024.mp4"),
        )
        val glued = video(1, "Animefest Nights 2024.mp4")
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to named + glued),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        val anime = catalogs.single { it.kind == "anime" }
        assertEquals(named.map { it.messageId }.toSet(), ids(anime).toSet())
        // The word glued into a longer one is no signal: that film is in no anime shelf,
        // and with the anime titles out, Movies has too little left to stand at all.
        assertFalse(glued.messageId in ids(anime))
        assertTrue(catalogs.none { it.kind == "movies" })
    }

    @Test
    fun noYearShelfIsBuiltHoweverManyTitlesShareAYear() {
        val films = listOf(
            video(1, "Alpha 2024.mp4"),
            video(1, "Beta 2024.mp4"),
            video(1, "Gamma 2024.mp4"),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
        )
        val catalogs = rows.filterIsInstance<HomeRow.Catalog>()
        assertTrue(catalogs.none { it.kind.startsWith("year-") })
        // The titles themselves are untouched: they stand in Movies as before.
        val movies = catalogs.single { it.kind == "movies" }
        assertEquals(films.map { it.messageId }.toSet(), ids(movies).toSet())
    }

    @Test
    fun aStoredYearCategoryReferenceIsSkippedGracefully() {
        // A layout stored while the year shelf existed can still name its group, in the
        // hidden set or in the viewer's order: the build must not crash or wedge on it,
        // and the shelves that remain keep their places.
        val films = listOf(
            video(1, "Alpha 2024.mp4"),
            video(1, "Beta 2024.mp4"),
            video(1, "Gamma 2024.mp4"),
            video(1, "Delta 2024.mp4"),
        )
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to films),
            recent = emptyList(),
            layout = HomeLayout(
                catalogRows = true,
                hiddenCatalog = setOf("year"),
                catalogOrder = listOf("year", "movies"),
            ),
        )
        val kinds = rows.filterIsInstance<HomeRow.Catalog>().map { it.kind }
        assertTrue("movies" in kinds)
        assertFalse(kinds.any { it.startsWith("year-") })
    }

    @Test
    fun `the pinned chat leads the row chats, listed once`() {
        val chats = listOf(chat(1), chat(2), chat(3))
        assertEquals(listOf(3L, 1L), HomeRows.rowChats(chats, setOf(1L, 3L), pinnedChat = 3L).map { it.id })
        assertEquals(listOf(2L, 1L), HomeRows.rowChats(chats, setOf(1L), pinnedChat = 2L).map { it.id })
        assertEquals(listOf(1L), HomeRows.rowChats(chats, setOf(1L)).map { it.id })
        assertEquals(listOf(1L), HomeRows.rowChats(chats, setOf(1L), pinnedChat = 99L).map { it.id })
        assertEquals(emptyList<Long>(), HomeRows.rowChats(chats, emptySet(), pinnedChat = 0L).map { it.id })
    }

    @Test
    fun `cam copies leave the shelves and continue watching while the switch is on`() {
        val cam = video(1, "Alpha 2024 PreDVD.mp4")
        val cleanAudio = video(1, "Beta 2024 HDTS Clean Audio.mp4")
        val plain = video(1, "Gamma 2024.mp4")
        val inProgressCam = resume(video(1, "Delta 2024 DVDScr.mp4"))
        val inProgressPlain = resume(plain)
        val rows = HomeRows.build(
            continueWatching = listOf(inProgressCam, inProgressPlain),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(cam, cleanAudio, plain)),
            recent = emptyList(),
            hideCamCopies = true,
        )
        val shown = rows.flatMap(::ids)
        assertFalse(cam.messageId in shown)
        assertFalse(cleanAudio.messageId in shown)
        assertFalse(inProgressCam.messageId in shown)
        assertTrue(plain.messageId in shown)
        // Off, everything stands as it did before the switch reached Home.
        val unhidden = HomeRows.build(
            continueWatching = listOf(inProgressCam, inProgressPlain),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(cam, cleanAudio, plain)),
            recent = emptyList(),
            hideCamCopies = false,
        )
        val all = unhidden.flatMap(::ids)
        assertTrue(cam.messageId in all)
        assertTrue(inProgressCam.messageId in all)
    }
}
