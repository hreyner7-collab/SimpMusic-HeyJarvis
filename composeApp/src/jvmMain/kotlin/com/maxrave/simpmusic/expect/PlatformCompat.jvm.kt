package com.maxrave.simpmusic.expect

import java.text.Bidi

actual fun platformOsName(): String = System.getProperty("os.name") ?: "jvm"

actual fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R = kotlin.synchronized(lock, block)

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformNanoTime(): Long = System.nanoTime()

actual fun platformIsRtl(text: String): Boolean = Bidi.requiresBidi(text.toCharArray(), 0, text.length)
