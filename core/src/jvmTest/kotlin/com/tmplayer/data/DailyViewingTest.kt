package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyViewingTest {

    @Test
    fun `seconds count for their own day only`() {
        assertEquals(0L, DailyViewing.secondsOn(null, "2026-11-08"))
        assertEquals(0L, DailyViewing.secondsOn("garbage", "2026-11-08"))
        assertEquals(0L, DailyViewing.secondsOn("2026-11-07=3600", "2026-11-08"))
        assertEquals(3600L, DailyViewing.secondsOn("2026-11-08=3600", "2026-11-08"))
    }

    @Test
    fun `adding builds the day up and a new day starts over`() {
        val day1 = DailyViewing.added(null, "2026-11-08", 90)
        assertEquals("2026-11-08=90", day1)
        assertEquals("2026-11-08=150", DailyViewing.added(day1, "2026-11-08", 60))
        assertEquals("2026-11-09=30", DailyViewing.added(day1, "2026-11-09", 30))
        assertEquals(day1, DailyViewing.added(day1, "2026-11-08", 0))
    }

    @Test
    fun `the limit is reached at the minute, never without one`() {
        assertFalse(DailyViewing.reached(3599, 60))
        assertTrue(DailyViewing.reached(3600, 60))
        assertTrue(DailyViewing.reached(7200, 60))
        assertFalse(DailyViewing.reached(86_400, 0))
    }

    @Test
    fun `a profile's daily limit round trips on its stored line`() {
        val profiles = listOf(
            ViewerProfile("p2", "Kids", dailyLimitMinutes = 60),
            ViewerProfile("", "", dailyLimitMinutes = 120),
            ViewerProfile("p3", "Gran"),
        )
        val back = ViewerProfiles.decode(ViewerProfiles.encode(profiles))
        assertEquals(listOf("", "p2", "p3"), back.map { it.id })
        assertEquals(120, back[0].dailyLimitMinutes)
        assertEquals(60, back[1].dailyLimitMinutes)
        assertEquals(0, back[2].dailyLimitMinutes)
        // A line written before limits existed: three fields, no limit.
        assertEquals(0, ViewerProfiles.decode("p2\u001FKids\u001Ff").single().dailyLimitMinutes)
        // A garbage limit loses the limit, not the profile.
        assertEquals(0, ViewerProfiles.decode("p2\u001FKids\u001Ff\u001Fsoon").single().dailyLimitMinutes)
    }
}
