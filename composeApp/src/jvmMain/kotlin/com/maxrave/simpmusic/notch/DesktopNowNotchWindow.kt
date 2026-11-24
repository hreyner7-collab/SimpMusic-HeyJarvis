package com.maxrave.simpmusic.notch

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Toolkit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Floating top-center "Now Notch" pill window for Windows / macOS / Linux desktop.
 * Displays the active track, live synced lyric line, progress hairline, track-change banner,
 * idle clock/dot, and click-to-expand quick controls.
 */
@Composable
fun DesktopNowNotchWindow(
    sharedViewModel: SharedViewModel,
    onRestoreApp: () -> Unit,
) {
    val state by NowNotchStateHolder.state.collectAsState()
    if (!state.settings.enabled) return
    if (!state.hasTrack && state.settings.idleMode == NowNotchIdleMode.HIDDEN) return

    val dataStoreManager = koinInject<DataStoreManager>()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var idleClock by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }

    LaunchedEffect(state.hasTrack, state.settings.idleMode) {
        if (!state.hasTrack && state.settings.idleMode == NowNotchIdleMode.COMPACT_CLOCK) {
            while (isActive) {
                idleClock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                delay(15_000L)
            }
        }
    }

    val pillWidthDp =
        when {
            !state.hasTrack -> 120f
            state.settings.compactSize -> 300f
            else -> 380f
        }
    val pillHeightDp =
        when {
            !state.hasTrack -> 44f
            expanded -> 112f
            state.settings.showLiveLyrics && !state.currentLyricLine.isNullOrBlank() -> 72f
            else -> 54f
        }

    val screenWidthDp = remember {
        runCatching { Toolkit.getDefaultToolkit().screenSize.width.toFloat() }.getOrDefault(1440f)
    }
    val centerX = ((screenWidthDp - pillWidthDp) / 2f).coerceAtLeast(0f)

    val windowState =
        remember(pillWidthDp, pillHeightDp, state.settings.topOffsetDp) {
            WindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(centerX.dp, state.settings.topOffsetDp.dp),
                size = DpSize(pillWidthDp.dp, pillHeightDp.dp),
            )
        }

    Window(
        onCloseRequest = {
            scope.launch {
                dataStoreManager.updateNowNotchSettings { it.copy(enabled = false) }
            }
        },
        title = "SimpMusic Now Notch",
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        state = windowState,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xEB101218))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
                        .clickable {
                            if (state.hasTrack) expanded = !expanded else onRestoreApp()
                        }.animateContentSize()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!state.hasTrack) {
                    Text(
                        text = if (state.settings.idleMode == NowNotchIdleMode.COMPACT_DOT) "♪" else idleClock,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                } else {
                    val headerText =
                        state.popupBanner
                            ?: if (state.artist.isNotBlank()) "${state.title} · ${state.artist}" else state.title
                    Text(
                        text = headerText,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onRestoreApp() },
                    )
                    val lyric = state.currentLyricLine?.takeIf { state.settings.showLiveLyrics && it.isNotBlank() }
                    if (lyric != null) {
                        Text(
                            text = lyric,
                            color = Color(0xFFD8C2F0FF),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { sharedViewModel.onUIEvent(UIEvent.Previous) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(SimpIcons.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    if (state.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                                    contentDescription = "Play or Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { sharedViewModel.onUIEvent(UIEvent.Next) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(SimpIcons.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        dataStoreManager.updateNowNotchSettings { it.copy(enabled = false) }
                                    }
                                },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(SimpIcons.Close, contentDescription = "Hide Now Notch", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progressFraction },
                        modifier = Modifier.fillMaxWidth(0.92f).height(2.dp).clip(RoundedCornerShape(1.dp)),
                        color = seed,
                        trackColor = Color.White.copy(alpha = 0.16f),
                    )
                }
            }
        }
    }
}
