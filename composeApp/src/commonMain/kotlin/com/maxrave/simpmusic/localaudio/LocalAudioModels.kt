package com.maxrave.simpmusic.localaudio

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LocalAudioTrack(
    val id: String,
    val uri: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val format: String,
    val dateAddedMs: Long,
    val artworkPath: String? = null,
) {
    val videoId: String
        get() = "$LOCAL_VIDEO_ID_PREFIX$id"

    fun toTrack(): Track {
        val durationSec = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
        val resolvedArtist = artist.ifBlank { UNKNOWN_ARTIST }
        val resolvedThumbnails =
            artworkPath
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(Thumbnail(height = 544, url = it, width = 544)) }
                ?: emptyList()
        return Track(
            album = album.takeIf { it.isNotBlank() }?.let { Album(id = "", name = it) },
            artists = listOf(Artist(id = null, name = resolvedArtist)),
            duration = LocalAudioConfig.formatDuration(durationMs),
            durationSeconds = durationSec,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = resolvedThumbnails,
            title = title.ifBlank { filePath.substringAfterLast('/').substringAfterLast('\\') },
            videoId = videoId,
            videoType = LOCAL_VIDEO_TYPE,
            category = format.uppercase().ifBlank { "AUDIO" },
            feedbackTokens = null,
            resultType = LOCAL_VIDEO_TYPE,
            year = "",
        )
    }

    fun toGenericMediaItem(): GenericMediaItem =
        GenericMediaItem(
            mediaId = videoId,
            uri = uri.ifBlank { filePath },
            metadata =
                GenericMediaMetadata(
                    title = title,
                    artist = artist.ifBlank { UNKNOWN_ARTIST },
                    albumTitle = album.ifBlank { null },
                    artworkUri = artworkPath?.takeIf { it.isNotBlank() },
                    description = MERGING_DATA_TYPE.SONG,
                ),
            customCacheKey = null,
        )

    companion object {
        const val LOCAL_VIDEO_ID_PREFIX = "local:"
        const val LOCAL_VIDEO_TYPE = "LOCAL_AUDIO"
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}

@Serializable
data class LocalAudioLibraryState(
    val watchedFolders: List<String> = emptyList(),
    val tracks: List<LocalAudioTrack> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanAtMs: Long = 0L,
    val lastError: String? = null,
)

object LocalAudioConfig {
    const val DATASTORE_KEY = "local_audio_library_v1"
    const val MAX_SCAN_TRACKS = 10_000

    val SUPPORTED_EXTENSIONS: Set<String> =
        setOf("mp3", "flac", "wav", "opus", "m4a", "ogg", "aac")

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encodeState(state: LocalAudioLibraryState): String =
        json.encodeToString(state.copy(isScanning = false))

    fun decodeState(raw: String?): LocalAudioLibraryState {
        if (raw.isNullOrBlank()) return LocalAudioLibraryState()
        return runCatching {
            json.decodeFromString<LocalAudioLibraryState>(raw).copy(isScanning = false)
        }.getOrDefault(LocalAudioLibraryState())
    }

    /**
     * Deterministic FNV-1a 64-bit hex ID derived from a file path or URI so track IDs stay
     * stable across rescans without relying on JVM-only crypto APIs.
     */
    fun stableTrackId(sourceKey: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
        val prime = 0x100000001b3L
        for (byte in sourceKey.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFFL)
            hash *= prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    fun formatFileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1_073_741_824L -> {
                val gbTenths = (safe * 10L) / 1_073_741_824L
                "${gbTenths / 10}.${gbTenths % 10} GB"
            }
            safe >= 1_048_576L -> {
                val mbTenths = (safe * 10L) / 1_048_576L
                "${mbTenths / 10}.${mbTenths % 10} MB"
            }
            safe >= 1024L -> "${safe / 1024L} KB"
            else -> "$safe B"
        }
    }

    fun normalizeExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isSupportedAudioFile(fileName: String): Boolean =
        normalizeExtension(fileName) in SUPPORTED_EXTENSIONS
}
