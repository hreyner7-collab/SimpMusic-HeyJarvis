package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JerryCommandCoordinatorTest {
    private class Playback : JerryPlayback {
        var playing = false
        var available = true
        var stepVolume = 5
        var volumePercent = 50
        var index = 1
        var track: String? = null
        val result = CompletableDeferred<Unit>()
        override suspend fun playQuery(query: String): String {
            result.await()
            track = query
            playing = true
            return query
        }
        override suspend fun resume(): Boolean { playing = available; return available }
        override suspend fun pause() { playing = false }
        override suspend fun stop() { playing = false }
        override suspend fun next(): Boolean { index++; return true }
        override suspend fun previous(): Boolean { index--; return true }
        override fun adjustVolume(up: Boolean) { stepVolume = (stepVolume + if (up) 1 else -1).coerceIn(0, 10) }
        override fun setVolume(percent: Int) { volumePercent = percent }
    }

    @Test fun executesTransportOnceAndResumesAfterStop() = runTest {
        val player = Playback()
        val coordinator = JerryCommandCoordinator(player)
        coordinator.execute(JerryCommand.Next)
        assertEquals(2, player.index)
        coordinator.execute(JerryCommand.Previous)
        assertEquals(1, player.index)
        coordinator.execute(JerryCommand.Resume)
        assertTrue(player.playing)
        coordinator.execute(JerryCommand.Stop)
        assertFalse(player.playing)
        coordinator.execute(JerryCommand.Resume)
        assertTrue(player.playing)
        coordinator.execute(JerryCommand.Pause)
        assertFalse(player.playing)
    }

    @Test fun stopCancelsPendingSearchWithoutLatePlayback() = runTest {
        val player = Playback()
        val coordinator = JerryCommandCoordinator(player)
        val search = launch { coordinator.execute(JerryCommand.PlayQuery("old song")) }
        runCurrent()
        coordinator.execute(JerryCommand.Stop)
        player.result.complete(Unit)
        runCurrent()
        assertTrue(search.isCancelled)
        assertNull(player.track)
        assertFalse(player.playing)
    }

    @Test fun newSearchSupersedesOldAndVolumeDoesNotCancelIt() = runTest {
        val player = Playback()
        val coordinator = JerryCommandCoordinator(player)
        val old = launch { coordinator.execute(JerryCommand.PlayQuery("old")) }
        runCurrent()
        val latest = launch { coordinator.execute(JerryCommand.PlayQuery("new")) }
        runCurrent()
        coordinator.execute(JerryCommand.VolumeUp)
        assertEquals(6, player.stepVolume)
        assertFalse(latest.isCancelled)
        player.result.complete(Unit)
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals("new", player.track)
    }

    @Test fun reportsEmptyQueueRatherThanPlaybackSuccess() = runTest {
        val player = Playback().apply { available = false }
        val message = JerryCommandCoordinator(player).execute(JerryCommand.Resume)
        assertFalse(player.playing)
        assertTrue(message.contains("song", ignoreCase = true))
    }

    @Test fun executesSetVolumeAcrossScaleAndCustomPercent() = runTest {
        val player = Playback()
        val coordinator = JerryCommandCoordinator(player)
        assertEquals("Volume 5 (50%)", coordinator.execute(JerryCommand.SetVolume(50)))
        assertEquals(50, player.volumePercent)
        assertEquals("Volume 1 (10%)", coordinator.execute(JerryCommand.SetVolume(10)))
        assertEquals(10, player.volumePercent)
        assertEquals("Volume 10 (100%)", coordinator.execute(JerryCommand.SetVolume(100)))
        assertEquals(100, player.volumePercent)
        assertEquals("Volume 75%", coordinator.execute(JerryCommand.SetVolume(75)))
        assertEquals(75, player.volumePercent)
    }
}
