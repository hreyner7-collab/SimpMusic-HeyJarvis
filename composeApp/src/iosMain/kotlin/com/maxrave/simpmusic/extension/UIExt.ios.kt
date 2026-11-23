package com.maxrave.simpmusic.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.maxrave.domain.data.model.ui.ScreenSizeInfo
import platform.UIKit.UIApplication
import kotlin.math.roundToInt

@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

// Picture-in-picture for the in-app video is not wired on iOS yet.
@Composable
actual fun rememberIsInPipMode(): Boolean = false

@Composable
actual fun getScreenSizeInfo(): ScreenSizeInfo {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val hPx = window.containerSize.height
    val wPx = window.containerSize.width
    return ScreenSizeInfo(
        hDP = with(density) { hPx.toDp().value.roundToInt() },
        wDP = with(density) { wPx.toDp().value.roundToInt() },
        hPX = hPx,
        wPX = wPx,
    )
}
