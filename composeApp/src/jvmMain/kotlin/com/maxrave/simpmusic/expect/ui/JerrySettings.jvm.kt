package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

// Desktop has no Hey Jerry; the settings row is Android-only.
@Composable
actual fun JerrySettingsSheet(onDismiss: () -> Unit) {}
