package com.maxrave.simpmusic.portability

expect object PortableFileIo {
    fun defaultExportDirectory(): String

    suspend fun writeTextFile(
        fileName: String,
        content: String,
        directory: String? = null,
    ): Result<String>

    suspend fun readTextFile(path: String): Result<String>
}

internal fun sanitizeExportFileName(rawName: String): String {
    val cleaned =
        rawName
            .trim()
            .replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .trim('_', '.', ' ')
    return cleaned.ifBlank { "playlist_export" }.take(80)
}
