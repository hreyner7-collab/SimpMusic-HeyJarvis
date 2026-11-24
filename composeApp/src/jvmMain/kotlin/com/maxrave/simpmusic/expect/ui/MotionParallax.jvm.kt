package com.maxrave.simpmusic.expect.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

actual fun Modifier.motionParallaxTilt(
    enabled: Boolean,
    maxTiltDegrees: Float,
): Modifier {
    if (!enabled) return this
    return composed {
        var targetTiltX by remember { mutableFloatStateOf(0f) }
        var targetTiltY by remember { mutableFloatStateOf(0f) }
        val tiltX by animateFloatAsState(targetValue = targetTiltX, animationSpec = tween(140), label = "parallaxTiltX")
        val tiltY by animateFloatAsState(targetValue = targetTiltY, animationSpec = tween(140), label = "parallaxTiltY")

        this
            .pointerInput(maxTiltDegrees) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val pos = event.changes.firstOrNull()?.position ?: continue
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = ((pos.x / w) - 0.5f) * 2f
                                val ny = ((pos.y / h) - 0.5f) * 2f
                                targetTiltY = (nx * maxTiltDegrees).coerceIn(-maxTiltDegrees, maxTiltDegrees)
                                targetTiltX = (-ny * maxTiltDegrees).coerceIn(-maxTiltDegrees, maxTiltDegrees)
                            }

                            PointerEventType.Exit -> {
                                targetTiltX = 0f
                                targetTiltY = 0f
                            }
                        }
                    }
                }
            }.graphicsLayer {
                rotationX = tiltX
                rotationY = tiltY
                val scale = 1f + (hypot(tiltX, tiltY) / maxTiltDegrees).coerceIn(0f, 1f) * 0.03f
                scaleX = scale
                scaleY = scale
                cameraDistance = 16f * density
            }
    }
}
