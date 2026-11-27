package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

interface BluetoothLightPermissionRequester {
    fun requestIfNeeded()
}

@Composable
expect fun rememberBluetoothLightPermission(onResult: (granted: Boolean) -> Unit): BluetoothLightPermissionRequester
