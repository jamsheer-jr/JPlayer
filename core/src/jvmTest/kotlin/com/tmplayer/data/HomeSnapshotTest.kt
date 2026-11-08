package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSnapshotTest {

    private fun video(chat: Long, message: Long, name: String) = MediaItem(
        chatId = chat, messageId = message, fileId = message.toInt(), title = name,
        sizeBytes = 900L shl 20, durationSec = 7_200, mimeType = "video/x-matroska",
        thumbnailFileId = 5, miniThumbnail = null, date = message.toInt(),
        fileName = name, caption = "a caption", width = 1920, height = 1080,
    )

    @Test
    fun `a snapshot round trips its three lists`() {
        val recent = listOf(video(1, 11, "New.2026.mkv"), video(2, 21, "Fresh.2026.mkv"))
        val loaded = mapOf(1L to listOf(video(1, 12, "Older.2025.mkv")))
        val catalog = mapOf(3L to listOf(video(3, 31, "Deep.2024.mkv"), video(3, 32, "Deeper.2023.mkv")))
        val saved = HomeSnapshot.decode(HomeSnapshot.encode(recent, loaded, catalog))
        assertEquals(recent.map { it.id }, saved?.recent?.map { it.id })
        assertEquals(listOf("1:12"), saved?.loaded?.get(1L)?.map { it.id })
        assertEquals(listOf("3:31", "3:32"), saved?.catalog?.get(3L)?.map { it.id })
        val item = saved?.recent?.first()
        assertEquals(5, item?.thumbnailFileId)
        assertEquals(1920, item?.width)
        assertEquals("a caption", item?.caption)
    }

    @Test
    fun `garbage, a wrong version and emptiness are no snapshot`() {
        assertNull(HomeSnapshot.decode(null))
        assertNull(HomeSnapshot.decode(""))
        assertNull(HomeSnapshot.decode("v2\nR1"))
        assertNull(HomeSnapshot.decode("v1"))
        assertNull(HomeSnapshot.decode("v1\nnot a line\nRxy"))
        // One good line among garbage still stands.
        val good = HomeSnapshot.encode(listOf(video(1, 11, "New.2026.mkv")), emptyMap(), emptyMap())
        val saved = HomeSnapshot.decode(good + "\nRbroken")
        assertEquals(1, saved?.recent?.size)
    }

    @Test
    fun `the bounds hold on encode`() {
        val many = (1..80).map { video(1, it.toLong(), "Film $it.mkv") }
        val saved = HomeSnapshot.decode(HomeSnapshot.encode(many, mapOf(1L to many), mapOf(1L to many)))
        assertEquals(HomeSnapshot.RECENT_MAX, saved?.recent?.size)
        assertEquals(HomeSnapshot.LOADED_PER_CHAT_MAX, saved?.loaded?.get(1L)?.size)
        assertEquals(80, saved?.catalog?.get(1L)?.size)
        // The catalog total is capped across chats, not per chat.
        val wide = (1L..3L).associateWith { chat -> (1..120).map { video(chat, it.toLong(), "Film $it.mkv") } }
        val capped = HomeSnapshot.decode(HomeSnapshot.encode(emptyList(), emptyMap(), wide))
        assertEquals(HomeSnapshot.CATALOG_MAX, capped?.catalog?.values?.sumOf { it.size })
    }
}
