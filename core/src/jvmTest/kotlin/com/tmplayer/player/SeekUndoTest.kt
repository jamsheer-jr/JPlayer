package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Back to" offer after a long jump: when it stands, where it leads and when it goes. */
class SeekUndoTest {

    private val undo = SeekUndo()

    @Test
    fun `a long jump offers the way back, a nudge does not`() {
        assertNull(undo.seeked(600_000, 610_000, nowMs = 0))
        val offer = undo.seeked(610_000, 1_800_000, nowMs = 10_000)
        assertEquals(SeekUndo.Offer(610_000, 10_000 + SeekUndo.OFFER_MS), offer)
        assertEquals(610_000L, undo.take(nowMs = 11_000))
        assertNull(undo.current(nowMs = 11_000))
    }

    @Test
    fun `presses close together are one journey from where the first started`() {
        // A double tap run: six jumps of ten seconds, each 600 ms after the last.
        var at = 300_000L
        var offer: SeekUndo.Offer? = null
        for (i in 0 until 6) {
            offer = undo.seeked(at, at + 10_000, nowMs = i * 600L)
            at += 10_000
        }
        assertEquals(300_000L, offer?.backToMs)
    }

    @Test
    fun `presses far apart are separate nudges`() {
        var at = 300_000L
        for (i in 0 until 6) {
            assertNull(undo.seeked(at, at + 10_000, nowMs = i * 5_000L))
            at += 10_000
        }
    }

    @Test
    fun `the offer ends on its own after a few seconds`() {
        undo.seeked(0, 900_000, nowMs = 1_000)
        assertNotNull(undo.current(nowMs = 1_000 + SeekUndo.OFFER_MS - 1))
        assertNull(undo.current(nowMs = 1_000 + SeekUndo.OFFER_MS))
        assertNull(undo.take(nowMs = 1_000 + SeekUndo.OFFER_MS))
    }

    @Test
    fun `any further seek takes the offer down at once`() {
        undo.seeked(120_000, 1_500_000, nowMs = 0)
        assertNull(undo.seeked(1_500_000, 1_495_000, nowMs = 4_000))
        assertNull(undo.current(nowMs = 4_001))
    }

    @Test
    fun `a fresh long jump raises a fresh offer from its own start`() {
        undo.seeked(120_000, 1_500_000, nowMs = 0)
        val offer = undo.seeked(1_500_000, 300_000, nowMs = 1_000)
        assertEquals(SeekUndo.Offer(1_500_000, 1_000 + SeekUndo.OFFER_MS), offer)
    }

    @Test
    fun `going back by hand ends the offer`() {
        undo.seeked(120_000, 1_500_000, nowMs = 0)
        assertNull(undo.seeked(1_500_000, 121_000, nowMs = 3_000))
        assertNull(undo.current(nowMs = 3_001))
    }

    @Test
    fun `the seek taking the offer is not a jump of its own`() {
        undo.seeked(120_000, 1_500_000, nowMs = 0)
        val back = undo.take(nowMs = 1_000)!!
        // Lands a keyframe short.
        assertNull(undo.seeked(1_500_400, back - 800, nowMs = 1_100))
        // And the next long jump offers again, from the place returned to.
        assertEquals(119_200L, undo.seeked(119_200, 900_000, nowMs = 20_000)?.backToMs)
    }

    @Test
    fun `clear forgets the journey`() {
        undo.seeked(0, 50_000, nowMs = 0)
        undo.clear()
        assertNull(undo.seeked(50_000, 100_000, nowMs = 500))
    }
}
