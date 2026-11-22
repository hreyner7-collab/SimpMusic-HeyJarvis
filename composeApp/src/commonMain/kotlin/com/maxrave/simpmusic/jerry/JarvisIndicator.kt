package com.maxrave.simpmusic.jerry

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Siri-style floating indicator:
 * - Idle (`Listening`): compact 44dp circle (matching the profile avatar) so it stays out of the way.
 * - Active (`Awake` / `Thinking` / `Result` / `Error`): expands into the full waveform pill box.
 */
@Composable
fun JarvisIndicator(modifier: Modifier = Modifier) {
    val state by JarvisStatus.state.collectAsState()
    if (state.phase == JarvisPhase.Off) return
    val expanded = state.phase != JarvisPhase.Listening
    val colors = listOf(Color(0xFF64D2FF), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFF30D158))

    if (!expanded) {
        Box(
            modifier = modifier
                .animateContentSize()
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xE61C1C1E))
                .border(1.5.dp, Brush.sweepGradient(colors), CircleShape)
                .clickable { JarvisController.triggerManualWake() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val mid = size.height / 2
                val path = Path()
                val steps = 28
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    val t = i.toFloat() / steps
                    val y = (mid + sin(t * 3 * PI) * (mid * 0.55f) * 0.85f * sin(t * PI)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, brush = Brush.horizontalGradient(colors), style = Stroke(width = 2.2.dp.toPx()))
            }
        }
        return
    }

    val reduceAnimations = com.maxrave.simpmusic.utils.LocalPerformanceSettings.current.reduceAnimations
    val phase: Float
    val amp: Float
    if (reduceAnimations) {
        phase = 0f
        amp = 0.7f
    } else {
        val transition = rememberInfiniteTransition(label = "jarvis")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f, targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "wave",
        )
        val animatedAmp by transition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)), label = "amp",
        )
        phase = animatedPhase
        amp = animatedAmp
    }

    val (label, color) = when (state.phase) {
        JarvisPhase.Listening -> "Listening for “Hey Jarvis”" to Color(0xFF8E8E93)
        JarvisPhase.Awake -> state.message.ifBlank { "Listening…" } to Color(0xFF30D158)
        JarvisPhase.Thinking -> "Thinking…" to Color(0xFF64D2FF)
        JarvisPhase.Result -> state.message to Color(0xFF30D158)
        JarvisPhase.Error -> state.message to Color(0xFFFF453A)
        JarvisPhase.Off -> "" to Color.Transparent
    }
    val amplitude = when (state.phase) {
        JarvisPhase.Awake -> 1f
        JarvisPhase.Thinking -> 0.5f * amp
        JarvisPhase.Listening -> 0.25f * amp
        else -> 0.15f
    }
    Column(
        modifier = modifier
            .animateContentSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE61C1C1E))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.width(220.dp).height(36.dp)) {
            val mid = size.height / 2
            val path = Path()
            val steps = 60
            for (i in 0..steps) {
                val x = size.width * i / steps
                val t = i.toFloat() / steps
                val y = (mid + sin(t * 4 * PI + phase) * (mid * 0.8f) * amplitude *
                    sin(t * PI)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, brush = Brush.horizontalGradient(colors), style = Stroke(width = 3.dp.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, color = color, fontSize = 12.sp, maxLines = 1)
    }
}
