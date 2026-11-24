package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.BlockedKind
import com.maxrave.domain.utils.PlaybackBlocklistState
import com.maxrave.domain.utils.musicOnlyModeFlow
import com.maxrave.domain.utils.playbackBlocklistFlow
import com.maxrave.domain.utils.setMusicOnlyMode
import com.maxrave.domain.utils.unblockItem
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class BlockFilterTab(val label: String) {
    ALL("All"),
    ARTISTS("Artists"),
    SONGS("Songs"),
}

@Composable
fun MusicOnlyAndBlocklistSettings() {
    val dataStore = koinInject<DataStoreManager>()
    val scope = rememberCoroutineScope()
    val musicOnlyFlow = remember(dataStore) { dataStore.musicOnlyModeFlow() }
    val blocklistFlow = remember(dataStore) { dataStore.playbackBlocklistFlow() }
    val musicOnly by musicOnlyFlow.collectAsState(initial = false)
    val blocklist by blocklistFlow.collectAsState(initial = PlaybackBlocklistState())
    var showManageDialog by remember { mutableStateOf(false) }

    SettingItem(
        title = "Music-Only Clean Mode",
        subtitle = "Hide Podcasts and Charts clutter from Home and Library for a pure music view.",
        smallSubtitle = true,
        switch = (musicOnly to { on: Boolean -> scope.launch { dataStore.setMusicOnlyMode(on) }; Unit }),
    )

    val count = blocklist.items.size
    SettingItem(
        title = "Blocked Songs & Artists",
        subtitle =
            if (count == 0) {
                "None blocked. Tap \"Don't play\" in any song menu to skip artists or tracks everywhere."
            } else {
                "$count blocked across Radio, Queue, and AI DJ"
            },
        smallSubtitle = true,
        onClick = { showManageDialog = true },
    )

    if (showManageDialog) {
        var selectedTab by remember { mutableStateOf(BlockFilterTab.ALL) }
        val filteredItems =
            remember(blocklist, selectedTab) {
                when (selectedTab) {
                    BlockFilterTab.ALL -> blocklist.items
                    BlockFilterTab.ARTISTS -> blocklist.items.filter { it.kind == BlockedKind.ARTIST }
                    BlockFilterTab.SONGS -> blocklist.items.filter { it.kind == BlockedKind.SONG }
                }
            }
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("Blocked Songs & Artists") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BlockFilterTab.entries.forEach { tab ->
                            FilterChip(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (filteredItems.isEmpty()) {
                        Text(
                            text = "No blocked entries in this category.",
                            style = typo().bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = filteredItems,
                                key = { "${it.kind.name}:${it.id}" },
                            ) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = typo().titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val sub =
                                            when {
                                                item.kind == BlockedKind.ARTIST -> "Artist"
                                                item.subtitle.isNotBlank() -> "Song · ${item.subtitle}"
                                                else -> "Song"
                                            }
                                        Text(
                                            text = sub,
                                            style = typo().bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch { dataStore.unblockItem(item.kind, item.id) }
                                        },
                                    ) {
                                        Text("Unblock")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text("Done")
                }
            },
        )
    }
}
