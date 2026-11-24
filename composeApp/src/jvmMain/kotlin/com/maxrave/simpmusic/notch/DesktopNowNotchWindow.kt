package com.maxrave.simpmusic.notch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Toolkit

/**
 * Floating top-center Nooki Now Notch window for Windows / macOS / Linux desktop,
 * rendering the shared [NookiNotchIsland] Compose component.
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
    val mediaPlayerHandler = koinInject<MediaPlayerHandler>()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val windowWidthDp = if (expanded && state.hasTrack) 372f else 330f
    val windowHeightDp = if (expanded && state.hasTrack) 308f else 96f

    val screenWidthDp = remember {
        runCatching { Toolkit.getDefaultToolkit().screenSize.width.toFloat() }.getOrDefault(1440f)
    }
    val centerX = ((screenWidthDp - windowWidthDp) / 2f).coerceAtLeast(0f)

    val windowState =
        remember(windowWidthDp, windowHeightDp, state.settings.topOffsetDp) {
            WindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(centerX.dp, state.settings.topOffsetDp.dp),
                size = DpSize(windowWidthDp.dp, windowHeightDp.dp),
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
            NookiNotchIsland(
                state = state,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onPrevious = { sharedViewModel.onUIEvent(UIEvent.Previous) },
                onPlayPause = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                onNext = { sharedViewModel.onUIEvent(UIEvent.Next) },
                onToggleLike = { mediaPlayerHandler.toggleLike() },
                onSeekFraction = { frac ->
                    sharedViewModel.onUIEvent(UIEvent.UpdateProgress((frac.coerceIn(0f, 1f) * 100f)))
                },
                onSeekMs = { ms ->
                    val total = state.durationMs
                    if (total > 0L) {
                        sharedViewModel.onUIEvent(
                            UIEvent.UpdateProgress(((ms.toFloat() * 100f) / total.toFloat()).coerceIn(0f, 100f)),
                        )
                    }
                },
                onOpenApp = onRestoreApp,
                onHide = {
                    scope.launch {
                        dataStoreManager.updateNowNotchSettings { it.copy(enabled = false) }
                    }
                },
            )
        }
    }
}
