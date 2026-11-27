package com.maxrave.simpmusic.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.maxrave.logger.Logger
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WakeUpAlarmSchedulerAndroid"
private const val RECEIVER_CLASS = "com.maxrave.simpmusic.service.alarm.WakeUpAlarmReceiver"

actual class WakeUpAlarmScheduler actual constructor() {
    private val scheduledIds = ConcurrentHashMap.newKeySet<String>()

    actual fun schedule(
        alarmId: String,
        triggerAtEpochMs: Long,
        onTrigger: (String) -> Unit,
    ) {
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context, alarmId, createIfMissing = true) ?: return

        scheduledIds.add(alarmId)
        val canExact =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }

        if (canExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtEpochMs,
                    pendingIntent,
                )
                Logger.i(TAG, "Scheduled exact alarm '$alarmId' at $triggerAtEpochMs")
                return
            } catch (se: SecurityException) {
                Logger.w(TAG, "Exact alarm permission revoked; falling back to inexact alarm: ${se.message}")
            } catch (e: Exception) {
                Logger.w(TAG, "setExactAndAllowWhileIdle failed; falling back: ${e.message}")
            }
        }

        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMs,
                pendingIntent,
            )
            Logger.i(TAG, "Scheduled inexact allow-while-idle alarm '$alarmId' at $triggerAtEpochMs")
        }.onFailure {
            Logger.e(TAG, "Failed to schedule alarm '$alarmId': ${it.message}", it)
        }
    }

    actual fun cancel(alarmId: String) {
        scheduledIds.remove(alarmId)
        val context = runCatching { getKoin().get<Context>() }.getOrNull() ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context, alarmId, createIfMissing = false) ?: return
        runCatching {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    actual fun cancelAll() {
        val ids = scheduledIds.toList()
        ids.forEach { cancel(it) }
        scheduledIds.clear()
    }

    private fun buildPendingIntent(
        context: Context,
        alarmId: String,
        createIfMissing: Boolean,
    ): PendingIntent? {
        val intent =
            Intent(ACTION_WAKE_UP_ALARM)
                .setClassName(context.packageName, RECEIVER_CLASS)
                .putExtra(EXTRA_ALARM_ID, alarmId)
        val flags =
            if (createIfMissing) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            }
        val requestCode = alarmId.hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
