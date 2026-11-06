package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStoresTest {

    @Test
    fun historyKeepsNewestFirstWithoutDuplicates() {
        var history = LinkHistory.decode(null)
        history = LinkHistory.plus(history, "https://a.example/1.mkv")
        history = LinkHistory.plus(history, "https://a.example/2.mkv")
        history = LinkHistory.plus(history, "https://a.example/1.mkv")
        assertEquals(listOf("https://a.example/1.mkv", "https://a.example/2.mkv"), history)
        assertEquals(history, LinkHistory.decode(LinkHistory.encode(history)))
    }

    @Test
    fun resumeRoundTripsAndClearsAtZero() {
        var places = StreamResume.decode("90000\thttps://a.example/1.mkv\njunk line\n5000\thttps://a.example/2.mkv")
        assertEquals(90_000L, places["https://a.example/1.mkv"])
        assertEquals(5_000L, places["https://a.example/2.mkv"])
        places = StreamResume.put(places, "https://a.example/1.mkv", 0)
        assertFalse(places.containsKey("https://a.example/1.mkv"))
        assertEquals(places, StreamResume.decode(StreamResume.encode(places)))
    }

    @Test
    fun seenTracksPostsNotAddresses() {
        val link = StreamLink(url = "https://a.example/live.m3u8", name = "Live", live = true, chatId = 7, messageId = 42)
        var seen = SeenStreams.decode(null)
        assertFalse(SeenStreams.seen(seen, link))
        seen = SeenStreams.plus(seen, link)
        assertTrue(SeenStreams.seen(seen, link))
        // A repost of the same address is a new post and reads as unseen.
        assertFalse(SeenStreams.seen(seen, link.copy(messageId = 43)))
        assertEquals(seen, SeenStreams.decode(SeenStreams.encode(seen)))
    }
}
