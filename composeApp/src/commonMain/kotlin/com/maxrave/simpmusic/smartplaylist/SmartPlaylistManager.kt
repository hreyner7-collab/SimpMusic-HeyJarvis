package com.maxrave.simpmusic.smartplaylist

import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MAX_SONGS_TO_EVALUATE = 5_000
private const val MAX_EVENTS_TO_EVALUATE = 3_000

@OptIn(ExperimentalTime::class)
class SmartPlaylistManager(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val mutex = Mutex()

    val customDefinitionsFlow: Flow<List<SmartPlaylistDefinition>> =
        dataStoreManager
            .getString(SmartPlaylistCodec.DATASTORE_KEY)
            .map { SmartPlaylistCodec.decodeCustomPlaylists(it) }
            .distinctUntilChanged()

    val definitionsFlow: Flow<List<SmartPlaylistDefinition>> =
        customDefinitionsFlow.map { custom ->
            SmartPlaylistPresets.all + custom
        }

    suspend fun getAllDefinitions(): List<SmartPlaylistDefinition> = definitionsFlow.first()

    suspend fun saveCustomPlaylist(definition: SmartPlaylistDefinition): SmartPlaylistDefinition =
        mutex.withLock {
            val current = customDefinitionsFlow.first().toMutableList()
            val cleanId =
                definition.id.trim().takeIf { it.isNotEmpty() && !it.startsWith("preset_") }
                    ?: "smart_${clockNowMs()}_${current.size}"
            val clean =
                definition.copy(
                    id = cleanId,
                    name = definition.name.trim().ifBlank { "Smart Playlist" },
                    description = definition.description.trim(),
                    limit = definition.limit.coerceIn(1, 500),
                    isBuiltIn = false,
                )
            val existingIdx = current.indexOfFirst { it.id == clean.id }
            if (existingIdx >= 0) {
                current[existingIdx] = clean
            } else {
                current += clean
            }
            dataStoreManager.putString(
                SmartPlaylistCodec.DATASTORE_KEY,
                SmartPlaylistCodec.encodeCustomPlaylists(current),
            )
            clean
        }

    suspend fun deleteCustomPlaylist(id: String): Boolean =
        mutex.withLock {
            if (SmartPlaylistPresets.all.any { it.id == id }) return@withLock false
            val current = customDefinitionsFlow.first().toMutableList()
            val removed = current.removeAll { it.id == id }
            if (removed) {
                dataStoreManager.putString(
                    SmartPlaylistCodec.DATASTORE_KEY,
                    SmartPlaylistCodec.encodeCustomPlaylists(current),
                )
            }
            removed
        }

    suspend fun evaluatePlaylist(definition: SmartPlaylistDefinition): List<SongEntity> =
        withContext(Dispatchers.IO) {
            val snapshot = loadEvaluationSnapshot()
            SmartPlaylistEngine.evaluate(
                definition = definition,
                songs = snapshot.songs,
                lastPlayedByVideoId = snapshot.lastPlayedByVideoId,
                nowMs = clockNowMs(),
                playTimeSecondsOverride = snapshot.playTimeSecondsByVideoId,
            )
        }

    suspend fun evaluateAllCounts(): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val definitions = getAllDefinitions()
            val snapshot = loadEvaluationSnapshot()
            val now = clockNowMs()
            definitions.associate { def ->
                def.id to
                    SmartPlaylistEngine
                        .evaluate(
                            definition = def,
                            songs = snapshot.songs,
                            lastPlayedByVideoId = snapshot.lastPlayedByVideoId,
                            nowMs = now,
                            playTimeSecondsOverride = snapshot.playTimeSecondsByVideoId,
                        ).size
            }
        }

    /**
     * Evaluates [definition] and starts playback via [mediaPlayerHandler].
     * Respects SimpMusic's Play vs Shuffle contract:
     * - `shuffle = false`: resets shuffle OFF and starts at index 0 in exact sorted order.
     * - `shuffle = true`: enables shuffle ON, preserves all tracks in queue, and starts from a random track.
     */
    suspend fun playSmartPlaylist(
        definition: SmartPlaylistDefinition,
        shuffle: Boolean = false,
    ): List<SongEntity> {
        val songs = evaluatePlaylist(definition)
        if (songs.isEmpty()) return emptyList()

        val tracks = songs.map { it.toTrack() }
        val startIndex = if (shuffle && tracks.size > 1) tracks.indices.random() else 0
        val startTrack = tracks[startIndex]

        withContext(mainDispatcher) {
            mediaPlayerHandler.setShuffle(shuffle)
            mediaPlayerHandler.reset()
            mediaPlayerHandler.setQueueData(
                QueueData.Data(
                    listTracks = tracks,
                    firstPlayedTrack = startTrack,
                    playlistId = "SMART_${definition.id}",
                    playlistName = definition.name,
                    playlistType = PlaylistType.PLAYLIST,
                    continuation = null,
                ),
            )
            mediaPlayerHandler.loadMediaItem(
                anyTrack = startTrack,
                type = Config.PLAYLIST_CLICK,
                index = startIndex,
            )
        }
        return songs
    }

    private data class EvaluationSnapshot(
        val songs: List<SongEntity>,
        val lastPlayedByVideoId: Map<String, Long>,
        val playTimeSecondsByVideoId: Map<String, Long>,
    )

    private suspend fun loadEvaluationSnapshot(): EvaluationSnapshot {
        val songs =
            runCatching { songRepository.getAllSongs(MAX_SONGS_TO_EVALUATE).first() }
                .getOrDefault(emptyList())
        val events =
            runCatching { analyticsRepository.getPlaybackEventsByOffset(0, MAX_EVENTS_TO_EVALUATE).first() }
                .getOrDefault(emptyList())

        val tz = TimeZone.currentSystemDefault()
        val lastPlayedMap = HashMap<String, Long>()
        val listenedSecondsMap = HashMap<String, Long>()
        for (ev in events) {
            if (ev.videoId.isBlank()) continue
            val tsMs = runCatching { ev.timestamp.toInstant(tz).toEpochMilliseconds() }.getOrDefault(0L)
            if (tsMs > 0L) {
                val prev = lastPlayedMap[ev.videoId] ?: 0L
                if (tsMs > prev) lastPlayedMap[ev.videoId] = tsMs
            }
            if (ev.listenedSecond > 0L) {
                listenedSecondsMap[ev.videoId] = (listenedSecondsMap[ev.videoId] ?: 0L) + ev.listenedSecond
            }
        }

        // Fill play-time seconds from Room's listen counter when analytics tracking has no events yet
        val effectivePlayTimeMap = HashMap<String, Long>(songs.size)
        for (song in songs) {
            val analyticsSecs = listenedSecondsMap[song.videoId] ?: 0L
            val estimatedSecs =
                if (analyticsSecs > 0L) {
                    analyticsSecs
                } else if (song.totalPlayTime > 0L && song.durationSeconds > 0) {
                    song.totalPlayTime * song.durationSeconds.toLong()
                } else {
                    song.totalPlayTime
                }
            effectivePlayTimeMap[song.videoId] = estimatedSecs
        }

        return EvaluationSnapshot(
            songs = songs,
            lastPlayedByVideoId = lastPlayedMap,
            playTimeSecondsByVideoId = effectivePlayTimeMap,
        )
    }
}
