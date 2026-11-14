package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.StateFlow

actual object JarvisController {
    actual val running: StateFlow<Boolean> = DesktopJarvis.running
    actual fun start() = DesktopJarvis.start()
    actual fun stop() = DesktopJarvis.stop()
}
