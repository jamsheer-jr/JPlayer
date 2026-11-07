package com.tmplayer.online

import com.tmplayer.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A person's TMDB filmography (owner, v1.0.15, item 17): the combined credits read over a fake
 * network, tidied into one list, and marked against the titles the viewer has.
 */
class PersonCreditsTest {

    private fun api(body: String, code: Int = 200): TmdbApi {
        val http = HttpTransport { HttpResponse(code, body = body.toByteArray()) }
        return TmdbApi(http, RateLimiter(0, { 0L }, {}), "t")
    }

    private fun item(id: Long, name: String) = MediaItem(
        chatId = 7, messageId = id, fileId = 0, title = name, sizeBytes = 1, durationSec = 1, mimeType = "video/mp4",
        thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = name,
    )

    private fun credit(id: String, kind: MetaKind, title: String, year: Int?, role: String = "") =
        PersonCredit(MetaRef(MetaProvider.Tmdb, kind, id, title), year, role)

    @Test
    fun combinedCreditsReadFilmsAndShowsNewestFirst() = runBlocking {
        val reply = api(
            """{"cast":[
                {"id":11,"media_type":"movie","title":"Old Film","release_date":"1999-05-01","character":"Lead","poster_path":"/old.jpg"},
                {"id":22,"media_type":"tv","name":"New Show","first_air_date":"2024-02-03","character":"Guest"},
                {"id":33,"media_type":"movie","title":"No Date Film","character":""},
                {"id":44,"media_type":"person","name":"Not A Title","character":"Self"},
                {"id":0,"media_type":"movie","title":"No Id","character":"X"},
                {"media_type":"tv","name":"Missing Id","character":"Y"},
                {"id":55,"media_type":"movie","title":"","character":"Blank"}
            ],"crew":[
                {"id":11,"media_type":"movie","title":"Old Film","job":"Producer"},
                {"id":66,"media_type":"movie","title":"Directed One","release_date":"2010-06-07","job":"Director"}
            ]}""",
        ).personCredits("k", "99", "en")
        val credits = (reply as MetaReply.Ok).value
        assertEquals(listOf("22", "66", "11", "33"), credits.map { it.ref.id })
        assertEquals(listOf(MetaKind.Show, MetaKind.Film, MetaKind.Film, MetaKind.Film), credits.map { it.ref.kind })
        assertEquals(2024, credits[0].year)
        assertNull(credits[3].year)
        // The acting part names the shared title, not the crew job.
        assertEquals("Lead", credits[2].role)
        assertEquals("Director", credits[1].role)
        assertEquals("https://image.tmdb.org/t/p/w342/old.jpg", credits[2].posterUrl)
    }

    @Test
    fun aCrewOnlyRoleFillsACastEntryThatNamesNone() {
        val tidied = PersonFilmography.tidy(
            cast = listOf(credit("1", MetaKind.Film, "One", 2001)),
            crew = listOf(credit("1", MetaKind.Film, "One", 2001, "Producer")),
        )
        assertEquals(1, tidied.size)
        assertEquals("Producer", tidied[0].role)
    }

    @Test
    fun tidyCapsTheListAtTheLimit() {
        val many = (1..(PersonFilmography.LIMIT + 50)).map { credit(it.toString(), MetaKind.Film, "Title $it", 2000) }
        assertEquals(PersonFilmography.LIMIT, PersonFilmography.tidy(many, emptyList()).size)
    }

    @Test
    fun markingKeepsEveryRowAndOpensTheOnesHeld() {
        val held = item(5, "Held Film.mkv")
        val known = listOf(KnownTitle(held, MetaInfo(MetaProvider.Tmdb, "11", MetaKind.Film, "Held Film", 2001)))
        val credits = listOf(
            credit("22", MetaKind.Show, "Not Held Show", 2020, "Guest"),
            credit("11", MetaKind.Film, "Held Film", 2001, "Lead"),
        )
        val entries = PersonFilmography.mark(credits, known)
        assertEquals(listOf("22", "11"), entries.map { it.credit.ref.id })
        assertEquals(listOf(false, true), entries.map { it.playable })
        assertEquals(held, entries[1].item)
        assertNull(entries[0].item)
        // A show and a film sharing an id are different titles: only the film's key matches.
        val showOnly = PersonFilmography.mark(
            listOf(credit("11", MetaKind.Show, "Same Id Show", 2001)),
            known,
        )
        assertTrue(showOnly.single().item == null)
    }

    @Test
    fun personSearchReadsIdsAndNamesAndSkipsJunk() = runBlocking {
        val reply = api(
            """{"results":[
                {"id":31,"name":"Real Person"},
                {"id":0,"name":"No Id"},
                {"name":"Missing Id"},
                {"id":32,"name":""}
            ]}""",
        ).searchPerson("k", "Real Person", "en")
        assertEquals(listOf("31" to "Real Person"), (reply as MetaReply.Ok).value)
    }

    @Test
    fun aRefusedKeyIsNotAnEmptyFilmography() = runBlocking {
        val reply = api("{}", code = 403).personCredits("k", "99", "en")
        assertEquals(MetaReply.Refused, reply)
    }
}
