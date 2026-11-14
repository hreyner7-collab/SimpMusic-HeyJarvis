package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/** Android-only "Hey Jerry" voice control opt-in. Desktop actual is never shown. */
@Composable
expect fun JerrySettingsSheet(onDismiss: () -> Unit)
