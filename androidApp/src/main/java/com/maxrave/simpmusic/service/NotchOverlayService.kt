package com.maxrave.simpmusic.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.notch.NookiNotchIsland
import com.maxrave.simpmusic.notch.NowNotchStateHolder
import com.maxrave.simpmusic.notch.updateNowNotchSettings
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoinOrNull

/**
 * 1-to-1 Jetpack Compose Dynamic Island / Now Notch overlay service matching Nooki's
 * NotchOverlayService: renders [NookiNotchIsland] inside a hardware-accelerated ComposeView
 * positioned cleanly over the Samsung punch-hole camera cutout, with outside-tap auto-collapse.
 */
class NotchOverlayService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore
        get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var rootFrame: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var lastAppliedOffsetDp: Int = Int.MIN_VALUE
    private var collapseCallback: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        attachComposeOverlayIfNeeded()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return START_STICKY
    }

    private fun attachComposeOverlayIfNeeded() {
        if (rootFrame != null) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val density = resources.displayMetrics.density

        val wrapper =
            object : FrameLayout(this) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        collapseCallback?.invoke()
                    }
                    return super.onTouchEvent(event)
                }
            }
        wrapper.setViewTreeLifecycleOwner(this)
        wrapper.setViewTreeViewModelStoreOwner(this)
        wrapper.setViewTreeSavedStateRegistryOwner(this)

        val cv =
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(this@NotchOverlayService)
                setViewTreeViewModelStoreOwner(this@NotchOverlayService)
                setViewTreeSavedStateRegistryOwner(this@NotchOverlayService)
                setContent {
                    val state by NowNotchStateHolder.state.collectAsState()
                    var expanded by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(expanded) {
                        collapseCallback = { expanded = false }
                    }
                    LaunchedEffect(state.settings.enabled, state.settings.topOffsetDp) {
                        if (!state.settings.enabled) {
                            stopSelf()
                            return@LaunchedEffect
                        }
                        val targetOffset = state.settings.topOffsetDp
                        if (lastAppliedOffsetDp != targetOffset) {
                            lastAppliedOffsetDp = targetOffset
                            windowLayoutParams?.let { lp ->
                                lp.y = (targetOffset * density).toInt()
                                runCatching { wm.updateViewLayout(wrapper, lp) }
                            }
                        }
                    }

                    val vm = remember { getKoinOrNull()?.getOrNull<SharedViewModel>() }
                    val handler = remember { getKoinOrNull()?.getOrNull<MediaPlayerHandler>() }
                    val ds = remember { getKoinOrNull()?.getOrNull<DataStoreManager>() }

                    NookiNotchIsland(
                        state = state,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onPrevious = { vm?.onUIEvent(UIEvent.Previous) },
                        onPlayPause = { vm?.onUIEvent(UIEvent.PlayPause) },
                        onNext = { vm?.onUIEvent(UIEvent.Next) },
                        onToggleLike = { handler?.toggleLike() },
                        onSeekFraction = { frac ->
                            val clamped = (frac.coerceIn(0f, 1f) * 100f)
                            vm?.onUIEvent(UIEvent.UpdateProgress(clamped))
                        },
                        onSeekMs = { ms ->
                            val total = state.durationMs
                            if (total > 0L) {
                                val pct = ((ms.toFloat() * 100f) / total.toFloat()).coerceIn(0f, 100f)
                                vm?.onUIEvent(UIEvent.UpdateProgress(pct))
                            }
                        },
                        onOpenApp = { openMainActivity() },
                        onHide = {
                            if (ds != null) {
                                scope.launch { ds.updateNowNotchSettings { it.copy(enabled = false) } }
                            } else {
                                stopSelf()
                            }
                        },
                    )
                }
            }

        wrapper.addView(
            cv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                windowAnimations = 0
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (NowNotchStateHolder.state.value.settings.topOffsetDp * density).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

        wm.addView(wrapper, params)
        rootFrame = wrapper
        composeView = cv
        windowLayoutParams = params
    }

    private fun openMainActivity() {
        val launch =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        startActivity(launch)
    }

    override fun onDestroy() {
        collapseCallback = null
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        composeView?.disposeComposition()
        rootFrame?.let { runCatching { windowManager?.removeView(it) } }
        store.clear()
        composeView = null
        rootFrame = null
        windowLayoutParams = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.maxrave.simpmusic.action.STOP_NOTCH_OVERLAY"
    }
}
