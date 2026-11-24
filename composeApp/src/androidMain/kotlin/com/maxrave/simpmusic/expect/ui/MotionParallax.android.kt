package com.maxrave.simpmusic.expect.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

actual fun Modifier.motionParallaxTilt(
    enabled: Boolean,
    maxTiltDegrees: Float,
): Modifier {
    if (!enabled) return this
    return composed {
        val context = LocalContext.current
        var tiltX by remember { mutableFloatStateOf(0f) }
        var tiltY by remember { mutableFloatStateOf(0f) }

        DisposableEffect(context, maxTiltDegrees) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val rotationSensor =
                sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            if (sensorManager == null || rotationSensor == null) {
                return@DisposableEffect onDispose {}
            }

            var basePitch: Float? = null
            var baseRoll: Float? = null
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val alpha = 0.15f

            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val (pitchDeg, rollDeg) =
                            if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                                SensorManager.getOrientation(rotationMatrix, orientation)
                                Math.toDegrees(orientation[1].toDouble()).toFloat() to
                                    Math.toDegrees(orientation[2].toDouble()).toFloat()
                            } else {
                                val ax = event.values[0]
                                val ay = event.values[1]
                                val az = event.values[2]
                                val pitch = Math.toDegrees(atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                                val roll = Math.toDegrees(atan2(-ax.toDouble(), az.toDouble())).toFloat()
                                pitch to roll
                            }

                        if (basePitch == null || baseRoll == null) {
                            basePitch = pitchDeg
                            baseRoll = rollDeg
                        }

                        val targetX = (-(pitchDeg - (basePitch ?: pitchDeg)) * 0.35f).coerceIn(-maxTiltDegrees, maxTiltDegrees)
                        val targetY = ((rollDeg - (baseRoll ?: rollDeg)) * 0.35f).coerceIn(-maxTiltDegrees, maxTiltDegrees)
                        tiltX += (targetX - tiltX) * alpha
                        tiltY += (targetY - tiltY) * alpha
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                    }
                }

            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }

        this.graphicsLayer {
            rotationX = tiltX
            rotationY = tiltY
            val scale = 1f + (hypot(tiltX, tiltY) / maxTiltDegrees).coerceIn(0f, 1f) * 0.03f
            scaleX = scale
            scaleY = scale
            cameraDistance = 16f * density
        }
    }
}
