package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.viewModel.SpotifyOwnAppViewModel
import org.koin.compose.viewmodel.koinViewModel

private const val DEFAULT_SPOTIFY_CLIENT_ID = "9e744a63aa5441139d9fad6d5f9ee4f7"

private const val REDIRECT_URI = "simpmusic://spotify-auth"

/**
 * Spotify sync through the user's own Spotify developer app. The shared web-player login is rate
 * limited for everyone who uses it; an app registered by the user has its own quota.
 */
@Composable
fun SpotifyOwnAppDialog(onDismiss: () -> Unit) {
    val viewModel = koinViewModel<SpotifyOwnAppViewModel>()
    val savedClientId by viewModel.clientId.collectAsState()
    val loggedIn by viewModel.loggedIn.collectAsState()
    val message by viewModel.message.collectAsState()
    val uriHandler = LocalUriHandler.current

    var clientId by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    LaunchedEffect(savedClientId) {
        if (clientId.isEmpty()) clientId = savedClientId.ifBlank { DEFAULT_SPOTIFY_CLIENT_ID }
    }

    val close = {
        viewModel.clearMessage()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Spotify: use your own app") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Spotify limits how often the shared login can be used, which causes the rate-limit " +
                        "error. Your own Spotify app has its own limit.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Open developer.spotify.com/dashboard and create an app (a Premium account is required).\n" +
                        "2. Add this Redirect URI to it and save: $REDIRECT_URI\n" +
                        "3. Paste the app's Client ID here, then press Log in.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://developer.spotify.com/dashboard") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Spotify dashboard") }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.start(clientId) { url -> uriHandler.openUri(url) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log in with Spotify") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Link the page ended on (only if needed)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { viewModel.complete(pasted) }) { Text("Finish login") }
                if (loggedIn) {
                    Text("Status: logged in with your own Spotify app.")
                    OutlinedButton(
                        onClick = { viewModel.signOut() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Log out of your own app") }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
