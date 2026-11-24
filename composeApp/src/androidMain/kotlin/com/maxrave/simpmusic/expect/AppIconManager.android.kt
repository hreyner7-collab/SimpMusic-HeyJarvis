package com.maxrave.simpmusic.expect

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon

private val aliasMap =
    mapOf(
        AppIconStyle.DEFAULT to "com.maxrave.simpmusic.LauncherDefault",
        AppIconStyle.OBSIDIAN to "com.maxrave.simpmusic.LauncherObsidian",
        AppIconStyle.NEON to "com.maxrave.simpmusic.LauncherNeon",
    )

actual fun applyPlatformAppIcon(style: AppIconStyle) {
    val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
    val pm = context.packageManager
    val targetClass = aliasMap[style] ?: return

    // Enable the new launcher alias first so the launcher always retains a valid entry point.
    runCatching {
        pm.setComponentEnabledSetting(
            ComponentName(context, targetClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
    aliasMap.forEach { (candidate, clsName) ->
        if (candidate != style) {
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context, clsName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}

@Composable
actual fun rememberAppIconPainter(style: AppIconStyle): Painter = painterResource(Res.drawable.circle_app_icon)
