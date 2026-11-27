package com.maxrave.simpmusic.portability

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.serialization.Serializable

@Serializable
data class PortableTrackEntry(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val videoId: String? = null,
    val url: String? = null,
) {
    fun resolvedUrl(): String =
        url?.takeIf { it.isNotBlank() }
            ?: videoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
            ?: ""
}

/**
 * Pure-Kotlin M3U8 & RFC-4180 CSV playlist encoder/decoder.
 *
 * Supports:
 * - Standard `#EXTM3U` / `#PLAYLIST:` / `#EXTINF:<seconds>,Artist - Title` playlists
 * - SimpMusic CSV (`Title,Artist,Album,DurationSeconds,VideoId,Url`)
 * - Exportify / Spotify CSV (`Track Name`, `Artist Name(s)`, `Album Name`, `Duration (ms)`, `Track URI`)
 * - Apple Music / iTunes / Soundiiz / generic CSV column variations
 */
object PlaylistPortabilityCodec {
    const val CSV_HEADER = "Title,Artist,Album,DurationSeconds,VideoId,Url"

    private val YOUTUBE_URL_PATTERNS =
        listOf(
            Regex("""(?:music\.)?youtube\.com/watch\?[^#\s]*\bv=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""(?:music\.)?youtube\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
        )

    private val EXTINF_REGEX = Regex("""^#EXTINF:\s*(-?\d+(?:\.\d+)?)\s*(?:[^,]*?),(.*)$""")
    private val MM_SS_REGEX = Regex("""^(\d+):([0-5]?\d)(?::([0-5]?\d))?$""")

    fun SongEntity.toPortableEntry(): PortableTrackEntry =
        PortableTrackEntry(
            title = title,
            artist = artistName.orEmpty().filter { it.isNotBlank() }.joinToString(", "),
            album = albumName.orEmpty(),
            durationSeconds = durationSeconds.coerceAtLeast(0),
            videoId = videoId.takeIf { it.isNotBlank() && !it.startsWith("local:") },
            url =
                if (videoId.startsWith("local:")) {
                    videoId
                } else {
                    "https://music.youtube.com/watch?v=$videoId"
                },
        )

    // -------------------------------------------------------------------------
    // M3U8
    // -------------------------------------------------------------------------

    fun exportSongsToM3u8(
        playlistTitle: String,
        songs: List<SongEntity>,
    ): String = exportToM3u8(playlistTitle, songs.map { it.toPortableEntry() })

    fun exportToM3u8(
        playlistTitle: String,
        tracks: List<PortableTrackEntry>,
    ): String =
        buildString {
            append("#EXTM3U\n")
            val cleanTitle = playlistTitle.replace("\r", " ").replace("\n", " ").trim()
            if (cleanTitle.isNotEmpty()) {
                append("#PLAYLIST:").append(cleanTitle).append('\n')
            }
            for (track in tracks) {
                val safeTitle = track.title.replace("\r", " ").replace("\n", " ").trim()
                val safeArtist = track.artist.replace("\r", " ").replace("\n", " ").trim()
                val duration = if (track.durationSeconds > 0) track.durationSeconds else -1
                val display =
                    if (safeArtist.isNotEmpty() && safeTitle.isNotEmpty()) {
                        "$safeArtist - $safeTitle"
                    } else {
                        safeTitle.ifEmpty { safeArtist.ifEmpty { "Unknown Track" } }
                    }
                append("#EXTINF:").append(duration).append(',').append(display).append('\n')
                val location = track.resolvedUrl().ifBlank { safeTitle }
                append(location).append('\n')
            }
        }

    fun extractPlaylistTitleFromM3u8(content: String): String? {
        val cleaned = stripBom(content).replace("\r\n", "\n").replace('\r', '\n')
        for (rawLine in cleaned.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                return line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun parseM3u8(content: String): List<PortableTrackEntry> {
        val cleaned = stripBom(content).replace("\r\n", "\n").replace('\r', '\n')
        val entries = mutableListOf<PortableTrackEntry>()
        var pendingDuration = 0
        var pendingArtist = ""
        var pendingTitle = ""
        var hasPendingExtInf = false

        for (rawLine in cleaned.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val match = EXTINF_REGEX.matchEntire(line)
                if (match != null) {
                    val durRaw = match.groupValues[1].toDoubleOrNull()?.toInt() ?: 0
                    pendingDuration = durRaw.coerceAtLeast(0)
                    val display = match.groupValues[2].trim()
                    val (artist, title) = splitArtistAndTitle(display)
                    pendingArtist = artist
                    pendingTitle = title
                    hasPendingExtInf = true
                }
                continue
            }

            val videoId = extractYouTubeVideoId(line)
            val fallbackTitle =
                if (hasPendingExtInf && pendingTitle.isNotBlank()) {
                    pendingTitle
                } else {
                    deriveTitleFromLocation(line)
                }
            entries +=
                PortableTrackEntry(
                    title = fallbackTitle,
                    artist = if (hasPendingExtInf) pendingArtist else "",
                    album = "",
                    durationSeconds = if (hasPendingExtInf) pendingDuration else 0,
                    videoId = videoId,
                    url = line,
                )
            pendingDuration = 0
            pendingArtist = ""
            pendingTitle = ""
            hasPendingExtInf = false
        }

        return entries
    }

    // -------------------------------------------------------------------------
    // CSV (RFC-4180 + Exportify / Spotify / Apple Music column compatibility)
    // -------------------------------------------------------------------------

    fun exportSongsToCsv(songs: List<SongEntity>): String = exportToCsv(songs.map { it.toPortableEntry() })

    fun exportToCsv(tracks: List<PortableTrackEntry>): String =
        buildString {
            append(CSV_HEADER).append("\r\n")
            for (track in tracks) {
                val fields =
                    listOf(
                        track.title,
                        track.artist,
                        track.album,
                        track.durationSeconds.coerceAtLeast(0).toString(),
                        track.videoId.orEmpty(),
                        track.resolvedUrl(),
                    )
                append(fields.joinToString(",") { escapeCsvField(it) })
                append("\r\n")
            }
        }

    fun parseCsv(content: String): List<PortableTrackEntry> {
        val cleaned = stripBom(content)
        val rows = parseCsvRows(cleaned)
        if (rows.isEmpty()) return emptyList()

        val headerRow = rows.first().map { normalizeHeader(it) }
        val titleCol = findColumn(headerRow, listOf("title", "trackname", "track", "song", "songname", "name"))
        val artistCol = findColumn(headerRow, listOf("artist", "artistnames", "artists", "artistname", "creator", "author"))
        val albumCol = findColumn(headerRow, listOf("album", "albumname", "release"))
        val durationSecCol = findColumn(headerRow, listOf("durationseconds", "durationsec", "seconds", "lengthseconds"))
        val durationMsCol = findColumn(headerRow, listOf("durationms", "trackdurationms", "durationinms"))
        val durationGenericCol = findColumn(headerRow, listOf("duration", "time", "length"))
        val videoIdCol = findColumn(headerRow, listOf("videoid", "youtubeid", "ytid"))
        val urlCol = findColumn(headerRow, listOf("url", "link", "trackuri", "uri", "spotifyuri", "youtubeurl"))

        val hasHeader = titleCol >= 0 || artistCol >= 0 || urlCol >= 0 || videoIdCol >= 0
        val dataRows = if (hasHeader) rows.drop(1) else rows

        val entries = mutableListOf<PortableTrackEntry>()
        for (row in dataRows) {
            if (row.all { it.isBlank() }) continue

            val rawTitle =
                if (hasHeader) {
                    row.getOrNull(titleCol)?.trim().orEmpty()
                } else {
                    row.getOrNull(0)?.trim().orEmpty()
                }
            val rawArtist =
                if (hasHeader) {
                    row.getOrNull(artistCol)?.trim().orEmpty()
                } else {
                    row.getOrNull(1)?.trim().orEmpty()
                }
            val normalizedArtist = normalizeArtists(rawArtist)
            val album =
                if (hasHeader) {
                    row.getOrNull(albumCol)?.trim().orEmpty()
                } else {
                    row.getOrNull(2)?.trim().orEmpty()
                }

            val durationSec =
                when {
                    durationSecCol >= 0 -> parseDurationToSeconds(row.getOrNull(durationSecCol), isMsHint = false)
                    durationMsCol >= 0 -> parseDurationToSeconds(row.getOrNull(durationMsCol), isMsHint = true)
                    durationGenericCol >= 0 -> parseDurationToSeconds(row.getOrNull(durationGenericCol), isMsHint = false)
                    !hasHeader -> parseDurationToSeconds(row.getOrNull(3), isMsHint = false)
                    else -> 0
                }

            val url =
                if (hasHeader) {
                    row.getOrNull(urlCol)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    row.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
                }
            val explicitVideoId =
                if (hasHeader) {
                    row.getOrNull(videoIdCol)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    row.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
                }
            val videoId = explicitVideoId ?: extractYouTubeVideoId(url)

            if (rawTitle.isEmpty() && videoId.isNullOrBlank() && url.isNullOrBlank()) continue
            entries +=
                PortableTrackEntry(
                    title = rawTitle.ifEmpty { deriveTitleFromLocation(url.orEmpty()) },
                    artist = normalizedArtist,
                    album = album,
                    durationSeconds = durationSec,
                    videoId = videoId,
                    url = url,
                )
        }
        return entries
    }

    fun detectFormat(
        content: String,
        fileNameHint: String? = null,
    ): String {
        val ext = fileNameHint?.substringAfterLast('.', "")?.lowercase()
        if (ext == "m3u" || ext == "m3u8") return "m3u8"
        if (ext == "csv") return "csv"
        val trimmed = stripBom(content).trimStart()
        return if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
            "m3u8"
        } else {
            "csv"
        }
    }

    fun parseAuto(
        content: String,
        fileNameHint: String? = null,
    ): Pair<String, List<PortableTrackEntry>> {
        val format = detectFormat(content, fileNameHint)
        val entries = if (format == "m3u8") parseM3u8(content) else parseCsv(content)
        return format to entries
    }

    fun extractYouTubeVideoId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        for (pattern in YOUTUBE_URL_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Internal parsing & escaping helpers
    // -------------------------------------------------------------------------

    fun escapeCsvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }

    /**
     * Full RFC-4180 CSV parser supporting quoted commas, doubled quotes (`""`),
     * and multiline quoted field values (`\n` / `\r\n`).
     */
    internal fun parseCsvRows(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = input.length

        while (i < len) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && input[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (field.isEmpty()) {
                            inQuotes = true
                        } else {
                            field.append(ch)
                        }
                    }

                    ',' -> {
                        currentRow += field.toString()
                        field.clear()
                    }

                    '\r' -> {
                        if (i + 1 < len && input[i + 1] == '\n') i++
                        currentRow += field.toString()
                        field.clear()
                        rows += currentRow
                        currentRow = mutableListOf()
                    }

                    '\n' -> {
                        currentRow += field.toString()
                        field.clear()
                        rows += currentRow
                        currentRow = mutableListOf()
                    }

                    else -> field.append(ch)
                }
            }
            i++
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += field.toString()
            rows += currentRow
        }
        return rows
    }

    private fun stripBom(raw: String): String =
        if (raw.startsWith('\uFEFF')) raw.substring(1) else raw

    private fun normalizeHeader(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }

    private fun findColumn(
        headers: List<String>,
        candidates: List<String>,
    ): Int {
        for (candidate in candidates) {
            val idx = headers.indexOf(candidate)
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun normalizeArtists(raw: String): String {
        if (raw.isBlank()) return ""
        // Exportify separates multiple artists with ';'
        return if (raw.contains(';') && !raw.contains(',')) {
            raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        } else {
            raw.trim()
        }
    }

    private fun parseDurationToSeconds(
        raw: String?,
        isMsHint: Boolean,
    ): Int {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0
        val mmss = MM_SS_REGEX.matchEntire(trimmed)
        if (mmss != null) {
            val p1 = mmss.groupValues[1].toIntOrNull() ?: 0
            val p2 = mmss.groupValues[2].toIntOrNull() ?: 0
            val p3 = mmss.groupValues[3].toIntOrNull()
            return if (p3 != null) p1 * 3600 + p2 * 60 + p3 else p1 * 60 + p2
        }
        val num = trimmed.toDoubleOrNull() ?: return 0
        if (num <= 0.0) return 0
        return if (isMsHint || num > 86_400.0) {
            (num / 1000.0).toInt()
        } else {
            num.toInt()
        }
    }

    private fun splitArtistAndTitle(display: String): Pair<String, String> {
        val idx = display.indexOf(" - ")
        if (idx > 0 && idx + 3 < display.length) {
            return display.substring(0, idx).trim() to display.substring(idx + 3).trim()
        }
        return "" to display.trim()
    }

    private fun deriveTitleFromLocation(location: String): String {
        if (location.isBlank()) return "Untitled Track"
        val leaf = location.substringAfterLast('/').substringAfterLast('\\')
        return leaf.substringBeforeLast('.', leaf).ifBlank { location }
    }
}
