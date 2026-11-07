package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightWindowTest {

    @Test
    fun `a window past midnight holds the day and frees the night`() {
        val start = NightWindow.DEFAULT_START
        val end = NightWindow.DEFAULT_END
        assertFalse(NightWindow.inside(12 * 60, start, end))
        assertFalse(NightWindow.inside(22 * 60 + 59, start, end))
        assertTrue(NightWindow.inside(23 * 60, start, end))
        assertTrue(NightWindow.inside(0, start, end))
        assertTrue(NightWindow.inside(5 * 60 + 59, start, end))
        assertFalse(NightWindow.inside(6 * 60, start, end))
    }

    @Test
    fun `a window inside one day works the same way`() {
        assertTrue(NightWindow.inside(60, 60, 120))
        assertTrue(NightWindow.inside(119, 60, 120))
        assertFalse(NightWindow.inside(120, 60, 120))
        assertFalse(NightWindow.inside(59, 60, 120))
    }

    @Test
    fun `a window whose ends meet holds nothing, and off is always allowed`() {
        assertFalse(NightWindow.inside(100, 100, 100))
        assertTrue(NightWindow.allowed(false, 12 * 60, NightWindow.DEFAULT_START, NightWindow.DEFAULT_END))
        assertFalse(NightWindow.allowed(true, 12 * 60, NightWindow.DEFAULT_START, NightWindow.DEFAULT_END))
    }

    @Test
    fun `stored minutes outside the day are cleaned, never trusted`() {
        assertEquals(0, NightWindow.clean(24 * 60))
        assertEquals(23 * 60, NightWindow.clean(-60))
        assertEquals(60, NightWindow.clean(25 * 60))
        assertTrue(NightWindow.inside(-60, NightWindow.DEFAULT_START, NightWindow.DEFAULT_END))
    }
}
