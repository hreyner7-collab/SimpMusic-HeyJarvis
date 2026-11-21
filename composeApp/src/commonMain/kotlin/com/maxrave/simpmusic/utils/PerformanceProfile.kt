package com.maxrave.simpmusic.utils

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.concurrent.Volatile

const val PERFORMANCE_MODE_KEY = "performance_mode"

enum class PerformanceMode(
    val storageValue: String,
) {
    AUTO("auto"),
    ULTRA_LIGHT("ultra_light"),
    BALANCED("balanced"),
    FULL_VISUALS("full_visuals"),
    ;

    companion object {
        fun fromStorage(value: String?): PerformanceMode =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}

data class HardwareProfile(
    val cpuCores: Int,
    val totalRamMb: Long,
    val isLowEndHardware: Boolean,
)

data class PerformanceSettings(
    val selectedMode: PerformanceMode,
    val effectiveMode: PerformanceMode,
    val enableBlur: Boolean,
    val enableMeshAnimation: Boolean,
    val reduceAnimations: Boolean,
    val enableShimmer: Boolean,
    val enableLyricsBlur: Boolean,
    val unfocusedFps: Int,
    val coilMemoryCachePercent: Double,
    val imageMaxConcurrentRequests: Int,
)

fun resolvePerformanceSettings(
    mode: PerformanceMode,
    hw: HardwareProfile,
): PerformanceSettings {
    val effective =
        when (mode) {
            PerformanceMode.AUTO ->
                when {
                    hw.cpuCores <= 4 || hw.totalRamMb in 1..6144L -> PerformanceMode.ULTRA_LIGHT
                    hw.cpuCores <= 6 || hw.totalRamMb in 1..10240L -> PerformanceMode.BALANCED
                    else -> PerformanceMode.FULL_VISUALS
                }

            else -> mode
        }

    return when (effective) {
        PerformanceMode.ULTRA_LIGHT,
        PerformanceMode.AUTO,
        ->
            PerformanceSettings(
                selectedMode = mode,
                effectiveMode = PerformanceMode.ULTRA_LIGHT,
                enableBlur = false,
                enableMeshAnimation = false,
                reduceAnimations = true,
                enableShimmer = false,
                enableLyricsBlur = false,
                unfocusedFps = 10,
                coilMemoryCachePercent = 0.08,
                imageMaxConcurrentRequests = 12,
            )

        PerformanceMode.BALANCED ->
            PerformanceSettings(
                selectedMode = mode,
                effectiveMode = PerformanceMode.BALANCED,
                enableBlur = true,
                enableMeshAnimation = false,
                reduceAnimations = false,
                enableShimmer = true,
                enableLyricsBlur = false,
                unfocusedFps = 15,
                coilMemoryCachePercent = 0.14,
                imageMaxConcurrentRequests = 24,
            )

        PerformanceMode.FULL_VISUALS ->
            PerformanceSettings(
                selectedMode = mode,
                effectiveMode = PerformanceMode.FULL_VISUALS,
                enableBlur = true,
                enableMeshAnimation = true,
                reduceAnimations = false,
                enableShimmer = true,
                enableLyricsBlur = true,
                unfocusedFps = 30,
                coilMemoryCachePercent = 0.20,
                imageMaxConcurrentRequests = 48,
            )
    }
}

expect fun detectHardwareProfile(): HardwareProfile

expect fun applyPlatformPerformanceSettings(settings: PerformanceSettings)

expect fun readDesktopRendererOverride(): String

expect fun writeDesktopRendererOverride(api: String)

expect fun readDesktopAudioDriverOverride(): String

expect fun writeDesktopAudioDriverOverride(driver: String)

expect fun isJarvisLowRamEnabled(): Boolean

expect fun setJarvisLowRamEnabled(enabled: Boolean)

expect fun isWindowsHost(): Boolean

object PerformanceRuntime {
    val hardwareProfile: HardwareProfile by lazy { detectHardwareProfile() }

    @Volatile
    var current: PerformanceSettings = resolvePerformanceSettings(PerformanceMode.AUTO, hardwareProfile)
}

val LocalPerformanceSettings = staticCompositionLocalOf { PerformanceRuntime.current }
