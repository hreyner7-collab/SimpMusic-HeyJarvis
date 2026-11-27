package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberBluetoothLightPermission(onResult: (granted: Boolean) -> Unit): BluetoothLightPermissionRequester {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    val permissions =
        remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = permissions.all { perm -> results[perm] == true }
            currentOnResult(allGranted)
        }

    return remember(context, launcher, permissions) {
        object : BluetoothLightPermissionRequester {
            override fun requestIfNeeded() {
                val alreadyGranted =
                    permissions.all { perm ->
                        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    }
                if (alreadyGranted) {
                    currentOnResult(true)
                } else {
                    launcher.launch(permissions)
                }
            }
        }
    }
}
