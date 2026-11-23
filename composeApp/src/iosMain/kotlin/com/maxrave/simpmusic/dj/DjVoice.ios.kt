package com.maxrave.simpmusic.dj

// The iPhone speech engine is not wired yet; the DJ switch stays off there.
actual object DjVoice {
    actual fun isAvailable(): Boolean = false

    actual fun warmUp() = Unit

    actual var preferSystemVoice: Boolean = false

    actual suspend fun speak(text: String) = Unit

    actual suspend fun playIntroClip() = Unit

    actual fun stop() = Unit
}
