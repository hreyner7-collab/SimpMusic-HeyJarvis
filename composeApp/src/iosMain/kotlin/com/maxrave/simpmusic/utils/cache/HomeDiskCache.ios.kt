package com.maxrave.simpmusic.utils.cache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes
import platform.Foundation.writeToURL

@OptIn(ExperimentalForeignApi::class)
private fun homeCacheUrl(): NSURL? {
    val cacheDirUrl =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: NSURL.fileURLWithPath(NSTemporaryDirectory())
    return cacheDirUrl.URLByAppendingPathComponent(HOME_DISK_CACHE_FILENAME)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun readHomeDiskCacheText(): String? =
    runCatching {
        val target = homeCacheUrl() ?: return null
        val data = NSData.dataWithContentsOfURL(target) ?: return null
        val length = data.length.toInt()
        if (length <= 0) return null
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            data.getBytes(pinned.addressOf(0), length.toULong())
        }
        bytes.decodeToString()
    }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeHomeDiskCacheText(content: String) {
    runCatching {
        val target = homeCacheUrl() ?: return
        val bytes = content.encodeToByteArray()
        val data =
            if (bytes.isEmpty()) {
                NSData()
            } else {
                bytes.usePinned { pinned ->
                    NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                }
            }
        data.writeToURL(target, atomically = true)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun clearHomeDiskCacheFile() {
    runCatching {
        val target = homeCacheUrl() ?: return
        NSFileManager.defaultManager.removeItemAtURL(target, error = null)
    }
}
