package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.alarm.AlarmTargetType
import com.maxrave.simpmusic.alarm.WakeUpAlarm
import com.maxrave.simpmusic.alarm.WakeUpAlarmManager
import com.maxrave.simpmusic.expect.copyToClipboard
import com.maxrave.simpmusic.expect.openUrl
import com.maxrave.simpmusic.hotkeys.GlobalHotkeyManager
import com.maxrave.simpmusic.hotkeys.HotkeyAction
import com.maxrave.simpmusic.hotkeys.HotkeyBinding
import com.maxrave.simpmusic.hotkeys.HotkeyModifiers
import com.maxrave.simpmusic.hotkeys.HotkeyShortcutFormatter
import com.maxrave.simpmusic.ui.icon.AccessAlarm
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.ContentCopy
import com.maxrave.simpmusic.ui.icon.Delete
import com.maxrave.simpmusic.ui.icon.Edit
import com.maxrave.simpmusic.ui.icon.MusicNote
import com.maxrave.simpmusic.ui.icon.OpenInNew
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Refresh
import com.maxrave.simpmusic.ui.icon.RestartAlt
import com.maxrave.simpmusic.ui.icon.Sensors
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Smartphone
import com.maxrave.simpmusic.ui.icon.Tune
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.webremote.LanWebRemoteManager
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ============================================================================
// 1. Musical Wake-Up Alarm UI
// ============================================================================

@Composable
fun WakeUpAlarmRingingBanner(
    manager: WakeUpAlarmManager,
    modifier: Modifier = Modifier,
) {
    val activeAlarm by manager.activeRingingAlarm.collectAsState()
    val fadeProgress by manager.currentFadeProgress.collectAsState()
    val alarm = activeAlarm ?: return

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SimpIcons.AccessAlarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${alarm.label} · ${alarm.formattedTime()}",
                        style = typo().titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Playing ${alarm.targetTitle} · Fade ${(fadeProgress * 100f).roundToInt()}%",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (alarm.fadeInSeconds > 0 && fadeProgress < 1f) {
                LinearProgressIndicator(
                    progress = { fadeProgress.coerceIn(0f, 1f) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { manager.snoozeActiveAlarm() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Snooze +${alarm.snoozeMinutes}m")
                }
                TextButton(
                    onClick = { manager.dismissActiveAlarm(pausePlayback = false) },
                ) {
                    Text("Keep Playing")
                }
                Button(
                    onClick = { manager.dismissActiveAlarm(pausePlayback = true) },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun WakeUpAlarmSection(
    manager: WakeUpAlarmManager,
    modifier: Modifier = Modifier,
) {
    val alarms by manager.alarms.collectAsState()
    val nextEpochMs by manager.nextScheduledEpochMs.collectAsState()
    val playlists by manager.availablePlaylists.collectAsState()
    val songs by manager.availableSongs.collectAsState()

    var editingAlarm by remember { mutableStateOf<WakeUpAlarm?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.refreshTargets()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = SimpIcons.AccessAlarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = "Musical Wake-Up Alarm",
                        style = typo().titleMedium,
                    )
                    val subtitle =
                        nextEpochMs?.let { epoch ->
                            val dt = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(TimeZone.currentSystemDefault())
                            val h = dt.hour.toString().padStart(2, '0')
                            val m = dt.minute.toString().padStart(2, '0')
                            "Next alarm: ${dt.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} at $h:$m"
                        } ?: "Gentle fade-in wake-up with your music"
                    Text(
                        text = subtitle,
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = {
                    editingAlarm = null
                    showEditor = true
                },
            ) {
                Icon(
                    imageVector = SimpIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        if (alarms.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = SimpIcons.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "No wake-up alarms yet",
                        style = typo().labelMedium,
                    )
                    Text(
                        text = "Tap Add to wake up to Liked Songs, a Local Playlist, or a favorite track with smooth volume fade-in.",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                alarms.forEach { alarm ->
                    WakeUpAlarmRow(
                        alarm = alarm,
                        onToggle = { enabled -> manager.toggleAlarm(alarm.id, enabled) },
                        onTest = { manager.testAlarm(alarm.id) },
                        onEdit = {
                            editingAlarm = alarm
                            showEditor = true
                        },
                        onDelete = { manager.deleteAlarm(alarm.id) },
                    )
                }
            }
        }
    }

    if (showEditor) {
        WakeUpAlarmEditorDialog(
            initial = editingAlarm,
            availablePlaylists = playlists,
            availableSongs = songs,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                if (editingAlarm == null) {
                    manager.addAlarm(saved)
                } else {
                    manager.updateAlarm(saved)
                }
                showEditor = false
            },
        )
    }
}

@Composable
private fun WakeUpAlarmRow(
    alarm: WakeUpAlarm,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border =
            if (alarm.enabled) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            } else {
                null
            },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = alarm.formattedTime(),
                            style = typo().headlineMedium,
                            color =
                                if (alarm.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            text = alarm.label,
                            style = typo().labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Text(
                        text = "${alarm.daysSummary()} · ${alarm.targetTitle}",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Fade-in ${alarm.fadeInSeconds}s · Vol ${(alarm.targetVolume * 100f).roundToInt()}% · Snooze ${alarm.snoozeMinutes}m",
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onTest) {
                    Icon(SimpIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test Ring")
                }
                IconButton(onClick = onEdit) {
                    Icon(SimpIcons.Edit, contentDescription = "Edit alarm", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        SimpIcons.Delete,
                        contentDescription = "Delete alarm",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WakeUpAlarmEditorDialog(
    initial: WakeUpAlarm?,
    availablePlaylists: List<Pair<Long, String>>,
    availableSongs: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (WakeUpAlarm) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label ?: "Morning Music") }
    var hour by remember(initial) { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember(initial) { mutableIntStateOf(initial?.minute ?: 0) }
    var daysOfWeek by remember(initial) { mutableStateOf(initial?.daysOfWeek ?: WakeUpAlarm.WEEKDAYS) }
    var targetType by remember(initial) { mutableStateOf(initial?.targetType ?: AlarmTargetType.LIKED_SONGS) }
    var targetId by remember(initial) { mutableStateOf(initial?.targetId) }
    var targetTitle by remember(initial) { mutableStateOf(initial?.targetTitle ?: "Liked Songs") }
    var fadeInSeconds by remember(initial) { mutableFloatStateOf((initial?.fadeInSeconds ?: 45).toFloat()) }
    var targetVolume by remember(initial) { mutableFloatStateOf(initial?.targetVolume ?: 0.8f) }
    var snoozeMinutes by remember(initial) { mutableIntStateOf(initial?.snoozeMinutes ?: 9) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "New Wake-Up Alarm" else "Edit Wake-Up Alarm",
                style = typo().titleMedium,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Alarm label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Time stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeNumberStepper(
                        label = "Hour",
                        value = hour,
                        onDecrement = { hour = (hour + 23) % 24 },
                        onIncrement = { hour = (hour + 1) % 24 },
                    )
                    Text(":", style = typo().headlineLarge)
                    TimeNumberStepper(
                        label = "Min",
                        value = minute,
                        onDecrement = { minute = (minute + 59) % 60 },
                        onIncrement = { minute = (minute + 1) % 60 },
                    )
                }

                // Quick time presets
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(6 to 30, 7 to 0, 7 to 30, 8 to 0).forEach { (h, m) ->
                        val text = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
                        FilterChip(
                            selected = hour == h && minute == m,
                            onClick = {
                                hour = h
                                minute = m
                            },
                            label = { Text(text) },
                        )
                    }
                }

                // Weekday pills (1=Mon..7=Sun)
                Text("Repeat days (leave empty for one-shot)", style = typo().labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (1..7).forEach { isoDay ->
                        val selected = isoDay in daysOfWeek
                        Surface(
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        daysOfWeek =
                                            if (selected) daysOfWeek - isoDay else daysOfWeek + isoDay
                                    },
                            shape = CircleShape,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = WakeUpAlarm.dayInitial(isoDay),
                                    style = typo().labelSmall,
                                    color =
                                        if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = daysOfWeek.isEmpty(),
                        onClick = { daysOfWeek = emptySet() },
                        label = { Text("One-shot") },
                    )
                    FilterChip(
                        selected = daysOfWeek == WakeUpAlarm.WEEKDAYS,
                        onClick = { daysOfWeek = WakeUpAlarm.WEEKDAYS },
                        label = { Text("Weekdays") },
                    )
                    FilterChip(
                        selected = daysOfWeek == WakeUpAlarm.EVERY_DAY,
                        onClick = { daysOfWeek = WakeUpAlarm.EVERY_DAY },
                        label = { Text("Every day") },
                    )
                }

                // Target music picker
                Text("Wake-up music source", style = typo().labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = targetType == AlarmTargetType.LIKED_SONGS,
                        onClick = {
                            targetType = AlarmTargetType.LIKED_SONGS
                            targetId = null
                            targetTitle = "Liked Songs"
                        },
                        label = { Text("Liked Songs") },
                    )
                    FilterChip(
                        selected = targetType == AlarmTargetType.LOCAL_PLAYLIST,
                        onClick = {
                            targetType = AlarmTargetType.LOCAL_PLAYLIST
                            val first = availablePlaylists.firstOrNull()
                            targetId = first?.first?.toString()
                            targetTitle = first?.second ?: "Local Playlist"
                        },
                        label = { Text("Local Playlist") },
                    )
                    FilterChip(
                        selected = targetType == AlarmTargetType.SPECIFIC_SONG,
                        onClick = {
                            targetType = AlarmTargetType.SPECIFIC_SONG
                            val first = availableSongs.firstOrNull()
                            targetId = first?.first
                            targetTitle = first?.second ?: "Selected Song"
                        },
                        label = { Text("Specific Song") },
                    )
                }

                if (targetType == AlarmTargetType.LOCAL_PLAYLIST) {
                    if (availablePlaylists.isEmpty()) {
                        Text(
                            text = "No local playlists found — will fall back to Liked Songs.",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            availablePlaylists.take(8).forEach { (id, title) ->
                                FilterChip(
                                    selected = targetId == id.toString(),
                                    onClick = {
                                        targetId = id.toString()
                                        targetTitle = title
                                    },
                                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                } else if (targetType == AlarmTargetType.SPECIFIC_SONG) {
                    if (availableSongs.isEmpty()) {
                        Text(
                            text = "No library songs cached yet — will fall back to Liked Songs.",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            availableSongs.take(8).forEach { (vid, title) ->
                                FilterChip(
                                    selected = targetId == vid,
                                    onClick = {
                                        targetId = vid
                                        targetTitle = title
                                    },
                                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                }

                // Fade-in duration slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Gentle fade-in", style = typo().labelSmall)
                        Text("${fadeInSeconds.roundToInt()}s", style = typo().labelSmall)
                    }
                    Slider(
                        value = fadeInSeconds,
                        onValueChange = { fadeInSeconds = it },
                        valueRange = 0f..120f,
                        steps = 23,
                    )
                }

                // Target volume slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Target volume", style = typo().labelSmall)
                        Text("${(targetVolume * 100f).roundToInt()}%", style = typo().labelSmall)
                    }
                    Slider(
                        value = targetVolume,
                        onValueChange = { targetVolume = it },
                        valueRange = 0.1f..1.0f,
                    )
                }

                // Snooze duration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Snooze:", style = typo().labelSmall)
                    listOf(5, 9, 10, 15).forEach { mins ->
                        FilterChip(
                            selected = snoozeMinutes == mins,
                            onClick = { snoozeMinutes = mins },
                            label = { Text("${mins}m") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result =
                        WakeUpAlarm(
                            id = initial?.id.orEmpty(),
                            label = label,
                            hour = hour,
                            minute = minute,
                            daysOfWeek = daysOfWeek,
                            enabled = initial?.enabled ?: true,
                            targetType = targetType,
                            targetId = targetId,
                            targetTitle = targetTitle,
                            fadeInSeconds = fadeInSeconds.roundToInt(),
                            targetVolume = targetVolume,
                            snoozeMinutes = snoozeMinutes,
                        ).normalized()
                    onSave(result)
                },
            ) {
                Text("Save Alarm")
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
private fun TimeNumberStepper(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onDecrement,
                modifier = Modifier.size(38.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("-")
            }
            Text(
                text = value.toString().padStart(2, '0'),
                style = typo().headlineMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            OutlinedButton(
                onClick = onIncrement,
                modifier = Modifier.size(38.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("+")
            }
        }
    }
}

// ============================================================================
// 2. Windows Global Hotkeys UI
// ============================================================================

@Composable
fun GlobalHotkeysSection(
    manager: GlobalHotkeyManager,
    modifier: Modifier = Modifier,
) {
    val config by manager.config.collectAsState()
    val registered by manager.registeredActions.collectAsState()
    val duplicates by manager.duplicateWarnings.collectAsState()

    var editingBinding by remember { mutableStateOf<HotkeyBinding?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = SimpIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text("Windows Global Hotkeys", style = typo().titleMedium)
                    Text(
                        text =
                            if (manager.isSupported) {
                                "Control playback & Hey Jarvis from any app (0% idle CPU)"
                            } else {
                                "Available on Windows Desktop (settings still editable)"
                            },
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { manager.setEnabled(it) },
            )
        }

        if (duplicates.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                val summary =
                    duplicates.entries.joinToString("; ") { (combo, actions) ->
                        "${HotkeyShortcutFormatter.format(combo.first, combo.second)}: ${actions.joinToString { it.displayTitle }}"
                    }
                Text(
                    text = "Shortcut conflict detected: $summary",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                config.bindings.forEachIndexed { idx, binding ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    val isActive = binding.action in registered
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = config.enabled) { editingBinding = binding }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = binding.action.displayTitle,
                                style = typo().labelMedium,
                                color =
                                    if (config.enabled && binding.enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            val statusText =
                                when {
                                    !config.enabled || !binding.enabled -> "Disabled"
                                    !manager.isSupported -> "Windows Desktop only"
                                    isActive -> "Registered"
                                    else -> "Unavailable (in use by another app)"
                                }
                            Text(
                                text = statusText,
                                style = typo().bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable { editingBinding = binding },
                        ) {
                            Text(
                                text = binding.displayLabel,
                                style = typo().labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }

                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = binding.enabled,
                            enabled = config.enabled,
                            onCheckedChange = { manager.toggleBinding(binding.action, it) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = { manager.resetToDefaults() }) {
                Icon(SimpIcons.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset to Defaults")
            }
        }
    }

    editingBinding?.let { target ->
        HotkeyBindingPickerDialog(
            binding = target,
            onDismiss = { editingBinding = null },
            onSave = { mods, vk ->
                manager.updateBinding(target.action, mods, vk, enabled = true)
                editingBinding = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotkeyBindingPickerDialog(
    binding: HotkeyBinding,
    onDismiss: () -> Unit,
    onSave: (modifiers: Int, virtualKey: Int) -> Unit,
) {
    var selectedMods by remember(binding) { mutableIntStateOf(binding.modifiers) }
    var selectedVk by remember(binding) { mutableIntStateOf(binding.virtualKey) }
    val presets = remember(binding.action) { HotkeyShortcutFormatter.presetCombinations(binding.action) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Shortcut: ${binding.action.displayTitle}",
                style = typo().titleMedium,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = HotkeyShortcutFormatter.format(selectedMods, selectedVk),
                        style = typo().titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Text("Quick presets", style = typo().labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { (mods, vk) ->
                        FilterChip(
                            selected = selectedMods == mods && selectedVk == vk,
                            onClick = {
                                selectedMods = mods
                                selectedVk = vk
                            },
                            label = { Text(HotkeyShortcutFormatter.format(mods, vk)) },
                        )
                    }
                }

                Text("Modifiers", style = typo().labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        HotkeyModifiers.MOD_CONTROL to "Ctrl",
                        HotkeyModifiers.MOD_ALT to "Alt",
                        HotkeyModifiers.MOD_SHIFT to "Shift",
                        HotkeyModifiers.MOD_WIN to "Win",
                    ).forEach { (flag, label) ->
                        val active = (selectedMods and flag) != 0
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedMods =
                                    if (active) selectedMods and flag.inv() else selectedMods or flag
                            },
                            label = { Text(label) },
                        )
                    }
                }

                Text("Key", style = typo().labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HotkeyShortcutFormatter.selectableKeys.forEach { (vk, label) ->
                        FilterChip(
                            selected = selectedVk == vk,
                            onClick = { selectedVk = vk },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedMods, selectedVk) },
                enabled = selectedMods != 0,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ============================================================================
// 3. PIN-Protected LAN Web Remote UI
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanWebRemoteSection(
    manager: LanWebRemoteManager,
    modifier: Modifier = Modifier,
) {
    val config by manager.config.collectAsState()
    val isRunning by manager.isRunning.collectAsState()
    val serverUrl by manager.serverUrl.collectAsState()
    val lastError by manager.lastError.collectAsState()
    var copiedBanner by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = SimpIcons.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("LAN Web Remote", style = typo().titleMedium)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color =
                                if (isRunning) {
                                    Color(0xFF30D158).copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isRunning) {
                                                    Color(0xFF30D158)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            ),
                                )
                                Text(
                                    text = if (isRunning) "Active" else "Stopped",
                                    style = typo().bodySmall,
                                    color =
                                        if (isRunning) {
                                            Color(0xFF30D158)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                    }
                    Text(
                        text =
                            if (manager.isSupported) {
                                "Control SimpMusic from any phone or browser on your Wi-Fi"
                            } else {
                                "LAN HTTP server is supported on Android and Desktop"
                            },
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = config.enabled,
                enabled = manager.isSupported,
                onCheckedChange = { manager.setEnabled(it) },
            )
        }

        if (lastError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = lastError.orEmpty(),
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Live URL card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val activeUrl = serverUrl
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = SimpIcons.Sensors,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("Remote URL", style = typo().labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = activeUrl ?: "Enable LAN Web Remote to publish URL",
                            style =
                                typo().labelMedium.copy(
                                    fontFamily = if (activeUrl != null) FontFamily.Monospace else FontFamily.Default,
                                ),
                            color =
                                if (activeUrl != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier =
                                if (activeUrl != null) {
                                    Modifier.clickable { openUrl(activeUrl) }
                                } else {
                                    Modifier
                                },
                        )
                    }
                    if (activeUrl != null) {
                        Row {
                            IconButton(
                                onClick = {
                                    copyToClipboard("SimpMusic Web Remote URL", activeUrl)
                                    copiedBanner = "Copied URL to clipboard"
                                },
                            ) {
                                Icon(SimpIcons.ContentCopy, contentDescription = "Copy URL", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { openUrl(activeUrl) }) {
                                Icon(SimpIcons.OpenInNew, contentDescription = "Open URL", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // PIN display + regenerate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("4-Digit Access PIN", style = typo().labelSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = config.pin.toCharArray().joinToString(" "),
                            style =
                                typo().headlineMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = {
                                copyToClipboard("SimpMusic Web Remote PIN", config.pin)
                                copiedBanner = "Copied PIN ${config.pin}"
                            },
                        ) {
                            Icon(SimpIcons.ContentCopy, contentDescription = "Copy PIN", modifier = Modifier.size(18.dp))
                        }
                        FilledTonalButton(onClick = {
                            manager.regeneratePin()
                            copiedBanner = "Generated a fresh 4-digit PIN"
                        }) {
                            Icon(SimpIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Regenerate")
                        }
                    }
                }

                // Port preset chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Port:", style = typo().labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(8765, 8088, 9090).forEach { port ->
                            FilterChip(
                                selected = config.port == port,
                                onClick = { manager.setPort(port) },
                                label = { Text(port.toString()) },
                            )
                        }
                    }
                }

                if (copiedBanner != null) {
                    Text(
                        text = copiedBanner.orEmpty(),
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = "Every control request requires this PIN via X-Remote-Pin header. Never expose this port to the public internet.",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
