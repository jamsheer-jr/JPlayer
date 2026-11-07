package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupTest {

    @Test
    fun `never written is due, and a week later is due again`() {
        val now = 100 * AutoBackup.WEEK_MS
        assertTrue(AutoBackup.due(0L, now))
        assertTrue(AutoBackup.due(now - AutoBackup.WEEK_MS, now))
        assertFalse(AutoBackup.due(now - AutoBackup.WEEK_MS + 1, now))
        assertFalse(AutoBackup.due(now, now))
    }

    @Test
    fun `a clock set back still counts as due`() {
        val now = 100 * AutoBackup.WEEK_MS
        assertTrue(AutoBackup.due(now + AutoBackup.WEEK_MS, now))
    }
}
