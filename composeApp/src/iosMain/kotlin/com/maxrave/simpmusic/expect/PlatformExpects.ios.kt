package com.maxrave.simpmusic.expect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIPasteboard

actual fun copyToClipboard(
    label: String,
    text: String,
) {
    UIPasteboard.generalPasteboard.string = text
}

actual fun getDownloadFolderPath(): String {
    val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    val docUrl = urls.firstOrNull() as? NSURL
    return docUrl?.path ?: ""
}

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    val controller = UIActivityViewController(activityItems = listOf(title, nsUrl), applicationActivities = null)
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    root?.presentViewController(controller, animated = true, completion = null)
}

actual fun currentOrientation(): Orientation =
    when (UIDevice.currentDevice.orientation) {
        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
        UIDeviceOrientation.UIDeviceOrientationLandscapeRight,
        -> Orientation.LANDSCAPE
        UIDeviceOrientation.UIDeviceOrientationPortrait,
        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown,
        -> Orientation.PORTRAIT
        else -> Orientation.PORTRAIT
    }

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveImageToDevice(
    bytes: ByteArray,
    fileName: String,
): Boolean =
    runCatching {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docUrl = urls.firstOrNull() as? NSURL ?: return false
        val target = docUrl.URLByAppendingPathComponent(fileName) ?: return false
        val data =
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        data.writeToURL(target, atomically = true)
    }.getOrDefault(false)

@OptIn(ExperimentalForeignApi::class)
actual suspend fun shareImage(
    bytes: ByteArray,
    fileName: String,
    chooserTitle: String,
    mimeType: String,
): Boolean =
    runCatching {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docUrl = urls.firstOrNull() as? NSURL ?: return false
        val target = docUrl.URLByAppendingPathComponent(fileName) ?: return false
        val data =
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        if (!data.writeToURL(target, atomically = true)) return false
        val controller = UIActivityViewController(activityItems = listOf(target), applicationActivities = null)
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        root?.presentViewController(controller, animated = true, completion = null)
        true
    }.getOrDefault(false)

actual fun isOnUnmeteredNetwork(): Boolean = true
