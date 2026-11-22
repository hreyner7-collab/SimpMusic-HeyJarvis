package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.SpotifyLibraryItem
import com.maxrave.domain.repository.SpotifyLibraryKind
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.ui.icon.CheckCircle
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.Refresh
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Sync
import com.maxrave.simpmusic.ui.theme.typo

@Composable
fun SpotifyLibraryTab(
    connected: Boolean,
    libraryState: LocalResource<List<SpotifyLibraryItem>>,
    importProgress: SpotifyImportProgress?,
    contentPadding: PaddingValues,
    onConnectSpotify: () -> Unit,
    onOpenLinkImport: () -> Unit,
    onRefresh: () -> Unit,
    onSyncAll: () -> Unit,
    onSyncItem: (SpotifyLibraryItem) -> Unit,
    onOpenLocalPlaylist: (Long) -> Unit,
    onDismissProgress: () -> Unit,
    onScrolling: (Boolean) -> Unit = {},
) {
    val isBusy = importProgress != null && !importProgress.isFinished

    if (!connected) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Sync Your Spotify Library",
                        style = typo().titleMedium,
                    )
                    Text(
                        text =
                            "Connect your Spotify account to view and sync all of your playlists, Liked Songs, and saved albums into SimpMusic playlists — or import any public Spotify / YouTube link right now without signing in.",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onConnectSpotify) {
                            Text("Connect Spotify")
                        }
                        OutlinedButton(onClick = onOpenLinkImport) {
                            Icon(
                                imageVector = SimpIcons.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Link")
                        }
                    }
                }
            }
        }
        return
    }

    val items = libraryState.data.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "spotify_header_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSyncAll,
                    enabled = !isBusy && items.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = SimpIcons.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync All")
                }
                OutlinedButton(
                    onClick = onOpenLinkImport,
                    enabled = !isBusy,
                ) {
                    Icon(
                        imageVector = SimpIcons.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Link")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !isBusy) {
                    Icon(
                        imageVector = SimpIcons.Refresh,
                        contentDescription = "Refresh Spotify library",
                    )
                }
            }
        }

        if (importProgress != null) {
            item(key = "spotify_import_progress") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text =
                                    if (importProgress.isFinished) {
                                        if (importProgress.errorMessage != null) {
                                            "Sync failed"
                                        } else {
                                            "Synced ${importProgress.playlistTitle}"
                                        }
                                    } else {
                                        "Syncing ${importProgress.playlistTitle}"
                                    },
                                style = typo().titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (importProgress.isFinished) {
                                IconButton(
                                    onClick = onDismissProgress,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = SimpIcons.Close,
                                        contentDescription = "Dismiss",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (!importProgress.isFinished) {
                            val fraction =
                                if (importProgress.total > 0) {
                                    (importProgress.processed.toFloat() / importProgress.total.toFloat()).coerceIn(0f, 1f)
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
                            Text(
                                text = "${importProgress.processed}/${importProgress.total} · ${importProgress.currentTitle}",
                                style = typo().bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                text =
                                    importProgress.errorMessage
                                        ?: "Matched ${importProgress.matched} tracks (${importProgress.failed} unmatched).",
                                style = typo().bodySmall,
                            )
                            importProgress.targetLocalPlaylistId?.let { createdId ->
                                TextButton(
                                    onClick = { onOpenLocalPlaylist(createdId) },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text("Open Playlist")
                                }
                            }
                        }
                    }
                }
            }
        }

        when {
            libraryState is LocalResource.Loading && items.isEmpty() -> {
                item(key = "spotify_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            libraryState is LocalResource.Error && items.isEmpty() -> {
                item(key = "spotify_error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = libraryState.message ?: "Failed to load Spotify library",
                            style = typo().bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = onRefresh) {
                                Text("Retry")
                            }
                            Button(onClick = onConnectSpotify) {
                                Text("Reconnect Spotify")
                            }
                            OutlinedButton(onClick = onOpenLinkImport) {
                                Text("Import Link")
                            }
                        }
                    }
                }
            }

            items.isEmpty() -> {
                item(key = "spotify_empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No Spotify playlists or liked songs found.",
                            style = typo().bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                items(items, key = { "${it.kind.name}:${it.id}" }) { item ->
                    onScrolling(false)
                    SpotifyLibraryRow(
                        item = item,
                        isBusy = isBusy,
                        onSync = { onSyncItem(item) },
                        onOpen = { item.localPlaylistId?.let(onOpenLocalPlaylist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyLibraryRow(
    item: SpotifyLibraryItem,
    isBusy: Boolean,
    onSync: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = item.localPlaylistId != null) { onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = SimpIcons.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.title,
                    style = typo().titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val kindLabel =
                    when (item.kind) {
                        SpotifyLibraryKind.LIKED_SONGS -> "Liked Songs"
                        SpotifyLibraryKind.PLAYLIST -> item.subtitle
                        SpotifyLibraryKind.ALBUM -> "Album · ${item.subtitle}"
                        SpotifyLibraryKind.TRACK -> "Track"
                    }
                Text(
                    text = if (item.trackCount > 0) "$kindLabel · ${item.trackCount} tracks" else kindLabel,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.localPlaylistId != null) {
                Icon(
                    imageVector = SimpIcons.CheckCircle,
                    contentDescription = "Synced",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                OutlinedButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Open", style = typo().labelMedium)
                }
            }
            FilledTonalButton(
                onClick = onSync,
                enabled = !isBusy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (item.localPlaylistId != null) "Re-sync" else "Sync",
                    style = typo().labelMedium,
                )
            }
        }
    }
}
