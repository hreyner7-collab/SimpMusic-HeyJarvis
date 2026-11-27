package com.maxrave.simpmusic.hotkeys

/**
 * Platform global hotkey registration engine.
 *
 * - Windows JVM: registers system-wide shortcuts via Win32 `user32` `RegisterHotKey`/`GetMessageW`
 *   on a single sleeping daemon thread (0% CPU, zero polling).
 * - macOS / Linux JVM, Android, iOS: safe no-op.
 */
expect class PlatformGlobalHotkeyEngine() {
    val isSupportedOnCurrentPlatform: Boolean

    /**
     * Registers [bindings] and invokes [onAction] whenever a registered hotkey is pressed.
     * Returns the set of [HotkeyAction]s successfully registered with the OS.
     */
    fun start(
        bindings: List<HotkeyBinding>,
        onAction: (HotkeyAction) -> Unit,
    ): Set<HotkeyAction>

    fun stop()
}
