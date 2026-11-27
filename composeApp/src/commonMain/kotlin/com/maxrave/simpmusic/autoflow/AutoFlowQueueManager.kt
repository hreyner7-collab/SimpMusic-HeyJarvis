package com.maxrave.simpmusic.autoflow

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.SongRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AutoFlowQueueManager(
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val songRepository: SongRepository,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private suspend fun loadPlayTimes(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                songRepository
                    .getAllSongs(limit = 500)
                    .first()
                    .associate { it.videoId to it.totalPlayTime }
            }.getOrDefault(emptyMap())
        }

    suspend fun previewCurrentQueue(arc: AutoFlowArc): List<TrackEnergyProfile> {
        val queue = mediaPlayerHandler.queueData.value?.data?.listTracks ?: return emptyList()
        if (queue.isEmpty()) return emptyList()
        val currentIdx = mediaPlayerHandler.currentSongIndex().coerceIn(0, queue.lastIndex)
        val upcoming = queue.drop(currentIdx + 1)
        val playTimes = loadPlayTimes()
        return AutoFlowQueueSorter.previewEnergyCurve(
            tracks = upcoming,
            arc = arc,
            playTimeByVideoId = playTimes,
            keepFirstTrackPinned = false,
        )
    }

    suspend fun applyAutoFlowToCurrentQueue(arc: AutoFlowArc): Result<Int> =
        runCatching {
            val queue = mediaPlayerHandler.queueData.value?.data?.listTracks?.toMutableList()
                ?: error("No active queue to sort")
            if (queue.size <= 2) return@runCatching 0

            val currentIdx = mediaPlayerHandler.currentSongIndex().coerceIn(0, queue.lastIndex)
            val startIdx = currentIdx + 1
            if (startIdx >= queue.lastIndex) return@runCatching 0

            val upcoming = queue.subList(startIdx, queue.size).toList()
            val playTimes = loadPlayTimes()
            val sortedUpcoming =
                AutoFlowQueueSorter.sortForArc(
                    tracks = upcoming,
                    arc = arc,
                    playTimeByVideoId = playTimes,
                    keepFirstTrackPinned = false,
                )

            withContext(mainDispatcher) {
                mediaPlayerHandler.setShuffle(false)
                val working = queue.toMutableList()
                for (offset in sortedUpcoming.indices) {
                    val targetPos = startIdx + offset
                    val desired = sortedUpcoming[offset]
                    val currentPos =
                        (targetPos until working.size).firstOrNull { working[it].videoId == desired.videoId }
                            ?: continue
                    if (currentPos != targetPos) {
                        mediaPlayerHandler.swap(currentPos, targetPos)
                        val moved = working.removeAt(currentPos)
                        working.add(targetPos, moved)
                    }
                }
            }
            sortedUpcoming.size
        }

    suspend fun playTracksWithAutoFlow(
        tracks: List<Track>,
        playlistTitle: String,
        arc: AutoFlowArc,
    ): Result<Int> =
        runCatching {
            require(tracks.isNotEmpty()) { "Playlist has no tracks" }
            val playTimes = loadPlayTimes()
            val sorted =
                AutoFlowQueueSorter.sortForArc(
                    tracks = tracks,
                    arc = arc,
                    playTimeByVideoId = playTimes,
                    keepFirstTrackPinned = false,
                )
            withContext(mainDispatcher) {
                mediaPlayerHandler.setShuffle(false)
                mediaPlayerHandler.reset()
                mediaPlayerHandler.setQueueData(
                    QueueData.Data(
                        listTracks = ArrayList(sorted),
                        firstPlayedTrack = sorted.first(),
                        playlistId = "autoflow:${arc.name}",
                        playlistName = "$playlistTitle • ${arc.label}",
                        playlistType = PlaylistType.LOCAL_PLAYLIST,
                        continuation = null,
                    ),
                )
                mediaPlayerHandler.loadMediaItem(
                    sorted.first(),
                    type = com.maxrave.common.Config.PLAYLIST_CLICK,
                    index = 0,
                )
            }
            sorted.size
        }
}
