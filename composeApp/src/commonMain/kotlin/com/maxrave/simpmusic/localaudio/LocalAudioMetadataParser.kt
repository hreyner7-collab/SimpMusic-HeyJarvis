package com.maxrave.simpmusic.localaudio

/**
 * Pure-Kotlin binary audio metadata parser operating directly on header & tail byte chunks.
 *
 * Supports:
 * - MP3 ID3v2.2 / v2.3 / v2.4 (TIT2/TPE1/TALB/TPE2/TLEN, synchsafe sizes, ISO-8859-1, UTF-16 BOM/BE, UTF-8)
 * - MP3 ID3v1 (128-byte TAG trailer)
 * - FLAC STREAMINFO (exact duration from sample rate + total samples) & VORBIS_COMMENT
 * - OGG Vorbis (`\x03vorbis`) & OPUS (`OpusTags` / `OpusHead`) comments & duration
 * - WAV RIFF `fmt `, `data` (duration from byte rate), `LIST/INFO` (`INAM`/`IART`/`IPRD`), and embedded `id3 ` chunk
 * - Smart filename fallback (`"01 - Artist - Title.ext"` / `"Artist - Title.ext"`)
 */
data class ParsedAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
)

object LocalAudioMetadataParser {
    private val LEADING_TRACK_NUM_REGEX = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*""")

    fun parse(
        fileName: String,
        headerBytes: ByteArray,
        tailBytes: ByteArray = ByteArray(0),
        fileSizeBytes: Long = headerBytes.size.toLong(),
    ): ParsedAudioMetadata {
        val ext = LocalAudioConfig.normalizeExtension(fileName).uppercase().ifBlank { "AUDIO" }
        val fallback = parseFilenameFallback(fileName)

        val partial =
            when {
                hasAsciiMagic(headerBytes, 0, "ID3") -> parseId3v2(headerBytes, tailBytes)
                hasAsciiMagic(headerBytes, 0, "fLaC") -> parseFlac(headerBytes)
                hasAsciiMagic(headerBytes, 0, "OggS") -> parseOgg(headerBytes, tailBytes)
                hasAsciiMagic(headerBytes, 0, "RIFF") && hasAsciiMagic(headerBytes, 8, "WAVE") -> parseWav(headerBytes, tailBytes)
                else -> {
                    val id3v1 = parseId3v1(if (tailBytes.isNotEmpty()) tailBytes else headerBytes)
                    id3v1 ?: PartialTags()
                }
            }

        // Fill any gaps from ID3v1 trailer if present on MP3/ID3 files
        val merged =
            if (partial.title.isNullOrBlank() || partial.artist.isNullOrBlank() || partial.album.isNullOrBlank()) {
                val id3v1 = parseId3v1(if (tailBytes.size >= 128) tailBytes else headerBytes)
                if (id3v1 != null) {
                    PartialTags(
                        title = partial.title?.takeIf { it.isNotBlank() } ?: id3v1.title,
                        artist = partial.artist?.takeIf { it.isNotBlank() } ?: id3v1.artist,
                        album = partial.album?.takeIf { it.isNotBlank() } ?: id3v1.album,
                        durationMs = partial.durationMs ?: id3v1.durationMs,
                        detectedFormat = partial.detectedFormat ?: id3v1.detectedFormat,
                    )
                } else {
                    partial
                }
            } else {
                partial
            }

        val resolvedDuration =
            (merged.durationMs ?: estimateMp3DurationMs(headerBytes, fileSizeBytes))
                ?.coerceAtLeast(0L)
                ?: 0L

        return ParsedAudioMetadata(
            title = merged.title?.trim()?.takeIf { it.isNotBlank() } ?: fallback.second,
            artist = merged.artist?.trim()?.takeIf { it.isNotBlank() } ?: fallback.first,
            album = merged.album?.trim().orEmpty(),
            durationMs = resolvedDuration,
            format = merged.detectedFormat ?: ext,
        )
    }

    /**
     * Parses a filename into `(artist, title)` using common music naming conventions.
     * Examples:
     * - `"Daft Punk - Harder Better Faster Stronger.flac"` -> `("Daft Punk", "Harder Better Faster Stronger")`
     * - `"01 - Radiohead - Everything In Its Right Place.mp3"` -> `("Radiohead", "Everything In Its Right Place")`
     * - `"03. Midnight_City.wav"` -> `("Unknown Artist", "Midnight City")`
     */
    fun parseFilenameFallback(fileName: String): Pair<String, String> {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
        val withoutExt = leaf.substringBeforeLast('.', leaf).trim()
        val stripped = withoutExt.replace(LEADING_TRACK_NUM_REGEX, "").trim()
        val normalizedSpaces =
            if (!stripped.contains(' ') && stripped.contains('_')) {
                stripped.replace('_', ' ')
            } else {
                stripped
            }
        val candidate = normalizedSpaces.ifBlank { withoutExt.ifBlank { "Untitled Track" } }

        val separators = listOf(" - ", " – ", " — ")
        for (sep in separators) {
            val idx = candidate.indexOf(sep)
            if (idx > 0 && idx + sep.length < candidate.length) {
                val left = candidate.substring(0, idx).trim()
                val right = candidate.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    // Handle `"01 - Artist - Title"` if track number survived
                    val cleanedLeft = left.replace(LEADING_TRACK_NUM_REGEX, "").trim()
                    if (cleanedLeft.isNotBlank() && !cleanedLeft.all { it.isDigit() }) {
                        return cleanedLeft to right
                    }
                    // Left was purely numeric track index; recurse on right
                    val nestedIdx = right.indexOf(sep)
                    if (nestedIdx > 0 && nestedIdx + sep.length < right.length) {
                        val artist = right.substring(0, nestedIdx).trim()
                        val title = right.substring(nestedIdx + sep.length).trim()
                        if (artist.isNotBlank() && title.isNotBlank()) {
                            return artist to title
                        }
                    }
                    return LocalAudioTrack.UNKNOWN_ARTIST to right
                }
            }
        }
        return LocalAudioTrack.UNKNOWN_ARTIST to candidate
    }

    // -------------------------------------------------------------------------
    // ID3v2 (v2.2, v2.3, v2.4)
    // -------------------------------------------------------------------------

    private data class PartialTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val detectedFormat: String? = null,
    )

    private fun parseId3v2(
        bytes: ByteArray,
        tailBytes: ByteArray,
    ): PartialTags {
        if (bytes.size < 10 || !hasAsciiMagic(bytes, 0, "ID3")) return PartialTags()
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(bytes, 6)
        if (tagSize <= 0) return PartialTags(detectedFormat = "MP3")

        val tagEnd = minOf(bytes.size, 10 + tagSize)
        var pos = 10

        // Skip extended header if present (bit 6 of flags)
        if ((flags and 0x40) != 0 && pos + 4 <= tagEnd) {
            val extSize =
                if (majorVersion >= 4) {
                    readSynchsafeInt(bytes, pos)
                } else {
                    readBeInt(bytes, pos) + 4
                }
            if (extSize > 0) pos += extSize
        }

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var durationMs: Long? = null

        if (majorVersion == 2) {
            while (pos + 6 <= tagEnd) {
                if (bytes[pos] == 0.toByte()) break
                val frameId = readAscii(bytes, pos, 3)
                val frameSize = readBe24(bytes, pos + 3)
                pos += 6
                if (frameSize <= 0 || pos + frameSize > tagEnd) break
                when (frameId) {
                    "TT2" -> title = decodeId3Text(bytes, pos, frameSize)
                    "TP1" -> artist = decodeId3Text(bytes, pos, frameSize)
                    "TP2" -> albumArtist = decodeId3Text(bytes, pos, frameSize)
                    "TAL" -> album = decodeId3Text(bytes, pos, frameSize)
                    "TLE" -> durationMs = decodeId3Text(bytes, pos, frameSize)?.toLongOrNull()
                }
                pos += frameSize
            }
        } else {
            while (pos + 10 <= tagEnd) {
                if (bytes[pos] == 0.toByte()) break
                val frameId = readAscii(bytes, pos, 4)
                if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val frameSize =
                    if (majorVersion >= 4) {
                        readSynchsafeInt(bytes, pos + 4)
                    } else {
                        readBeInt(bytes, pos + 4)
                    }
                pos += 10
                if (frameSize <= 0 || pos + frameSize > tagEnd) break
                when (frameId) {
                    "TIT2" -> title = decodeId3Text(bytes, pos, frameSize)
                    "TPE1" -> artist = decodeId3Text(bytes, pos, frameSize)
                    "TPE2" -> albumArtist = decodeId3Text(bytes, pos, frameSize)
                    "TALB" -> album = decodeId3Text(bytes, pos, frameSize)
                    "TLEN" -> durationMs = decodeId3Text(bytes, pos, frameSize)?.toLongOrNull()
                }
                pos += frameSize
            }
        }

        val resolvedArtist = artist?.takeIf { it.isNotBlank() } ?: albumArtist
        val v1 = if (title.isNullOrBlank() || resolvedArtist.isNullOrBlank() || album.isNullOrBlank()) {
            parseId3v1(if (tailBytes.size >= 128) tailBytes else bytes)
        } else {
            null
        }
        return PartialTags(
            title = title?.takeIf { it.isNotBlank() } ?: v1?.title,
            artist = resolvedArtist?.takeIf { it.isNotBlank() } ?: v1?.artist,
            album = album?.takeIf { it.isNotBlank() } ?: v1?.album,
            durationMs = durationMs,
            detectedFormat = "MP3",
        )
    }

    private fun decodeId3Text(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String? {
        if (length <= 1 || offset + length > bytes.size) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val start = offset + 1
        val textLen = length - 1
        val raw =
            when (encoding) {
                0 -> decodeIso88591(bytes, start, textLen)
                1 -> decodeUtf16WithBom(bytes, start, textLen)
                2 -> decodeUtf16(bytes, start, textLen, bigEndian = true)
                3 -> bytes.decodeToString(start, start + textLen)
                else -> bytes.decodeToString(start, start + textLen)
            }
        return raw.trim('\u0000', ' ', '\t', '\r', '\n').takeIf { it.isNotEmpty() }
    }

    // -------------------------------------------------------------------------
    // ID3v1 (128-byte footer starting with "TAG")
    // -------------------------------------------------------------------------

    private fun parseId3v1(bytes: ByteArray): PartialTags? {
        if (bytes.size < 128) return null
        val base = bytes.size - 128
        if (!hasAsciiMagic(bytes, base, "TAG")) return null
        val title = decodeIso88591(bytes, base + 3, 30).trim('\u0000', ' ')
        val artist = decodeIso88591(bytes, base + 33, 30).trim('\u0000', ' ')
        val album = decodeIso88591(bytes, base + 63, 30).trim('\u0000', ' ')
        return PartialTags(
            title = title.takeIf { it.isNotEmpty() },
            artist = artist.takeIf { it.isNotEmpty() },
            album = album.takeIf { it.isNotEmpty() },
            detectedFormat = "MP3",
        )
    }

    // -------------------------------------------------------------------------
    // FLAC (STREAMINFO + VORBIS_COMMENT)
    // -------------------------------------------------------------------------

    private fun parseFlac(bytes: ByteArray): PartialTags {
        if (bytes.size < 8 || !hasAsciiMagic(bytes, 0, "fLaC")) return PartialTags()
        var pos = 4
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var durationMs: Long? = null

        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7F
            val blockLength = readBe24(bytes, pos + 1)
            pos += 4
            if (blockLength < 0 || pos + blockLength > bytes.size) break

            when (blockType) {
                0 -> {
                    // STREAMINFO: 34 bytes
                    if (blockLength >= 18) {
                        val b10 = bytes[pos + 10].toLong() and 0xFFL
                        val b11 = bytes[pos + 11].toLong() and 0xFFL
                        val b12 = bytes[pos + 12].toLong() and 0xFFL
                        val b13 = bytes[pos + 13].toLong() and 0xFFL
                        val b14 = bytes[pos + 14].toLong() and 0xFFL
                        val b15 = bytes[pos + 15].toLong() and 0xFFL
                        val b16 = bytes[pos + 16].toLong() and 0xFFL
                        val b17 = bytes[pos + 17].toLong() and 0xFFL

                        val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                        val totalSamples =
                            ((b13 and 0x0FL) shl 32) or
                                (b14 shl 24) or
                                (b15 shl 16) or
                                (b16 shl 8) or
                                b17
                        if (sampleRate > 0L && totalSamples > 0L) {
                            durationMs = (totalSamples * 1000L) / sampleRate
                        }
                    }
                }

                4 -> {
                    // VORBIS_COMMENT
                    val comments = parseVorbisCommentBlock(bytes, pos, blockLength)
                    title = comments["TITLE"] ?: title
                    artist = comments["ARTIST"] ?: artist
                    albumArtist = comments["ALBUMARTIST"] ?: albumArtist
                    album = comments["ALBUM"] ?: album
                }
            }

            pos += blockLength
            if (isLast) break
        }

        return PartialTags(
            title = title,
            artist = artist ?: albumArtist,
            album = album,
            durationMs = durationMs,
            detectedFormat = "FLAC",
        )
    }

    // -------------------------------------------------------------------------
    // OGG Vorbis / OPUS
    // -------------------------------------------------------------------------

    private fun parseOgg(
        headerBytes: ByteArray,
        tailBytes: ByteArray,
    ): PartialTags {
        var pos = 0
        var isOpus = false
        var sampleRate = 0L
        var preSkip = 0L
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var lastGranule = 0L

        while (pos + 27 <= headerBytes.size) {
            if (!hasAsciiMagic(headerBytes, pos, "OggS")) break
            val granule = readLeLong(headerBytes, pos + 6)
            if (granule > 0L) lastGranule = granule
            val pageSegments = headerBytes[pos + 26].toInt() and 0xFF
            val tableStart = pos + 27
            val bodyStart = tableStart + pageSegments
            if (bodyStart > headerBytes.size) break
            var bodyLen = 0
            for (i in 0 until pageSegments) {
                bodyLen += headerBytes[tableStart + i].toInt() and 0xFF
            }
            val bodyEnd = minOf(headerBytes.size, bodyStart + bodyLen)
            val availLen = bodyEnd - bodyStart

            if (availLen >= 8 && hasAsciiMagic(headerBytes, bodyStart, "OpusHead")) {
                isOpus = true
                sampleRate = 48_000L
                if (availLen >= 12) {
                    preSkip = readLeShort(headerBytes, bodyStart + 10).toLong()
                }
            } else if (availLen >= 8 && hasAsciiMagic(headerBytes, bodyStart, "OpusTags")) {
                isOpus = true
                val comments = parseVorbisCommentBlock(headerBytes, bodyStart + 8, availLen - 8)
                title = comments["TITLE"] ?: title
                artist = comments["ARTIST"] ?: artist
                albumArtist = comments["ALBUMARTIST"] ?: albumArtist
                album = comments["ALBUM"] ?: album
            } else if (availLen >= 16 && headerBytes[bodyStart] == 1.toByte() && hasAsciiMagic(headerBytes, bodyStart + 1, "vorbis")) {
                sampleRate = readLeInt(headerBytes, bodyStart + 12).toLong() and 0xFFFFFFFFL
            } else if (availLen >= 7 && headerBytes[bodyStart] == 3.toByte() && hasAsciiMagic(headerBytes, bodyStart + 1, "vorbis")) {
                val comments = parseVorbisCommentBlock(headerBytes, bodyStart + 7, availLen - 7)
                title = comments["TITLE"] ?: title
                artist = comments["ARTIST"] ?: artist
                albumArtist = comments["ALBUMARTIST"] ?: albumArtist
                album = comments["ALBUM"] ?: album
            }

            pos = bodyStart + bodyLen
        }

        // Scan tail bytes backwards for the final OggS page granule position to calculate duration
        val tailGranule = findLastOggGranule(tailBytes)
        val bestGranule = maxOf(lastGranule, tailGranule)
        val durationMs =
            if (sampleRate > 0L && bestGranule > preSkip) {
                ((bestGranule - preSkip) * 1000L) / sampleRate
            } else {
                null
            }

        return PartialTags(
            title = title,
            artist = artist ?: albumArtist,
            album = album,
            durationMs = durationMs,
            detectedFormat = if (isOpus) "OPUS" else "OGG",
        )
    }

    private fun findLastOggGranule(bytes: ByteArray): Long {
        if (bytes.size < 27) return 0L
        for (i in bytes.size - 27 downTo 0) {
            if (hasAsciiMagic(bytes, i, "OggS")) {
                val g = readLeLong(bytes, i + 6)
                if (g > 0L) return g
            }
        }
        return 0L
    }

    private fun parseVorbisCommentBlock(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Map<String, String> {
        val end = minOf(bytes.size, offset + length)
        var pos = offset
        if (pos + 4 > end) return emptyMap()
        val vendorLen = readLeInt(bytes, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen + 4 > end) return emptyMap()
        pos += vendorLen

        val commentCount = readLeInt(bytes, pos)
        pos += 4
        if (commentCount <= 0 || commentCount > 4096) return emptyMap()

        val result = LinkedHashMap<String, String>()
        for (i in 0 until commentCount) {
            if (pos + 4 > end) break
            val commentLen = readLeInt(bytes, pos)
            pos += 4
            if (commentLen < 0 || pos + commentLen > end) break
            val entry = bytes.decodeToString(pos, pos + commentLen)
            pos += commentLen
            val eqIdx = entry.indexOf('=')
            if (eqIdx > 0) {
                val key = entry.substring(0, eqIdx).trim().uppercase()
                val value = entry.substring(eqIdx + 1).trim()
                if (value.isNotEmpty() && key !in result) {
                    result[key] = value
                }
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // WAV RIFF / LIST-INFO
    // -------------------------------------------------------------------------

    private fun parseWav(
        headerBytes: ByteArray,
        tailBytes: ByteArray,
    ): PartialTags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var byteRate = 0L
        var dataSize = 0L

        fun scanChunks(
            chunkData: ByteArray,
            startOffset: Int,
        ) {
            var pos = startOffset
            while (pos + 8 <= chunkData.size) {
                val chunkId = readAscii(chunkData, pos, 4)
                val chunkSize = readLeInt(chunkData, pos + 4).toLong() and 0xFFFFFFFFL
                val payloadStart = pos + 8
                if (chunkSize > Int.MAX_VALUE) break
                val sizeInt = chunkSize.toInt()

                when (chunkId) {
                    "fmt " -> {
                        if (payloadStart + 12 <= chunkData.size && sizeInt >= 12) {
                            byteRate = readLeInt(chunkData, payloadStart + 8).toLong() and 0xFFFFFFFFL
                        }
                    }

                    "data" -> {
                        dataSize = chunkSize
                    }

                    "LIST" -> {
                        if (payloadStart + 4 <= chunkData.size && hasAsciiMagic(chunkData, payloadStart, "INFO")) {
                            val listEnd = minOf(chunkData.size, payloadStart + sizeInt)
                            var infoPos = payloadStart + 4
                            while (infoPos + 8 <= listEnd) {
                                val infoId = readAscii(chunkData, infoPos, 4)
                                val infoLen = readLeInt(chunkData, infoPos + 4)
                                val infoPayload = infoPos + 8
                                if (infoLen < 0 || infoPayload + infoLen > listEnd) break
                                val text =
                                    chunkData
                                        .decodeToString(infoPayload, infoPayload + infoLen)
                                        .trim('\u0000', ' ', '\t', '\r', '\n')
                                        .takeIf { it.isNotEmpty() }
                                when (infoId) {
                                    "INAM" -> if (title == null) title = text
                                    "IART" -> if (artist == null) artist = text
                                    "IPRD" -> if (album == null) album = text
                                }
                                infoPos = infoPayload + infoLen + (infoLen and 1)
                            }
                        }
                    }

                    "id3 ", "ID3 " -> {
                        if (payloadStart + sizeInt <= chunkData.size) {
                            val embedded = parseId3v2(chunkData.copyOfRange(payloadStart, payloadStart + sizeInt), ByteArray(0))
                            if (title == null) title = embedded.title
                            if (artist == null) artist = embedded.artist
                            if (album == null) album = embedded.album
                        }
                    }
                }

                val paddedSize = chunkSize + (chunkSize and 1L)
                val nextPos = payloadStart.toLong() + paddedSize
                if (nextPos <= pos || nextPos > chunkData.size) break
                pos = nextPos.toInt()
            }
        }

        scanChunks(headerBytes, 12)

        // Many WAV encoders append LIST/INFO at the very end of the file (inside tailBytes).
        if ((title == null || artist == null || album == null) && tailBytes.size >= 16) {
            val listIdx = findAsciiIndex(tailBytes, "LIST")
            if (listIdx >= 0) {
                scanChunks(tailBytes, listIdx)
            }
        }

        val durationMs =
            if (byteRate > 0L && dataSize > 0L) {
                (dataSize * 1000L) / byteRate
            } else {
                null
            }

        return PartialTags(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            detectedFormat = "WAV",
        )
    }

    // -------------------------------------------------------------------------
    // MP3 bitrate fallback duration estimate
    // -------------------------------------------------------------------------

    private val MPEG1_LAYER3_BITRATES =
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

    private fun estimateMp3DurationMs(
        headerBytes: ByteArray,
        fileSizeBytes: Long,
    ): Long? {
        if (fileSizeBytes <= 0L || headerBytes.size < 4) return null
        val audioStart =
            if (hasAsciiMagic(headerBytes, 0, "ID3") && headerBytes.size >= 10) {
                10 + readSynchsafeInt(headerBytes, 6)
            } else {
                0
            }
        if (audioStart >= headerBytes.size - 4) return null
        // Locate first MPEG-1 Layer III frame sync (0xFF 0xFB / 0xFA / 0xF3 / 0xF2)
        val searchLimit = minOf(headerBytes.size - 4, audioStart + 4096)
        for (i in audioStart..searchLimit) {
            val b0 = headerBytes[i].toInt() and 0xFF
            val b1 = headerBytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val versionBits = (b1 ushr 3) and 0x03
                val layerBits = (b1 ushr 1) and 0x03
                if (versionBits == 3 && layerBits == 1) {
                    val b2 = headerBytes[i + 2].toInt() and 0xFF
                    val bitrateIdx = (b2 ushr 4) and 0x0F
                    val kbps = MPEG1_LAYER3_BITRATES.getOrElse(bitrateIdx) { 0 }
                    if (kbps > 0) {
                        val audioBytes = (fileSizeBytes - audioStart).coerceAtLeast(0L)
                        return (audioBytes * 8L) / kbps
                    }
                }
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Low-level binary helpers
    // -------------------------------------------------------------------------

    private fun hasAsciiMagic(
        bytes: ByteArray,
        offset: Int,
        magic: String,
    ): Boolean {
        if (offset < 0 || offset + magic.length > bytes.size) return false
        for (i in magic.indices) {
            if ((bytes[offset + i].toInt() and 0xFF) != magic[i].code) return false
        }
        return true
    }

    private fun findAsciiIndex(
        bytes: ByteArray,
        needle: String,
    ): Int {
        if (needle.isEmpty() || bytes.size < needle.length) return -1
        for (i in 0..bytes.size - needle.length) {
            if (hasAsciiMagic(bytes, i, needle)) return i
        }
        return -1
    }

    private fun readAscii(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append((bytes[offset + i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    private fun readSynchsafeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun readBeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readBe24(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun readLeShort(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLeLong(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return value
    }

    private fun decodeIso88591(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = (bytes[offset + i].toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    private fun decodeUtf16WithBom(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        if (length < 2) return ""
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return when {
            b0 == 0xFE && b1 == 0xFF -> decodeUtf16(bytes, offset + 2, length - 2, bigEndian = true)
            b0 == 0xFF && b1 == 0xFE -> decodeUtf16(bytes, offset + 2, length - 2, bigEndian = false)
            else -> decodeUtf16(bytes, offset, length, bigEndian = true)
        }
    }

    private fun decodeUtf16(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        bigEndian: Boolean,
    ): String {
        val pairs = length / 2
        val sb = StringBuilder(pairs)
        for (i in 0 until pairs) {
            val idx = offset + i * 2
            val hi = bytes[idx].toInt() and 0xFF
            val lo = bytes[idx + 1].toInt() and 0xFF
            val codeUnit = if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi
            if (codeUnit == 0) break
            sb.append(codeUnit.toChar())
        }
        return sb.toString()
    }
}
