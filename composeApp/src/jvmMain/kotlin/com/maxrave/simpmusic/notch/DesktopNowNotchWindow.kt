package com.maxrave.simpmusic.notch

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.ui.component.MacWindowDrag
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Toolkit
import java.util.prefs.Preferences

/**
 * Draggable floating Nooki Now Notch window for Windows / macOS / Linux desktop,
 * rendering the shared [NookiNotchIsland] Compose component and persisting custom screen placement.
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

    val prefs = remember { Preferences.userRoot().node("SimpMusic/NowNotch") }
    var customCenterXDp by remember {
        val saved = prefs.getFloat("centerX", Float.NaN)
        mutableStateOf(saved.takeUnless { it.isNaN() })
    }
    var customYDp by remember {
        val saved = prefs.getFloat("windowY", Float.NaN)
        mutableStateOf(saved.takeUnless { it.isNaN() })
    }

    val windowWidthDp = if (expanded && state.hasTrack) 372f else 330f
    val windowHeightDp = if (expanded && state.hasTrack) 316f else 108f

    val screenWidthDp = remember {
        runCatching { Toolkit.getDefaultToolkit().screenSize.width.toFloat() }.getOrDefault(1440f)
    }

    val windowState =
        remember {
            val startX = (customCenterXDp ?: (screenWidthDp / 2f)) - (windowWidthDp / 2f)
            val startY = customYDp ?: state.settings.topOffsetDp.toFloat()
            WindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(startX.dp, startY.dp),
                size = DpSize(windowWidthDp.dp, windowHeightDp.dp),
            )
        }

    var isDragging by remember { mutableStateOf(false) }
    var lastResetCounter by remember { mutableStateOf(DesktopNowNotchManager.resetPositionCounter) }
    var lastObservedOffsetDp by remember { mutableStateOf(state.settings.topOffsetDp) }

    LaunchedEffect(DesktopNowNotchManager.resetPositionCounter) {
        val count = DesktopNowNotchManager.resetPositionCounter
        if (count != lastResetCounter) {
            lastResetCounter = count
            customCenterXDp = null
            customYDp = null
            prefs.remove("centerX")
            prefs.remove("windowY")
        }
    }

    LaunchedEffect(state.settings.topOffsetDp) {
        if (state.settings.topOffsetDp != lastObservedOffsetDp) {
            lastObservedOffsetDp = state.settings.topOffsetDp
            customYDp = null
            prefs.remove("windowY")
        }
    }

    LaunchedEffect(windowWidthDp, windowHeightDp, customCenterXDp, customYDp, state.settings.topOffsetDp, isDragging) {
        windowState.size = DpSize(windowWidthDp.dp, windowHeightDp.dp)
        if (!isDragging) {
            val center = customCenterXDp ?: (screenWidthDp / 2f)
            val top = customYDp ?: state.settings.topOffsetDp.toFloat()
            windowState.position = WindowPosition((center - (windowWidthDp / 2f)).dp, top.dp)
        }
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
        var dragOffsetX by remember { mutableStateOf(0) }
        var dragOffsetY by remember { mutableStateOf(0) }

        fun persistWindowLocation() {
            val transform = window.graphicsConfiguration?.defaultTransform
            val scaleX = transform?.scaleX ?: 1.0
            val scaleY = transform?.scaleY ?: 1.0
            val pos = windowState.position as? WindowPosition.Absolute
            val leftDp = pos?.x?.value ?: (window.x / scaleX).toFloat()
            val topDp = pos?.y?.value ?: (window.y / scaleY).toFloat()
            val centerDp = leftDp + (windowWidthDp / 2f)
            customCenterXDp = centerDp
            customYDp = topDp
            prefs.putFloat("centerX", centerDp)
            prefs.putFloat("windowY", topDp)
            isDragging = false
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
                    .pointerInput(window) {
                        var nativeDrag = false
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                nativeDrag = MacWindowDrag.start(window)
                                if (nativeDrag) return@detectDragGestures
                                val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                dragOffsetX = mouse.x - window.x
                                dragOffsetY = mouse.y - window.y
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                if (nativeDrag) return@detectDragGestures
                                val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                window.setLocation(mouse.x - dragOffsetX, mouse.y - dragOffsetY)
                            },
                            onDragEnd = { persistWindowLocation() },
                            onDragCancel = { persistWindowLocation() },
                        )
                    },
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
