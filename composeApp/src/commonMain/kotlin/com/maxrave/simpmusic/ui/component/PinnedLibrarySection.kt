package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.Favorite
import com.maxrave.simpmusic.ui.icon.Headphones
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.PushPin
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Sync
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PINNED_LIBRARY_KEY = "pinned_library_items_json"
private const val MAX_PINNED_ITEMS = 8

private val pinnedJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

@Serializable
enum class PinnedLibraryKind {
    LOCAL_PLAYLIST,
    LIKED_SONGS,
    AI_DJ,
    DOWNLOADED,
    SPOTIFY_TAB,
}

@Serializable
data class PinnedLibraryItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val kind: PinnedLibraryKind,
)

fun DataStoreManager.pinnedLibraryItemsFlow(): Flow<List<PinnedLibraryItem>> =
    getString(PINNED_LIBRARY_KEY)
        .map { raw ->
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching { pinnedJson.decodeFromString<List<PinnedLibraryItem>>(raw) }
                    .getOrDefault(emptyList())
                    .take(MAX_PINNED_ITEMS)
            }
        }.distinctUntilChanged()

suspend fun DataStoreManager.togglePinLibraryItem(item: PinnedLibraryItem): Boolean {
    val current = pinnedLibraryItemsFlow().first()
    val exists = current.any { it.kind == item.kind && it.id == item.id }
    val updated =
        if (exists) {
            current.filterNot { it.kind == item.kind && it.id == item.id }
        } else {
            (listOf(item) + current).distinctBy { it.kind to it.id }.take(MAX_PINNED_ITEMS)
        }
    putString(PINNED_LIBRARY_KEY, pinnedJson.encodeToString(updated))
    return !exists
}

fun List<PinnedLibraryItem>.isPinned(
    kind: PinnedLibraryKind,
    id: String,
): Boolean = any { it.kind == kind && it.id == id }

@Composable
fun PinnedLibrarySection(
    pinnedItems: List<PinnedLibraryItem>,
    localPlaylists: List<LocalPlaylistEntity>,
    onOpenItem: (PinnedLibraryItem) -> Unit,
    onTogglePin: (PinnedLibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPinPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = SimpIcons.PushPin,
                contentDescription = null,
                tint = seed,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Pinned (${pinnedItems.size}/$MAX_PINNED_ITEMS)",
                style = typo().titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showPinPicker = true }) {
                Icon(
                    imageVector = SimpIcons.Add,
                    contentDescription = "Manage pins",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (pinnedItems.isEmpty()) "Pin Favorites" else "Edit Pins", style = typo().labelMedium)
            }
        }

        if (pinnedItems.isNotEmpty()) {
            pinnedItems.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { item ->
                        PinnedTile(
                            item = item,
                            onClick = { onOpenItem(item) },
                            onUnpin = { onTogglePin(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showPinPicker) {
        val quickCandidates =
            remember(localPlaylists) {
                buildList {
                    add(
                        PinnedLibraryItem(
                            id = "ai_dj",
                            title = "Ace AI DJ",
                            subtitle = "Personalized DJ session",
                            kind = PinnedLibraryKind.AI_DJ,
                        ),
                    )
                    add(
                        PinnedLibraryItem(
                            id = "liked_songs",
                            title = "Liked Songs",
                            subtitle = "Your favorites",
                            kind = PinnedLibraryKind.LIKED_SONGS,
                        ),
                    )
                    add(
                        PinnedLibraryItem(
                            id = "downloaded",
                            title = "Downloaded",
                            subtitle = "Offline tracks",
                            kind = PinnedLibraryKind.DOWNLOADED,
                        ),
                    )
                    add(
                        PinnedLibraryItem(
                            id = "spotify_tab",
                            title = "Spotify Sync",
                            subtitle = "Playlists & import",
                            kind = PinnedLibraryKind.SPOTIFY_TAB,
                        ),
                    )
                    localPlaylists.forEach { playlist ->
                        add(
                            PinnedLibraryItem(
                                id = playlist.id.toString(),
                                title = playlist.title,
                                subtitle = "${playlist.tracks?.size ?: 0} tracks",
                                thumbnailUrl = playlist.thumbnail,
                                kind = PinnedLibraryKind.LOCAL_PLAYLIST,
                            ),
                        )
                    }
                }
            }

        AlertDialog(
            onDismissRequest = { showPinPicker = false },
            title = { Text("Pin up to $MAX_PINNED_ITEMS favorites") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = quickCandidates,
                        key = { "${it.kind.name}:${it.id}" },
                    ) { candidate ->
                        val checked = pinnedItems.isPinned(candidate.kind, candidate.id)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onTogglePin(candidate) }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onTogglePin(candidate) },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.title,
                                    style = typo().titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (candidate.subtitle.isNotBlank()) {
                                    Text(
                                        text = candidate.subtitle,
                                        style = typo().bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPinPicker = false }) {
                    Text("Done")
                }
            },
        )
    }
}

@Composable
private fun PinnedTile(
    item: PinnedLibraryItem,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(56.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
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
                    val icon =
                        when (item.kind) {
                            PinnedLibraryKind.AI_DJ -> SimpIcons.Headphones
                            PinnedLibraryKind.LIKED_SONGS -> SimpIcons.Favorite
                            PinnedLibraryKind.DOWNLOADED -> SimpIcons.DownloadForOffline
                            PinnedLibraryKind.SPOTIFY_TAB -> SimpIcons.Sync
                            PinnedLibraryKind.LOCAL_PLAYLIST -> SimpIcons.LibraryMusic
                        }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = seed,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.title,
                style = typo().labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onUnpin,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = SimpIcons.Close,
                    contentDescription = "Unpin ${item.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
