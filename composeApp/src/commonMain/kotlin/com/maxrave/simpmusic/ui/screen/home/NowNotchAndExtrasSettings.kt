package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.expect.AppIconStyle
import com.maxrave.simpmusic.expect.appIconStyleFlow
import com.maxrave.simpmusic.expect.applyPlatformAppIcon
import com.maxrave.simpmusic.expect.setAppIconStyle
import com.maxrave.simpmusic.expect.ui.motionParallaxFlow
import com.maxrave.simpmusic.expect.ui.setMotionParallax
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.notch.NowNotchIdleMode
import com.maxrave.simpmusic.notch.NowNotchSettings
import com.maxrave.simpmusic.notch.PlatformNowNotchBridge
import com.maxrave.simpmusic.notch.nowNotchSettingsFlow
import com.maxrave.simpmusic.notch.updateNowNotchSettings
import com.maxrave.simpmusic.ui.component.AllTrackNotesDialog
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.ui.component.trackNotesFlow
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowNotchAndExtrasSettings(
    dataStoreManager: DataStoreManager = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val notchSettingsFlow = remember(dataStoreManager) { dataStoreManager.nowNotchSettingsFlow() }
    val notchSettings by notchSettingsFlow.collectAsState(NowNotchSettings())
    val parallaxFlow = remember(dataStoreManager) { dataStoreManager.motionParallaxFlow() }
    val motionParallaxEnabled by parallaxFlow.collectAsState(true)
    val iconStyleFlow = remember(dataStoreManager) { dataStoreManager.appIconStyleFlow() }
    val iconStyle by iconStyleFlow.collectAsState(AppIconStyle.DEFAULT)
    val trackNotesFlow = remember(dataStoreManager) { dataStoreManager.trackNotesFlow() }
    val trackNotes by trackNotesFlow.collectAsState(emptyMap())

    var showTrackNotesLibrary by remember { mutableStateOf(false) }

    if (showTrackNotesLibrary) {
        AllTrackNotesDialog(
            dataStoreManager = dataStoreManager,
            onDismiss = { showTrackNotesLibrary = false },
        )
    }

    val isAndroid = getPlatform() == Platform.Android
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val canOverlay = remember(lifecycleState) { PlatformNowNotchBridge.canDrawOverlay() }
    val canPromotedNotifications =
        remember(lifecycleState) { PlatformNowNotchBridge.canPostPromotedNotifications() }

    SettingItem(
        title = "Now Notch / Dynamic Island",
        subtitle =
            if (isAndroid) {
                "Floating top pill with now playing, live lyrics, and quick controls"
            } else {
                "Floating top-center pill above all windows with live lyrics and controls"
            },
        smallSubtitle = true,
        switch = (
            notchSettings.enabled to { checked ->
                if (isAndroid && checked && !PlatformNowNotchBridge.canDrawOverlay()) {
                    PlatformNowNotchBridge.requestOverlayPermission()
                }
                scope.launch {
                    dataStoreManager.updateNowNotchSettings { it.copy(enabled = checked) }
                }
            }
        ),
    )

    if (isAndroid && notchSettings.enabled && !canOverlay) {
        OutlinedButton(
            onClick = { PlatformNowNotchBridge.requestOverlayPermission() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Grant Overlay Permission for Now Notch")
        }
    }

    if (isAndroid) {
        SettingItem(
            title = "Lock-Screen & Samsung Now Bar Live Lyrics",
            subtitle = "Shows real-time synced lyrics on the lock screen and Samsung One UI 8+ Now Bar (also updates while locked)",
            smallSubtitle = true,
            switch = (
                notchSettings.lockScreenLiveLyrics to { checked ->
                    scope.launch {
                        dataStoreManager.updateNowNotchSettings { it.copy(lockScreenLiveLyrics = checked) }
                    }
                }
            ),
        )
        if (notchSettings.lockScreenLiveLyrics && !canPromotedNotifications) {
            OutlinedButton(
                onClick = { PlatformNowNotchBridge.openLiveNotificationsSettings() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("Enable Samsung Now Bar / Live Notifications")
            }
        }
    }

    if (notchSettings.enabled) {
        SettingItem(
            title = "Live Synced Lyrics in Now Notch",
            subtitle = "Show the active lyric line inside the floating pill",
            smallSubtitle = true,
            switch = (
                notchSettings.showLiveLyrics to { checked ->
                    scope.launch { dataStoreManager.updateNowNotchSettings { it.copy(showLiveLyrics = checked) } }
                }
            ),
        )
        SettingItem(
            title = "Side Glow Waves on Notch",
            subtitle = "Album-colored reactive glowing wave arcs beside the Dynamic Island pill",
            smallSubtitle = true,
            switch = (
                notchSettings.showSideWaves to { checked ->
                    scope.launch { dataStoreManager.updateNowNotchSettings { it.copy(showSideWaves = checked) } }
                }
            ),
        )
        SettingItem(
            title = "Track-Change Notification Popups",
            subtitle = "Briefly announce song changes inside Now Notch",
            smallSubtitle = true,
            switch = (
                notchSettings.showTrackChangePopup to { checked ->
                    scope.launch {
                        dataStoreManager.updateNowNotchSettings { it.copy(showTrackChangePopup = checked) }
                    }
                }
            ),
        )
        SettingItem(
            title = "Compact Now Notch Pill",
            subtitle = "Use narrower width & padding to fit smaller screens",
            smallSubtitle = true,
            switch = (
                notchSettings.compactSize to { checked ->
                    scope.launch { dataStoreManager.updateNowNotchSettings { it.copy(compactSize = checked) } }
                }
            ),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Notch Idle Mode", style = typo().labelMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NowNotchIdleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = notchSettings.idleMode == mode,
                        onClick = {
                            scope.launch {
                                dataStoreManager.updateNowNotchSettings { it.copy(idleMode = mode) }
                            }
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Now Notch Top Offset: ${notchSettings.topOffsetDp} dp", style = typo().labelMedium)
            Slider(
                value = notchSettings.topOffsetDp.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        dataStoreManager.updateNowNotchSettings { it.copy(topOffsetDp = value.toInt().coerceIn(0, 48)) }
                    }
                },
                valueRange = 0f..48f,
            )
        }
    }

    SettingItem(
        title = "3D Motion Parallax Artwork",
        subtitle = if (isAndroid) "Subtle gyroscope tilt on Now Playing artwork" else "Subtle 3D cursor tilt on Now Playing artwork",
        smallSubtitle = true,
        switch = (
            motionParallaxEnabled to { checked ->
                scope.launch { dataStoreManager.setMotionParallax(checked) }
            }
        ),
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("App Icon Style", style = typo().labelMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIconStyle.entries.forEach { style ->
                FilterChip(
                    selected = iconStyle == style,
                    onClick = {
                        scope.launch {
                            dataStoreManager.setAppIconStyle(style)
                            applyPlatformAppIcon(style)
                        }
                    },
                    label = { Text(style.label) },
                )
            }
        }
    }

    SettingItem(
        title = "Saved Track Notes (${trackNotes.size})",
        subtitle = "Browse, search, and edit personal song notes with timestamp cues",
        smallSubtitle = true,
        onClick = { showTrackNotesLibrary = true },
    )
}
