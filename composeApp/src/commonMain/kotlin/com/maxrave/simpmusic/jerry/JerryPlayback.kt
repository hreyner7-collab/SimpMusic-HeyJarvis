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
}
