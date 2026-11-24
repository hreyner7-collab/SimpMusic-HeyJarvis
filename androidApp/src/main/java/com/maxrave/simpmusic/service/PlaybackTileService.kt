package com.maxrave.simpmusic.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import org.koin.mp.KoinPlatform.getKoinOrNull

/**
 * Android Quick Settings tile showing current track status and toggling Play/Pause
 * (or opening SimpMusic if nothing is currently loaded).
 */
@RequiresApi(Build.VERSION_CODES.N)
class PlaybackTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val handler = getKoinOrNull()?.getOrNull<MediaPlayerHandler>()
        val vm = getKoinOrNull()?.getOrNull<SharedViewModel>()
        if (handler != null && vm != null && handler.nowPlaying.value != null) {
            vm.onUIEvent(UIEvent.PlayPause)
            qsTile?.apply {
                val nowPlaying = !handler.controlState.value.isPlaying
                state = if (nowPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = "SimpMusic"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    subtitle = handler.nowPlaying.value?.metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Playing"
                }
                updateTile()
            }
            return
        }

        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val handler = getKoinOrNull()?.getOrNull<MediaPlayerHandler>()
        val isPlaying = handler?.controlState?.value?.isPlaying == true
        val trackTitle = handler?.nowPlaying?.value?.metadata?.title?.toString()?.takeIf { it.isNotBlank() }
        qsTile?.apply {
            state = if (isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "SimpMusic"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = trackTitle ?: "Tap to open"
            }
            updateTile()
        }
    }
}
