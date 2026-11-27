package com.maxrave.simpmusic.localaudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

actual object LocalAudioScanner {
    private const val HEADER_READ_BYTES = 65_536
    private const val TAIL_READ_BYTES = 4_096
    private const val MAX_WALK_DEPTH = 10

    actual fun defaultFolders(): List<String> =
        listOf(File(System.getProperty("user.home"), "Music").absolutePath)

    actual suspend fun scan(watchedFolders: List<String>): LocalAudioScanResult =
        withContext(Dispatchers.IO) {
            val roots =
                watchedFolders
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { defaultFolders() }

            val found = LinkedHashMap<String, LocalAudioTrack>()
            val warnings = mutableListOf<String>()

            for (folderPath in roots) {
                val rootDir = File(folderPath)
                if (!rootDir.exists() || !rootDir.isDirectory) {
                    warnings += "Folder not found: $folderPath"
                    continue
                }

                runCatching {
                    rootDir
                        .walkTopDown()
                        .maxDepth(MAX_WALK_DEPTH)
                        .onFail { _, _ -> }
                        .filter { it.isFile && LocalAudioConfig.isSupportedAudioFile(it.name) }
                        .forEach { file ->
                            if (found.size >= LocalAudioConfig.MAX_SCAN_TRACKS) return@forEach
                            val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                            val id = LocalAudioConfig.stableTrackId(canonicalPath)
                            if (id in found) return@forEach

                            val (header, tail) = readHeaderAndTail(file)
                            val parsed =
                                LocalAudioMetadataParser.parse(
                                    fileName = file.name,
                                    headerBytes = header,
                                    tailBytes = tail,
                                    fileSizeBytes = file.length(),
                                )
                            val coverArt = findSiblingArtwork(file.parentFile)
                            found[id] =
                                LocalAudioTrack(
                                    id = id,
                                    uri = file.toURI().toString(),
                                    filePath = canonicalPath,
                                    title = parsed.title,
                                    artist = parsed.artist,
                                    album = parsed.album,
                                    durationMs = parsed.durationMs,
                                    fileSizeBytes = file.length(),
                                    format = parsed.format,
                                    dateAddedMs = file.lastModified(),
                                    artworkPath = coverArt,
                                )
                        }
                }.onFailure { err ->
                    warnings += err.message ?: "Failed to scan $folderPath"
                }
            }

            val sorted =
                found.values.sortedWith(
                    compareBy<LocalAudioTrack> { it.artist.lowercase() }
                        .thenBy { it.album.lowercase() }
                        .thenBy { it.title.lowercase() },
                )

            LocalAudioScanResult(
                tracks = sorted,
                errorMessage = if (sorted.isEmpty() && warnings.isNotEmpty()) warnings.first() else null,
            )
        }

    private fun readHeaderAndTail(file: File): Pair<ByteArray, ByteArray> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val headerSize = minOf(len, HEADER_READ_BYTES.toLong()).toInt()
                val header = ByteArray(headerSize)
                if (headerSize > 0) raf.readFully(header)

                val tail =
                    if (len > HEADER_READ_BYTES) {
                        val tailSize = minOf(len, TAIL_READ_BYTES.toLong()).toInt()
                        val buf = ByteArray(tailSize)
                        raf.seek(len - tailSize)
                        raf.readFully(buf)
                        buf
                    } else {
                        ByteArray(0)
                    }
                header to tail
            }
        }.getOrDefault(ByteArray(0) to ByteArray(0))

    private val ARTWORK_FILENAMES = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "front.jpg")

    private fun findSiblingArtwork(dir: File?): String? {
        if (dir == null || !dir.isDirectory) return null
        for (name in ARTWORK_FILENAMES) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate.toURI().toString()
        }
        return null
    }
}
