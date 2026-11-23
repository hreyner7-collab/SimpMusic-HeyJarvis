package com.maxrave.simpmusic.ui.screen.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.domain.repository.DjRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.simpmusic.dj.DjListen
import com.maxrave.simpmusic.dj.DjSessionBuilder
import com.maxrave.simpmusic.dj.DjSessionStarter
import com.maxrave.simpmusic.dj.DjSessionState
import com.maxrave.simpmusic.ui.component.DjBlue
import com.maxrave.simpmusic.ui.component.DjGreen
import com.maxrave.simpmusic.ui.component.DjRing
import com.maxrave.simpmusic.ui.icon.KeyboardArrowDown
import com.maxrave.simpmusic.ui.icon.Mic
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Send
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val SUGGESTIONS =
    listOf("Play something upbeat", "Surprise me", "Something calm", "New songs I haven't heard", "90s hits")

/**
 * The DJ screen, modelled on Spotify's DJ: a blue DJ tile with the green ring in place of cover art
 * (the ring moves while the DJ talks), "Up next" on the tile, the song that is playing, a request
 * box with suggestions, a menu for the voice and commentary, and the DJ button in the bottom corner,
 * which asks for different picks.
 */
@Composable
fun DjScreen(
    innerPadding: PaddingValues,
    navController: NavController,
) {
    val handler = koinInject<MediaPlayerHandler>()
    val dataStore = koinInject<DataStoreManager>()
    val songRepository = koinInject<SongRepository>()
    val commonRepository = koinInject<CommonRepository>()
    val djRepository = koinInject<DjRepository>()
    val searchRepository = koinInject<SearchRepository>()
    val streamRepository = koinInject<StreamRepository>()
    val starter =
        remember {
            DjSessionStarter(
                DjSessionBuilder(songRepository, commonRepository),
                handler,
                djRepository,
                searchRepository,
                songRepository,
                dataStore,
                streamRepository,
            )
        }
    val scope = rememberCoroutineScope()

    val nowPlaying by handler.nowPlayingState.collectAsState()
    val control by handler.controlState.collectAsState()
    val queue by handler.queueData.collectAsState()
    val index by handler.currentSongIndex.collectAsState()
    val speaking by DjSessionState.speaking.collectAsState()
    val status by DjSessionState.status.collectAsState()
    val commentary by dataStore.djCommentary.collectAsState(initial = DataStoreManager.TRUE)
    val voice by dataStore.djVoice.collectAsState(initial = "NATURAL")

    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }

    // Like Spotify, opening the DJ starts it.
    LaunchedEffect(Unit) {
        if (!DjSessionState.active.value) {
            busy = true
            starter.start()
            busy = false
        }
    }

    val title = nowPlaying.mediaItem.metadata.title.orEmpty()
    val artist = nowPlaying.mediaItem.metadata.artist.orEmpty()
    val next = queue?.data?.listTracks?.getOrNull(index + 1)

    fun ask(text: String) {
        if (text.isBlank() || busy) return
        scope.launch {
            busy = true
            starter.request(text.trim())
            busy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DjBlue.copy(alpha = 0.6f), Color(0xFF0A0F1E), Color.Black))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding() + 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: close, the title, and the menu.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(SimpIcons.KeyboardArrowDown, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    "DJ",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SimpIcons.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pick your DJ") },
                            onClick = {
                                menuOpen = false
                                pickVoice = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("DJ commentary: " + if (commentary == DataStoreManager.TRUE) "On" else "Off") },
                            onClick = {
                                menuOpen = false
                                scope.launch { dataStore.setDjCommentary(commentary != DataStoreManager.TRUE) }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // The DJ tile, where the cover art would be.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                val side = if (maxWidth < 340.dp) maxWidth else 340.dp
                Box(
                    Modifier
                        .size(side)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DjBlue),
                ) {
                    Text(
                        "DJ",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    )
                    DjRing(Modifier.align(Alignment.Center).size(side * 0.78f), speaking = speaking)
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text("Up next", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text(
                            next?.title ?: "DJ",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // The song that is playing.
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                Text(
                    title.ifBlank { if (busy) "Getting your DJ ready..." else "Nothing playing" },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1)
                Text(
                    when {
                        speaking -> "DJ is talking..."
                        !status.isNullOrBlank() -> status.orEmpty()
                        else -> ""
                    },
                    color = DjGreen,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Transport.
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { handler.onPlayerEvent(PlayerEvent.Previous) } }) {
                    Icon(SimpIcons.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { scope.launch { handler.onPlayerEvent(PlayerEvent.PlayPause) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (control.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                        contentDescription = "Play or pause",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { scope.launch { handler.onPlayerEvent(PlayerEvent.Next) } }) {
                    Icon(SimpIcons.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Ask the DJ: type a request, or pick a suggestion.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it },
                    placeholder = { Text("Ask DJ for anything") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Speak a request: the system's speech recognition listens once.
                            if (DjListen.isAvailable()) {
                                IconButton(
                                    enabled = !listening && !busy,
                                    onClick = {
                                        scope.launch {
                                            listening = true
                                            DjSessionState.setStatus("Listening... say what you want to hear")
                                            val heard = DjListen.listen()
                                            listening = false
                                            if (heard == null) {
                                                DjSessionState.setStatus(
                                                    "I did not catch that. If it keeps failing, allow desktop apps to use the microphone in Windows Privacy settings.",
                                                )
                                            } else {
                                                request = heard
                                                ask(heard)
                                                request = ""
                                            }
                                        }
                                    },
                                ) { Icon(SimpIcons.Mic, contentDescription = "Speak", tint = if (listening) Color.Red else DjGreen) }
                            }
                            IconButton(onClick = {
                                ask(request)
                                request = ""
                            }) { Icon(SimpIcons.Send, contentDescription = "Send", tint = DjGreen) }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { suggestion ->
                        SuggestionChip(onClick = { ask(suggestion) }, label = { Text(suggestion) })
                    }
                }
            }
        }

        // The DJ button, bottom right: a tap asks for different picks.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Get some different DJ picks",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DjBlue)
                    .clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            starter.start()
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                DjRing(Modifier.size(52.dp), speaking = busy || speaking)
            }
        }
    }

    if (pickVoice) {
        AlertDialog(
            onDismissRequest = { pickVoice = false },
            title = { Text("Pick your DJ") },
            text = {
                Column {
                    listOf("NATURAL" to "Natural voice (offline, downloads once)", "SYSTEM" to "System voice").forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { scope.launch { dataStore.setDjVoice(key) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = voice == key, onClick = { scope.launch { dataStore.setDjVoice(key) } })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickVoice = false }) { Text("Done") } },
        )
    }
}
