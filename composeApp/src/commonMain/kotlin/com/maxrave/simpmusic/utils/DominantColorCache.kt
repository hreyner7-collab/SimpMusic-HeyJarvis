package com.maxrave.simpmusic.utils

import androidx.compose.ui.graphics.Color
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared CIO HTTP client + bounded in-memory caches so composables (HomeScreen, BrowseScreen,
 * MixForYouScreen, WrappedScreen, ShareTasteSheet, AmbientThemeGlow) don't allocate and tear
 * down separate Ktor CIO thread/connection pools on every recomposition or screen visit.
 */
object DominantColorCache {
    private const val MAX_ENTRIES = 128

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                maxConnectionsCount = 32
                requestTimeout = 15_000
            }
        }
    }

    private val mutex = Mutex()
    private val byteCache = LinkedHashMap<String, ByteArray>()
    private val colorCache = LinkedHashMap<String, Color>()

    suspend fun getBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        mutex.withLock {
            byteCache[url]?.let { return it }
        }
        val fetched =
            runCatching {
                httpClient.get(url).readRawBytes()
            }.getOrNull() ?: return null
        // Skip caching oversized images to avoid retaining megabytes in heap on low-RAM hardware.
        if (fetched.size <= 512 * 1024) {
            mutex.withLock {
                if (byteCache.size >= MAX_ENTRIES) {
                    val oldestKey = byteCache.entries.firstOrNull()?.key
                    if (oldestKey != null) byteCache.remove(oldestKey)
                }
                byteCache[url] = fetched
            }
        }
        return fetched
    }

    suspend fun getColor(url: String): Color? =
        mutex.withLock {
            colorCache[url]
        }

    suspend fun putColor(
        url: String,
        color: Color,
    ) {
        if (url.isBlank()) return
        mutex.withLock {
            if (colorCache.size >= MAX_ENTRIES) {
                val oldestKey = colorCache.entries.firstOrNull()?.key
                if (oldestKey != null) colorCache.remove(oldestKey)
            }
            colorCache[url] = color
        }
    }
}
