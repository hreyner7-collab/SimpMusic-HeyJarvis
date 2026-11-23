@file:OptIn(ExperimentalForeignApi::class)

package com.maxrave.simpmusic.expect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNumber
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.subdataWithRange
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

actual fun platformOsName(): String = "iOS"

// One process-wide recursive lock. The callers only guard short in-memory cache reads and writes,
// so a single lock is cheap, and recursion keeps a nested platformSynchronized from deadlocking.
private val globalLock = NSRecursiveLock()

actual fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R {
    globalLock.lock()
    try {
        return block()
    } finally {
        globalLock.unlock()
    }
}

@OptIn(ExperimentalTime::class)
actual fun platformCurrentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val nanoOrigin = TimeSource.Monotonic.markNow()

actual fun platformNanoTime(): Long = nanoOrigin.elapsedNow().inWholeNanoseconds

actual fun platformIsRtl(text: String): Boolean =
    text.any { ch ->
        val code = ch.code
        (code in 0x0590..0x08FF) || (code in 0xFB1D..0xFDFF) || (code in 0xFE70..0xFEFF)
    }

actual fun platformFormatEpochMillis(epochMillis: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    formatter.timeZone = NSTimeZone.localTimeZone
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

actual fun platformFileLength(path: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
    return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun platformReadText(path: String): String =
    NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String? ?: ""

actual fun platformReadBytesRange(
    path: String,
    from: Long,
): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: return ByteArray(0)
    val total = data.length.toLong()
    if (total <= from) return ByteArray(0)
    val slice = data.subdataWithRange(NSMakeRange(from.toULong(), (total - from).toULong()))
    val bytes = ByteArray(slice.length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), slice.bytes, slice.length) }
    }
    return bytes
}

actual fun platformWriteBytes(
    path: String,
    bytes: ByteArray?,
) {
    if (bytes == null) return
    val data =
        if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
    data.writeToFile(path, atomically = true)
}
