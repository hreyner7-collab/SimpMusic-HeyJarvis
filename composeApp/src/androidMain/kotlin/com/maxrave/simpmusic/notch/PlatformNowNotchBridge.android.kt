package com.maxrave.simpmusic.notch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.koin.mp.KoinPlatform.getKoin

private const val NOTCH_SERVICE_CLASS = "com.maxrave.simpmusic.service.NotchOverlayService"
private const val ACTION_STOP_NOTCH = "com.maxrave.simpmusic.action.STOP_NOTCH_OVERLAY"
private const val LIVE_LYRICS_CHANNEL_ID = "simpmusic_live_lyrics"
private const val LIVE_LYRICS_NOTIFICATION_ID = 44210

actual object PlatformNowNotchBridge {
    private var lastServiceRunning = false
    private var lastNotifiedText: String? = null
    private var channelCreated = false

    actual fun canDrawOverlay(): Boolean {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    actual fun requestOverlayPermission() {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent =
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    actual fun sync(state: NowNotchLiveState) {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return

        // 1. Floating Dynamic Notch overlay service (only start/stop on state transition or when enabled with permission).
        val shouldRunOverlay = state.settings.enabled && canDrawOverlay()
        if (shouldRunOverlay) {
            if (!lastServiceRunning) {
                runCatching {
                    val intent =
                        Intent().setClassName(context.packageName, NOTCH_SERVICE_CLASS).apply {
                            putExtra("extra_title", state.title.ifBlank { "SimpMusic" })
                        }
                    context.startService(intent)
                    lastServiceRunning = true
                }
            }
        } else if (lastServiceRunning) {
            runCatching {
                val intent =
                    Intent().setClassName(context.packageName, NOTCH_SERVICE_CLASS).apply {
                        action = ACTION_STOP_NOTCH
                    }
                context.startService(intent)
            }
            lastServiceRunning = false
        }

        // 2. Lock-screen & Samsung Now Bar live lyrics silent notification.
        syncLockScreenLiveLyricsNotification(context, state)
    }

    private fun syncLockScreenLiveLyricsNotification(
        context: Context,
        state: NowNotchLiveState,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val lyric = state.currentLyricLine?.takeIf { it.isNotBlank() }
        val shouldShow =
            state.settings.lockScreenLiveLyrics &&
                state.hasTrack &&
                state.isPlaying &&
                lyric != null

        if (!shouldShow) {
            if (lastNotifiedText != null) {
                nm.cancel(LIVE_LYRICS_NOTIFICATION_ID)
                lastNotifiedText = null
            }
            return
        }

        val trackSummary =
            if (state.artist.isNotBlank()) "${state.title} • ${state.artist}" else state.title
        val key = "$lyric|$trackSummary"
        if (key == lastNotifiedText) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            val channel =
                NotificationChannel(
                    LIVE_LYRICS_CHANNEL_ID,
                    "Live Synced Lyrics (Now Bar / Lock Screen)",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            nm.createNotificationChannel(channel)
            channelCreated = true
        }

        val notification =
            NotificationCompat
                .Builder(context, LIVE_LYRICS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(lyric)
                .setContentText(trackSummary)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

        runCatching {
            nm.notify(LIVE_LYRICS_NOTIFICATION_ID, notification)
            lastNotifiedText = key
        }
    }
}
