package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The filing of a mostly vertical drag on the enlarged picture (v1.0.13): where the finger went
 * down decides whether it is a level drag or a pan. Only the pure region decision is covered
 * here: the drag itself is filed by an Android GestureDetector over MotionEvents, which the
 * local test harness cannot drive, and the pan producing no zoom chip lives in the activity,
 * which the harness cannot stand up either.
 */
class PlayerGesturesTest {

    /** A 1000 by 500 picture: the outer quarters are the level strips, the middle half pans. */
    private val width = 1000

    @Test
    fun `a zoomed drag starting in the left strip files brightness`() {
        assertEquals(ZoomedVerticalDrag.Brightness, PlayerGestures.zoomedVerticalDrag(0f, width))
        assertEquals(ZoomedVerticalDrag.Brightness, PlayerGestures.zoomedVerticalDrag(249f, width))
    }

    @Test
    fun `a zoomed drag starting in the right strip files volume`() {
        assertEquals(ZoomedVerticalDrag.Volume, PlayerGestures.zoomedVerticalDrag(751f, width))
        assertEquals(ZoomedVerticalDrag.Volume, PlayerGestures.zoomedVerticalDrag(999f, width))
    }

    @Test
    fun `a zoomed drag starting in the middle files a pan`() {
        assertEquals(ZoomedVerticalDrag.Pan, PlayerGestures.zoomedVerticalDrag(250f, width))
        assertEquals(ZoomedVerticalDrag.Pan, PlayerGestures.zoomedVerticalDrag(500f, width))
        assertEquals(ZoomedVerticalDrag.Pan, PlayerGestures.zoomedVerticalDrag(750f, width))
    }

    @Test
    fun `an unreadable width files a pan`() {
        assertEquals(ZoomedVerticalDrag.Pan, PlayerGestures.zoomedVerticalDrag(100f, 0))
    }
}
