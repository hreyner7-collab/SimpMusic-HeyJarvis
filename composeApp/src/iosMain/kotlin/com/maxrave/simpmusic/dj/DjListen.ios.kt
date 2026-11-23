package com.maxrave.simpmusic.dj

actual object DjListen {
    actual fun isAvailable(): Boolean = false

    actual suspend fun listen(): String? = null
}
