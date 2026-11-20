package com.maxrave.simpmusic.jerry

interface JerryPlayback {
    suspend fun playQuery(query: String): String
    suspend fun resume(): Boolean
    suspend fun pause()
    suspend fun stop()
    suspend fun next(): Boolean
    suspend fun previous(): Boolean
    fun adjustVolume(up: Boolean)
    fun setVolume(percent: Int) {}

    suspend fun playArtist(artist: String, shuffle: Boolean = false): String =
        throw JerryPlaybackException("Not supported")

    suspend fun playSong(query: String): String = playQuery(query)

    suspend fun playAlbum(query: String): String =
        throw JerryPlaybackException("Not supported")

    suspend fun playPlaylist(query: String): String =
        throw JerryPlaybackException("Not supported")

    suspend fun playGenreOrMood(query: String): String =
        throw JerryPlaybackException("Not supported")

    suspend fun playRadio(query: String? = null): String =
        throw JerryPlaybackException("Not supported")

    suspend fun restartTrack(): Boolean = false
    suspend fun seekBy(deltaSeconds: Int): Boolean = false
    suspend fun seekToPercent(percent: Int): Boolean = false
    suspend fun setShuffle(enabled: Boolean?): Boolean = false
    suspend fun setRepeat(mode: RepeatTarget): RepeatTarget = RepeatTarget.OFF
    suspend fun toggleLike(like: Boolean?): Boolean? = null
    suspend fun dislike(): Boolean = false
    suspend fun setSleepTimer(minutes: Int?): Boolean = false

    suspend fun playNextQuery(query: String): String =
        throw JerryPlaybackException("Not supported")

    suspend fun addToQueueQuery(query: String): String =
        throw JerryPlaybackException("Not supported")

    suspend fun clearQueue(): Boolean = false
    suspend fun showQueue(): Boolean = false
    suspend fun mute() {}
    suspend fun unmute() {}
    suspend fun navigate(target: JerryNavTarget): Boolean = false
}
