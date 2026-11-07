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
}
