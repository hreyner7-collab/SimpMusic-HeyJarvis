package com.maxrave.simpmusic.jerry

import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.data.model.browse.artist.ResultSong
import com.maxrave.domain.data.model.browse.artist.Songs
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.mood.MoodSection
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.genre.ItemsPlaylist
import com.maxrave.domain.data.model.mood.genre.Title
import com.maxrave.domain.data.model.mood.moodmoments.Item
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JerryMediaPlaybackTest {
    private fun makeTrack(
        videoId: String,
        title: String,
        artistName: String = "Test Artist",
        durationSeconds: Int = 200,
    ): Track = Track(
        album = Album(id = "ALB_$videoId", name = "Album $title"),
        artists = listOf(Artist(id = "ART_$videoId", name = artistName)),
        duration = "3:20",
        durationSeconds = durationSeconds,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = emptyList(),
        title = title,
        videoId = videoId,
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = "Song",
        feedbackTokens = null,
        resultType = "Song",
    )

    private fun makeSongResult(videoId: String, title: String, artistName: String = "Test Artist"): SongsResult =
        SongsResult(
            album = Album(id = "ALB_$videoId", name = "Album $title"),
            artists = listOf(Artist(id = "ART_$videoId", name = artistName)),
            category = "Song",
            duration = "3:20",
            durationSeconds = 200,
            feedbackTokens = null,
            isExplicit = false,
            resultType = "Song",
            thumbnails = emptyList(),
            title = title,
            videoId = videoId,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            year = "2024",
        )

    private class RecordingVolume : JerryVolume {
        val adjusts = mutableListOf<Boolean>()
        val levels = mutableListOf<Int>()
        var muteCalls = 0
        var unmuteCalls = 0
        var muted = false

        override fun adjust(up: Boolean) {
            adjusts += up
        }

        override fun setLevel(percent: Int) {
            levels += percent
        }

        override suspend fun mute() {
            muteCalls++
            muted = true
        }

        override suspend fun unmute() {
            unmuteCalls++
            muted = false
        }
    }

    private class FakePlayer {
        var isPlaying = false
        var currentPosition = 30_000L
        var duration = 200_000L
        var mediaItemCount = 1
        var playbackState = 3
        var shuffleModeEnabled = false
        var repeatMode = 0
        var playWhenReady = true
        var volume = 0.5f
        var hasPrevious = false
        var hasNext = true

        var stopCalls = 0
        var playCalls = 0
        var pauseCalls = 0
        var prepareCalls = 0
        val seekPositions = mutableListOf<Long>()

        val proxy: MediaPlayerInterface = Proxy.newProxyInstance(
            MediaPlayerInterface::class.java.classLoader,
            arrayOf(MediaPlayerInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "stop" -> { stopCalls++; Unit }
                "play" -> { playCalls++; isPlaying = true; Unit }
                "pause" -> { pauseCalls++; isPlaying = false; Unit }
                "prepare" -> { prepareCalls++; Unit }
                "seekTo" -> {
                    if (args != null && args.size == 1 && args[0] is Long) {
                        val pos = args[0] as Long
                        currentPosition = pos
                        seekPositions += pos
                    } else if (args != null && args.size == 2 && args[1] is Long) {
                        val pos = args[1] as Long
                        currentPosition = pos
                        seekPositions += pos
                    }
                    Unit
                }
                "hasPreviousMediaItem" -> hasPrevious
                "hasNextMediaItem" -> hasNext
                "isPlaying" -> isPlaying
                "getIsPlaying" -> isPlaying
                "getCurrentPosition" -> currentPosition
                "getDuration" -> duration
                "getBufferedPosition" -> duration
                "getBufferedPercentage" -> 100
                "getCurrentMediaItem" -> null
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemCount" -> mediaItemCount
                "getContentPosition" -> currentPosition
                "getPlaybackState" -> playbackState
                "getShuffleModeEnabled" -> shuffleModeEnabled
                "setShuffleModeEnabled" -> { shuffleModeEnabled = args?.get(0) as? Boolean ?: false; Unit }
                "getRepeatMode" -> repeatMode
                "setRepeatMode" -> { repeatMode = args?.get(0) as? Int ?: 0; Unit }
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args?.get(0) as? Boolean ?: true; Unit }
                "getPlaybackParameters" -> GenericPlaybackParameters.DEFAULT
                "getAudioSessionId" -> 0
                "getVolume" -> volume
                "setVolume" -> { volume = args?.get(0) as? Float ?: 1f; Unit }
                "getSleepFadeFactor" -> 1f
                "getCrossfadeSuppressed" -> false
                "getAlbumTrackIds" -> emptySet<String>()
                "getSkipSilenceEnabled" -> false
                "getCurrentMediaTimeLine" -> emptyList<GenericMediaItem>()
                "getUnshuffledIndex" -> args?.get(0) as? Int ?: 0
                else -> defaultValue(method.returnType)
            }
        } as MediaPlayerInterface
    }

    private class FakeMediaPlayerHandler(val fakePlayer: FakePlayer = FakePlayer()) {
        val simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
        val nowPlaying = MutableStateFlow<GenericMediaItem?>(null)
        val queueData = MutableStateFlow<QueueData?>(null)
        val controlState = MutableStateFlow(
            ControlState(
                isPlaying = false,
                isShuffle = false,
                repeatState = RepeatState.None,
                isLiked = false,
                isNextAvailable = true,
                isPreviousAvailable = true,
                isCrossfading = false,
                volume = 0.5f,
            ),
        )
        val nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
        val sleepTimerState = MutableStateFlow(SleepTimerState(isDone = false, timeRemaining = 0))
        val skipSegments = MutableStateFlow<List<com.maxrave.domain.data.model.mediaService.SponsorSkipSegments>?>(null)
        val format = MutableStateFlow<NewFormatEntity?>(null)
        val currentSongIndex = MutableStateFlow(0)
        val castState = MutableStateFlow(GenericCastState.NOT_CASTING)

        val loadMediaItemCalls = mutableListOf<Triple<Any?, String, Int?>>()
        val setQueueDataCalls = mutableListOf<QueueData.Data>()
        val playNextCalls = mutableListOf<Track>()
        val loadMoreCatalogCalls = mutableListOf<Pair<List<Track>, Boolean>>()
        val playerEvents = mutableListOf<PlayerEvent>()
        val likeCalls = mutableListOf<Boolean>()
        val sleepStartCalls = mutableListOf<Int>()
        val shufflePlaylistCalls = mutableListOf<Int>()
        var resetCalls = 0
        var clearMediaItemsCalls = 0
        var resetSongAndQueueCalls = 0
        var toggleRadioCalls = 0
        var toggleLikeCalls = 0
        var sleepStopCalls = 0
        var restoreQueueAndPlayResult = false
        var stopCallsBeforeReset = 0

        val proxy: MediaPlayerHandler = Proxy.newProxyInstance(
            MediaPlayerHandler::class.java.classLoader,
            arrayOf(MediaPlayerHandler::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPlayer" -> fakePlayer.proxy
                "getSimpleMediaState" -> simpleMediaState
                "getNowPlaying" -> nowPlaying
                "getQueueData" -> queueData
                "getControlState" -> controlState
                "getNowPlayingState" -> nowPlayingState
                "getSleepTimerState" -> sleepTimerState
                "getSkipSegments" -> skipSegments
                "getFormat" -> format
                "getCurrentSongIndex" -> currentSongIndex
                "getCastState" -> castState
                "reset" -> {
                    if (resetCalls == 0) {
                        stopCallsBeforeReset = fakePlayer.stopCalls
                    }
                    resetCalls++
                    Unit
                }
                "clearMediaItems" -> { clearMediaItemsCalls++; Unit }
                "resetSongAndQueue" -> { resetSongAndQueueCalls++; Unit }
                "toggleRadio" -> { toggleRadioCalls++; Unit }
                "toggleLike" -> {
                    toggleLikeCalls++
                    controlState.value = controlState.value.copy(isLiked = !controlState.value.isLiked)
                    Unit
                }
                "like" -> {
                    val liked = args?.get(0) as? Boolean ?: false
                    likeCalls += liked
                    controlState.value = controlState.value.copy(isLiked = liked)
                    Unit
                }
                "sleepStart" -> {
                    val mins = args?.get(0) as? Int ?: 0
                    sleepStartCalls += mins
                    Unit
                }
                "sleepStop" -> { sleepStopCalls++; Unit }
                "shufflePlaylist" -> {
                    val idx = args?.get(0) as? Int ?: 0
                    shufflePlaylistCalls += idx
                    fakePlayer.shuffleModeEnabled = true
                    controlState.value = controlState.value.copy(isShuffle = true)
                    Unit
                }
                "setQueueData" -> {
                    val data = args?.get(0) as QueueData.Data
                    setQueueDataCalls += data
                    queueData.value = QueueData(data = data)
                    Unit
                }
                "loadMediaItem" -> {
                    val anyTrack = args?.get(0)
                    val type = args?.get(1) as String
                    val index = args?.get(2) as? Int
                    loadMediaItemCalls += Triple(anyTrack, type, index)
                    if (anyTrack is Track) {
                        nowPlayingState.value = NowPlayingTrackState(
                            mediaItem = GenericMediaItem(anyTrack.videoId, null, GenericMediaMetadata(title = anyTrack.title)),
                            track = anyTrack,
                            songEntity = null,
                        )
                    }
                    Unit
                }
                "playNext" -> {
                    val track = args?.get(0) as Track
                    playNextCalls += track
                    Unit
                }
                "loadMoreCatalog" -> {
                    @Suppress("UNCHECKED_CAST")
                    val tracks = (args?.get(0) as List<Track>).toList()
                    val isAddToQueue = args?.get(1) as? Boolean ?: false
                    loadMoreCatalogCalls += (tracks to isAddToQueue)
                    Unit
                }
                "onPlayerEvent" -> {
                    val event = args?.get(0) as PlayerEvent
                    playerEvents += event
                    when (event) {
                        PlayerEvent.Shuffle -> {
                            val next = !controlState.value.isShuffle
                            fakePlayer.shuffleModeEnabled = next
                            controlState.value = controlState.value.copy(isShuffle = next)
                        }
                        PlayerEvent.Repeat -> {
                            val next = when (controlState.value.repeatState) {
                                RepeatState.None -> RepeatState.All
                                RepeatState.All -> RepeatState.One
                                RepeatState.One -> RepeatState.None
                            }
                            fakePlayer.repeatMode = when (next) {
                                RepeatState.None -> 0
                                RepeatState.One -> 1
                                RepeatState.All -> 2
                            }
                            controlState.value = controlState.value.copy(repeatState = next)
                        }
                        PlayerEvent.Forward -> fakePlayer.currentPosition += 10_000L
                        PlayerEvent.Backward -> fakePlayer.currentPosition = (fakePlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                        PlayerEvent.ToggleLike -> {
                            controlState.value = controlState.value.copy(isLiked = !controlState.value.isLiked)
                        }
                        else -> Unit
                    }
                    Unit
                }
                "getDuration", "getPlayerDuration" -> fakePlayer.duration
                "getProgress" -> fakePlayer.currentPosition
                "restoreQueueAndPlay" -> restoreQueueAndPlayResult
                else -> defaultValue(method.returnType)
            }
        } as MediaPlayerHandler
    }

    private class FakeSearchRepository {
        var songSearch: (String) -> Flow<Resource<ArrayList<SongsResult>>> = { flowOf(Resource.Success(arrayListOf())) }
        var artistSearch: (String) -> Flow<Resource<ArrayList<ArtistsResult>>> = { flowOf(Resource.Success(arrayListOf())) }
        var albumSearch: (String) -> Flow<Resource<ArrayList<AlbumsResult>>> = { flowOf(Resource.Success(arrayListOf())) }
        var playlistSearch: (String) -> Flow<Resource<ArrayList<PlaylistsResult>>> = { flowOf(Resource.Success(arrayListOf())) }
        var featuredPlaylistSearch: (String) -> Flow<Resource<ArrayList<PlaylistsResult>>> = { flowOf(Resource.Success(arrayListOf())) }

        val proxy: SearchRepository = Proxy.newProxyInstance(
            SearchRepository::class.java.classLoader,
            arrayOf(SearchRepository::class.java),
        ) { _, method, args ->
            val q = args?.firstOrNull() as? String ?: ""
            when (method.name) {
                "getSearchDataSong" -> songSearch(q)
                "getSearchDataArtist" -> artistSearch(q)
                "getSearchDataAlbum" -> albumSearch(q)
                "getSearchDataPlaylist" -> playlistSearch(q)
                "getSearchDataFeaturedPlaylist" -> featuredPlaylistSearch(q)
                else -> defaultValue(method.returnType)
            }
        } as SearchRepository
    }

    private class FakeArtistRepository {
        var artistData: (String) -> Flow<Resource<ArtistBrowse>> = { flowOf(Resource.Error("not found")) }
        val requestedBrowseIds = mutableListOf<String>()

        val proxy: ArtistRepository = Proxy.newProxyInstance(
            ArtistRepository::class.java.classLoader,
            arrayOf(ArtistRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getArtistData" -> {
                    val id = args?.get(0) as String
                    requestedBrowseIds += id
                    artistData(id)
                }
                else -> defaultValue(method.returnType)
            }
        } as ArtistRepository
    }

    private class FakeHomeRepository {
        var moodAndMoments: () -> Flow<Resource<Mood>> = { flowOf(Resource.Error("empty")) }
        var genreData: (String) -> Flow<Resource<GenreObject>> = { flowOf(Resource.Error("none")) }
        var moodData: (String) -> Flow<Resource<MoodsMomentObject>> = { flowOf(Resource.Error("none")) }

        val proxy: HomeRepository = Proxy.newProxyInstance(
            HomeRepository::class.java.classLoader,
            arrayOf(HomeRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMoodAndMomentsData" -> moodAndMoments()
                "getGenreData" -> genreData(args?.get(0) as String)
                "getMoodData" -> moodData(args?.get(0) as String)
                else -> defaultValue(method.returnType)
            }
        } as HomeRepository
    }

    private class FakeSongRepository {
        var radioByPlaylistId: (String) -> Flow<Resource<Pair<List<Track>, String?>>> = { flowOf(Resource.Error("none")) }
        var likedSongs: List<SongEntity> = emptyList()
        var downloadedSongs: List<SongEntity> = emptyList()
        var mostPlayedSongs: List<SongEntity> = emptyList()
        val dislikedMediaIds = mutableListOf<String?>()

        val proxy: SongRepository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getRadioFromEndpoint" -> {
                    val ep = args?.get(0) as YouTubeWatchEndpoint
                    radioByPlaylistId(ep.playlistId ?: "")
                }
                "getLikedSongs" -> flowOf(likedSongs)
                "getDownloadedSongs" -> flowOf(downloadedSongs)
                "getMostPlayedSongs" -> flowOf(mostPlayedSongs)
                "addToYouTubeDisliked" -> {
                    dislikedMediaIds += args?.get(0) as? String
                    flowOf(200)
                }
                else -> defaultValue(method.returnType)
            }
        } as SongRepository
    }

    private class FakeStreamRepository {
        val proxy: StreamRepository = Proxy.newProxyInstance(
            StreamRepository::class.java.classLoader,
            arrayOf(StreamRepository::class.java),
        ) { _, method, _ -> defaultValue(method.returnType) } as StreamRepository
    }

    private class FakeAlbumRepository {
        var likedAlbums: List<AlbumEntity> = emptyList()
        var albumData: (String) -> Flow<Resource<AlbumBrowse>> = { flowOf(Resource.Error("none")) }

        val proxy: AlbumRepository = Proxy.newProxyInstance(
            AlbumRepository::class.java.classLoader,
            arrayOf(AlbumRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLikedAlbums" -> flowOf(likedAlbums)
                "getAlbumData" -> albumData(args?.get(0) as String)
                else -> defaultValue(method.returnType)
            }
        } as AlbumRepository
    }

    private class FakePlaylistRepository {
        var mixedForYou: List<PlaylistsResult> = emptyList()
        var likedPlaylists: List<PlaylistEntity> = emptyList()
        var fullPlaylistData: (String) -> Flow<Resource<PlaylistBrowse>> = { flowOf(Resource.Error("none")) }
        var playlistData: (String) -> Flow<Resource<Pair<PlaylistBrowse, String?>>> = { flowOf(Resource.Error("none")) }
        var rdatRadioData: (String) -> Flow<Resource<Pair<PlaylistBrowse, String?>>> = { flowOf(Resource.Error("none")) }

        val proxy: PlaylistRepository = Proxy.newProxyInstance(
            PlaylistRepository::class.java.classLoader,
            arrayOf(PlaylistRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMixedForYou" -> flowOf(mixedForYou)
                "getLikedPlaylists" -> flowOf(likedPlaylists)
                "getFullPlaylistData" -> fullPlaylistData(args?.get(0) as String)
                "getPlaylistData" -> playlistData(args?.get(0) as String)
                "getRDATRadioData" -> rdatRadioData(args?.get(0) as String)
                else -> defaultValue(method.returnType)
            }
        } as PlaylistRepository
    }

    private class FakeLocalPlaylistRepository {
        var localPlaylists: List<LocalPlaylistEntity> = emptyList()
        var tracksById: Map<Long, List<SongEntity>> = emptyMap()

        val proxy: LocalPlaylistRepository = Proxy.newProxyInstance(
            LocalPlaylistRepository::class.java.classLoader,
            arrayOf(LocalPlaylistRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAllLocalPlaylists" -> flowOf(localPlaylists)
                "getFullPlaylistTracks" -> tracksById[args?.get(0) as? Long] ?: emptyList<SongEntity>()
                else -> defaultValue(method.returnType)
            }
        } as LocalPlaylistRepository
    }

    private data class PlaybackHarness(
        val playback: JerryMediaPlayback,
        val handler: FakeMediaPlayerHandler,
        val search: FakeSearchRepository,
        val artist: FakeArtistRepository,
        val home: FakeHomeRepository,
        val song: FakeSongRepository,
        val album: FakeAlbumRepository,
        val playlist: FakePlaylistRepository,
        val localPlaylist: FakeLocalPlaylistRepository,
        val volume: RecordingVolume,
        val scope: CoroutineScope,
    )

    private fun createHarness(): PlaybackHarness {
        val handler = FakeMediaPlayerHandler()
        val search = FakeSearchRepository()
        val artist = FakeArtistRepository()
        val home = FakeHomeRepository()
        val song = FakeSongRepository()
        val stream = FakeStreamRepository()
        val album = FakeAlbumRepository()
        val playlist = FakePlaylistRepository()
        val localPlaylist = FakeLocalPlaylistRepository()
        val volume = RecordingVolume()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val playback = JerryMediaPlayback(
            handler = handler.proxy,
            searchRepository = search.proxy,
            songRepository = song.proxy,
            streamRepository = stream.proxy,
            volume = volume,
            playlistRepository = playlist.proxy,
            localPlaylistRepository = localPlaylist.proxy,
            albumRepository = album.proxy,
            artistRepository = artist.proxy,
            homeRepository = home.proxy,
            scope = scope,
        )
        return PlaybackHarness(
            playback = playback,
            handler = handler,
            search = search,
            artist = artist,
            home = home,
            song = song,
            album = album,
            playlist = playlist,
            localPlaylist = localPlaylist,
            volume = volume,
            scope = scope,
        )
    }

    // -------------------------------------------------------------------------
    // R1 / F4: Single-Track Guarantee & Job Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun playQueryLoadsSingleTrackOnceWithoutPreCallingPlayerStop() = runBlocking {
        val h = createHarness()
        try {
            h.search.songSearch = {
                flowOf(Resource.Success(arrayListOf(makeSongResult("vid_bohemian", "Bohemian Rhapsody", "Queen"))))
            }

            val title = h.playback.playQuery("Bohemian Rhapsody")

            assertEquals("Bohemian Rhapsody", title)
            assertEquals(1, h.handler.loadMediaItemCalls.size, "loadMediaItem must be called exactly once")
            assertEquals(0, h.handler.stopCallsBeforeReset, "Must NOT pre-call handler.player.stop() before reset()")
            assertEquals(0, h.handler.fakePlayer.stopCalls, "playQuery must rely on atomic loadMediaItem/reset, not player.stop()")
            assertEquals(1, h.handler.setQueueDataCalls.size)
            assertEquals(listOf("vid_bohemian"), h.handler.setQueueDataCalls.single().listTracks.map { it.videoId })
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun rapidReQueryViaCoordinatorCancelsPriorSearchAndStartsOnlyOneStream() = runBlocking {
        val h = createHarness()
        try {
            val gate = CompletableDeferred<Unit>()
            h.search.songSearch = { query ->
                flow {
                    if (query == "Coldplay") {
                        gate.await()
                    }
                    emit(Resource.Success(arrayListOf(makeSongResult("vid_$query", query))))
                }
            }
            val coordinator = JerryCommandCoordinator(h.playback)

            val firstJob = launch { coordinator.execute(JerryCommand.PlayQuery("Coldplay")) }
            yield()
            val secondJob = launch { coordinator.execute(JerryCommand.PlayQuery("Adele")) }
            yield()
            gate.complete(Unit)
            firstJob.join()
            secondJob.join()

            assertTrue(firstJob.isCancelled || h.handler.loadMediaItemCalls.none { (it.first as? Track)?.videoId == "vid_Coldplay" })
            assertEquals(1, h.handler.loadMediaItemCalls.size, "Superseding query must result in exactly 1 loadMediaItem call")
            assertEquals("vid_Adele", (h.handler.loadMediaItemCalls.single().first as Track).videoId)
        } finally {
            h.scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // R2 / F8: Genre & Mood Routing ("smooth jazz" + cold-start fallback)
    // -------------------------------------------------------------------------

    @Test
    fun playGenreOrMoodSmoothJazzUsesHomeRepositoryAndBuildsMultiTrackQueue() = runBlocking {
        val h = createHarness()
        try {
            val jazzTracks = listOf(
                makeTrack("jazz_1", "Take Five", "Dave Brubeck"),
                makeTrack("jazz_2", "So What", "Miles Davis"),
                makeTrack("jazz_3", "Autumn Leaves", "Chet Baker"),
            )
            h.home.moodAndMoments = {
                flowOf(
                    Resource.Success(
                        Mood(
                            sections = listOf(
                                MoodSection(
                                    title = "Genres",
                                    items = listOf(MoodItem(title = "Smooth Jazz", params = "params_smooth_jazz", stripeColor = 0L)),
                                ),
                            ),
                        ),
                    ),
                )
            }
            h.home.genreData = { params ->
                assertEquals("params_smooth_jazz", params)
                flowOf(
                    Resource.Success(
                        GenreObject(
                            header = "Smooth Jazz",
                            itemsPlaylist = listOf(
                                ItemsPlaylist(
                                    contents = listOf(
                                        com.maxrave.domain.data.model.mood.genre.Content(
                                            playlistBrowseId = "VLPL_SMOOTH_JAZZ",
                                            thumbnail = null,
                                            title = Title(subtitle = "Jazz", title = "Smooth Jazz Essentials"),
                                        ),
                                    ),
                                    header = "Playlists",
                                    type = "playlist",
                                ),
                            ),
                            itemsSong = null,
                        ),
                    ),
                )
            }
            h.playlist.fullPlaylistData = { browseId ->
                flowOf(
                    Resource.Success(
                        PlaylistBrowse(
                            author = Author("", "YouTube Music"),
                            description = null,
                            duration = "15:00",
                            durationSeconds = 900,
                            id = browseId,
                            privacy = "PUBLIC",
                            thumbnails = emptyList(),
                            title = "Smooth Jazz Essentials",
                            trackCount = jazzTracks.size,
                            tracks = jazzTracks,
                            year = "2025",
                        ),
                    ),
                )
            }

            val result = h.playback.playGenreOrMood("smooth jazz")

            assertTrue(result.isNotBlank())
            assertEquals(1, h.handler.loadMediaItemCalls.size, "Genre playback must start exactly 1 stream")
            assertEquals(0, h.handler.fakePlayer.stopCalls, "Must not pre-call player.stop()")
            assertEquals(3, h.handler.setQueueDataCalls.single().listTracks.size, "Smooth jazz must queue multiple tracks, not 1-song dead end")
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun playGenreOrMoodFallsBackToMoodDataAndFeaturedPlaylistWhenMoodCacheEmpty() = runBlocking {
        val h = createHarness()
        try {
            val energizeTracks = listOf(
                makeTrack("en_1", "Power Run"),
                makeTrack("en_2", "High Voltage"),
            )
            // Cold-start empty Moods & Moments cache
            h.home.moodAndMoments = { flowOf(Resource.Error("cold start")) }
            h.home.moodData = { _ ->
                flowOf(
                    Resource.Success(
                        MoodsMomentObject(
                            endpoint = "browse",
                            header = "Energize",
                            items = listOf(
                                Item(
                                    contents = listOf(
                                        com.maxrave.domain.data.model.mood.moodmoments.Content(
                                            playlistBrowseId = "PL_ENERGIZE",
                                            subtitle = "Workout",
                                            thumbnails = null,
                                            title = "Energize Mix",
                                        ),
                                    ),
                                    header = "Featured",
                                ),
                            ),
                            params = "energize",
                        ),
                    ),
                )
            }
            h.search.featuredPlaylistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            PlaylistsResult("YouTube", "PL_ENERGIZE", "Playlist", "2", "Playlist", emptyList(), "Energize Mix"),
                        ),
                    ),
                )
            }
            h.search.playlistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            PlaylistsResult("YouTube", "PL_ENERGIZE", "Playlist", "2", "Playlist", emptyList(), "Energize Mix"),
                        ),
                    ),
                )
            }
            h.song.radioByPlaylistId = { _ -> flowOf(Resource.Success(energizeTracks to null)) }

            val result = h.playback.playGenreOrMood("energize")

            assertTrue(result.isNotBlank())
            assertEquals(1, h.handler.loadMediaItemCalls.size)
            assertTrue(h.handler.setQueueDataCalls.single().listTracks.size >= 2, "Fallback must still yield multi-track queue")
        } finally {
            h.scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // R2 / F8: Artist Routing (The ..., radioId fallback, shuffle = true)
    // -------------------------------------------------------------------------

    @Test
    fun playArtistHandlesLeadingTheAndRadioId() = runBlocking {
        val h = createHarness()
        try {
            val tracks = listOf(makeTrack("wk_1", "Blinding Lights", "The Weeknd"), makeTrack("wk_2", "Starboy", "The Weeknd"))
            h.search.artistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            ArtistsResult(
                                artist = "The Weeknd",
                                browseId = "UC_WEEKND",
                                category = "Artist",
                                radioId = "RDAMPL_WEEKND",
                                resultType = "Artist",
                                shuffleId = "RDSH_WEEKND",
                                thumbnails = emptyList(),
                            ),
                        ),
                    ),
                )
            }
            h.song.radioByPlaylistId = { id ->
                if (id == "RDAMPL_WEEKND") flowOf(Resource.Success(tracks to null)) else flowOf(Resource.Error("no"))
            }

            val title = h.playback.playArtist("The Weeknd", shuffle = false)

            assertTrue(title.contains("Weeknd", ignoreCase = true))
            assertEquals(1, h.handler.loadMediaItemCalls.size)
            assertEquals(2, h.handler.setQueueDataCalls.single().listTracks.size)
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun playArtistFallsBackToArtistRepositoryWhenRadioIdBlankAndSupportsShuffle() = runBlocking {
        val h = createHarness()
        try {
            h.search.artistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            ArtistsResult(
                                artist = "The Beatles",
                                browseId = "UC_BEATLES",
                                category = "Artist",
                                radioId = "",
                                resultType = "Artist",
                                shuffleId = "",
                                thumbnails = emptyList(),
                            ),
                        ),
                    ),
                )
            }
            h.artist.artistData = { browseId ->
                assertEquals("UC_BEATLES", browseId)
                flowOf(
                    Resource.Success(
                        ArtistBrowse(
                            albums = null,
                            channelId = browseId,
                            description = null,
                            name = "The Beatles",
                            radioId = null,
                            related = null,
                            shuffleId = null,
                            singles = null,
                            songs = Songs(
                                browseId = null,
                                results = listOf(
                                    ResultSong("bt_1", "Come Together", listOf(Artist(browseId, "The Beatles")), 259, Album("a1", "Abbey Road"), "INDIFFERENT", emptyList(), true, false, "MUSIC_VIDEO_TYPE_ATV"),
                                    ResultSong("bt_2", "Something", listOf(Artist(browseId, "The Beatles")), 182, Album("a1", "Abbey Road"), "INDIFFERENT", emptyList(), true, false, "MUSIC_VIDEO_TYPE_ATV"),
                                ),
                            ),
                            video = null,
                            featuredOn = null,
                            videoList = null,
                            subscribed = false,
                            subscribers = "30M",
                            thumbnails = emptyList(),
                            views = null,
                        ),
                    ),
                )
            }

            val title = h.playback.playArtist("The Beatles", shuffle = true)

            assertTrue(title.contains("Beatles", ignoreCase = true))
            assertTrue("UC_BEATLES" in h.artist.requestedBrowseIds, "Must fall back to ArtistRepository.getArtistData when radioId blank")
            assertEquals(1, h.handler.loadMediaItemCalls.size)
            assertTrue(
                h.handler.fakePlayer.shuffleModeEnabled ||
                    h.handler.shufflePlaylistCalls.isNotEmpty() ||
                    PlayerEvent.Shuffle in h.handler.playerEvents,
                "playArtist(..., shuffle = true) must enable shuffle",
            )
        } finally {
            h.scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // R2 / F8: Album, Playlist, Radio
    // -------------------------------------------------------------------------

    @Test
    fun playAlbumAndPlayPlaylistFallBackToOnlineSearch() = runBlocking {
        val h = createHarness()
        try {
            val albumTracks = listOf(makeTrack("pf_1", "Speak to Me"), makeTrack("pf_2", "Breathe"))
            h.search.albumSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            AlbumsResult(
                                artists = listOf(Artist("uc_pf", "Pink Floyd")),
                                browseId = "MPREb_DSOTM",
                                category = "Album",
                                duration = "42:00",
                                isExplicit = false,
                                resultType = "Album",
                                thumbnails = emptyList(),
                                title = "Dark Side of the Moon",
                                type = "Album",
                                year = "1973",
                            ),
                        ),
                    ),
                )
            }
            h.album.albumData = { id ->
                assertEquals("MPREb_DSOTM", id)
                flowOf(
                    Resource.Success(
                        AlbumBrowse(
                            artists = listOf(Artist("uc_pf", "Pink Floyd")),
                            audioPlaylistId = "OLAK_DSOTM",
                            description = null,
                            duration = "42:00",
                            durationSeconds = 2520,
                            thumbnails = emptyList(),
                            title = "Dark Side of the Moon",
                            trackCount = 2,
                            tracks = albumTracks,
                            type = "Album",
                            year = "1973",
                        ),
                    ),
                )
            }

            val albumResult = h.playback.playAlbum("Dark Side of the Moon")
            assertEquals("Dark Side of the Moon", albumResult)
            assertEquals(PlaylistType.ALBUM, h.handler.setQueueDataCalls.last().playlistType)

            val playlistTracks = listOf(makeTrack("pl_1", "Midnight City"), makeTrack("pl_2", "Nightcall"))
            h.search.playlistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            PlaylistsResult("Curator", "VLPL_LATE_NIGHT", "Playlist", "2", "Playlist", emptyList(), "Late Night Vibes"),
                        ),
                    ),
                )
            }
            h.song.radioByPlaylistId = { _ -> flowOf(Resource.Success(playlistTracks to null)) }

            val playlistResult = h.playback.playPlaylist("Late Night Vibes")
            assertTrue(playlistResult.contains("Late Night", ignoreCase = true))
            assertEquals(2, h.handler.loadMediaItemCalls.size)
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun playRadioHandlesNullCurrentTrackToggleAndSeededArtistQuery() = runBlocking {
        val h = createHarness()
        try {
            h.playback.playRadio(null)
            assertEquals(1, h.handler.toggleRadioCalls, "playRadio(null) should call handler.toggleRadio()")

            val radioTracks = listOf(makeTrack("dp_1", "Get Lucky"), makeTrack("dp_2", "One More Time"))
            h.search.artistSearch = {
                flowOf(
                    Resource.Success(
                        arrayListOf(
                            ArtistsResult("Daft Punk", "UC_DP", "Artist", "RDAMPL_DP", "Artist", "", emptyList()),
                        ),
                    ),
                )
            }
            h.song.radioByPlaylistId = { _ -> flowOf(Resource.Success(radioTracks to null)) }

            val seeded = h.playback.playRadio("Daft Punk")
            assertTrue(seeded.contains("Daft Punk", ignoreCase = true))
            assertEquals(1, h.handler.loadMediaItemCalls.size)
        } finally {
            h.scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // R2 / F8: Queue, Transport, Sleep, Volume, Navigation (JarvisUiEvents)
    // -------------------------------------------------------------------------

    @Test
    fun queueControlsPlayNextAddToQueueClearQueueAndShowQueue() = runBlocking {
        val h = createHarness()
        try {
            val collectedEvents = mutableListOf<JerryNavTarget>()
            val collector = launch(Dispatchers.Unconfined) {
                JarvisUiEvents.navEvents.collect { collectedEvents += it }
            }

            h.search.songSearch = { q ->
                when (q) {
                    "Take Five" -> flowOf(Resource.Success(arrayListOf(makeSongResult("vid_take5", "Take Five", "Dave Brubeck"))))
                    else -> flowOf(Resource.Success(arrayListOf(makeSongResult("vid_blinding", "Blinding Lights", "The Weeknd"))))
                }
            }

            h.playback.playNextQuery("Take Five")
            assertEquals(listOf("vid_take5"), h.handler.playNextCalls.map { it.videoId })

            h.playback.addToQueueQuery("Blinding Lights")
            assertEquals(1, h.handler.loadMoreCatalogCalls.size)
            assertTrue(h.handler.loadMoreCatalogCalls.single().second, "addToQueueQuery must pass isAddToQueue = true")
            assertEquals(listOf("vid_blinding"), h.handler.loadMoreCatalogCalls.single().first.map { it.videoId })

            h.playback.clearQueue()
            assertEquals(1, h.handler.resetSongAndQueueCalls)

            h.playback.showQueue()
            yield()
            assertTrue(JerryNavTarget.QUEUE in collectedEvents, "showQueue() must emit JerryNavTarget.QUEUE via JarvisUiEvents")
            collector.cancel()
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun transportSeekShuffleRepeatLikeDislikeAndSleepTimerControls() = runBlocking {
        val h = createHarness()
        try {
            val currentTrack = makeTrack("cur_1", "Current Song")
            h.handler.nowPlayingState.value = NowPlayingTrackState(
                mediaItem = GenericMediaItem("cur_1", null, GenericMediaMetadata(title = "Current Song")),
                track = currentTrack,
                songEntity = null,
            )
            h.handler.fakePlayer.currentPosition = 50_000L
            h.handler.fakePlayer.duration = 200_000L

            // Restart & seek
            h.playback.restartTrack()
            assertEquals(0L, h.handler.fakePlayer.currentPosition)

            h.handler.fakePlayer.currentPosition = 40_000L
            h.playback.seekBy(20)
            assertEquals(60_000L, h.handler.fakePlayer.currentPosition)

            h.playback.seekBy(-15)
            assertEquals(45_000L, h.handler.fakePlayer.currentPosition)

            h.playback.seekToPercent(50)
            assertEquals(100_000L, h.handler.fakePlayer.currentPosition)

            // Shuffle idempotency (explicit true/false + null toggle)
            h.playback.setShuffle(true)
            assertTrue(h.handler.controlState.value.isShuffle)
            h.playback.setShuffle(true)
            assertTrue(h.handler.controlState.value.isShuffle, "setShuffle(true) when already true must stay true")
            h.playback.setShuffle(false)
            assertFalse(h.handler.controlState.value.isShuffle)
            h.playback.setShuffle(null)
            assertTrue(h.handler.controlState.value.isShuffle)

            // Repeat targets
            h.playback.setRepeat(RepeatTarget.ALL)
            assertEquals(RepeatState.All, h.handler.controlState.value.repeatState)
            h.playback.setRepeat(RepeatTarget.ONE)
            assertEquals(RepeatState.One, h.handler.controlState.value.repeatState)
            h.playback.setRepeat(RepeatTarget.OFF)
            assertEquals(RepeatState.None, h.handler.controlState.value.repeatState)
            h.playback.setRepeat(RepeatTarget.CYCLE)
            assertEquals(RepeatState.All, h.handler.controlState.value.repeatState)

            // Like / Dislike
            h.playback.toggleLike(true)
            assertTrue(h.handler.controlState.value.isLiked)
            h.playback.toggleLike(false)
            assertFalse(h.handler.controlState.value.isLiked)
            h.playback.toggleLike(null)
            assertTrue(h.handler.controlState.value.isLiked)

            h.playback.dislike()
            assertTrue(
                h.handler.likeCalls.contains(false) || h.song.dislikedMediaIds.contains("cur_1"),
                "dislike() must call handler.like(false) or songRepository.addToYouTubeDisliked",
            )

            // Sleep timer
            h.playback.setSleepTimer(30)
            assertEquals(listOf(30), h.handler.sleepStartCalls)
            h.playback.setSleepTimer(null)
            h.playback.setSleepTimer(0)
            assertEquals(2, h.handler.sleepStopCalls)
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun volumeMuteUnmuteAndNavigationEmitUiEvents() = runBlocking {
        val h = createHarness()
        try {
            val collected = mutableListOf<JerryNavTarget>()
            val collector = launch(Dispatchers.Unconfined) {
                JarvisUiEvents.navEvents.collect { collected += it }
            }

            h.playback.mute()
            assertEquals(1, h.volume.muteCalls, "mute() must call volume.mute()")
            assertEquals(0, h.handler.fakePlayer.stopCalls, "mute() must NEVER stop playback")

            h.playback.unmute()
            assertEquals(1, h.volume.unmuteCalls, "unmute() must call volume.unmute()")

            for (target in JerryNavTarget.entries) {
                h.playback.navigate(target)
            }
            yield()
            assertEquals(JerryNavTarget.entries.toSet(), collected.toSet(), "All JerryNavTarget entries must emit via JarvisUiEvents")
            collector.cancel()
        } finally {
            h.scope.cancel()
        }
    }

    private companion object {
        fun defaultValue(returnType: Class<*>): Any? = when {
            returnType == java.lang.Boolean.TYPE -> false
            returnType == java.lang.Integer.TYPE -> 0
            returnType == java.lang.Long.TYPE -> 0L
            returnType == java.lang.Float.TYPE -> 0f
            returnType == java.lang.Double.TYPE -> 0.0
            returnType == Unit::class.java || returnType == Void.TYPE -> Unit
            Flow::class.java.isAssignableFrom(returnType) -> emptyFlow<Any?>()
            List::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
            Set::class.java.isAssignableFrom(returnType) -> emptySet<Any?>()
            else -> null
        }
    }
}
