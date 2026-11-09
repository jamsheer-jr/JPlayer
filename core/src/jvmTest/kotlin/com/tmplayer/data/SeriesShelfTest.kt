package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesShelfTest {

    private var nextId = 1L

    /** A video as a chat lists it; [date] counts up so later files are newer. */
    private fun video(fileName: String, caption: String = "", date: Int = nextId.toInt(), size: Long = 1) = MediaItem(
        chatId = 7,
        messageId = nextId++,
        fileId = 0,
        title = fileName.ifBlank { caption.lineSequence().firstOrNull().orEmpty() },
        sizeBytes = size,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
    )

    private fun shows(entries: List<ShelfEntry>) = entries.filterIsInstance<ShelfEntry.Show>().map { it.series }
    private fun files(entries: List<ShelfEntry>) = entries.filterIsInstance<ShelfEntry.File>().map { it.item.fileName }

    @Test
    fun `a split film is watched by its last piece`() {
        val cd2 = video("Coast Run 2014 720p CD2.avi", size = 700L * 1024 * 1024)
        val cd1 = video("Coast Run 2014 720p CD1.avi", size = 700L * 1024 * 1024)
        val film = SeriesShelf.arrange(listOf(cd2, cd1)).filterIsInstance<ShelfEntry.File>().single().item
        assertEquals(listOf(cd1.id, cd2.id), film.parts.map { it.id })
        assertEquals(cd2.id, film.watchedBy.id)
        assertEquals(cd2.id, cd2.watchedBy.id)
    }

    @Test
    fun `a file of two episodes ticks both when it is watched`() {
        val double = video("Harbour.Notes.S01E01E02.1080p.mkv")
        val e02 = video("Harbour.Notes.S01E02.720p.mkv")
        val e03 = video("Harbour.Notes.S01E03.1080p.mkv")
        val show = shows(SeriesShelf.arrange(listOf(e03, e02, double))).single()
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        val second = show.episodes[1]
        assertEquals(listOf(double.id), second.alsoIn.map { it.id })
        val finished: (MediaItem) -> Boolean = { it.id == double.id }
        assertTrue(second.watched(finished))
        val progress = SeriesShelf.progress(show, finished, { 0f })
        assertEquals(2, progress.watched)
        assertEquals(3, progress.next?.episode)
    }

    @Test
    fun `every way of writing one show lands on the same key`() {
        val key = SeriesShelf.keyOf("Harbour Notes")
        assertEquals("harbour notes", key)
        assertEquals(key, SeriesShelf.keyOf("HARBOUR NOTES:"))
        assertEquals(key, SeriesShelf.keyOf("  Harbour  Notes "))
        assertEquals(key, SeriesShelf.keyOf("Harbour-Notes"))
        assertEquals("", SeriesShelf.keyOf(" - "))
    }

    @Test
    fun `files from several naming styles fold into one show`() {
        val items = listOf(
            video("Harbour.Notes.S01E02.1080p.WEB-DL.mkv"),
            video("Harbour Notes 1x01 720p.mkv"),
            video("harbour_notes_720p.mkv", caption = "Harbour Notes S01E03\nJoin the channel"),
            video("City.Archive.1999.1080p.BluRay.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val show = shows(entries).single()
        assertEquals("harbour notes", show.key)
        assertEquals("Harbour Notes", show.title)
        assertEquals(3, show.episodeCount)
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        assertEquals(listOf("City.Archive.1999.1080p.BluRay.mkv"), files(entries))
    }

    @Test
    fun `a brand tag before the name folds into the bare show`() {
        val items = listOf(
            video("Vikings.Valhalla.S01E01.720p.mkv"),
            video("Vikings.Valhalla.S01E02.720p.mkv"),
            video("KC Vikings Valhalla S01E01 720p.mkv"),
            video("KC Vikings Valhalla S01E02 720p.mkv"),
            video("MM Vikings Valhalla S01E01 720p.mkv"),
            video("MM Vikings Valhalla S01E02 720p.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals("vikings valhalla", show.key)
        assertEquals(2, show.episodeCount)
        assertEquals(3, show.episodes[0].copies.size)
        assertEquals(3, show.episodes[1].copies.size)
    }

    @Test
    fun `a branded show with no bare show stands as its own show`() {
        val items = listOf(
            video("KC Vikings Valhalla S01E01 720p.mkv"),
            video("KC Vikings Valhalla S01E02 720p.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals("kc vikings valhalla", show.key)
        assertEquals(2, show.episodeCount)
    }

    @Test
    fun `a sequel named after its parent stays apart from it`() {
        val items = listOf(
            video("Vikings.S01E01.720p.mkv"),
            video("Vikings.S01E02.720p.mkv"),
            video("Vikings.Valhalla.S01E01.720p.mkv"),
            video("Vikings.Valhalla.S01E02.720p.mkv"),
        )
        val keys = shows(SeriesShelf.arrange(items)).map { it.key }
        assertEquals(listOf("vikings", "vikings valhalla"), keys.sorted())
    }

    @Test
    fun `a title word before the name is no brand tag`() {
        val items = listOf(
            video("Star.Wars.S01E01.720p.mkv"),
            video("Star.Wars.S01E02.720p.mkv"),
            video("Lego Star Wars S01E01 720p.mkv"),
            video("Lego Star Wars S01E02 720p.mkv"),
        )
        val keys = shows(SeriesShelf.arrange(items)).map { it.key }
        assertEquals(listOf("lego star wars", "star wars"), keys.sorted())
    }

    @Test
    fun `seasons come out in order with their episodes in order`() {
        val items = listOf(
            video("Studio.Sessions.S02E02.mkv"),
            video("Studio.Sessions.S01E10.mkv"),
            video("Studio.Sessions.S02E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Studio.Sessions.S01E01.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 10), show.seasons[0].episodes.map { it.episode })
        assertEquals(listOf(1, 2), show.seasons[1].episodes.map { it.episode })
        assertEquals(listOf("S01E01", "S01E02", "S01E10", "S02E01", "S02E02"), show.episodes.map { it.code })
    }

    @Test
    fun `a show without seasons is season one`() {
        val items = listOf(
            video("[SubsPlease] Sky Garden - 02 (1080p).mkv"),
            video("[SubsPlease] Sky Garden - 01 (1080p).mkv"),
            video("Sky Garden Ep 03.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
    }

    @Test
    fun `two copies of one episode are one episode, the newer copy first`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.720p.mkv", date = 10),
            video("Studio.Sessions.S01E01.1080p.mkv", date = 20),
            video("Studio.Sessions.S01E02.1080p.mkv", date = 15),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(2, show.episodeCount)
        assertEquals(listOf("Studio.Sessions.S01E01.1080p.mkv", "Studio.Sessions.S01E02.1080p.mkv"), show.episodes.map { it.item.fileName })
        assertEquals(
            listOf("Studio.Sessions.S01E01.1080p.mkv", "Studio.Sessions.S01E01.720p.mkv"),
            show.episodes.first().copies.map { it.fileName },
        )
        assertEquals("Studio.Sessions.S01E01.1080p.mkv", show.cover.fileName)
    }

    @Test
    fun `copies of one episode alone are not a show`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.720p.mkv"),
            video("Studio.Sessions.S01E01.1080p.mkv"),
        )
        assertTrue(shows(SeriesShelf.arrange(items)).isEmpty())
    }

    // ---- CP27 on a real chat: "Family Full House", every episode in three sizes ----------------

    private val mb = 1024L * 1024

    /** Episodes 1 to 4 at 354 MB, 599 MB and 1.8 GB, all under the same file name, as posted. */
    private fun familyFullHouse(): List<MediaItem> = (1..4).flatMap { n ->
        val name = "Family_Full_House_With_Rohit_Sharma_S01E0${n}_Mumbai_Punters_vs_Team.mp4"
        listOf(
            video(name, size = 354 * mb + n),
            video(name, size = 599 * mb + n),
            video(name, size = 1_843 * mb + n),
        )
    }.reversed()

    private fun family(entries: List<ShelfEntry>) = shows(entries).single { it.key.startsWith("family full house") }

    @Test
    fun `every size of an episode is one row, and the count is of episodes`() {
        val show = family(SeriesShelf.arrange(familyFullHouse()))
        assertEquals(4, show.episodeCount)
        assertEquals(listOf("S01E01", "S01E02", "S01E03", "S01E04"), show.episodes.map { it.code })
        assertTrue(show.episodes.all { it.copies.size == 3 })
        assertEquals(listOf(4), show.seasons.map { it.episodes.size })
    }

    @Test
    fun `one copy watched marks the episode, and up next is the next episode in the same size`() {
        val items = familyFullHouse()
        val show = family(SeriesShelf.arrange(items))
        val watchedCopy = show.episodes.first().copies.single { it.sizeBytes / mb == 354L }
        val p = SeriesShelf.progress(show, { it.id == watchedCopy.id }, { 0f })
        assertEquals(1, p.watched)
        assertEquals(4, p.total)
        assertEquals("S01E02", p.next?.code)
        assertEquals(354L, p.next!!.item.sizeBytes / mb)
        assertEquals(watchedCopy.id, p.like?.id)
    }

    @Test
    fun `a copy partway through is the one to carry on with`() {
        val show = family(SeriesShelf.arrange(familyFullHouse()))
        val e2 = show.episodes[1]
        val big = e2.copies.single { it.sizeBytes / mb == 1_843L }
        val p = SeriesShelf.progress(show, { false }, { if (it.id == big.id) 0.3f else 0f })
        assertEquals("S01E02", p.next?.code)
        assertEquals(big.id, p.next!!.item.id)
        // And the episode after it is offered in that size too.
        assertEquals(1_843L, SeriesShelf.pick(show.episodes[2], { false }, { 0f }, p.like).sizeBytes / mb)
    }

    @Test
    fun `saving data picks the episodes lightest adequate copy, a partway copy still first`() {
        val hd = video("Show.S01E01.1080p.mkv", size = 1_800L * 1024 * 1024)
        val sd = video("Show.S01E01.720p.mkv", size = 900L * 1024 * 1024)
        val light = video("Show.S01E01.360p.mkv", size = 200L * 1024 * 1024)
        val episode = SeriesEpisode(item = hd, season = 1, episode = 1, copies = listOf(hd, sd, light))
        // Like for like without the saving: the copy most like the 1080p one watched last.
        assertEquals(hd.id, SeriesShelf.pick(episode, { false }, { 0f }, like = hd).id)
        // Saving: the 360p copy, whatever was watched last.
        assertEquals(light.id, SeriesShelf.pick(episode, { false }, { 0f }, like = hd, saveData = true).id)
        // A copy partway through is the resume itself and wins over the saving.
        assertEquals(hd.id, SeriesShelf.pick(episode, { false }, { if (it.id == hd.id) 0.4f else 0f }, like = null, saveData = true).id)
    }

    @Test
    fun `episode 5 named in a bare E5 joins the show the chat established`() {
        val items = familyFullHouse() + video("Family Full House With Rohit Sharma \u2014 E5. Vlog.mp4", size = 475 * mb)
        val entries = SeriesShelf.arrange(items)
        val show = family(entries)
        assertEquals(5, show.episodeCount)
        assertEquals("S01E05", show.episodes.last().code)
        assertTrue(files(entries).isEmpty())
        assertEquals("Family Full House With Rohit Sharma", show.title)
    }

    @Test
    fun `the caption shapes episode 5 arrives in all join the show`() {
        val shapes = listOf(
            video("", caption = "Family Full House Episode 5\nJoin the channel"),
            video("", caption = "Family Full House With Rohit Sharma E05"),
            video("", caption = "Family Full House With Rohit Sharma Ep 5"),
            video("Family Full House With Rohit Sharma.mp4", caption = "\u0D2B\u0D3E\u0D2E\u0D3F\u0D32\u0D3F \u0D2B\u0D41\u0D7E \u0D39\u0D57\u0D38\u0D4D Episode 5"),
            video("Family Full House With Rohit Sharma.mp4", caption = "Episode 5"),
            video("Family Full House With Rohit Sharma Malayalam E05 720p.mkv"),
        )
        for (shape in shapes) {
            val entries = SeriesShelf.arrange(familyFullHouse() + shape)
            val show = family(entries)
            assertEquals("${shape.fileName} | ${shape.caption}", 5, show.episodeCount)
            assertEquals(shape.id, show.episodes.last().item.id)
            assertTrue(files(entries).isEmpty())
        }
    }

    @Test
    fun `a bare E number without an established show stays a file, and one word never joins`() {
        val items = listOf(
            video("Harbour E05.mkv"),
            video("Harbour Notes S01E01.mkv"),
            video("Harbour Notes S01E02.mkv"),
            video("Harbour Ep 3.mkv"),
            video("Kitchen Journal E04.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        assertEquals(listOf(1, 2), shows(entries).single().episodes.map { it.episode })
        assertEquals(listOf("Harbour E05.mkv", "Harbour Ep 3.mkv", "Kitchen Journal E04.mkv"), files(entries))
    }

    @Test
    fun `an all bare E chat forms a series in season one, episodes in order`() {
        val items = listOf(
            video("My Show E03.mkv"),
            video("My Show E01.mkv"),
            video("My Show E02.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val show = shows(entries).single()
        assertEquals("my show", show.key)
        assertEquals("My Show", show.title)
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(3, show.episodeCount)
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        assertEquals(listOf("S01E01", "S01E02", "S01E03"), show.episodes.map { it.code })
        assertTrue(files(entries).isEmpty())
    }

    @Test
    fun `a single bare E file forms no series`() {
        val entries = SeriesShelf.arrange(listOf(video("My Show E01.mkv")))
        assertTrue(shows(entries).isEmpty())
        assertEquals(listOf("My Show E01.mkv"), files(entries))
    }

    @Test
    fun `bare E files and S01E01 files of one title land in one series, copies merged`() {
        val items = listOf(
            video("Harbour Notes S01E01 1080p.mkv"),
            video("Harbour Notes S01E02.mkv"),
            video("Harbour Notes E01 720p.mkv"),
            video("Harbour Notes E03.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val show = shows(entries).single()
        assertEquals(3, show.episodeCount)
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        val firstEpisode = show.episodes.single { it.episode == 1 }
        assertEquals(2, firstEpisode.copies.size)
        assertEquals(
            setOf("Harbour Notes S01E01 1080p.mkv", "Harbour Notes E01 720p.mkv"),
            firstEpisode.copies.map { it.fileName }.toSet(),
        )
        assertTrue(files(entries).isEmpty())
    }

    @Test
    fun `same title E-only files of different years stay apart`() {
        val mixed = listOf(
            video("My Show 2019 E01.mkv"),
            video("My Show 2024 E02.mkv"),
        )
        val mixedEntries = SeriesShelf.arrange(mixed)
        assertTrue(shows(mixedEntries).isEmpty())
        assertEquals(mixed.map { it.fileName }, files(mixedEntries))

        val items = listOf(
            video("My Show 2019 E01.mkv"),
            video("My Show 2019 E02.mkv"),
            video("My Show 2024 E01.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val show = shows(entries).single()
        assertEquals(listOf(1, 2), show.episodes.map { it.episode })
        assertEquals(listOf("My Show 2024 E01.mkv"), files(entries))
    }

    @Test
    fun `a lone episode stays a file, and so does a season pack`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Harbour Notes Season 2 Complete 1080p.mkv"),
            video("Harbour Notes Season 3 Complete 1080p.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        assertTrue(shows(entries).isEmpty())
        assertEquals(items.map { it.fileName }, files(entries))
    }

    @Test
    fun `a show takes the place of its first file and the chat order is kept`() {
        val items = listOf(
            video("Workshop (2022) 1080p.mkv"),
            video("Studio.Sessions.S01E03.mkv"),
            video("Kitchen Journal.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Sky Garden - 02.mkv"),
            video("Studio.Sessions.S01E01.mkv"),
            video("Sky Garden - 01.mkv"),
        )
        val keys = SeriesShelf.arrange(items).map {
            when (it) {
                is ShelfEntry.Show -> "show:" + it.series.key
                is ShelfEntry.File -> it.item.fileName
            }
        }
        assertEquals(
            listOf("Workshop (2022) 1080p.mkv", "show:studio sessions", "Kitchen Journal.mkv", "show:sky garden"),
            keys,
        )
    }

    @Test
    fun `nothing is lost or listed twice`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("City.Archive.1999.mkv"),
            video("Sky Garden - 01.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val listed = entries.flatMap {
            when (it) {
                is ShelfEntry.Show -> it.series.episodes.map { e -> e.item }
                is ShelfEntry.File -> listOf(it.item)
            }
        }
        assertEquals(items.map { it.id }.sorted(), listed.map { it.id }.sorted())
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `movies with years and resolutions never become a show`() {
        val items = listOf(
            video("City.Archive.2016.1080p.BluRay.x265.mkv"),
            video("City.Archive.2016.720p.BluRay.x264.mkv"),
            video("City Archive - 2019 1080p.mkv"),
        )
        assertTrue(shows(SeriesShelf.arrange(items)).isEmpty())
    }

    @Test
    fun `an empty chat arranges to nothing`() {
        assertTrue(SeriesShelf.arrange(emptyList()).isEmpty())
    }

    // ---- progress ---------------------------------------------------------------------------

    private val show = run {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Studio.Sessions.S01E03.mkv"),
            video("Studio.Sessions.S02E01.mkv"),
        )
        shows(SeriesShelf.arrange(items)).single()
    }

    private fun progress(finished: Set<String>, started: Map<String, Float> = emptyMap()) =
        SeriesShelf.progress(show, { it.fileName.code() in finished }, { started[it.fileName.code()] ?: 0f })

    private fun String.code() = Regex("""S\d\dE\d\d""").find(this)!!.value

    @Test
    fun `a fresh show starts at the first episode`() {
        val p = progress(emptySet())
        assertEquals(0, p.watched)
        assertEquals(4, p.total)
        assertEquals("S01E01", p.next?.code)
        assertFalse(p.finished)
    }

    @Test
    fun `the next episode follows the furthest one watched`() {
        val p = progress(setOf("S01E01", "S01E02"))
        assertEquals(2, p.watched)
        assertEquals("S01E03", p.next?.code)
    }

    @Test
    fun `the end of a season carries on into the next one`() {
        assertEquals("S02E01", progress(setOf("S01E01", "S01E02", "S01E03")).next?.code)
    }

    @Test
    fun `an episode part way through is where to carry on`() {
        val p = progress(setOf("S01E01", "S01E02", "S01E03"), started = mapOf("S01E02" to 0.4f))
        // E02 is on the Watched list, so its old position does not count; nothing else is started.
        assertEquals("S02E01", p.next?.code)
        assertEquals("S01E03", progress(setOf("S01E01"), started = mapOf("S01E03" to 0.5f)).next?.code)
    }

    @Test
    fun `a skipped episode is offered once the later ones are done`() {
        assertEquals("S01E02", progress(setOf("S01E01", "S01E03", "S02E01")).next?.code)
    }

    @Test
    fun `a show watched to the end has nothing next`() {
        val p = progress(setOf("S01E01", "S01E02", "S01E03", "S02E01"))
        assertTrue(p.finished)
        assertNull(p.next)
        assertEquals(4, p.watched)
    }

    @Test
    fun `the caption alone can make a show`() {
        val items = listOf(
            video("", caption = "Sky Garden Episode 1\nsubbed"),
            video("", caption = "Sky Garden Episode 2"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals("Sky Garden", show.title)
        assertEquals(listOf(1, 2), show.episodes.map { it.episode })
    }

    @Test
    fun `copies of one film fold into one tile that knows every version`() {
        val small = video("Night.Train.2019.720p.WEB.mkv", date = 10)
        val large = video("Night Train (2019) 1080p x265.mkv", date = 20)
        val other = video("Mountains.2021.1080p.mkv", date = 5)
        val entries = SeriesShelf.arrange(listOf(small, other, large))
        val tiles = entries.filterIsInstance<ShelfEntry.File>().map { it.item }
        assertEquals(2, tiles.size)
        val film = tiles.first()
        assertEquals(listOf(large.id, small.id), film.versions.map { it.id })
        assertEquals("Night Train (2019)", SeriesShelf.filmTitle(film))
        assertTrue(tiles[1].versions.isEmpty())
    }

    @Test
    fun `plain names with no year or quality are never taken for copies`() {
        val entries = SeriesShelf.arrange(listOf(video("Lecture.mp4"), video("Lecture.mp4")))
        assertEquals(2, entries.size)
        assertTrue(entries.filterIsInstance<ShelfEntry.File>().all { it.item.versions.isEmpty() })
    }

    @Test
    fun `an episode stands for the built-in channel's post of it`() {
        val older = video("Harbour.Notes.S01E01.mkv", date = 10).copy(chatId = 42)
        val newer = video("Harbour.Notes.S01E01.mkv", date = 20)
        val second = video("Harbour.Notes.S01E02.mkv", date = 30)
        BuiltInChannels.jMoviesChatId = 42L
        try {
            val show = shows(SeriesShelf.arrange(listOf(newer, second, older))).single()
            val first = show.episodes.single { it.episode == 1 }
            assertEquals(42L, first.item.chatId)
            assertEquals(setOf(42L, 7L), first.copies.map { it.chatId }.toSet())
        } finally {
            BuiltInChannels.jMoviesChatId = null
        }
    }


    @Test
    fun `posts of one range pack fold into one row, and a different range stays its own`() {
        val items = listOf(
            video("My Show S01E01.mkv"),
            video("My Show S01E02.mkv"),
            video("My Show S01E01-E05 1080p.mkv"),
            video("My Show S01E01-E05 1080p.mkv"),
            video("My Show S01E01-E05 1080p.mkv"),
            video("My Show S01E01-E08 1080p.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        val rows = show.episodes.filter { it.episode == 1 }
        assertEquals(3, rows.size)
        assertEquals(3, rows.single { it.lastEpisode == 5 }.copies.size)
        assertEquals(1, rows.single { it.lastEpisode == 8 }.copies.size)
        assertEquals(1, rows.single { it.lastEpisode == null }.copies.size)
    }

    @Test
    fun `singles whose dotted title lost its head words still take their season pack`() {
        val items = listOf(
            video("Make.Me.Tingle.S01E01.mkv"),
            video("Make.Me.Tingle.S01E02.mkv"),
            video("Make Me Tingle S01 720p HEVC COMBINED AMZN WEB-DL.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(1, show.packs.size)
    }

    @Test
    fun `spaced singles with plain dates and sizes take their season pack`() {
        val items = listOf(
            video("Make Me Tingle S01E01.mkv", date = 11, size = 100),
            video("Make Me Tingle S01E02.mkv", date = 12, size = 100),
            video("Make Me Tingle S01 720p HEVC COMBINED AMZN WEB-DL.mkv", date = 13, size = 100),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(1, show.packs.size)
    }

    @Test
    fun `a season in one file joins its show as a pack, not an episode`() {
        val items = listOf(
            video("Make Me Tingle S01E01 1080p.mkv"),
            video("Make Me Tingle S01E02 1080p.mkv"),
            video("Make Me Tingle S01 720p HEVC COMBINED AMZN WEB-DL.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1, 2), show.episodes.map { it.episode })
        val pack = show.packs.single()
        assertEquals(1, pack.season)
        assertEquals("Make Me Tingle S01 720p HEVC COMBINED AMZN WEB-DL.mkv", pack.item.fileName)
    }
}
