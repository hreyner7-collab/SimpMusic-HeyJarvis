package com.maxrave.simpmusic.dj

// Android's speech recognition needs the microphone permission flow; the mic button stays hidden there for now.
actual object DjListen {
    actual fun isAvailable(): Boolean = false

    actual suspend fun listen(): String? = null
}
