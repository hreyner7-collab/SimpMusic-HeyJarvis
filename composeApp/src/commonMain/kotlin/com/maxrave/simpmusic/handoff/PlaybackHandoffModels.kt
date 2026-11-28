package com.maxrave.simpmusic.handoff

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RemoteSpeakerOutput(
    val id: String,
    val name: String,
    val isActive: Boolean = false,
    val isSecondary: Boolean = false,
    val delayMs: Int = 0,
)

@Serializable
data class RemoteSpeakerCommand(
    val action: String,
    val id: String,
    val delayMs: Int = 0,
)

@Serializable
data class HandoffBeacon(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val httpPort: Int,
    val nowPlayingTitle: String = "",
    val nowPlayingArtist: String = "",
    val isPlaying: Boolean = false,
    val activeRoomCode: String? = null,
    val wholeHomeGroupId: String? = null,
    val speakers: List<RemoteSpeakerOutput> = emptyList(),
)

@Serializable
data class HandoffPeer(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val host: String,
    val httpPort: Int,
    val nowPlayingTitle: String = "",
    val nowPlayingArtist: String = "",
    val isPlaying: Boolean = false,
    val lastSeenMs: Long = 0L,
    val activeRoomCode: String? = null,
    val wholeHomeGroupId: String? = null,
    val speakers: List<RemoteSpeakerOutput> = emptyList(),
)

@Serializable
data class HandoffTrack(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albumName: String? = null,
    val durationSeconds: Int = 0,
    val thumbnailUrl: String? = null,
) {
    fun toTrack(): Track =
        Track(
            album = albumName?.takeIf { it.isNotBlank() }?.let { Album(id = "", name = it) },
            artists = artists.map { Artist(id = null, name = it) },
            duration = formatDuration(durationSeconds),
            durationSeconds = durationSeconds,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails =
                thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                    listOf(Thumbnail(height = 544, url = it, width = 544))
                } ?: emptyList(),
            title = title,
            videoId = videoId,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            feedbackTokens = null,
            resultType = "Song",
        )

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}

fun Track.toHandoffTrack(): HandoffTrack =
    HandoffTrack(
        videoId = videoId,
        title = title,
        artists = artists.orEmpty().map { it.name }.filter { it.isNotBlank() },
        albumName = album?.name,
        durationSeconds = durationSeconds ?: 0,
        thumbnailUrl = thumbnails?.lastOrNull()?.url,
    )

@Serializable
data class HandoffPlaybackSnapshot(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val tracks: List<HandoffTrack>,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = true,
    val isShuffle: Boolean = false,
    val repeatMode: Int = 0,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val playlistTypeName: String? = null,
)

object PlaybackHandoffWire {
    const val UDP_DISCOVERY_PORT = 49877
    const val DEFAULT_HTTP_PORT = 49878
    const val DISCOVER_PING = "SIMPMUSIC_HANDOFF_PING_V1"
    const val BEACON_PREFIX = "SIMPMUSIC_HANDOFF_BEACON_V1:"
    const val REMOTE_SPEAKER_PATH = "/remote-speaker"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encodeBeacon(beacon: HandoffBeacon): String = BEACON_PREFIX + json.encodeToString(beacon)

    fun decodeBeacon(raw: String): HandoffBeacon? {
        if (!raw.startsWith(BEACON_PREFIX)) return null
        return runCatching {
            json.decodeFromString<HandoffBeacon>(raw.removePrefix(BEACON_PREFIX))
        }.getOrNull()
    }

    fun encodeSnapshot(snapshot: HandoffPlaybackSnapshot): String = json.encodeToString(snapshot)

    fun decodeSnapshot(raw: String): HandoffPlaybackSnapshot? =
        runCatching { json.decodeFromString<HandoffPlaybackSnapshot>(raw) }.getOrNull()
}

object PlaybackHandoffSettings {
    const val KEY_ENABLED = "playback_handoff_enabled_v1"
    const val KEY_DEVICE_NAME = "playback_handoff_device_name_v1"

    fun DataStoreManager.playbackHandoffEnabledFlow(): Flow<Boolean> =
        getString(KEY_ENABLED).map { it != FALSE }

    suspend fun DataStoreManager.setPlaybackHandoffEnabled(enabled: Boolean) {
        putString(KEY_ENABLED, if (enabled) TRUE else FALSE)
    }

    fun DataStoreManager.playbackHandoffDeviceNameFlow(): Flow<String> =
        getString(KEY_DEVICE_NAME).map { it.orEmpty() }

    suspend fun DataStoreManager.setPlaybackHandoffDeviceName(name: String) {
        putString(KEY_DEVICE_NAME, name.trim())
    }

    suspend fun DataStoreManager.effectiveHandoffDeviceName(defaultName: String): String =
        getString(KEY_DEVICE_NAME).first()?.takeIf { it.isNotBlank() } ?: defaultName
}
