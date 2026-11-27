package com.maxrave.simpmusic.alarm

import com.maxrave.logger.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "WakeUpAlarmSchedulerJvm"

actual class WakeUpAlarmScheduler actual constructor() {
    private val futures = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // Lazily created single daemon thread so zero threads exist when no alarms are scheduled.
    private val executorLock = Any()
    @Volatile
    private var executor: ScheduledExecutorService? = null

    private fun ensureExecutor(): ScheduledExecutorService =
        executor ?: synchronized(executorLock) {
            executor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "SimpMusic-AlarmScheduler").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY + 1
                }
            }.also { executor = it }
        }

    actual fun schedule(
        alarmId: String,
        triggerAtEpochMs: Long,
        onTrigger: (String) -> Unit,
    ) {
        cancel(alarmId)
        val delayMs = (triggerAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val future =
            ensureExecutor().schedule(
                {
                    futures.remove(alarmId)
                    runCatching { onTrigger(alarmId) }
                        .onFailure { Logger.e(TAG, "Alarm callback failed for '$alarmId': ${it.message}", it) }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        futures[alarmId] = future
        Logger.i(TAG, "Scheduled desktop alarm '$alarmId' in ${delayMs}ms")
    }

    actual fun cancel(alarmId: String) {
        futures.remove(alarmId)?.cancel(false)
    }

    actual fun cancelAll() {
        val entries = futures.keys.toList()
        entries.forEach { cancel(it) }
        synchronized(executorLock) {
            executor?.shutdownNow()
            executor = null
        }
    }
}
