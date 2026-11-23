package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.dj.DjSessionState

/**
 * The DJ in the Library, drawn like Spotify's: a solid blue card with a hand-drawn green ring.
 * Tapping it opens the DJ screen, where the music, the DJ and its button live.
 */
@Composable
fun DjCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sessionActive by DjSessionState.active.collectAsState()
    val speaking by DjSessionState.speaking.collectAsState()

    Box(
        modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DjBlue)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(100.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).height(100.dp)) {
                Text("DJ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f))
                Text(
                    if (sessionActive) "Open the DJ" else "Tap to play a mix made for you",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Your favourites, forgotten songs and fresh finds", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                DjRing(Modifier.size(100.dp), speaking = speaking)
            }
        }
    }
}
