package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.StateFlow

/** Platform bridge for the settings toggle and desktop diagnostics. Android uses the permission sheet instead. */
expect object JarvisController {
    val running: StateFlow<Boolean>
    val diagnostics: StateFlow<JarvisDiagnostics>

    fun start()

    fun stop()

    fun refreshDevices()

    fun setPreferredMic(micName: String)

    fun setSensitivity(sensitivity: JarvisSensitivity)

    fun setWakeChimeEnabled(enabled: Boolean)

    fun downloadHighAccuracyModel()

    fun triggerManualWake()

    fun onLowRamChanged(lowRam: Boolean)
}
