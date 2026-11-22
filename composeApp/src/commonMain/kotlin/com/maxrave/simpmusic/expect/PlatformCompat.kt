package com.maxrave.simpmusic.expect

expect fun platformOsName(): String

expect fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R

expect fun platformCurrentTimeMillis(): Long

expect fun platformNanoTime(): Long

expect fun platformIsRtl(text: String): Boolean

expect fun platformFormatEpochMillis(epochMillis: Long): String

expect fun platformFileLength(path: String): Long

expect fun platformReadText(path: String): String

expect fun platformReadBytesRange(
    path: String,
    from: Long,
): ByteArray
