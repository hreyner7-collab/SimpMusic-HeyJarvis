@file:OptIn(ExperimentalForeignApi::class)

package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.maxrave.data.mediaservice.IosAvPlayerRegistry
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * A UIView that keeps one AVPlayerLayer exactly the size of the view. Compose sets this view's
 * frame as it lays the composable out; layoutSubviews runs after every such change.
 */
private class VideoContainer(
    private val videoLayer: AVPlayerLayer,
) : UIView(frame = CGRectZero.readValue()) {
    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        layer.addSublayer(videoLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
    }
}

/**
 * A muted, looping clip drawn straight from [url]: Spotify canvases (mp4) and Apple Music animated
 * artwork (an HLS playlist, which AVPlayer reads natively). It owns its own player and never
 * touches the music player.
 */
private class LoopingClip(
    url: String,
) {
    val player = AVQueuePlayer()
    val layer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
    private var looper: AVPlayerLooper? = null

    init {
        player.volume = 0f
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            looper = AVPlayerLooper.playerLooperWithPlayer(player, templateItem = AVPlayerItem.playerItemWithURL(nsUrl))
            player.play()
        }
    }

    fun release() {
        player.pause()
        looper?.disableLooping()
        looper = null
        layer.player = null
    }

    fun hasDrawnFrame(): Boolean {
        val item = player.currentItem ?: return false
        val hasSize = item.presentationSize.useContents { width > 0.0 }
        return hasSize && CMTimeGetSeconds(player.currentTime()) > 0.05
    }
}

@Composable
actual fun MediaPlayerView(
    url: String,
    modifier: Modifier,
    cropToBounds: Boolean,
    onFirstFrame: () -> Unit,
) {
    val latestFirstFrame = rememberUpdatedState(onFirstFrame)
    val clip = remember(url) { LoopingClip(url) }
    DisposableEffect(clip) { onDispose { clip.release() } }
    LaunchedEffect(clip) {
        while (!clip.hasDrawnFrame()) delay(100)
        latestFirstFrame.value()
    }
    UIKitView(
        factory = { VideoContainer(clip.layer) },
        modifier = modifier,
        update = {
            clip.layer.videoGravity = if (cropToBounds) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
        },
    )
}

/** Width over height of the video the main player has loaded, or null while there is none. */
@Composable
actual fun rememberVideoAspectRatio(playerName: String): Float? {
    val state =
        produceState<Float?>(initialValue = null) {
            while (true) {
                val item = IosAvPlayerRegistry.player?.currentItem
                var ratio: Float? = null
                if (IosAvPlayerRegistry.isVideo.value && item != null) {
                    item.presentationSize.useContents {
                        if (width > 0.0 && height > 0.0) ratio = (width / height).toFloat()
                    }
                }
                value = ratio
                delay(500)
            }
        }
    return state.value
}

@Composable
actual fun MediaPlayerViewWithSubtitle(
    modifier: Modifier,
    playerName: String,
    shouldPip: Boolean,
    shouldShowSubtitle: Boolean,
    shouldScaleDownSubtitle: Boolean,
    isInPipMode: Boolean,
    timelineState: TimeLine,
    lyricsData: Lyrics?,
    translatedLyricsData: Lyrics?,
    mainTextStyle: TextStyle,
    translatedTextStyle: TextStyle,
) {
    val isVideo by IosAvPlayerRegistry.isVideo.collectAsState()
    val player = IosAvPlayerRegistry.player
    Box(modifier.fillMaxSize()) {
        if (isVideo && player != null) {
            val layer =
                remember(player) {
                    AVPlayerLayer.playerLayerWithPlayer(player).also { it.videoGravity = AVLayerVideoGravityResizeAspect }
                }
            DisposableEffect(layer) { onDispose { layer.player = null } }
            UIKitView(
                factory = { VideoContainer(layer) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (shouldShowSubtitle) {
            val main = currentLine(lyricsData, timelineState.current)
            val translated = currentLine(translatedLyricsData, timelineState.current)
            if (main != null) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = if (shouldScaleDownSubtitle) 8.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = main,
                        style = mainTextStyle,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (translated != null) {
                        Text(
                            text = translated,
                            style = translatedTextStyle,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** The lyric line being sung at [positionMs], or null before the first line or without lyrics. */
private fun currentLine(
    lyrics: Lyrics?,
    positionMs: Long,
): String? {
    val lines = lyrics?.lines ?: return null
    var best: String? = null
    var bestStart = Long.MIN_VALUE
    for (line in lines) {
        val start = line.startTimeMs.toLongOrNull() ?: continue
        if (start <= positionMs && start >= bestStart) {
            bestStart = start
            best = line.words
        }
    }
    return best?.takeIf { it.isNotBlank() }
}
