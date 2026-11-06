package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyLinksTest {

    @Test
    fun `a link round trips, channel id and start time included`() {
        val link = PartyLink(chatId = -1001234567890L, messageId = 48151623L, startSec = 754)
        assertEquals("jplayer://party?c=-1001234567890&m=48151623&t=754", PartyLinks.build(link.chatId, link.messageId, link.startSec))
        assertEquals(link, PartyLinks.parse(PartyLinks.build(link.chatId, link.messageId, link.startSec)))
    }

    @Test
    fun `no start time is left out of the link and read back as the beginning`() {
        assertEquals("jplayer://party?c=123456&m=78", PartyLinks.build(123456L, 78L))
        assertEquals(PartyLink(123456L, 78L, 0), PartyLinks.parse("jplayer://party?c=123456&m=78"))
    }

    @Test
    fun `a start time is read the way telegram writes one`() {
        assertEquals(90, PartyLinks.parse("jplayer://party?c=1&m=2&t=90")?.startSec)
        assertEquals(90, PartyLinks.parse("jplayer://party?c=1&m=2&t=1:30")?.startSec)
        assertEquals(3723, PartyLinks.parse("jplayer://party?c=1&m=2&t=1h2m3s")?.startSec)
    }

    @Test
    fun `the order of the parts and the case of the scheme do not matter`() {
        assertEquals(PartyLink(-5L, 9L, 0), PartyLinks.parse("  JPLAYER://PARTY?m=9&c=-5  "))
    }

    @Test
    fun `anything that is not a whole party link is refused`() {
        assertNull(PartyLinks.parse(""))
        assertNull(PartyLinks.parse("jplayer://party"))
        assertNull(PartyLinks.parse("jplayer://party?c=0&m=2"))
        assertNull(PartyLinks.parse("jplayer://party?c=1&m=0"))
        assertNull(PartyLinks.parse("jplayer://party?c=1&m=-2"))
        assertNull(PartyLinks.parse("jplayer://party?c=abc&m=2"))
        assertNull(PartyLinks.parse("jplayer://party?c=1"))
        assertNull(PartyLinks.parse("https://t.me/somechannel/12"))
        assertNull(PartyLinks.parse("jplayer://party?c=1&m=2 and some words"))
        assertNull(PartyLinks.parse("jplayer://party?c=99999999999999999999&m=2"))
    }

    @Test
    fun `an absurd start time is no start time`() {
        assertEquals(0, PartyLinks.parse("jplayer://party?c=1&m=2&t=99999999")?.startSec)
        assertEquals(0, PartyLinks.parse("jplayer://party?c=1&m=2&t=later")?.startSec)
    }
}
