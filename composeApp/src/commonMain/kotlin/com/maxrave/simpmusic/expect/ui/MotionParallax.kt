package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.utils.PerformanceRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

private const val MOTION_PARALLAX_KEY = "motion_parallax_enabled"

fun DataStoreManager.motionParallaxFlow(): Flow<Boolean> =
    getString(MOTION_PARALLAX_KEY)
        .map { raw -> raw != DataStoreManager.FALSE }
        .distinctUntilChanged()

suspend fun DataStoreManager.setMotionParallax(enabled: Boolean) {
    putString(MOTION_PARALLAX_KEY, if (enabled) DataStoreManager.TRUE else DataStoreManager.FALSE)
}

@Composable
fun rememberMotionParallaxEnabled(): Boolean {
    val dataStoreManager = koinInject<DataStoreManager>()
    val enabledFlow = remember(dataStoreManager) { dataStoreManager.motionParallaxFlow() }
    val enabled by enabledFlow.collectAsState(true)
    return enabled && !PerformanceRuntime.current.reduceAnimations
}

expect fun Modifier.motionParallaxTilt(
    enabled: Boolean,
    maxTiltDegrees: Float = 6f,
): Modifier
