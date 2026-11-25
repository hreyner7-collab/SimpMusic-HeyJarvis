package org.simpmusic.cast

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.media3.cast.CastPlayer
import androidx.media3.common.Player
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.maxrave.logger.Logger

private const val TAG = "Cast"

@Volatile
private var castContext: CastContext? = null

@Volatile
private var castAvailable: Boolean = false

private fun isMainProcess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return Application.getProcessName() == context.packageName
    }
    val pid = Process.myPid()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
    val processName = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: return true
    return processName == context.packageName
}

/**
 * Safely initializes the Google Cast framework. Never throws — devices/emulators
 * without Google Play services (or a misconfigured Cast receiver) simply end up
 * with casting disabled instead of crashing the app.
 */
fun initCast(context: Context): Boolean {
    val appContext = context.applicationContext
    if (!isMainProcess(appContext)) {
        Logger.d(TAG, "Skipping Cast init on non-main process")
        castAvailable = false
        return false
    }
    castContext?.let { return castAvailable }

    return try {
        val playServicesAvailable =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
                ConnectionResult.SUCCESS
        if (!playServicesAvailable) {
            Logger.d(TAG, "Google Play services unavailable, Cast disabled")
            castAvailable = false
            return false
        }

        castContext = CastContext.getSharedInstance(appContext)
        castAvailable = true
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to initialize CastContext: ${e.message}", e)
        castContext = null
        castAvailable = false
        false
    }
}

fun isCastAvailable(): Boolean = castAvailable

/**
 * Wraps [localPlayer] in a [CastPlayer] so playback can switch seamlessly between
 * local and remote (Cast) output. Falls back to returning [localPlayer] unchanged
 * when Cast isn't available or the CastPlayer fails to build.
 */
fun wrapWithCastPlayer(
    context: Context,
    localPlayer: Player,
): Player {
    if (!castAvailable || !isMainProcess(context.applicationContext)) return localPlayer

    return try {
        CastPlayer.Builder(context.applicationContext)
            .setLocalPlayer(localPlayer)
            .build()
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to create CastPlayer, falling back to local player: ${e.message}", e)
        localPlayer
    }
}

/** The route selector the Cast framework registered for this app's receiver, or null without Cast. */
internal fun castRouteSelector(): MediaRouteSelector? =
    try {
        castContext?.mergedSelector
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to read the Cast route selector: ${e.message}", e)
        null
    }

fun currentCastDeviceName(): String? =
    try {
        castContext
            ?.sessionManager
            ?.currentCastSession
            ?.castDevice
            ?.friendlyName
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to read current cast device name: ${e.message}", e)
        null
    }
