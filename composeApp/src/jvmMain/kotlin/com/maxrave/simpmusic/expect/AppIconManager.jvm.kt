package com.maxrave.simpmusic.expect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon

object DesktopAppIconState {
    private val _style = MutableStateFlow(AppIconStyle.DEFAULT)
    val style: StateFlow<AppIconStyle> = _style.asStateFlow()

    fun setStyle(style: AppIconStyle) {
        _style.value = style
    }
}

actual fun applyPlatformAppIcon(style: AppIconStyle) {
    DesktopAppIconState.setStyle(style)
}

@Composable
actual fun rememberAppIconPainter(style: AppIconStyle): Painter {
    val activeStyle by DesktopAppIconState.style.collectAsState()
    val resolved = if (style == AppIconStyle.DEFAULT) activeStyle else style
    val base = painterResource(Res.drawable.circle_app_icon)
    if (resolved == AppIconStyle.DEFAULT) return base

    return remember(resolved, base) {
        object : Painter() {
            override val intrinsicSize: Size = Size(128f, 128f)

            override fun DrawScope.onDraw() {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val bgBrush =
                    when (resolved) {
                        AppIconStyle.OBSIDIAN ->
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF232530), Color(0xFF0E0F14)),
                                center = center,
                                radius = radius,
                            )

                        AppIconStyle.NEON ->
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF005B7F), Color(0xFF061B26)),
                                center = center,
                                radius = radius,
                            )

                        AppIconStyle.DEFAULT ->
                            Brush.radialGradient(colors = listOf(Color.Transparent, Color.Transparent))
                    }
                drawCircle(brush = bgBrush, radius = radius, center = center)
                val ringColor = if (resolved == AppIconStyle.NEON) Color(0xFF00E5FF) else Color(0xFFC8CDD8)
                drawCircle(
                    color = ringColor,
                    radius = (radius - 4f).coerceAtLeast(1f),
                    center = center,
                    style = Stroke(width = 6f),
                )
                with(base) {
                    draw(size = size, alpha = 0.92f)
                }
            }
        }
    }
}
