package com.maxrave.simpmusic.localaudio

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "LocalAudioLibraryManager"

@OptIn(ExperimentalTime::class)
class LocalAudioLibraryManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val scanner: suspend (List<String>) -> LocalAudioScanResult = { LocalAudioScanner.scan(it) },
    private val defaultFoldersProvider: () -> List<String> = { LocalAudioScanner.defaultFolders() },
    private val clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(LocalAudioLibraryState())
    val state: StateFlow<LocalAudioLibraryState> = _state.asStateFlow()

    private var loadedFromStore = false

    init {
        scope.launch {
            ensureLoaded()
        }
    }

    suspend fun ensureLoaded(): LocalAudioLibraryState =
        mutex.withLock {
            if (!loadedFromStore) {
                val raw = runCatching { dataStoreManager.getString(LocalAudioConfig.DATASTORE_KEY).first() }.getOrNull()
                val decoded = LocalAudioConfig.decodeState(raw)
                val folders = decoded.watchedFolders.ifEmpty { defaultFoldersProvider() }
                val initialized = decoded.copy(watchedFolders = folders, isScanning = false)
                _state.value = initialized
                loadedFromStore = true
            }
            _state.value
        }

    suspend fun addFolder(folderPath: String): Boolean {
        val trimmed = folderPath.trim()
        if (trimmed.isEmpty()) return false
        ensureLoaded()
        return mutex.withLock {
            val current = _state.value
            val normalized = trimmed.trimEnd('/', '\\')
            if (current.watchedFolders.any { it.trimEnd('/', '\\').equals(normalized, ignoreCase = true) }) {
                return@withLock false
            }
            val updated = current.copy(watchedFolders = current.watchedFolders + trimmed)
            _state.value = updated
            persistState(updated)
            true
        }
    }

    suspend fun removeFolder(folderPath: String): Boolean {
        ensureLoaded()
        return mutex.withLock {
            val current = _state.value
            val next = current.watchedFolders.filterNot { it.equals(folderPath, ignoreCase = true) }
            if (next.size == current.watchedFolders.size) return@withLock false
            val updated = current.copy(watchedFolders = next)
            _state.value = updated
            persistState(updated)
            true
        }
    }

    suspend fun scanLibrary(): LocalAudioScanResult {
        ensureLoaded()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val folders = _state.value.watchedFolders.ifEmpty { defaultFoldersProvider() }
                _state.update { it.copy(isScanning = true, lastError = null, watchedFolders = folders) }
                try {
                    val result = scanner(folders)
                    val now = clockNowMs()
                    val updated =
                        _state.value.copy(
                            watchedFolders = folders,
                            tracks = result.tracks,
                            isScanning = false,
                            lastScanAtMs = now,
                            lastError = result.errorMessage,
                        )
                    _state.value = updated
                    persistState(updated)
                    Logger.i(TAG, "Scanned ${result.tracks.size} local audio tracks across ${folders.size} folders")
                    result
                } catch (e: Exception) {
                    val err = e.message ?: "Local audio scan failed"
                    Logger.e(TAG, err, e)
                    val failed = _state.value.copy(isScanning = false, lastError = err)
                    _state.value = failed
                    persistState(failed)
                    LocalAudioScanResult(tracks = failed.tracks, errorMessage = err)
                }
            }
        }
    }

    fun filterTracks(
        query: String,
        formatFilter: String? = null,
        tracks: List<LocalAudioTrack> = _state.value.tracks,
    ): List<LocalAudioTrack> {
        val q = query.trim().lowercase()
        val fmt = formatFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "ALL" }
        return tracks.filter { track ->
            val formatOk = fmt == null || track.format.equals(fmt, ignoreCase = true)
            val queryOk =
                q.isEmpty() ||
                    track.title.lowercase().contains(q) ||
                    track.artist.lowercase().contains(q) ||
                    track.album.lowercase().contains(q) ||
                    track.filePath.lowercase().contains(q)
            formatOk && queryOk
        }
    }

    fun playTrack(
        track: LocalAudioTrack,
        queueTracks: List<LocalAudioTrack> = _state.value.tracks,
    ) {
        val effectiveList = if (queueTracks.any { it.id == track.id }) queueTracks else listOf(track)
        val startIndex = effectiveList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val orderedFromTrack = effectiveList.drop(startIndex) + effectiveList.take(startIndex)
        enqueueLocalTracks(orderedFromTrack, shuffle = false)
    }

    fun playAll(
        tracks: List<LocalAudioTrack> = _state.value.tracks,
        shuffle: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        enqueueLocalTracks(ordered, shuffle = shuffle)
    }

    private fun enqueueLocalTracks(
        orderedTracks: List<LocalAudioTrack>,
        shuffle: Boolean,
    ) {
        if (orderedTracks.isEmpty()) return
        val domainTracks = orderedTracks.map { it.toTrack() }
        val mediaItems = orderedTracks.map { it.toGenericMediaItem() }

        mediaPlayerHandler.setShuffle(shuffle)
        mediaPlayerHandler.reset()
        mediaPlayerHandler.setQueueData(
            QueueData.Data(
                listTracks = domainTracks,
                firstPlayedTrack = domainTracks.first(),
                playlistId = LOCAL_AUDIO_PLAYLIST_ID,
                playlistName = "Local Audio",
                playlistType = PlaylistType.LOCAL_PLAYLIST,
                continuation = null,
            ),
        )
        mediaPlayerHandler.clearMediaItems()
        mediaPlayerHandler.addMediaItem(mediaItems.first(), playWhenReady = true)
        if (mediaItems.size > 1) {
            mediaPlayerHandler.addMediaItemList(mediaItems.drop(1))
        }
    }

    private suspend fun persistState(state: LocalAudioLibraryState) {
        runCatching {
            dataStoreManager.putString(LocalAudioConfig.DATASTORE_KEY, LocalAudioConfig.encodeState(state))
        }.onFailure { err ->
            Logger.w(TAG, "Failed to persist local audio state: ${err.message}")
        }
    }

    companion object {
        const val LOCAL_AUDIO_PLAYLIST_ID = "LOCAL_AUDIO_LIBRARY"
    }
}
