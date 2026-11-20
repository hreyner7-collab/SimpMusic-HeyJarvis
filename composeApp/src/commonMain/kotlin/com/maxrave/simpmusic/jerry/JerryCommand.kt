package com.maxrave.simpmusic.jerry

enum class RepeatTarget { OFF, ALL, ONE, CYCLE }

enum class JerryNavTarget {
    HOME, SEARCH, LIBRARY, SETTINGS, MIX_FOR_YOU, ANALYTICS,
    NOW_PLAYING, QUEUE, FULLSCREEN, BACK
}

sealed interface JerryCommand {
    // Playback transport & modes
    data object Next : JerryCommand
    data object Previous : JerryCommand
    data object Resume : JerryCommand
    data object Pause : JerryCommand
    data object Stop : JerryCommand
    data object RestartTrack : JerryCommand
    data class SeekForward(val seconds: Int = 10) : JerryCommand
    data class SeekBackward(val seconds: Int = 10) : JerryCommand
    data class SeekToPercent(val percent: Int) : JerryCommand
    data class SetShuffle(val enabled: Boolean? = null) : JerryCommand // null = toggle
    data class SetRepeat(val mode: RepeatTarget = RepeatTarget.CYCLE) : JerryCommand
    data class ToggleLike(val like: Boolean? = null) : JerryCommand // null = toggle
    data object Dislike : JerryCommand
    data class SetSleepTimer(val minutes: Int?) : JerryCommand // null or 0 = cancel

    // Volume
    data object VolumeUp : JerryCommand
    data object VolumeDown : JerryCommand
    data class SetVolume(val percent: Int) : JerryCommand
    data object Mute : JerryCommand
    data object Unmute : JerryCommand

    // Media queries (PlayQuery kept for general/backward compatibility)
    data class PlayQuery(val query: String) : JerryCommand
    data class PlayArtist(val artist: String, val shuffle: Boolean = false) : JerryCommand
    data class PlaySong(val query: String) : JerryCommand
    data class PlayAlbum(val query: String) : JerryCommand
    data class PlayPlaylist(val query: String) : JerryCommand
    data class PlayGenreOrMood(val query: String) : JerryCommand
    data class PlayRadio(val query: String? = null) : JerryCommand

    // Queue
    data class PlayNextQuery(val query: String) : JerryCommand
    data class AddToQueueQuery(val query: String) : JerryCommand
    data object ClearQueue : JerryCommand
    data object ShowQueue : JerryCommand

    // Navigation
    data class Navigate(val target: JerryNavTarget) : JerryCommand
}
