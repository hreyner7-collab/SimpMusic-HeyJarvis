package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.StateFlow

actual object JarvisController {
    actual val running: StateFlow<Boolean> = DesktopJarvis.running
    actual val diagnostics: StateFlow<JarvisDiagnostics> = DesktopJarvis.diagnostics

    actual fun start() = DesktopJarvis.start()

    actual fun stop() = DesktopJarvis.stop()

    actual fun refreshDevices() = DesktopJarvis.refreshDevices()

    actual fun setPreferredMic(micName: String) = DesktopJarvis.setPreferredMic(micName)

    actual fun setSensitivity(sensitivity: JarvisSensitivity) = DesktopJarvis.setSensitivity(sensitivity)

    actual fun setWakeChimeEnabled(enabled: Boolean) = DesktopJarvis.setWakeChimeEnabled(enabled)

    actual fun downloadHighAccuracyModel() = DesktopJarvis.downloadHighAccuracyModel()

    actual fun triggerManualWake() = DesktopJarvis.triggerManualWake()

    actual fun onLowRamChanged(lowRam: Boolean) = DesktopJarvis.onLowRamChanged(lowRam)
}
