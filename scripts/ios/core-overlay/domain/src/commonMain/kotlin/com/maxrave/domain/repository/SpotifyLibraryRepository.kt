package com.maxrave.domain.repository

import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

enum class SpotifyLibraryKind {
    LIKED_SONGS,
    PLAYLIST,
    ALBUM,
    TRACK,
}

data class SpotifyLibraryItem(
    val id: String,
    val kind: SpotifyLibraryKind,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
    val localPlaylistId: Long? = null,
    val lastSyncedAtMs: Long? = null,
)

data class SpotifyImportProgress(
    val total: Int,
    val processed: Int,
    val matched: Int,
    val failed: Int,
    val currentTitle: String = "",
    val targetLocalPlaylistId: Long? = null,
    val playlistTitle: String = "",
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
)

interface SpotifyLibraryRepository {
    fun isSpotifyConnected(): Flow<Boolean>

    suspend fun getUserSpotifyLibrary(forceRefresh: Boolean = false): Resource<List<SpotifyLibraryItem>>

    suspend fun refreshLocalSyncStatus(items: List<SpotifyLibraryItem>): List<SpotifyLibraryItem>

    fun importSpotifyItem(item: SpotifyLibraryItem): Flow<SpotifyImportProgress>

    fun importFromLink(url: String): Flow<SpotifyImportProgress>

    fun syncAllSpotifyLibrary(): Flow<SpotifyImportProgress>
}
