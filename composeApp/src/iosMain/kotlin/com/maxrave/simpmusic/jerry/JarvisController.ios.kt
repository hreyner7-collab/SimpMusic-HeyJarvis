package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object JarvisController {
    actual val running: StateFlow<Boolean> = MutableStateFlow(false)
    actual val diagnostics: StateFlow<JarvisDiagnostics> = MutableStateFlow(JarvisDiagnostics())

    actual fun start() {}

    actual fun stop() {}

    actual fun refreshDevices() {}

    actual fun setPreferredMic(micName: String) {}

    actual fun setSensitivity(sensitivity: JarvisSensitivity) {}

    actual fun setWakeChimeEnabled(enabled: Boolean) {}

    actual fun downloadHighAccuracyModel() {}

    actual fun triggerManualWake() {}

    actual fun onLowRamChanged(lowRam: Boolean) {}
}
