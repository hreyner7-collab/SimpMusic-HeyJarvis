package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.model.offline.AutoSyncPlaylistKind
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.getWifiAutoSyncPlaylists
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.wifiAutoSyncEnabledFlow
import com.maxrave.domain.data.model.offline.WifiAutoSyncPlaylistRef
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.isLive
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.isOnUnmeteredNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

private const val TAG = "WifiPlaylistAutoSync"

/**
 * Automatically downloads missing tracks from playlists marked for Wi-Fi Auto-Sync whenever the
 * device is on an unmetered network (Wi-Fi / Ethernet). Never touches cellular data.
 */
class WifiPlaylistAutoSyncManager(
    private val dataStoreManager: DataStoreManager,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
    private val downloadHandler: DownloadHandler,
    private val unmeteredCheck: () -> Boolean = { isOnUnmeteredNetwork() },
) {
    data class SyncResult(
        val skippedReason: String? = null,
        val playlistsChecked: Int = 0,
        val tracksQueued: Int = 0,
    )

    suspend fun syncEnabledPlaylistsNow(): SyncResult {
        val enabled = runCatching { dataStoreManager.wifiAutoSyncEnabledFlow().first() }.getOrDefault(true)
        if (!enabled) return SyncResult(skippedReason = "disabled")
        if (!unmeteredCheck()) return SyncResult(skippedReason = "metered_network")

        val playlists = runCatching { dataStoreManager.getWifiAutoSyncPlaylists() }.getOrDefault(emptyList())
        if (playlists.isEmpty()) return SyncResult(playlistsChecked = 0, tracksQueued = 0)

        var queued = 0
        for (ref in playlists) {
            queued += syncSinglePlaylistInternal(ref)
        }
        Logger.i(TAG, "Wi-Fi auto-sync checked ${playlists.size} playlist(s), queued $queued track(s)")
        return SyncResult(playlistsChecked = playlists.size, tracksQueued = queued)
    }

    suspend fun syncSinglePlaylistIfOnWifi(ref: WifiAutoSyncPlaylistRef): SyncResult {
        val enabled = runCatching { dataStoreManager.wifiAutoSyncEnabledFlow().first() }.getOrDefault(true)
        if (!enabled) return SyncResult(skippedReason = "disabled")
        if (!unmeteredCheck()) return SyncResult(skippedReason = "metered_network")
        val queued = syncSinglePlaylistInternal(ref)
        return SyncResult(playlistsChecked = 1, tracksQueued = queued)
    }

    private suspend fun syncSinglePlaylistInternal(ref: WifiAutoSyncPlaylistRef): Int {
        var queued = 0
        try {
            val videoIds: List<String> =
                when (ref.kind) {
                    AutoSyncPlaylistKind.LOCAL_PLAYLIST -> {
                        val longId = ref.id.toLongOrNull() ?: return 0
                        localPlaylistRepository.getFullPlaylistTracks(longId).map { it.videoId }
                    }

                    AutoSyncPlaylistKind.YOUTUBE_PLAYLIST -> {
                        playlistRepository.getPlaylist(ref.id).firstOrNull()?.tracks.orEmpty()
                    }
                }.distinct().filter { it.isNotBlank() }

            if (videoIds.isEmpty()) return 0
            val songs = songRepository.getSongsByListVideoId(videoIds).firstOrNull().orEmpty()
            for (song in songs) {
                if (song.isLive) continue
                if (song.downloadState == DownloadState.STATE_DOWNLOADED ||
                    song.downloadState == DownloadState.STATE_DOWNLOADING ||
                    song.downloadState == DownloadState.STATE_PREPARING
                ) {
                    continue
                }
                songRepository.updateDownloadState(song.videoId, DownloadState.STATE_PREPARING)
                downloadHandler.downloadTrack(
                    videoId = song.videoId,
                    title = song.title,
                    thumbnail = song.thumbnails ?: "",
                )
                queued++
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Logger.w(TAG, "Auto-sync failed for ${ref.title}: ${e.message}")
        }
        return queued
    }
}
