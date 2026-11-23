package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.ui.component.SettingItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Stereo widener: a switch and one slider. Makes the sound wider, like a bigger room, without moving
 * it around the way 8D audio does. Works on Windows and Android through the same stored setting.
 */
@Composable
fun StereoWidenerSettings(enabledByCast: Boolean) {
    val dataStore = koinInject<DataStoreManager>()
    val scope = rememberCoroutineScope()
    val enabledRaw by dataStore.stereoWidenEnabled.collectAsState(initial = DataStoreManager.FALSE)
    val savedAmount by dataStore.stereoWidenAmount.collectAsState(initial = 0.5f)
    val enabled = enabledRaw == DataStoreManager.TRUE
    var dragging by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragging >= 0f) dragging else savedAmount

    SettingItem(
        title = "Stereo widener",
        subtitle =
            if (enabledByCast) {
                "Makes the sound wider, like a bigger room"
            } else {
                "Not available while casting"
            },
        smallSubtitle = true,
        switch = (enabled to { on: Boolean -> scope.launch { dataStore.setStereoWidenEnabled(on) }; Unit }),
        isEnable = enabledByCast,
    )
    if (enabled && enabledByCast) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Width ${(shown * 100).roundToInt()}%")
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val value = dragging
                    dragging = -1f
                    if (value >= 0f) scope.launch { dataStore.setStereoWidenAmount(value) }
                },
                valueRange = 0f..1f,
            )
        }
    }
}
