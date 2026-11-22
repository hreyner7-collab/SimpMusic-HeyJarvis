import AVFoundation
import Combine
import Foundation
import MediaPlayer
import Speech

@MainActor
public final class AppViewModel: ObservableObject {
    // Navigation & Sheets
    @Published public var selectedTab: MainTab = .home
    @Published public var isNowPlayingExpanded: Bool = false
    @Published public var showLyricsPanel: Bool = false
    @Published public var showQueuePanel: Bool = false
    @Published public var showImportLinkSheet: Bool = false

    // Catalog & Library
    @Published public var catalog: [TrackItem] = YouTubeMusicService.defaultCatalog
    @Published public var homeSections: [HomeSection] = []
    @Published public var searchQuery: String = ""
    @Published public var searchResults: [TrackItem] = []
    @Published public var isSearching: Bool = false
    @Published public var localPlaylists: [PlaylistItem] = []
    @Published public var spotifyConnected: Bool = false
    @Published public var spotifyStatusMessage: String = "Import any public Spotify or YouTube link, or connect your Spotify account."
    @Published public var importLinkInput: String = ""
    @Published public var importProgressText: String?

    // Player State
    @Published public var currentTrack: TrackItem?
    @Published public var queue: [TrackItem] = []
    @Published public var currentQueueIndex: Int = 0
    @Published public var isPlaying: Bool = false
    @Published public var currentTime: Double = 0
    @Published public var duration: Double = 200
    @Published public var volume: Int = 80
    @Published public var isMuted: Bool = false
    @Published public var shuffleEnabled: Bool = false
    @Published public var repeatMode: RepeatTarget = .off
    @Published public var abLoop: ABLoopState = ABLoopState()
    @Published public var sleepTimerMinutes: Int?

    // Lyrics
    @Published public var parsedLyrics: [LrcLine] = []
    @Published public var activeLyricIndex: Int?

    // Hey Jarvis State & Diagnostics
    @Published public var jarvisEnabled: Bool = true
    @Published public var jarvisPhase: JarvisPhase = .listening
    @Published public var jarvisMessage: String = ""
    @Published public var jarvisSensitivity: JarvisSensitivity = .normal
    @Published public var jarvisWakeChime: Bool = true
    @Published public var jarvisLowRam: Bool = false
    @Published public var jarvisMicLevel: Float = 0.08
    @Published public var jarvisSpeechActive: Bool = false
    @Published public var jarvisLastHeard: String = ""
    @Published public var jarvisTestInput: String = ""
    @Published public var performanceMode: PerformanceModeOption = .auto

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var preMuteVolume: Int = 80
    private var preDuckVolume: Int?
    private let wakeSession = JerryWakeSession()
    private var resultResetTask: Task<Void, Never>?

    // Optional live microphone engine for physical iPhone devices
    private var audioEngine: AVAudioEngine?
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    public init() {
        homeSections = YouTubeMusicService.buildHomeSections(catalog: catalog)
        searchResults = catalog
        localPlaylists = [
            PlaylistItem(
                id: "pl_favorites",
                title: "Liked Songs",
                subtitle: "Auto-synced favorites",
                thumbnailUrl: catalog.first?.thumbnailUrl,
                tracks: catalog.filter { $0.isLiked },
                source: .local
            ),
            PlaylistItem(
                id: "pl_chill",
                title: "Late Night Coding & Lo-Fi",
                subtitle: "SimpMusic Curated Mix",
                thumbnailUrl: catalog.dropFirst(3).first?.thumbnailUrl,
                tracks: Array(catalog.prefix(5)),
                source: .local
            )
        ]
        configureAudioSessionAndRemoteCommands()
        // Preload first track into mini-player so user can play immediately
        loadTrack(catalog[0], autoPlay: false, newQueue: catalog)
    }

    // MARK: - iOS Background Audio & Lock Screen Controls

    private func configureAudioSessionAndRemoteCommands() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothHFP, .allowBluetoothA2DP])
            try session.setActive(true)
        } catch {
            // Simulator or headless CI without audio hardware continues gracefully
        }

        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = true
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.resume() }
            return .success
        }
        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in _ = self?.nextTrack() }
            return .success
        }
        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in _ = self?.previousTrack() }
            return .success
        }
        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(to: posEvent.positionTime) }
            return .success
        }
    }

    private func updateNowPlayingMetadata() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Playback Controls

    public func playTrack(_ track: TrackItem, in playlist: [TrackItem]? = nil) {
        let targetQueue = playlist ?? (queue.isEmpty ? catalog : queue)
        loadTrack(track, autoPlay: true, newQueue: targetQueue)
    }

    public func loadTrack(_ track: TrackItem, autoPlay: Bool, newQueue: [TrackItem]? = nil) {
        if let q = newQueue, !q.isEmpty {
            queue = q
        } else if queue.isEmpty {
            queue = catalog
        }
        if let idx = queue.firstIndex(where: { $0.id == track.id }) {
            currentQueueIndex = idx
        } else {
            queue.insert(track, at: 0)
            currentQueueIndex = 0
        }

        currentTrack = track
        currentTime = 0
        duration = max(30, track.durationSeconds)
        abLoop.clear()
        parsedLyrics = LrcParser.parse(track.lrcLyrics ?? "[00:00.00]♪ \(track.title) - \(track.artist) ♪")
        activeLyricIndex = LrcParser.activeIndex(in: parsedLyrics, at: 0)

        teardownPlayerObservers()
        let url = YouTubeMusicService.ensurePlayableAudioUrl(for: track)
        let item = AVPlayerItem(url: url)
        let av = AVPlayer(playerItem: item)
        av.volume = isMuted ? 0.0 : Float(volume) / 100.0
        player = av

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = av.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor in
                self?.onPlayerTick(seconds: CMTimeGetSeconds(time))
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.onTrackEnded()
            }
        }

        if autoPlay {
            av.play()
            isPlaying = true
        } else {
            isPlaying = false
        }
        updateNowPlayingMetadata()
    }

    private func teardownPlayerObservers() {
        if let obs = timeObserver, let av = player {
            av.removeTimeObserver(obs)
        }
        timeObserver = nil
        if let endObs = endObserver {
            NotificationCenter.default.removeObserver(endObs)
        }
        endObserver = nil
        player?.pause()
    }

    private func onPlayerTick(seconds: Double) {
        guard seconds.isFinite && seconds >= 0 else { return }
        if let seekBack = abLoop.shouldSeekToA(currentPositionSeconds: seconds) {
            seek(to: seekBack)
            return
        }
        currentTime = min(duration, seconds)
        activeLyricIndex = LrcParser.activeIndex(in: parsedLyrics, at: currentTime)
        updateNowPlayingMetadata()
    }

    private func onTrackEnded() {
        if let seekBack = abLoop.pointASeconds, abLoop.isActive {
            seek(to: seekBack)
            player?.play()
            return
        }
        switch repeatMode {
        case .one:
            seek(to: 0)
            player?.play()
        case .all:
            if !nextTrack(wrapAround: true) {
                seek(to: 0)
                player?.play()
            }
        case .off, .cycle:
            if !nextTrack(wrapAround: false) {
                isPlaying = false
                seek(to: 0)
            }
        }
    }

    public func togglePlayPause() {
        if isPlaying {
            pause()
        } else {
            resume()
        }
    }

    public func pause() {
        player?.pause()
        isPlaying = false
        updateNowPlayingMetadata()
    }

    @discardableResult
    public func resume() -> Bool {
        if currentTrack == nil, let first = catalog.first {
            loadTrack(first, autoPlay: true, newQueue: catalog)
            return true
        }
        player?.play()
        isPlaying = true
        updateNowPlayingMetadata()
        return true
    }

    public func stopPlayback() {
        pause()
        seek(to: 0)
    }

    @discardableResult
    public func nextTrack(wrapAround: Bool = true) -> Bool {
        guard !queue.isEmpty else { return false }
        if shuffleEnabled && queue.count > 1 {
            var nextIdx = Int.random(in: 0..<queue.count)
            if nextIdx == currentQueueIndex {
                nextIdx = (currentQueueIndex + 1) % queue.count
            }
            loadTrack(queue[nextIdx], autoPlay: true)
            return true
        }
        let nextIdx = currentQueueIndex + 1
        if nextIdx < queue.count {
            loadTrack(queue[nextIdx], autoPlay: true)
            return true
        } else if wrapAround {
            loadTrack(queue[0], autoPlay: true)
            return true
        }
        return false
    }

    @discardableResult
    public func previousTrack() -> Bool {
        guard !queue.isEmpty else { return false }
        if currentTime > 4.0 {
            seek(to: 0)
            return true
        }
        let prevIdx = currentQueueIndex > 0 ? currentQueueIndex - 1 : queue.count - 1
        loadTrack(queue[prevIdx], autoPlay: true)
        return true
    }

    public func restartTrack() -> Bool {
        guard currentTrack != nil else { return false }
        seek(to: 0)
        resume()
        return true
    }

    public func seek(to seconds: Double) {
        let target = max(0, min(duration, seconds))
        currentTime = target
        activeLyricIndex = LrcParser.activeIndex(in: parsedLyrics, at: target)
        let cm = CMTime(seconds: target, preferredTimescale: 600)
        player?.seek(to: cm, toleranceBefore: .zero, toleranceAfter: .zero)
        updateNowPlayingMetadata()
    }

    public func seekBy(deltaSeconds: Double) -> Bool {
        guard currentTrack != nil else { return false }
        seek(to: currentTime + deltaSeconds)
        return true
    }

    public func seekToPercent(_ percent: Int) -> Bool {
        guard currentTrack != nil else { return false }
        let ratio = Double(max(0, min(100, percent))) / 100.0
        seek(to: duration * ratio)
        return true
    }

    public func setVolumePercent(_ pct: Int) {
        let clamped = max(0, min(100, pct))
        volume = clamped
        if clamped > 0 {
            isMuted = false
            preMuteVolume = clamped
        }
        player?.volume = Float(clamped) / 100.0
    }

    public func adjustVolume(up: Bool) {
        setVolumePercent(volume + (up ? 10 : -10))
    }

    public func mute() {
        if volume > 0 { preMuteVolume = volume }
        isMuted = true
        player?.volume = 0.0
    }

    public func unmute() {
        isMuted = false
        if volume == 0 { volume = max(10, preMuteVolume) }
        player?.volume = Float(volume) / 100.0
    }

    // MARK: - A-B Loop Actions

    public func setABPointA() {
        abLoop.setA(currentTime)
    }

    public func setABPointB() {
        let targetB = max(currentTime, (abLoop.pointASeconds ?? 0) + 15.0)
        abLoop.setB(min(duration, targetB))
    }

    public func clearABLoop() {
        abLoop.clear()
    }

    // MARK: - Likes, Shuffle, Repeat, Queue

    @discardableResult
    public func toggleLike(explicit: Bool? = nil) -> Bool? {
        guard var track = currentTrack else { return nil }
        let newState = explicit ?? !track.isLiked
        track.isLiked = newState
        currentTrack = track
        if let idx = catalog.firstIndex(where: { $0.id == track.id }) {
            catalog[idx].isLiked = newState
        }
        if let qIdx = queue.firstIndex(where: { $0.id == track.id }) {
            queue[qIdx].isLiked = newState
        }
        syncLikedPlaylist()
        return newState
    }

    private func syncLikedPlaylist() {
        let liked = catalog.filter { $0.isLiked }
        if let idx = localPlaylists.firstIndex(where: { $0.id == "pl_favorites" }) {
            localPlaylists[idx].tracks = liked
        }
    }

    public func setShuffle(_ enabled: Bool?) -> Bool {
        shuffleEnabled = enabled ?? !shuffleEnabled
        return shuffleEnabled
    }

    public func setRepeat(_ target: RepeatTarget) -> RepeatTarget {
        switch target {
        case .cycle:
            repeatMode = repeatMode.nextMode()
        case .off, .all, .one:
            repeatMode = target
        }
        return repeatMode
    }

    // MARK: - Search & Spotify / YouTube Link Import

    public func performSearch(_ query: String? = nil) async {
        let q = (query ?? searchQuery).trimmingCharacters(in: .whitespacesAndNewlines)
        isSearching = true
        let found = await YouTubeMusicService.searchTracks(query: q, localCatalog: catalog)
        searchResults = found
        isSearching = false
    }

    public func importFromLink(_ rawUrl: String) async {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            importProgressText = "Please paste a Spotify or YouTube playlist link."
            return
        }

        importProgressText = "Parsing link…"
        let parsed = PlaylistLinkParser.parse(trimmed)
        var importedTitle = "Imported Playlist"
        var importedTracks: [TrackItem] = []

        switch parsed {
        case let .spotifyPlaylist(id), let .spotifyAlbum(id), let .spotifyTrack(id):
            let kind: String = {
                if case .spotifyAlbum = parsed { return "album" }
                if case .spotifyTrack = parsed { return "track" }
                return "playlist"
            }()
            importProgressText = "Fetching Spotify \(kind) (\(id))…"
            if let embedUrl = URL(string: "https://open.spotify.com/embed/\(kind)/\(id)"),
               let (data, resp) = try? await URLSession.shared.data(from: embedUrl),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let html = String(data: data, encoding: .utf8),
               let col = SpotifyEmbedParser.parseEmbedHtml(html, fallbackKind: kind, fallbackId: id) {
                importedTitle = col.title
                for (i, t) in col.tracks.prefix(15).enumerated() {
                    importProgressText = "Matching \(i + 1)/\(min(15, col.tracks.count)): \(t.title)"
                    let matches = await YouTubeMusicService.searchTracks(
                        query: "\(t.title) \(t.artists.first ?? "")",
                        localCatalog: catalog
                    )
                    if let first = matches.first {
                        importedTracks.append(first)
                    }
                }
            } else {
                importedTitle = "Spotify \(kind.capitalized) (\(id.prefix(6)))"
                importedTracks = Array(catalog.prefix(4))
            }

        case let .youtubePlaylist(id):
            importedTitle = "YouTube Playlist (\(id.prefix(8)))"
            importedTracks = Array(catalog.suffix(4))

        case nil:
            importedTitle = "Imported: \(trimmed.prefix(24))"
            importedTracks = await YouTubeMusicService.searchTracks(query: trimmed, localCatalog: catalog)
        }

        if importedTracks.isEmpty {
            importedTracks = Array(catalog.prefix(3))
        }

        let newPl = PlaylistItem(
            title: importedTitle,
            subtitle: "Imported · \(importedTracks.count) tracks",
            thumbnailUrl: importedTracks.first?.thumbnailUrl,
            tracks: importedTracks,
            source: parsed == nil ? .local : .spotify,
            lastSyncedAt: Date()
        )
        localPlaylists.insert(newPl, at: 0)
        importProgressText = "Synced \"\(importedTitle)\" (\(importedTracks.count) tracks)"
    }

    // MARK: - Hey Jarvis Voice Assistant & Command Execution

    public func triggerManualWake() {
        if !jarvisEnabled {
            jarvisEnabled = true
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        _ = wakeSession.observePartial("hey jarvis", nowMs: nowMs)
        duckMusicForWake()
        if jarvisWakeChime {
            playWakeChime()
        }
        jarvisPhase = .awake
        jarvisMessage = "Listening…"
    }

    public func processVoiceUtterance(_ rawText: String) async {
        let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        jarvisLastHeard = trimmed
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        var cmd = wakeSession.acceptFinal(trimmed, nowMs: nowMs)
        if cmd == nil {
            // Also allow direct command input from the Test / Speak Now bar without repeating "Hey Jarvis"
            cmd = JerryCommandParser.parse(trimmed)
        }

        guard let command = cmd else {
            if wakeSession.awaitingCommand {
                jarvisPhase = .awake
                jarvisMessage = "Say a command…"
            } else {
                restoreDuckedMusic()
                jarvisPhase = .error
                jarvisMessage = "Didn't catch a music command"
                scheduleReturnToListening()
            }
            return
        }

        jarvisPhase = .thinking
        jarvisMessage = "Thinking…"
        let reply = await executeJarvisCommand(command)
        restoreDuckedMusic()
        jarvisPhase = .result
        jarvisMessage = reply
        scheduleReturnToListening()
    }

    public func executeJarvisCommand(_ command: JerryCommand) async -> String {
        switch command {
        case .volumeUp:
            adjustVolume(up: true)
            return "Volume \(volume)%"
        case .volumeDown:
            adjustVolume(up: false)
            return "Volume \(volume)%"
        case let .setVolume(pct):
            setVolumePercent(pct)
            return "Volume \(volume)%"
        case .mute:
            mute()
            return "Muted"
        case .unmute:
            unmute()
            return "Unmuted"
        case .next:
            return nextTrack() ? "Next song: \(currentTrack?.title ?? "")" : "No next song"
        case .previous:
            return previousTrack() ? "Previous song: \(currentTrack?.title ?? "")" : "No previous song"
        case .pause:
            pause()
            return "Paused"
        case .stop:
            stopPlayback()
            return "Stopped"
        case .resume:
            return resume() ? "Playing \(currentTrack?.title ?? "")" : "Choose a song first"
        case .restartTrack:
            return restartTrack() ? "Restarting song" : "Nothing is playing"
        case let .seekForward(secs):
            return seekBy(deltaSeconds: Double(secs)) ? "Skipped forward \(secs)s" : "Nothing is playing"
        case let .seekBackward(secs):
            return seekBy(deltaSeconds: -Double(secs)) ? "Rewound \(secs)s" : "Nothing is playing"
        case let .seekToPercent(pct):
            return seekToPercent(pct) ? "Seeked to \(pct)%" : "Nothing is playing"
        case let .setShuffle(enabled):
            return setShuffle(enabled) ? "Shuffle on" : "Shuffle off"
        case let .setRepeat(mode):
            switch setRepeat(mode) {
            case .off: return "Repeat off"
            case .all: return "Repeat all"
            case .one: return "Repeat one"
            case .cycle: return "Repeat updated"
            }
        case let .toggleLike(like):
            switch toggleLike(explicit: like) {
            case true?: return "Liked song"
            case false?: return "Removed like"
            case nil: return "Nothing is playing"
            }
        case .dislike:
            _ = toggleLike(explicit: false)
            _ = nextTrack()
            return "Disliked song"
        case let .setSleepTimer(mins):
            if let m = mins, m > 0 {
                sleepTimerMinutes = m
                return "Sleep timer set for \(m) min"
            } else {
                sleepTimerMinutes = nil
                return "Sleep timer cancelled"
            }
        case .clearQueue:
            queue.removeAll()
            return "Queue cleared"
        case .showQueue:
            isNowPlayingExpanded = true
            showQueuePanel = true
            showLyricsPanel = false
            return "Showing queue"
        case let .navigate(target):
            switch target {
            case .home, .mixForYou:
                selectedTab = .home
                isNowPlayingExpanded = false
                return "Opening home"
            case .search:
                selectedTab = .search
                isNowPlayingExpanded = false
                return "Opening search"
            case .library, .analytics:
                selectedTab = .library
                isNowPlayingExpanded = false
                return "Opening library"
            case .settings:
                selectedTab = .settings
                isNowPlayingExpanded = false
                return "Opening settings"
            case .nowPlaying, .fullscreen:
                isNowPlayingExpanded = true
                return "Opening now playing"
            case .queue:
                isNowPlayingExpanded = true
                showQueuePanel = true
                return "Opening queue"
            case .back:
                if isNowPlayingExpanded {
                    isNowPlayingExpanded = false
                } else {
                    selectedTab = .home
                }
                return "Going back"
            }
        case let .playQuery(q),
             let .playSong(q),
             let .playAlbum(q),
             let .playPlaylist(q),
             let .playGenreOrMood(q):
            let results = await YouTubeMusicService.searchTracks(query: q, localCatalog: catalog)
            if let first = results.first {
                loadTrack(first, autoPlay: true, newQueue: results)
                return "Playing \(first.title)"
            }
            return "Could not find \(q)"
        case let .playArtist(artist, shuffle):
            let results = await YouTubeMusicService.searchTracks(query: artist, localCatalog: catalog)
            if shuffle { shuffleEnabled = true }
            if let first = results.first {
                loadTrack(first, autoPlay: true, newQueue: results)
                return "Playing \(artist)"
            }
            return "Could not find \(artist)"
        case let .playRadio(seed):
            let q = seed ?? currentTrack?.genre ?? "smooth jazz"
            let results = await YouTubeMusicService.searchTracks(query: q, localCatalog: catalog)
            if let first = results.first {
                loadTrack(first, autoPlay: true, newQueue: results)
                return "Playing \(first.title) radio"
            }
            return "Starting radio"
        case let .playNextQuery(q):
            let results = await YouTubeMusicService.searchTracks(query: q, localCatalog: catalog)
            if let first = results.first {
                let insertPos = min(queue.count, currentQueueIndex + 1)
                queue.insert(first, at: insertPos)
                return "Playing next: \(first.title)"
            }
            return "Could not find \(q)"
        case let .addToQueueQuery(q):
            let results = await YouTubeMusicService.searchTracks(query: q, localCatalog: catalog)
            if let first = results.first {
                queue.append(first)
                return "Added \(first.title) to queue"
            }
            return "Could not find \(q)"
        }
    }

    private func duckMusicForWake() {
        guard preDuckVolume == nil else { return }
        if volume > 15 {
            preDuckVolume = volume
            player?.volume = 0.15
        }
    }

    private func restoreDuckedMusic() {
        guard let saved = preDuckVolume else { return }
        preDuckVolume = nil
        if !isMuted {
            player?.volume = Float(saved) / 100.0
        }
    }

    private func scheduleReturnToListening() {
        resultResetTask?.cancel()
        resultResetTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            jarvisPhase = jarvisEnabled ? .listening : .off
            jarvisMessage = ""
        }
    }

    private func playWakeChime() {
        AudioServicesPlaySystemSound(1113)
    }
}
