package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberBluetoothLightPermission(onResult: (granted: Boolean) -> Unit): BluetoothLightPermissionRequester {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        object : BluetoothLightPermissionRequester {
            override fun requestIfNeeded() {
                currentOnResult(true)
            }
        }
    }
}
