package com.maxrave.simpmusic.portability

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToURL
import platform.posix.memcpy

actual object PortableFileIo {
    actual fun defaultExportDirectory(): String {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docPath = (urls.firstOrNull() as? NSURL)?.path ?: ""
        return if (docPath.isBlank()) "" else "$docPath/SimpMusic"
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun writeTextFile(
        fileName: String,
        content: String,
        directory: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dirPath = directory?.takeIf { it.isNotBlank() } ?: defaultExportDirectory()
                check(dirPath.isNotBlank()) { "iOS Documents directory unavailable" }
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path = dirPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
                val targetPath = if (dirPath.endsWith("/")) "$dirPath$fileName" else "$dirPath/$fileName"
                val targetUrl = NSURL.fileURLWithPath(targetPath)
                val bytes = content.encodeToByteArray()
                val data =
                    if (bytes.isEmpty()) {
                        NSData()
                    } else {
                        bytes.usePinned { pinned ->
                            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                        }
                    }
                check(data.writeToURL(targetUrl, atomically = true)) { "Failed to write $targetPath" }
                targetPath
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun readTextFile(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = path.trim()
                val data = NSData.dataWithContentsOfFile(trimmed) ?: error("File not found: $trimmed")
                val len = data.length.toInt()
                if (len <= 0) return@runCatching ""
                val ptr = data.bytes ?: return@runCatching ""
                val out = ByteArray(len)
                out.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), ptr, data.length)
                }
                out.decodeToString()
            }
        }
}
