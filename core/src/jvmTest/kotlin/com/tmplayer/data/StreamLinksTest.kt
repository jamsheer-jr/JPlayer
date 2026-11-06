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
        val links = StreamLinks.inPosts(posts, 42L, "J MOVIES")
        assertEquals(StreamLinks.LIMIT, links.size)
        assertEquals("Show 20", links.first().name)
        assertTrue(links.all { it.live })
    }

    @Test
    fun postsCarryThePostTheyWereReadFrom() {
        val posts = listOf(
            ChannelPost(messageId = 9L, text = "Match https://cdn.example.com/m.m3u8", isVideo = false),
            ChannelPost(messageId = 4L, text = "Show https://cdn.example.com/s.mp4", isVideo = false),
        )
        val links = StreamLinks.inPosts(posts, 42L, "J MOVIES")
        assertEquals(2, links.size)
        assertEquals(42L, links[0].chatId)
        assertEquals(9L, links[0].messageId)
        assertEquals(42L, links[1].chatId)
        assertEquals(4L, links[1].messageId)
    }

    @Test
    fun aPastedLiveLinkPlaysAsLive() {
        val link = StreamLinks.pasted("  https://cdn.example.com/live/match.m3u8?token=abc  ")
        assertEquals("https://cdn.example.com/live/match.m3u8?token=abc", link?.url)
        assertEquals(true, link?.live)
        assertEquals(false, link?.dash)
        assertEquals("match.m3u8", link?.name)
        assertEquals("cdn.example.com", link?.source)
    }

    @Test
    fun aPastedDashLinkIsDashAndAPlainFileIsNeither() {
        val dash = StreamLinks.pasted("https://cdn.example.com/v/movie.mpd")
        assertEquals(true, dash?.dash)
        assertEquals(false, dash?.live)
        val file = StreamLinks.pasted("https://cdn.example.com/v/movie.mkv")
        assertEquals(false, file?.dash)
        assertEquals(false, file?.live)
        assertEquals("movie.mkv", file?.name)
    }

    @Test
    fun aPastedLinkThatIsNotPlayableIsRefused() {
        assertNull(StreamLinks.pasted("https://example.com/page.html"))
        assertNull(StreamLinks.pasted("https://example.com/movie.mp4.html"))
        assertNull(StreamLinks.pasted("not a link at all"))
        assertNull(StreamLinks.pasted(""))
        // A channel post reads every shape a pasted link does (owner, 2026-11-06):
        // DASH from a post included.
        assertEquals(true, StreamLinks.inPost("Show https://cdn.example.com/v/movie.mpd", "J MOVIES")?.dash)
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

    @Test
    fun aPostCarriesMkvAndDashLinksNow() {
        val mkv = StreamLinks.inPost("Show https://rick.example.dev/0:/Show.2026.1080p.mkv", "J MOVIES")
        assertEquals("https://rick.example.dev/0:/Show.2026.1080p.mkv", mkv?.url)
        assertFalse(mkv!!.live)
        val dash = StreamLinks.inPost("Show https://cdn.example.com/v/manifest.mpd", "J MOVIES")
        assertTrue(dash!!.dash)
    }

    @Test
    fun theNameAfterThePipeWins() {
        val link = StreamLinks.inPost("https://cdn.example.com/v/a.mkv | My Show Name", "J MOVIES")
        assertEquals("My Show Name", link?.name)
        val named = StreamLinks.inPost("Before name https://cdn.example.com/v/a.mkv | Pipe Name", "J MOVIES")
        assertEquals("Pipe Name", named?.name)
    }

    @Test
    fun driveLinksBecomeDirect() {
        val share = "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUv/view?usp=sharing"
        assertEquals(
            "https://drive.google.com/uc?export=download&id=1AbCdEfGhIjKlMnOpQrStUv",
            StreamLinks.driveDirect(share),
        )
        assertEquals(
            "https://drive.google.com/uc?export=download&id=1AbCdEfGhIjKlMnOpQrStUv",
            StreamLinks.driveDirect("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOpQrStUv"),
        )
        assertNull(StreamLinks.driveDirect("https://example.com/file/d/1AbCdEfGhIjKlMnOpQrStUv/view"))
        val pasted = StreamLinks.pasted(share)
        assertEquals("https://drive.google.com/uc?export=download&id=1AbCdEfGhIjKlMnOpQrStUv", pasted?.url)
        val posted = StreamLinks.inPost("$share | Drive Show", "J MOVIES")
        assertEquals("Drive Show", posted?.name)
        assertEquals(pasted?.url, posted?.url)
    }

    @Test
    fun pageHostsAreKnown() {
        assertTrue(StreamLinks.isPageLink("https://gdflix.dev/file/abc123"))
        assertTrue(StreamLinks.isPageLink("https://hubcloud.ist/video/xyz"))
        assertFalse(StreamLinks.isPageLink("https://cdn.example.com/v/a.mkv"))
    }
}
