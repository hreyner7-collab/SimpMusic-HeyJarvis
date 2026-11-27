package com.maxrave.simpmusic.localaudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAudioMetadataParserTest {
    @Test
    fun parsesId3v24Utf8AndSynchsafeSizes() {
        val frames =
            buildId3v24Frame("TIT2", encoding = 3, textBytes = "Instant Crush".encodeToByteArray()) +
                buildId3v24Frame("TPE1", encoding = 3, textBytes = "Daft Punk".encodeToByteArray()) +
                buildId3v24Frame("TALB", encoding = 3, textBytes = "Random Access Memories".encodeToByteArray()) +
                buildId3v24Frame("TLEN", encoding = 3, textBytes = "337000".encodeToByteArray())
        val header = buildId3Header(majorVersion = 4, bodySize = frames.size) + frames

        val parsed = LocalAudioMetadataParser.parse("track01.mp3", header)
        assertEquals("Instant Crush", parsed.title)
        assertEquals("Daft Punk", parsed.artist)
        assertEquals("Random Access Memories", parsed.album)
        assertEquals(337_000L, parsed.durationMs)
        assertEquals("MP3", parsed.format)
    }

    @Test
    fun parsesId3v23Utf16WithBomAndIso88591() {
        val utf16LeTitle = encodeUtf16LeWithBom("Café Del Mar")
        val isoArtist = "Energy 52".map { it.code.toByte() }.toByteArray()
        val utf16BeAlbum = encodeUtf16BeWithBom("Ibiza Classics")

        val frames =
            buildId3v23Frame("TIT2", encoding = 1, textBytes = utf16LeTitle) +
                buildId3v23Frame("TPE1", encoding = 0, textBytes = isoArtist) +
                buildId3v23Frame("TALB", encoding = 1, textBytes = utf16BeAlbum)
        val header = buildId3Header(majorVersion = 3, bodySize = frames.size) + frames

        val parsed = LocalAudioMetadataParser.parse("unknown.mp3", header)
        assertEquals("Café Del Mar", parsed.title)
        assertEquals("Energy 52", parsed.artist)
        assertEquals("Ibiza Classics", parsed.album)
        assertEquals("MP3", parsed.format)
    }

    @Test
    fun parsesId3v1FooterAndMergesMissingFields() {
        val v1 = ByteArray(128)
        "TAG".encodeToByteArray().copyInto(v1, 0)
        "Paranoid Android".encodeToByteArray().copyInto(v1, 3)
        "Radiohead".encodeToByteArray().copyInto(v1, 33)
        "OK Computer".encodeToByteArray().copyInto(v1, 63)

        val parsed =
            LocalAudioMetadataParser.parse(
                fileName = "02.mp3",
                headerBytes = ByteArray(64),
                tailBytes = v1,
            )
        assertEquals("Paranoid Android", parsed.title)
        assertEquals("Radiohead", parsed.artist)
        assertEquals("OK Computer", parsed.album)
        assertEquals("MP3", parsed.format)
    }

    @Test
    fun parsesFlacStreamInfoDurationAndVorbisComments() {
        // STREAMINFO (34 bytes): sampleRate = 44100 (0x0AC44), totalSamples = 44100 * 215 = 9_481_500
        val streamInfo = ByteArray(34)
        val sampleRate = 44_100L
        val totalSamples = 44_100L * 215L
        streamInfo[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        streamInfo[12] = ((sampleRate and 0x0F) shl 4).toByte()
        streamInfo[13] = ((totalSamples ushr 32) and 0x0F).toByte()
        streamInfo[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        streamInfo[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        streamInfo[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        streamInfo[17] = (totalSamples and 0xFF).toByte()

        val vorbis =
            buildVorbisCommentBlock(
                vendor = "reference libFLAC",
                comments =
                    listOf(
                        "TITLE=Time",
                        "ARTIST=Pink Floyd",
                        "ALBUM=The Dark Side of the Moon",
                    ),
            )

        val flac =
            "fLaC".encodeToByteArray() +
                buildFlacBlock(isLast = false, type = 0, payload = streamInfo) +
                buildFlacBlock(isLast = true, type = 4, payload = vorbis)

        val parsed = LocalAudioMetadataParser.parse("04.flac", flac)
        assertEquals("Time", parsed.title)
        assertEquals("Pink Floyd", parsed.artist)
        assertEquals("The Dark Side of the Moon", parsed.album)
        assertEquals(215_000L, parsed.durationMs)
        assertEquals("FLAC", parsed.format)
    }

    @Test
    fun parsesOpusOggTagsAndDurationFromTailGranule() {
        val opusHead =
            ByteArray(19).apply {
                "OpusHead".encodeToByteArray().copyInto(this, 0)
                this[8] = 1 // version
                this[9] = 2 // channels
                // pre-skip = 312 (LE16)
                this[10] = (312 and 0xFF).toByte()
                this[11] = ((312 ushr 8) and 0xFF).toByte()
            }
        val opusTags =
            "OpusTags".encodeToByteArray() +
                buildVorbisCommentBlock(
                    vendor = "libopus",
                    comments = listOf("title=Midnight City", "artist=M83", "album=Hurry Up, We're Dreaming"),
                )
        val header =
            buildOggPage(granule = 0L, payload = opusHead) +
                buildOggPage(granule = 0L, payload = opusTags)
        // 244 seconds at 48 kHz + 312 pre-skip
        val tail = buildOggPage(granule = 48_000L * 244L + 312L, payload = ByteArray(16))

        val parsed = LocalAudioMetadataParser.parse("track.opus", header, tail)
        assertEquals("Midnight City", parsed.title)
        assertEquals("M83", parsed.artist)
        assertEquals("Hurry Up, We're Dreaming", parsed.album)
        assertEquals(244_000L, parsed.durationMs)
        assertEquals("OPUS", parsed.format)
    }

    @Test
    fun parsesWavRiffListInfoAndDuration() {
        // 44.1kHz stereo 16-bit PCM -> byteRate = 176400 B/s, data = 176400 * 10 = 10s
        val fmtChunk =
            ByteArray(16).apply {
                writeLe32(this, 8, 176_400)
            }
        val infoListPayload =
            "INFO".encodeToByteArray() +
                buildWavSubchunk("INAM", "Take Five\u0000".encodeToByteArray()) +
                buildWavSubchunk("IART", "Dave Brubeck\u0000".encodeToByteArray()) +
                buildWavSubchunk("IPRD", "Time Out\u0000".encodeToByteArray())

        val body =
            "WAVE".encodeToByteArray() +
                buildWavSubchunk("fmt ", fmtChunk) +
                buildWavSubchunk("LIST", infoListPayload) +
                buildWavSubchunk("data", ByteArray(0), declaredSize = 1_764_000)
        val wavHeader = "RIFF".encodeToByteArray() + le32(body.size) + body

        val parsed = LocalAudioMetadataParser.parse("jazz.wav", wavHeader)
        assertEquals("Take Five", parsed.title)
        assertEquals("Dave Brubeck", parsed.artist)
        assertEquals("Time Out", parsed.album)
        assertEquals(10_000L, parsed.durationMs)
        assertEquals("WAV", parsed.format)
    }

    @Test
    fun smartFilenameFallbackHandlesCommonConventions() {
        assertEquals("Daft Punk" to "Get Lucky", LocalAudioMetadataParser.parseFilenameFallback("Daft Punk - Get Lucky.flac"))
        assertEquals("Radiohead" to "Subterranean Homesick Alien", LocalAudioMetadataParser.parseFilenameFallback("03 - Radiohead - Subterranean Homesick Alien.mp3"))
        assertEquals("Unknown Artist" to "Ambient Rain Loop", LocalAudioMetadataParser.parseFilenameFallback("07. Ambient_Rain_Loop.wav"))

        val parsed = LocalAudioMetadataParser.parse("Massive Attack - Teardrop.m4a", ByteArray(0))
        assertEquals("Teardrop", parsed.title)
        assertEquals("Massive Attack", parsed.artist)
        assertEquals("M4A", parsed.format)
    }

    @Test
    fun localAudioTrackConvertsToDomainTrackAndSerializes() {
        val local =
            LocalAudioTrack(
                id = "abc123",
                uri = "file:///Music/track.flac",
                filePath = "/Music/track.flac",
                title = "Nude",
                artist = "Radiohead",
                album = "In Rainbows",
                durationMs = 255_000L,
                fileSizeBytes = 28_000_000L,
                format = "FLAC",
                dateAddedMs = 1_700_000_000_000L,
            )
        val domainTrack = local.toTrack()
        assertEquals("local:abc123", domainTrack.videoId)
        assertEquals("Nude", domainTrack.title)
        assertEquals("Radiohead", domainTrack.artists?.firstOrNull()?.name)
        assertEquals("In Rainbows", domainTrack.album?.name)
        assertEquals(255, domainTrack.durationSeconds)
        assertEquals("4:15", domainTrack.duration)

        val state = LocalAudioLibraryState(watchedFolders = listOf("/Music"), tracks = listOf(local), isScanning = true, lastScanAtMs = 999L)
        val decoded = LocalAudioConfig.decodeState(LocalAudioConfig.encodeState(state))
        assertEquals(1, decoded.tracks.size)
        assertEquals("Nude", decoded.tracks.first().title)
        assertTrue(!decoded.isScanning)
    }

    // -------------------------------------------------------------------------
    // Binary fixture builders
    // -------------------------------------------------------------------------

    private fun buildId3Header(
        majorVersion: Int,
        bodySize: Int,
    ): ByteArray =
        byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            majorVersion.toByte(),
            0,
            0,
            ((bodySize ushr 21) and 0x7F).toByte(),
            ((bodySize ushr 14) and 0x7F).toByte(),
            ((bodySize ushr 7) and 0x7F).toByte(),
            (bodySize and 0x7F).toByte(),
        )

    private fun buildId3v24Frame(
        id: String,
        encoding: Int,
        textBytes: ByteArray,
    ): ByteArray {
        val size = 1 + textBytes.size
        val header =
            id.encodeToByteArray() +
                byteArrayOf(
                    ((size ushr 21) and 0x7F).toByte(),
                    ((size ushr 14) and 0x7F).toByte(),
                    ((size ushr 7) and 0x7F).toByte(),
                    (size and 0x7F).toByte(),
                    0,
                    0,
                )
        return header + byteArrayOf(encoding.toByte()) + textBytes
    }

    private fun buildId3v23Frame(
        id: String,
        encoding: Int,
        textBytes: ByteArray,
    ): ByteArray {
        val size = 1 + textBytes.size
        val header =
            id.encodeToByteArray() +
                byteArrayOf(
                    ((size ushr 24) and 0xFF).toByte(),
                    ((size ushr 16) and 0xFF).toByte(),
                    ((size ushr 8) and 0xFF).toByte(),
                    (size and 0xFF).toByte(),
                    0,
                    0,
                )
        return header + byteArrayOf(encoding.toByte()) + textBytes
    }

    private fun encodeUtf16LeWithBom(text: String): ByteArray {
        val out = ByteArray(2 + text.length * 2)
        out[0] = 0xFF.toByte()
        out[1] = 0xFE.toByte()
        for (i in text.indices) {
            val c = text[i].code
            out[2 + i * 2] = (c and 0xFF).toByte()
            out[3 + i * 2] = ((c ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun encodeUtf16BeWithBom(text: String): ByteArray {
        val out = ByteArray(2 + text.length * 2)
        out[0] = 0xFE.toByte()
        out[1] = 0xFF.toByte()
        for (i in text.indices) {
            val c = text[i].code
            out[2 + i * 2] = ((c ushr 8) and 0xFF).toByte()
            out[3 + i * 2] = (c and 0xFF).toByte()
        }
        return out
    }

    private fun buildFlacBlock(
        isLast: Boolean,
        type: Int,
        payload: ByteArray,
    ): ByteArray {
        val firstByte = ((if (isLast) 0x80 else 0) or (type and 0x7F)).toByte()
        val len = payload.size
        val header =
            byteArrayOf(
                firstByte,
                ((len ushr 16) and 0xFF).toByte(),
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte(),
            )
        return header + payload
    }

    private fun buildVorbisCommentBlock(
        vendor: String,
        comments: List<String>,
    ): ByteArray {
        val vendorBytes = vendor.encodeToByteArray()
        val parts = mutableListOf<ByteArray>()
        parts += le32(vendorBytes.size)
        parts += vendorBytes
        parts += le32(comments.size)
        for (c in comments) {
            val cb = c.encodeToByteArray()
            parts += le32(cb.size)
            parts += cb
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private fun buildOggPage(
        granule: Long,
        payload: ByteArray,
    ): ByteArray {
        val header = ByteArray(28)
        "OggS".encodeToByteArray().copyInto(header, 0)
        for (i in 0 until 8) {
            header[6 + i] = ((granule ushr (i * 8)) and 0xFFL).toByte()
        }
        header[26] = 1 // 1 segment
        header[27] = payload.size.coerceAtMost(255).toByte()
        return header + payload
    }

    private fun buildWavSubchunk(
        id: String,
        payload: ByteArray,
        declaredSize: Int = payload.size,
    ): ByteArray {
        val pad = if ((payload.size and 1) == 1) byteArrayOf(0) else ByteArray(0)
        return id.encodeToByteArray() + le32(declaredSize) + payload + pad
    }

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private fun writeLe32(
        dest: ByteArray,
        offset: Int,
        value: Int,
    ) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dest[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
