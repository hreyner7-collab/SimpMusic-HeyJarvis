package com.maxrave.simpmusic

import androidx.compose.ui.window.ComposeUIViewController
import com.maxrave.common.AppIdentity
import com.maxrave.common.Config
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.simpmusic.di.viewModelModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

private var koinStarted = false

/**
 * Bindings that Android (media3) and Desktop (media-jvm) provide from their own player modules but
 * which iOS has no equivalent module for: the service coroutine scope and the player cache.
 * They must be registered BEFORE `loadAllModules`, because the repository module's
 * `createdAtStart` singletons resolve them as soon as it is loaded.
 */
private val iosPlatformModule =
    module {
        single<CoroutineScope>(qualifier = named(Config.SERVICE_SCOPE)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        single<CacheRepository> {
            object : CacheRepository {
                override suspend fun getCacheSize(cacheName: String): Long = 0L

                override fun clearCache(cacheName: String) {}

                override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()

                override suspend fun getFullyCachedSongIds(): List<String> = emptyList()

                override suspend fun removeCachedSong(videoId: String) {}
            }
        }
    }

fun MainViewController() =
    ComposeUIViewController {
        if (!koinStarted) {
            koinStarted = true
            startKoin {
                modules(iosPlatformModule, viewModelModule)
            }
            runCatching {
                loadAllModules(
                    AppIdentity(
                        applicationId = "com.maxrave.simpmusic",
                        versionName = "2.6.13",
                        platform = "iOS",
                    ),
                )
            }.onFailure { println("SimpMusic iOS: loadAllModules failed: $it\n${it.stackTraceToString()}") }
        }
        App()
    }
