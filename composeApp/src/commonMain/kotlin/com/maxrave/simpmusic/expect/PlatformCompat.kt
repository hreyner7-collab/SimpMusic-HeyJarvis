package com.maxrave.simpmusic.expect

expect fun platformOsName(): String

expect fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R

expect fun platformCurrentTimeMillis(): Long

expect fun platformNanoTime(): Long

expect fun platformIsRtl(text: String): Boolean
