package com.maxrave.simpmusic.utils.cache

import com.maxrave.data.io.getHomeFolderPath
import java.io.File

private fun homeCacheFile(): File =
    File(getHomeFolderPath(listOf(".simpmusic", "cache")), HOME_DISK_CACHE_FILENAME)

internal actual fun readHomeDiskCacheText(): String? =
    runCatching {
        val file = homeCacheFile()
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

internal actual fun writeHomeDiskCacheText(content: String) {
    runCatching {
        val file = homeCacheFile()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}

internal actual fun clearHomeDiskCacheFile() {
    runCatching {
        val file = homeCacheFile()
        if (file.exists()) file.delete()
    }
}
