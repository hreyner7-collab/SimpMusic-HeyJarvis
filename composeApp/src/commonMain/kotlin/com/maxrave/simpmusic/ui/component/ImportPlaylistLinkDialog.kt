package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.utils.ParsedPlaylistLink
import com.maxrave.domain.utils.PlaylistLinkParser
import com.maxrave.simpmusic.ui.icon.CheckCircle
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo

@Composable
fun ImportPlaylistLinkDialog(
    progress: SpotifyImportProgress?,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }
    val parsedLink = remember(urlInput) { PlaylistLinkParser.parse(urlInput) }
    val isImporting = progress != null && !progress.isFinished

    AlertDialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        icon = {
            Icon(
                imageVector = if (progress?.isFinished == true && progress.errorMessage == null) SimpIcons.CheckCircle else SimpIcons.PlaylistAdd,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = "Import Playlist from Link",
                style = typo().titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (progress == null) {
                    Text(
                        text = "Paste a public Spotify playlist, album, or track link, or any YouTube / YouTube Music playlist URL.",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        label = { Text("Playlist or track URL") },
                        placeholder = { Text("https://open.spotify.com/playlist/...") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (urlInput.isNotBlank()) {
                        val (badgeText, badgeBg, badgeFg) =
                            when (parsedLink) {
                                is ParsedPlaylistLink.SpotifyPlaylist ->
                                    Triple(
                                        "Detected: Spotify Playlist",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                is ParsedPlaylistLink.SpotifyAlbum ->
                                    Triple(
                                        "Detected: Spotify Album",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                is ParsedPlaylistLink.SpotifyTrack ->
                                    Triple(
                                        "Detected: Spotify Track",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                is ParsedPlaylistLink.YouTubePlaylist ->
                                    Triple(
                                        "Detected: YouTube Playlist",
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                null ->
                                    Triple(
                                        "Link format not recognized",
                                        MaterialTheme.colorScheme.errorContainer,
                                        MaterialTheme.colorScheme.onErrorContainer,
                                    )
                            }
                        Surface(
                            color = badgeBg,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = typo().labelSmall,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    if (progress.playlistTitle.isNotBlank()) {
                        Text(
                            text = progress.playlistTitle,
                            style = typo().titleSmall,
                        )
                    }
                    if (!progress.isFinished) {
                        val fraction =
                            if (progress.total > 0) {
                                (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${progress.processed} / ${progress.total} tracks",
                                style = typo().labelMedium,
                            )
                            Text(
                                text = "Matched: ${progress.matched}",
                                style = typo().labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (progress.currentTitle.isNotBlank()) {
                            Text(
                                text = "Matching: ${progress.currentTitle}",
                                style = typo().bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    } else if (progress.errorMessage != null) {
                        Text(
                            text = progress.errorMessage ?: "Import failed",
                            style = typo().bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text =
                                buildString {
                                    append("Matched and saved ${progress.matched} of ${progress.total} tracks.")
                                    if (progress.failed > 0) {
                                        append(" (${progress.failed} could not be matched.)")
                                    }
                                },
                            style = typo().bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            if (progress == null) {
                Button(
                    onClick = { onImport(urlInput.trim()) },
                    enabled = parsedLink != null,
                ) {
                    Text("Import")
                }
            } else if (progress.isFinished) {
                val createdId = progress.targetLocalPlaylistId
                if (createdId != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenPlaylist(createdId)
                        },
                    ) {
                        Text("Open Playlist")
                    }
                } else {
                    Button(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }
        },
        dismissButton = {
            if (progress == null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            } else if (progress.isFinished && progress.targetLocalPlaylistId != null) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            } else if (progress.isFinished) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}
