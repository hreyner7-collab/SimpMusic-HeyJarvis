package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class JerryCommandCoordinator(private val playback: JerryPlayback) {
    private val mutex = Mutex()
    private var search: Job? = null

    suspend fun execute(command: JerryCommand): String = when (command) {
        JerryCommand.VolumeUp -> {
            playback.adjustVolume(true)
            "Volume up"
        }
        JerryCommand.VolumeDown -> {
            playback.adjustVolume(false)
            "Volume down"
        }
        is JerryCommand.SetVolume -> {
            val pct = command.percent.coerceIn(0, 100)
            playback.setVolume(pct)
            if (pct % 10 == 0 && pct in 10..100) {
                "Volume ${pct / 10} ($pct%)"
            } else {
                "Volume $pct%"
            }
        }
        JerryCommand.Mute -> {
            playback.mute()
            "Muted"
        }
        JerryCommand.Unmute -> {
            playback.unmute()
            "Unmuted"
        }
        JerryCommand.Next -> {
            cancelSearch()
            if (playback.next()) "Next song" else "No next song"
        }
        JerryCommand.Previous -> {
            cancelSearch()
            if (playback.previous()) "Previous song" else "No previous song"
        }
        JerryCommand.Pause -> {
            cancelSearch()
            playback.pause()
            "Paused"
        }
        JerryCommand.Stop -> {
            cancelSearch()
            playback.stop()
            "Stopped"
        }
        JerryCommand.Resume -> {
            cancelSearch()
            if (playback.resume()) "Playing" else "Choose a song first"
        }
        JerryCommand.RestartTrack -> {
            cancelSearch()
            if (playback.restartTrack()) "Restarting song" else "Nothing is playing"
        }
        is JerryCommand.SeekForward -> {
            val secs = command.seconds.coerceAtLeast(1)
            if (playback.seekBy(secs)) "Skipped forward ${secs}s" else "Nothing is playing"
        }
        is JerryCommand.SeekBackward -> {
            val secs = command.seconds.coerceAtLeast(1)
            if (playback.seekBy(-secs)) "Rewound ${secs}s" else "Nothing is playing"
        }
        is JerryCommand.SeekToPercent -> {
            val pct = command.percent.coerceIn(0, 100)
            if (playback.seekToPercent(pct)) "Seeked to $pct%" else "Nothing is playing"
        }
        is JerryCommand.SetShuffle -> {
            if (playback.setShuffle(command.enabled)) "Shuffle on" else "Shuffle off"
        }
        is JerryCommand.SetRepeat -> {
            when (playback.setRepeat(command.mode)) {
                RepeatTarget.OFF -> "Repeat off"
                RepeatTarget.ALL -> "Repeat all"
                RepeatTarget.ONE -> "Repeat one"
                RepeatTarget.CYCLE -> "Repeat updated"
            }
        }
        is JerryCommand.ToggleLike -> {
            when (playback.toggleLike(command.like)) {
                true -> "Liked song"
                false -> "Removed like"
                null -> "Nothing is playing"
            }
        }
        JerryCommand.Dislike -> {
            if (playback.dislike()) "Disliked song" else "Nothing is playing"
        }
        is JerryCommand.SetSleepTimer -> {
            val mins = command.minutes
            if (mins == null || mins <= 0) {
                playback.setSleepTimer(null)
                "Sleep timer cancelled"
            } else if (playback.setSleepTimer(mins)) {
                "Sleep timer set for $mins min"
            } else {
                "Could not set sleep timer"
            }
        }
        is JerryCommand.PlayQuery -> runSearch { "Playing ${playback.playQuery(command.query)}" }
        is JerryCommand.PlayArtist -> runSearch { "Playing ${playback.playArtist(command.artist, command.shuffle)}" }
        is JerryCommand.PlaySong -> runSearch { "Playing ${playback.playSong(command.query)}" }
        is JerryCommand.PlayAlbum -> runSearch { "Playing ${playback.playAlbum(command.query)}" }
        is JerryCommand.PlayPlaylist -> runSearch { "Playing ${playback.playPlaylist(command.query)}" }
        is JerryCommand.PlayGenreOrMood -> runSearch { "Playing ${playback.playGenreOrMood(command.query)}" }
        is JerryCommand.PlayRadio -> runSearch { "Playing ${playback.playRadio(command.query)}" }
        is JerryCommand.PlayNextQuery -> runSearch { "Playing next: ${playback.playNextQuery(command.query)}" }
        is JerryCommand.AddToQueueQuery -> runSearch { "Added ${playback.addToQueueQuery(command.query)} to queue" }
        JerryCommand.ClearQueue -> {
            cancelSearch()
            if (playback.clearQueue()) "Queue cleared" else "Queue is already empty"
        }
        JerryCommand.ShowQueue -> {
            if (playback.showQueue()) "Showing queue" else "Open app to view queue"
        }
        is JerryCommand.Navigate -> {
            if (playback.navigate(command.target)) "Opening ${navLabel(command.target)}" else "Open app to navigate"
        }
    }

    private suspend fun cancelSearch() = mutex.withLock {
        search?.cancel()
        search = null
    }

    private suspend fun runSearch(block: suspend () -> String): String {
        val caller = coroutineContext[Job]!!
        mutex.withLock {
            if (search !== caller) search?.cancel()
            search = caller
        }
        return try {
            block()
        } catch (e: CancellationException) {
            "Cancelled"
        } catch (e: JerryPlaybackException) {
            e.message ?: "Could not play that"
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (search === caller) search = null
                }
            }
        }
    }

    private fun navLabel(target: JerryNavTarget): String = when (target) {
        JerryNavTarget.HOME -> "home"
        JerryNavTarget.SEARCH -> "search"
        JerryNavTarget.LIBRARY -> "library"
        JerryNavTarget.SETTINGS -> "settings"
        JerryNavTarget.MIX_FOR_YOU -> "mix for you"
        JerryNavTarget.ANALYTICS -> "analytics"
        JerryNavTarget.NOW_PLAYING -> "now playing"
        JerryNavTarget.QUEUE -> "queue"
        JerryNavTarget.FULLSCREEN -> "fullscreen"
        JerryNavTarget.BACK -> "back"
    }
}
