package com.maxrave.simpmusic.notch

actual object PlatformNowNotchBridge {
    actual fun canDrawOverlay(): Boolean = false

    actual fun requestOverlayPermission() {}

    actual fun canPostPromotedNotifications(): Boolean = true

    actual fun openLiveNotificationsSettings() {}

    actual fun resetDesktopPosition() {}

    actual fun sync(state: NowNotchLiveState) {
        // Dynamic Notch overlay relies on Android SYSTEM_ALERT_WINDOW or Desktop AWT window.
    }
}
