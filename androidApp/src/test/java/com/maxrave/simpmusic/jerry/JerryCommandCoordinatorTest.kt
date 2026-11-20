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
        val calls = mutableListOf<String>()
        var muted = false
        var lastNav: JerryNavTarget? = null
        val result = CompletableDeferred<Unit>()
        override suspend fun playQuery(query: String): String {
            result.await()
            track = query
            playing = true
            calls += "playQuery:$query"
            return query
        }
        override suspend fun playArtist(artist: String, shuffle: Boolean): String {
            result.await()
            track = artist
            playing = true
            calls += "playArtist:$artist:$shuffle"
            return artist
        }
        override suspend fun playSong(query: String): String {
            result.await()
            track = query
            playing = true
            calls += "playSong:$query"
            return query
        }
        override suspend fun playAlbum(query: String): String {
            result.await()
            track = query
            playing = true
            calls += "playAlbum:$query"
            return query
        }
        override suspend fun playPlaylist(query: String): String {
            result.await()
            track = query
            playing = true
            calls += "playPlaylist:$query"
            return query
        }
        override suspend fun playGenreOrMood(query: String): String {
            result.await()
            track = query
            playing = true
            calls += "playGenreOrMood:$query"
            return query
        }
        override suspend fun playRadio(query: String?): String {
            result.await()
            track = query ?: "radio"
            playing = true
            calls += "playRadio:$query"
            return track!!
        }
        override suspend fun resume(): Boolean { playing = available; return available }
        override suspend fun pause() { playing = false }
        override suspend fun stop() { playing = false }
        override suspend fun next(): Boolean { index++; return true }
        override suspend fun previous(): Boolean { index--; return true }
        override fun adjustVolume(up: Boolean) { stepVolume = (stepVolume + if (up) 1 else -1).coerceIn(0, 10) }
        override fun setVolume(percent: Int) { volumePercent = percent }
        override suspend fun restartTrack(): Boolean { calls += "restartTrack"; return true }
        override suspend fun seekBy(deltaSeconds: Int): Boolean { calls += "seekBy:$deltaSeconds"; return true }
        override suspend fun seekToPercent(percent: Int): Boolean { calls += "seekToPercent:$percent"; return true }
        override suspend fun setShuffle(enabled: Boolean?): Boolean { calls += "setShuffle:$enabled"; return enabled ?: true }
        override suspend fun setRepeat(mode: RepeatTarget): RepeatTarget { calls += "setRepeat:$mode"; return mode }
        override suspend fun toggleLike(like: Boolean?): Boolean? { calls += "toggleLike:$like"; return like ?: true }
        override suspend fun dislike(): Boolean { calls += "dislike"; return true }
        override suspend fun setSleepTimer(minutes: Int?): Boolean { calls += "setSleepTimer:$minutes"; return (minutes ?: 0) > 0 }
        override suspend fun playNextQuery(query: String): String { calls += "playNextQuery:$query"; return query }
        override suspend fun addToQueueQuery(query: String): String { calls += "addToQueueQuery:$query"; return query }
        override suspend fun clearQueue(): Boolean { calls += "clearQueue"; return true }
        override suspend fun showQueue(): Boolean { calls += "showQueue"; lastNav = JerryNavTarget.QUEUE; return true }
        override suspend fun mute() { muted = true; calls += "mute" }
        override suspend fun unmute() { muted = false; calls += "unmute" }
        override suspend fun navigate(target: JerryNavTarget): Boolean { lastNav = target; calls += "navigate:$target"; return true }
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

    @Test fun dispatchesAllNewCatalogTransportQueueMuteAndNavCommands() = runTest {
        val player = Playback().apply { result.complete(Unit) }
        val coordinator = JerryCommandCoordinator(player)

        coordinator.execute(JerryCommand.PlayArtist("The Weeknd", shuffle = true))
        coordinator.execute(JerryCommand.PlaySong("Lose Yourself"))
        coordinator.execute(JerryCommand.PlayAlbum("Abbey Road"))
        coordinator.execute(JerryCommand.PlayPlaylist("Chill Hits"))
        coordinator.execute(JerryCommand.PlayGenreOrMood("smooth jazz"))
        coordinator.execute(JerryCommand.PlayRadio("Daft Punk"))
        coordinator.execute(JerryCommand.RestartTrack)
        coordinator.execute(JerryCommand.SeekForward(30))
        coordinator.execute(JerryCommand.SeekBackward(15))
        coordinator.execute(JerryCommand.SeekToPercent(50))
        coordinator.execute(JerryCommand.SetShuffle(true))
        coordinator.execute(JerryCommand.SetRepeat(RepeatTarget.ALL))
        coordinator.execute(JerryCommand.ToggleLike(true))
        coordinator.execute(JerryCommand.Dislike)
        coordinator.execute(JerryCommand.SetSleepTimer(30))
        coordinator.execute(JerryCommand.PlayNextQuery("Take Five"))
        coordinator.execute(JerryCommand.AddToQueueQuery("Blinding Lights"))
        coordinator.execute(JerryCommand.ClearQueue)
        coordinator.execute(JerryCommand.ShowQueue)
        coordinator.execute(JerryCommand.Mute)
        assertTrue(player.muted)
        coordinator.execute(JerryCommand.Unmute)
        assertFalse(player.muted)
        coordinator.execute(JerryCommand.Navigate(JerryNavTarget.SETTINGS))
        assertEquals(JerryNavTarget.SETTINGS, player.lastNav)

        assertTrue("playArtist:The Weeknd:true" in player.calls)
        assertTrue("playGenreOrMood:smooth jazz" in player.calls)
        assertTrue("seekBy:30" in player.calls)
        assertTrue("seekBy:-15" in player.calls)
        assertTrue("seekToPercent:50" in player.calls)
        assertTrue("setShuffle:true" in player.calls)
        assertTrue("setRepeat:ALL" in player.calls)
        assertTrue("toggleLike:true" in player.calls)
        assertTrue("dislike" in player.calls)
        assertTrue("setSleepTimer:30" in player.calls)
        assertTrue("playNextQuery:Take Five" in player.calls)
        assertTrue("addToQueueQuery:Blinding Lights" in player.calls)
        assertTrue("clearQueue" in player.calls)
        assertTrue("showQueue" in player.calls)
    }

    @Test fun genreQuerySupersedesPendingPlayQueryWhileMuteDoesNotCancelSearch() = runTest {
        val player = Playback()
        val coordinator = JerryCommandCoordinator(player)
        val old = launch { coordinator.execute(JerryCommand.PlayQuery("old")) }
        runCurrent()
        val latest = launch { coordinator.execute(JerryCommand.PlayGenreOrMood("smooth jazz")) }
        runCurrent()
        coordinator.execute(JerryCommand.Mute)
        assertTrue(player.muted)
        assertFalse(latest.isCancelled)
        player.result.complete(Unit)
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals("smooth jazz", player.track)
    }
}
