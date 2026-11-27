package com.maxrave.simpmusic.autoflow

import com.maxrave.domain.data.model.browse.album.Track
import kotlin.math.roundToInt

object AutoFlowQueueSorter {
    private val HIGH_ENERGY_KEYWORDS =
        listOf(
            "remix", "club", "drill", "phonk", "edm", "hype", "workout", "party",
            "rock", "rap", "trap", "bass", "fast", "sped up", "nightcore", "rave",
            "metal", "punk", "hardstyle", "techno", "dnb", "dubstep",
        )

    private val CALM_KEYWORDS =
        listOf(
            "acoustic", "ballad", "lofi", "lo-fi", "ambient", "piano", "sleep",
            "chill", "slowed", "lullaby", "rain", "soft", "unplugged", "meditation",
            "instrumental", "interlude", "outro", "jazz", "classical",
        )

    fun estimateProfile(
        track: Track,
        playTimeSeconds: Long = 0L,
    ): TrackEnergyProfile {
        val text =
            buildString {
                append(track.title)
                append(' ')
                append(track.album?.name.orEmpty())
                append(' ')
                append(track.artists?.joinToString(" ") { it.name.orEmpty() }.orEmpty())
            }.lowercase()

        var energy = 0.50f
        var bpm = 108

        val durationSec = track.durationSeconds ?: 210
        when {
            durationSec in 90..190 -> {
                energy += 0.08f
                bpm += 10
            }
            durationSec > 300 -> {
                energy -= 0.08f
                bpm -= 10
            }
        }

        if (track.isExplicit) {
            energy += 0.07f
            bpm += 6
        }

        var highHits = 0
        for (kw in HIGH_ENERGY_KEYWORDS) {
            if (text.contains(kw)) highHits++
        }
        var calmHits = 0
        for (kw in CALM_KEYWORDS) {
            if (text.contains(kw)) calmHits++
        }

        energy += (highHits.coerceAtMost(3) * 0.13f)
        bpm += (highHits.coerceAtMost(3) * 14)
        energy -= (calmHits.coerceAtMost(3) * 0.14f)
        bpm -= (calmHits.coerceAtMost(3) * 12)

        if (playTimeSeconds > 600L) {
            energy += 0.04f
        }

        val clampedEnergy = energy.coerceIn(0.05f, 0.98f)
        val clampedBpm = bpm.coerceIn(70, 165)
        return TrackEnergyProfile(
            videoId = track.videoId,
            title = track.title,
            estimatedBpm = clampedBpm,
            energyScore = clampedEnergy,
        )
    }

    fun sortForArc(
        tracks: List<Track>,
        arc: AutoFlowArc,
        playTimeByVideoId: Map<String, Long> = emptyMap(),
        keepFirstTrackPinned: Boolean = false,
    ): List<Track> {
        if (tracks.size <= 1) return tracks.toList()

        val pinned = if (keepFirstTrackPinned) tracks.first() else null
        val sortable = if (keepFirstTrackPinned) tracks.drop(1) else tracks
        if (sortable.size <= 1) {
            return if (pinned != null) listOf(pinned) + sortable else sortable.toList()
        }

        val ascending =
            sortable
                .mapIndexed { originalIndex, track ->
                    val profile = estimateProfile(track, playTimeByVideoId[track.videoId] ?: 0L)
                    IndexedTrack(originalIndex, track, profile.energyScore, profile.estimatedBpm)
                }.sortedWith(
                    compareBy<IndexedTrack> { it.energy }
                        .thenBy { it.bpm }
                        .thenBy { it.originalIndex },
                )

        val ordered =
            when (arc) {
                AutoFlowArc.WORKOUT_RAMP -> ascending.map { it.track }
                AutoFlowArc.CHILL_WINDDOWN -> ascending.asReversed().map { it.track }
                AutoFlowArc.PEAK_FIRST -> ascending.asReversed().map { it.track }
                AutoFlowArc.WARMUP_PEAK_COOLDOWN -> buildWarmupPeakCooldown(ascending)
            }

        return if (pinned != null) listOf(pinned) + ordered else ordered
    }

    fun previewEnergyCurve(
        tracks: List<Track>,
        arc: AutoFlowArc,
        playTimeByVideoId: Map<String, Long> = emptyMap(),
        keepFirstTrackPinned: Boolean = false,
    ): List<TrackEnergyProfile> =
        sortForArc(tracks, arc, playTimeByVideoId, keepFirstTrackPinned)
            .map { track -> estimateProfile(track, playTimeByVideoId[track.videoId] ?: 0L) }

    private fun buildWarmupPeakCooldown(ascending: List<IndexedTrack>): List<Track> {
        val n = ascending.size
        if (n <= 2) return ascending.map { it.track }
        // Place ~65% of tracks climbing up to the peak, and the remaining ~35% stepping down smoothly.
        val peakIndex = ((n - 1) * 0.65f).roundToInt().coerceIn(1, n - 1)
        val warmupCount = peakIndex + 1
        val cooldownCount = n - warmupCount

        val warmup = ArrayList<IndexedTrack>(warmupCount)
        val cooldown = ArrayList<IndexedTrack>(cooldownCount)

        // Distribute alternating low-to-high items so the highest energy sits at `peakIndex`
        // and the tail steps down without abrupt jumps.
        for (i in 0 until n) {
            val item = ascending[i]
            if (i == n - 1) {
                warmup.add(item)
            } else if (cooldown.size < cooldownCount && i % 3 == 0 && warmup.size >= 1) {
                cooldown.add(item)
            } else if (warmup.size < warmupCount) {
                warmup.add(item)
            } else {
                cooldown.add(item)
            }
        }
        warmup.sortWith(compareBy<IndexedTrack> { it.energy }.thenBy { it.bpm })
        cooldown.sortWith(compareByDescending<IndexedTrack> { it.energy }.thenByDescending { it.bpm })
        return (warmup + cooldown).map { it.track }
    }

    private data class IndexedTrack(
        val originalIndex: Int,
        val track: Track,
        val energy: Float,
        val bpm: Int,
    )
}
