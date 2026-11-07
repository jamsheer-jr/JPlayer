package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLoginLinksTest {

    @Test
    fun `a sign in link the television shows is read back whole`() {
        val link = "tg://login?token=AaBbCc09_-xYz0123456789"
        assertEquals(link, QrLoginLinks.parse(link))
        assertEquals(link, QrLoginLinks.parse("  $link  "))
    }

    @Test
    fun `the scheme and host may differ in case, the token may not`() {
        assertEquals(
            "TG://LOGIN?token=AbC",
            QrLoginLinks.parse("TG://LOGIN?token=AbC"),
        )
    }

    @Test
    fun `anything that is not a sign in link is refused`() {
        assertNull(QrLoginLinks.parse(""))
        assertNull(QrLoginLinks.parse("tg://login"))
        assertNull(QrLoginLinks.parse("tg://login?token="))
        assertNull(QrLoginLinks.parse("tg://login?token=has space"))
        assertNull(QrLoginLinks.parse("tg://login?token=abc&extra=1"))
        assertNull(QrLoginLinks.parse("https://t.me/login?token=abc"))
        assertNull(QrLoginLinks.parse("jplayer://party?c=1&m=2"))
        assertNull(QrLoginLinks.parse("tg://login?token=" + "a".repeat(600)))
    }
}
