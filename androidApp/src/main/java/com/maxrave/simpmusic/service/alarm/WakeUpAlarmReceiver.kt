package com.maxrave.simpmusic.service.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.alarm.EXTRA_ALARM_ID
import com.maxrave.simpmusic.alarm.WakeUpAlarmManager
import org.koin.mp.KoinPlatform.getKoinOrNull

private const val TAG = "WakeUpAlarmReceiver"
private const val WAKE_LOCK_TIMEOUT_MS = 15_000L
private const val ASYNC_HOLD_MS = 5_000L

/**
 * Receives scheduled Android AlarmManager broadcasts and fires [WakeUpAlarmManager.triggerAlarm]
 * via Koin, holding a brief partial WakeLock via `goAsync()` so playback startup completes
 * cleanly even while the device screen is off.
 */
class WakeUpAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID)?.takeIf { it.isNotBlank() } ?: return
        Logger.i(TAG, "Received wake-up alarm broadcast for '$alarmId'")

        val manager = getKoinOrNull()?.getOrNull<WakeUpAlarmManager>()
        if (manager == null) {
            Logger.w(TAG, "WakeUpAlarmManager not yet registered in Koin; skipping '$alarmId'")
            return
        }

        val wakeLock =
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpMusic:WakeUpAlarm")?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }.getOrNull()

        val pendingResult = goAsync()
        try {
            manager.triggerAlarm(alarmId)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to trigger wake-up alarm '$alarmId': ${e.message}", e)
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
            pendingResult.finish()
            return
        }

        // Keep receiver alive briefly while coroutine loads tracks and starts SimpleMediaService.
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                runCatching { pendingResult.finish() }
            },
            ASYNC_HOLD_MS,
        )
    }
}
