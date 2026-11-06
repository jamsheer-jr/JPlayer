package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLinksTest {

    @Test
    fun aLivePostGivesItsNameAndPlaysLive() {
        val link = StreamLinks.inPost("Big match tonight\nhttps://cdn.example.com/live/match.m3u8", "J MOVIES")
        assertEquals("https://cdn.example.com/live/match.m3u8", link?.url)
        assertEquals("Big match tonight", link?.name)
        assertTrue(link!!.live)
    }

    @Test
    fun aFilePostIsNotLiveAndKeepsItsQueryString() {
        val link = StreamLinks.inPost("Premiere show https://cdn.example.com/v/show.mp4?token=abc&x=1", "J MOVIES")
        assertEquals("https://cdn.example.com/v/show.mp4?token=abc&x=1", link?.url)
        assertEquals("Premiere show", link?.name)
        assertFalse(link!!.live)
    }

    @Test
    fun aPostWithNoNameFallsBackToTheChannel() {
        val link = StreamLinks.inPost("https://cdn.example.com/live/x.m3u8", "J MOVIES")
        assertEquals("J MOVIES", link?.name)
    }

    @Test
    fun theNameIsTheFirstLineOnly() {
        val link = StreamLinks.inPost("First line\nSecond line https://cdn.example.com/a.mp4", "J MOVIES")
        assertEquals("First line", link?.name)
    }

    @Test
    fun ordinaryLinksAndTextCarryNoStream() {
        assertNull(StreamLinks.inPost("Read more at https://example.com/page.html", "J MOVIES"))
        assertNull(StreamLinks.inPost("No links at all", "J MOVIES"))
        assertNull(StreamLinks.inPost("", "J MOVIES"))
        // A page whose name merely contains the extension is not a video file.
        assertNull(StreamLinks.inPost("Notes https://example.com/movie.mp4.html", "J MOVIES"))
        assertNull(StreamLinks.inPost("Almost https://example.com/live.m3u8x", "J MOVIES"))
    }

    @Test
    fun extensionsMatchInAnyCase() {
        assertTrue(StreamLinks.inPost("Up https://cdn.example.com/LIVE.M3U8", "J MOVIES")!!.live)
        assertFalse(StreamLinks.inPost("Up https://cdn.example.com/V.MP4", "J MOVIES")!!.live)
    }

    @Test
    fun prosePunctuationStuckToTheAddressIsNotPartOfIt() {
        val link = StreamLinks.inPost("Watch it here (https://cdn.example.com/a.mp4).", "J MOVIES")
        assertEquals("https://cdn.example.com/a.mp4", link?.url)
    }

    @Test
    fun theFirstStreamLinkInAPostWins() {
        val link = StreamLinks.inPost(
            "Site https://example.com/home then https://cdn.example.com/a.m3u8 and https://cdn.example.com/b.mp4",
            "J MOVIES",
        )
        assertEquals("https://cdn.example.com/a.m3u8", link?.url)
    }

    @Test
    fun postsGiveTheirStreamsNewestFirstAndBounded() {
        val posts = (20 downTo 1).map { i ->
            ChannelPost(
                messageId = i.toLong(),
                text = if (i % 2 == 0) "Show $i https://cdn.example.com/$i.m3u8" else "Just a note $i",
                isVideo = false,
            )
        }
        val links = StreamLinks.inPosts(posts, "J MOVIES")
        assertEquals(StreamLinks.LIMIT, links.size)
        assertEquals("Show 20", links.first().name)
        assertTrue(links.all { it.live })
    }
}

class ChannelNewsTest {

    private fun post(id: Long, video: Boolean = true) = ChannelPost(messageId = id, text = "", isVideo = video)

    @Test
    fun onlyVideosNewerThanTheSeenIdCount() {
        val posts = listOf(post(9), post(8, video = false), post(7), post(6))
        assertEquals(1, ChannelNews.newVideos(posts, seenMessageId = 7))
        assertEquals(3, ChannelNews.newVideos(posts, seenMessageId = 0))
        assertEquals(0, ChannelNews.newVideos(posts, seenMessageId = 9))
        assertEquals(0, ChannelNews.newVideos(emptyList(), seenMessageId = 0))
    }

    @Test
    fun seenAdvancesToTheNewestPostAndNeverBackwards() {
        val posts = listOf(post(9), post(7))
        assertEquals(9, ChannelNews.seenAfter(posts, seenMessageId = 5))
        assertEquals(12, ChannelNews.seenAfter(posts, seenMessageId = 12))
        assertEquals(5, ChannelNews.seenAfter(emptyList(), seenMessageId = 5))
    }
}
