package com.maxrave.simpmusic.expect

import java.io.File
import java.io.RandomAccessFile
import java.text.Bidi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual fun platformOsName(): String = System.getProperty("os.name") ?: "android"

actual fun <R> platformSynchronized(
    lock: Any,
    block: () -> R,
): R = kotlin.synchronized(lock, block)

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformNanoTime(): Long = System.nanoTime()

actual fun platformIsRtl(text: String): Boolean = Bidi.requiresBidi(text.toCharArray(), 0, text.length)

actual fun platformFormatEpochMillis(epochMillis: Long): String =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

actual fun platformFileLength(path: String): Long = File(path).let { if (it.exists()) it.length() else 0L }

actual fun platformReadText(path: String): String = File(path).let { if (it.exists()) it.readText() else "" }

actual fun platformReadBytesRange(
    path: String,
    from: Long,
): ByteArray {
    val file = File(path)
    if (!file.exists()) return ByteArray(0)
    return RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length <= from) return@use ByteArray(0)
        val bytes = ByteArray((length - from).toInt())
        raf.seek(from)
        raf.readFully(bytes)
        bytes
    }
}
