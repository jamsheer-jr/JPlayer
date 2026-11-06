package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB's trending answer, read over a fake network: kinds kept, people and junk left out. */
class TmdbTrendingTest {

    private fun api(body: String, code: Int = 200): TmdbApi {
        val http = HttpTransport { HttpResponse(code, body = body.toByteArray()) }
        return TmdbApi(http, RateLimiter(0, { 0L }, {}), "t")
    }

    @Test
    fun filmsAndShowsKeepTheirKindAndOrder() = runBlocking {
        val reply = api(
            """{"results":[
                {"id":11,"media_type":"movie","title":"A Film"},
                {"id":22,"media_type":"tv","name":"A Show"},
                {"id":33,"media_type":"movie","title":"Another Film"}
            ]}""",
        ).trending("k", "en")
        val refs = (reply as MetaReply.Ok).value
        assertEquals(
            listOf("Tmdb|Film|11", "Tmdb|Show|22", "Tmdb|Film|33"),
            refs.map { it.matchKey },
        )
        assertEquals("A Show", refs[1].title)
    }

    @Test
    fun peopleUnknownKindsAndBrokenEntriesAreSkipped() = runBlocking {
        val reply = api(
            """{"results":[
                {"id":44,"media_type":"person","name":"A Person"},
                {"id":0,"media_type":"movie","title":"No Id"},
                {"media_type":"tv","name":"Missing Id"},
                {"id":"xx","media_type":"movie","title":"Text Id"},
                {"id":55,"media_type":"collection","name":"A Collection"},
                {"id":66,"media_type":"tv","name":"The One Good Show"}
            ]}""",
        ).trending("k", "en")
        val refs = (reply as MetaReply.Ok).value
        assertEquals(listOf("Tmdb|Show|66"), refs.map { it.matchKey })
    }

    @Test
    fun anAnswerWithNoResultsIsAnEmptyList() = runBlocking {
        val reply = api("""{"page":1}""").trending("k", "en")
        assertTrue((reply as MetaReply.Ok).value.isEmpty())
    }

    @Test
    fun aRefusedKeyIsNotAnEmptyList() = runBlocking {
        val reply = api("{}", code = 401).trending("k", "en")
        assertEquals(MetaReply.Refused, reply)
    }
}
