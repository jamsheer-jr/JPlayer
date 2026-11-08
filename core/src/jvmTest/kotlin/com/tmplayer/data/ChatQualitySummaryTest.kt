package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chat quality summary (item 34, v1.0.15): counts, order, totals, and the unreadable. */
class ChatQualitySummaryTest {

    private fun item(name: String, size: Long, width: Int = 0, height: Int = 0) = MediaItem(
        chatId = -1001,
        messageId = name.hashCode().toLong(),
        fileId = 1,
        title = name,
        sizeBytes = size,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = name,
        caption = "",
        width = width,
        height = height,
    )

    @Test
    fun `counts by quality, sharpest first, with the total size`() {
        val summary = ChatQualitySummary.of(
            listOf(
                item("a.720p.mkv", 100),
                item("b.1080p.mkv", 200),
                item("c.1080p.mkv", 300),
                item("d.mkv", 400, width = 3840, height = 2160),
            ),
        )
        assertEquals(4, summary.videos)
        assertEquals(1000L, summary.totalBytes)
        assertEquals(listOf("4K", "1080p", "720p"), summary.lines.map { it.label })
        assertEquals(listOf(1, 2, 1), summary.lines.map { it.count })
        assertEquals("4K × 1, 1080p × 2, 720p × 1", ChatQualitySummary.countsText(summary))
    }

    @Test
    fun `a video with no readable quality counts to size and videos only`() {
        val summary = ChatQualitySummary.of(listOf(item("plain.mkv", 55)))
        assertEquals(1, summary.videos)
        assertEquals(55L, summary.totalBytes)
        assertTrue(summary.lines.isEmpty())
    }

    @Test
    fun `nothing loaded is an empty summary`() {
        val summary = ChatQualitySummary.of(emptyList())
        assertTrue(summary.isEmpty)
        assertEquals(0L, summary.totalBytes)
    }
}
