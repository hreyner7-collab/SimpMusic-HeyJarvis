package com.maxrave.simpmusic.dj

/**
 * Listens through the microphone for one spoken request to the DJ ("play something calm"). It uses
 * the speech recognition the operating system already has, so it needs no account and no download.
 */
expect object DjListen {
    /** False where this build has no speech recognition to call. */
    fun isAvailable(): Boolean

    /** Listens for one phrase and returns it, or null when nothing was heard or listening failed. */
    suspend fun listen(): String?
}
