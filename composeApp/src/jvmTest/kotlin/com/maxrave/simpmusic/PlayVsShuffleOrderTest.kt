package com.maxrave.simpmusic

import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.utils.FastPageCache
import com.maxrave.simpmusic.viewModel.AlbumViewModel
import com.maxrave.simpmusic.viewModel.PlaylistUIEvent
import com.maxrave.simpmusic.viewModel.PlaylistViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayVsShuffleOrderTest {
    private fun makeTrack(
        index: Int,
        idPrefix: String = "trk",
    ): Track =
        Track(
            album = Album(id = "ALB_1", name = "Skepta Album"),
            artists = listOf(Artist(id = "ART_1", name = "Skepta")),
            duration = "3:15",
            durationSeconds = 195,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = emptyList(),
            title = "Song $index",
            videoId = "${idPrefix}_$index",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            feedbackTokens = null,
            resultType = "Song",
        )

    private class RecordingHandler {
        var shuffleEnabled = false
        val setShuffleHistory = mutableListOf<Boolean>()
        val setQueueCalls = mutableListOf<QueueData.Data>()
        val loadMediaItemCalls = mutableListOf<Triple<Any?, String, Int?>>()

        val controlState =
            MutableStateFlow(
                ControlState(
                    isPlaying = false,
                    isShuffle = false,
                    repeatState = RepeatState.None,
                    isLiked = false,
                    isNextAvailable = true,
                    isPreviousAvailable = true,
                    isCrossfading = false,
                    volume = 1f,
                ),
            )
        val nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
        val queueData = MutableStateFlow<QueueData?>(null)
        val simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
        val nowPlaying = MutableStateFlow<GenericMediaItem?>(null)
        val sleepTimerState = MutableStateFlow(SleepTimerState(isDone = false, timeRemaining = 0))
        val skipSegments = MutableStateFlow<List<com.maxrave.domain.data.model.mediaService.SponsorSkipSegments>?>(null)
        val format = MutableStateFlow<com.maxrave.domain.data.entities.NewFormatEntity?>(null)
        val currentSongIndex = MutableStateFlow(0)
        val castState = MutableStateFlow(GenericCastState.NOT_CASTING)

        val playerProxy: MediaPlayerInterface =
            Proxy.newProxyInstance(
                MediaPlayerInterface::class.java.classLoader,
                arrayOf(MediaPlayerInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getShuffleModeEnabled" -> shuffleEnabled
                    "setShuffleModeEnabled" -> {
                        shuffleEnabled = args?.getOrNull(0) as? Boolean ?: false
                        Unit
                    }
                    "getPlaybackParameters" -> GenericPlaybackParameters.DEFAULT
                    else -> defaultReturn(method.returnType)
                }
            } as MediaPlayerInterface

        val proxy: MediaPlayerHandler =
            Proxy.newProxyInstance(
                MediaPlayerHandler::class.java.classLoader,
                arrayOf(MediaPlayerHandler::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getPlayer" -> playerProxy
                    "getControlState" -> controlState
                    "getNowPlayingState" -> nowPlayingState
                    "getQueueData" -> queueData
                    "getSimpleMediaState" -> simpleMediaState
                    "getNowPlaying" -> nowPlaying
                    "getSleepTimerState" -> sleepTimerState
                    "getSkipSegments" -> skipSegments
                    "getFormat" -> format
                    "getCurrentSongIndex" -> currentSongIndex
                    "getCastState" -> castState
                    "setShuffle" -> {
                        val enabled = args?.getOrNull(0) as? Boolean ?: false
                        shuffleEnabled = enabled
                        setShuffleHistory += enabled
                        controlState.value = controlState.value.copy(isShuffle = enabled)
                        Unit
                    }
                    "setQueueData" -> {
                        val q = args?.getOrNull(0) as QueueData.Data
                        setQueueCalls += q
                        queueData.value = QueueData(data = q)
                        Unit
                    }
                    "loadMediaItem" -> {
                        loadMediaItemCalls += Triple(args?.getOrNull(0), args?.getOrNull(1) as String, args?.getOrNull(2) as? Int)
                        Unit
                    }
                    else -> defaultReturn(method.returnType)
                }
            } as MediaPlayerHandler
    }

    private fun fakeDownloadHandler(): DownloadHandler =
        Proxy.newProxyInstance(
            DownloadHandler::class.java.classLoader,
            arrayOf(DownloadHandler::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDownloads", "getDownloadTask" -> MutableStateFlow(emptyMap<String, Int>())
                else -> defaultReturn(method.returnType)
            }
        } as DownloadHandler

    private inline fun <T> withKoinMocks(
        handler: RecordingHandler,
        songRepo: SongRepository,
        block: () -> T,
    ): T {
        stopKoin()
        startKoin {
            modules(
                module {
                    single<MediaPlayerHandler> { handler.proxy }
                    single<DownloadHandler> { fakeDownloadHandler() }
                    single<SongRepository> { songRepo }
                },
            )
        }
        return try {
            block()
        } finally {
            stopKoin()
        }
    }

    @Test
    fun playlistPlayAllResetsShuffleToFalseAndPlaysFromFirstSongInOriginalOrder() =
        runBlocking {
            val handler = RecordingHandler()
            // Simulate Shuffle left ON from a previous session
            handler.proxy.setShuffle(true)
            assertTrue(handler.controlState.value.isShuffle)

            val tracks = (0 until 6).map { makeTrack(it, "skepta") }
            val browse =
                PlaylistBrowse(
                    author = Author(id = "art_skepta", name = "SKEPTA"),
                    description = "Top songs",
                    duration = "19:30",
                    durationSeconds = 1170,
                    id = "VLPL_SKEPTA_TOP",
                    privacy = "PUBLIC",
                    thumbnails = emptyList(),
                    title = "Top songs",
                    trackCount = tracks.size,
                    tracks = tracks,
                    year = "2026",
                    shuffleEndpoint = YouTubeWatchEndpoint(playlistId = "RDAMPLPL_SKEPTA_TOP"),
                    radioEndpoint = YouTubeWatchEndpoint(playlistId = "RDQM_SKEPTA_TOP"),
                )
            FastPageCache.putPlaylist(
                "VLPL_SKEPTA_TOP",
                FastPageCache.PlaylistSnapshot(browse = browse, continuation = null, isRadio = false),
            )

            val songRepo = proxyRepo(SongRepository::class.java)
            val localPlaylistRepo = proxyRepo(LocalPlaylistRepository::class.java)
            val playlistRepo =
                Proxy.newProxyInstance(
                    PlaylistRepository::class.java.classLoader,
                    arrayOf(PlaylistRepository::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getPlaylistData" -> flowOf(Resource.Success(Pair(browse, null as String?)))
                        else -> defaultReturn(method.returnType)
                    }
                } as PlaylistRepository

            withKoinMocks(handler, songRepo) {
                val vm = PlaylistViewModel(songRepo, localPlaylistRepo, playlistRepo)
                try {
                    vm.getData("VLPL_SKEPTA_TOP")
                    delay(80L)

                    // 1. User clicks [Shuffle] first -> must set isShuffle = true and keep all 6 tracks
                    vm.onUIEvent(PlaylistUIEvent.Shuffle)
                    delay(80L)
                    assertTrue(handler.controlState.value.isShuffle, "Shuffle button must enable shuffle mode")
                    val shuffledQueue = assertNotNull(handler.setQueueCalls.lastOrNull())
                    assertEquals(6, shuffledQueue.listTracks.size, "Shuffle must keep all 6 playlist tracks")
                    val shuffleLoad = assertNotNull(handler.loadMediaItemCalls.lastOrNull())
                    val shuffleStartIdx = assertNotNull(shuffleLoad.third)
                    assertTrue(shuffleStartIdx in 0 until 6)
                    assertEquals(tracks[shuffleStartIdx].videoId, (shuffleLoad.first as Track).videoId)

                    // 2. User now clicks [Play] -> must reset isShuffle = false and start at Song 0 in exact original order!
                    vm.onUIEvent(PlaylistUIEvent.PlayAll)
                    delay(80L)
                    assertFalse(handler.controlState.value.isShuffle, "Play button MUST reset isShuffle to false")
                    assertFalse(handler.shuffleEnabled, "Play button MUST disable player.shuffleModeEnabled")
                    val orderedQueue = assertNotNull(handler.setQueueCalls.lastOrNull())
                    assertEquals(tracks.map { it.videoId }, orderedQueue.listTracks.map { it.videoId })
                    assertEquals("skepta_0", orderedQueue.firstPlayedTrack?.videoId)
                    val playLoad = assertNotNull(handler.loadMediaItemCalls.lastOrNull())
                    assertEquals("skepta_0", (playLoad.first as Track).videoId)
                    assertEquals(Config.PLAYLIST_CLICK, playLoad.second)
                    assertEquals(0, playLoad.third)
                } finally {
                    vm.viewModelScope.cancel()
                }
            }
        }

    @Test
    fun albumShuffleKeepsEveryTrackInQueueAndPlayTrackRestoresSequentialOrder() =
        runBlocking {
            val handler = RecordingHandler()
            val tracks = (0 until 8).map { makeTrack(it, "alb") }
            val albumBrowse =
                AlbumBrowse(
                    artists = listOf(Artist(id = "art_1", name = "Skepta")),
                    audioPlaylistId = "OLAK5uy_test",
                    description = "Test Album",
                    duration = "26:00",
                    durationSeconds = 1560,
                    otherVersion = emptyList(),
                    thumbnails = emptyList(),
                    title = "Ignorance Is Bliss",
                    trackCount = tracks.size,
                    tracks = tracks,
                    type = "Album",
                    year = "2019",
                )
            FastPageCache.putAlbum("MPREb_test", albumBrowse)

            val songRepo = proxyRepo(SongRepository::class.java)
            val albumRepo =
                Proxy.newProxyInstance(
                    AlbumRepository::class.java.classLoader,
                    arrayOf(AlbumRepository::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getAlbumData" -> flowOf(Resource.Success(albumBrowse))
                        else -> defaultReturn(method.returnType)
                    }
                } as AlbumRepository

            withKoinMocks(handler, songRepo) {
                val vm = AlbumViewModel(songRepo, albumRepo)
                try {
                    vm.updateBrowseId("MPREb_test")
                    delay(80L)

                    // 1. Tapping [Shuffle] on an album must enable shuffle AND retain all 8 tracks in album order
                    // so the player's shuffleOrder visits tracks before randomIndex too (never stranding 0 until randomIndex!).
                    vm.shuffle()
                    delay(80L)
                    assertTrue(handler.controlState.value.isShuffle)
                    val shuffleQueue = assertNotNull(handler.setQueueCalls.lastOrNull())
                    assertEquals(tracks.map { it.videoId }, shuffleQueue.listTracks.map { it.videoId })
                    val shuffleLoad = assertNotNull(handler.loadMediaItemCalls.lastOrNull())
                    val randomIdx = assertNotNull(shuffleLoad.third)
                    assertTrue(randomIdx in tracks.indices)
                    assertEquals(tracks[randomIdx].videoId, (shuffleLoad.first as Track).videoId)

                    // 2. Tapping [Play] (playTrack(first)) while shuffling must switch isShuffle = false and start at index 0
                    vm.playTrack(tracks.first())
                    delay(80L)
                    assertFalse(handler.controlState.value.isShuffle)
                    assertEquals(PlaylistType.ALBUM, handler.setQueueCalls.last().playlistType)
                    val playLoad = assertNotNull(handler.loadMediaItemCalls.lastOrNull())
                    assertEquals("alb_0", (playLoad.first as Track).videoId)
                    assertEquals(0, playLoad.third)
                } finally {
                    vm.viewModelScope.cancel()
                }
            }
        }

    private companion object {
        fun <T> proxyRepo(clazz: Class<T>): T =
            @Suppress("UNCHECKED_CAST")
            (
                Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
                    defaultReturn(method.returnType)
                } as T
            )

        fun defaultReturn(returnType: Class<*>): Any? =
            when {
                Flow::class.java.isAssignableFrom(returnType) -> emptyFlow<Any?>()
                returnType == java.lang.Boolean.TYPE -> false
                returnType == java.lang.Integer.TYPE -> 0
                returnType == java.lang.Long.TYPE -> 0L
                returnType == java.lang.Float.TYPE -> 0f
                returnType == java.lang.Double.TYPE -> 0.0
                returnType == Unit::class.java || returnType == Void.TYPE -> Unit
                List::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
                Set::class.java.isAssignableFrom(returnType) -> emptySet<Any?>()
                else -> null
            }
    }
}
