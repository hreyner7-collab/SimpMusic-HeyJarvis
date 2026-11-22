package com.maxrave.simpmusic.jerry

import kotlin.concurrent.Volatile

import com.maxrave.common.Config
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.addToQueueBlock
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.LIVE_RESULT_TYPE
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioPlaylistId
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface JerryVolume {
    fun adjust(up: Boolean)
    fun setLevel(percent: Int) {}
    suspend fun mute() {}
    suspend fun unmute() {}
}

class JerryMediaPlayback(
    private val handler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    @Suppress("unused") private val streamRepository: StreamRepository,
    private val volume: JerryVolume,
    private val playlistRepository: PlaylistRepository? = null,
    private val localPlaylistRepository: LocalPlaylistRepository? = null,
    private val albumRepository: AlbumRepository? = null,
    private val scope: CoroutineScope? = null,
    private val artistRepository: ArtistRepository? = null,
    private val homeRepository: HomeRepository? = null,
    private val dataStoreManager: DataStoreManager? = null,
    private val accountRepository: AccountRepository? = null,
) : JerryPlayback {
    private val historyLock = Any()
    private val playedHistory = ArrayDeque<Track>()
    private var lastObservedTrack: Track? = null
    @Volatile private var suppressHistoryPush = false

    @Volatile private var cachedMixes: List<PlaylistsResult> = emptyList()
    @Volatile private var cachedMixesAtMs: Long = 0L

    private val handoffMutex = Mutex()
    private val cookieMutex = Mutex()
    @Volatile private var cookieReadyChecked = false

    init {
        scope?.launch {
            handler.nowPlayingState.collect { state ->
                val active = state.track ?: state.songEntity?.toTrack()
                synchronized(historyLock) {
                    val prev = lastObservedTrack
                    if (
                        prev != null &&
                        prev.videoId.isNotBlank() &&
                        active?.videoId != prev.videoId &&
                        !suppressHistoryPush
                    ) {
                        pushHistoryLocked(prev)
                    }
                    if (active != null && active.videoId.isNotBlank()) {
                        lastObservedTrack = active
                        if (suppressHistoryPush && prev?.videoId != active.videoId) {
                            suppressHistoryPush = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitCookieReadyIfNeeded() {
        if (cookieReadyChecked) return
        cookieMutex.withLock {
            if (cookieReadyChecked) return
            val ds = dataStoreManager
            val acc = accountRepository
            if (ds == null || acc == null) {
                cookieReadyChecked = true
                return
            }
            if (!acc.getYouTubeCookie().isNullOrBlank()) {
                cookieReadyChecked = true
                return
            }
            val storedCookie = runCatching { withTimeoutOrNull(500L) { ds.cookie.first() } }.getOrNull()
            if (storedCookie.isNullOrBlank()) {
                cookieReadyChecked = true
                return
            }
            withTimeoutOrNull(2500L) {
                while (acc.getYouTubeCookie().isNullOrBlank()) {
                    delay(50L)
                }
            }
            cookieReadyChecked = true
        }
    }

    override suspend fun playQuery(query: String): String {
        awaitCookieReadyIfNeeded()
        tryPlayMix(query)?.let { return it }
        tryPlayLibraryCollection(query)?.let { return it }
        tryPlayUserLibrary(query)?.let { return it }

        return kotlinx.coroutines.coroutineScope {
            val songDeferred =
                async {
                    searchRepository
                        .getSearchDataSong(query)
                        .firstOrNull { it is Resource.Success || it is Resource.Error }
                }
            val artist =
                searchRepository
                    .getSearchDataArtist(query)
                    .firstOrNull { it is Resource.Success || it is Resource.Error }
            val normalizedQuery = normalizeMatchKey(query)
            val artistList = (artist as? Resource.Success)?.data.orEmpty()
            val artistMatch =
                artistList.firstOrNull { it.artist.equals(query, ignoreCase = true) }
                    ?: artistList.firstOrNull { normalizeMatchKey(it.artist) == normalizedQuery }
            if (artistMatch != null && artistMatch.radioId.isNotBlank()) {
                val radio =
                    songRepository
                        .getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.radioId))
                        .firstOrNull { it is Resource.Success || it is Resource.Error }
                val tracks = (radio as? Resource.Success)?.data?.first
                if (!tracks.isNullOrEmpty()) {
                    songDeferred.cancel()
                    startQueue(
                        tracks = tracks,
                        playlistId = artistMatch.radioId,
                        playlistName = "${artistMatch.artist} radio",
                        playlistType = PlaylistType.RADIO,
                        configType = Config.PLAYLIST_CLICK,
                        continuation = (radio as? Resource.Success)?.data?.second,
                    )
                    return@coroutineScope "${artistMatch.artist} radio"
                }
            }
            val songs = songDeferred.await()
            val results = (songs as? Resource.Success)?.data
            if (results.isNullOrEmpty()) throw JerryPlaybackException("No results for $query")
            val best = selectBestSong(results, query)
            val finalTrack = best.toTrack()
            startSingleTrack(finalTrack)
            finalTrack.title
        }
    }

    override suspend fun playArtist(artist: String, shuffle: Boolean): String {
        awaitCookieReadyIfNeeded()
        val artistRes = searchRepository.getSearchDataArtist(artist)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val artistList = (artistRes as? Resource.Success)?.data.orEmpty()
        val normTarget = normalizeMatchKey(artist)
        val artistMatch = artistList.firstOrNull { it.artist.equals(artist, ignoreCase = true) }
            ?: artistList.firstOrNull { normalizeMatchKey(it.artist) == normTarget }
            ?: artistList.firstOrNull()
            ?: throw JerryPlaybackException("No artist found for $artist")

        if (shuffle && artistMatch.shuffleId.isNotBlank()) {
            val res = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.shuffleId))
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val tracks = (res as? Resource.Success)?.data?.first
            if (!tracks.isNullOrEmpty()) {
                startQueue(
                    tracks = tracks,
                    playlistId = artistMatch.shuffleId,
                    playlistName = "${artistMatch.artist} (shuffled)",
                    playlistType = PlaylistType.RADIO,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = (res as? Resource.Success)?.data?.second,
                )
                setShuffle(true)
                return "${artistMatch.artist} shuffled"
            }
        }

        if (!shuffle && artistMatch.radioId.isNotBlank()) {
            val res = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.radioId))
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val tracks = (res as? Resource.Success)?.data?.first
            if (!tracks.isNullOrEmpty()) {
                startQueue(
                    tracks = tracks,
                    playlistId = artistMatch.radioId,
                    playlistName = "${artistMatch.artist} radio",
                    playlistType = PlaylistType.RADIO,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = (res as? Resource.Success)?.data?.second,
                )
                return "${artistMatch.artist} radio"
            }
        }

        val artRepo = artistRepository
        if (artRepo != null && artistMatch.browseId.isNotBlank()) {
            val browseRes = artRepo.getArtistData(artistMatch.browseId)
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val browse = (browseRes as? Resource.Success)?.data
            if (browse != null) {
                val endpoint = if (shuffle) browse.shuffleId ?: browse.radioId else browse.radioId
                if (endpoint != null) {
                    val radioRes = songRepository.getRadioFromEndpoint(endpoint)
                        .firstOrNull { it is Resource.Success || it is Resource.Error }
                    val radioTracks = (radioRes as? Resource.Success)?.data?.first
                    if (!radioTracks.isNullOrEmpty()) {
                        val finalTracks = if (shuffle && browse.shuffleId == null) radioTracks.shuffled() else radioTracks
                        startQueue(
                            tracks = finalTracks,
                            playlistId = endpoint.playlistId ?: artistMatch.browseId,
                            playlistName = if (shuffle) "${browse.name} (shuffled)" else "${browse.name} radio",
                            playlistType = PlaylistType.RADIO,
                            configType = Config.PLAYLIST_CLICK,
                            continuation = (radioRes as? Resource.Success)?.data?.second,
                        )
                        if (shuffle) setShuffle(true)
                        return if (shuffle) "${browse.name} shuffled" else browse.name
                    }
                }
                val topTracks = browse.songs?.results?.map { it.toTrack() }.orEmpty()
                if (topTracks.isNotEmpty()) {
                    val finalTracks = if (shuffle) topTracks.shuffled() else topTracks
                    startQueue(
                        tracks = finalTracks,
                        playlistId = browse.channelId ?: artistMatch.browseId,
                        playlistName = browse.name,
                        playlistType = PlaylistType.PLAYLIST,
                        configType = Config.PLAYLIST_CLICK,
                        continuation = null,
                    )
                    if (shuffle) setShuffle(true)
                    return if (shuffle) "${browse.name} shuffled" else browse.name
                }
            }
        }

        if (shuffle && artistMatch.radioId.isNotBlank()) {
            val res = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.radioId))
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val tracks = (res as? Resource.Success)?.data?.first
            if (!tracks.isNullOrEmpty()) {
                startQueue(
                    tracks = tracks.shuffled(),
                    playlistId = artistMatch.radioId,
                    playlistName = "${artistMatch.artist} (shuffled)",
                    playlistType = PlaylistType.RADIO,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = null,
                )
                setShuffle(true)
                return "${artistMatch.artist} shuffled"
            }
        }

        val songsRes = searchRepository.getSearchDataSong(artistMatch.artist)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val songResults = (songsRes as? Resource.Success)?.data.orEmpty()
        val byArtist = songResults.filter { song ->
            song.artists.orEmpty().any { a -> normalizeMatchKey(a.name) == normalizeMatchKey(artistMatch.artist) }
        }.ifEmpty { songResults }
        if (byArtist.isEmpty()) throw JerryPlaybackException("No songs found for ${artistMatch.artist}")
        val tracks = byArtist.map { it.toTrack() }.let { if (shuffle) it.shuffled() else it }
        startQueue(
            tracks = tracks,
            playlistId = artistMatch.browseId.ifBlank { "ARTIST_${artistMatch.artist}" },
            playlistName = artistMatch.artist,
            playlistType = PlaylistType.PLAYLIST,
            configType = Config.PLAYLIST_CLICK,
            continuation = null,
        )
        if (shuffle) setShuffle(true)
        return if (shuffle) "${artistMatch.artist} shuffled" else artistMatch.artist
    }

    override suspend fun playSong(query: String): String {
        awaitCookieReadyIfNeeded()
        val songs = searchRepository.getSearchDataSong(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val results = (songs as? Resource.Success)?.data
        if (results.isNullOrEmpty()) throw JerryPlaybackException("No results for $query")
        val best = selectBestSong(results, query)
        val finalTrack = best.toTrack()
        startSingleTrack(finalTrack)
        return finalTrack.title
    }

    override suspend fun playAlbum(query: String): String {
        awaitCookieReadyIfNeeded()
        val albRepo = albumRepository
        if (albRepo != null) {
            val savedAlbums = runCatching { albRepo.getLikedAlbums().firstOrNull() }.getOrNull().orEmpty()
            val normTarget = normalizeMatchKey(query)
            val savedMatch = savedAlbums.firstOrNull { it.title.equals(query, ignoreCase = true) }
                ?: savedAlbums.firstOrNull { normalizeMatchKey(it.title) == normTarget }
            if (savedMatch != null) {
                val res = albRepo.getAlbumData(savedMatch.browseId)
                    .firstOrNull { it is Resource.Success || it is Resource.Error }
                val tracks = (res as? Resource.Success)?.data?.tracks
                if (!tracks.isNullOrEmpty()) {
                    startQueue(
                        tracks = tracks,
                        playlistId = savedMatch.browseId.removePrefix("VL"),
                        playlistName = savedMatch.title,
                        playlistType = PlaylistType.ALBUM,
                        configType = Config.ALBUM_CLICK,
                        continuation = null,
                    )
                    return savedMatch.title
                }
            }
        }

        val searchRes = searchRepository.getSearchDataAlbum(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val albums = (searchRes as? Resource.Success)?.data.orEmpty()
        val normTarget = normalizeMatchKey(query)
        val bestAlbum = albums.firstOrNull { it.title.equals(query, ignoreCase = true) }
            ?: albums.firstOrNull { normalizeMatchKey(it.title) == normTarget }
            ?: albums.firstOrNull()
            ?: throw JerryPlaybackException("No album found for $query")

        if (albRepo != null) {
            val albumRes = albRepo.getAlbumData(bestAlbum.browseId)
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val albumData = (albumRes as? Resource.Success)?.data
            if (albumData != null && albumData.tracks.isNotEmpty()) {
                val title = albumData.title.ifBlank { bestAlbum.title }
                startQueue(
                    tracks = albumData.tracks,
                    playlistId = bestAlbum.browseId.removePrefix("VL"),
                    playlistName = title,
                    playlistType = PlaylistType.ALBUM,
                    configType = Config.ALBUM_CLICK,
                    continuation = null,
                )
                return title
            }
        }
        throw JerryPlaybackException("Could not load album $query")
    }

    override suspend fun playPlaylist(query: String): String {
        awaitCookieReadyIfNeeded()
        tryPlayMix(query)?.let { return it }
        tryPlayLibraryCollection(query)?.let { return it }
        tryPlayUserLibrary(query)?.let { return it }

        val plRes = searchRepository.getSearchDataPlaylist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val playlists = (plRes as? Resource.Success)?.data.orEmpty()
        for (pl in playlists.take(3)) {
            playMixByBrowseId(pl.browseId, pl.title.ifBlank { query })?.let { return it }
        }

        val featRes = searchRepository.getSearchDataFeaturedPlaylist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val featured = (featRes as? Resource.Success)?.data.orEmpty()
        for (pl in featured.take(3)) {
            playMixByBrowseId(pl.browseId, pl.title.ifBlank { query })?.let { return it }
        }

        throw JerryPlaybackException("No playlist found for $query")
    }

    override suspend fun playGenreOrMood(query: String): String {
        awaitCookieReadyIfNeeded()
        val normTarget = normalizeMatchKey(query)
        val moodItems = fetchMoodItems()

        // 1. Exact or normalized MoodItem match
        val exactMood = moodItems.firstOrNull { it.title.equals(query, ignoreCase = true) }
            ?: moodItems.firstOrNull { normalizeMatchKey(it.title) == normTarget }
        if (exactMood != null) {
            playMoodItem(exactMood, query)?.let { return it }
        }

        // 2. Featured playlists for compound/sub-genre queries (e.g., "smooth jazz")
        val featRes = searchRepository.getSearchDataFeaturedPlaylist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val featured = (featRes as? Resource.Success)?.data.orEmpty()
        for (pl in featured.take(3)) {
            playMixByBrowseId(pl.browseId, pl.title.ifBlank { query })?.let { return it }
        }

        // 3. Community playlists
        val plRes = searchRepository.getSearchDataPlaylist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val playlists = (plRes as? Resource.Success)?.data.orEmpty()
        for (pl in playlists.take(3)) {
            playMixByBrowseId(pl.browseId, pl.title.ifBlank { query })?.let { return it }
        }

        // 4. Partial/token-overlap MoodItem match (e.g., "smooth jazz" -> "Jazz")
        val targetTokens = normTarget.split(' ').filter { it.isNotEmpty() }.toSet()
        val partialMood = moodItems.firstOrNull { item ->
            val itemNorm = normalizeMatchKey(item.title)
            val itemTokens = itemNorm.split(' ').filter { it.isNotEmpty() }.toSet()
            itemTokens.isNotEmpty() && (itemNorm in normTarget || normTarget in itemNorm || itemTokens.intersect(targetTokens).isNotEmpty())
        }
        if (partialMood != null) {
            playMoodItem(partialMood, query)?.let { return it }
        }

        // 5. Fallback to song search
        return playSong(query)
    }

    private suspend fun fetchMoodItems(): List<MoodItem> {
        val homeRepo = homeRepository ?: return emptyList()
        val moodRes = withTimeoutOrNull(10_000L) {
            homeRepo.getMoodAndMomentsData()
                .firstOrNull { (it is Resource.Success && it.data != null) || it is Resource.Error }
        }
        val mood = (moodRes as? Resource.Success)?.data ?: return emptyList()
        return mood.sections.flatMap { it.items }
    }

    private suspend fun playMoodItem(item: MoodItem, fallbackTitle: String): String? {
        val homeRepo = homeRepository ?: return null
        val genreRes = withTimeoutOrNull(10_000L) {
            homeRepo.getGenreData(item.params)
                .firstOrNull { it is Resource.Success || it is Resource.Error }
        }
        val genre = (genreRes as? Resource.Success)?.data
        if (genre != null) {
            val playlistContents = genre.itemsPlaylist.flatMap { it.contents }
            for (c in playlistContents.take(3)) {
                if (c.playlistBrowseId.isNotBlank()) {
                    playMixByBrowseId(c.playlistBrowseId, c.title.title.ifBlank { item.title })?.let { return it }
                }
            }
            val tracks = genre.itemsSong.orEmpty()
                .filter { it.videoId.isNotBlank() }
                .map { s ->
                    Track(
                        album = null,
                        artists = s.artist,
                        duration = "",
                        durationSeconds = 0,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "",
                        thumbnails = emptyList(),
                        title = s.title,
                        videoId = s.videoId,
                        videoType = "",
                        category = null,
                        feedbackTokens = null,
                        resultType = null,
                        year = "",
                    )
                }
            if (tracks.isNotEmpty()) {
                startQueue(
                    tracks = tracks,
                    playlistId = "GENRE_${item.params}",
                    playlistName = genre.header.ifBlank { item.title },
                    playlistType = PlaylistType.PLAYLIST,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = null,
                )
                return genre.header.ifBlank { item.title }
            }
        }

        val moodRes = withTimeoutOrNull(10_000L) {
            homeRepo.getMoodData(item.params)
                .firstOrNull { it is Resource.Success || it is Resource.Error }
        }
        val moodObj = (moodRes as? Resource.Success)?.data
        if (moodObj != null) {
            val contents = moodObj.items.flatMap { it.contents }
            val songTracks = contents
                .filter { !it.videoId.isNullOrBlank() }
                .map { c ->
                    Track(
                        album = null,
                        artists = emptyList(),
                        duration = "",
                        durationSeconds = 0,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "",
                        thumbnails = c.thumbnails,
                        title = c.title,
                        videoId = c.videoId!!,
                        videoType = "",
                        category = null,
                        feedbackTokens = null,
                        resultType = null,
                        year = "",
                    )
                }
            for (c in contents.filter { it.videoId.isNullOrBlank() && it.playlistBrowseId.isNotBlank() }.take(3)) {
                playMixByBrowseId(c.playlistBrowseId, c.title.ifBlank { item.title })?.let { return it }
            }
            if (songTracks.isNotEmpty()) {
                startQueue(
                    tracks = songTracks,
                    playlistId = "MOOD_${item.params}",
                    playlistName = moodObj.header.ifBlank { fallbackTitle },
                    playlistType = PlaylistType.PLAYLIST,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = null,
                )
                return moodObj.header.ifBlank { item.title }
            }
        }
        return null
    }

    override suspend fun playRadio(query: String?): String {
        awaitCookieReadyIfNeeded()
        if (query.isNullOrBlank()) {
            val active = handler.nowPlayingState.value.let { it.track ?: it.songEntity?.toTrack() }
            withContext(Dispatchers.Main.immediate) {
                handler.toggleRadio()
            }
            return if (active != null && active.title.isNotBlank()) "${active.title} radio" else "radio"
        }

        val artistRes = searchRepository.getSearchDataArtist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val artistList = (artistRes as? Resource.Success)?.data.orEmpty()
        val normQuery = normalizeMatchKey(query)
        val artistMatch = artistList.firstOrNull { it.artist.equals(query, ignoreCase = true) }
            ?: artistList.firstOrNull { normalizeMatchKey(it.artist) == normQuery }
        if (artistMatch != null && artistMatch.radioId.isNotBlank()) {
            val radioRes = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.radioId))
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val data = (radioRes as? Resource.Success)?.data
            if (data != null && data.first.isNotEmpty()) {
                startQueue(
                    tracks = data.first,
                    playlistId = artistMatch.radioId,
                    playlistName = "${artistMatch.artist} radio",
                    playlistType = PlaylistType.RADIO,
                    configType = Config.RADIO_CLICK,
                    continuation = data.second,
                )
                return "${artistMatch.artist} radio"
            }
        }

        val songsRes = searchRepository.getSearchDataSong(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val results = (songsRes as? Resource.Success)?.data
        if (results.isNullOrEmpty()) throw JerryPlaybackException("No radio found for $query")
        val track = selectBestSong(results, query).toTrack()
        val playlistId = "RDAMVM${track.videoId}"
        val radioRes = songRepository.getRadioFromEndpoint(
            YouTubeWatchEndpoint(videoId = track.videoId, playlistId = playlistId),
        ).firstOrNull { it is Resource.Success || it is Resource.Error }
        val data = (radioRes as? Resource.Success)?.data
        if (data != null && data.first.isNotEmpty()) {
            startQueue(
                tracks = data.first,
                playlistId = playlistId,
                playlistName = "${track.title} radio",
                playlistType = PlaylistType.RADIO,
                configType = Config.RADIO_CLICK,
                continuation = data.second,
            )
        } else {
            startSingleTrack(track)
        }
        return "${track.title} radio"
    }

    override suspend fun resume(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (handler.player.mediaItemCount > 0) {
            if (handler.player.playbackState == PlayerConstants.STATE_IDLE) {
                handler.player.prepare()
            }
            handler.player.play()
            true
        } else {
            handler.restoreQueueAndPlay()
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        handler.player.pause()
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        handler.player.stop()
    }

    override suspend fun next(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (handler.player.mediaItemCount == 0) false else {
            handler.onPlayerEvent(PlayerEvent.Next)
            true
        }
    }

    override suspend fun previous(): Boolean {
        val currentTrack = handler.nowPlayingState.value.let { it.track ?: it.songEntity?.toTrack() }
        val currentId = currentTrack?.videoId
        val hasPrev = withContext(Dispatchers.Main.immediate) { handler.player.hasPreviousMediaItem() }
        if (hasPrev) {
            withContext(Dispatchers.Main.immediate) {
                handler.onPlayerEvent(PlayerEvent.SkipToPrevious)
            }
            return true
        }

        val fallbackTrack = popPreviousFromHistory(currentId) ?: findRecentFallbackTrack(currentId)
        if (fallbackTrack != null) {
            val queue = if (currentTrack != null && currentTrack.videoId.isNotBlank() && currentTrack.videoId != fallbackTrack.videoId) {
                listOf(fallbackTrack, currentTrack)
            } else {
                listOf(fallbackTrack)
            }
            suppressHistoryPush = true
            startQueue(
                tracks = queue,
                playlistId = "RDAMVM${fallbackTrack.videoId}",
                playlistName = "Jarvis",
                playlistType = PlaylistType.RADIO,
                configType = if (queue.size > 1) Config.PLAYLIST_CLICK else Config.SONG_CLICK,
                continuation = null,
                recordOutgoing = false,
            )
            return true
        }

        return withContext(Dispatchers.Main.immediate) {
            if (handler.player.mediaItemCount == 0) {
                false
            } else {
                handler.onPlayerEvent(PlayerEvent.Previous)
                true
            }
        }
    }

    override suspend fun restartTrack(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (handler.player.mediaItemCount == 0) return@withContext false
        handler.player.seekTo(0L)
        if (!handler.player.isPlaying) {
            if (handler.player.playbackState == PlayerConstants.STATE_IDLE) {
                handler.player.prepare()
            }
            handler.player.play()
        }
        true
    }

    override suspend fun seekBy(deltaSeconds: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        if (handler.player.mediaItemCount == 0) return@withContext false
        val current = handler.getProgress().coerceAtLeast(0L)
        val dur = handler.getPlayerDuration()
        val rawTarget = current + deltaSeconds * 1000L
        val target = if (dur > 0L) rawTarget.coerceIn(0L, dur) else rawTarget.coerceAtLeast(0L)
        handler.player.seekTo(target)
        true
    }

    override suspend fun seekToPercent(percent: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        val dur = handler.getPlayerDuration()
        if (handler.player.mediaItemCount == 0 || dur <= 0L) return@withContext false
        val clamped = percent.coerceIn(0, 100)
        val target = (dur * clamped / 100L).coerceIn(0L, dur)
        handler.player.seekTo(target)
        true
    }

    override suspend fun setShuffle(enabled: Boolean?): Boolean = withContext(Dispatchers.Main.immediate) {
        val next = enabled ?: !handler.player.shuffleModeEnabled
        if (handler.player.shuffleModeEnabled != next) {
            handler.onPlayerEvent(PlayerEvent.Shuffle)
        }
        handler.player.shuffleModeEnabled
    }

    override suspend fun setRepeat(mode: RepeatTarget): RepeatTarget = withContext(Dispatchers.Main.immediate) {
        if (mode == RepeatTarget.CYCLE) {
            handler.onPlayerEvent(PlayerEvent.Repeat)
        } else {
            val targetMode = when (mode) {
                RepeatTarget.OFF -> PlayerConstants.REPEAT_MODE_OFF
                RepeatTarget.ALL -> PlayerConstants.REPEAT_MODE_ALL
                RepeatTarget.ONE -> PlayerConstants.REPEAT_MODE_ONE
                RepeatTarget.CYCLE -> PlayerConstants.REPEAT_MODE_OFF
            }
            var attempts = 0
            while (handler.player.repeatMode != targetMode && attempts < 3) {
                handler.onPlayerEvent(PlayerEvent.Repeat)
                attempts++
            }
            if (handler.player.repeatMode != targetMode) {
                handler.player.repeatMode = targetMode
            }
        }
        when (handler.player.repeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> RepeatTarget.ALL
            PlayerConstants.REPEAT_MODE_ONE -> RepeatTarget.ONE
            else -> RepeatTarget.OFF
        }
    }

    override suspend fun toggleLike(like: Boolean?): Boolean? {
        val videoId = activeVideoId() ?: return null
        val target = like ?: !handler.controlState.value.isLiked
        songRepository.updateLikeStatus(videoId, if (target) 1 else 0)
        withContext(Dispatchers.Main.immediate) {
            handler.like(target)
        }
        runCatching {
            if (target) {
                songRepository.addToYouTubeLiked(videoId).firstOrNull()
            } else {
                songRepository.removeFromYouTubeLiked(videoId).firstOrNull()
            }
        }
        return target
    }

    override suspend fun dislike(): Boolean {
        val videoId = activeVideoId() ?: return false
        songRepository.updateLikeStatus(videoId, 0)
        withContext(Dispatchers.Main.immediate) {
            handler.like(false)
        }
        runCatching {
            songRepository.addToYouTubeDisliked(videoId).firstOrNull()
        }
        return true
    }

    override suspend fun setSleepTimer(minutes: Int?): Boolean = withContext(Dispatchers.Main.immediate) {
        if (minutes == null || minutes <= 0) {
            handler.sleepStop()
            false
        } else {
            handler.sleepStart(minutes)
            true
        }
    }

    override suspend fun playNextQuery(query: String): String {
        awaitCookieReadyIfNeeded()
        val songs = searchRepository.getSearchDataSong(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val results = (songs as? Resource.Success)?.data
        if (results.isNullOrEmpty()) throw JerryPlaybackException("No results for $query")
        val track = selectBestSong(results, query).toTrack()
        val count = withContext(Dispatchers.Main.immediate) { handler.player.mediaItemCount }
        if (count > 0) {
            withContext(Dispatchers.Main.immediate) {
                handler.playNext(track)
            }
        } else {
            startSingleTrack(track)
        }
        return track.title
    }

    override suspend fun addToQueueQuery(query: String): String {
        awaitCookieReadyIfNeeded()
        val songs = searchRepository.getSearchDataSong(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val results = (songs as? Resource.Success)?.data
        if (results.isNullOrEmpty()) throw JerryPlaybackException("No results for $query")
        val track = selectBestSong(results, query).toTrack()
        val count = withContext(Dispatchers.Main.immediate) { handler.player.mediaItemCount }
        if (count == 0) {
            startSingleTrack(track)
        } else {
            val endless = runCatching { dataStoreManager?.endlessQueue?.first() == TRUE }.getOrDefault(false)
            val blocked = handler.queueData.value.addToQueueBlock(endless) != null
            withContext(Dispatchers.Main.immediate) {
                if (blocked) {
                    handler.playNext(track)
                } else {
                    handler.loadMoreCatalog(arrayListOf(track), isAddToQueue = true)
                }
            }
        }
        return track.title
    }

    override suspend fun clearQueue(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (handler.player.mediaItemCount == 0 && handler.queueData.value?.data?.listTracks.isNullOrEmpty()) {
            return@withContext false
        }
        handler.resetSongAndQueue()
        true
    }

    override suspend fun showQueue(): Boolean = navigate(JerryNavTarget.QUEUE)

    override suspend fun mute() = volume.mute()

    override suspend fun unmute() = volume.unmute()

    override suspend fun navigate(target: JerryNavTarget): Boolean = JarvisUiEvents.emitNav(target)

    override fun adjustVolume(up: Boolean) = volume.adjust(up)

    override fun setVolume(percent: Int) = volume.setLevel(percent.coerceIn(0, 100))

    private fun activeVideoId(): String? {
        val state = handler.nowPlayingState.value
        val raw = state.track?.videoId
            ?: state.songEntity?.videoId
            ?: state.mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        return raw.removePrefix(MERGING_DATA_TYPE.VIDEO).takeIf { it.isNotBlank() }
    }

    internal fun selectBestSong(results: List<SongsResult>, query: String): SongsResult {
        val byMatch = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(query.trim())
        val titleQuery = byMatch?.groupValues?.get(1)?.trim() ?: query.trim()
        val artistQuery = byMatch?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

        val qLower = query.lowercase()
        val wantsLive = "live" in qLower
        val wantsRemix = "remix" in qLower
        val wantsCover = "cover" in qLower
        val wantsKaraoke = "karaoke" in qLower
        val wantsAcoustic = "acoustic" in qLower
        val wantsInstrumental = "instrumental" in qLower
        val wantsSpedUp = "sped up" in qLower
        val wantsSlowed = "slowed" in qLower
        val wantsNightcore = "nightcore" in qLower
        val anyModifierRequested = wantsLive || wantsRemix || wantsCover || wantsKaraoke ||
            wantsAcoustic || wantsInstrumental || wantsSpedUp || wantsSlowed || wantsNightcore

        val normTarget = normalizeMatchKey(titleQuery)
        val targetTokens = normTarget.split(' ').filter { it.isNotEmpty() }
        val normArtist = artistQuery?.let { normalizeMatchKey(it) }

        return results.maxByOrNull { song ->
            var score = 0
            val rawTitle = song.title.orEmpty()
            val titleLower = rawTitle.lowercase()
            val normTitle = normalizeMatchKey(rawTitle)

            score += when (song.videoType) {
                MUSIC_VIDEO_TYPE_ATV -> 40
                MUSIC_VIDEO_TYPE_OMV -> 25
                MUSIC_VIDEO_TYPE_UGC -> if (anyModifierRequested) 0 else -30
                else -> {
                    val hasSquareThumb = song.thumbnails.orEmpty().any { it.width > 0 && it.width == it.height }
                    if (hasSquareThumb) 20 else 5
                }
            }

            if (song.resultType == LIVE_RESULT_TYPE && !wantsLive) {
                score -= 50
            }

            score += when {
                rawTitle.equals(titleQuery, ignoreCase = true) || rawTitle.equals(query, ignoreCase = true) -> 50
                normTarget.isNotEmpty() && normTitle == normTarget -> 35
                normTarget.isNotEmpty() && normTitle.contains(normTarget) -> 15
                targetTokens.isNotEmpty() && targetTokens.all { it in normTitle } -> 10
                else -> 0
            }

            if (!normArtist.isNullOrEmpty()) {
                val artistsNorm = song.artists.orEmpty().map { normalizeMatchKey(it.name) }
                if (artistsNorm.any { it == normArtist || it.contains(normArtist) || normArtist.contains(it) }) {
                    score += 40
                }
            }

            if (!song.album?.name.isNullOrBlank()) {
                score += 10
            }

            if (!wantsCover && ("cover" in titleLower || "tribute" in titleLower || "parody" in titleLower)) score -= 40
            if (!wantsKaraoke && "karaoke" in titleLower) score -= 40
            if (!wantsSpedUp && "sped up" in titleLower) score -= 40
            if (!wantsSlowed && ("slowed" in titleLower || "reverb" in titleLower)) score -= 40
            if (!wantsNightcore && "nightcore" in titleLower) score -= 40
            if ("8d audio" in titleLower) score -= 40
            if (!wantsAcoustic && "acoustic" in titleLower && "acoustic" !in qLower) score -= 15
            if (!wantsInstrumental && "instrumental" in titleLower && "instrumental" !in qLower) score -= 15
            if (!wantsLive && ("(live" in titleLower || "[live" in titleLower || "live at " in titleLower || "live from " in titleLower || "live in " in titleLower)) {
                score -= 35
            }
            if (!wantsRemix && "remix" in titleLower) {
                score -= 25
            }

            score
        } ?: results.first()
    }

    private suspend fun startSingleTrack(track: Track) {
        currentCoroutineContext().ensureActive()
        recordOutgoingCurrentTrack()
        handoffMutex.withLock {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                handler.reset()
                handler.setQueueData(
                    QueueData.Data(
                        listTracks = arrayListOf(track),
                        firstPlayedTrack = track,
                        playlistId = "RDAMVM${track.videoId}",
                        playlistName = "Jarvis",
                        playlistType = PlaylistType.RADIO,
                        continuation = null,
                    ),
                )
                handler.loadMediaItem(track, Config.SONG_CLICK, 0)
            }
        }
    }

    private suspend fun tryPlayMix(query: String): String? {
        val rawLower = query.lowercase().trim()
        val normalized = normalizeMixTitle(query)
        if (normalized.isEmpty()) return null

        val isGenericRemixRequest = rawLower in GENERIC_REMIX_QUERIES
        if (isGenericRemixRequest) {
            tryPlayUserLibrary("remixes")?.let { return it }
            tryPlayUserLibrary("remix")?.let { return it }
        }

        if (normalized in GENERIC_MIX_QUERIES || isGenericRemixRequest) {
            val mixes = fetchMixesForYou()
            val candidate = mixes
                .filterNot { it.browseId.removePrefix("VL") == "LM" }
                .ifEmpty { mixes }
                .randomOrNull()
            if (candidate != null) {
                playMixByBrowseId(candidate.browseId, candidate.title)?.let { return it }
            }
            val fallback = WELL_KNOWN_MIXES.random()
            return playMixByBrowseId(fallback.second, fallback.first)
                ?: playSearchedPlaylist(fallback.first)
        }

        val wellKnownTarget = when (normalized) {
            "discover", "discovery" -> "discover mix"
            "supermix" -> "my supermix"
            else -> normalized
        }
        val looksLikeMixQuery =
            "mix" in normalized ||
                "discover" in normalized ||
                "supermix" in normalized ||
                "replay" in normalized ||
                "new release" in normalized ||
                "liked music" in normalized ||
                WELL_KNOWN_MIXES.any { (title, _) ->
                    val knownNorm = normalizeMixTitle(title)
                    knownNorm == wellKnownTarget || knownNorm.removePrefix("my ") == wellKnownTarget.removePrefix("my ")
                }
        val mixes =
            if (looksLikeMixQuery || cachedMixes.isNotEmpty()) {
                fetchMixesForYou()
            } else {
                emptyList()
            }
        val matched = findBestMixMatch(normalized, mixes)
        if (matched != null) {
            playMixByBrowseId(matched.browseId, matched.title)?.let { return it }
        }

        val known = WELL_KNOWN_MIXES.firstOrNull { (title, _) ->
            val knownNorm = normalizeMixTitle(title)
            knownNorm == wellKnownTarget || knownNorm.removePrefix("my ") == wellKnownTarget.removePrefix("my ")
        }
        if (known != null) {
            return playMixByBrowseId(known.second, known.first)
                ?: playSearchedPlaylist(known.first)
        }

        if ("mix" in normalized && mixes.isEmpty()) {
            playSearchedPlaylist(query)?.let { return it }
        }
        return null
    }

    private fun findBestMixMatch(normalizedQuery: String, mixes: List<PlaylistsResult>): PlaylistsResult? {
        if (mixes.isEmpty()) return null
        val target = when (normalizedQuery) {
            "discover", "discovery" -> "discover mix"
            else -> normalizedQuery
        }
        mixes.firstOrNull { normalizeMixTitle(it.title) == target }?.let { return it }
        val strippedTarget = target.removePrefix("my ")
        mixes.firstOrNull { normalizeMixTitle(it.title).removePrefix("my ") == strippedTarget }?.let { return it }
        if ("mix" in target || target == "liked music") {
            mixes.firstOrNull { mix ->
                val normTitle = normalizeMixTitle(mix.title)
                normTitle.startsWith(target) || normTitle.removePrefix("my ").startsWith(strippedTarget)
            }?.let { return it }
        }
        return null
    }

    private suspend fun fetchMixesForYou(): List<PlaylistsResult> {
        val repo = playlistRepository ?: return emptyList()
        val now = System.currentTimeMillis()
        if (cachedMixes.isNotEmpty() && now - cachedMixesAtMs < MIX_CACHE_TTL_MS) {
            return cachedMixes
        }
        val fresh = runCatching { repo.getMixedForYou().firstOrNull() }.getOrNull().orEmpty()
        val likedMixes = runCatching { repo.getLikedPlaylists().firstOrNull() }.getOrNull().orEmpty()
            .filter { p ->
                val t = p.title.lowercase()
                "mix" in t || "remix" in t
            }
            .map { p ->
                PlaylistsResult(
                    author = "",
                    browseId = p.id,
                    category = "Mix",
                    itemCount = "${p.trackCount}",
                    resultType = "Playlist",
                    thumbnails = emptyList(),
                    title = p.title,
                )
            }
        val combined = (fresh + likedMixes)
            .distinctBy { it.browseId.removePrefix("VL") }
        cachedMixesAtMs = now
        if (combined.isNotEmpty()) {
            cachedMixes = combined
        }
        Logger.d(TAG, "fetchMixesForYou loaded ${combined.size} mixes: ${combined.joinToString { it.title }}")
        return combined.ifEmpty { cachedMixes }
    }

    private suspend fun playMixByBrowseId(browseId: String, title: String): String? {
        val cleanId = browseId.removePrefix("VL")
        val radioRes = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = cleanId))
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val radioData = (radioRes as? Resource.Success)?.data
        if (radioData != null && radioData.first.isNotEmpty()) {
            val isRadio = cleanId.isRadioQueueId()
            startQueue(
                tracks = radioData.first,
                playlistId = cleanId,
                playlistName = title,
                playlistType = if (isRadio) PlaylistType.RADIO else PlaylistType.PLAYLIST,
                configType = if (isRadio) Config.RADIO_CLICK else Config.PLAYLIST_CLICK,
                continuation = radioData.second,
            )
            return title
        }

        val repo = playlistRepository ?: return null
        if (cleanId.isRadioPlaylistId()) {
            val rdatRes = repo.getRDATRadioData(cleanId, "views")
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val rdat = (rdatRes as? Resource.Success)?.data
            if (rdat != null && rdat.first.tracks.isNotEmpty()) {
                val resolvedTitle = rdat.first.title.ifBlank { title }
                startQueue(
                    tracks = rdat.first.tracks,
                    playlistId = cleanId,
                    playlistName = resolvedTitle,
                    playlistType = PlaylistType.RADIO,
                    configType = Config.RADIO_CLICK,
                    continuation = rdat.second,
                )
                return resolvedTitle
            }
        }

        val fullRes = repo.getFullPlaylistData(browseId, "views")
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val full = (fullRes as? Resource.Success)?.data
        if (full != null && full.tracks.isNotEmpty()) {
            val resolvedTitle = full.title.ifBlank { title }
            startQueue(
                tracks = full.tracks,
                playlistId = browseId,
                playlistName = resolvedTitle,
                playlistType = PlaylistType.PLAYLIST,
                configType = Config.PLAYLIST_CLICK,
                continuation = null,
            )
            return resolvedTitle
        }
        return null
    }

    private suspend fun playSearchedPlaylist(query: String): String? {
        val playlistsRes = searchRepository.getSearchDataPlaylist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val firstPlaylist = (playlistsRes as? Resource.Success)?.data?.firstOrNull() ?: return null
        return playMixByBrowseId(firstPlaylist.browseId, firstPlaylist.title.ifBlank { query })
    }

    private suspend fun tryPlayLibraryCollection(query: String): String? {
        val lower = query.lowercase().trim()
            .removePrefix("my ").removeSuffix(" playlist").trim()
        return when (lower) {
            "favorite", "favorites", "favourite", "favourites", "favorite songs", "favourite songs",
            "liked songs", "liked song",
            -> {
                val liked = songRepository.getLikedSongs().firstOrNull().orEmpty().map { it.toTrack() }
                if (liked.isNotEmpty()) {
                    startQueue(liked, "LM", "Favorite Songs", PlaylistType.PLAYLIST, Config.PLAYLIST_CLICK, null)
                    "Favorite Songs"
                } else {
                    playMixByBrowseId("LM", "Liked Music")
                }
            }
            "downloaded", "downloads", "downloaded songs", "offline songs", "offline music" -> {
                val downloaded = songRepository.getDownloadedSongs().firstOrNull().orEmpty().map { it.toTrack() }
                if (downloaded.isNotEmpty()) {
                    startQueue(downloaded, "DOWNLOADED", "Downloaded", PlaylistType.PLAYLIST, Config.PLAYLIST_CLICK, null)
                    "Downloaded"
                } else {
                    null
                }
            }
            "most played", "most played songs", "top played", "top songs" -> {
                val mostPlayed = songRepository.getMostPlayedSongs().firstOrNull().orEmpty().map { it.toTrack() }
                if (mostPlayed.isNotEmpty()) {
                    startQueue(mostPlayed, "MOST_PLAYED", "Most Played", PlaylistType.PLAYLIST, Config.PLAYLIST_CLICK, null)
                    "Most Played"
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private suspend fun tryPlayUserLibrary(query: String): String? {
        val rawLower = query.lowercase().trim()
        val explicitPlaylist = rawLower.contains("playlist") || rawLower.startsWith("my ")
        val explicitAlbum = rawLower.contains("album")
        val target = rawLower
            .removePrefix("my ")
            .removeSuffix(" playlist")
            .removeSuffix(" album")
            .trim()
        if (target.isEmpty()) return null

        val localRepo = localPlaylistRepository
        if (localRepo != null) {
            val locals = runCatching { localRepo.getAllLocalPlaylists().firstOrNull() }.getOrNull().orEmpty()
            val match = locals.firstOrNull { it.title.equals(target, ignoreCase = true) }
                ?: if (explicitPlaylist) locals.firstOrNull { it.title.contains(target, ignoreCase = true) } else null
            if (match != null) {
                val tracks = runCatching { localRepo.getFullPlaylistTracks(match.id) }.getOrNull().orEmpty().map { it.toTrack() }
                if (tracks.isNotEmpty()) {
                    startQueue(
                        tracks = tracks,
                        playlistId = "$LOCAL_PLAYLIST_ID${match.id}",
                        playlistName = match.title,
                        playlistType = PlaylistType.LOCAL_PLAYLIST,
                        configType = Config.PLAYLIST_CLICK,
                        continuation = null,
                    )
                    return match.title
                }
            }
        }

        val plRepo = playlistRepository
        if (plRepo != null) {
            val savedPlaylists = runCatching { plRepo.getLikedPlaylists().firstOrNull() }.getOrNull().orEmpty()
            val plMatch = savedPlaylists.firstOrNull { it.title.equals(target, ignoreCase = true) }
                ?: if (explicitPlaylist) savedPlaylists.firstOrNull { it.title.contains(target, ignoreCase = true) } else null
            if (plMatch != null) {
                playMixByBrowseId(plMatch.id, plMatch.title)?.let { return it }
            }
        }

        val albRepo = albumRepository
        if (albRepo != null) {
            val savedAlbums = runCatching { albRepo.getLikedAlbums().firstOrNull() }.getOrNull().orEmpty()
            val albMatch = savedAlbums.firstOrNull { it.title.equals(target, ignoreCase = true) }
                ?: if (explicitAlbum) savedAlbums.firstOrNull { it.title.contains(target, ignoreCase = true) } else null
            if (albMatch != null) {
                val res = albRepo.getAlbumData(albMatch.browseId).firstOrNull { it is Resource.Success || it is Resource.Error }
                val tracks = (res as? Resource.Success)?.data?.tracks
                if (!tracks.isNullOrEmpty()) {
                    startQueue(tracks, albMatch.browseId, albMatch.title, PlaylistType.ALBUM, Config.ALBUM_CLICK, null)
                    return albMatch.title
                }
            }
        }

        return null
    }

    private suspend fun startQueue(
        tracks: List<Track>,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType,
        configType: String,
        continuation: String?,
        recordOutgoing: Boolean = true,
    ) {
        val uniqueTracks = tracks.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        if (uniqueTracks.isEmpty()) return
        currentCoroutineContext().ensureActive()
        if (recordOutgoing) recordOutgoingCurrentTrack()
        handoffMutex.withLock {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                handler.reset()
                handler.setQueueData(
                    QueueData.Data(
                        listTracks = uniqueTracks.toCollection(arrayListOf()),
                        firstPlayedTrack = uniqueTracks.first(),
                        playlistId = playlistId,
                        playlistName = playlistName,
                        playlistType = playlistType,
                        continuation = continuation,
                    ),
                )
                handler.loadMediaItem(uniqueTracks.first(), configType, 0)
            }
        }
    }

    private fun recordOutgoingCurrentTrack() {
        val current = handler.nowPlayingState.value.let { it.track ?: it.songEntity?.toTrack() } ?: return
        if (current.videoId.isBlank()) return
        synchronized(historyLock) {
            pushHistoryLocked(current)
            lastObservedTrack = current
        }
    }

    private fun pushHistoryLocked(track: Track) {
        if (playedHistory.lastOrNull()?.videoId == track.videoId) return
        playedHistory.addLast(track)
        while (playedHistory.size > MAX_HISTORY) {
            playedHistory.removeFirst()
        }
    }

    private fun popPreviousFromHistory(currentId: String?): Track? {
        synchronized(historyLock) {
            while (playedHistory.isNotEmpty() && playedHistory.last().videoId == currentId) {
                playedHistory.removeLast()
            }
            return if (playedHistory.isNotEmpty()) playedHistory.removeLast() else null
        }
    }

    private suspend fun findRecentFallbackTrack(currentId: String?): Track? {
        val recent = runCatching { songRepository.getAllSongs(15).firstOrNull() }.getOrNull().orEmpty()
        return recent.firstOrNull { it.videoId.isNotBlank() && it.videoId != currentId }?.toTrack()
    }

    private fun normalizeMatchKey(raw: String): String =
        raw.lowercase().replace(NON_ALNUM_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()

    companion object {
        private const val TAG = "JerryMediaPlayback"
        private const val MAX_HISTORY = 50
        private const val MIX_CACHE_TTL_MS = 5 * 60 * 1000L

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val NUMBER_WORDS = mapOf(
            "zero" to "0", "01" to "1", "one" to "1", "02" to "2", "two" to "2",
            "03" to "3", "three" to "3", "04" to "4", "four" to "4", "05" to "5", "five" to "5",
            "06" to "6", "six" to "6", "07" to "7", "seven" to "7", "08" to "8", "eight" to "8",
            "09" to "9", "nine" to "9", "ten" to "10",
        )

        private val GENERIC_MIX_QUERIES = setOf(
            "mix", "mixes", "random mix", "mix for you", "mixes for you",
            "mix for me", "mixes for me", "all mix", "all my mix", "all of my mix",
        )

        private val GENERIC_REMIX_QUERIES = setOf(
            "remix", "remixes", "the remixes", "my remixes", "all remixes",
            "all my remixes", "all of my remixes", "some remixes", "a remix",
            "random remix", "a random remix", "remixes for me", "remixes for you",
        )

        private val WELL_KNOWN_MIXES = listOf(
            "Discover Mix" to "RDTMAK5uy_n_5IN6hzAOwdCnM8D8rzrs3vDl12UcZpA",
            "My Supermix" to "RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY",
            "New Release Mix" to "RDTMAK5uy_lr0LWzGrq6FU9GIxWvFHTRPQD2LHMqlFA",
            "Replay Mix" to "RDTMAK5uy_kpxnNxJpPZjLKbL9WgG23WK3n6d3E8_A",
            "Archive Mix" to "RDTMAK5uy_nGQKSMIkpr4o9VI_2i56pk03342506D8",
            "Liked Music" to "LM",
        )

        internal fun normalizeMixTitle(raw: String): String {
            val cleaned = raw.lowercase()
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .replace("discovery", "discover")
                .replace("mixes", "mix")
                .replace("super mix", "supermix")
                .replace("new releases", "new release")
                .replace("favourites", "liked music")
                .replace("favorites", "liked music")
                .trim()
                .removePrefix("the ")
                .trim()
            return cleaned.split(' ').joinToString(" ") { NUMBER_WORDS[it] ?: it }
        }
    }
}
