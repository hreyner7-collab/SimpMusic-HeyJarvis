package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.player.KaraokeEffect
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.AudioVisualizerBus
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime

/** Visual style for the real-time 24-band live audio visualizer. */
@Serializable
enum class VisualizerStyle {
    OFF,
    BARS,
    WAVE,
    GLOW,
    ;

    fun displayLabel(): String =
        when (this) {
            OFF -> "Off"
            BARS -> "Bars"
            WAVE -> "Wave"
            GLOW -> "Glow"
        }

    companion object {
        const val DATASTORE_KEY: String = "visualizer_style_v1"

        fun fromStored(raw: String?): VisualizerStyle {
            if (raw.isNullOrBlank()) return OFF
            val cleaned = raw.trim().trim('"').uppercase()
            return entries.firstOrNull { it.name == cleaned } ?: OFF
        }
    }
}

/**
 * Persistence and playback-sync helper for Karaoke Mode and Live Audio Visualizer settings.
 *
 * Uses [DataStoreManager.getString] / [DataStoreManager.putString] so integrator screens
 * (`AudioEffectsSection`, `NowPlayingScreen`, `FullscreenPlayer`) can share state without extra
 * DataStore schema keys.
 */
@OptIn(ExperimentalTime::class)
object KaraokeAndVisualizerPrefs {
    const val KARAOKE_KEY: String = KaraokeEffect.DATASTORE_KEY
    const val VISUALIZER_STYLE_KEY: String = VisualizerStyle.DATASTORE_KEY

    fun observeKaraokeEffect(dataStoreManager: DataStoreManager): Flow<KaraokeEffect> =
        dataStoreManager
            .getString(KARAOKE_KEY)
            .map { KaraokeEffect.decodeFromJson(it) }
            .distinctUntilChanged()

    fun observeVisualizerStyle(dataStoreManager: DataStoreManager): Flow<VisualizerStyle> =
        dataStoreManager
            .getString(VISUALIZER_STYLE_KEY)
            .map { VisualizerStyle.fromStored(it) }
            .distinctUntilChanged()

    suspend fun setKaraokeEffect(
        dataStoreManager: DataStoreManager,
        mediaPlayerHandler: MediaPlayerHandler,
        effect: KaraokeEffect,
    ) {
        val clamped = effect.clamped()
        mediaPlayerHandler.setKaraokeEffect(clamped)
        dataStoreManager.putString(KARAOKE_KEY, clamped.encodeToJson())
    }

    suspend fun setVisualizerStyle(
        dataStoreManager: DataStoreManager,
        style: VisualizerStyle,
    ) {
        dataStoreManager.putString(VISUALIZER_STYLE_KEY, style.name)
    }
}

/** Convenience hook for player screens (`NowPlayingScreen`, `FullscreenPlayer`) to observe visualizer style. */
@Composable
fun rememberVisualizerStyle(dataStoreManager: DataStoreManager): State<VisualizerStyle> {
    val flow = remember(dataStoreManager) { KaraokeAndVisualizerPrefs.observeVisualizerStyle(dataStoreManager) }
    return flow.collectAsStateWithLifecycle(initialValue = VisualizerStyle.OFF)
}

private val IDLE_SPECTRUM = FloatArray(AudioVisualizerBus.BAND_COUNT)

/**
 * Real-time 24-band audio spectrum visualizer driven by [AudioVisualizerBus].
 *
 * Registers with [AudioVisualizerBus] ONLY when `style != VisualizerStyle.OFF && isPlaying`,
 * ensuring zero FFT work and zero recomposition overhead when hidden or paused.
 */
@Composable
fun LiveAudioVisualizer(
    style: VisualizerStyle,
    isPlaying: Boolean,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    if (style == VisualizerStyle.OFF) return

    DisposableEffect(style, isPlaying) {
        if (isPlaying) {
            AudioVisualizerBus.registerCollector()
            onDispose {
                AudioVisualizerBus.unregisterCollector()
            }
        } else {
            onDispose { }
        }
    }

    val bands: FloatArray =
        if (isPlaying) {
            AudioVisualizerBus.bands.collectAsStateWithLifecycle().value
        } else {
            IDLE_SPECTRUM
        }

    Canvas(
        modifier = modifier.fillMaxWidth().height(64.dp),
    ) {
        when (style) {
            VisualizerStyle.OFF -> Unit
            VisualizerStyle.BARS -> drawSpectrumBars(bands, accentColor)
            VisualizerStyle.WAVE -> drawSpectrumWave(bands, accentColor)
            VisualizerStyle.GLOW -> drawAmbientGlow(bands, accentColor)
        }
    }
}

private fun DrawScope.drawSpectrumBars(
    bands: FloatArray,
    accentColor: Color,
) {
    val count = bands.size.coerceAtLeast(1)
    val gap = (size.width * 0.012f).coerceIn(2f, 6f)
    val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2f)
    val maxBarHeight = size.height * 0.88f
    val baselineY = size.height * 0.94f
    val minSliver = (size.height * 0.05f).coerceAtLeast(2f)
    val highlight = lerp(accentColor, Color.White, 0.45f)
    val cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)

    for (i in 0 until count) {
        val level = bands[i].coerceIn(0f, 1f)
        val barHeight = (minSliver + level * (maxBarHeight - minSliver)).coerceAtMost(maxBarHeight)
        val left = i * (barWidth + gap)
        val top = baselineY - barHeight

        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            highlight.copy(alpha = 0.55f + 0.45f * level),
                            accentColor.copy(alpha = 0.35f + 0.60f * level),
                            accentColor.copy(alpha = 0.14f + 0.20f * level),
                        ),
                    startY = top,
                    endY = baselineY,
                ),
            topLeft = Offset(left, top),
            size = Size(barWidth, barHeight),
            cornerRadius = cornerRadius,
        )

        // Subtle reflection sliver below baseline for depth.
        val reflectionHeight = (barHeight * 0.10f).coerceAtMost(size.height - baselineY)
        if (reflectionHeight > 0.5f) {
            drawRoundRect(
                color = accentColor.copy(alpha = 0.12f + 0.12f * level),
                topLeft = Offset(left, baselineY + 1f),
                size = Size(barWidth, reflectionHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

private fun DrawScope.drawSpectrumWave(
    bands: FloatArray,
    accentColor: Color,
) {
    val count = bands.size
    if (count < 2) return

    val stepX = size.width / (count - 1)
    val baseY = size.height * 0.90f
    val usableHeight = size.height * 0.78f
    val highlight = lerp(accentColor, Color.White, 0.35f)

    val primaryStroke = Path()
    val primaryFill = Path()
    val secondaryStroke = Path()

    fun pointY(
        index: Int,
        scale: Float,
    ): Float {
        val level = bands[index].coerceIn(0f, 1f)
        return baseY - (0.04f + level * scale) * usableHeight
    }

    val y0 = pointY(0, 1.0f)
    primaryStroke.moveTo(0f, y0)
    primaryFill.moveTo(0f, baseY)
    primaryFill.lineTo(0f, y0)
    secondaryStroke.moveTo(0f, pointY(0, 0.62f))

    var prevX = 0f
    var prevY = y0
    var prevSecY = pointY(0, 0.62f)
    for (i in 1 until count) {
        val x = i * stepX
        val y = pointY(i, 1.0f)
        val midX = (prevX + x) * 0.5f
        val midY = (prevY + y) * 0.5f
        primaryStroke.quadraticTo(prevX, prevY, midX, midY)
        primaryFill.quadraticTo(prevX, prevY, midX, midY)

        val secY = pointY((i + 2) % count, 0.62f)
        val secMidY = (prevSecY + secY) * 0.5f
        secondaryStroke.quadraticTo(prevX, prevSecY, midX, secMidY)

        prevX = x
        prevY = y
        prevSecY = secY
    }
    primaryStroke.lineTo(size.width, prevY)
    primaryFill.lineTo(size.width, prevY)
    primaryFill.lineTo(size.width, baseY)
    primaryFill.close()
    secondaryStroke.lineTo(size.width, prevSecY)

    drawPath(
        path = primaryFill,
        brush =
            Brush.verticalGradient(
                colors =
                    listOf(
                        accentColor.copy(alpha = 0.42f),
                        accentColor.copy(alpha = 0.04f),
                    ),
                startY = size.height * 0.08f,
                endY = baseY,
            ),
    )
    drawPath(
        path = secondaryStroke,
        color = accentColor.copy(alpha = 0.35f),
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = primaryStroke,
        brush =
            Brush.horizontalGradient(
                colors =
                    listOf(
                        accentColor.copy(alpha = 0.85f),
                        highlight,
                        accentColor.copy(alpha = 0.85f),
                    ),
            ),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawAmbientGlow(
    bands: FloatArray,
    accentColor: Color,
) {
    val count = bands.size
    if (count == 0) return

    fun avgRange(
        start: Int,
        endExclusive: Int,
    ): Float {
        val lo = start.coerceIn(0, count - 1)
        val hi = endExclusive.coerceIn(lo + 1, count)
        var sum = 0f
        for (i in lo until hi) {
            sum += bands[i].coerceIn(0f, 1f)
        }
        return sum / (hi - lo)
    }

    val bass = avgRange(0, 6)
    val mid = avgRange(6, 16)
    val treble = avgRange(16, count)

    val warmTint = lerp(accentColor, Color.White, 0.30f)
    val coolTint = lerp(accentColor, Color(0xFF64B5F6), 0.35f)
    val centerY = size.height * 0.52f
    val maxRadius = maxOf(size.width * 0.42f, size.height * 0.95f)

    // Left bass orb
    val bassRadius = (maxRadius * (0.42f + 0.58f * bass)).coerceAtLeast(1f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.22f + 0.55f * bass), Color.Transparent),
                center = Offset(size.width * 0.24f, centerY),
                radius = bassRadius,
            ),
        radius = bassRadius,
        center = Offset(size.width * 0.24f, centerY),
    )

    // Center vocal/mid orb
    val midRadius = (maxRadius * (0.38f + 0.56f * mid)).coerceAtLeast(1f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(warmTint.copy(alpha = 0.18f + 0.52f * mid), Color.Transparent),
                center = Offset(size.width * 0.52f, centerY * 0.92f),
                radius = midRadius,
            ),
        radius = midRadius,
        center = Offset(size.width * 0.52f, centerY * 0.92f),
    )

    // Right treble shimmer orb
    val trebleRadius = (maxRadius * (0.32f + 0.52f * treble)).coerceAtLeast(1f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(coolTint.copy(alpha = 0.16f + 0.48f * treble), Color.Transparent),
                center = Offset(size.width * 0.78f, centerY),
                radius = trebleRadius,
            ),
        radius = trebleRadius,
        center = Offset(size.width * 0.78f, centerY),
    )
}

/**
 * Self-contained Settings / Audio Effects card for Karaoke Vocal Remover and Live Audio Visualizer.
 *
 * Ready to embed inside `AudioEffectsSection` or `SettingScreen`.
 */
@Composable
fun KaraokeAndVisualizerSection(
    dataStoreManager: DataStoreManager,
    mediaPlayerHandler: MediaPlayerHandler,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val karaokeFlow = remember(dataStoreManager) { KaraokeAndVisualizerPrefs.observeKaraokeEffect(dataStoreManager) }
    val styleFlow = remember(dataStoreManager) { KaraokeAndVisualizerPrefs.observeVisualizerStyle(dataStoreManager) }
    val karaoke by karaokeFlow.collectAsStateWithLifecycle(initialValue = KaraokeEffect.OFF)
    val visualizerStyle by styleFlow.collectAsStateWithLifecycle(initialValue = VisualizerStyle.OFF)
    val controlState by mediaPlayerHandler.controlState.collectAsStateWithLifecycle()

    var suppressionDraft by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(karaoke.vocalSuppression) { suppressionDraft = null }
    val shownSuppression = suppressionDraft ?: karaoke.clampedSuppression

    fun commitKaraoke(updated: KaraokeEffect) {
        val clamped = updated.clamped()
        mediaPlayerHandler.setKaraokeEffect(clamped)
        scope.launch(Dispatchers.IO) {
            KaraokeAndVisualizerPrefs.setKaraokeEffect(dataStoreManager, mediaPlayerHandler, clamped)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Karaoke Vocal Remover toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Karaoke Mode",
                        style = typo().labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Suppress centered vocals in real time",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = karaoke.enabled,
                    onCheckedChange = { enabled ->
                        commitKaraoke(karaoke.copy(enabled = enabled))
                    },
                )
            }

            AnimatedVisibility(visible = karaoke.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Vocal Suppression",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Slider(
                                value = shownSuppression,
                                onValueChange = { raw ->
                                    val snapped = (raw * 100f).roundToInt().coerceIn(0, 100) / 100f
                                    suppressionDraft = snapped
                                    mediaPlayerHandler.setKaraokeEffect(karaoke.copy(vocalSuppression = snapped))
                                },
                                onValueChangeFinished = {
                                    suppressionDraft?.let { finalValue ->
                                        commitKaraoke(karaoke.copy(vocalSuppression = finalValue))
                                    }
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(shownSuppression * 100f).roundToInt()} %",
                                style = typo().bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(52.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Preserve Bass",
                                style = typo().labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Keep sub-bass and kick drums below 180 Hz",
                                style = typo().bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = karaoke.preserveBass,
                            onCheckedChange = { preserve ->
                                commitKaraoke(karaoke.copy(preserveBass = preserve))
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Live Audio Visualizer style selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Live Audio Visualizer",
                    style = typo().labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "24-band real-time spectrum (zero CPU when Off)",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VisualizerStyle.entries.forEach { option ->
                        Chip(
                            isSelected = option == visualizerStyle,
                            text = option.displayLabel(),
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    KaraokeAndVisualizerPrefs.setVisualizerStyle(dataStoreManager, option)
                                }
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = visualizerStyle != VisualizerStyle.OFF) {
                LiveAudioVisualizer(
                    style = visualizerStyle,
                    isPlaying = controlState.isPlaying,
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).height(48.dp),
                )
            }
        }
    }
}
