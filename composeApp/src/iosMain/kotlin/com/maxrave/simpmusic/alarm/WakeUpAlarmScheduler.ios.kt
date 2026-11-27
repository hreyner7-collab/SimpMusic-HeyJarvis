package com.maxrave.simpmusic.alarm

actual class WakeUpAlarmScheduler actual constructor() {
    actual fun schedule(
        alarmId: String,
        triggerAtEpochMs: Long,
        onTrigger: (String) -> Unit,
    ) = Unit

    actual fun cancel(alarmId: String) = Unit

    actual fun cancelAll() = Unit
}
