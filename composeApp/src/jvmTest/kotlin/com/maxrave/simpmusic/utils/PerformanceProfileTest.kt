package com.maxrave.simpmusic.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceProfileTest {
    @Test
    fun `auto selects ultra light for 10 year old PC hardware`() {
        val oldDualCore = HardwareProfile(cpuCores = 2, totalRamMb = 4096L, isLowEndHardware = true)
        val settings = resolvePerformanceSettings(PerformanceMode.AUTO, oldDualCore)

        assertEquals(PerformanceMode.AUTO, settings.selectedMode)
        assertEquals(PerformanceMode.ULTRA_LIGHT, settings.effectiveMode)
        assertFalse(settings.enableBlur)
        assertFalse(settings.enableMeshAnimation)
        assertTrue(settings.reduceAnimations)
        assertFalse(settings.enableShimmer)
        assertFalse(settings.enableLyricsBlur)
        assertEquals(10, settings.unfocusedFps)
        assertEquals(0.08, settings.coilMemoryCachePercent)
    }

    @Test
    fun `auto selects balanced for mid-tier 6-core 8gb machine`() {
        val midTier = HardwareProfile(cpuCores = 6, totalRamMb = 8192L, isLowEndHardware = false)
        val settings = resolvePerformanceSettings(PerformanceMode.AUTO, midTier)

        assertEquals(PerformanceMode.BALANCED, settings.effectiveMode)
        assertTrue(settings.enableBlur)
        assertFalse(settings.enableLyricsBlur)
        assertEquals(15, settings.unfocusedFps)
    }

    @Test
    fun `auto selects full visuals for modern 8-core 16gb hardware`() {
        val modern = HardwareProfile(cpuCores = 8, totalRamMb = 16_384L, isLowEndHardware = false)
        val settings = resolvePerformanceSettings(PerformanceMode.AUTO, modern)

        assertEquals(PerformanceMode.FULL_VISUALS, settings.effectiveMode)
        assertTrue(settings.enableBlur)
        assertTrue(settings.enableMeshAnimation)
        assertFalse(settings.reduceAnimations)
        assertTrue(settings.enableLyricsBlur)
        assertEquals(30, settings.unfocusedFps)
    }

    @Test
    fun `explicit user mode overrides hardware auto detection`() {
        val lowEnd = HardwareProfile(cpuCores = 2, totalRamMb = 3072L, isLowEndHardware = true)
        val forcedFull = resolvePerformanceSettings(PerformanceMode.FULL_VISUALS, lowEnd)
        assertEquals(PerformanceMode.FULL_VISUALS, forcedFull.effectiveMode)
        assertTrue(forcedFull.enableBlur)

        assertEquals(PerformanceMode.AUTO, PerformanceMode.fromStorage(null))
        assertEquals(PerformanceMode.ULTRA_LIGHT, PerformanceMode.fromStorage("ultra_light"))
    }
}
