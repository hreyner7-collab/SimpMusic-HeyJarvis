package com.maxrave.simpmusic.localaudio

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

actual object LocalAudioScanner {
    private val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")

    actual fun defaultFolders(): List<String> =
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path,
        ).distinct()

    actual suspend fun scan(watchedFolders: List<String>): LocalAudioScanResult =
        withContext(Dispatchers.IO) {
            val context =
                runCatching { getKoin().get<Context>() }.getOrNull()
                    ?: return@withContext LocalAudioScanResult(
                        tracks = emptyList(),
                        errorMessage = "Android context unavailable",
                    )

            val requiredPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

            if (context.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
                return@withContext LocalAudioScanResult(
                    tracks = emptyList(),
                    permissionRequired = true,
                    errorMessage = "Audio library permission is required to scan local music.",
                )
            }

            val normalizedFolders =
                watchedFolders
                    .map { it.trim().trimEnd('/', '\\') }
                    .filter { it.isNotEmpty() }

            val tracks = LinkedHashMap<String, LocalAudioTrack>()

            val projection =
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.SIZE)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    @Suppress("DEPRECATION")
                    add(MediaStore.Audio.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val queryResult =
                runCatching {
                    context.contentResolver
                        .query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            selection,
                            null,
                            sortOrder,
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                            val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                            val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                            @Suppress("DEPRECATION")
                            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                            val relPathCol =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                                } else {
                                    -1
                                }

                            while (cursor.moveToNext() && tracks.size < LocalAudioConfig.MAX_SCAN_TRACKS) {
                                val mediaId = cursor.getLong(idCol)
                                val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol).orEmpty() else ""
                                val rawPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                                val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty() else ""
                                val effectivePath = rawPath.ifBlank { "$relPath$displayName" }

                                if (normalizedFolders.isNotEmpty() && !matchesAnyFolder(effectivePath, relPath, normalizedFolders)) {
                                    continue
                                }

                                val ext = LocalAudioConfig.normalizeExtension(displayName.ifBlank { rawPath })
                                if (ext.isNotEmpty() && ext !in LocalAudioConfig.SUPPORTED_EXTENSIONS) {
                                    continue
                                }

                                val fallback = LocalAudioMetadataParser.parseFilenameFallback(displayName.ifBlank { rawPath })
                                val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                                val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else ""
                                val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""

                                val title = rawTitle.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback.second
                                val artist = rawArtist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback.first
                                val album = rawAlbum.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty()
                                val durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
                                val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                                val dateAddedMs = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) * 1000L else 0L
                                val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L

                                val contentUri =
                                    ContentUris
                                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                                        .toString()
                                val artworkUri =
                                    if (albumId > 0L) {
                                        ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId).toString()
                                    } else {
                                        null
                                    }

                                val id = LocalAudioConfig.stableTrackId(contentUri)
                                tracks[id] =
                                    LocalAudioTrack(
                                        id = id,
                                        uri = contentUri,
                                        filePath = effectivePath,
                                        title = title,
                                        artist = artist,
                                        album = album,
                                        durationMs = durationMs,
                                        fileSizeBytes = sizeBytes,
                                        format = ext.uppercase().ifBlank { "AUDIO" },
                                        dateAddedMs = dateAddedMs,
                                        artworkPath = artworkUri,
                                    )
                            }
                        }
                }

            val sorted = tracks.values.sortedWith(compareBy<LocalAudioTrack> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
            LocalAudioScanResult(
                tracks = sorted,
                errorMessage = queryResult.exceptionOrNull()?.message,
            )
        }

    private fun matchesAnyFolder(
        fullPath: String,
        relativePath: String,
        folders: List<String>,
    ): Boolean {
        val normFull = fullPath.replace('\\', '/').lowercase()
        val normRel = relativePath.replace('\\', '/').lowercase()
        return folders.any { folder ->
            val normFolder = folder.replace('\\', '/').lowercase()
            val folderLeaf = File(folder).name.lowercase()
            normFull.startsWith(normFolder) ||
                (folderLeaf.isNotBlank() && normRel.contains(folderLeaf))
        }
    }
}
