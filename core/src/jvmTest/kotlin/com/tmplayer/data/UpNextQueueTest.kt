package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Up Next queue (item 33, v1.0.15): order, de-duping, the cap, and edits. */
class UpNextQueueTest {

    private fun item(id: Long) = MediaItem(
        chatId = -1000 - id,
        messageId = id,
        fileId = 1,
        title = "v$id",
        sizeBytes = 10,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = "v$id.mp4",
        caption = "",
    )

    @Test
    fun `play next jumps the head, add appends, and neither repeats a video`() {
        var queue = emptyList<MediaItem>()
        queue = UpNextQueue.add(queue, item(1))
        queue = UpNextQueue.add(queue, item(2))
        queue = UpNextQueue.playNext(queue, item(3))
        assertEquals(listOf(3L, 1L, 2L), queue.map { it.messageId })
        queue = UpNextQueue.playNext(queue, item(2))
        assertEquals(listOf(2L, 3L, 1L), queue.map { it.messageId })
        queue = UpNextQueue.add(queue, item(1))
        assertEquals(listOf(2L, 3L, 1L), queue.map { it.messageId })
    }

    @Test
    fun `taking walks the queue in order and empties it`() {
        var queue = listOf(item(1), item(2))
        val (first, rest) = UpNextQueue.takeNext(queue)
        assertEquals(1L, first?.messageId)
        queue = rest
        val (second, rest2) = UpNextQueue.takeNext(queue)
        assertEquals(2L, second?.messageId)
        val (none, _) = UpNextQueue.takeNext(rest2)
        assertNull(none)
    }

    @Test
    fun `remove and move edit the queue, bad moves change nothing`() {
        var queue = listOf(item(1), item(2), item(3))
        queue = UpNextQueue.move(queue, 2, 0)
        assertEquals(listOf(3L, 1L, 2L), queue.map { it.messageId })
        assertEquals(queue, UpNextQueue.move(queue, 0, 9))
        assertEquals(queue, UpNextQueue.move(queue, -1, 0))
        queue = UpNextQueue.remove(queue, item(1).id)
        assertEquals(listOf(3L, 2L), queue.map { it.messageId })
    }

    @Test
    fun `the cap holds`() {
        var queue = emptyList<MediaItem>()
        for (n in 1..(UpNextQueue.MAX_ITEMS + 5)) queue = UpNextQueue.add(queue, item(n.toLong()))
        assertEquals(UpNextQueue.MAX_ITEMS, queue.size)
    }
}
