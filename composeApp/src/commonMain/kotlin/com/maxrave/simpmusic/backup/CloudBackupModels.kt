package com.maxrave.simpmusic.backup

import com.maxrave.domain.data.model.offline.WifiAutoSyncPlaylistRef
import com.maxrave.domain.data.player.DeviceAudioProfile
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class CloudBackupProvider {
    GITHUB_GIST,
    WEBDAV_OR_HTTP,
}

@Serializable
enum class CloudBackupSchedule(
    val intervalMs: Long,
    val label: String,
) {
    MANUAL(0L, "Manual only"),
    DAILY(24L * 60L * 60L * 1000L, "Daily"),
    WEEKLY(7L * 24L * 60L * 60L * 1000L, "Weekly"),
}

@Serializable
data class CloudBackupConfig(
    val enabled: Boolean = false,
    val provider: CloudBackupProvider = CloudBackupProvider.GITHUB_GIST,
    val schedule: CloudBackupSchedule = CloudBackupSchedule.DAILY,
    val wifiOnly: Boolean = true,
    /** Local-only credentials; NEVER included in exported snapshots. */
    val githubToken: String = "",
    val githubGistId: String = "",
    val customEndpointUrl: String = "",
    val customAuthHeader: String = "",
    val lastBackupEpochMs: Long = 0L,
    val lastBackupSummary: String = "",
    val lastError: String? = null,
) {
    val isConfigured: Boolean
        get() =
            when (provider) {
                CloudBackupProvider.GITHUB_GIST -> githubToken.isNotBlank()
                CloudBackupProvider.WEBDAV_OR_HTTP -> customEndpointUrl.isNotBlank()
            }
}

@Serializable
data class CloudPreferencesSnapshot(
    val quality: String = "",
    val downloadQuality: String = "",
    val equalizerEnabled: Boolean = false,
    val equalizerBands: List<Float> = emptyList(),
    val equalizerPreamp: Float = 0f,
    val equalizerAutoEqProfile: String = "",
    val stereoWidenEnabled: Boolean = false,
    val stereoWidenAmount: Float = 0.5f,
    val delayEnabled: Boolean = false,
    val delayTimeMs: Int = 250,
    val delayFeedback: Float = 0.3f,
    val delayMix: Float = 0.25f,
    val reverbEnabled: Boolean = false,
    val reverbPreset: String = "",
    val reverbMix: Float = 0.25f,
    val eightDEnabled: Boolean = false,
    val eightDDirection: String = "",
    val eightDPeriodSeconds: Float = 10f,
    val normalizeVolume: Boolean = false,
    val skipSilent: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDuration: Int = 0,
    val crossfadeDjMode: Boolean = false,
    val crossfadeSkipAlbum: Boolean = false,
    val saveStateOfPlayback: Boolean = true,
    val endlessQueue: Boolean = true,
    val radioAudioOnly: Boolean = false,
    val explicitContentEnabled: Boolean = true,
    val playbackSpeed: Float = 1f,
    val pitch: Int = 0,
    val themeMode: String = DataStoreManager.THEME_MODE_DARK,
    val themeColorSource: String = DataStoreManager.THEME_COLOR_DEFAULT,
    val customThemeColor: String = DataStoreManager.DEFAULT_THEME_COLOR_HEX,
    val nowPlayingStyle: String = DataStoreManager.NOW_PLAYING_STYLE_APPLE_MUSIC,
    val lyricsStyle: String = DataStoreManager.LYRICS_STYLE_APPLE_MUSIC,
    val lyricsProvider: String = DataStoreManager.SIMPMUSIC,
    val lyricsOffsetMs: Int = 0,
    val romanizationLanguages: String = "",
    val enableLiquidGlass: Boolean = true,
    val localTrackingEnabled: Boolean = false,
    val perDeviceAudioProfilesEnabled: Boolean = false,
    val deviceAudioProfiles: List<DeviceAudioProfile> = emptyList(),
    val rollingOfflineCacheEnabled: Boolean = true,
    val rollingOfflineCacheLimitMb: Int = 1024,
    val wifiAutoSyncEnabled: Boolean = true,
    val wifiAutoSyncPlaylists: List<WifiAutoSyncPlaylistRef> = emptyList(),
    val listenTogetherBlocklist: String = "",
)

@Serializable
data class CloudStatSong(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val totalPlayTimeMs: Long = 0L,
    val liked: Boolean = false,
)

@Serializable
data class CloudPlaybackEvent(
    val videoId: String,
    val albumBrowseId: String? = null,
    val durationSecond: Long = 0L,
    val listenedSecond: Long = 0L,
    val timestampIso: String = "",
    val syncId: String? = null,
)

@Serializable
data class CloudLocalPlaylistSummary(
    val title: String,
    val thumbnail: String? = null,
    val trackVideoIds: List<String> = emptyList(),
)

@Serializable
data class CloudListeningStatsSnapshot(
    val totalPlayCount: Long = 0L,
    val totalListeningSeconds: Long = 0L,
    val totalUniqueArtists: Long = 0L,
    val topSongs: List<CloudStatSong> = emptyList(),
    val recentEvents: List<CloudPlaybackEvent> = emptyList(),
    val likedVideoIds: List<String> = emptyList(),
    val localPlaylists: List<CloudLocalPlaylistSummary> = emptyList(),
)

@Serializable
data class CloudBackupSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAtEpochMs: Long,
    val deviceName: String,
    val appVersion: String,
    val preferences: CloudPreferencesSnapshot,
    val listeningStats: CloudListeningStatsSnapshot,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class CloudBackupResult(
    val gistId: String? = null,
    val summary: String,
    val uploadedBytes: Int,
    val skippedReason: String? = null,
)

data class CloudRestoreResult(
    val restoredPreferences: Boolean,
    val restoredEventsCount: Int,
    val restoredLikedCount: Int,
    val restoredPlaylistsCount: Int,
    val snapshotCreatedAtEpochMs: Long,
    val sourceDeviceName: String,
)

object CloudBackupProtocol {
    const val DEFAULT_GIST_FILENAME = "simpmusic-cloud-backup.json"
    const val GIST_DESCRIPTION = "SimpMusic Automated Cloud Backup (Listening Statistics & Preferences)"
    const val KEY_CONFIG = "cloud_backup_config_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }

    fun encodeSnapshot(snapshot: CloudBackupSnapshot): String = json.encodeToString(snapshot)

    fun decodeSnapshot(raw: String): CloudBackupSnapshot? =
        runCatching { json.decodeFromString<CloudBackupSnapshot>(raw) }.getOrNull()

    fun encodeConfig(config: CloudBackupConfig): String = json.encodeToString(config)

    fun decodeConfig(raw: String?): CloudBackupConfig {
        if (raw.isNullOrBlank()) return CloudBackupConfig()
        return runCatching { json.decodeFromString<CloudBackupConfig>(raw) }.getOrDefault(CloudBackupConfig())
    }

    /**
     * Returns true when an automated backup is enabled, configured, has an automatic schedule,
     * and the elapsed time since the last backup meets or exceeds the scheduled interval.
     */
    fun isBackupDue(
        config: CloudBackupConfig,
        nowEpochMs: Long,
    ): Boolean {
        if (!config.enabled || !config.isConfigured) return false
        if (config.schedule == CloudBackupSchedule.MANUAL || config.schedule.intervalMs <= 0L) return false
        if (config.lastBackupEpochMs <= 0L) return true
        return (nowEpochMs - config.lastBackupEpochMs) >= config.schedule.intervalMs
    }

    fun buildGitHubGistRequestBody(
        filename: String,
        content: String,
        description: String = GIST_DESCRIPTION,
        isNewGist: Boolean = true,
    ): String {
        val payload =
            buildJsonObject {
                put("description", description)
                if (isNewGist) {
                    put("public", false)
                }
                put(
                    "files",
                    buildJsonObject {
                        put(
                            filename,
                            buildJsonObject {
                                put("content", content)
                            },
                        )
                    },
                )
            }
        return payload.toString()
    }

    data class ParsedGistResponse(
        val gistId: String?,
        val content: String?,
        val truncated: Boolean,
        val rawUrl: String?,
    )

    fun parseGitHubGistResponse(
        responseBody: String,
        preferredFilename: String = DEFAULT_GIST_FILENAME,
    ): ParsedGistResponse? {
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        val files = root["files"] as? JsonObject
        val targetFile =
            files?.get(preferredFilename) as? JsonObject
                ?: files?.values?.firstOrNull() as? JsonObject
        val content = targetFile?.get("content")?.jsonPrimitive?.contentOrNull
        val truncated = targetFile?.get("truncated")?.jsonPrimitive?.booleanOrNull ?: false
        val rawUrl = targetFile?.get("raw_url")?.jsonPrimitive?.contentOrNull
        return ParsedGistResponse(
            gistId = id,
            content = content,
            truncated = truncated,
            rawUrl = rawUrl,
        )
    }

    fun DataStoreManager.cloudBackupConfigFlow(): Flow<CloudBackupConfig> =
        getString(KEY_CONFIG).map { decodeConfig(it) }

    suspend fun DataStoreManager.readCloudBackupConfig(): CloudBackupConfig =
        decodeConfig(getString(KEY_CONFIG).first())

    suspend fun DataStoreManager.writeCloudBackupConfig(config: CloudBackupConfig) {
        putString(KEY_CONFIG, encodeConfig(config))
    }
}
