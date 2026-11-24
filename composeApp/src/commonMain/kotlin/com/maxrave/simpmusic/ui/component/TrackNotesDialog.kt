package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.System

private const val TRACK_NOTES_KEY = "track_notes_json"

private val trackNotesJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private val timestampRegex = Regex("""\b(\d{1,2}):([0-5]\d)\b""")

@Serializable
data class TrackNoteEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val note: String,
    val updatedAtEpochMs: Long,
)

fun DataStoreManager.trackNotesFlow(): Flow<Map<String, TrackNoteEntry>> =
    getString(TRACK_NOTES_KEY)
        .map { raw ->
            if (raw.isNullOrBlank()) {
                emptyMap()
            } else {
                runCatching {
                    trackNotesJson.decodeFromString<List<TrackNoteEntry>>(raw).associateBy { it.videoId }
                }.getOrDefault(emptyMap())
            }
        }.distinctUntilChanged()

suspend fun DataStoreManager.saveTrackNote(
    videoId: String,
    title: String,
    artist: String,
    note: String,
) {
    if (videoId.isBlank()) return
    val current = trackNotesFlow().first().toMutableMap()
    val trimmed = note.trim()
    if (trimmed.isEmpty()) {
        current.remove(videoId)
    } else {
        current[videoId] =
            TrackNoteEntry(
                videoId = videoId,
                title = title,
                artist = artist,
                note = trimmed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
    }
    putString(
        TRACK_NOTES_KEY,
        trackNotesJson.encodeToString(current.values.sortedByDescending { it.updatedAtEpochMs }),
    )
}

suspend fun DataStoreManager.deleteTrackNote(videoId: String) {
    val current = trackNotesFlow().first().toMutableMap()
    if (current.remove(videoId) != null) {
        putString(
            TRACK_NOTES_KEY,
            trackNotesJson.encodeToString(current.values.sortedByDescending { it.updatedAtEpochMs }),
        )
    }
}

fun formatNoteTimestamp(positionMs: Long): String {
    val totalSec = (positionMs.coerceAtLeast(0L) / 1000L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "$min:${sec.toString().padStart(2, '0')}"
}

fun extractNoteTimestampsMs(note: String): List<Pair<String, Long>> =
    timestampRegex
        .findAll(note)
        .mapNotNull { match ->
            val min = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            match.value to ((min * 60L + sec) * 1000L)
        }.distinctBy { it.first }
        .toList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackNoteEditorDialog(
    videoId: String,
    title: String,
    artist: String,
    existingNote: TrackNoteEntry?,
    currentPositionMs: Long,
    canSeek: Boolean,
    dataStoreManager: DataStoreManager,
    onSeekToMs: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var noteText by remember(videoId, existingNote) { mutableStateOf(existingNote?.note.orEmpty()) }
    val timestamps = remember(noteText) { extractNoteTimestampsMs(noteText) }

    AlertDialog(
        containerColor = rememberSurfaceDarkColors().container,
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Track Notes", style = typo().titleMedium)
                Text(
                    text = if (artist.isNotBlank()) "$title · $artist" else title,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Add note, lyric thought, or [m:ss] cue") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canSeek) {
                    val formatted = formatNoteTimestamp(currentPositionMs)
                    FilterChip(
                        selected = false,
                        onClick = {
                            val sep = if (noteText.isBlank() || noteText.endsWith(" ") || noteText.endsWith("\n")) "" else " "
                            noteText = "$noteText$sep[$formatted] "
                        },
                        label = { Text("+ Timestamp ($formatted)") },
                    )
                }
                if (timestamps.isNotEmpty() && canSeek) {
                    Text("Jump to timestamp:", style = typo().labelSmall, color = seed)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        timestamps.forEach { (label, ms) ->
                            FilterChip(
                                selected = true,
                                onClick = { onSeekToMs(ms) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingNote != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                dataStoreManager.deleteTrackNote(videoId)
                                onDismiss()
                            }
                        },
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            dataStoreManager.saveTrackNote(videoId, title, artist, noteText)
                            onDismiss()
                        }
                    },
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun AllTrackNotesDialog(
    dataStoreManager: DataStoreManager,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val notesFlow = remember(dataStoreManager) { dataStoreManager.trackNotesFlow() }
    val notesMap by notesFlow.collectAsState(emptyMap())
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TrackNoteEntry?>(null) }

    val filtered =
        remember(notesMap, query) {
            val all = notesMap.values.sortedByDescending { it.updatedAtEpochMs }
            if (query.isBlank()) {
                all
            } else {
                val q = query.trim().lowercase()
                all.filter {
                    it.title.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q) ||
                        it.note.lowercase().contains(q)
                }
            }
        }

    editing?.let { entry ->
        TrackNoteEditorDialog(
            videoId = entry.videoId,
            title = entry.title,
            artist = entry.artist,
            existingNote = entry,
            currentPositionMs = 0L,
            canSeek = false,
            dataStoreManager = dataStoreManager,
            onSeekToMs = {},
            onDismiss = { editing = null },
        )
    }

    AlertDialog(
        containerColor = rememberSurfaceDarkColors().container,
        onDismissRequest = onDismiss,
        title = { Text("Saved Track Notes (${notesMap.size})", style = typo().titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search notes or tracks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (notesMap.isEmpty()) "No saved track notes yet. Open a song's ⋯ menu → Track Notes to add one." else "No matching notes.",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.videoId }) { entry ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth().clickable { editing = entry },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            style = typo().bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (entry.artist.isNotBlank()) {
                                            Text(
                                                text = entry.artist,
                                                style = typo().bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = entry.note,
                                            style = typo().bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { scope.launch { dataStoreManager.deleteTrackNote(entry.videoId) } },
                                    ) {
                                        Icon(SimpIcons.Close, contentDescription = "Delete note")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
