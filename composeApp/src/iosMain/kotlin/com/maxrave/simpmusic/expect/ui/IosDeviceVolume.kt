package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFoundation.AVAudioSession
import platform.CoreGraphics.CGRectMake
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UISlider

/**
 * iOS has no public call that sets the system volume. Reading goes through
 * [AVAudioSession.outputVolume]; writing uses the long-standing trick of driving the UISlider
 * inside an off-screen [MPVolumeView], which is the only route Apple's own volume HUD accepts.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDeviceVolumeController(): DeviceVolumeController? {
    var current by remember { mutableFloatStateOf(AVAudioSession.sharedInstance().outputVolume) }

    LaunchedEffect(Unit) {
        while (true) {
            current = AVAudioSession.sharedInstance().outputVolume
            delay(500)
        }
    }

    return remember {
        object : DeviceVolumeController {
            private var volumeView: MPVolumeView? = null

            override val volumeFraction: Float
                get() = current

            override fun setVolumeFraction(fraction: Float) {
                val view = volumeView ?: createHiddenVolumeView()?.also { volumeView = it } ?: return
                val slider = view.subviews.filterIsInstance<UISlider>().firstOrNull() ?: return
                slider.setValue(fraction.coerceIn(0f, 1f), animated = false)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createHiddenVolumeView(): MPVolumeView? {
    val window = UIApplication.sharedApplication.keyWindow ?: return null
    val view = MPVolumeView(frame = CGRectMake(-2000.0, -2000.0, 1.0, 1.0))
    window.addSubview(view)
    return view
}
