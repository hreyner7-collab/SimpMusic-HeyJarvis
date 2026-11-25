package com.maxrave.simpmusic

import com.maxrave.simpmusic.notch.hsvToArgb
import org.junit.Test
import kotlin.test.assertEquals

class ReliabilityGuardsTest {
    @Test
    fun hsvToArgbHandlesNonFiniteInputsWithoutThrowing() {
        val nanColor = hsvToArgb(Float.NaN, Float.NaN, Float.NaN)
        assertEquals(0xFF000000L, nanColor and 0xFF000000L)

        val infColor = hsvToArgb(Float.POSITIVE_INFINITY, 1f, 1f)
        assertEquals(0xFF000000L, infColor and 0xFF000000L)

        val red = hsvToArgb(0f, 1f, 1f)
        assertEquals(0xFFFF0000L, red)
    }
}
