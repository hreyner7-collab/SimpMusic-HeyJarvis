package com.maxrave.simpmusic.notch

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val NOW_NOTCH_SETTINGS_KEY = "now_notch_settings_json"

private val nowNotchJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

@Serializable
enum class NowNotchIdleMode(val label: String) {
    COMPACT_CLOCK("Live Clock"),
    COMPACT_DOT("Compact Dot"),
    HIDDEN("Hidden"),
}

@Serializable
data class NowNotchSettings(
    val enabled: Boolean = false,
    val showLiveLyrics: Boolean = true,
    val showTrackChangePopup: Boolean = true,
    val idleMode: NowNotchIdleMode = NowNotchIdleMode.COMPACT_CLOCK,
    val topOffsetDp: Int = 10,
    val compactSize: Boolean = false,
    val lockScreenLiveLyrics: Boolean = false,
)

data class NowNotchLiveState(
    val settings: NowNotchSettings = NowNotchSettings(),
    val hasTrack: Boolean = false,
    val isPlaying: Boolean = false,
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val currentLyricLine: String? = null,
    val popupBanner: String? = null,
    val progressFraction: Float = 0f,
)

object NowNotchStateHolder {
    private val _state = MutableStateFlow(NowNotchLiveState())
    val state: StateFlow<NowNotchLiveState> = _state.asStateFlow()

    fun update(newState: NowNotchLiveState) {
        _state.value = newState
    }

    /**
     * Resolves the currently active lyric line at [positionMs] adjusted by [offsetMs].
     * Mirrors the player lyric timeline check: returns null when lyrics are unsynced or
     * playback is before the first timestamped line.
     */
    fun resolveSyncedLyricLine(
        lines: List<Line>?,
        syncType: String?,
        positionMs: Long,
        offsetMs: Int,
    ): String? {
        if (lines.isNullOrEmpty() || syncType == null || syncType == "UNSYNCED") return null
        val nowMs = positionMs - offsetMs
        if (nowMs <= 0L) return null
        val firstStart = lines.first().startTimeMs.toLongOrNull() ?: 0L
        if (nowMs < firstStart) return null

        var active: String? = null
        for (i in lines.indices) {
            val startMs = lines[i].startTimeMs.toLongOrNull() ?: 0L
            val endMs =
                if (i < lines.lastIndex) {
                    lines[i + 1].startTimeMs.toLongOrNull() ?: (startMs + 60_000L)
                } else {
                    startMs + 60_000L
                }
            if (nowMs in startMs..endMs) {
                active = lines[i].words.trim().takeIf { it.isNotEmpty() }
            }
        }
        return active
    }
}

fun DataStoreManager.nowNotchSettingsFlow(): Flow<NowNotchSettings> =
    getString(NOW_NOTCH_SETTINGS_KEY)
        .map { raw ->
            if (raw.isNullOrBlank()) {
                NowNotchSettings()
            } else {
                runCatching { nowNotchJson.decodeFromString<NowNotchSettings>(raw) }
                    .getOrDefault(NowNotchSettings())
            }
        }.distinctUntilChanged()

suspend fun DataStoreManager.updateNowNotchSettings(transform: (NowNotchSettings) -> NowNotchSettings) {
    val current = nowNotchSettingsFlow().first()
    val updated = transform(current)
    putString(NOW_NOTCH_SETTINGS_KEY, nowNotchJson.encodeToString(updated))
}

expect object PlatformNowNotchBridge {
    fun sync(state: NowNotchLiveState)

    fun canDrawOverlay(): Boolean

    fun requestOverlayPermission()
}
