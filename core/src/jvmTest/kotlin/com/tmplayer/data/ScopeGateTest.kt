package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope gate's read state (owner, v1.0.15): [ChatGate.awaitBrowsingKnown] is what a
 * title's load and the search ask before they trust a gate that narrows nothing, so an
 * unread stored answer is never taken for the viewer choosing every chat.
 */
class ScopeGateTest {

    @Test
    fun `browsing is known once both stored answers are set`() = runBlocking {
        ChatGate.set(null)
        AddedChats.set(AddedChoice(AddedMode.Picked, listOf(AddedChat(7, "Seven"))))
        assertTrue(ChatGate.awaitBrowsingKnown())
        assertEquals(setOf(7L), ChatGate.awaitBrowsing())
    }

    @Test
    fun `an answered every chat is known and narrows nothing`() = runBlocking {
        ChatGate.set(null)
        AddedChats.set(AddedChoice(AddedMode.All))
        assertTrue(ChatGate.awaitBrowsingKnown())
        assertEquals(null, ChatGate.awaitBrowsing())
    }
}
