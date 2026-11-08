package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Low space mode's own decisions (item 9a, v1.0.15): when the cache trims itself, and when
 * the SD card is suggested. The threshold is the one [StorageAlerts.reason] has always
 * used: free space under the larger of 1 GB and 5 percent of the volume.
 */
class LowSpaceActionsTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `plenty of space does nothing`() {
        val actions = StorageAlerts.lowSpaceActions(30 * gb, 64 * gb, gb, removableAvailable = true, downloadsOnRemovable = false)
        assertEquals(StorageAlerts.LowSpaceActions(false, false, false), actions)
    }

    @Test
    fun `a nearly full device trims and suggests the card it is not using`() {
        val actions = StorageAlerts.lowSpaceActions(gb / 2, 64 * gb, 0, removableAvailable = true, downloadsOnRemovable = false)
        assertTrue(actions.trimCache)
        assertTrue(actions.suggestCleanup)
        assertTrue(actions.suggestSdCard)
    }

    @Test
    fun `no card suggestion when downloads already live on the card`() {
        val actions = StorageAlerts.lowSpaceActions(gb / 2, 64 * gb, 0, removableAvailable = true, downloadsOnRemovable = true)
        assertTrue(actions.trimCache)
        assertFalse(actions.suggestSdCard)
    }

    @Test
    fun `no card suggestion without a card`() {
        val actions = StorageAlerts.lowSpaceActions(gb / 2, 64 * gb, 0, removableAvailable = false, downloadsOnRemovable = false)
        assertFalse(actions.suggestSdCard)
    }

    @Test
    fun `a cache over its ceiling trims without calling the device full`() {
        val actions = StorageAlerts.lowSpaceActions(30 * gb, 64 * gb, 5 * gb, removableAvailable = true, downloadsOnRemovable = false)
        assertTrue(actions.trimCache)
        assertTrue(actions.suggestCleanup)
        // The device itself has room, so the card is not suggested for a cache problem.
        assertFalse(actions.suggestSdCard)
    }

    @Test
    fun `an unmeasured disk is never low`() {
        val actions = StorageAlerts.lowSpaceActions(0, 0, 0, removableAvailable = true, downloadsOnRemovable = false)
        assertEquals(StorageAlerts.LowSpaceActions(false, false, false), actions)
    }
}
