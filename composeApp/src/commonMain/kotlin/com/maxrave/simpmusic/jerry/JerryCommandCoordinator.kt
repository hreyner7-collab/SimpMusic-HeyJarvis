package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class JerryCommandCoordinator(private val playback: JerryPlayback) {
    private val mutex = Mutex()
    private var search: Job? = null

    suspend fun execute(command: JerryCommand): String = when (command) {
        JerryCommand.VolumeUp -> { playback.adjustVolume(true); "Volume up" }
        JerryCommand.VolumeDown -> { playback.adjustVolume(false); "Volume down" }
        is JerryCommand.SetVolume -> {
            val pct = command.percent.coerceIn(0, 100)
            playback.setVolume(pct)
            if (pct % 10 == 0 && pct in 10..100) {
                "Volume ${pct / 10} ($pct%)"
            } else {
                "Volume $pct%"
            }
        }
        JerryCommand.Next -> { cancelSearch(); if (playback.next()) "Next song" else "No next song" }
        JerryCommand.Previous -> { cancelSearch(); if (playback.previous()) "Previous song" else "No previous song" }
        JerryCommand.Pause -> { cancelSearch(); playback.pause(); "Paused" }
        JerryCommand.Stop -> { cancelSearch(); playback.stop(); "Stopped" }
        JerryCommand.Resume -> { cancelSearch(); if (playback.resume()) "Playing" else "Choose a song first" }
        is JerryCommand.PlayQuery -> playQuery(command.query)
    }

    private suspend fun cancelSearch() = mutex.withLock { search?.cancel(); search = null }

    private suspend fun playQuery(query: String): String {
        val caller = coroutineContext[Job]!!
        mutex.withLock { search?.cancel(); search = caller }
        return try {
            val title = playback.playQuery(query)
            "Playing $title"
        } catch (e: CancellationException) {
            "Cancelled"
        } catch (e: JerryPlaybackException) {
            e.message ?: "Could not play that"
        }
    }
}
