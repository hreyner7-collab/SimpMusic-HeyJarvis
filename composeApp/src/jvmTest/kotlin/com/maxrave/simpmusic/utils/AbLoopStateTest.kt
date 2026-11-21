package com.maxrave.simpmusic.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbLoopStateTest {
    @Test
    fun `toggle state machine sets A then B then clears`() {
        val duration = 180_000L
        var loop = AbLoopState()
        assertFalse(loop.isActive)

        // 1st tap sets A
        loop = loop.toggleAt(positionMs = 30_000L, durationMs = duration)
        assertEquals(30_000L, loop.pointAMs)
        assertNull(loop.pointBMs)
        assertFalse(loop.isActive)

        // 2nd tap >= 500ms after A sets B and activates loop
        loop = loop.toggleAt(positionMs = 75_000L, durationMs = duration)
        assertEquals(30_000L, loop.pointAMs)
        assertEquals(75_000L, loop.pointBMs)
        assertTrue(loop.isActive)

        // 3rd tap resets both A and B
        loop = loop.toggleAt(positionMs = 50_000L, durationMs = duration)
        assertNull(loop.pointAMs)
        assertNull(loop.pointBMs)
        assertFalse(loop.isActive)
    }

    @Test
    fun `setting B before or too close to A resets loop cleanly`() {
        val duration = 120_000L
        val withA = AbLoopState().toggleAt(positionMs = 40_000L, durationMs = duration)

        assertEquals(AbLoopState(), withA.toggleAt(positionMs = 40_300L, durationMs = duration))
        assertEquals(AbLoopState(), withA.toggleAt(positionMs = 10_000L, durationMs = duration))
    }

    @Test
    fun `shouldLoopBack triggers only near or past B`() {
        val active = AbLoopState(pointAMs = 10_000L, pointBMs = 50_000L)
        assertFalse(active.shouldLoopBack(49_500L))
        assertTrue(active.shouldLoopBack(50_000L - AbLoopState.LOOP_TRIGGER_LEAD_MS))
        assertTrue(active.shouldLoopBack(50_100L))

        val partial = AbLoopState(pointAMs = 10_000L, pointBMs = null)
        assertFalse(partial.shouldLoopBack(50_000L))
    }

    @Test
    fun `seekPercentForPointA converts point A into 0 to 100 percentage`() {
        val active = AbLoopState(pointAMs = 45_000L, pointBMs = 90_000L)
        assertEquals(25f, active.seekPercentForPointA(180_000L))
        assertNull(active.seekPercentForPointA(0L))
        assertNull(AbLoopState(pointAMs = 45_000L).seekPercentForPointA(180_000L))
    }

    @Test
    fun `formatLoopTime formats mmss and hhmmss`() {
        assertEquals("0:00", AbLoopState.formatLoopTime(0L))
        assertEquals("1:05", AbLoopState.formatLoopTime(65_000L))
        assertEquals("1:02:03", AbLoopState.formatLoopTime(3_723_000L))
    }
}
