package com.maxrave.simpmusic.notch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.simpmusic.ui.icon.Favorite
import com.maxrave.simpmusic.ui.icon.FavoriteBorder
import com.maxrave.simpmusic.ui.icon.Lyrics
import com.maxrave.simpmusic.ui.icon.MusicNote
import com.maxrave.simpmusic.ui.icon.OpenInFull
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.icon.UnfoldLess
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared 1-to-1 clone of Nooki's Dynamic Island / Now Notch pill & Expanded Music Card.
 * Used identically on Android (inside [NotchOverlayService]) and Desktop (inside [DesktopNowNotchWindow]).
 */
@Composable
fun NookiNotchIsland(
    state: NowNotchLiveState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekMs: (Long) -> Unit,
    onOpenApp: () -> Unit,
    onHide: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!state.settings.enabled) return
    if (!state.hasTrack && state.settings.idleMode == NowNotchIdleMode.HIDDEN) return

    var lyricsPeekOpen by remember(state.videoId) { mutableStateOf(false) }

    val targetWidth =
        when {
            !state.hasTrack -> if (state.settings.idleMode == NowNotchIdleMode.COMPACT_DOT) 46.dp else 104.dp
            expanded -> if (state.settings.compactSize) 316.dp else 348.dp
            state.settings.compactSize -> 158.dp
            else -> 190.dp
        }
    val targetHeight =
        when {
            !state.hasTrack -> 32.dp
            expanded && lyricsPeekOpen -> 284.dp
            expanded -> 194.dp
            state.settings.compactSize -> 32.dp
            else -> 36.dp
        }
    val targetCorner = if (expanded && state.hasTrack) 34.dp else 22.dp

    val morphSpecDp = spring<Dp>(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)
    val animatedWidth by animateDpAsState(targetValue = targetWidth, animationSpec = morphSpecDp, label = "notchWidth")
    val animatedHeight by animateDpAsState(targetValue = targetHeight, animationSpec = morphSpecDp, label = "notchHeight")
    val animatedCorner by animateDpAsState(targetValue = targetCorner, animationSpec = morphSpecDp, label = "notchCorner")

    val primaryColor by animateColorAsState(
        targetValue = Color(state.accentColorArgb),
        animationSpec = tween(550),
        label = "notchPrimary",
    )
    val secondaryColor by animateColorAsState(
        targetValue = Color(state.secondaryColorArgb),
        animationSpec = tween(550),
        label = "notchSecondary",
    )
    val shape = RoundedCornerShape(animatedCorner)
    val activeLyricIndex =
        remember(state.lyricsLines, state.lyricsSyncType, state.positionMs, state.lyricsOffsetMs) {
            NowNotchStateHolder.resolveSyncedLyricIndex(
                lines = state.lyricsLines,
                syncType = state.lyricsSyncType,
                positionMs = state.positionMs,
                offsetMs = state.lyricsOffsetMs,
            )
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        val showWaves = state.settings.showSideWaves && state.hasTrack && !expanded && state.isPlaying
        Box(
            modifier =
                Modifier
                    .nookiSideWaves(visible = showWaves, color = primaryColor)
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .clip(shape)
                    .background(Color.Black)
                    .nookiSpecularOutline(
                        shape = shape,
                        accentColor = if (state.hasTrack) primaryColor else Color.White.copy(alpha = 0.35f),
                    ).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (state.hasTrack) {
                            onExpandedChange(!expanded)
                        } else {
                            onOpenApp()
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (!state.hasTrack) {
                NookiIdlePillContent(idleMode = state.settings.idleMode)
            } else if (!expanded) {
                NookiCollapsedMusicPill(
                    artworkBitmap = state.artworkBitmap,
                    thumbnailUrl = state.thumbnailUrl,
                    isPlaying = state.isPlaying,
                    accentColor = primaryColor,
                    compact = state.settings.compactSize,
                )
            } else {
                NookiExpandedMusicCard(
                    state = state,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    lyricsPeekOpen = lyricsPeekOpen,
                    activeLyricIndex = activeLyricIndex,
                    onToggleLyricsPeek = { lyricsPeekOpen = !lyricsPeekOpen },
                    onCollapse = { onExpandedChange(false) },
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onToggleLike = onToggleLike,
                    onSeekFraction = onSeekFraction,
                    onSeekMs = onSeekMs,
                    onOpenApp = onOpenApp,
                    onHide = onHide,
                )
            }
        }

        // Exact port of Nooki's CollapsedNotchLyricsBar sitting just below the collapsed pill
        val subBarText =
            when {
                !state.hasTrack || expanded -> null
                !state.popupBanner.isNullOrBlank() -> state.popupBanner
                state.settings.showLiveLyrics && !state.currentLyricLine.isNullOrBlank() -> state.currentLyricLine
                else -> null
            }

        AnimatedVisibility(
            visible = subBarText != null,
            enter =
                fadeIn(tween(180)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                    ),
            exit =
                fadeOut(tween(150)) +
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(160)),
        ) {
            subBarText?.let { line ->
                NookiCollapsedLyricsBar(
                    text = line,
                    accentColor = primaryColor,
                    isPopup = !state.popupBanner.isNullOrBlank(),
                    maxWidth = if (state.settings.compactSize) 248.dp else 306.dp,
                    onClick = { onExpandedChange(true) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun NookiIdlePillContent(idleMode: NowNotchIdleMode) {
    if (idleMode == NowNotchIdleMode.COMPACT_DOT) {
        Icon(
            imageVector = SimpIcons.MusicNote,
            contentDescription = "SimpMusic",
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(16.dp),
        )
    } else {
        var now by remember {
            mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
        }
        LaunchedEffect(Unit) {
            while (true) {
                now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                delay(15_000L)
            }
        }
        val hh = now.hour.toString().padStart(2, '0')
        val mm = now.minute.toString().padStart(2, '0')
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = SimpIcons.MusicNote,
                contentDescription = null,
                tint = Color(NOOKI_VISUALIZER_BLUE_ARGB),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "$hh:$mm",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NookiCollapsedMusicPill(
    artworkBitmap: ImageBitmap?,
    thumbnailUrl: String?,
    isPlaying: Boolean,
    accentColor: Color,
    compact: Boolean,
) {
    val artSize = if (compact) 21.dp else 24.dp
    val hPad = if (compact) 8.dp else 10.dp
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NookiArtworkThumb(
            artworkBitmap = artworkBitmap,
            thumbnailUrl = thumbnailUrl,
            size = artSize,
            cornerRadius = if (compact) 7.dp else 8.dp,
        )
        // Clean center breathing room aligned over front camera cutout
        Spacer(Modifier.weight(1f))
        NookiCollapsedVisualizer(
            isPlaying = isPlaying,
            color = accentColor,
            modifier = if (compact) Modifier.size(width = 16.dp, height = 14.dp) else Modifier.size(width = 19.2.dp, height = 16.8.dp),
        )
    }
}

@Composable
private fun NookiCollapsedLyricsBar(
    text: String,
    accentColor: Color,
    isPopup: Boolean,
    maxWidth: Dp,
    onClick: () -> Unit,
) {
    val barProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        barProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        )
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier =
            Modifier
                .padding(top = 5.dp)
                .widthIn(min = 120.dp, max = maxWidth)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    val p = barProgress.value.coerceIn(0f, 1.15f)
                    scaleX = max(0.05f, p)
                    scaleY = max(0.05f, p)
                    alpha = p.coerceIn(0f, 1f)
                }.clip(shape)
                .background(Color(0xEB0A0B0F))
                .border(0.75.dp, accentColor.copy(alpha = 0.42f), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = SimpIcons.MusicNote,
                contentDescription = null,
                tint = if (isPopup) Color.White else accentColor,
                modifier = Modifier.size(13.dp),
            )
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (slideInVertically(tween(210)) { it / 2 } + fadeIn(tween(190)))
                        .togetherWith(slideOutVertically(tween(150)) { -it / 2 } + fadeOut(tween(140)))
                },
                label = "notchLyricLine",
            ) { line ->
                Text(
                    text = line,
                    color = if (isPopup) accentColor else Color.White.copy(alpha = 0.94f),
                    fontSize = 12.sp,
                    fontWeight = if (isPopup) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NookiExpandedMusicCard(
    state: NowNotchLiveState,
    primaryColor: Color,
    secondaryColor: Color,
    lyricsPeekOpen: Boolean,
    activeLyricIndex: Int,
    onToggleLyricsPeek: () -> Unit,
    onCollapse: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekMs: (Long) -> Unit,
    onOpenApp: () -> Unit,
    onHide: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Nooki AnimatedCoverArtGradientBackground (black top cutout veil + orbiting pastel gradient orbs)
        NookiAnimatedCoverArtBackground(
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Foreground Content
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Track Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NookiArtworkThumb(
                    artworkBitmap = state.artworkBitmap,
                    thumbnailUrl = state.thumbnailUrl,
                    size = 54.dp,
                    cornerRadius = 14.dp,
                    modifier = Modifier.clickable(onClick = onOpenApp),
                )
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpenApp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.title.ifBlank { "SimpMusic" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    if (state.artist.isNotBlank()) {
                        Text(
                            text = state.artist,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                NookiExpandedVisualizer(
                    isPlaying = state.isPlaying,
                    color = primaryColor,
                    modifier = Modifier.size(width = 24.dp, height = 18.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (lyricsPeekOpen) primaryColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f),
                            ).clickable(onClick = onToggleLyricsPeek),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SimpIcons.Lyrics,
                        contentDescription = "Lyrics",
                        tint = if (lyricsPeekOpen) primaryColor else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            // Optional expandable inline Lyrics Peek (direct port of MusicLyricsPeek)
            if (lyricsPeekOpen) {
                NookiLyricsPeek(
                    lines = state.lyricsLines,
                    syncType = state.lyricsSyncType,
                    activeIndex = activeLyricIndex,
                    accentColor = primaryColor,
                    onSeekToMs = onSeekMs,
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                )
            }

            // Middle Seek Bar & Timestamps
            NookiSeekBar(
                progress = state.progressFraction,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                accentColor = primaryColor,
                onSeekFraction = onSeekFraction,
            )

            // Bottom Controls Row (Like / Prev / Play-Pause / Next / Open-or-Collapse)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onToggleLike),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isLiked) SimpIcons.Favorite else SimpIcons.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (state.isLiked) primaryColor else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onPrevious),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SimpIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                        contentDescription = "Play or Pause",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SimpIcons.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onOpenApp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SimpIcons.OpenInFull,
                            contentDescription = "Open SimpMusic",
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onHide?.invoke() ?: onCollapse() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SimpIcons.UnfoldLess,
                            contentDescription = "Collapse",
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NookiArtworkThumb(
    artworkBitmap: ImageBitmap?,
    thumbnailUrl: String?,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val boxMod =
        modifier
            .size(size)
            .clip(shape)
            .background(Color(0xFF1F2230))
    if (artworkBitmap != null) {
        Image(
            bitmap = artworkBitmap,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = boxMod,
        )
    } else if (!thumbnailUrl.isNullOrBlank()) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = boxMod,
        )
    } else {
        Box(
            modifier = boxMod,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SimpIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

@Composable
private fun NookiAnimatedCoverArtBackground(
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nookiCoverBg")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(9000, easing = LinearEasing)),
        label = "bgPhase",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(color = Color.Black)

        val cos1 = cos(phase)
        val sin1 = sin(phase)
        val cos2 = cos(phase * 0.75f + 1.2f)
        val sin2 = sin(phase * 0.75f + 1.2f)

        val cx1 = ((cos1 * 0.18f) + 0.28f) * w
        val cy1 = ((sin1 * 0.14f) + 0.80f) * h
        val r1 = max(w, h) * 0.88f
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            primaryColor.copy(alpha = 0.58f),
                            primaryColor.copy(alpha = 0.28f),
                            Color.Transparent,
                        ),
                    center = Offset(cx1, cy1),
                    radius = r1,
                ),
            radius = r1,
            center = Offset(cx1, cy1),
        )

        val cx2 = (0.72f - (cos2 * 0.18f)) * w
        val cy2 = (0.84f - (sin2 * 0.14f)) * h
        val r2 = max(w, h) * 0.82f
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            secondaryColor.copy(alpha = 0.52f),
                            secondaryColor.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                    center = Offset(cx2, cy2),
                    radius = r2,
                ),
            radius = r2,
            center = Offset(cx2, cy2),
        )

        // Vertical cutout veil from Nooki's MusicVisualizerKt.AnimatedCoverArtGradientBackground
        drawRect(
            brush =
                Brush.verticalGradient(
                    0.0f to Color.Black,
                    0.38f to Color.Black,
                    0.68f to Color.Black.copy(alpha = 0.40f),
                    1.0f to Color.Black.copy(alpha = 0.20f),
                ),
        )
    }
}

@Composable
private fun NookiCollapsedVisualizer(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nookiViz4")
    val barPhases =
        listOf(0, 180, 340, 110).mapIndexed { index, delayMs ->
            if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 1.0f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(420 + index * 95, delayMillis = delayMs, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "bar$index",
                ).value
            } else {
                0f
            }
        }
    val gradient = Brush.horizontalGradient(listOf(lerp(color, Color.White, 0.32f), color))

    Canvas(modifier = modifier) {
        val barWidth = size.width / 5.2f
        val gap = (size.width - (4f * barWidth)) / 3f
        val radius = barWidth / 2f
        for (i in 0 until 4) {
            val amp = barPhases[i].coerceIn(0f, 1f)
            val barH = ((size.height - barWidth) * amp) + barWidth
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(i * (barWidth + gap), (size.height - barH) / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
private fun NookiExpandedVisualizer(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nookiViz5")
    val amplitudes =
        listOf(0, 140, 280, 90, 220).mapIndexed { index, delayMs ->
            if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1.0f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(400 + index * 85, delayMillis = delayMs, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "expBar$index",
                ).value
            } else {
                0.2f
            }
        }

    Canvas(modifier = modifier) {
        val barWidth = 2.6.dp.toPx()
        val gap = 2.4.dp.toPx()
        val radius = barWidth / 2f
        val maxH = size.height.coerceAtLeast(barWidth)
        for (i in 0 until 5) {
            val amp = amplitudes[i].coerceIn(0f, 1f)
            val barH = ((maxH - barWidth) * amp) + barWidth
            drawRoundRect(
                color = color.copy(alpha = 0.78f),
                topLeft = Offset(i * (barWidth + gap), (size.height - barH) / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
private fun NookiSeekBar(
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    accentColor: Color,
    onSeekFraction: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress) }
    val displayFraction = (if (dragging) dragFraction else progress).coerceIn(0f, 1f)
    val trackHeight by animateDpAsState(if (dragging) 7.dp else 4.dp, label = "seekHeight")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatMinSec(if (dragging && durationMs > 0L) (dragFraction * durationMs).toLong() else positionMs),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Canvas(
            modifier =
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onSeekFraction(frac)
                        }
                    }.pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragFraction = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragging = false
                                onSeekFraction(dragFraction)
                            },
                            onDragCancel = { dragging = false },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragFraction = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            },
                        )
                    },
        ) {
            val hPx = trackHeight.toPx()
            val y = (size.height - hPx) / 2f
            val r = hPx / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, y),
                size = Size(size.width, hPx),
                cornerRadius = CornerRadius(r, r),
            )
            val fillW = (size.width * displayFraction).coerceIn(hPx, size.width)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(lerp(accentColor, Color.White, 0.35f), accentColor)),
                topLeft = Offset(0f, y),
                size = Size(fillW, hPx),
                cornerRadius = CornerRadius(r, r),
            )
        }
        Text(
            text = formatRemainingMinSec(durationMs, positionMs),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun NookiLyricsPeek(
    lines: List<Line>,
    syncType: String?,
    activeIndex: Int,
    accentColor: Color,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lines.size) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lines.isEmpty() -> {
                Text(
                    text = "No lyrics found",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }

            syncType == null || syncType == "UNSYNCED" -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { idx, line ->
                        Text(
                            text = line.words,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isActive = index == activeIndex
                        val startMs = line.startTimeMs.toLongOrNull() ?: 0L
                        Text(
                            text = line.words,
                            color = if (isActive) Color.White else accentColor.copy(alpha = 0.58f),
                            fontSize = if (isActive) 13.sp else 11.5.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { if (startMs > 0L) onSeekToMs(startMs) }
                                    .padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.nookiSpecularOutline(
    shape: Shape,
    accentColor: Color,
): Modifier {
    val brush =
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.42f),
            0.25f to accentColor.copy(alpha = 0.50f),
            0.55f to Color.White.copy(alpha = 0.14f),
            0.85f to accentColor.copy(alpha = 0.38f),
            1.0f to Color.White.copy(alpha = 0.36f),
        )
    return this.border(width = 0.85.dp, brush = brush, shape = shape)
}

@Composable
private fun Modifier.nookiSideWaves(
    visible: Boolean,
    color: Color,
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(260),
        label = "sideWaves",
    )
    if (progress <= 0.01f) return this
    return this.drawWithContent {
        drawContent()
        val waveColor = color.copy(alpha = 0.85f * progress)
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        val xShift = ((6f * progress) - 2f).dp.toPx()
        val radius = size.height * ((progress * 0.18f) + 0.22f)
        val arcSize = Size(radius * 2f, radius * 2f)
        val topY = center.y - radius

        // Left glowing arc (148° for 64°) matching Nooki's NotchSideWavesKt
        drawArc(
            color = waveColor,
            startAngle = 148f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = Offset(-xShift, topY),
            size = arcSize,
            style = stroke,
        )
        // Right glowing arc (-32° for 64°)
        drawArc(
            color = waveColor,
            startAngle = -32f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = Offset((size.width + xShift) - arcSize.width, topY),
            size = arcSize,
            style = stroke,
        )
    }
}

private fun formatMinSec(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val m = sec / 60L
    val s = sec % 60L
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun formatRemainingMinSec(
    durationMs: Long,
    positionMs: Long,
): String {
    val rem = (durationMs - positionMs).coerceAtLeast(0L) / 1000L
    val m = rem / 60L
    val s = rem % 60L
    return "-$m:${s.toString().padStart(2, '0')}"
}
