package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.maxrave.simpmusic.backup.CloudBackupConfig
import com.maxrave.simpmusic.backup.CloudBackupManager
import com.maxrave.simpmusic.backup.CloudBackupProvider
import com.maxrave.simpmusic.backup.CloudBackupSchedule
import com.maxrave.simpmusic.expect.platformFormatEpochMillis
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.playbackHandoffEnabledFlow
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.setPlaybackHandoffEnabled
import com.maxrave.simpmusic.smarthome.SmartSpeakerManager
import com.maxrave.simpmusic.smarthome.WholeHomeAudioManager
import com.maxrave.simpmusic.smarthome.WholeHomeRole
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

@Composable
fun AutomatedCloudBackupSettings() {
    val cloudBackupManager = koinInject<CloudBackupManager>()
    val scope = rememberCoroutineScope()

    val config by cloudBackupManager.configFlow.collectAsState(initial = CloudBackupConfig())
    val isRunning by cloudBackupManager.isRunning.collectAsStateWithLifecycle()
    var showCredentialsDialog by remember { mutableStateOf(false) }

    val subtitle =
        when {
            isRunning -> "Syncing listening statistics & preferences with cloud…"
            !config.enabled -> "Automatically back up your listening stats, EQ, audio profiles & playlists to private cloud"
            !config.isConfigured -> "Tap Configure Cloud Account to connect a private GitHub Gist or WebDAV URL"
            config.lastError != null -> "Last error: ${config.lastError}"
            config.lastBackupEpochMs > 0L ->
                "Last synced ${platformFormatEpochMillis(config.lastBackupEpochMs)} · ${config.lastBackupSummary}"
            else -> "Ready · ${config.schedule.label}${if (config.wifiOnly) " on Wi-Fi" else ""}"
        }

    SettingItem(
        title = "Automated Cloud Backup (Stats & Preferences)",
        subtitle = subtitle,
        smallSubtitle = true,
        switch = (config.enabled to { on -> scope.launch { cloudBackupManager.updateConfig { it.copy(enabled = on) } }; Unit }),
    )
    AnimatedVisibility(visible = config.enabled) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Provider:", style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudBackupProvider.entries.forEach { provider ->
                    val label = if (provider == CloudBackupProvider.GITHUB_GIST) "GitHub Gist" else "WebDAV / HTTP"
                    TextButton(
                        onClick = { scope.launch { cloudBackupManager.updateConfig { it.copy(provider = provider) } } },
                    ) {
                        Text(
                            text = label,
                            color =
                                if (config.provider == provider) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Schedule:", style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudBackupSchedule.entries.forEach { sched ->
                    TextButton(
                        onClick = { scope.launch { cloudBackupManager.updateConfig { it.copy(schedule = sched) } } },
                    ) {
                        Text(
                            text = sched.label,
                            color =
                                if (config.schedule == sched) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showCredentialsDialog = true }) {
                    Text(if (config.isConfigured) "Edit Cloud Account" else "Configure Cloud Account")
                }
                TextButton(
                    onClick = {
                        scope.launch { cloudBackupManager.updateConfig { it.copy(wifiOnly = !it.wifiOnly) } }
                    },
                ) {
                    Text(if (config.wifiOnly) "Wi-Fi Only: ON" else "Wi-Fi Only: OFF")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !isRunning && config.isConfigured,
                    onClick = {
                        scope.launch {
                            val res = cloudBackupManager.backupNow(respectWifiOnly = false)
                            val msg =
                                res.fold(
                                    onSuccess = { "Backed up: ${it.summary}" },
                                    onFailure = { it.message ?: "Cloud backup failed" },
                                )
                            showToast(msg, duration = ToastDuration.Short, gravity = ToastGravity.Bottom)
                        }
                    },
                ) {
                    Text(if (isRunning) "Syncing…" else "Backup Now")
                }
                TextButton(
                    enabled = !isRunning && config.isConfigured,
                    onClick = {
                        scope.launch {
                            val res = cloudBackupManager.restoreLatest()
                            val msg =
                                res.fold(
                                    onSuccess = {
                                        "Restored preferences, +${it.restoredEventsCount} plays, ${it.restoredPlaylistsCount} playlists"
                                    },
                                    onFailure = { it.message ?: "Cloud restore failed" },
                                )
                            showToast(msg, duration = ToastDuration.Long, gravity = ToastGravity.Bottom)
                        }
                    },
                ) {
                    Text("Restore from Cloud")
                }
            }
        }
    }

    if (showCredentialsDialog) {
        var tokenDraft by remember(config.githubToken) { mutableStateOf(config.githubToken) }
        var gistIdDraft by remember(config.githubGistId) { mutableStateOf(config.githubGistId) }
        var urlDraft by remember(config.customEndpointUrl) { mutableStateOf(config.customEndpointUrl) }
        var authDraft by remember(config.customAuthHeader) { mutableStateOf(config.customAuthHeader) }

        AlertDialog(
            onDismissRequest = { showCredentialsDialog = false },
            title = {
                Text(
                    text =
                        if (config.provider == CloudBackupProvider.GITHUB_GIST) {
                            "Private GitHub Gist Backup"
                        } else {
                            "WebDAV / Custom Cloud Endpoint"
                        },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (config.provider == CloudBackupProvider.GITHUB_GIST) {
                        Text(
                            text = "Use a GitHub Personal Access Token with 'gist' scope. Credentials stay on-device and are never uploaded.",
                            style = typo().bodySmall,
                        )
                        OutlinedTextField(
                            value = tokenDraft,
                            onValueChange = { tokenDraft = it },
                            label = { Text("GitHub Token (gist scope)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = gistIdDraft,
                            onValueChange = { gistIdDraft = it },
                            label = { Text("Gist ID (optional — auto-created on first backup)") },
                            singleLine = true,
                        )
                    } else {
                        Text(
                            text = "Enter a WebDAV file URL (e.g. Nextcloud/Synology) or HTTP JSON endpoint supporting PUT and GET.",
                            style = typo().bodySmall,
                        )
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = { urlDraft = it },
                            label = { Text("Cloud Backup URL (https://…)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = authDraft,
                            onValueChange = { authDraft = it },
                            label = { Text("Authorization Header (optional)") },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            cloudBackupManager.updateConfig {
                                it.copy(
                                    githubToken = tokenDraft.trim(),
                                    githubGistId = gistIdDraft.trim(),
                                    customEndpointUrl = urlDraft.trim(),
                                    customAuthHeader = authDraft.trim(),
                                    lastError = null,
                                )
                            }
                            showCredentialsDialog = false
                            showToast("Saved cloud backup settings", duration = ToastDuration.Short, gravity = ToastGravity.Bottom)
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialsDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun SmartHomeAndWholeHomeAudioSettings() {
    val smartSpeakerManager = koinInject<SmartSpeakerManager>()
    val wholeHomeManager = koinInject<WholeHomeAudioManager>()
    val scope = rememberCoroutineScope()

    val speakers by smartSpeakerManager.speakers.collectAsStateWithLifecycle()
    val speakerState by smartSpeakerManager.state.collectAsStateWithLifecycle()
    val wholeHomeState by wholeHomeManager.state.collectAsStateWithLifecycle()
    val availableRooms by wholeHomeManager.availableRooms.collectAsStateWithLifecycle()

    SettingItem(
        title = "Smart Home Speakers & Whole-Home Audio",
        subtitle =
            when {
                speakerState.activeSpeaker != null ->
                    "Casting to ${speakerState.activeSpeaker!!.friendlyName} (${speakerState.volumePercent}%)"
                wholeHomeState.isActive ->
                    "${wholeHomeState.groupName} · ${wholeHomeState.statusMessage.orEmpty()}"
                speakers.isNotEmpty() ->
                    "${speakers.size} DLNA/Sonos smart speaker(s) discovered on Wi-Fi"
                else ->
                    "Discover Sonos, Bose, HEOS, MusicCast, Smart TVs & sync multi-room SimpMusic devices"
            },
        smallSubtitle = true,
        onClick = { smartSpeakerManager.scanForSpeakers() },
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = speakerState.statusMessage ?: "Tap Scan Speakers to discover DLNA/UPnP speakers",
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { smartSpeakerManager.scanForSpeakers() }) {
                Text(if (speakerState.isScanning) "Scanning…" else "Scan Speakers")
            }
        }
        speakers.forEach { speaker ->
            val connected = speakerState.activeSpeaker?.udn == speaker.udn
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = speaker.friendlyName, style = typo().labelMedium)
                    Text(
                        text = "${speaker.ecosystemLabel} · ${speaker.host}",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    TextButton(onClick = { scope.launch { smartSpeakerManager.togglePlayPause() } }) {
                        Text(if (speakerState.isPlaying) "Pause" else "Play")
                    }
                    TextButton(onClick = { scope.launch { smartSpeakerManager.stopCasting() } }) {
                        Text("Stop")
                    }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = smartSpeakerManager.castCurrentTrackTo(speaker)
                                showToast(
                                    res.getOrElse { it.message ?: "Could not connect to speaker" },
                                    duration = ToastDuration.Short,
                                    gravity = ToastGravity.Bottom,
                                )
                            }
                        },
                    ) {
                        Text("Play Here")
                    }
                }
            }
        }
        if (availableRooms.isNotEmpty() || wholeHomeState.isActive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (wholeHomeState.isActive) {
                            wholeHomeState.statusMessage ?: wholeHomeState.groupName
                        } else {
                            "Whole-Home Group (${availableRooms.size} Wi-Fi room(s) available)"
                        },
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (wholeHomeState.isActive) {
                    TextButton(onClick = { scope.launch { wholeHomeManager.disbandOrLeaveGroup() } }) {
                        Text("Stop Group")
                    }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = wholeHomeManager.startWholeHomePlayback()
                                showToast(
                                    res.getOrElse { it.message ?: "Whole-Home sync failed" },
                                    duration = ToastDuration.Short,
                                    gravity = ToastGravity.Bottom,
                                )
                            }
                        },
                    ) {
                        Text("Play All Rooms")
                    }
                }
            }
            if (wholeHomeState.role == WholeHomeRole.LEADER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Group Vol", style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = wholeHomeState.masterVolume,
                        onValueChange = { vol -> scope.launch { wholeHomeManager.setMasterVolume(vol) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
