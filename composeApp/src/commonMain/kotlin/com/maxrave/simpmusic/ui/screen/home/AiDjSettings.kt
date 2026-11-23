package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.dj.DjVoice
import com.maxrave.simpmusic.ui.component.SettingItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings for the AI DJ: between songs a short spoken intro, written by the user's own AI provider
 * and read by the system voice. It stays silent until an AI provider is set up in Settings.
 */
@Composable
fun AiDjSettings() {
    if (!DjVoice.isAvailable()) return
    val dataStore = koinInject<DataStoreManager>()
    val scope = rememberCoroutineScope()
    val enabledRaw by dataStore.djEnabled.collectAsState(initial = DataStoreManager.FALSE)
    val every by dataStore.djEvery.collectAsState(initial = 3)
    val aiKey by dataStore.aiApiKey.collectAsState(initial = "")
    val enabled = enabledRaw == DataStoreManager.TRUE

    SettingItem(
        title = "AI DJ",
        subtitle =
            if (aiKey.isBlank()) {
                "Speaks a short intro between songs. Needs an AI provider key (Settings > AI)."
            } else {
                "Speaks a short intro between songs, written by your AI and read by the system voice"
            },
        smallSubtitle = true,
        switch = (enabled to { on: Boolean -> scope.launch { dataStore.setDjEnabled(on) }; Unit }),
    )
    if (enabled) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Speak before")
            Row {
                listOf(1 to "every song", 3 to "every 3rd", 5 to "every 5th").forEach { (value, label) ->
                    FilterChip(
                        selected = every == value,
                        onClick = { scope.launch { dataStore.setDjEvery(value) } },
                        label = { Text(label) },
                    )
                    Row(Modifier.width(8.dp)) {}
                }
            }
        }
    }
}
