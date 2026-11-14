package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Android enables Jarvis through the permission sheet (JerrySettingsSheet), not this toggle.
actual object JarvisController {
    actual val running: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun start() {}
    actual fun stop() {}
}
