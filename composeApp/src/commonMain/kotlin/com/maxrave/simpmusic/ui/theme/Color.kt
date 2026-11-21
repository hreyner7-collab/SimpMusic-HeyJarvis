package com.maxrave.simpmusic.ui.theme

import androidx.compose.ui.graphics.Color

// ===== Brand =====

/**
 * Brand seed color — Apple Music crimson. The whole Material 3 ColorScheme is generated from this
 * color at runtime — see [AppTheme].
 */
val seed = Color(0xFFFA233B)

// ===== Semantic colors (not derivable from the color scheme) =====

/** Liked/favorite state (heart buttons, favorite tiles). */
val favoriteColor = Color(0xFFFA233B)

/** Currently playing lyric line — luminous white, Apple Music style. */
val lyricActiveColor = Color(0xFFFFFFFF)

val shimmerBackground = Color(0x7E2C2C2E)
val shimmerLine = Color(0xFF3A3A3C)

// Light-theme counterparts of the shimmer tokens.
val shimmerBackgroundLight = Color(0x7EDCD8D8)
val shimmerLineLight = Color(0xFFCFC8C8)

val overlay = Color(0x321C1C1E)
val blackMoreOverlay = Color(0x8F141416)

// ===== Desktop shell =====
// Apple Music macOS-style layering: pure black window frame with elevated obsidian-charcoal panels.

val desktopWindowDark = Color(0xFF000000)
val desktopWindowLight = Color(0xFFF5F5F7)
val desktopPanelDark = Color(0xFF141416)

// ===== Desktop window controls =====
// The macOS traffic lights. Fixed by convention rather than by theme — users read these by
// colour, so they stay the same in light and dark.

val windowCloseButton = Color(0xFFFF605C)
val windowCloseButtonHover = Color(0xFFE54942)
val windowMinimiseButton = Color(0xFFFFBD44)
val windowMinimiseButtonHover = Color(0xFFE5A93D)
val windowMaximiseButton = Color(0xFF00CA4E)
val windowMaximiseButtonHover = Color(0xFF00B344)

// ===== Legacy — do not add new usages =====

/**
 * Old M3 primary (lavender). Kept only for the SettingScreen storage bar,
 * which stays untouched by owner's decision.
 */
@Deprecated("Legacy storage bar color only — use MaterialTheme.colorScheme.primary in new code")
val md_theme_dark_primary = Color(0xFFB2C5FF)
