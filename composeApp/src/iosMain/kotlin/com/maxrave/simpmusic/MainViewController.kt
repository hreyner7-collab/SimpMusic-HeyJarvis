package com.maxrave.simpmusic

import androidx.compose.ui.window.ComposeUIViewController
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
                    loadAllModules()
                    modules(viewModelModule)
                }
            }
        }
        App()
    }
