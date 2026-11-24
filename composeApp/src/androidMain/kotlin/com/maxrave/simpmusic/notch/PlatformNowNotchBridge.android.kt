package com.maxrave.simpmusic.notch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.koin.mp.KoinPlatform.getKoin

private const val NOTCH_SERVICE_CLASS = "com.maxrave.simpmusic.service.NotchOverlayService"
private const val MAIN_ACTIVITY_CLASS = "com.maxrave.simpmusic.MainActivity"
private const val ACTION_STOP_NOTCH = "com.maxrave.simpmusic.action.STOP_NOTCH_OVERLAY"
private const val LEGACY_LIVE_LYRICS_CHANNEL_ID = "simpmusic_live_lyrics"
private const val LIVE_LYRICS_CHANNEL_ID = "simpmusic_live_lyrics_v2"
private const val LIVE_LYRICS_NOTIFICATION_ID = 44210
private const val NOW_BAR_SHORT_TEXT_MAX_LEN = 22

actual object PlatformNowNotchBridge {
    private var lastServiceRunning = false
    private var lastNotifiedKey: String? = null
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

        // 1. 1-to-1 Nooki Dynamic Island / Now Notch overlay service.
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

        // 2. Native Android 16 / Samsung One UI Now Bar Live Update & Lock-Screen live lyrics notification.
        syncLockScreenLiveLyricsNotification(context, state)
    }

    private fun syncLockScreenLiveLyricsNotification(
        context: Context,
        state: NowNotchLiveState,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val shouldShow =
            state.settings.lockScreenLiveLyrics &&
                state.hasTrack &&
                state.isPlaying

        if (!shouldShow) {
            if (lastNotifiedKey != null) {
                nm.cancel(LIVE_LYRICS_NOTIFICATION_ID)
                lastNotifiedKey = null
            }
            return
        }

        val activeLyric = state.currentLyricLine?.takeIf { it.isNotBlank() }
        val trackTitle = state.title.ifBlank { "SimpMusic" }
        val trackSummary =
            if (state.artist.isNotBlank()) "$trackTitle • ${state.artist}" else trackTitle

        val primaryTitle = activeLyric ?: trackTitle
        val secondaryBody =
            if (activeLyric != null) {
                trackSummary
            } else if (state.artist.isNotBlank()) {
                "${state.artist} • Live Lyrics"
            } else {
                "Now Playing • Live Lyrics"
            }
        val shortChipText = formatNowBarShortText(activeLyric ?: trackTitle)
        val key = "$primaryTitle|$secondaryBody|$shortChipText"
        if (key == lastNotifiedKey) return

        ensureLiveUpdateChannel(nm)

        val contentIntent =
            PendingIntent.getActivity(
                context,
                LIVE_LYRICS_NOTIFICATION_ID,
                Intent()
                    .setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            if (Build.VERSION.SDK_INT >= 36) {
                Notification
                    .Builder(context, LIVE_LYRICS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(primaryTitle)
                    .setContentText(secondaryBody)
                    .setStyle(
                        Notification
                            .BigTextStyle()
                            .setBigContentTitle(primaryTitle)
                            .bigText(secondaryBody),
                    ).setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setRequestPromotedOngoing(true)
                    .setShortCriticalText(shortChipText)
                    .setContentIntent(contentIntent)
                    .build()
            } else {
                NotificationCompat
                    .Builder(context, LIVE_LYRICS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(primaryTitle)
                    .setContentText(secondaryBody)
                    .setStyle(
                        NotificationCompat
                            .BigTextStyle()
                            .setBigContentTitle(primaryTitle)
                            .bigText(secondaryBody),
                    ).setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(contentIntent)
                    .build()
            }

        runCatching {
            nm.notify(LIVE_LYRICS_NOTIFICATION_ID, notification)
            lastNotifiedKey = key
        }
    }

    private fun ensureLiveUpdateChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelCreated) return
        // Clean up legacy IMPORTANCE_LOW channel so Android 16 / One UI Live Update promotion can activate.
        runCatching { nm.deleteNotificationChannel(LEGACY_LIVE_LYRICS_CHANNEL_ID) }
        val channel =
            NotificationChannel(
                LIVE_LYRICS_CHANNEL_ID,
                "Samsung Now Bar & Lock-Screen Live Lyrics",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Promoted silent live-lyrics update for Samsung Now Bar and Lock Screen"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        nm.createNotificationChannel(channel)
        channelCreated = true
    }

    private fun formatNowBarShortText(raw: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length <= NOW_BAR_SHORT_TEXT_MAX_LEN) return cleaned
        val slice = cleaned.take(NOW_BAR_SHORT_TEXT_MAX_LEN - 1)
        val lastSpace = slice.lastIndexOf(' ')
        val trimmed =
            if (lastSpace >= NOW_BAR_SHORT_TEXT_MAX_LEN / 2) {
                slice.substring(0, lastSpace)
            } else {
                slice
            }
        return "${trimmed.trimEnd()}…"
    }
}
