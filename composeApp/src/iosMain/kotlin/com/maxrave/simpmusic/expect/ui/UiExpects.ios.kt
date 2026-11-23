package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import coil3.Image
import coil3.toBitmap
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL

actual fun ImageBitmap.toByteArray(): ByteArray? =
    runCatching {
        SkiaImage.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, 90)?.bytes
    }.getOrNull()

actual fun ImageBitmap.toPngByteArray(): ByteArray? =
    runCatching {
        SkiaImage.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG, 100)?.bytes
    }.getOrNull()

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

actual fun ImageBitmap.toReadableBitmap(
    width: Int,
    height: Int,
): ImageBitmap = this

@Composable
actual fun PlatformCastButton(
    modifier: Modifier,
    tint: Color,
) {}

actual fun isPlatformCastAvailable(): Boolean = false

@Composable
actual fun rememberCastReceivers(discover: Boolean): CastReceivers =
    remember {
        CastReceivers(receivers = emptyList(), connect = {}, disconnect = {})
    }

@Composable
actual fun DiscordWebView(
    state: MutableState<WebViewState>,
    aboveContent: @Composable (BoxScope.() -> Unit),
    onLoginDone: (token: String) -> Unit,
) {
    Box { aboveContent() }
}

@Composable
actual fun filePickerResult(onResultUri: (String?) -> Unit): FilePickerLauncher =
    remember {
        object : FilePickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }

@Composable
actual fun fileSaverResult(
    fileName: String,
    mimeType: String,
    onResultUri: (String?) -> Unit,
): FilePickerLauncher =
    remember {
        object : FilePickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
actual suspend fun persistPickedImage(
    bytes: ByteArray,
    fileName: String,
): String? =
    runCatching {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docUrl = urls.firstOrNull() as? NSURL ?: return null
        val target = docUrl.URLByAppendingPathComponent(fileName) ?: return null
        val data =
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        if (data.writeToURL(target, atomically = true)) target.absoluteString else null
    }.getOrNull()

@Composable
actual fun JerrySettingsSheet(onDismiss: () -> Unit) {}

@Composable
actual fun LibrarySyncDialog(onDismiss: () -> Unit) {}

actual fun restartAfterSyncedSettings() {}

@Composable
actual fun rememberLocalNetworkPermission(): LocalNetworkPermissionRequester =
    remember {
        object : LocalNetworkPermissionRequester {
            override fun requestIfNeeded(url: String) {}
        }
    }

@Composable
actual fun LoginSyncDialog(onDismiss: () -> Unit) {}

@Composable
actual fun MediaPlayerView(
    url: String,
    modifier: Modifier,
    cropToBounds: Boolean,
    onFirstFrame: () -> Unit,
) {
    Box(modifier)
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
    Box(modifier)
}

@Composable
actual fun rememberVideoAspectRatio(playerName: String): Float? = null

@Composable
actual fun openEqResult(audioSessionId: Int): OpenEqLauncher =
    remember {
        object : OpenEqLauncher {
            override fun launch() {}
        }
    }

@Composable
actual fun photoPickerResult(onResultUri: (String?) -> Unit): PhotoPickerLauncher =
    remember {
        object : PhotoPickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {}

actual fun isLyricsBlurSupported(): Boolean = true

@Composable
actual fun platformDynamicColorScheme(isDark: Boolean): ColorScheme? = null

actual fun isWallpaperDynamicColorSupported(): Boolean = false

@Composable
actual fun SystemBarAppearanceEffect(isDark: Boolean) {}

@Composable
actual fun rememberSaveImagePermission(onResult: (granted: Boolean) -> Unit): SaveImagePermissionRequester =
    remember {
        object : SaveImagePermissionRequester {
            override fun requestIfNeeded() {
                onResult(true)
            }
        }
    }

@Composable
actual fun HorizontalScrollBar(
    modifier: Modifier,
    scrollState: LazyListState,
    flingBehavior: FlingBehavior?,
) {}

@Composable
actual fun HorizontalScrollBar(
    modifier: Modifier,
    scrollState: LazyGridState,
    flingBehavior: FlingBehavior?,
) {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun HorizontalScrollBar(
    modifier: Modifier,
    scrollState: CarouselState,
    itemCount: Int,
) {}

@Composable
actual fun VerticalScrollBar(
    scrollState: LazyListState,
    modifier: Modifier,
) {}

@Composable
actual fun VerticalScrollBar(
    scrollState: LazyGridState,
    modifier: Modifier,
) {}

@Composable
actual fun VerticalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier,
) {}

@Composable
actual fun SongRecognitionSheet(
    onFind: (String) -> Unit,
    onDismiss: () -> Unit,
) {}
