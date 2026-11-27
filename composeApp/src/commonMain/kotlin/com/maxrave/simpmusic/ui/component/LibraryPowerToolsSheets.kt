package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.simpmusic.bookmarks.TrackBookmark
import com.maxrave.simpmusic.bookmarks.TrackBookmarkManager
import com.maxrave.simpmusic.bookmarks.formatShareSummary
import com.maxrave.simpmusic.bookmarks.formatTimestamp
import com.maxrave.simpmusic.expect.copyToClipboard
import com.maxrave.simpmusic.localaudio.LocalAudioConfig
import com.maxrave.simpmusic.localaudio.LocalAudioLibraryManager
import com.maxrave.simpmusic.localaudio.LocalAudioTrack
import com.maxrave.simpmusic.portability.PlaylistExportFormat
import com.maxrave.simpmusic.portability.PlaylistPortabilityManager
import com.maxrave.simpmusic.portability.PortableFileIo
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistCondition
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistDefinition
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistManager
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistMatchMode
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistOperator
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistRuleField
import com.maxrave.simpmusic.smartplaylist.SmartPlaylistSort
import com.maxrave.simpmusic.ui.icon.AccessAlarm
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.ContentCopy
import com.maxrave.simpmusic.ui.icon.Delete
import com.maxrave.simpmusic.ui.icon.Download
import com.maxrave.simpmusic.ui.icon.Edit
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.MusicNote
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.PushPin
import com.maxrave.simpmusic.ui.icon.Refresh
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Tune
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerToolModalSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable (SurfaceDarkColors) -> Unit,
) {
    val surfaceColors = rememberSurfaceDarkColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColors.container),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(surfaceColors.handle),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = typo().titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = surfaceColors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = typo().bodySmall,
                                color = surfaceColors.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    headerAction?.invoke()
                    IconButton(onClick = close) {
                        Icon(
                            imageVector = SimpIcons.Close,
                            contentDescription = "Close",
                            tint = surfaceColors.content,
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    color = surfaceColors.disabled.copy(alpha = 0.35f),
                )
                content(surfaceColors)
            }
        }
    }
}

// ============================================================================
// 1. Local Audio Library Sheet
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalAudioLibrarySheet(
    manager: LocalAudioLibraryManager,
    onDismiss: () -> Unit,
) {
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var newFolderPath by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("ALL") }

    LaunchedEffect(manager) {
        manager.ensureLoaded()
    }

    val availableFormats =
        remember(state.tracks) {
            listOf("ALL") + state.tracks.map { it.format.uppercase() }.distinct().sorted()
        }
    val filteredTracks =
        remember(state.tracks, searchQuery, selectedFormat) {
            manager.filterTracks(
                query = searchQuery,
                formatFilter = selectedFormat,
                tracks = state.tracks,
            )
        }

    PowerToolModalSheet(
        title = "Local Audio Library",
        subtitle = "${state.tracks.size} local tracks · MP3, FLAC, WAV, OPUS, M4A, OGG",
        onDismiss = onDismiss,
        headerAction = {
            IconButton(
                onClick = { scope.launch { manager.scanLibrary() } },
                enabled = !state.isScanning,
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = SimpIcons.Refresh,
                        contentDescription = "Scan Now",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { colors ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Watched folder chips
            if (state.watchedFolders.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.watchedFolders.forEach { folder ->
                        val shortLabel = folder.substringAfterLast('/').substringAfterLast('\\').ifBlank { folder }
                        InputChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    text = shortLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = SimpIcons.Close,
                                    contentDescription = "Remove folder",
                                    modifier =
                                        Modifier
                                            .size(16.dp)
                                            .clickable { scope.launch { manager.removeFolder(folder) } },
                                )
                            },
                        )
                    }
                }
            }

            // Add folder path row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newFolderPath,
                    onValueChange = { newFolderPath = it },
                    label = { Text("Add folder path") },
                    placeholder = { Text("/Music or C:\\Users\\...\\Music") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        val candidate = newFolderPath.trim()
                        if (candidate.isNotEmpty()) {
                            scope.launch {
                                manager.addFolder(candidate)
                                newFolderPath = ""
                            }
                        }
                    },
                    enabled = newFolderPath.isNotBlank(),
                ) {
                    Icon(SimpIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }

            // Action row: Scan Now, Play All, Shuffle All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { manager.scanLibrary() } },
                    enabled = !state.isScanning,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(SimpIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.isScanning) "Scanning..." else "Scan Now", maxLines = 1)
                }
                Button(
                    onClick = { manager.playAll(filteredTracks, shuffle = false) },
                    enabled = filteredTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(SimpIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play All", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { manager.playAll(filteredTracks, shuffle = true) },
                    enabled = filteredTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(SimpIcons.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle", maxLines = 1)
                }
            }

            state.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(SimpIcons.Search, contentDescription = null) },
                placeholder = { Text("Search local tracks, artists, albums...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Format filter chips
            if (availableFormats.size > 2) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableFormats.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = { selectedFormat = fmt },
                            label = { Text(fmt) },
                        )
                    }
                }
            }

            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = SimpIcons.LibraryMusic,
                            contentDescription = null,
                            tint = colors.subtitle,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text =
                                if (state.tracks.isEmpty()) {
                                    "No local tracks indexed yet. Tap Scan Now to discover your offline audio files."
                                } else {
                                    "No local tracks match \"$searchQuery\"."
                                },
                            style = typo().bodyMedium,
                            color = colors.subtitle,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        LocalAudioTrackRow(
                            track = track,
                            colors = colors,
                            onClick = { manager.playTrack(track, filteredTracks) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalAudioTrackRow(
    track: LocalAudioTrack,
    colors: SurfaceDarkColors,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SimpIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOf(track.artist, track.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                Text(
                    text = meta.ifBlank { track.filePath },
                    style = typo().bodySmall,
                    color = colors.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = track.format.uppercase(),
                        style = typo().labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = LocalAudioConfig.formatDuration(track.durationMs),
                    style = typo().labelSmall,
                    color = colors.subtitle,
                )
            }
        }
    }
}

// ============================================================================
// 2. Rule-Based Smart Playlists Sheet
// ============================================================================

@Composable
fun SmartPlaylistsSheet(
    manager: SmartPlaylistManager,
    onDismiss: () -> Unit,
) {
    val definitions by manager.definitionsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var trackCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var isRefreshingCounts by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(definitions) {
        isRefreshingCounts = true
        trackCounts = runCatching { manager.evaluateAllCounts() }.getOrDefault(emptyMap())
        isRefreshingCounts = false
    }

    if (showCreateDialog) {
        CreateSmartPlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { def ->
                scope.launch {
                    manager.saveCustomPlaylist(def)
                    showCreateDialog = false
                    statusMessage = "Created \"${def.name}\""
                }
            },
        )
    }

    PowerToolModalSheet(
        title = "Smart Playlists",
        subtitle = "Auto-updating rule-based mixes from your library & listening history",
        onDismiss = onDismiss,
        headerAction = {
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(SimpIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Rule")
            }
        },
    ) { colors ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(SimpIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("+ Create Rule Playlist")
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(definitions, key = { it.id }) { def ->
                    val count = trackCounts[def.id]
                    SmartPlaylistCard(
                        definition = def,
                        trackCount = count,
                        isCounting = isRefreshingCounts && count == null,
                        colors = colors,
                        onPlay = {
                            scope.launch {
                                val played = manager.playSmartPlaylist(def, shuffle = false)
                                statusMessage =
                                    if (played.isEmpty()) {
                                        "No matching tracks for \"${def.name}\" yet."
                                    } else {
                                        "Playing ${played.size} tracks from \"${def.name}\""
                                    }
                            }
                        },
                        onShuffle = {
                            scope.launch {
                                val played = manager.playSmartPlaylist(def, shuffle = true)
                                statusMessage =
                                    if (played.isEmpty()) {
                                        "No matching tracks for \"${def.name}\" yet."
                                    } else {
                                        "Shuffling ${played.size} tracks from \"${def.name}\""
                                    }
                            }
                        },
                        onDelete =
                            if (def.isBuiltIn) {
                                null
                            } else {
                                {
                                    scope.launch {
                                        manager.deleteCustomPlaylist(def.id)
                                        statusMessage = "Deleted \"${def.name}\""
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartPlaylistCard(
    definition: SmartPlaylistDefinition,
    trackCount: Int?,
    isCounting: Boolean,
    colors: SurfaceDarkColors,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = definition.name,
                            style = typo().titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text =
                                    when {
                                        isCounting -> "…"
                                        trackCount != null -> "$trackCount tracks"
                                        else -> "0 tracks"
                                    },
                                style = typo().labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val subtitle =
                        definition.description.ifBlank {
                            definition.conditions.joinToString(
                                if (definition.matchMode == SmartPlaylistMatchMode.ALL) " · " else " OR ",
                            ) { it.summaryText() }
                        }
                    Text(
                        text = subtitle,
                        style = typo().bodySmall,
                        color = colors.subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = SimpIcons.Delete,
                            contentDescription = "Delete smart playlist",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    enabled = (trackCount ?: 1) > 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(SimpIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                FilledTonalButton(
                    onClick = onShuffle,
                    enabled = (trackCount ?: 1) > 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(SimpIcons.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSmartPlaylistDialog(
    onDismiss: () -> Unit,
    onSave: (SmartPlaylistDefinition) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var matchMode by remember { mutableStateOf(SmartPlaylistMatchMode.ALL) }
    var sort by remember { mutableStateOf(SmartPlaylistSort.MOST_PLAYED) }
    var limitText by remember { mutableStateOf("50") }

    var draftField by remember { mutableStateOf(SmartPlaylistRuleField.LIKED) }
    var draftOperator by remember { mutableStateOf(SmartPlaylistOperator.EQUALS) }
    var draftValue by remember { mutableStateOf("true") }

    val conditions =
        remember {
            mutableStateListOf(
                SmartPlaylistCondition(
                    field = SmartPlaylistRuleField.LIKED,
                    operator = SmartPlaylistOperator.EQUALS,
                    value = "true",
                ),
            )
        }

    AlertDialog(
        containerColor = rememberSurfaceDarkColors().container,
        onDismissRequest = onDismiss,
        title = { Text("Create Rule Playlist", style = typo().titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    placeholder = { Text("e.g. Late Night Gems") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartPlaylistMatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = matchMode == mode,
                            onClick = { matchMode = mode },
                            label = { Text(if (mode == SmartPlaylistMatchMode.ALL) "Match ALL" else "Match ANY") },
                        )
                    }
                }

                Text("Rules (${conditions.size})", style = typo().labelMedium)
                conditions.forEachIndexed { idx, cond ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cond.summaryText(),
                                style = typo().bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { conditions.removeAt(idx) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(SimpIcons.Close, contentDescription = "Remove rule", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Rule builder
                Text("Add Condition", style = typo().labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmartPlaylistRuleField.entries.forEach { field ->
                        FilterChip(
                            selected = draftField == field,
                            onClick = {
                                draftField = field
                                when {
                                    field.isBoolean -> {
                                        draftOperator = SmartPlaylistOperator.EQUALS
                                        draftValue = "true"
                                    }
                                    field.isNumeric -> {
                                        draftOperator = SmartPlaylistOperator.GREATER_THAN
                                        draftValue = if (field == SmartPlaylistRuleField.DAYS_SINCE_LAST_PLAYED) "30" else "180"
                                    }
                                    else -> {
                                        draftOperator = SmartPlaylistOperator.CONTAINS
                                        draftValue = ""
                                    }
                                }
                            },
                            label = { Text(field.displayLabel, maxLines = 1) },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val allowedOps =
                        when {
                            draftField.isBoolean -> listOf(SmartPlaylistOperator.EQUALS)
                            draftField.isNumeric ->
                                listOf(
                                    SmartPlaylistOperator.GREATER_THAN,
                                    SmartPlaylistOperator.LESS_THAN,
                                    SmartPlaylistOperator.EQUALS,
                                )
                            else -> listOf(SmartPlaylistOperator.CONTAINS, SmartPlaylistOperator.EQUALS)
                        }
                    allowedOps.forEach { op ->
                        FilterChip(
                            selected = draftOperator == op,
                            onClick = { draftOperator = op },
                            label = { Text(op.displayLabel) },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draftValue,
                        onValueChange = { draftValue = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            if (draftValue.isNotBlank()) {
                                conditions += SmartPlaylistCondition(draftField, draftOperator, draftValue.trim())
                            }
                        },
                        enabled = draftValue.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                }

                Text("Sort Order", style = typo().labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmartPlaylistSort.entries.forEach { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = { sort = s },
                            label = { Text(s.displayLabel) },
                        )
                    }
                }

                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max Tracks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedLimit = (limitText.toIntOrNull() ?: 50).coerceIn(1, 500)
                    onSave(
                        SmartPlaylistDefinition(
                            id = "",
                            name = name.trim().ifBlank { "Custom Smart Mix" },
                            matchMode = matchMode,
                            conditions = conditions.toList(),
                            sort = sort,
                            limit = parsedLimit,
                            isBuiltIn = false,
                        ),
                    )
                },
                enabled = conditions.isNotEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ============================================================================
// 3. Playlist Portability Sheet (M3U8 & CSV Import / Export)
// ============================================================================

@Composable
fun PlaylistPortabilitySheet(
    manager: PlaylistPortabilityManager,
    playlists: List<LocalPlaylistEntity>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(0) } // 0 = Export, 1 = Import
    var selectedPlaylistId by remember(playlists) { mutableStateOf(playlists.firstOrNull()?.id) }
    var exportFormat by remember { mutableStateOf(PlaylistExportFormat.M3U8) }
    var exportPreview by remember { mutableStateOf<String?>(null) }

    var importTitle by remember { mutableStateOf("") }
    var importModeFile by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importFilePath by remember { mutableStateOf("") }

    var isBusy by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    PowerToolModalSheet(
        title = "Playlist Import & Export",
        subtitle = "Standard M3U8, SimpMusic CSV, Spotify Exportify & Apple Music CSV",
        onDismiss = onDismiss,
    ) { colors ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    label = { Text("Export Playlist") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    label = { Text("Import M3U8 / CSV") },
                    modifier = Modifier.weight(1f),
                )
            }

            feedbackMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color =
                        if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        style = typo().bodySmall,
                        color =
                            if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (activeTab == 0) {
                // EXPORT TAB
                if (playlists.isEmpty()) {
                    Text(
                        text = "No local playlists available to export yet.",
                        style = typo().bodyMedium,
                        color = colors.subtitle,
                    )
                } else {
                    Text("Select Local Playlist", style = typo().labelMedium, color = colors.subtitle)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        playlists.forEach { pl ->
                            val selected = pl.id == selectedPlaylistId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                modifier = Modifier.fillMaxWidth().clickable { selectedPlaylistId = pl.id },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pl.title,
                                            style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color =
                                                if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    colors.content
                                                },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${pl.tracks?.size ?: 0} tracks",
                                            style = typo().labelSmall,
                                            color = colors.subtitle,
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            imageVector = SimpIcons.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text("Format", style = typo().labelMedium, color = colors.subtitle)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaylistExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = exportFormat == fmt,
                                onClick = { exportFormat = fmt },
                                label = { Text(".${fmt.extension.uppercase()}") },
                            )
                        }
                    }

                    Text(
                        text = "Export folder: ${PortableFileIo.defaultExportDirectory()}",
                        style = typo().labelSmall,
                        color = colors.subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val id = selectedPlaylistId ?: return@Button
                                scope.launch {
                                    isBusy = true
                                    isError = false
                                    manager
                                        .exportPlaylistToFile(id, exportFormat)
                                        .onSuccess { res ->
                                            exportPreview = res.content.take(500)
                                            feedbackMessage =
                                                "Exported ${res.trackCount} tracks to ${res.savedFilePath}"
                                        }.onFailure { err ->
                                            isError = true
                                            feedbackMessage = err.message ?: "Export failed"
                                        }
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && selectedPlaylistId != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(SimpIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isBusy) "Exporting..." else "Save File")
                        }
                        FilledTonalButton(
                            onClick = {
                                val id = selectedPlaylistId ?: return@FilledTonalButton
                                scope.launch {
                                    isBusy = true
                                    isError = false
                                    manager
                                        .exportPlaylistToText(id, exportFormat)
                                        .onSuccess { res ->
                                            copyToClipboard(res.playlistTitle, res.content)
                                            exportPreview = res.content.take(500)
                                            feedbackMessage =
                                                "Copied ${res.trackCount} tracks (${res.format.label}) to clipboard"
                                        }.onFailure { err ->
                                            isError = true
                                            feedbackMessage = err.message ?: "Copy failed"
                                        }
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && selectedPlaylistId != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(SimpIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Text")
                        }
                    }

                    exportPreview?.let { snippet ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = snippet,
                                style = typo().labelSmall,
                                color = colors.subtitle,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            } else {
                // IMPORT TAB
                OutlinedTextField(
                    value = importTitle,
                    onValueChange = { importTitle = it },
                    label = { Text("New Playlist Name (optional for M3U8)") },
                    placeholder = { Text("e.g. Spotify Favorites") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !importModeFile,
                        onClick = { importModeFile = false },
                        label = { Text("Paste M3U8 / CSV") },
                    )
                    FilterChip(
                        selected = importModeFile,
                        onClick = { importModeFile = true },
                        label = { Text("From File Path") },
                    )
                }

                if (importModeFile) {
                    OutlinedTextField(
                        value = importFilePath,
                        onValueChange = { importFilePath = it },
                        label = { Text("File path (.m3u8 or .csv)") },
                        placeholder = { Text("${PortableFileIo.defaultExportDirectory()}/playlist.csv") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("Paste M3U8 or CSV content") },
                        placeholder = { Text("#EXTM3U or Track Name,Artist Name(s),Album Name...") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            isError = false
                            val result =
                                if (importModeFile) {
                                    manager.importFromFile(
                                        path = importFilePath,
                                        playlistTitleOverride = importTitle.takeIf { it.isNotBlank() },
                                    )
                                } else {
                                    manager.importFromText(
                                        playlistTitle = importTitle,
                                        rawContent = importText,
                                    )
                                }
                            result
                                .onSuccess { res ->
                                    feedbackMessage =
                                        "Imported ${res.importedCount}/${res.totalEntries} tracks into \"${res.playlistTitle}\" " +
                                            "(${res.resolvedViaSearchCount} matched via search, ${res.unresolvedCount} skipped)"
                                }.onFailure { err ->
                                    isError = true
                                    feedbackMessage = err.message ?: "Import failed"
                                }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy && (if (importModeFile) importFilePath.isNotBlank() else importText.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Importing & Resolving Tracks...")
                    } else {
                        Icon(SimpIcons.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import into Local Playlist")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ============================================================================
// 4. Timestamp Bookmarks Sheet
// ============================================================================

@Composable
fun TrackBookmarksSheet(
    manager: TrackBookmarkManager,
    videoId: String,
    songTitle: String,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
) {
    val bookmarksFlow = remember(manager, videoId) { manager.bookmarksForTrackFlow(videoId) }
    val bookmarks by bookmarksFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var quickLabel by remember { mutableStateOf("") }
    var editingBookmark by remember { mutableStateOf<TrackBookmark?>(null) }
    var copiedNotice by remember { mutableStateOf(false) }

    editingBookmark?.let { target ->
        EditBookmarkDialog(
            bookmark = target,
            onDismiss = { editingBookmark = null },
            onSave = { newLabel, newNote ->
                scope.launch {
                    manager.updateBookmark(target.id, newLabel, newNote)
                    editingBookmark = null
                }
            },
        )
    }

    PowerToolModalSheet(
        title = "Timestamp Bookmarks",
        subtitle = "$songTitle · ${bookmarks.size} saved cues",
        onDismiss = onDismiss,
        headerAction = {
            if (bookmarks.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val summary = formatShareSummary(songTitle, bookmarks)
                        copyToClipboard("$songTitle Bookmarks", summary)
                        copiedNotice = true
                    },
                ) {
                    Icon(
                        imageVector = SimpIcons.ContentCopy,
                        contentDescription = "Copy shareable timestamps",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { colors ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = quickLabel,
                onValueChange = { quickLabel = it },
                label = { Text("Optional cue label (e.g. Guitar solo, Drop, Quote)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (videoId.isNotBlank()) {
                            scope.launch {
                                manager.addBookmark(
                                    videoId = videoId,
                                    songTitle = songTitle,
                                    timestampMs = currentPositionMs,
                                    label = quickLabel,
                                )
                                quickLabel = ""
                                copiedNotice = false
                            }
                        }
                    },
                    enabled = videoId.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SimpIcons.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ Bookmark Current Time (${formatTimestamp(currentPositionMs)})")
                }
            }

            if (copiedNotice) {
                Text(
                    text = "Copied shareable timestamp summary to clipboard.",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = SimpIcons.AccessAlarm,
                            contentDescription = null,
                            tint = colors.subtitle,
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            text = "No bookmarks for this track yet. Tap above to save key moments for one-tap jumping.",
                            style = typo().bodyMedium,
                            color = colors.subtitle,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { manager.jumpToBookmark(bm) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = formatTimestamp(bm.timestampMs),
                                        style = typo().labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.label.ifBlank { "Bookmark" },
                                        style = typo().bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.content,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (bm.note.isNotBlank()) {
                                        Text(
                                            text = bm.note,
                                            style = typo().bodySmall,
                                            color = colors.subtitle,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { editingBookmark = bm }) {
                                    Icon(
                                        imageVector = SimpIcons.Edit,
                                        contentDescription = "Edit bookmark",
                                        tint = colors.subtitle,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { scope.launch { manager.deleteBookmark(bm.id) } },
                                ) {
                                    Icon(
                                        imageVector = SimpIcons.Delete,
                                        contentDescription = "Delete bookmark",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditBookmarkDialog(
    bookmark: TrackBookmark,
    onDismiss: () -> Unit,
    onSave: (label: String, note: String) -> Unit,
) {
    var label by remember(bookmark.id) { mutableStateOf(bookmark.label) }
    var note by remember(bookmark.id) { mutableStateOf(bookmark.note) }

    AlertDialog(
        containerColor = rememberSurfaceDarkColors().container,
        onDismissRequest = onDismiss,
        title = { Text("Edit Bookmark (${formatTimestamp(bookmark.timestampMs)})", style = typo().titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(label, note) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
