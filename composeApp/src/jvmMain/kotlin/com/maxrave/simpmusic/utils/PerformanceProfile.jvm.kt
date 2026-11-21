package com.maxrave.simpmusic.utils

import com.maxrave.simpmusic.DesktopFrameGovernor
import java.io.File
import java.lang.management.ManagementFactory

private val VALID_RENDERERS = setOf("DIRECT3D", "OPENGL", "SOFTWARE", "METAL")
private val VALID_AUDIO_DRIVERS = setOf("wasapi", "winmm", "sdl")

private fun simpmusicConfigFile(name: String): File =
    File(File(System.getProperty("user.home"), ".simpmusic"), name)

private fun readTrimmedConfig(name: String): String =
    runCatching {
        val file = simpmusicConfigFile(name)
        if (file.isFile) file.readText().trim() else ""
    }.getOrDefault("")

private fun writeTrimmedConfig(
    name: String,
    value: String,
) {
    runCatching {
        val file = simpmusicConfigFile(name)
        if (value.isBlank()) {
            if (file.exists()) file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(value.trim())
        }
    }
}

actual fun detectHardwareProfile(): HardwareProfile {
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val osBytes =
        runCatching {
            val bean = ManagementFactory.getOperatingSystemMXBean()
            val sunBean = bean as? com.sun.management.OperatingSystemMXBean
            sunBean?.totalMemorySize ?: 0L
        }.getOrDefault(0L)
    val totalBytes = if (osBytes > 0L) osBytes else Runtime.getRuntime().maxMemory().coerceAtLeast(0L)
    val totalRamMb = totalBytes / (1024L * 1024L)
    val isLowEnd = cores <= 4 || (totalRamMb in 1..6144L)
    return HardwareProfile(
        cpuCores = cores,
        totalRamMb = totalRamMb,
        isLowEndHardware = isLowEnd,
    )
}

actual fun applyPlatformPerformanceSettings(settings: PerformanceSettings) {
    DesktopFrameGovernor.unfocusedFps = settings.unfocusedFps
    DesktopFrameGovernor.update()
}

actual fun readDesktopRendererOverride(): String {
    val value = readTrimmedConfig("renderer.conf").uppercase()
    return if (value in VALID_RENDERERS) value else ""
}

actual fun writeDesktopRendererOverride(api: String) {
    val normalized = api.trim().uppercase()
    writeTrimmedConfig("renderer.conf", if (normalized in VALID_RENDERERS) normalized else "")
}

actual fun readDesktopAudioDriverOverride(): String {
    val value = readTrimmedConfig("audio-driver.conf").lowercase()
    return if (value in VALID_AUDIO_DRIVERS) value else ""
}

actual fun writeDesktopAudioDriverOverride(driver: String) {
    val normalized = driver.trim().lowercase()
    writeTrimmedConfig("audio-driver.conf", if (normalized in VALID_AUDIO_DRIVERS) normalized else "")
}

actual fun isJarvisLowRamEnabled(): Boolean =
    runCatching { simpmusicConfigFile("jarvis.lowram").isFile }.getOrDefault(false)

actual fun setJarvisLowRamEnabled(enabled: Boolean) {
    runCatching {
        val file = simpmusicConfigFile("jarvis.lowram")
        if (enabled) {
            file.parentFile?.mkdirs()
            file.writeText("1")
        } else if (file.exists()) {
            file.delete()
        }
    }
}

actual fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
