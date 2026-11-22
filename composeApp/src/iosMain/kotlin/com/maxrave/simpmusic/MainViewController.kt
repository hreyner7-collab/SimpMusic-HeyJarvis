package com.maxrave.simpmusic

import androidx.compose.ui.window.ComposeUIViewController
import com.maxrave.common.AppIdentity
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.simpmusic.di.viewModelModule
import org.koin.core.context.startKoin

private var koinStarted = false

fun MainViewController() =
    ComposeUIViewController {
        if (!koinStarted) {
            koinStarted = true
            runCatching {
                startKoin {
                    modules(viewModelModule)
                }
                loadAllModules(
                    AppIdentity(
                        applicationId = "com.maxrave.simpmusic",
                        versionName = "2.6.13",
                        platform = "iOS",
                    ),
                )
            }
        }
        App()
    }
