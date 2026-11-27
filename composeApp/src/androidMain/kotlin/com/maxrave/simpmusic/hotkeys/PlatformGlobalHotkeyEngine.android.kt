package com.maxrave.simpmusic.hotkeys

actual class PlatformGlobalHotkeyEngine actual constructor() {
    actual val isSupportedOnCurrentPlatform: Boolean = false

    actual fun start(
        bindings: List<HotkeyBinding>,
        onAction: (HotkeyAction) -> Unit,
    ): Set<HotkeyAction> = emptySet()

    actual fun stop() = Unit
}
