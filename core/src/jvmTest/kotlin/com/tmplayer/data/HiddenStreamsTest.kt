package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live row's hidden set (v1.0.11): a stream is out by the post that carried it, so the
 * same post stays out across loads and restarts, a repost of the same address (a new post)
 * shows again, and a damaged stored value loses only its damaged lines.
 */
class HiddenStreamsTest {

    private fun posted(chatId: Long, messageId: Long, url: String = "https://cdn.example.com/live/m.m3u8") =
        StreamLink(url = url, name = "Match", live = true, chatId = chatId, messageId = messageId)

    @Test
    fun aHiddenPostStaysHiddenAcrossAStoredRoundTrip() {
        val link = posted(chatId = -100123L, messageId = 55L)
        val stored = HiddenStreams.encode(HiddenStreams.plus(emptySet(), link))
        val readBack = HiddenStreams.decode(stored)
        assertTrue(HiddenStreams.hides(readBack, link))
        assertTrue(HiddenStreams.visible(listOf(link), readBack).isEmpty())
    }

    @Test
    fun aRepostOfTheSameAddressShowsAgain() {
        val first = posted(chatId = -100123L, messageId = 55L)
        val repost = posted(chatId = -100123L, messageId = 56L)
        val hidden = HiddenStreams.plus(emptySet(), first)
        assertTrue(HiddenStreams.hides(hidden, first))
        assertFalse(HiddenStreams.hides(hidden, repost))
        assertEquals(listOf(repost), HiddenStreams.visible(listOf(first, repost), hidden))
    }

    @Test
    fun theSameMessageIdInAnotherChatIsAnotherPost() {
        val hidden = HiddenStreams.plus(emptySet(), posted(chatId = -100123L, messageId = 55L))
        assertFalse(HiddenStreams.hides(hidden, posted(chatId = -100999L, messageId = 55L)))
    }

    @Test
    fun aLinkWithNoPostIsKnownByItsAddress() {
        val pasted = StreamLink(url = "https://cdn.example.com/v.mp4", name = "v.mp4", live = false)
        val hidden = HiddenStreams.plus(emptySet(), pasted)
        assertTrue(HiddenStreams.hides(hidden, pasted.copy(name = "renamed")))
        assertFalse(HiddenStreams.hides(hidden, pasted.copy(url = "https://cdn.example.com/other.mp4")))
    }

    @Test
    fun damagedStoredTextLosesOnlyItsDamagedLines() {
        val raw = buildString {
            appendLine("not a key at all")
            appendLine("")
            appendLine("  -100123_55  ")
            appendLine("12x_9")
            appendLine("https://cdn.example.com/v.mp4")
            appendLine("-100123_55")
        }
        assertEquals(setOf("-100123_55", "https://cdn.example.com/v.mp4"), HiddenStreams.decode(raw))
        assertTrue(HiddenStreams.decode(null).isEmpty())
        assertTrue(HiddenStreams.decode("").isEmpty())
        assertTrue(HiddenStreams.decode("\n\n  \n").isEmpty())
    }

    @Test
    fun theStoredSetIsCappedAndAnAlreadyHiddenKeyStillCounts() {
        val full = (1..HiddenStreams.MAX_HIDDEN).map { "1_$it" }.toSet()
        assertEquals(HiddenStreams.MAX_HIDDEN, HiddenStreams.decode(HiddenStreams.encode(full)).size)
        val oneMore = posted(chatId = 2L, messageId = 1L)
        assertEquals(full, HiddenStreams.plus(full, oneMore))
        val already = posted(chatId = 1L, messageId = 1L)
        assertTrue(HiddenStreams.hides(HiddenStreams.plus(full, already), already))
        val huge = (1..(HiddenStreams.MAX_HIDDEN + 50)).joinToString("\n") { "3_$it" }
        assertEquals(HiddenStreams.MAX_HIDDEN, HiddenStreams.decode(huge).size)
    }
}
