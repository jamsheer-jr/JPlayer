package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStatsTest {

    private fun watched(chatId: Long, messageId: Long, chat: String, durationSec: Int, title: String = "Film $messageId") = WatchedRecord(
        chatId = chatId,
        messageId = messageId,
        fileId = 1,
        title = title,
        chatTitle = chat,
        sizeBytes = 1,
        durationSec = durationSec,
        watchedAt = messageId,
        manual = false,
        fileName = "$title.mp4",
    )

    private fun going(chatId: Long, messageId: Long, chat: String, positionMs: Long) = ResumeRecord(
        chatId = chatId,
        messageId = messageId,
        fileId = 1,
        title = "Going $messageId",
        chatTitle = chat,
        sizeBytes = 1,
        durationSec = 3_600,
        positionMs = positionMs,
        durationMs = 3_600_000,
        updatedAt = messageId,
    )

    @Test
    fun `hours add finished lengths and places reached`() {
        val stats = WatchStats.of(
            watched = listOf(watched(1, 1, "Films", 3_600), watched(1, 2, "Films", 1_800)),
            continuing = listOf(going(2, 3, "Shows", 900_000)),
            genresOf = { emptyList() },
        )
        assertEquals(3_600_000L + 1_800_000L + 900_000L, stats.watchedMs)
        assertEquals(2, stats.finishedCount)
        assertEquals(1, stats.goingCount)
    }

    @Test
    fun `genres count once per title and chats across both lists`() {
        val byTitle = mapOf("Film 1" to listOf("Drama", "Comedy"), "Film 2" to listOf("Drama"))
        val stats = WatchStats.of(
            watched = listOf(watched(1, 1, "Films", 60), watched(1, 2, "Films", 60)),
            continuing = listOf(going(2, 3, "Shows", 1_000)),
            genresOf = { item -> byTitle[item.title].orEmpty() },
        )
        assertEquals(listOf("Drama" to 2, "Comedy" to 1), stats.topGenres)
        assertEquals(listOf("Films" to 2, "Shows" to 1), stats.topChats)
    }

    @Test
    fun `nothing watched is empty`() {
        val stats = WatchStats.of(emptyList(), emptyList()) { emptyList() }
        assertTrue(stats.isEmpty)
        assertEquals(0L, stats.watchedMs)
    }
}
