package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One film or show once: [SameTitle], the carousel ([HomeFeatured]) and Home's rows ([HomeRows]). */
class SameTitleTest {

    private fun video(
        chatId: Long,
        messageId: Long,
        fileName: String,
        caption: String = "",
        date: Int = messageId.toInt(),
        duration: Int = 8_000,
        onDevice: Boolean = false,
    ) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = (chatId * 100 + messageId).toInt(),
        title = fileName,
        sizeBytes = 1_500 * SizeFilter.MB,
        durationSec = duration,
        mimeType = "video/x-matroska",
        thumbnailFileId = 7,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
        onDevice = onDevice,
    )

    private val noOnline: (MediaItem) -> String? = { null }

    @Test
    fun `a film's name and year come from the caption when the file name has none`() {
        assertEquals("film:khalifa|2026", SameTitle.filmKey(video(1, 1, "Khalifa.mkv", caption = "Khalifa (2026)")))
        assertEquals("film:khalifa|2026", SameTitle.filmKey(video(2, 2, "Khalifa.2026.1080p.WEB-DL.mkv")))
        // No year anywhere: a name alone never folds two videos.
        assertNull(SameTitle.filmKey(video(3, 3, "Lecture 1080p.mkv")))
        // An episode is not a film.
        assertNull(SameTitle.filmKey(video(4, 4, "Harbour.Notes.S01E02.mkv")))
    }

    @Test
    fun `the same film in two chats is one tile, the best copy leading`() {
        val entries = listOf(
            ShelfEntry.File(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300)),
            ShelfEntry.File(video(2, 11, "Night.Train.2019.1080p.mkv", date = 200)),
            ShelfEntry.File(video(3, 12, "Khalifa 2026 2160p.mkv", date = 100)),
        )
        val folded = SameTitle.dedupe(entries, noOnline)
        assertEquals(2, folded.size)
        val film = (folded.first() as ShelfEntry.File).item
        // Where the newest copy stood, led by the 4K copy, with both as its copies.
        assertEquals(12L, film.messageId)
        assertEquals(setOf(10L, 12L), film.versions.map { it.messageId }.toSet())
    }

    @Test
    fun `a detail opened in one chat gathers the copies the other chats hold`() {
        // Owner, v1.0.14 (item 34): the same widening a Search opening arrives with.
        val opened = video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300)
        val elsewhere = video(3, 12, "Khalifa 2026 2160p.mkv", date = 100)
        val otherFilm = video(2, 11, "Night.Train.2019.1080p.mkv", date = 200)
        // A same named trailer is no copy: the film evidence asks for about the same length.
        val trailer = video(4, 13, "Khalifa.2026.Trailer.mkv", duration = 60)
        val widened = SameTitle.withPoolCopies(opened, listOf(elsewhere, otherFilm, trailer))
        assertEquals(setOf(10L, 12L), widened.versions.map { it.messageId }.toSet())
        // Nothing more in the pool: the item stands exactly as opened.
        assertEquals(opened, SameTitle.withPoolCopies(opened, listOf(otherFilm)))
        assertEquals(opened, SameTitle.withPoolCopies(opened, emptyList()))
    }

    @Test
    fun `a page opened on a joined copy gathers the film's ordinary copies`() {
        // Owner, v1.0.33: the page stood at 2 versions while Search counted 93 copies. The
        // joined copy keyed as its first piece, so the film's single-file copies never
        // folded in, and its length was its first piece's, which the same-length test
        // also refused against a whole copy.
        val pieceOne = video(1, 40, "Thallumaala.2022.720p.part001.mkv", duration = 3_700)
        val pieceTwo = video(1, 41, "Thallumaala.2022.720p.part002.mkv", duration = 3_700)
        val joined = SplitPieces.joined(listOf(pieceOne, pieceTwo))
        assertEquals("film:thallumaala|2022", SameTitle.filmKey(joined))
        assertEquals(7_400, joined.durationSec)
        val single = video(2, 42, "Thallumaala (2022) 1080p.mkv", duration = 7_440)
        val widened = SameTitle.withPoolCopies(joined, listOf(single))
        assertEquals(setOf(40L, 42L), widened.versions.map { it.messageId }.toSet())
        // The other way round too: a page on the single file gathers the joined set.
        val fromSingle = SameTitle.withPoolCopies(single, listOf(joined))
        assertEquals(setOf(40L, 42L), fromSingle.versions.map { it.messageId }.toSet())
        // A lone piece posted elsewhere is still no copy of the whole film.
        val lonePiece = video(9, 49, "Thallumaala.2022.720p.part001.mkv", duration = 3_700)
        assertEquals(joined, SameTitle.withPoolCopies(joined, listOf(lonePiece)))
    }

    @Test
    fun `a detail page gathers a copy the names alone do not tie, by their online match`() {
        // Owner, v1.0.33: a copy whose file name carries no year folds in Search by its
        // online match; the detail page's widening asked the name alone and missed it.
        val opened = video(1, 50, "Khalifa.2026.1080p.mkv")
        val yearless = video(2, 51, "KLF_final_cut.mkv")
        val matched: (MediaItem) -> String? = { "tmdb:film:42" }
        val widened = SameTitle.withPoolCopies(opened, listOf(yearless), matched)
        assertEquals(setOf(50L, 51L), widened.versions.map { it.messageId }.toSet())
        // No online match known: the yearless name still does not fold.
        assertEquals(opened, SameTitle.withPoolCopies(opened, listOf(yearless), noOnline))
        // A different film matched elsewhere does not fold either.
        val other: (MediaItem) -> String? = { item -> if (item.messageId == 51L) "tmdb:film:99" else "tmdb:film:42" }
        assertEquals(opened, SameTitle.withPoolCopies(opened, listOf(yearless), other))
    }

    @Test
    fun `an episode gathers its other encodes across chats, and no other episode`() {
        val opened = video(1, 20, "Harbour.Notes.S01E02.720p.mkv")
        val encode = video(2, 21, "Harbour Notes S01E02 1080p.mkv")
        val next = video(2, 22, "Harbour.Notes.S01E03.1080p.mkv")
        val widened = SameTitle.withPoolCopies(opened, listOf(encode, next))
        assertEquals(setOf(20L, 21L), widened.versions.map { it.messageId }.toSet())
    }

    @Test
    fun `a combined pack is no copy of its first episode, and its own posts fold together`() {
        // Owner, 2026-11-10: the E01 versions listed the EP(01-04) pack files as
        // copies of episode one. A range file keys with its span, so it widens
        // only its own range's posts, never a single episode's list.
        val opened = video(1, 30, "Prince.of.Mollywood.S01E01.The.Dreamer.720p.mkv")
        val pack = video(2, 31, "Prince of Mollywood (2026) S01 EP(01-04) combined.mkv")
        val packAgain = video(3, 32, "Prince of Mollywood S01 EP(01-04) combined 1080p.mkv")
        assertEquals(opened, SameTitle.withPoolCopies(opened, listOf(pack, packAgain)))
        val widenedPack = SameTitle.withPoolCopies(pack, listOf(opened, packAgain))
        assertEquals(setOf(31L, 32L), widenedPack.versions.map { it.messageId }.toSet())
        // A plain episode's copy key carries no span, as before.
        assertEquals(
            SeriesShelf.sameVideoKey(video(1, 33, "Harbour.Notes.S01E02.720p.mkv")),
            SeriesShelf.sameVideoKey(video(2, 34, "Harbour Notes S01E02 1080p.mkv")),
        )
    }

    @Test
    fun `one online match is one tile whatever the names say`() {
        val entries = listOf(
            ShelfEntry.File(video(1, 1, "KLF_final_cut.mkv")),
            ShelfEntry.File(video(2, 2, "Khalifa.2026.720p.mkv")),
        )
        val folded = SameTitle.dedupe(entries) { "tmdb:film:42" }
        assertEquals(1, folded.size)
        assertEquals(2, (folded.single() as ShelfEntry.File).item.versions.size)
    }

    @Test
    fun `the carousel shows a film once, posted twice without a shared file name`() {
        val picks = HomeFeatured.pick(
            listOf(
                ShelfEntry.File(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 500)),
                ShelfEntry.File(video(2, 11, "Night.Train.2019.1080p.mkv", date = 400)),
                ShelfEntry.File(video(3, 12, "Khalifa (2026) 1080p.mkv", date = 300)),
                ShelfEntry.File(video(4, 13, "Ledger.of.Small.Hours.2021.mkv", date = 200)),
            ),
            online = noOnline,
        )
        val titles = picks.map { SameTitle.filmKey(it.item) }
        assertEquals(listOf("film:khalifa|2026", "film:night train|2019", "film:ledger of small hours|2021"), titles)
    }

    @Test
    fun `the desktop carousel does not repeat a film from Continue watching under another name`() {
        val fresh = HomeFeatured.pick(listOf(ShelfEntry.File(video(1, 10, "Khalifa 2026 1080p.mkv"))), online = noOnline)
        val going = listOf(video(2, 20, "Khalifa.mkv", caption = "Khalifa (2026)"))
        val mixed = HomeFeatured.mix(fresh, going, complete = { true }, matched = noOnline)
        assertEquals(1, mixed.size)
    }

    @Test
    fun `Home's Recently added lists a film once`() {
        val chat = ChatSummary(1, "Films", null, 0, ChatKind.Channel)
        val other = ChatSummary(2, "More films", null, 0, ChatKind.Channel)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat, other),
            loaded = mapOf(
                1L to listOf(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300), video(1, 11, "Night.Train.2019.mkv", date = 200)),
                2L to listOf(video(2, 12, "Khalifa.2026.1080p.mkv", date = 100)),
            ),
            recent = emptyList(),
            autoFill = true,
        )
        val movies = rows.filterIsInstance<HomeRow.Recent>().single()
        val keys = movies.entries.map { SameTitle.filmKey((it as ShelfEntry.File).item) }
        assertEquals(keys.distinct(), keys)
        assertTrue("film:khalifa|2026" in keys)
    }

    @Test
    fun `a row read on past its tiles folds the copies it finds into the tile already there`() {
        val lead = video(1, 10, "Khalifa.2026.1080p.WEB-DL.mkv", date = 300)
        val row = HomeRow.Recent(listOf(ShelfEntry.File(lead)), loaded = true, chatIds = listOf(1L, 2L))
        // The next pages bring the same film from another chat, smaller, and from the same chat at 720p.
        val read = listOf(
            video(2, 11, "Khalifa (2026) 720p.mkv", date = 200).copy(sizeBytes = 700 * SizeFilter.MB),
            video(1, 12, "Khalifa.2026.2160p.mkv", date = 100).copy(sizeBytes = 9_000 * SizeFilter.MB),
            video(2, 13, "Night.Train.2019.1080p.mkv", date = 50),
        )
        val shown = HomeRows.seeAll(row, read)!!
        assertEquals(2, shown.size)
        val film = (shown.first() as ShelfEntry.File).item
        assertEquals(setOf(10L, 11L, 12L), film.versions.map { it.messageId }.toSet())
    }

    @Test
    fun `a fold leads with the built-in channel's post, and keeps every copy`() {
        BuiltInChannels.jMoviesChatId = 42L
        try {
            val entries = listOf(
                ShelfEntry.File(video(7, 20, "Khalifa 2026 2160p.mkv", date = 100)),
                ShelfEntry.File(video(42, 21, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300)),
            )
            val folded = SameTitle.dedupe(entries, noOnline)
            val film = (folded.single() as ShelfEntry.File).item
            assertEquals(42L, film.chatId)
            assertEquals(21L, film.versions.first().messageId)
            assertEquals(setOf(20L, 21L), film.versions.map { it.messageId }.toSet())
        } finally {
            BuiltInChannels.jMoviesChatId = null
        }
    }

    @Test
    fun `the canonical reading ties a film's many spellings together`() {
        val first = SameTitle.Canon("kgf", 2018, 1)
        assertEquals(first, SameTitle.canonOf("kgf chapter 1", 2018))
        assertEquals(first, SameTitle.canonOf("kgf 1", 2018))
        assertEquals(first, SameTitle.canonOf("tg kgf chapter 1", 2018))
        // No part stated reads as the first part, and no year stated still matches.
        assertTrue(first.matches(SameTitle.canonOf("kgf", 2018)!!))
        assertTrue(first.matches(SameTitle.canonOf("kgf chapter 1", null)!!))
        val second = SameTitle.Canon("kgf", 2022, 2)
        assertEquals(second, SameTitle.canonOf("kgf chapter 2", 2022))
        assertEquals(second, SameTitle.canonOf("k g f chapter 2 kgf 2", 2022))
        assertEquals(second, SameTitle.canonOf("www 1tamilmv fans kgf 2", 2022))
        // The parts are different films, and another year is a remake, not a copy.
        assertTrue(!first.matches(second))
        assertTrue(!first.matches(SameTitle.Canon("kgf", 2022, 1)))
        // A name that is only a part marker names no film at all.
        assertNull(SameTitle.canonOf("chapter 2", 2022))
        // A title's own number is kept: "Apollo 13" is one film under its whole name.
        assertEquals(SameTitle.Canon("apollo 13", 1995, null), SameTitle.canonOf("apollo 13", 1995))
    }

    @Test
    fun `a detail page gathers a film's copies under every spelling`() {
        val page = video(1, 1, "Animal (2023) Hindi 1080p.mkv", duration = 9_000)
        val pool = listOf(
            page,
            video(2, 2, "Animal.2023.720p.WEB-DL.mkv", duration = 9_100),
            video(3, 3, "Animal Hindi Dubbed (2023).mkv", duration = 8_900),
            video(6, 6, "Animal Chapter 1 (2023).mkv", duration = 9_050),
            video(7, 7, "www 1TamilMV fans Animal (2023).mkv", duration = 8_950),
            video(4, 4, "Animal Part 2 (2023).mkv", duration = 9_000),
            video(5, 5, "Animal (2019).mkv", duration = 9_000),
        )
        val widened = SameTitle.withPoolCopies(page, pool, noOnline)
        // The dotted, dubbed, chapter spelled and site prefixed copies fold in; the
        // sequel and the other year's namesake do not.
        assertEquals(setOf(1L, 2L, 3L, 6L, 7L), widened.versions.map { it.messageId }.toSet())
    }
}
