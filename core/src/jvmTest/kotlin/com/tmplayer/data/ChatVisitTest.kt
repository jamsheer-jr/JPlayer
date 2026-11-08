package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** The chat visit marks (item 11e, v1.0.15): counting, clearing, and bad lines. */
class ChatVisitTest {

    private fun item(messageId: Long) = MediaItem(
        chatId = -1001,
        messageId = messageId,
        fileId = 1,
        title = "v$messageId",
        sizeBytes = 10,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = "v$messageId.mp4",
        caption = "",
    )

    @Test
    fun `only posts newer than the mark count, once each`() {
        val videos = listOf(item(5), item(9), item(9), item(12), item(3))
        assertEquals(2, ChatVisit.newCount(videos, 8))
        assertEquals(0, ChatVisit.newCount(videos, 12))
        // No mark yet: nothing counts, so a first run shows no badge anywhere.
        assertEquals(0, ChatVisit.newCount(videos, 0))
    }

    @Test
    fun `visiting moves the mark forward and never back`() {
        val once = ChatVisit.visited(emptyMap(), -1001, 50)
        assertEquals(50L, once[-1001])
        val twice = ChatVisit.visited(once, -1001, 20)
        assertEquals(50L, twice[-1001])
        val thrice = ChatVisit.visited(twice, -1001, 80)
        assertEquals(80L, thrice[-1001])
    }

    @Test
    fun `encode and decode round trip, bad lines dropped`() {
        val marks = mapOf(-1001L to 50L, -1002L to 9L)
        assertEquals(marks, ChatVisit.decode(ChatVisit.encode(marks)))
        val raw = "junk\n-1001\u001F50\n-1002\u001Fno\n-1003\u001F0"
        assertEquals(mapOf(-1001L to 50L), ChatVisit.decode(raw))
        assertEquals(emptyMap(), ChatVisit.decode(null))
    }

    @Test
    fun `the newest of a listing is its highest post`() {
        assertEquals(12L, ChatVisit.newestOf(listOf(item(3), item(12), item(7))))
        assertEquals(0L, ChatVisit.newestOf(emptyList()))
    }
}
