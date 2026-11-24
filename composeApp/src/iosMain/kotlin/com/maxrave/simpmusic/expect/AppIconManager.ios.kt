package com.maxrave.simpmusic.expect

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon

actual fun applyPlatformAppIcon(style: AppIconStyle) {}

@Composable
actual fun rememberAppIconPainter(style: AppIconStyle): Painter = painterResource(Res.drawable.circle_app_icon)
