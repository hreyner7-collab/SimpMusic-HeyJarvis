package com.maxrave.simpmusic.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
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

private const val OVERLAY_CHANNEL_ID = "simpmusic_notch_overlay_service"
private const val OVERLAY_NOTIFICATION_ID = 44211

/**
 * 1-to-1 Jetpack Compose Dynamic Island / Now Notch overlay service matching Nooki's
 * NotchOverlayService: runs as a specialUse foreground service so Android/One UI does not
 * kill or hide it when swiping home, rendering [NookiNotchIsland] inside a hardware-accelerated
 * ComposeView over the Samsung punch-hole camera cutout.
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
        // Call startForeground immediately when started via startForegroundService, even before checks.
        enterForegroundSafe()

        if (intent?.action == ACTION_STOP) {
            NowNotchStateHolder.isOverlayServiceRunning = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            NowNotchStateHolder.isOverlayServiceRunning = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        attachComposeOverlayIfNeeded()
        NowNotchStateHolder.isOverlayServiceRunning = true
        return START_STICKY
    }

    private fun enterForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(OVERLAY_CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    OVERLAY_CHANNEL_ID,
                    "Now Notch Floating Overlay",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Keeps the floating Now Notch overlay active above other apps"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            nm.createNotificationChannel(channel)
        }

        val openIntent =
            PendingIntent.getActivity(
                this,
                OVERLAY_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(this, OVERLAY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Now Notch Active")
                .setContentText("Tap to open SimpMusic")
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openIntent)
                .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    OVERLAY_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(OVERLAY_NOTIFICATION_ID, notification)
            }
        }
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
            }.apply {
                id = android.R.id.content
                setViewTreeLifecycleOwner(this@NotchOverlayService)
                setViewTreeViewModelStoreOwner(this@NotchOverlayService)
                setViewTreeSavedStateRegistryOwner(this@NotchOverlayService)
            }

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
                    LaunchedEffect(state.settings.topOffsetDp) {
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
        NowNotchStateHolder.isOverlayServiceRunning = false
        collapseCallback = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
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
