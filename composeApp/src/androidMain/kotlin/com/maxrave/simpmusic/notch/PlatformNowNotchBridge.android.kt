package com.maxrave.simpmusic.notch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.koin.mp.KoinPlatform.getKoin

private const val NOTCH_SERVICE_CLASS = "com.maxrave.simpmusic.service.NotchOverlayService"
private const val MAIN_ACTIVITY_CLASS = "com.maxrave.simpmusic.MainActivity"
private const val LEGACY_LIVE_LYRICS_CHANNEL_ID = "simpmusic_live_lyrics"
private const val LEGACY_LIVE_LYRICS_CHANNEL_V2_ID = "simpmusic_live_lyrics_v2"
private const val LIVE_LYRICS_CHANNEL_ID = "simpmusic_live_lyrics_v3"
private const val LIVE_LYRICS_NOTIFICATION_ID = 44210
private const val NOW_BAR_SHORT_TEXT_MAX_LEN = 8
private const val OVERLAY_START_COOLDOWN_MS = 1_500L
private const val PROGRESS_UPDATE_MIN_INTERVAL_MS = 1_500L

actual object PlatformNowNotchBridge {
    private var lastOverlayStartAttemptMs = 0L
    private var lastLyricSignature: String? = null
    private var lastNotifyTimeMs = 0L
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

    actual fun canPostPromotedNotifications(): Boolean {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return if (Build.VERSION.SDK_INT >= 36) {
            runCatching { nm.canPostPromotedNotifications() }.getOrElse { nm.areNotificationsEnabled() }
        } else {
            nm.areNotificationsEnabled()
        }
    }

    actual fun openLiveNotificationsSettings() {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= 36) {
            val promoIntent =
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(promoIntent) }.isSuccess) return
        }
        runCatching {
            val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    actual fun sync(state: NowNotchLiveState) {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return

        // 1. 1-to-1 Nooki Dynamic Island / Now Notch specialUse foreground overlay service.
        val shouldRunOverlay = state.settings.enabled && canDrawOverlay()
        val nowMs = SystemClock.elapsedRealtime()
        if (shouldRunOverlay) {
            if (!NowNotchStateHolder.isOverlayServiceRunning && (nowMs - lastOverlayStartAttemptMs) >= OVERLAY_START_COOLDOWN_MS) {
                lastOverlayStartAttemptMs = nowMs
                val intent =
                    Intent().setClassName(context.packageName, NOTCH_SERVICE_CLASS).apply {
                        putExtra("extra_title", state.title.ifBlank { "SimpMusic" })
                    }
                runCatching {
                    ContextCompat.startForegroundService(context, intent)
                }.onFailure {
                    runCatching { context.startService(intent) }
                }
            }
        } else if (NowNotchStateHolder.isOverlayServiceRunning || lastOverlayStartAttemptMs > 0L) {
            lastOverlayStartAttemptMs = 0L
            runCatching {
                context.stopService(Intent().setClassName(context.packageName, NOTCH_SERVICE_CLASS))
            }
        }

        // 2. Native Android 16 / Samsung One UI ProgressStyle Live Update & Lock-Screen live lyrics notification.
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
            if (lastLyricSignature != null) {
                nm.cancel(LIVE_LYRICS_NOTIFICATION_ID)
                lastLyricSignature = null
                lastNotifyTimeMs = 0L
            }
            return
        }

        val activeLyric = state.currentLyricLine?.takeIf { it.isNotBlank() }
        val nextLine = if (activeLyric != null) state.nextLyricLine?.takeIf { it.isNotBlank() } else null
        val trackTitle = state.title.ifBlank { "SimpMusic" }
        val trackSummary =
            if (state.artist.isNotBlank()) "$trackTitle • ${state.artist}" else trackTitle

        val primaryTitle = activeLyric ?: trackTitle
        val secondaryLine = nextLine ?: trackSummary
        val bigTextBody = if (nextLine != null) "$nextLine\n$trackSummary" else trackSummary
        val shortChipText =
            activeLyric?.let(::formatNowBarShortText)
                ?: if (trackTitle.trim().length <= NOW_BAR_SHORT_TEXT_MAX_LEN) trackTitle.trim() else "♪ Lyrics"
        val progressPercent = (state.progressFraction * 100f).toInt().coerceIn(0, 100)
        val barColor = state.accentColorArgb.toInt()

        val lyricSignature = "$primaryTitle|$secondaryLine|$shortChipText|$trackSummary|$barColor"
        val nowMs = SystemClock.elapsedRealtime()
        val contentChanged = lyricSignature != lastLyricSignature
        if (!contentChanged && (nowMs - lastNotifyTimeMs) < PROGRESS_UPDATE_MIN_INTERVAL_MS) return

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
                val segment = Notification.ProgressStyle.Segment(100).setColor(barColor)
                val progressStyle =
                    Notification
                        .ProgressStyle()
                        .setProgressSegments(listOf(segment))
                        .setStyledByProgress(true)
                        .setProgress(progressPercent)

                Notification
                    .Builder(context, LIVE_LYRICS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setColor(barColor)
                    .setContentTitle(primaryTitle)
                    .setContentText(secondaryLine)
                    .apply {
                        if (activeLyric != null) {
                            setSubText(trackSummary)
                        }
                    }.setStyle(progressStyle)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .setRequestPromotedOngoing(true)
                    .setShortCriticalText(shortChipText)
                    .setContentIntent(contentIntent)
                    .build()
            } else {
                NotificationCompat
                    .Builder(context, LIVE_LYRICS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setColor(barColor)
                    .setContentTitle(primaryTitle)
                    .setContentText(secondaryLine)
                    .apply {
                        if (activeLyric != null) {
                            setSubText(trackSummary)
                        }
                    }.setStyle(
                        NotificationCompat
                            .BigTextStyle()
                            .setBigContentTitle(primaryTitle)
                            .bigText(bigTextBody),
                    ).setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(contentIntent)
                    .build()
            }

        runCatching {
            nm.notify(LIVE_LYRICS_NOTIFICATION_ID, notification)
            lastLyricSignature = lyricSignature
            lastNotifyTimeMs = nowMs
        }
    }

    private fun ensureLiveUpdateChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelCreated) return
        // Clean up legacy channels so One UI creates the silent IMPORTANCE_HIGH ProgressStyle channel fresh.
        runCatching { nm.deleteNotificationChannel(LEGACY_LIVE_LYRICS_CHANNEL_ID) }
        runCatching { nm.deleteNotificationChannel(LEGACY_LIVE_LYRICS_CHANNEL_V2_ID) }
        val channel =
            NotificationChannel(
                LIVE_LYRICS_CHANNEL_ID,
                "Samsung Now Bar & Lock-Screen Live Lyrics",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Silent high-priority Android 16 ProgressStyle Live Updates for Samsung Now Bar"
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
            if (lastSpace >= 3) {
                slice.substring(0, lastSpace)
            } else {
                slice
            }
        return "${trimmed.trimEnd()}…"
    }
}
