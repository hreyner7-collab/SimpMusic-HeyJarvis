package com.maxrave.simpmusic.notch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Controls visibility and position reset triggers of the desktop Now Notch floating window. */
object DesktopNowNotchManager {
    var isVisible by mutableStateOf(false)
    var resetPositionCounter by mutableStateOf(0)

    fun resetPosition() {
        resetPositionCounter++
    }
}

actual object PlatformNowNotchBridge {
    actual fun canDrawOverlay(): Boolean = true

    actual fun requestOverlayPermission() {}

    actual fun canPostPromotedNotifications(): Boolean = true

    actual fun openLiveNotificationsSettings() {}

    actual fun resetDesktopPosition() {
        DesktopNowNotchManager.resetPosition()
    }

    actual fun sync(state: NowNotchLiveState) {
        DesktopNowNotchManager.isVisible = state.settings.enabled
    }
}
