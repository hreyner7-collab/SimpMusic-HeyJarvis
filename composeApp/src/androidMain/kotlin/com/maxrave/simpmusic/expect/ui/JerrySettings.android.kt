package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maxrave.simpmusic.jerry.JerryVoiceService
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.jerry_allow_mic
import simpmusic.composeapp.generated.resources.jerry_desc
import simpmusic.composeapp.generated.resources.jerry_listening
import simpmusic.composeapp.generated.resources.jerry_mic_needed
import simpmusic.composeapp.generated.resources.jerry_off
import simpmusic.composeapp.generated.resources.jerry_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun JerrySettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listening by JerryVoiceService.listening.collectAsState()
    val prefs = remember { context.getSharedPreferences(JerryVoiceService.PREFS, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(JerryVoiceService.KEY_ENABLED, false)) }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var micGranted by remember { mutableStateOf(hasMic()) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted && enabled) JerryVoiceService.start(context)
    }
    LaunchedEffect(Unit) { micGranted = hasMic() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(text = stringResource(Res.string.jerry_title), style = typo().titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(Res.string.jerry_desc), style = typo().bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (listening) Res.string.jerry_listening else Res.string.jerry_off),
                    style = typo().bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        enabled = want
                        if (want) {
                            if (micGranted) JerryVoiceService.start(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            JerryVoiceService.stop(context)
                        }
                    },
                )
            }
            if (!micGranted) {
                Spacer(Modifier.height(12.dp))
                Text(text = stringResource(Res.string.jerry_mic_needed), style = typo().bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(stringResource(Res.string.jerry_allow_mic))
                }
            }
        }
    }
}
