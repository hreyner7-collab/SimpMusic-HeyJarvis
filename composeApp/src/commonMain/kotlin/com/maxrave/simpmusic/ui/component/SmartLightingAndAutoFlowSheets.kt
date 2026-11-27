package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.simpmusic.autoflow.AutoFlowArc
import com.maxrave.simpmusic.autoflow.AutoFlowQueueManager
import com.maxrave.simpmusic.autoflow.AutoFlowQueueSorter
import com.maxrave.simpmusic.autoflow.TrackEnergyProfile
import com.maxrave.simpmusic.expect.ui.rememberBluetoothLightPermission
import com.maxrave.simpmusic.lighting.LightingProtocol
import com.maxrave.simpmusic.lighting.LightingReactiveMode
import com.maxrave.simpmusic.lighting.RgbColor
import com.maxrave.simpmusic.lighting.SmartLightDevice
import com.maxrave.simpmusic.lighting.SmartLightingManager
import com.maxrave.simpmusic.lighting.formatFriendlyLightName
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import multiplatform.network.cmptoast.ToastDuration
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun SmartLightingSection(
    manager: SmartLightingManager,
    modifier: Modifier = Modifier,
) {
    val cfg by manager.config.collectAsStateWithLifecycle()
    val status by manager.statusMessage.collectAsStateWithLifecycle()
    val activeCue by manager.activeCue.collectAsStateWithLifecycle()
    val lastColor by manager.lastColor.collectAsStateWithLifecycle()
    val discovered by manager.discoveredDevices.collectAsStateWithLifecycle()
    val isScanning by manager.isScanning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var showFineTune by remember { mutableStateOf(false) }

    val permissionRequester =
        rememberBluetoothLightPermission { granted ->
            if (granted) {
                manager.scanForLights()
            } else {
                showToast(
                    "Allow Nearby Devices permission to find Govee lights",
                    duration = ToastDuration.Short,
                    gravity = ToastGravity.Bottom,
                )
            }
        }

    val liveColor =
        if (cfg.enabled && (lastColor.r > 0 || lastColor.g > 0 || lastColor.b > 0)) {
            Color(lastColor.r, lastColor.g, lastColor.b)
        } else {
            MaterialTheme.colorScheme.primary
        }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Live Glowing DJ Header
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = liveColor.copy(alpha = if (cfg.enabled) 0.14f else 0.06f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(if (cfg.enabled) liveColor else MaterialTheme.colorScheme.outlineVariant),
                        )
                        Column {
                            Text("DJ Room Lights", style = typo().titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = activeCue ?: status ?: "Govee Bluetooth & Wi-Fi Disco Sync",
                                style = typo().bodySmall,
                                color = if (activeCue != null) liveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { manager.setEnabled(it) },
                    )
                }
            }

            // 2. Prominent 1-Tap Find My Lights Button
            Button(
                onClick = { permissionRequester.requestIfNeeded() },
                enabled = !isScanning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Scanning Bluetooth & Wi-Fi...")
                } else {
                    Text("Find My Lights (1-Tap Connect)")
                }
            }

            // 3. Discovered Lights
            if (discovered.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Found Nearby", style = typo().labelMedium, color = MaterialTheme.colorScheme.primary)
                    discovered.forEach { dev ->
                        val alreadyAdded = cfg.devices.any { it.protocol == dev.protocol && it.host.equals(dev.host, ignoreCase = true) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatFriendlyLightName(dev.name), style = typo().bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${dev.protocol.displayName} • ${dev.host}",
                                        style = typo().labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val res = manager.blinkDevice(dev)
                                            showToast(
                                                if (res.isSuccess) "Blinked ${formatFriendlyLightName(dev.name)}" else "Blink failed: ${res.exceptionOrNull()?.message}",
                                                duration = ToastDuration.Short,
                                                gravity = ToastGravity.Bottom,
                                            )
                                        }
                                    },
                                ) {
                                    Text("Blink")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        manager.quickConnectDevice(dev)
                                        showToast(
                                            "Connected ${formatFriendlyLightName(dev.name)} in DJ Disco mode",
                                            duration = ToastDuration.Short,
                                            gravity = ToastGravity.Bottom,
                                        )
                                    },
                                ) {
                                    Text(if (alreadyAdded) "Connected" else "Connect")
                                }
                            }
                        }
                    }
                }
            }

            // 4. Connected Devices + Quick Color Test Bar
            if (cfg.devices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connected Lights", style = typo().labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cfg.devices.forEach { dev ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatFriendlyLightName(dev.name), style = typo().bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${dev.protocol.displayName} • ${dev.host}",
                                        style = typo().labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { scope.launch { manager.blinkDevice(dev) } }) {
                                    Text("Blink")
                                }
                                if (dev.protocol == LightingProtocol.GOVEE_BLE) {
                                    TextButton(onClick = { scope.launch { manager.turnOffDevice(dev) } }) {
                                        Text("Off")
                                    }
                                }
                                Switch(
                                    checked = dev.enabled,
                                    onCheckedChange = { manager.toggleDevice(dev.id) },
                                )
                                TextButton(onClick = { manager.removeDevice(dev.id) }) {
                                    Text("×")
                                }
                            }
                        }
                    }

                    // Quick Color Test Palette
                    val palette =
                        listOf(
                            "Red" to RgbColor(255, 10, 20),
                            "Orange" to RgbColor(255, 115, 0),
                            "Purple" to RgbColor(200, 0, 255),
                            "Cyan" to RgbColor(0, 210, 255),
                            "Green" to RgbColor(0, 255, 90),
                            "White" to RgbColor(255, 245, 235),
                        )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        palette.forEach { (_, rgb) ->
                            Box(
                                modifier =
                                    Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(rgb.r, rgb.g, rgb.b))
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                        .clickable { scope.launch { manager.setManualColor(rgb) } },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { scope.launch { manager.turnOffAllDevices() } },
                            label = { Text("Off") },
                        )
                    }
                }
            }

            // 5. 2x2 DJ Mode Grid
            val modes =
                listOf(
                    LightingReactiveMode.DJ_DISCO to ("DJ Disco" to "Bass=Red, Pitch=Colors"),
                    LightingReactiveMode.BASS_FLASH to ("Bass Flash" to "Punches on beat drops"),
                    LightingReactiveMode.SPECTRUM_PULSE to ("Spectrum" to "Full RGB frequency mix"),
                    LightingReactiveMode.AMBIENT_FLOW to ("Ambient" to "Smooth mood transitions"),
                )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { (mode, labels) ->
                            val selected = cfg.mode == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clickable { manager.setMode(mode) },
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        labels.first,
                                        style = typo().labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        labels.second,
                                        style = typo().labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Collapsible Fine-Tune & Manual Add
            TextButton(
                onClick = { showFineTune = !showFineTune },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (showFineTune) "Hide Fine-Tune" else "Fine-Tune & Manual Add")
            }

            AnimatedVisibility(visible = showFineTune) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(
                            "Brightness: ${(cfg.brightness * 100).roundToInt()}%",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = cfg.brightness,
                            onValueChange = { manager.setBrightness(it) },
                            valueRange = 0.05f..1.0f,
                        )
                    }
                    Column {
                        Text(
                            "Bass Sensitivity: ${(cfg.sensitivity * 100).roundToInt()}%",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = cfg.sensitivity,
                            onValueChange = { manager.setSensitivity(it) },
                            valueRange = 0.25f..3.0f,
                        )
                    }
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("+ Add Light Manually")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSmartLightDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { device ->
                manager.addDevice(device)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddSmartLightDialog(
    onDismiss: () -> Unit,
    onAdd: (SmartLightDevice) -> Unit,
) {
    var name by remember { mutableStateOf("Desk LED Strip") }
    var host by remember { mutableStateOf("192.168.1.100") }
    var protocol by remember { mutableStateOf(LightingProtocol.WLED_UDP) }
    var portText by remember { mutableStateOf(protocol.defaultPort.toString()) }
    var ledCountText by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Smart Light") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LightingProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = {
                                protocol = proto
                                portText = proto.defaultPort.toString()
                            },
                            label = { Text(proto.displayName) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("LAN IP / Hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = ledCountText,
                        onValueChange = { ledCountText = it.filter(Char::isDigit) },
                        label = { Text("LEDs") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: protocol.defaultPort
                    val leds = ledCountText.toIntOrNull()?.coerceIn(1, 300) ?: 60
                    if (host.isNotBlank()) {
                        onAdd(
                            SmartLightDevice(
                                id = "light-${Random.nextInt(100000, 999999)}",
                                name = name.ifBlank { protocol.displayName },
                                host = host.trim(),
                                port = port,
                                protocol = protocol,
                                ledCount = leds,
                                enabled = true,
                            ),
                        )
                    }
                },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoFlowQueueSheet(
    manager: AutoFlowQueueManager,
    tracks: List<Track>? = null,
    playlistTitle: String? = null,
    onDismiss: () -> Unit,
) {
    var selectedArc by remember { mutableStateOf(AutoFlowArc.WARMUP_PEAK_COOLDOWN) }
    var preview by remember { mutableStateOf<List<TrackEnergyProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedArc, tracks) {
        preview =
            if (tracks != null) {
                AutoFlowQueueSorter.previewEnergyCurve(tracks, selectedArc)
            } else {
                manager.previewCurrentQueue(selectedArc)
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Auto-Flow DJ Queue Sorter", style = typo().titleLarge)
            Text(
                selectedArc.subtitle,
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoFlowArc.entries.forEach { arc ->
                    FilterChip(
                        selected = selectedArc == arc,
                        onClick = { selectedArc = arc },
                        label = { Text(arc.label) },
                    )
                }
            }

            if (preview.isNotEmpty()) {
                val lineColor = MaterialTheme.colorScheme.primary
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp).padding(12.dp)) {
                        if (preview.size == 1) {
                            val y = size.height * (1f - preview.first().energyScore)
                            drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(size.width / 2f, y))
                        } else {
                            val path = Path()
                            preview.forEachIndexed { idx, p ->
                                val x = (idx.toFloat() / (preview.size - 1)) * size.width
                                val y = size.height * (1f - p.energyScore.coerceIn(0f, 1f))
                                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(preview) { idx, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${idx + 1}. ${item.title}",
                                style = typo().bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${item.estimatedBpm} BPM • ${(item.energyScore * 100).roundToInt()}%",
                                style = typo().labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            } else {
                Text(
                    "Start playing a queue or playlist with 2+ upcoming tracks to preview the energy curve.",
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val res =
                            if (tracks != null) {
                                manager.playTracksWithAutoFlow(
                                    tracks = tracks,
                                    playlistTitle = playlistTitle ?: "Auto-Flow",
                                    arc = selectedArc,
                                )
                            } else {
                                manager.applyAutoFlowToCurrentQueue(selectedArc)
                            }
                        showToast(
                            res.fold(
                                onSuccess = { count -> "Auto-Flow applied to $count track(s)" },
                                onFailure = { err -> err.message ?: "Could not sort queue" },
                            ),
                            duration = ToastDuration.Short,
                            gravity = ToastGravity.Bottom,
                        )
                        if (res.isSuccess) onDismiss()
                    }
                },
                enabled = preview.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (tracks != null) "Play with Auto-Flow" else "Apply Auto-Flow to Queue")
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
