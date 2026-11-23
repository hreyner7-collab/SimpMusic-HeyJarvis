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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
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
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

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
        return CMTimeGetSeconds(player.currentTime()) > 0.05 && item.presentationSize.useContents { width > 0.0 }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fitLayer(
    layer: AVPlayerLayer,
    view: UIView,
) {
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    layer.frame = view.bounds
    CATransaction.commit()
}

@OptIn(ExperimentalForeignApi::class)
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
        factory = {
            UIView().also { container ->
                container.backgroundColor = UIColor.blackColor
                container.clipsToBounds = true
                container.layer.addSublayer(clip.layer)
            }
        },
        modifier = modifier,
        update = { view ->
            clip.layer.videoGravity = if (cropToBounds) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
            fitLayer(clip.layer, view)
        },
        onResize = { view, rect ->
            view.setFrame(rect)
            fitLayer(clip.layer, view)
        },
    )
}

/** Width over height of the video the main player has loaded, or null while there is none. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberVideoAspectRatio(playerName: String): Float? {
    val state =
        produceState<Float?>(initialValue = null) {
            while (true) {
                val size = IosAvPlayerRegistry.player?.currentItem?.presentationSize
                value =
                    if (IosAvPlayerRegistry.isVideo.value && size != null) {
                        size.useContents { if (width > 0.0 && height > 0.0) (width / height).toFloat() else null }
                    } else {
                        null
                    }
                delay(500)
            }
        }
    return state.value
}

@OptIn(ExperimentalForeignApi::class)
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
            val layer = remember(player) { AVPlayerLayer.playerLayerWithPlayer(player) }
            DisposableEffect(layer) { onDispose { layer.player = null } }
            UIKitView(
                factory = {
                    UIView().also { container ->
                        container.backgroundColor = UIColor.blackColor
                        layer.videoGravity = AVLayerVideoGravityResizeAspect
                        container.layer.addSublayer(layer)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onResize = { view, rect ->
                    view.setFrame(rect)
                    fitLayer(layer, view)
                },
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
