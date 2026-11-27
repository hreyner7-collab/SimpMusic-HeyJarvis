package com.maxrave.simpmusic.utils.cache

import android.content.Context
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

private fun homeCacheFile(): File? =
    runCatching {
        val context = getKoin().get<Context>()
        File(context.cacheDir, HOME_DISK_CACHE_FILENAME)
    }.getOrNull()

internal actual fun readHomeDiskCacheText(): String? =
    runCatching {
        val file = homeCacheFile() ?: return null
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

internal actual fun writeHomeDiskCacheText(content: String) {
    runCatching {
        val file = homeCacheFile() ?: return
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
        homeCacheFile()?.let { if (it.exists()) it.delete() }
    }
}
