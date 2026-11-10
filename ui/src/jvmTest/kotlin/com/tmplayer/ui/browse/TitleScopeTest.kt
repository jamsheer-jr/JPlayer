package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedMode
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scope a title's load and the search may ask over (owner, v1.0.15): "Global Search
 * off" (a picked set of chats) must mean off in each consumer, and an unread stored
 * answer must never be read as the viewer choosing every chat.
 */
class TitleScopeTest {

    @Test
    fun `a narrowing gate is asked exactly, its own chat first`() {
        val asked = TitleScope.chats(ownChatId = 9, gate = setOf(3, 9, 5), gateKnown = true, listedChatIds = setOf(1, 2, 3))
        assertEquals(listOf(9L, 3L, 5L), asked)
    }

    @Test
    fun `a known every chat answer asks the listed chats`() {
        val asked = TitleScope.chats(ownChatId = 9, gate = null, gateKnown = true, listedChatIds = setOf(2, 9, 4))
        assertEquals(listOf(9L, 2L, 4L), asked)
    }

    @Test
    fun `an unread gate asks the title's own chat alone, never every listed chat`() {
        val asked = TitleScope.chats(ownChatId = 9, gate = null, gateKnown = false, listedChatIds = setOf(1, 2, 3, 9))
        assertEquals(listOf(9L), asked)
    }
}

/** [TitleMediaLoad.search]: a chat's finds are handed over page by page, as they land. */
class TitleMediaLoadPagesTest {

    private fun video(messageId: Long, name: String) = MediaItem(
        chatId = 7,
        messageId = messageId,
        fileId = 1,
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = name,
    )

    @Test
    fun `each page is handed over as it lands, in order`() = runTest {
        val first = listOf(video(1, "Harbour Lights 2019 720p"), video(2, "Harbour Lights 2019 1080p"))
        val second = listOf(video(3, "Harbour Lights 2019 2160p"))
        val handed = mutableListOf<List<Long>>()
        val found = TitleMediaLoad.search(
            fetch = { _, _, cursors ->
                when (cursors.video) {
                    0L -> com.tmplayer.data.MediaPage(first, com.tmplayer.data.MediaCursors(video = 1), endReached = false)
                    else -> com.tmplayer.data.MediaPage(second, com.tmplayer.data.MediaCursors(video = 2), endReached = true)
                }
            },
            chatId = 7,
            query = "Harbour Lights",
            onPage = { page -> handed += page.map { it.messageId } },
        )
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), handed)
        assertEquals(listOf(1L, 2L, 3L), found.map { it.messageId })
    }

    @Test
    fun `a failed later page keeps the pages already handed over`() = runTest {
        val first = listOf(video(1, "Harbour Lights 2019 720p"))
        val handed = mutableListOf<List<Long>>()
        val found = TitleMediaLoad.search(
            fetch = { _, _, cursors ->
                if (cursors.video == 0L) {
                    com.tmplayer.data.MediaPage(first, com.tmplayer.data.MediaCursors(video = 1), endReached = false)
                } else {
                    null
                }
            },
            chatId = 7,
            query = "Harbour Lights",
            onPage = { page -> handed += page.map { it.messageId } },
        )
        assertEquals(listOf(listOf(1L)), handed)
        assertEquals(listOf(1L), found.map { it.messageId })
    }
}

/** [TitleMediaLoad] itself, over a fake Telegram: the chats it asks are the chosen ones. */
class TitleMediaLoadScopeTest {

    private fun video(chatId: Long, messageId: Long, name: String) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = 1,
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = name,
    )

    @Test
    fun `a title load asks the chosen chats and its own, and no other`() = runTest {
        ChatGate.set(null)
        AddedChats.set(AddedChoice(AddedMode.Picked, listOf(AddedChat(52, "Two"), AddedChat(53, "Three"))))
        val asked = mutableListOf<Long>()
        val item = video(chatId = 51, messageId = 5151, name = "Harbour Lights 2019")
        TitleMediaLoad.load(item) { chatId, _, _ ->
            asked += chatId
            null
        }
        assertEquals(listOf(51L, 52L, 53L), asked)
    }

    @Test
    fun `a later load asks only the chats listed since the first`() = runTest {
        // An every chat answer: the scope is whatever chats the pool knows.
        com.tmplayer.online.KnownMedia.clear()
        ChatGate.set(null)
        AddedChats.set(AddedChoice(AddedMode.All, emptyList()))
        val item = video(chatId = 61, messageId = 6161, name = "Harbour Lights 2019")
        val first = mutableListOf<Long>()
        TitleMediaLoad.load(item) { chatId, _, _ ->
            first += chatId
            null
        }
        assertEquals(listOf(61L), first)
        // Home lists another chat behind the standing page, as a category
        // page's deepening does while the viewer is looking at the title.
        com.tmplayer.online.KnownMedia.note(listOf(video(chatId = 62, messageId = 6262, name = "Other Film 2021")))
        val second = mutableListOf<Long>()
        TitleMediaLoad.load(item) { chatId, _, _ ->
            second += chatId
            null
        }
        assertEquals(listOf(62L), second)
        // Nothing new listed since: no ask at all.
        val third = mutableListOf<Long>()
        TitleMediaLoad.load(item) { chatId, _, _ ->
            third += chatId
            null
        }
        assertEquals(emptyList<Long>(), third)
    }
}
