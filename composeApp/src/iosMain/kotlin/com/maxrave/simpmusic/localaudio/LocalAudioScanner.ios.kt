package com.maxrave.simpmusic.localaudio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

actual object LocalAudioScanner {
    private const val HEADER_READ_BYTES: ULong = 65_536uL
    private const val TAIL_READ_BYTES: ULong = 4_096uL

    actual fun defaultFolders(): List<String> {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docPath = (urls.firstOrNull() as? NSURL)?.path ?: return emptyList()
        return listOf(docPath)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun scan(watchedFolders: List<String>): LocalAudioScanResult =
        withContext(Dispatchers.IO) {
            val fm = NSFileManager.defaultManager
            val roots =
                watchedFolders
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { defaultFolders() }

            val found = LinkedHashMap<String, LocalAudioTrack>()

            for (rootPath in roots) {
                val subpaths = fm.subpathsAtPath(rootPath) ?: continue
                for (entry in subpaths) {
                    if (found.size >= LocalAudioConfig.MAX_SCAN_TRACKS) break
                    val relPath = entry as? String ?: continue
                    val fileName = relPath.substringAfterLast('/')
                    if (!LocalAudioConfig.isSupportedAudioFile(fileName)) continue

                    val fullPath = if (rootPath.endsWith("/")) "$rootPath$relPath" else "$rootPath/$relPath"
                    val attrs = fm.attributesOfItemAtPath(fullPath, error = null) ?: continue
                    val fileType = attrs[NSFileType] as? String
                    if (fileType != NSFileTypeRegular) continue

                    val fileSize = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
                    val modifiedDate = attrs[NSFileModificationDate] as? NSDate
                    val dateAddedMs = modifiedDate?.let { (it.timeIntervalSince1970 * 1000.0).toLong() } ?: 0L

                    val id = LocalAudioConfig.stableTrackId(fullPath)
                    if (id in found) continue

                    val (header, tail) = readHeaderAndTail(fullPath, fileSize)
                    val parsed =
                        LocalAudioMetadataParser.parse(
                            fileName = fileName,
                            headerBytes = header,
                            tailBytes = tail,
                            fileSizeBytes = fileSize,
                        )
                    val fileUri = NSURL.fileURLWithPath(fullPath).absoluteString ?: "file://$fullPath"
                    found[id] =
                        LocalAudioTrack(
                            id = id,
                            uri = fileUri,
                            filePath = fullPath,
                            title = parsed.title,
                            artist = parsed.artist,
                            album = parsed.album,
                            durationMs = parsed.durationMs,
                            fileSizeBytes = fileSize,
                            format = parsed.format,
                            dateAddedMs = dateAddedMs,
                            artworkPath = null,
                        )
                }
            }

            val sorted =
                found.values.sortedWith(
                    compareBy<LocalAudioTrack> { it.artist.lowercase() }
                        .thenBy { it.album.lowercase() }
                        .thenBy { it.title.lowercase() },
                )
            LocalAudioScanResult(tracks = sorted)
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun readHeaderAndTail(
        path: String,
        fileSize: Long,
    ): Pair<ByteArray, ByteArray> {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0) to ByteArray(0)
        return try {
            val header = handle.readDataOfLength(HEADER_READ_BYTES).toByteArray()
            val tail =
                if (fileSize > HEADER_READ_BYTES.toLong()) {
                    val tailOffset = (fileSize - TAIL_READ_BYTES.toLong()).coerceAtLeast(0L).toULong()
                    handle.seekToFileOffset(tailOffset)
                    handle.readDataOfLength(TAIL_READ_BYTES).toByteArray()
                } else {
                    ByteArray(0)
                }
            header to tail
        } catch (_: Throwable) {
            ByteArray(0) to ByteArray(0)
        } finally {
            runCatching { handle.closeFile() }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val len = length.toInt()
        if (len <= 0) return ByteArray(0)
        val ptr = bytes ?: return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), ptr, length)
        }
        return out
    }
}
