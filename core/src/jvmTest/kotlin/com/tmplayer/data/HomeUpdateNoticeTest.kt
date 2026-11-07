package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home update card's day of peace (owner, v1.0.15, item 13): see [HomeUpdateNotice]. */
class HomeUpdateNoticeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `nothing put away, the card stands`() {
        assertTrue(HomeUpdateNotice.visible("", "1.0.15", now))
        assertTrue(HomeUpdateNotice.visible("nonsense", "1.0.15", now))
        assertTrue(HomeUpdateNotice.visible("1.0.15|notanumber", "1.0.15", now))
        assertTrue(HomeUpdateNotice.visible("|123", "1.0.15", now))
    }

    @Test
    fun `put away for the day, the card waits, then stands again`() {
        val stored = HomeUpdateNotice.stamp("1.0.15", now)
        assertFalse(HomeUpdateNotice.visible(stored, "1.0.15", now))
        assertFalse(HomeUpdateNotice.visible(stored, "1.0.15", now + HomeUpdateNotice.DAY_MS - 1))
        assertTrue(HomeUpdateNotice.visible(stored, "1.0.15", now + HomeUpdateNotice.DAY_MS))
    }

    @Test
    fun `a newer version stands at once, and no version never does`() {
        val stored = HomeUpdateNotice.stamp("1.0.15", now)
        assertTrue(HomeUpdateNotice.visible(stored, "1.0.16", now))
        assertFalse(HomeUpdateNotice.visible("", "", now))
    }
}
