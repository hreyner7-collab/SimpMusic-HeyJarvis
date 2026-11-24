package com.maxrave.simpmusic.expect

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val APP_ICON_STYLE_KEY = "app_icon_style"

enum class AppIconStyle(
    val id: String,
    val label: String,
) {
    DEFAULT("default", "Default"),
    OBSIDIAN("obsidian", "Dark Obsidian"),
    NEON("neon", "Neon Cyan"),
    ;

    companion object {
        fun fromId(id: String?): AppIconStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

fun DataStoreManager.appIconStyleFlow(): Flow<AppIconStyle> =
    getString(APP_ICON_STYLE_KEY)
        .map { AppIconStyle.fromId(it) }
        .distinctUntilChanged()

suspend fun DataStoreManager.setAppIconStyle(style: AppIconStyle) {
    putString(APP_ICON_STYLE_KEY, style.id)
}

expect fun applyPlatformAppIcon(style: AppIconStyle)

@Composable
expect fun rememberAppIconPainter(style: AppIconStyle): Painter
