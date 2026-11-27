package com.maxrave.simpmusic.alarm

/**
 * Platform wakeup scheduler for [WakeUpAlarmManager].
 *
 * - Android: schedules via `AlarmManager.setExactAndAllowWhileIdle` (falling back gracefully to
 *   `setAndAllowWhileIdle` when `SCHEDULE_EXACT_ALARM` is not granted) targeting `WakeUpAlarmReceiver`.
 * - JVM Desktop: schedules via a single lazy daemon `ScheduledExecutorService` timer.
 * - iOS: safe no-op stub.
 */
expect class WakeUpAlarmScheduler() {
    fun schedule(
        alarmId: String,
        triggerAtEpochMs: Long,
        onTrigger: (String) -> Unit,
    )

    fun cancel(alarmId: String)

    fun cancelAll()
}
