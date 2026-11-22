package com.maxrave.simpmusic.jerry

enum class JarvisSensitivity(val label: String) {
    HIGH("High (Quiet Mics)"),
    NORMAL("Normal (Recommended)"),
    LOW("Low (Noisy Rooms)"),
}

sealed class ParakeetModelStatus {
    data object Ready : ParakeetModelStatus()

    data object Missing : ParakeetModelStatus()

    data object SkippedLowRam : ParakeetModelStatus()

    data class Downloading(
        val percent: Int,
        val detail: String,
    ) : ParakeetModelStatus()

    data class Error(
        val reason: String,
    ) : ParakeetModelStatus()
}

data class JarvisDiagnostics(
    val activeMicName: String = "Not started",
    val preferredMicName: String = "",
    val availableMics: List<String> = emptyList(),
    val engineLabel: String = "Idle",
    val micLevel: Float = 0f,
    val speechActive: Boolean = false,
    val sensitivity: JarvisSensitivity = JarvisSensitivity.NORMAL,
    val wakeChimeEnabled: Boolean = true,
    val parakeetStatus: ParakeetModelStatus = ParakeetModelStatus.Missing,
    val lastHeard: String = "",
)
