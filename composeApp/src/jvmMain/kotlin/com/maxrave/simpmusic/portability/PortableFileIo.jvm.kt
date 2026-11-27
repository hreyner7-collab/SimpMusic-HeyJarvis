package com.maxrave.simpmusic.portability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object PortableFileIo {
    actual fun defaultExportDirectory(): String =
        File(File(System.getProperty("user.home"), "Downloads"), "SimpMusic").absolutePath

    actual suspend fun writeTextFile(
        fileName: String,
        content: String,
        directory: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(directory?.takeIf { it.isNotBlank() } ?: defaultExportDirectory())
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, fileName)
                target.writeText(content, Charsets.UTF_8)
                target.absolutePath
            }
        }

    actual suspend fun readTextFile(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path.trim())
                check(file.isFile) { "File not found: ${file.path}" }
                file.readText(Charsets.UTF_8)
            }
        }
}
