package com.maxrave.simpmusic.utils

import com.maxrave.simpmusic.expect.platformSynchronized

import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Lightweight in-memory TTL cache for Home, Album, Artist, and Playlist pages so navigating
 * between screens renders immediately without waiting on redundant network round-trips.
 */
object FastPageCache {
    private const val HOME_TTL_MS = 5L * 60L * 1000L
    private const val DETAIL_TTL_MS = 10L * 60L * 1000L
    private const val MAX_ENTRIES = 120
    private val cacheLock = Any()

    private data class Entry<T>(
        val value: T,
        val timestampMs: Long,
    )

    data class HomeSnapshot(
        val homeItems: List<HomeItem>,
        val continuation: String?,
        val homeBackground: String?,
        val mood: Mood?,
        val chart: Chart?,
        val newRelease: List<HomeItem>,
    )

    data class PlaylistSnapshot(
        val browse: PlaylistBrowse,
        val continuation: String?,
        val isRadio: Boolean,
    )

    private val homeCache = LinkedHashMap<String, Entry<HomeSnapshot>>()
    private val albumCache = LinkedHashMap<String, Entry<AlbumBrowse>>()
    private val artistCache = LinkedHashMap<String, Entry<ArtistBrowse>>()
    private val playlistCache = LinkedHashMap<String, Entry<PlaylistSnapshot>>()

    @OptIn(ExperimentalTime::class)
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun <T> getValid(
        map: LinkedHashMap<String, Entry<T>>,
        key: String,
        ttlMs: Long,
    ): T? =
        platformSynchronized(cacheLock) {
            val entry = map[key] ?: return@platformSynchronized null
            if (nowMs() - entry.timestampMs <= ttlMs) {
                // Bump LRU recency
                map.remove(key)
                map[key] = entry
                entry.value
            } else {
                map.remove(key)
                null
            }
        }

    private fun <T> putBounded(
        map: LinkedHashMap<String, Entry<T>>,
        key: String,
        value: T,
    ) = platformSynchronized(cacheLock) {
        map.remove(key)
        if (map.size >= MAX_ENTRIES) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[key] = Entry(value = value, timestampMs = nowMs())
    }

    fun getHome(key: String): HomeSnapshot? = getValid(homeCache, key, HOME_TTL_MS)

    fun putHome(
        key: String,
        snapshot: HomeSnapshot,
    ) = putBounded(homeCache, key, snapshot)

    fun getAlbum(browseId: String): AlbumBrowse? = getValid(albumCache, browseId, DETAIL_TTL_MS)

    fun putAlbum(
        browseId: String,
        browse: AlbumBrowse,
    ) = putBounded(albumCache, browseId, browse)

    fun getArtist(channelId: String): ArtistBrowse? = getValid(artistCache, channelId, DETAIL_TTL_MS)

    fun putArtist(
        channelId: String,
        browse: ArtistBrowse,
    ) = putBounded(artistCache, channelId, browse)

    fun getPlaylist(id: String): PlaylistSnapshot? = getValid(playlistCache, id, DETAIL_TTL_MS)

    fun putPlaylist(
        id: String,
        snapshot: PlaylistSnapshot,
    ) = putBounded(playlistCache, id, snapshot)

    fun invalidatePlaylist(id: String) {
        platformSynchronized(cacheLock) {
            playlistCache.remove(id)
        }
    }

    fun clearHome() =
        platformSynchronized(cacheLock) {
            homeCache.clear()
        }

    fun clearAll() =
        platformSynchronized(cacheLock) {
            homeCache.clear()
            albumCache.clear()
            artistCache.clear()
            playlistCache.clear()
        }
}
