package com.maxrave.simpmusic.portability

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

actual object PortableFileIo {
    actual fun defaultExportDirectory(): String {
        val publicDownloads =
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SimpMusic",
                )
            }.getOrNull()
        if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs()) && publicDownloads.canWrite()) {
            return publicDownloads.absolutePath
        }
        val context = runCatching { getKoin().get<Context>() }.getOrNull()
        val appDocs =
            context?.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context?.filesDir
                ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(appDocs, "SimpMusic").absolutePath
    }

    actual suspend fun writeTextFile(
        fileName: String,
        content: String,
        directory: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseDir = File(directory?.takeIf { it.isNotBlank() } ?: defaultExportDirectory())
                if (!baseDir.exists()) baseDir.mkdirs()
                val target = File(baseDir, fileName)
                target.writeText(content, Charsets.UTF_8)
                target.absolutePath
            }.recoverCatching {
                // Fallback to app-private directory if scoped storage blocks public Downloads
                val context = getKoin().get<Context>()
                val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "SimpMusic")
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                val target = File(fallbackDir, fileName)
                target.writeText(content, Charsets.UTF_8)
                target.absolutePath
            }
        }

    actual suspend fun readTextFile(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = path.trim()
                if (trimmed.startsWith("content://", ignoreCase = true)) {
                    val context = getKoin().get<Context>()
                    context.contentResolver
                        .openInputStream(Uri.parse(trimmed))
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("Could not open content URI: $trimmed")
                } else {
                    val file = File(trimmed)
                    check(file.isFile) { "File not found: $trimmed" }
                    file.readText(Charsets.UTF_8)
                }
            }
        }
}
