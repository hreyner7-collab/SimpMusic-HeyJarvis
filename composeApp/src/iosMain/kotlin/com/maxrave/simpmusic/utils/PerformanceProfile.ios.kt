package com.maxrave.simpmusic.utils

actual fun detectHardwareProfile(): HardwareProfile =
    HardwareProfile(
        cpuCores = 6,
        totalRamMb = 6144L,
        isLowEndHardware = false,
    )

actual fun applyPlatformPerformanceSettings(settings: PerformanceSettings) {}

actual fun readDesktopRendererOverride(): String = "AUTO"

actual fun writeDesktopRendererOverride(api: String) {}

actual fun readDesktopAudioDriverOverride(): String = "AUTO"

actual fun writeDesktopAudioDriverOverride(driver: String) {}

actual fun isJarvisLowRamEnabled(): Boolean = false

actual fun setJarvisLowRamEnabled(enabled: Boolean) {}

actual fun isWindowsHost(): Boolean = false
