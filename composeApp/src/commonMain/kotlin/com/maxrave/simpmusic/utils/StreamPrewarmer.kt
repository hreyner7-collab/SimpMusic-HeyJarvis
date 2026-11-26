package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.getPlaybackBlocklist
import com.maxrave.domain.utils.isLive
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.platformSynchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "StreamPrewarmer"

/**
 * Speculatively resolves stream URLs for the top track(s) of a screen the user just opened
 * (Playlist, Album, Local Playlist, or Search results) into [StreamRepository]'s in-memory URL
 * cache so tapping Play starts near-instantly.
 *
 * Bounded by concurrency ([maxConcurrent]), per-track cooldown ([cooldown]), and cancellation:
 *  - Skips live streams, blank IDs, blocked tracks, and tracks already downloaded offline.
 *  - Shares [StreamRepository]'s single-flight map so tapping Play mid-prewarm joins the in-flight
 *    request rather than issuing a duplicate network call.
 */
class StreamPrewarmer(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrent: Int = 2,
    private val cooldown: Duration = 120.seconds,
    private val resolveTimeoutMs: Long = 12_000L,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
    private val lock = Any()
    private val recentMarks = LinkedHashMap<String, TimeMark>(64)

    fun prewarmTracks(
        tracks: List<Track>,
        maxTracks: Int = 2,
    ): List<Job> {
        if (tracks.isEmpty() || maxTracks <= 0) return emptyList()
        val candidates =
            tracks
                .asSequence()
                .filter { it.videoId.isNotBlank() && !it.isLive }
                .distinctBy { it.videoId }
                .take(maxTracks)
                .toList()
        return prewarmVideoIds(candidates.map { it.videoId }, candidates, maxTracks = maxTracks)
    }

    fun prewarmVideoIds(
        videoIds: List<String>,
        tracksForBlocklist: List<Track> = emptyList(),
        maxTracks: Int = 2,
    ): List<Job> {
        if (videoIds.isEmpty() || maxTracks <= 0) return emptyList()
        val byId = tracksForBlocklist.associateBy { it.videoId }
        val selected =
            videoIds
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .take(maxTracks)
                .filter { claimSlot(it) }
                .toList()
        if (selected.isEmpty()) return emptyList()

        return selected.map { videoId ->
            scope.launch {
                semaphore.withPermit {
                    try {
                        val blocklist = runCatching { dataStoreManager.getPlaybackBlocklist() }.getOrNull()
                        val track = byId[videoId]
                        if (blocklist != null && track != null && blocklist.isTrackBlocked(track)) {
                            return@withPermit
                        }
                        val localSong =
                            songRepository?.let { repo ->
                                runCatching { repo.getSongById(videoId).firstOrNull() }.getOrNull()
                            }
                        if (localSong?.downloadState == DownloadState.STATE_DOWNLOADED) {
                            return@withPermit
                        }
                        if (localSong?.isLive == true) {
                            return@withPermit
                        }
                        withTimeoutOrNull(resolveTimeoutMs) {
                            streamRepository
                                .getStream(
                                    dataStoreManager = dataStoreManager,
                                    videoId = videoId,
                                    isDownloading = false,
                                    isVideo = false,
                                ).lastOrNull()
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Logger.d(TAG, "Prewarm skipped for $videoId: ${e.message}")
                    }
                }
            }
        }
    }

    internal fun clearCooldowns() {
        platformSynchronized(lock) {
            recentMarks.clear()
        }
    }

    private fun claimSlot(videoId: String): Boolean =
        platformSynchronized(lock) {
            val prev = recentMarks[videoId]
            if (prev != null && prev.elapsedNow() < cooldown) {
                return@platformSynchronized false
            }
            if (recentMarks.size >= 160) {
                val oldest = recentMarks.keys.firstOrNull()
                if (oldest != null) recentMarks.remove(oldest)
            }
            recentMarks[videoId] = timeSource.markNow()
            true
        }
}
