package com.maxrave.simpmusic.portability

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

private const val TAG = "PlaylistPortabilityManager"

enum class PlaylistExportFormat(
    val extension: String,
    val label: String,
) {
    M3U8("m3u8", "M3U8"),
    CSV("csv", "CSV"),
}

data class PlaylistExportResult(
    val playlistTitle: String,
    val format: PlaylistExportFormat,
    val trackCount: Int,
    val content: String,
    val savedFilePath: String? = null,
)

data class PlaylistImportResult(
    val playlistTitle: String,
    val playlistId: Long?,
    val totalEntries: Int,
    val importedCount: Int,
    val resolvedViaSearchCount: Int,
    val unresolvedCount: Int,
)

class PlaylistPortabilityManager(
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val searchRepository: SearchRepository,
) {
    suspend fun exportPlaylistToText(
        playlistId: Long,
        format: PlaylistExportFormat,
    ): Result<PlaylistExportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val playlist =
                    localPlaylistRepository
                        .getLocalPlaylist(playlistId)
                        .first { it !is LocalResource.Loading }
                        .data
                        ?: error("Local playlist #$playlistId not found")
                val songs = localPlaylistRepository.getFullPlaylistTracks(playlistId)
                val content =
                    when (format) {
                        PlaylistExportFormat.M3U8 -> PlaylistPortabilityCodec.exportSongsToM3u8(playlist.title, songs)
                        PlaylistExportFormat.CSV -> PlaylistPortabilityCodec.exportSongsToCsv(songs)
                    }
                PlaylistExportResult(
                    playlistTitle = playlist.title,
                    format = format,
                    trackCount = songs.size,
                    content = content,
                )
            }
        }

    suspend fun exportPlaylistToFile(
        playlistId: Long,
        format: PlaylistExportFormat,
        directory: String? = null,
    ): Result<PlaylistExportResult> =
        withContext(Dispatchers.IO) {
            exportPlaylistToText(playlistId, format).mapCatching { exported ->
                val fileName = "${sanitizeExportFileName(exported.playlistTitle)}.${format.extension}"
                val savedPath =
                    PortableFileIo
                        .writeTextFile(
                            fileName = fileName,
                            content = exported.content,
                            directory = directory,
                        ).getOrThrow()
                exported.copy(savedFilePath = savedPath)
            }
        }

    suspend fun importFromFile(
        path: String,
        playlistTitleOverride: String? = null,
    ): Result<PlaylistImportResult> =
        withContext(Dispatchers.IO) {
            PortableFileIo.readTextFile(path).mapCatching { content ->
                val leafName = path.substringAfterLast('/').substringAfterLast('\\')
                val fallbackTitle = leafName.substringBeforeLast('.', leafName).ifBlank { "Imported Playlist" }
                val effectiveTitle =
                    playlistTitleOverride?.trim()?.takeIf { it.isNotEmpty() }
                        ?: PlaylistPortabilityCodec.extractPlaylistTitleFromM3u8(content)
                        ?: fallbackTitle
                val formatHint =
                    when (leafName.substringAfterLast('.', "").lowercase()) {
                        "m3u", "m3u8" -> PlaylistExportFormat.M3U8
                        "csv" -> PlaylistExportFormat.CSV
                        else -> null
                    }
                importFromText(
                    playlistTitle = effectiveTitle,
                    rawContent = content,
                    formatHint = formatHint,
                ).getOrThrow()
            }
        }

    suspend fun importFromText(
        playlistTitle: String,
        rawContent: String,
        formatHint: PlaylistExportFormat? = null,
    ): Result<PlaylistImportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries =
                    when (formatHint) {
                        PlaylistExportFormat.M3U8 -> PlaylistPortabilityCodec.parseM3u8(rawContent)
                        PlaylistExportFormat.CSV -> PlaylistPortabilityCodec.parseCsv(rawContent)
                        null -> PlaylistPortabilityCodec.parseAuto(rawContent).second
                    }
                if (entries.isEmpty()) {
                    error("No tracks found in the provided playlist content.")
                }

                val resolvedTitle =
                    playlistTitle.trim().takeIf { it.isNotEmpty() }
                        ?: PlaylistPortabilityCodec.extractPlaylistTitleFromM3u8(rawContent)
                        ?: "Imported Playlist"

                val resolvedSongs = mutableListOf<SongEntity>()
                val seenIds = HashSet<String>()
                var resolvedViaSearch = 0
                var unresolved = 0

                for (entry in entries) {
                    val directVideoId = entry.videoId?.takeIf { it.isNotBlank() }
                    val song =
                        if (directVideoId != null) {
                            buildDirectSongEntity(entry, directVideoId)
                        } else {
                            val matched = resolveTrackViaSearch(entry)
                            if (matched != null) {
                                resolvedViaSearch++
                                matched
                            } else {
                                unresolved++
                                null
                            }
                        }

                    if (song != null && seenIds.add(song.videoId)) {
                        resolvedSongs += song
                    }
                }

                if (resolvedSongs.isEmpty()) {
                    return@runCatching PlaylistImportResult(
                        playlistTitle = resolvedTitle,
                        playlistId = null,
                        totalEntries = entries.size,
                        importedCount = 0,
                        resolvedViaSearchCount = resolvedViaSearch,
                        unresolvedCount = unresolved,
                    )
                }

                val existingIdsBefore =
                    runCatching { localPlaylistRepository.getAllLocalPlaylists().first().map { it.id }.toSet() }
                        .getOrDefault(emptySet())

                val insertOutcome =
                    localPlaylistRepository
                        .insertLocalPlaylist(
                            localPlaylist =
                                LocalPlaylistEntity(
                                    title = resolvedTitle,
                                    thumbnail = resolvedSongs.firstNotNullOfOrNull { it.thumbnails },
                                    tracks = emptyList(),
                                ),
                            successMessage = "Imported $resolvedTitle",
                        ).first { it !is LocalResource.Loading }
                if (insertOutcome is LocalResource.Error) {
                    error(insertOutcome.message ?: "Failed to create local playlist")
                }

                val createdPlaylist =
                    localPlaylistRepository
                        .getAllLocalPlaylists()
                        .first()
                        .filter { it.id !in existingIdsBefore && it.title == resolvedTitle }
                        .maxByOrNull { it.id }
                        ?: localPlaylistRepository
                            .getAllLocalPlaylists()
                            .first()
                            .filter { it.title == resolvedTitle }
                            .maxByOrNull { it.id }

                var addedCount = 0
                if (createdPlaylist != null) {
                    for (song in resolvedSongs) {
                        val addOutcome =
                            runCatching {
                                localPlaylistRepository
                                    .addTrackToLocalPlaylist(
                                        id = createdPlaylist.id,
                                        song = song,
                                        successMessage = "Added",
                                        updatedYtMessage = "Updated",
                                        errorMessage = "Error",
                                    ).first { it !is LocalResource.Loading }
                            }.getOrNull()
                        if (addOutcome is LocalResource.Success) {
                            addedCount++
                        }
                    }
                }

                Logger.i(
                    TAG,
                    "Imported playlist '$resolvedTitle': $addedCount/${entries.size} tracks ($resolvedViaSearch via search, $unresolved unresolved)",
                )
                PlaylistImportResult(
                    playlistTitle = resolvedTitle,
                    playlistId = createdPlaylist?.id,
                    totalEntries = entries.size,
                    importedCount = addedCount,
                    resolvedViaSearchCount = resolvedViaSearch,
                    unresolvedCount = unresolved,
                )
            }
        }

    private suspend fun resolveTrackViaSearch(entry: PortableTrackEntry): SongEntity? {
        val query =
            listOf(entry.title, entry.artist)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        if (query.isBlank()) return null

        val result =
            runCatching {
                searchRepository.getSearchDataSong(query).firstOrNull()
            }.getOrNull()

        val candidates = (result as? Resource.Success)?.data ?: return null
        if (candidates.isEmpty()) return null
        val best = pickBestSearchMatch(entry, candidates)
        return best.toTrack().toSongEntity()
    }

    private fun pickBestSearchMatch(
        entry: PortableTrackEntry,
        candidates: List<SongsResult>,
    ): SongsResult {
        val targetTitle = entry.title.trim().lowercase()
        val targetArtist = entry.artist.trim().lowercase()
        return candidates.maxByOrNull { candidate ->
            var score = 0
            val candTitle = candidate.title.orEmpty().lowercase()
            val candArtists = candidate.artists.orEmpty().joinToString(" ") { it.name.lowercase() }
            if (targetTitle.isNotEmpty() && candTitle == targetTitle) score += 100
            else if (targetTitle.isNotEmpty() && candTitle.contains(targetTitle)) score += 60
            if (targetArtist.isNotEmpty() && candArtists.contains(targetArtist)) score += 50
            if (entry.durationSeconds > 0 && candidate.durationSeconds != null) {
                val diff = kotlin.math.abs(entry.durationSeconds - candidate.durationSeconds!!)
                if (diff <= 5) score += 25
                else if (diff <= 15) score += 10
            }
            score
        } ?: candidates.first()
    }

    private fun buildDirectSongEntity(
        entry: PortableTrackEntry,
        videoId: String,
    ): SongEntity {
        val minutes = entry.durationSeconds / 60
        val seconds = entry.durationSeconds % 60
        val durationText =
            if (entry.durationSeconds > 0) {
                "$minutes:${seconds.toString().padStart(2, '0')}"
            } else {
                ""
            }
        val artists =
            entry.artist
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("Unknown Artist") }
        return SongEntity(
            videoId = videoId,
            albumId = null,
            albumName = entry.album.takeIf { it.isNotBlank() },
            artistId = List(artists.size) { "" },
            artistName = artists,
            duration = durationText,
            durationSeconds = entry.durationSeconds.coerceAtLeast(0),
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            title = entry.title.ifBlank { videoId },
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            resultType = "Song",
        )
    }
}
