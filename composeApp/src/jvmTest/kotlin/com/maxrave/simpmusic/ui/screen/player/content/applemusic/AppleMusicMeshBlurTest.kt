package com.maxrave.simpmusic.ui.screen.player.content.applemusic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleMusicMeshBlurTest {
    @Test
    fun `boxBlurArgb smooths hard edges and preserves opaque alpha on 2D mesh texture`() {
        val w = 32
        val h = 32
        val raw =
            IntArray(w * h) { idx ->
                val x = idx % w
                if (x < w / 2) 0x80FF0000.toInt() else 0x400000FF
            }

        val blurred = boxBlurArgb(pixels = raw, width = w, height = h, radius = 3, passes = 2)
        assertEquals(raw.size, blurred.size)

        // Every output texel must have full opaque alpha
        assertTrue(blurred.all { (it ushr 24) == 0xFF })

        // Seam column should now contain blended red and blue channels rather than hard step
        val seamPixel = blurred[16 * w + 16]
        val red = (seamPixel ushr 16) and 0xFF
        val blue = seamPixel and 0xFF
        assertTrue(red in 40..215, "Expected blended red at seam, got $red")
        assertTrue(blue in 40..215, "Expected blended blue at seam, got $blue")
    }

    @Test
    fun `boxBlurArgb supports 1D top-edge band without crashing`() {
        val band = IntArray(32) { i -> if (i < 16) 0xFF204060.toInt() else 0xFFA0C0E0.toInt() }
        val blurred = boxBlurArgb(pixels = band, width = 32, height = 1, radius = 3, passes = 2)
        assertEquals(32, blurred.size)
        assertTrue(blurred.all { (it ushr 24) == 0xFF })
    }
}
