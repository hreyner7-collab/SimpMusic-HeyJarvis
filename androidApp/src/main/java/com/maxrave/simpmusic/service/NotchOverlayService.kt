package com.maxrave.simpmusic.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.notch.NowNotchIdleMode
import com.maxrave.simpmusic.notch.NowNotchLiveState
import com.maxrave.simpmusic.notch.NowNotchStateHolder
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android-only Dynamic Notch / Island floating music pill inspired by Nooki.
 * Supports live synced lyrics, track-change banner popups, idle clock/dot collapse,
 * top offset & compact sizing, and tap-to-expand playback controls.
 */
class NotchOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootPill: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var controlsExpanded = false
    private var lastAppliedOffsetDp = Int.MIN_VALUE
    private var lastAppliedCompact: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            NowNotchStateHolder.state.collectLatest { state ->
                if (!state.settings.enabled ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@NotchOverlayService))
                ) {
                    stopSelf()
                    return@collectLatest
                }
                renderState(state)
            }
        }
        serviceScope.launch {
            while (isActive) {
                val current = NowNotchStateHolder.state.value
                if (!current.hasTrack && current.settings.idleMode == NowNotchIdleMode.COMPACT_CLOCK) {
                    renderState(current)
                }
                delay(15_000L)
            }
        }
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
        ensurePillCreated()
        renderState(NowNotchStateHolder.state.value)
        return START_STICKY
    }

    private fun ensurePillCreated() {
        if (rootPill != null) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val density = resources.displayMetrics.density

        val pill =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#EB101218"))
                        cornerRadius = 24 * density
                        setStroke((1 * density).toInt(), Color.parseColor("#40FFFFFF"))
                    }
                setOnClickListener {
                    val state = NowNotchStateHolder.state.value
                    if (state.hasTrack) {
                        controlsExpanded = !controlsExpanded
                        renderState(state)
                    } else {
                        openMainActivity()
                    }
                }
            }

        val titleView =
            TextView(this).apply {
                tag = "title"
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
            }

        val lyricsView =
            TextView(this).apply {
                tag = "lyrics"
                setTextColor(Color.parseColor("#D8C2F0FF"))
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
            }

        val controlsRow =
            LinearLayout(this).apply {
                tag = "controls"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(0, (6 * density).toInt(), 0, 0)
            }

        fun controlButton(
            tagName: String,
            symbol: String,
            onTap: () -> Unit,
        ): TextView =
            TextView(this).apply {
                tag = tagName
                text = symbol
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(
                    (12 * density).toInt(),
                    (2 * density).toInt(),
                    (12 * density).toInt(),
                    (2 * density).toInt(),
                )
                setOnClickListener { onTap() }
            }

        val vm = runCatching { getKoin().getOrNull<SharedViewModel>() }.getOrNull()
        controlsRow.addView(controlButton("prev", "⏮") { vm?.onUIEvent(UIEvent.Previous) })
        controlsRow.addView(controlButton("playPause", "⏯") { vm?.onUIEvent(UIEvent.PlayPause) })
        controlsRow.addView(controlButton("next", "⏭") { vm?.onUIEvent(UIEvent.Next) })
        controlsRow.addView(controlButton("open", "↗") { openMainActivity() })

        pill.addView(titleView)
        pill.addView(lyricsView)
        pill.addView(controlsRow)

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (10 * density).toInt()
            }

        wm.addView(pill, params)
        rootPill = pill
        layoutParams = params
    }

    private fun renderState(state: NowNotchLiveState) {
        ensurePillCreated()
        val pill = rootPill ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density

        if (!state.hasTrack && state.settings.idleMode == NowNotchIdleMode.HIDDEN) {
            pill.visibility = View.GONE
            return
        }
        pill.visibility = View.VISIBLE

        if (lastAppliedCompact != state.settings.compactSize) {
            lastAppliedCompact = state.settings.compactSize
            val hPad = ((if (state.settings.compactSize) 12 else 16) * density).toInt()
            val vPad = ((if (state.settings.compactSize) 6 else 8) * density).toInt()
            pill.setPadding(hPad, vPad, hPad, vPad)
        }

        if (lastAppliedOffsetDp != state.settings.topOffsetDp) {
            lastAppliedOffsetDp = state.settings.topOffsetDp
            params.y = (state.settings.topOffsetDp * density).toInt()
            runCatching { wm.updateViewLayout(pill, params) }
        }

        val titleView = pill.findViewWithTag<TextView>("title")
        val lyricsView = pill.findViewWithTag<TextView>("lyrics")
        val controlsRow = pill.findViewWithTag<LinearLayout>("controls")
        val playPauseBtn = controlsRow?.findViewWithTag<TextView>("playPause")

        val maxTextWidth = ((if (state.settings.compactSize) 210 else 280) * density).toInt()
        titleView?.maxWidth = maxTextWidth
        lyricsView?.maxWidth = maxTextWidth

        if (!state.hasTrack) {
            controlsExpanded = false
            titleView?.text =
                when (state.settings.idleMode) {
                    NowNotchIdleMode.COMPACT_CLOCK -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    NowNotchIdleMode.COMPACT_DOT -> "♪"
                    NowNotchIdleMode.HIDDEN -> ""
                }
            lyricsView?.visibility = View.GONE
            controlsRow?.visibility = View.GONE
            return
        }

        val prefix = if (state.isPlaying) "▶ " else "⏸ "
        val header =
            state.popupBanner
                ?: if (state.artist.isNotBlank()) "$prefix${state.title} · ${state.artist}" else "$prefix${state.title}"
        titleView?.text = header

        val lyric = state.currentLyricLine?.takeIf { state.settings.showLiveLyrics && it.isNotBlank() }
        lyricsView?.apply {
            text = lyric.orEmpty()
            visibility = if (lyric == null) View.GONE else View.VISIBLE
        }

        playPauseBtn?.text = if (state.isPlaying) "⏸" else "▶"
        controlsRow?.visibility = if (controlsExpanded) View.VISIBLE else View.GONE
    }

    private fun openMainActivity() {
        val launch =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        startActivity(launch)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        rootPill?.let { runCatching { windowManager?.removeView(it) } }
        rootPill = null
        layoutParams = null
    }

    companion object {
        const val ACTION_STOP = "com.maxrave.simpmusic.action.STOP_NOTCH_OVERLAY"
    }
}
