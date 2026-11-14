package com.maxrave.simpmusic.jerry

import com.maxrave.common.Config
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioPlaylistId
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun interface JerryVolume {
    fun adjust(up: Boolean)
    fun setLevel(percent: Int) {}
}

class JerryMediaPlayback(
    private val handler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val volume: JerryVolume,
    private val playlistRepository: PlaylistRepository? = null,
    private val localPlaylistRepository: LocalPlaylistRepository? = null,
    private val albumRepository: AlbumRepository? = null,
    private val scope: CoroutineScope? = null,
) : JerryPlayback {
    private val historyLock = Any()
    private val playedHistory = ArrayDeque<Track>()
    private var lastObservedTrack: Track? = null
    @Volatile private var suppressHistoryPush = false

    @Volatile private var cachedMixes: List<PlaylistsResult> = emptyList()
    @Volatile private var cachedMixesAtMs: Long = 0L

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

    override suspend fun playQuery(query: String): String {
        tryPlayMix(query)?.let { return it }
        tryPlayLibraryCollection(query)?.let { return it }
        tryPlayUserLibrary(query)?.let { return it }

        val artist = searchRepository.getSearchDataArtist(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val normalizedQuery = query.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, " ").trim()
        val artistList = (artist as? Resource.Success)?.data.orEmpty()
        val artistMatch = artistList.firstOrNull { it.artist.equals(query, ignoreCase = true) }
            ?: artistList.firstOrNull {
                it.artist.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, " ").trim() == normalizedQuery
            }
        if (artistMatch != null && artistMatch.radioId.isNotBlank()) {
            val radio = songRepository.getRadioFromEndpoint(YouTubeWatchEndpoint(playlistId = artistMatch.radioId))
                .firstOrNull { it is Resource.Success || it is Resource.Error }
            val tracks = (radio as? Resource.Success)?.data?.first
            if (!tracks.isNullOrEmpty()) {
                startQueue(
                    tracks = tracks,
                    playlistId = artistMatch.radioId,
                    playlistName = "${artistMatch.artist} radio",
                    playlistType = PlaylistType.RADIO,
                    configType = Config.PLAYLIST_CLICK,
                    continuation = (radio as? Resource.Success)?.data?.second,
                )
                return "${artistMatch.artist} radio"
            }
        }

        val songs = searchRepository.getSearchDataSong(query)
            .firstOrNull { it is Resource.Success || it is Resource.Error }
        val results = (songs as? Resource.Success)?.data
        if (results.isNullOrEmpty()) throw JerryPlaybackException("No results for $query")
        val exact = results.filter { it.title.equals(query, ignoreCase = true) }
        val best = exact.firstOrNull() ?: results.first()
        val finalTrack = best.toTrack()
        recordOutgoingCurrentTrack()
        withContext(Dispatchers.Main) {
            handler.player.stop()
            handler.reset()
            handler.setQueueData(
                QueueData.Data(
                    listTracks = arrayListOf(finalTrack),
                    firstPlayedTrack = finalTrack,
                    playlistId = "RDAMVM${finalTrack.videoId}",
                    playlistName = "Jarvis",
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            handler.loadMediaItem(finalTrack, Config.SONG_CLICK, 0)
        }
        return finalTrack.title
    }

    override suspend fun resume(): Boolean = withContext(Dispatchers.Main) {
        if (handler.player.mediaItemCount > 0) {
            if (handler.player.playbackState == 1) {
                handler.player.prepare()
            }
            handler.player.play()
            true
        } else {
            handler.restoreQueueAndPlay()
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) { handler.player.pause() }

    override suspend fun stop() = withContext(Dispatchers.Main) { handler.player.stop() }

    override suspend fun next(): Boolean = withContext(Dispatchers.Main) {
        if (handler.player.mediaItemCount == 0) false else {
            handler.onPlayerEvent(PlayerEvent.Next)
            true
        }
    }

    override suspend fun previous(): Boolean {
        val currentTrack = handler.nowPlayingState.value.let { it.track ?: it.songEntity?.toTrack() }
        val currentId = currentTrack?.videoId
        val hasPrev = withContext(Dispatchers.Main) { handler.player.hasPreviousMediaItem() }
        if (hasPrev) {
            withContext(Dispatchers.Main) {
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

        return withContext(Dispatchers.Main) {
            if (handler.player.mediaItemCount == 0) {
                false
            } else {
                handler.onPlayerEvent(PlayerEvent.Previous)
                true
            }
        }
    }

    override fun adjustVolume(up: Boolean) = volume.adjust(up)

    override fun setVolume(percent: Int) = volume.setLevel(percent.coerceIn(0, 100))

    private suspend fun tryPlayMix(query: String): String? {
        val rawLower = query.lowercase(Locale.ROOT).trim()
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

        val mixes = fetchMixesForYou()
        val matched = findBestMixMatch(normalized, mixes)
        if (matched != null) {
            playMixByBrowseId(matched.browseId, matched.title)?.let { return it }
        }

        val wellKnownTarget = when (normalized) {
            "discover", "discovery" -> "discover mix"
            "supermix" -> "my supermix"
            else -> normalized
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
                val t = p.title.lowercase(Locale.ROOT)
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
        if (combined.isNotEmpty()) {
            cachedMixes = combined
            cachedMixesAtMs = now
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
        val lower = query.lowercase(Locale.ROOT).trim()
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
        val rawLower = query.lowercase(Locale.ROOT).trim()
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
        if (recordOutgoing) recordOutgoingCurrentTrack()
        withContext(Dispatchers.Main) {
            handler.player.stop()
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
            val cleaned = raw.lowercase(Locale.ROOT)
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
