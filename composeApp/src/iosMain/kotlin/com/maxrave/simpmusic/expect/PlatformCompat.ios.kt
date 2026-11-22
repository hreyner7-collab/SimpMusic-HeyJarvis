package com.maxrave.simpmusic.expect

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

actual fun platformOsName(): String = "iOS"

actual fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R = block()

@OptIn(ExperimentalTime::class)
actual fun platformCurrentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val nanoOrigin = TimeSource.Monotonic.markNow()

actual fun platformNanoTime(): Long = nanoOrigin.elapsedNow().inWholeNanoseconds

actual fun platformIsRtl(text: String): Boolean =
    text.any { ch ->
        val code = ch.code
        (code in 0x0590..0x08FF) || (code in 0xFB1D..0xFDFF) || (code in 0xFE70..0xFEFF)
    }

actual fun platformFormatEpochMillis(epochMillis: Long): String = epochMillis.toString()

actual fun platformFileLength(path: String): Long = 0L

actual fun platformReadText(path: String): String = ""

actual fun platformReadBytesRange(
    path: String,
    from: Long,
): ByteArray = ByteArray(0)
