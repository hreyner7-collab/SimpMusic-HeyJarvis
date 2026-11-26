package com.maxrave.simpmusic.handoff

actual class PlaybackHandoffTransport actual constructor() {
    actual fun defaultDeviceName(): String = "iPhone"

    actual fun platformLabel(): String = "IOS"

    actual fun startServer(
        preferredPort: Int,
        beaconProvider: () -> HandoffBeacon,
        onGetState: (pauseLocal: Boolean) -> HandoffPlaybackSnapshot?,
        onReceiveTransfer: (HandoffPlaybackSnapshot) -> Unit,
        onPeerDiscovered: (HandoffPeer) -> Unit,
    ): Int = 0

    actual fun broadcastDiscoverPing() {}

    actual suspend fun sendSnapshot(
        peer: HandoffPeer,
        snapshot: HandoffPlaybackSnapshot,
    ): Boolean = false

    actual suspend fun pullSnapshot(
        peer: HandoffPeer,
        pauseRemote: Boolean,
    ): HandoffPlaybackSnapshot? = null

    actual fun stopServer() {}
}
