package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val DjBlue = Color(0xFF1654C9)
val DjGreen = Color(0xFF2BD576)

/**
 * The DJ's ring, drawn like Spotify's: a green hand-drawn circle that is deliberately not perfect.
 * While the DJ is talking it pulses and wobbles with the voice; otherwise it breathes slowly.
 */
@Composable
fun DjRing(
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "djRing")
    val talkScale by transition.animateFloat(
        initialValue = 0.93f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "talkScale",
    )
    val idleScale by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idleScale",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (speaking) 3500 else 14000, easing = LinearEasing)),
        label = "spin",
    )
    val scale = if (speaking) talkScale else idleScale

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.36f * scale
        val path = Path()
        val steps = 120
        // A little more than one full turn, with the radius wobbling, so the ends overlap slightly.
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val angle = t * 2f * PI.toFloat() * 1.06f - 1.2f + spin
            val wobbleAmount = if (speaking) 0.09f else 0.06f
            val wobble = 1f + wobbleAmount * sin(2f * angle + 0.6f) + 0.035f * sin(3f * angle) + 0.03f * t
            val x = center.x + base * wobble * cos(angle)
            val y = center.y + base * wobble * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val width = size.minDimension * (if (speaking) 0.1f else 0.085f)
        drawPath(path, color = DjGreen, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
