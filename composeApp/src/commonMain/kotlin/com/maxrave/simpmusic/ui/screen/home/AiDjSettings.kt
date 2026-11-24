package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.dj.DjVoice
import com.maxrave.simpmusic.ui.component.SettingItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings for the AI DJ: hosts a session from the listener's own favourites, forgotten tracks,
 * and fresh recommendations with spoken Ace DJ intros.
 */
@Composable
fun AiDjSettings() {
    if (!DjVoice.isAvailable()) return
    val dataStore = koinInject<DataStoreManager>()
    val scope = rememberCoroutineScope()
    val enabledRaw by dataStore.djEnabled.collectAsState(initial = DataStoreManager.FALSE)
    val aiKey by dataStore.aiApiKey.collectAsState(initial = "")
    val tasteProfile by dataStore.tasteProfile.collectAsState(initial = null)
    var tasteResetDone by remember { mutableStateOf(false) }
    val enabled = enabledRaw == DataStoreManager.TRUE

    SettingItem(
        title = "AI DJ",
        subtitle =
            if (aiKey.isBlank()) {
                "Session-based mix of your favorites and fresh picks. Optional AI API key personalizes intros."
            } else {
                "Session-based mix with Ace DJ intros written by your AI provider"
            },
        smallSubtitle = true,
        switch = (enabled to { on: Boolean -> scope.launch { dataStore.setDjEnabled(on) }; Unit }),
    )
    if (tasteProfile != null || tasteResetDone) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            dataStore.setTasteProfile(null)
                            tasteResetDone = true
                        }
                    },
                ) {
                    Text(if (tasteResetDone && tasteProfile == null) "Taste profile cleared" else "Reset AI Taste Profile")
                }
            }
            Text(
                text = "Clears cached listening persona so shared listening doesn't skew recommendations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
