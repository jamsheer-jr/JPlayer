package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenCopiesTest {

    private fun copy(chatId: Long, messageId: Long) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = messageId.toInt(),
        title = "A video",
        sizeBytes = 900 * SizeFilter.MB,
        durationSec = 6_000,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1_700_000_000,
        fileName = "A.Video.mkv",
        caption = "",
    )

    @Test
    fun `a hidden copy is keyed by its post and stays hidden`() {
        val bad = copy(-100123L, 42L)
        val keys = HiddenCopies.plus(emptySet(), bad)
        assertTrue(HiddenCopies.hides(keys, bad))
        assertFalse(HiddenCopies.hides(keys, copy(-100123L, 43L)))
        // The same file under another post is another copy and still shows.
        assertFalse(HiddenCopies.hides(keys, copy(-100999L, 42L)))
        assertEquals(keys, HiddenCopies.decode(HiddenCopies.encode(keys)))
    }

    @Test
    fun `unhiding brings the copy back and the playing copy is exempt`() {
        val bad = copy(7L, 8L)
        val keys = HiddenCopies.plus(emptySet(), bad)
        assertEquals(emptySet<String>(), HiddenCopies.minus(keys, bad))
        assertEquals(listOf(bad), HiddenCopies.visible(listOf(bad), keys, exempt = bad))
        assertEquals(emptyList<MediaItem>(), HiddenCopies.visible(listOf(bad), keys))
    }

    @Test
    fun `damaged lines are dropped and the rest kept`() {
        val decoded = HiddenCopies.decode("12_34\n\nnot a key\n-5_678\n12_34\n1_2_3")
        assertEquals(setOf("12_34", "-5_678"), decoded)
        assertEquals(emptySet<String>(), HiddenCopies.decode(null))
        assertEquals(emptySet<String>(), HiddenCopies.decode("   "))
    }
}
