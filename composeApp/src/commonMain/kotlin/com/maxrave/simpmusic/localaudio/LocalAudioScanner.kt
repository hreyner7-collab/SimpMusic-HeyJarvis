package com.maxrave.simpmusic.localaudio

data class LocalAudioScanResult(
    val tracks: List<LocalAudioTrack>,
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
)

expect object LocalAudioScanner {
    fun defaultFolders(): List<String>

    suspend fun scan(watchedFolders: List<String>): LocalAudioScanResult
}
