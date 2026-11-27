package com.maxrave.simpmusic.autoflow

import kotlinx.serialization.Serializable

@Serializable
enum class AutoFlowArc(
    val label: String,
    val subtitle: String,
) {
    WARMUP_PEAK_COOLDOWN(
        label = "Warm-Up → Peak → Cooldown",
        subtitle = "Builds smoothly to a high-energy middle, then winds down",
    ),
    WORKOUT_RAMP(
        label = "Workout Ramp",
        subtitle = "Starts steady and climbs continuously to maximum intensity",
    ),
    CHILL_WINDDOWN(
        label = "Chill Wind-Down",
        subtitle = "Steps down from upbeat to calmest tracks for late-night listening",
    ),
    PEAK_FIRST(
        label = "Peak First",
        subtitle = "Opens with your highest-energy tracks and tapers gently",
    ),
}

@Serializable
data class TrackEnergyProfile(
    val videoId: String,
    val title: String,
    val estimatedBpm: Int,
    val energyScore: Float,
)
