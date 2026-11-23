package com.maxrave.simpmusic.dj

/**
 * The AI DJ's voice: the system's own text-to-speech engine (Windows speech, macOS `say`, Android
 * TextToSpeech). It needs no account and no download.
 */
expect object DjVoice {
    /** False where the platform has no speech engine to call. */
    fun isAvailable(): Boolean

    /** Speaks [text] and returns when it has finished. Cancelling the caller stops the speech. */
    /** Gets a better voice ready in the background where the platform has one to fetch; otherwise nothing. */
    fun warmUp()

    /** True when the listener chose the plain system voice over the natural one. */
    var preferSystemVoice: Boolean

    suspend fun speak(text: String)

    /** Plays the bundled Ace DJ intro clip and returns when it finishes. */
    suspend fun playIntroClip()

    fun stop()
}
