package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name shapes real chats post (with made-up titles), read one at a time. */
class NameEdgeCasesTest {

    private fun parse(name: String, caption: String? = null) = MediaName.parse(name, caption, maxYear = 2027)

    @Test
    fun `every name in the shared corpus reads as it should`() {
        val wrong = NameCorpus.CASES.mapNotNull { case ->
            val got = parse(case.fileName, case.caption)
            val expected = listOf(case.title, case.year, case.season, case.episode, case.part, case.extra, case.airDate)
            val actual = listOf(got.title, got.year, got.season, got.episode, got.part, got.extra, got.airDate)
            if (expected == actual) null else "${case.fileName}\n  expected $expected\n  got      $actual"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `a film with a number in its name is never an episode`() {
        for (name in listOf("Siren Patrol 100.mp4", "Harbour Lights Part 2 (2019).mkv", "Harbour Lights Chapter 3 (2021).mkv", "Coast Run 2 2014 720p.mkv", "Tide 1080.mkv", "1944.1080p.mkv")) {
            val parsed = parse(name)
            assertFalse(name, parsed.isSeries)
            assertNull(name, parsed.episode)
            assertNull(name, parsed.season)
        }
    }

    @Test
    fun `a year that opens the name is the title, not the year`() {
        assertEquals("1944", parse("1944.mkv").title)
        assertNull(parse("1944.mkv").year)
        assertEquals("2019 Highlights", parse("2019 Highlights.mp4").title)
        assertNull(parse("2019 Highlights.mp4").year)
        // A bracketed year in front is still a year.
        assertEquals(2014, parse("[2014] Garden Notes 1080p.mkv").year)
    }

    @Test
    fun `a sequel's Part 2 is title, a split file's is a piece`() {
        assertNull(parse("Harbour Lights Part 2 (2019).mkv").part)
        assertEquals(2, parse("Harbour Lights (2019) 720p Part 2.mkv").part)
        assertEquals(2, parse("Harbour Lights (2019) 720p Part 2.mkv").let { it.part })
        assertEquals("Harbour Lights", parse("Harbour Lights (2019) 720p Part 2.mkv").title)
        assertEquals(1, parse("Harbour.Lights.2019.Disc.1.mkv").part)
        // An episode's "Part 2" is the episode's.
        assertNull(parse("Harbour Notes S01E02 Part 2.mkv").part)
        // Twenty pieces is not a split film.
        assertNull(parse("Workshop CD20.mkv").part)
    }

    @Test
    fun `extras need the word to end the title`() {
        assertEquals(ExtraKind.Promo, parse("Coast Run Promo.mp4").extra)
        assertEquals(ExtraKind.Sample, parse("Coast.Run.2014.1080p.Sample.mkv").extra)
        assertNull(parse("The Sample Room (2020).mkv").extra)
        assertNull(parse("Promo Nights Live.mp4").extra)
        // In the script a Tamil channel writes it in.
        assertEquals(ExtraKind.Trailer, parse("Coast Run (2014) ட்ரெய்லர்.mp4").extra)
    }

    @Test
    fun `an outright episode number beats the word promo`() {
        // The owner's own file (2026-11-08): a full episode whose own title carries the word.
        // Telegram's search in its chat finds it, but the series listed E25 then E27 while the
        // word marked the file an extra and grouping left it out.
        val parsed = parse("Badai.Bungalow.S01E26.Ring.Master.promo.1080p.HS.WEB-DL.AAC2.0.H.264-RUBEK.mkv")
        assertNull(parsed.extra)
        assertEquals("Badai Bungalow", parsed.title)
        assertEquals(1, parsed.season)
        assertEquals(26, parsed.episode)
        // No episode number: the word still marks a true extra.
        assertEquals(ExtraKind.Promo, parse("Badai Bungalow Promo.mkv").extra)
        // The other extra words keep their say over an episode number.
        assertEquals(ExtraKind.Trailer, parse("Badai Bungalow S01E26 Trailer.mkv").extra)
    }

    @Test
    fun `languages after the title are read, a language that is the title is not`() {
        assertEquals(listOf("ta", "hi"), MediaName.languages("Coast Run 2014 Tamil Hindi 720p.mkv"))
        assertEquals(listOf("ml"), MediaName.languages("Coast_Run_(2014)_Malayalam_Dubbed.mkv"))
        assertEquals(listOf("ta", "te", "hi"), MediaName.languages("Coast Run 2014 [Tam + Tel + Hin] 720p.mkv"))
        assertEquals(emptyList<String>(), MediaName.languages("Tamil Lessons 01.mp4"))
        assertEquals(listOf("ta"), MediaFacts.read("Coast Run (2014) Tamil 720p.mkv").languages)
    }

    @Test
    fun `an air date makes an episode, never a film`() {
        val parsed = parse("Morning Desk 2024.10.14 720p.mp4")
        assertTrue(parsed.isSeries)
        assertEquals("2024-10-14", parsed.airDate)
        assertFalse(SeriesShelf.isFilm(video("Morning Desk 2024.10.14 720p.mp4")))
    }

    @Test
    fun `empty, decorated and camera names read safely`() {
        assertEquals("", parse("").title)
        assertNull(MediaName.tileTitle(""))
        assertEquals("", parse("🔥🔥.mp4").title.trim { !it.isLetterOrDigit() })
        assertTrue(MediaMapper.isGenericName("VID_20241013_120000.mp4"))
        assertFalse(SeriesShelf.isFilm(video("VID_20241013_120000.mp4")))
        assertNull(SeriesShelf.sameVideoKey(video("VID_20241013_120000.mp4")))
        // A name that is only a caption's.
        val captioned = video("", caption = "Night Lantern (2022) Tamil HD")
        assertEquals("Night Lantern", SeriesShelf.episodeOf(captioned).title)
        assertEquals(2022, SeriesShelf.episodeOf(captioned).year)
    }

    @Test
    fun `titles in other scripts keep their vowel signs in the key`() {
        val tamil = "இரவு நிலா"
        assertEquals(tamil, SeriesShelf.keyOf(tamil))
        assertEquals("ليلة القمر", SeriesShelf.keyOf("ليلة القمر:"))
    }

    @Test
    fun `tile titles for the caption shapes and the cast bracket`() {
        assertEquals("Paper Kite", SeriesShelf.tileTitle(video("Paper Kite Full Movie HD | Arun Devan.mp4")))
        assertEquals("Velvet Hour", SeriesShelf.tileTitle(video("Velvet Hour [ Arun Devan, Mira Joshi ].mp4")))
        // A bracket with a number in it stays.
        assertTrue(SeriesShelf.tileTitle(video("Workshop [Part 1].mp4")).startsWith("Workshop [Part 1"))
        assertEquals("Siren Patrol 100", SeriesShelf.tileTitle(video("Siren Patrol 100.mp4")))
    }

    private var nextId = 1L

    private fun video(fileName: String, caption: String = "") = MediaItem(
        chatId = 9,
        messageId = nextId++,
        fileId = 0,
        title = fileName.ifBlank { caption.lineSequence().firstOrNull().orEmpty() },
        sizeBytes = 1,
        durationSec = 0,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = fileName,
        caption = caption,
    )
}
