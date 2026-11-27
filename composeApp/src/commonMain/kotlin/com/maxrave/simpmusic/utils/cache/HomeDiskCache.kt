package com.maxrave.simpmusic.utils.cache

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.simpmusic.utils.FastPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val HOME_DISK_CACHE_FILENAME = "home_snapshot_v1.json"

@Serializable
data class CachedHomeEnvelopeDto(
    val version: Int = HomeDiskCache.SCHEMA_VERSION,
    val cacheKey: String,
    val savedAtEpochMs: Long,
    val continuation: String? = null,
    val homeBackground: String? = null,
    val homeItems: List<CachedHomeItemDto> = emptyList(),
    val newRelease: List<CachedHomeItemDto> = emptyList(),
)

@Serializable
data class CachedHomeItemDto(
    val title: String,
    val subtitle: String? = null,
    val thumbnail: List<CachedThumbnailDto>? = null,
    val channelId: String? = null,
    val moreEndpoint: CachedMoreEndpointDto? = null,
    val itemsPerColumn: Int? = null,
    val playAllEndpoint: CachedWatchEndpointDto? = null,
    val contents: List<CachedContentDto?> = emptyList(),
)

@Serializable
data class CachedMoreEndpointDto(
    val browseId: String,
    val params: String? = null,
    val pageType: String? = null,
)

@Serializable
data class CachedWatchEndpointDto(
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val params: String? = null,
    val index: Int? = null,
    val musicVideoType: String? = null,
)

@Serializable
data class CachedContentDto(
    val album: CachedAlbumDto? = null,
    val artists: List<CachedArtistDto>? = null,
    val description: String? = null,
    val isExplicit: Boolean? = null,
    val playlistId: String? = null,
    val browseId: String? = null,
    val thumbnails: List<CachedThumbnailDto> = emptyList(),
    val title: String,
    val videoId: String? = null,
    val views: String? = null,
    val durationSeconds: Int? = null,
    val radio: String? = null,
    val videoType: String? = null,
    val isLive: Boolean = false,
    val playEndpoint: CachedWatchEndpointDto? = null,
)

@Serializable
data class CachedThumbnailDto(
    val height: Int,
    val url: String,
    val width: Int,
)

@Serializable
data class CachedAlbumDto(
    val id: String,
    val name: String,
)

@Serializable
data class CachedArtistDto(
    val id: String? = null,
    val name: String,
)

/**
 * Persistent JSON disk cache for the Home screen snapshot with 24-hour stale-while-revalidate TTL.
 *
 * Allows cold starts to render cached Home shelves immediately off disk while a silent live
 * network refresh runs in the background.
 */
object HomeDiskCache {
    const val SCHEMA_VERSION: Int = 1
    const val HOME_DISK_TTL_MS: Long = 24L * 60L * 60L * 1000L
    const val HOME_DISK_FRESH_MS: Long = 5L * 60L * 1000L

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    data class ReadResult(
        val snapshot: FastPageCache.HomeSnapshot,
        val savedAtEpochMs: Long,
        val isFresh: Boolean,
    )

    @OptIn(ExperimentalTime::class)
    internal fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun encode(
        cacheKey: String,
        snapshot: FastPageCache.HomeSnapshot,
        savedAtEpochMs: Long = currentTimeMs(),
    ): String {
        val envelope =
            CachedHomeEnvelopeDto(
                version = SCHEMA_VERSION,
                cacheKey = cacheKey,
                savedAtEpochMs = savedAtEpochMs,
                continuation = snapshot.continuation,
                homeBackground = snapshot.homeBackground,
                homeItems = snapshot.homeItems.map { it.toDto() },
                newRelease = snapshot.newRelease.map { it.toDto() },
            )
        return json.encodeToString(envelope)
    }

    fun decode(
        rawJson: String,
        expectedCacheKey: String,
        nowMs: Long = currentTimeMs(),
        maxTtlMs: Long = HOME_DISK_TTL_MS,
        freshWindowMs: Long = HOME_DISK_FRESH_MS,
    ): ReadResult? {
        if (rawJson.isBlank()) return null
        val envelope =
            runCatching {
                json.decodeFromString<CachedHomeEnvelopeDto>(rawJson)
            }.getOrNull() ?: return null

        if (envelope.version != SCHEMA_VERSION) return null
        if (envelope.cacheKey != expectedCacheKey) return null
        if (envelope.homeItems.isEmpty()) return null
        if (envelope.savedAtEpochMs <= 0L) return null

        val ageMs = nowMs - envelope.savedAtEpochMs
        if (ageMs < 0L || ageMs > maxTtlMs) return null

        val snapshot =
            FastPageCache.HomeSnapshot(
                homeItems = envelope.homeItems.map { it.toDomain() },
                continuation = envelope.continuation,
                homeBackground = envelope.homeBackground,
                mood = null,
                chart = null,
                newRelease = envelope.newRelease.map { it.toDomain() },
            )
        return ReadResult(
            snapshot = snapshot,
            savedAtEpochMs = envelope.savedAtEpochMs,
            isFresh = ageMs <= freshWindowMs,
        )
    }

    suspend fun load(cacheKey: String): ReadResult? =
        withContext(Dispatchers.IO) {
            val raw = readHomeDiskCacheText() ?: return@withContext null
            decode(rawJson = raw, expectedCacheKey = cacheKey)
        }

    suspend fun save(
        cacheKey: String,
        snapshot: FastPageCache.HomeSnapshot,
    ) = withContext(Dispatchers.IO) {
        if (snapshot.homeItems.isEmpty()) return@withContext
        val encoded = encode(cacheKey = cacheKey, snapshot = snapshot)
        writeHomeDiskCacheText(encoded)
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            clearHomeDiskCacheFile()
        }
}

private fun HomeItem.toDto(): CachedHomeItemDto =
    CachedHomeItemDto(
        title = title,
        subtitle = subtitle,
        thumbnail = thumbnail?.map { it.toDto() },
        channelId = channelId,
        moreEndpoint = moreEndpoint?.let { CachedMoreEndpointDto(it.browseId, it.params, it.pageType) },
        itemsPerColumn = itemsPerColumn,
        playAllEndpoint = playAllEndpoint?.toDto(),
        contents = contents.map { it?.toDto() },
    )

private fun CachedHomeItemDto.toDomain(): HomeItem =
    HomeItem(
        contents = contents.map { it?.toDomain() },
        title = title,
        subtitle = subtitle,
        thumbnail = thumbnail?.map { it.toDomain() },
        channelId = channelId,
        moreEndpoint = moreEndpoint?.let { HomeItem.MoreEndpoint(it.browseId, it.params, it.pageType) },
        itemsPerColumn = itemsPerColumn,
        playAllEndpoint = playAllEndpoint?.toDomain(),
    )

private fun Content.toDto(): CachedContentDto =
    CachedContentDto(
        album = album?.let { CachedAlbumDto(it.id, it.name) },
        artists = artists?.map { CachedArtistDto(it.id, it.name) },
        description = description,
        isExplicit = isExplicit,
        playlistId = playlistId,
        browseId = browseId,
        thumbnails = thumbnails.map { it.toDto() },
        title = title,
        videoId = videoId,
        views = views,
        durationSeconds = durationSeconds,
        radio = radio,
        videoType = videoType,
        isLive = isLive,
        playEndpoint = playEndpoint?.toDto(),
    )

private fun CachedContentDto.toDomain(): Content =
    Content(
        album = album?.let { Album(it.id, it.name) },
        artists = artists?.map { Artist(it.id, it.name) },
        description = description,
        isExplicit = isExplicit,
        playlistId = playlistId,
        browseId = browseId,
        thumbnails = thumbnails.map { it.toDomain() },
        title = title,
        videoId = videoId,
        views = views,
        durationSeconds = durationSeconds,
        radio = radio,
        videoType = videoType,
        isLive = isLive,
        playEndpoint = playEndpoint?.toDomain(),
    )

private fun Thumbnail.toDto(): CachedThumbnailDto =
    CachedThumbnailDto(
        height = height,
        url = url,
        width = width,
    )

private fun CachedThumbnailDto.toDomain(): Thumbnail =
    Thumbnail(
        height = height,
        url = url,
        width = width,
    )

private fun YouTubeWatchEndpoint.toDto(): CachedWatchEndpointDto =
    CachedWatchEndpointDto(
        videoId = videoId,
        playlistId = playlistId,
        playlistSetVideoId = playlistSetVideoId,
        params = params,
        index = index,
        musicVideoType = watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType,
    )

private fun CachedWatchEndpointDto.toDomain(): YouTubeWatchEndpoint =
    YouTubeWatchEndpoint(
        videoId = videoId,
        playlistId = playlistId,
        playlistSetVideoId = playlistSetVideoId,
        params = params,
        index = index,
        watchEndpointMusicSupportedConfigs =
            musicVideoType?.let { type ->
                YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    watchEndpointMusicConfig =
                        YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(
                            musicVideoType = type,
                        ),
                )
            },
    )

internal expect fun readHomeDiskCacheText(): String?

internal expect fun writeHomeDiskCacheText(content: String)

internal expect fun clearHomeDiskCacheFile()
