package com.maxrave.simpmusic.utils

actual fun detectHardwareProfile(): HardwareProfile {
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    // Android heap limit is a lightweight proxy when no ActivityManager Context is passed here;
    // phones with <= 4 cores or <= 256MB VM heap map to low-end defaults cleanly.
    val heapMb = (Runtime.getRuntime().maxMemory().coerceAtLeast(0L)) / (1024L * 1024L)
    val approxRamMb =
        when {
            heapMb <= 128L -> 3072L
            heapMb <= 256L -> 4096L
            heapMb <= 384L -> 6144L
            else -> 8192L
        }
    val isLowEnd = cores <= 4 || approxRamMb <= 6144L
    return HardwareProfile(
        cpuCores = cores,
        totalRamMb = approxRamMb,
        isLowEndHardware = isLowEnd,
    )
}

actual fun applyPlatformPerformanceSettings(settings: PerformanceSettings) {
    // Desktop-specific frame governor hooks are no-ops on Android.
}

actual fun readDesktopRendererOverride(): String = ""

actual fun writeDesktopRendererOverride(api: String) {}

actual fun readDesktopAudioDriverOverride(): String = ""

actual fun writeDesktopAudioDriverOverride(driver: String) {}

actual fun isJarvisLowRamEnabled(): Boolean = false

actual fun setJarvisLowRamEnabled(enabled: Boolean) {}

actual fun isWindowsHost(): Boolean = false
