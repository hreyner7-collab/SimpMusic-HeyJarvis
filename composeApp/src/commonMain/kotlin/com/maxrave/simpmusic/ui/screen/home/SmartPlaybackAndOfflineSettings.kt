package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.common.Config
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheEnabledFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheLimitMbFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setRollingOfflineCacheEnabled
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setRollingOfflineCacheLimitMb
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setWifiAutoSyncEnabled
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.toggleWifiAutoSyncPlaylist
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.wifiAutoSyncEnabledFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.wifiAutoSyncPlaylistsFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles
import com.maxrave.domain.data.player.DeviceAudioProfiles.applyDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.deleteDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.deviceAudioProfilesFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.perDeviceAudioProfilesEnabledFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.setPerDeviceAudioProfilesEnabled
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.playbackHandoffEnabledFlow
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.setPlaybackHandoffEnabled
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.utils.DeviceAudioProfileController
import com.maxrave.simpmusic.utils.WifiPlaylistAutoSyncManager
import kotlinx.coroutines.launch
import multiplatform.network.cmptoast.ToastDuration
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.koin.compose.koinInject

@Composable
fun DeviceAudioProfilesSettings() {
    val dataStore = koinInject<DataStoreManager>()
    val controller = koinInject<DeviceAudioProfileController>()
    val scope = rememberCoroutineScope()

    val enabled by dataStore.perDeviceAudioProfilesEnabledFlow().collectAsState(initial = false)
    val profiles by dataStore.deviceAudioProfilesFlow().collectAsState(initial = emptyList())
    val activeOutput by controller.activeOutput.collectAsStateWithLifecycle()

    SettingItem(
        title = "Per-device audio profiles",
        subtitle =
            if (activeOutput != null) {
                "Auto-switch EQ, AutoEQ, Widener & Crossfade per device · Active: ${DeviceAudioProfiles.displayLabelFor(activeOutput!!)}"
            } else {
                "Auto-switch EQ, AutoEQ, Widener & Crossfade when headphones or speakers change"
            },
        smallSubtitle = true,
        switch = (enabled to { on -> scope.launch { dataStore.setPerDeviceAudioProfilesEnabled(on) }; Unit }),
    )
    AnimatedVisibility(visible = enabled) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        activeOutput?.let { "Active output: ${DeviceAudioProfiles.displayLabelFor(it)}" }
                            ?: "Active output: System Default",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val saved = controller.saveCurrentForActiveDevice()
                            showToast(
                                if (saved != null) "Saved audio profile for ${saved.displayName}" else "No active audio device detected yet",
                                duration = ToastDuration.Short,
                                gravity = ToastGravity.Bottom,
                            )
                        }
                    },
                ) {
                    Text("Save for this device")
                }
            }
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = profile.displayName, style = typo().labelMedium)
                        Text(
                            text =
                                buildString {
                                    append(if (profile.equalizerEnabled) "EQ On" else "EQ Off")
                                    if (profile.stereoWidenEnabled) append(" · Widener On")
                                    if (profile.crossfadeEnabled) append(" · Crossfade On")
                                    if (profile.normalizeVolume) append(" · Normalize")
                                },
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                dataStore.applyDeviceAudioProfile(profile)
                                showToast("Applied profile: ${profile.displayName}", duration = ToastDuration.Short, gravity = ToastGravity.Bottom)
                            }
                        },
                    ) {
                        Text("Apply")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                dataStore.deleteDeviceAudioProfile(profile.deviceKey)
                            }
                        },
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackHandoffSettings() {
    val dataStore = koinInject<DataStoreManager>()
    val handoffManager = koinInject<PlaybackHandoffManager>()
    val scope = rememberCoroutineScope()

    val enabled by dataStore.playbackHandoffEnabledFlow().collectAsState(initial = true)
    val peers by handoffManager.peers.collectAsStateWithLifecycle()

    SettingItem(
        title = "Phone ↔ PC Playback Handoff",
        subtitle =
            if (enabled) {
                if (peers.isEmpty()) {
                    "Listening on Wi-Fi · Open Audio Output in Now Playing to transfer playback"
                } else {
                    "${peers.size} SimpMusic device(s) ready on Wi-Fi"
                }
            } else {
                "Transfer your queue and exact timestamp between your phone and PC on Wi-Fi"
            },
        smallSubtitle = true,
        switch = (enabled to { on -> scope.launch { dataStore.setPlaybackHandoffEnabled(on) }; Unit }),
    )
    AnimatedVisibility(visible = enabled) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (peers.isEmpty()) "No other SimpMusic devices found on Wi-Fi yet" else "Nearby SimpMusic devices",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { handoffManager.refreshPeers() }) {
                    Text("Scan Wi-Fi")
                }
            }
            peers.forEach { peer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = peer.deviceName, style = typo().labelMedium)
                        Text(
                            text = peer.nowPlayingTitle.ifBlank { "Ready (${peer.host})" },
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = handoffManager.sendPlaybackTo(peer)
                                showToast(
                                    res.getOrElse { it.message ?: "Handoff failed" },
                                    duration = ToastDuration.Short,
                                    gravity = ToastGravity.Bottom,
                                )
                            }
                        },
                    ) {
                        Text("Send")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = handoffManager.pullPlaybackFrom(peer)
                                showToast(
                                    res.getOrElse { it.message ?: "Pull failed" },
                                    duration = ToastDuration.Short,
                                    gravity = ToastGravity.Bottom,
                                )
                            }
                        },
                    ) {
                        Text("Pull")
                    }
                }
            }
        }
    }
}

@Composable
fun RollingOfflineCacheAndSyncSettings() {
    val dataStore = koinInject<DataStoreManager>()
    val cacheRepository = koinInject<CacheRepository>()
    val autoSyncManager = koinInject<WifiPlaylistAutoSyncManager>()
    val scope = rememberCoroutineScope()

    val rollingCacheEnabled by dataStore.rollingOfflineCacheEnabledFlow().collectAsState(initial = true)
    val rollingCacheMb by dataStore.rollingOfflineCacheLimitMbFlow().collectAsState(initial = OfflineCacheAndSyncSettings.DEFAULT_ROLLING_CACHE_MB)
    val wifiAutoSyncEnabled by dataStore.wifiAutoSyncEnabledFlow().collectAsState(initial = true)
    val autoSyncPlaylists by dataStore.wifiAutoSyncPlaylistsFlow().collectAsState(initial = emptyList())
    var desktopCacheBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(rollingCacheEnabled, rollingCacheMb) {
        if (getPlatform() == Platform.Desktop) {
            desktopCacheBytes = runCatching { cacheRepository.getCacheSize(Config.PLAYER_CACHE) }.getOrDefault(0L)
        }
    }

    if (getPlatform() == Platform.Desktop) {
        val usedMb = desktopCacheBytes / (1024L * 1024L)
        SettingItem(
            title = "Rolling offline audio cache",
            subtitle =
                if (rollingCacheEnabled) {
                    "Caches streamed songs locally for instant offline replay ($usedMb MB / $rollingCacheMb MB used)"
                } else {
                    "Off — streamed songs are fetched from network each time"
                },
            smallSubtitle = true,
            switch = (rollingCacheEnabled to { on -> scope.launch { dataStore.setRollingOfflineCacheEnabled(on) }; Unit }),
        )
        AnimatedVisibility(visible = rollingCacheEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OfflineCacheAndSyncSettings.ROLLING_CACHE_PRESETS_MB.forEach { preset ->
                    TextButton(
                        onClick = {
                            scope.launch { dataStore.setRollingOfflineCacheLimitMb(preset) }
                        },
                    ) {
                        Text(
                            text = if (preset >= 1024) "${preset / 1024} GB" else "$preset MB",
                            color =
                                if (preset == rollingCacheMb) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        cacheRepository.clearCache(Config.PLAYER_CACHE)
                        desktopCacheBytes = 0L
                        showToast("Cleared rolling offline audio cache", duration = ToastDuration.Short, gravity = ToastGravity.Bottom)
                    },
                ) {
                    Text("Clear")
                }
            }
        }
    }

    SettingItem(
        title = "Wi-Fi playlist auto-sync",
        subtitle =
            if (autoSyncPlaylists.isEmpty()) {
                "Enable Auto-Sync in any playlist's ⋯ menu to keep it downloaded on Wi-Fi"
            } else {
                "Auto-downloading ${autoSyncPlaylists.size} playlist(s) whenever connected to Wi-Fi"
            },
        smallSubtitle = true,
        switch = (wifiAutoSyncEnabled to { on -> scope.launch { dataStore.setWifiAutoSyncEnabled(on) }; Unit }),
    )
    AnimatedVisibility(visible = wifiAutoSyncEnabled && autoSyncPlaylists.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Synced playlists (${autoSyncPlaylists.size})",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val res = autoSyncManager.syncEnabledPlaylistsNow()
                            val msg =
                                when {
                                    res.skippedReason == "metered_network" -> "Waiting for Wi-Fi connection"
                                    res.tracksQueued > 0 -> "Downloading ${res.tracksQueued} missing track(s) on Wi-Fi"
                                    else -> "All synced playlists are already downloaded"
                                }
                            showToast(msg, duration = ToastDuration.Short, gravity = ToastGravity.Bottom)
                        }
                    },
                ) {
                    Text("Sync now")
                }
            }
            autoSyncPlaylists.forEach { ref ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = ref.title, style = typo().bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch { dataStore.toggleWifiAutoSyncPlaylist(ref) }
                        },
                    ) {
                        Text("Stop syncing")
                    }
                }
            }
        }
    }
}
