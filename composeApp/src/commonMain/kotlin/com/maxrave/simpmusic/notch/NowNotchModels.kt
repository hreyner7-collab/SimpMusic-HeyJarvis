package com.maxrave.simpmusic.notch

import androidx.compose.ui.graphics.ImageBitmap
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.expect.ui.toReadableBitmap
import com.maxrave.simpmusic.ui.component.TimedLineIndex
import com.maxrave.simpmusic.ui.component.activeIndexAt
import com.maxrave.simpmusic.ui.component.buildDisplayLines
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val NOTCH_RICH_SYNC_TIMESTAMP_REGEX = Regex("""<\d{2}:\d{2}\.\d{2,3}>\s*""")
private val NOTCH_WHITESPACE_REGEX = Regex("""\s+""")

fun cleanNotchLyricText(raw: String): String =
    raw
        .replace(NOTCH_RICH_SYNC_TIMESTAMP_REGEX, " ")
        .replace(NOTCH_WHITESPACE_REGEX, " ")
        .trim()

private const val NOW_NOTCH_SETTINGS_KEY = "now_notch_settings_json"
const val NOOKI_VISUALIZER_BLUE_ARGB: Long = 0xFF52B8FFL
const val NOOKI_FALLBACK_PRIMARY_ARGB: Long = 0xFF2B2E48L
const val NOOKI_FALLBACK_SECONDARY_ARGB: Long = 0xFF191B2CL

private val nowNotchJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

@Serializable
enum class NowNotchIdleMode(
    val label: String,
) {
    COMPACT_CLOCK("Live Clock"),
    COMPACT_DOT("Compact Dot"),
    HIDDEN("Hidden"),
}

@Serializable
data class NowNotchSettings(
    val enabled: Boolean = false,
    val showLiveLyrics: Boolean = true,
    val showTrackChangePopup: Boolean = true,
    val showSideWaves: Boolean = true,
    val idleMode: NowNotchIdleMode = NowNotchIdleMode.COMPACT_CLOCK,
    val topOffsetDp: Int = 4,
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
    val thumbnailUrl: String? = null,
    val artworkBitmap: ImageBitmap? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyricsLines: List<Line> = emptyList(),
    val lyricsSyncType: String? = null,
    val lyricsOffsetMs: Int = 0,
    val currentLyricLine: String? = null,
    val nextLyricLine: String? = null,
    val popupBanner: String? = null,
    val progressFraction: Float = 0f,
    val isLiked: Boolean = false,
    val accentColorArgb: Long = NOOKI_VISUALIZER_BLUE_ARGB,
    val secondaryColorArgb: Long = NOOKI_FALLBACK_SECONDARY_ARGB,
)

object NowNotchStateHolder {
    private val _state = MutableStateFlow(NowNotchLiveState())
    val state: StateFlow<NowNotchLiveState> = _state.asStateFlow()

    @kotlin.concurrent.Volatile
    var isOverlayServiceRunning: Boolean = false

    fun update(newState: NowNotchLiveState) {
        _state.value = newState
    }

    /**
     * Resolves the active 0-based index in the canonical display list at [positionMs] adjusted by
     * [offsetMs], or `-1` when unsynced or before the first timestamped line.
     */
    fun resolveSyncedLyricIndex(
        lines: List<Line>?,
        syncType: String?,
        positionMs: Long,
        offsetMs: Int,
    ): Int {
        val (_, ordered) = buildNotchTimedDisplayLines(lines, syncType)
        if (ordered.isEmpty()) return -1
        val nowMs = positionMs - offsetMs
        if (nowMs < 0L) return -1
        return ordered.activeIndexAt(nowMs)
    }

    /**
     * Resolves the currently active lyric line at [positionMs] adjusted by [offsetMs].
     */
    fun resolveSyncedLyricLine(
        lines: List<Line>?,
        syncType: String?,
        positionMs: Long,
        offsetMs: Int,
    ): String? {
        val (displayLines, ordered) = buildNotchTimedDisplayLines(lines, syncType)
        if (ordered.isEmpty()) return null
        val nowMs = positionMs - offsetMs
        if (nowMs < 0L) return null
        val idx = ordered.activeIndexAt(nowMs)
        if (idx < 0) return null
        val words = displayLines.getOrNull(idx)?.words ?: return null
        return cleanNotchLyricText(words).takeIf { it.isNotEmpty() }
    }

    /**
     * Resolves the next non-empty upcoming lyric line for lock-screen / Now Bar preview.
     */
    fun resolveNextSyncedLyricLine(
        lines: List<Line>?,
        syncType: String?,
        positionMs: Long,
        offsetMs: Int,
    ): String? {
        val (displayLines, ordered) = buildNotchTimedDisplayLines(lines, syncType)
        if (ordered.isEmpty()) return null
        val nowMs = positionMs - offsetMs
        val idx = if (nowMs < 0L) -1 else ordered.activeIndexAt(nowMs)
        val startSearch = if (idx >= 0) idx + 1 else 0
        for (i in startSearch until displayLines.size) {
            val cleaned = cleanNotchLyricText(displayLines[i].words)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }
}

internal fun buildNotchTimedDisplayLines(
    lines: List<Line>?,
    syncType: String?,
): Pair<List<Line>, List<TimedLineIndex>> {
    val source = lines.orEmpty()
    if (source.isEmpty() || syncType == "UNSYNCED") return source to emptyList()
    val display = buildDisplayLines(source, syncType)
    val timed =
        display.lines.mapIndexedNotNull { index, line ->
            line.startTimeMs.toLongOrNull()?.let { TimedLineIndex(index, it) }
        }
    val ordered =
        if (timed.distinctBy { it.startTimeMs }.size <= 1) {
            emptyList()
        } else {
            timed.sortedBy { it.startTimeMs }
        }
    return display.lines to ordered
}

/**
 * Mirrors Nooki's `MusicVisualizerKt.extractPaletteColors` across Android & Desktop:
 * downsamples artwork to 24x24, scores vibrant saturated pixels `(s * 1.8f) + (v * 0.9f)`,
 * picks primary + hue-distinct (`> 25°`) secondary jewel tones, and safely falls back on error.
 */
fun extractNookiPaletteColors(bitmap: ImageBitmap?): Pair<Long, Long> {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        return NOOKI_VISUALIZER_BLUE_ARGB to NOOKI_FALLBACK_SECONDARY_ARGB
    }
    return runCatching {
        val readable = bitmap.toReadableBitmap(24, 24)
        val pixels = IntArray(24 * 24)
        readable.readPixels(pixels)

        var bestScore = -1f
        var primaryPixel = 0
        var secondScore = -1f
        var secondaryPixel = 0
        val hsvPrimary = FloatArray(3)
        val hsv = FloatArray(3)

        for (px in pixels) {
            val alpha = (px ushr 24) and 0xFF
            if (alpha < 128) continue
            argbToHsv(px, hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (v >= 0.16f && (v <= 0.94f || s >= 0.10f) && s >= 0.14f) {
                val score = (s * 1.8f) + (v * 0.9f)
                if (score > bestScore) {
                    if (primaryPixel != 0) {
                        argbToHsv(primaryPixel, hsvPrimary)
                        if (abs(hsv[0] - hsvPrimary[0]) > 25f) {
                            secondScore = bestScore
                            secondaryPixel = primaryPixel
                        }
                    }
                    bestScore = score
                    primaryPixel = px
                } else if (score > secondScore && primaryPixel != 0) {
                    argbToHsv(primaryPixel, hsvPrimary)
                    if (abs(hsv[0] - hsvPrimary[0]) > 25f) {
                        secondScore = score
                        secondaryPixel = px
                    }
                }
            }
        }

        if (primaryPixel == 0) {
            NOOKI_VISUALIZER_BLUE_ARGB to NOOKI_FALLBACK_SECONDARY_ARGB
        } else {
            argbToHsv(primaryPixel, hsv)
            val p1 = hsvToArgb(hsv[0], hsv[1].coerceIn(0.55f, 0.95f), hsv[2].coerceIn(0.65f, 0.95f))
            val p2 =
                if (secondaryPixel != 0) {
                    argbToHsv(secondaryPixel, hsv)
                    hsvToArgb(hsv[0], hsv[1].coerceIn(0.45f, 0.85f), hsv[2].coerceIn(0.55f, 0.85f))
                } else {
                    hsvToArgb((hsv[0] + 50f) % 360f, (hsv[1] * 0.85f).coerceIn(0.40f, 0.80f), hsv[2].coerceIn(0.55f, 0.85f))
                }
            p1 to p2
        }
    }.getOrDefault(NOOKI_VISUALIZER_BLUE_ARGB to NOOKI_FALLBACK_SECONDARY_ARGB)
}

private fun argbToHsv(
    argb: Int,
    out: FloatArray,
) {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxVal = max(r, max(g, b))
    val minVal = min(r, min(g, b))
    val delta = maxVal - minVal
    var h = 0f
    if (delta > 1e-6f) {
        h =
            when (maxVal) {
                r -> ((g - b) / delta) % 6f
                g -> ((b - r) / delta) + 2f
                else -> ((r - g) / delta) + 4f
            } * 60f
        if (h < 0f) h += 360f
    }
    val s = if (maxVal > 1e-6f) delta / maxVal else 0f
    out[0] = h
    out[1] = s
    out[2] = maxVal
}

internal fun hsvToArgb(
    hue: Float,
    saturation: Float,
    value: Float,
): Long {
    val c = value * saturation
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = value - c
    val (r1, g1, b1) =
        when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    val r = (((r1 + m) * 255f).takeIf { it.isFinite() } ?: 0f).roundToInt().coerceIn(0, 255)
    val g = (((g1 + m) * 255f).takeIf { it.isFinite() } ?: 0f).roundToInt().coerceIn(0, 255)
    val b = (((b1 + m) * 255f).takeIf { it.isFinite() } ?: 0f).roundToInt().coerceIn(0, 255)
    return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
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

    fun canPostPromotedNotifications(): Boolean

    fun openLiveNotificationsSettings()

    fun resetDesktopPosition()
}
