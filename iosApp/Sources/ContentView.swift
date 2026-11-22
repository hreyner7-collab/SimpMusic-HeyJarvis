import SwiftUI

@main
struct SimpMusicIOSApp: App {
    @StateObject private var vm = AppViewModel()

    var body: some Scene {
        WindowGroup {
            RootContainerView(vm: vm)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootContainerView: View {
    @ObservedObject var vm: AppViewModel

    private let accentCyan = Color(red: 0.39, green: 0.82, blue: 1.0)
    private let accentPurple = Color(red: 0.75, green: 0.35, blue: 0.95)
    private let accentGreen = Color(red: 0.19, green: 0.82, blue: 0.35)
    private let accentPink = Color(red: 1.0, green: 0.22, blue: 0.37)

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.06, green: 0.06, blue: 0.10),
                    Color(red: 0.03, green: 0.03, blue: 0.05)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                TopHeaderBar(vm: vm)

                ZStack {
                    switch vm.selectedTab {
                    case .home:
                        HomeTabView(vm: vm)
                    case .search:
                        SearchTabView(vm: vm)
                    case .library:
                        LibraryTabView(vm: vm)
                    case .settings:
                        SettingsTabView(vm: vm)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if vm.currentTrack != nil && !vm.isNowPlayingExpanded {
                    MiniPlayerCapsuleView(vm: vm)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 6)
                }

                BottomNavBar(vm: vm)
            }

            if vm.isNowPlayingExpanded {
                NowPlayingFullscreenView(vm: vm)
                    .transition(.move(edge: .bottom))
                    .zIndex(10)
            }

            if vm.showImportLinkSheet {
                ImportLinkOverlayView(vm: vm)
                    .zIndex(20)
            }
        }
    }
}

// MARK: - Top Header Bar with Siri-Style Hey Jarvis Floating Orb

struct TopHeaderBar: View {
    @ObservedObject var vm: AppViewModel

    private let jarvisGradient = LinearGradient(
        colors: [
            Color(red: 0.39, green: 0.82, blue: 1.0),
            Color(red: 0.75, green: 0.35, blue: 0.95),
            Color(red: 1.0, green: 0.22, blue: 0.37),
            Color(red: 0.19, green: 0.82, blue: 0.35)
        ],
        startPoint: .leading,
        endPoint: .trailing
    )

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("SimpMusic")
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("app-header-title")
                Text("Hey Jarvis · v2.6.13")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.6))
            }

            Spacer()

            Button {
                vm.showImportLinkSheet = true
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "link.badge.plus")
                        .font(.system(size: 13, weight: .semibold))
                    Text("Import")
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color.white.opacity(0.12), in: Capsule())
            }
            .accessibilityIdentifier("btn-top-import")

            if vm.jarvisEnabled {
                Button {
                    Task {
                        if vm.jarvisPhase == .awake {
                            await vm.processVoiceUtterance("hey jarvis play smooth jazz")
                        } else {
                            vm.triggerManualWake()
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "waveform")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(jarvisGradient)
                        if vm.jarvisPhase != .listening {
                            Text(jarvisPillText)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                                .accessibilityIdentifier("jarvis-status-label")
                        }
                    }
                    .padding(.horizontal, vm.jarvisPhase == .listening ? 11 : 12)
                    .padding(.vertical, 9)
                    .background(Color(red: 0.11, green: 0.11, blue: 0.14), in: Capsule())
                    .overlay(
                        Capsule()
                            .stroke(jarvisGradient, lineWidth: 1.5)
                    )
                }
                .accessibilityIdentifier("jarvis-orb")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.black.opacity(0.35))
    }

    private var jarvisPillText: String {
        switch vm.jarvisPhase {
        case .off: return "Off"
        case .listening: return "Hey Jarvis"
        case .awake: return vm.jarvisMessage.isEmpty ? "Listening…" : vm.jarvisMessage
        case .thinking: return "Thinking…"
        case .result, .error: return vm.jarvisMessage.isEmpty ? vm.jarvisPhase.rawValue : vm.jarvisMessage
        }
    }
}

// MARK: - Home Tab

struct HomeTabView: View {
    @ObservedObject var vm: AppViewModel

    private let moodChips = ["Smooth Jazz", "Lo-Fi", "Classic Rock", "Hip Hop", "Pop"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Mood & Genre Chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(moodChips, id: \.self) { mood in
                            Button {
                                Task {
                                    await vm.processVoiceUtterance("hey jarvis play \(mood.lowercased())")
                                }
                            } label: {
                                Text(mood)
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(Color.white.opacity(0.10), in: Capsule())
                            }
                            .accessibilityIdentifier("mood-chip-\(mood.lowercased().replacingOccurrences(of: " ", with: "-"))")
                        }
                    }
                    .padding(.horizontal, 16)
                }

                // Voice Quick Banner
                HStack(spacing: 12) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Color(red: 0.19, green: 0.82, blue: 0.35))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Say \"Hey Jarvis\" or tap a command")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                        Text(vm.jarvisLastHeard.isEmpty ? "Try: \"Hey Jarvis play smooth jazz\" or \"next song\"" : "Last heard: \"\(vm.jarvisLastHeard)\"")
                            .font(.system(size: 11))
                            .foregroundStyle(Color.white.opacity(0.68))
                            .accessibilityIdentifier("home-jarvis-subtitle")
                    }
                    Spacer()
                    Button("Jazz Mix") {
                        Task {
                            await vm.processVoiceUtterance("hey jarvis play smooth jazz")
                        }
                    }
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(red: 0.19, green: 0.82, blue: 0.35), in: Capsule())
                    .accessibilityIdentifier("btn-home-quick-jazz")
                }
                .padding(12)
                .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16)

                ForEach(vm.homeSections) { section in
                    VStack(alignment: .leading, spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(section.title)
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(.white)
                            Text(section.subtitle)
                                .font(.system(size: 12))
                                .foregroundStyle(Color.white.opacity(0.6))
                        }
                        .padding(.horizontal, 16)

                        VStack(spacing: 8) {
                            ForEach(section.tracks) { track in
                                TrackRowView(track: track, isCurrent: vm.currentTrack?.id == track.id && vm.isPlaying) {
                                    vm.playTrack(track, in: section.tracks)
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
            }
            .padding(.vertical, 12)
        }
    }
}

// MARK: - Search Tab

struct SearchTabView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(Color.white.opacity(0.6))
                    TextField("Search songs, artists, albums…", text: $vm.searchQuery)
                        .foregroundStyle(.white)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("search-input")
                        .onSubmit {
                            Task { await vm.performSearch() }
                        }
                    if !vm.searchQuery.isEmpty {
                        Button {
                            vm.searchQuery = ""
                            Task { await vm.performSearch("") }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(Color.white.opacity(0.5))
                        }
                        .accessibilityIdentifier("search-clear")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))

                Button("Search") {
                    Task { await vm.performSearch() }
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.black)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(red: 0.39, green: 0.82, blue: 1.0), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("search-submit")
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(vm.searchResults) { track in
                        TrackRowView(track: track, isCurrent: vm.currentTrack?.id == track.id && vm.isPlaying) {
                            vm.playTrack(track, in: vm.searchResults)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
        }
    }
}

// MARK: - Library & Spotify Sync Tab

struct LibraryTabView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Spotify Sync & Link Importer Card
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(Color(red: 0.19, green: 0.82, blue: 0.35))
                        Text("Spotify Library & Link Importer")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                        Spacer()
                    }
                    Text(vm.spotifyStatusMessage)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.white.opacity(0.7))

                    if let prog = vm.importProgressText {
                        Text(prog)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                            .accessibilityIdentifier("library-import-status")
                    }

                    HStack(spacing: 10) {
                        Button {
                            vm.showImportLinkSheet = true
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "plus.circle.fill")
                                Text("Import Link")
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(Color(red: 0.19, green: 0.82, blue: 0.35), in: Capsule())
                        }
                        .accessibilityIdentifier("btn-open-import-sheet")

                        Button {
                            Task {
                                await vm.importFromLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
                            }
                        } label: {
                            Text("Quick Sync Top Hits")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 9)
                                .background(Color.white.opacity(0.14), in: Capsule())
                        }
                        .accessibilityIdentifier("btn-quick-sync-spotify")
                    }
                }
                .padding(14)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)

                Text("Your Playlists (\(vm.localPlaylists.count))")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("library-playlists-header")

                VStack(spacing: 10) {
                    ForEach(vm.localPlaylists) { pl in
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 10)
                                .fill(Color.white.opacity(0.12))
                                .frame(width: 52, height: 52)
                                .overlay(
                                    Image(systemName: pl.source == .spotify ? "waveform.circle.fill" : "music.note.list")
                                        .font(.system(size: 22))
                                        .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                                )

                            VStack(alignment: .leading, spacing: 3) {
                                Text(pl.title)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                Text("\(pl.source.rawValue) · \(pl.tracks.count) tracks")
                                    .font(.system(size: 12))
                                    .foregroundStyle(Color.white.opacity(0.6))
                            }
                            Spacer()
                            Button("Play") {
                                if let first = pl.tracks.first {
                                    vm.playTrack(first, in: pl.tracks)
                                }
                            }
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Color.white.opacity(0.15), in: Capsule())
                            .accessibilityIdentifier("btn-play-playlist-\(pl.id)")
                        }
                        .padding(12)
                        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.vertical, 12)
        }
    }
}

// MARK: - Settings & Hey Jarvis Live Diagnostics Tab

struct SettingsTabView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Hey Jarvis Voice Assistant Group
                VStack(alignment: .leading, spacing: 12) {
                    Text("HEY JARVIS VOICE ASSISTANT")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.6))

                    Toggle(isOn: $vm.jarvisEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Hey Jarvis")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.white)
                            Text(vm.jarvisEnabled ? "Active · iOS Speech + Offline Command Engine" : "Disabled")
                                .font(.system(size: 12))
                                .foregroundStyle(Color.white.opacity(0.65))
                        }
                    }
                    .accessibilityIdentifier("jarvis-toggle")

                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(vm.jarvisSpeechActive ? "Voice detected" : "Mic Level · Sensitivity: \(vm.jarvisSensitivity.rawValue)")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(Color.white.opacity(0.75))
                            Spacer()
                            if !vm.jarvisLastHeard.isEmpty {
                                Text("Last: \"\(vm.jarvisLastHeard)\"")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(Color(red: 0.19, green: 0.82, blue: 0.35))
                                    .accessibilityIdentifier("jarvis-last-heard")
                            }
                        }
                        ProgressView(value: Double(vm.jarvisMicLevel), total: 1.0)
                            .tint(Color(red: 0.19, green: 0.82, blue: 0.35))
                    }

                    // 1-Tap Voice Command Simulator & Test Bar
                    Text("Quick Test Voice Commands:")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.75))

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            Button("Play Smooth Jazz") {
                                Task { await vm.processVoiceUtterance("hey jarvis play smooth jazz") }
                            }
                            .accessibilityIdentifier("btn-voice-jazz")

                            Button("Next Song") {
                                Task { await vm.processVoiceUtterance("hey jarvis next song") }
                            }
                            .accessibilityIdentifier("btn-voice-next")

                            Button("Volume 70%") {
                                Task { await vm.processVoiceUtterance("hey jarvis set volume to 70 percent") }
                            }
                            .accessibilityIdentifier("btn-voice-vol70")

                            Button("Pause") {
                                Task { await vm.processVoiceUtterance("hey jarvis pause") }
                            }
                            .accessibilityIdentifier("btn-voice-pause")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.white.opacity(0.16))
                        .font(.system(size: 12, weight: .semibold))
                    }

                    Button {
                        let nextSens: JarvisSensitivity = {
                            switch vm.jarvisSensitivity {
                            case .normal: return .high
                            case .high: return .low
                            case .low: return .normal
                            }
                        }()
                        vm.jarvisSensitivity = nextSens
                    } label: {
                        HStack {
                            Text("Mic Sensitivity")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.white)
                            Spacer()
                            Text(vm.jarvisSensitivity.rawValue)
                                .font(.system(size: 13))
                                .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                        }
                    }
                    .accessibilityIdentifier("btn-jarvis-sensitivity")

                    Toggle("Wake Confirmation Chime", isOn: $vm.jarvisWakeChime)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .accessibilityIdentifier("jarvis-chime-toggle")

                    Toggle("Hey Jarvis Low-RAM Mode", isOn: $vm.jarvisLowRam)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .accessibilityIdentifier("jarvis-lowram-toggle")
                }
                .padding(14)
                .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)

                // Performance & Old Device Mode
                VStack(alignment: .leading, spacing: 12) {
                    Text("PERFORMANCE & OLD DEVICE MODE")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.6))

                    Button {
                        let all = PerformanceModeOption.allCases
                        if let idx = all.firstIndex(of: vm.performanceMode) {
                            vm.performanceMode = all[(idx + 1) % all.count]
                        }
                    } label: {
                        HStack {
                            Text("Performance Mode")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.white)
                            Spacer()
                            Text(vm.performanceMode.rawValue)
                                .font(.system(size: 13))
                                .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                        }
                    }
                    .accessibilityIdentifier("btn-perf-mode")
                }
                .padding(14)
                .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)

                // About Card
                VStack(alignment: .leading, spacing: 6) {
                    Text("ABOUT SIMPMUSIC HEY JARVIS")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.6))
                    Text("SimpMusic Hey Jarvis v2.6.13 (Build 70)")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .accessibilityIdentifier("settings-version-label")
                    Text("GitHub: hreyner7-collab/SimpMusic-HeyJarvis · Verified with govijr/terminal-phone")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.white.opacity(0.65))
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)
            }
            .padding(.vertical, 12)
        }
    }
}

// MARK: - Track Row Component

struct TrackRowView: View {
    let track: TrackItem
    let isCurrent: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(isCurrent ? Color(red: 0.19, green: 0.82, blue: 0.35).opacity(0.25) : Color.white.opacity(0.10))
                    .frame(width: 46, height: 46)
                    .overlay(
                        Image(systemName: isCurrent ? "speaker.wave.3.fill" : "music.note")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(isCurrent ? Color(red: 0.19, green: 0.82, blue: 0.35) : .white)
                    )

                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(isCurrent ? Color(red: 0.19, green: 0.82, blue: 0.35) : .white)
                        .lineLimit(1)
                    Text("\(track.artist) · \(track.album)")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.white.opacity(0.62))
                        .lineLimit(1)
                }
                Spacer()
                Text(track.formattedDuration)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.5))
            }
            .padding(10)
            .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
        }
        .accessibilityIdentifier("track-row-\(track.videoId)")
    }
}

// MARK: - Floating Liquid-Glass Mini-Player Capsule

struct MiniPlayerCapsuleView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        HStack(spacing: 12) {
            Button {
                vm.isNowPlayingExpanded = true
            } label: {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(red: 0.39, green: 0.82, blue: 1.0).opacity(0.25))
                        .frame(width: 40, height: 40)
                        .overlay(
                            Image(systemName: "music.quarternote.3")
                                .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                        )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(vm.currentTrack?.title ?? "Not Playing")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .accessibilityIdentifier("mini-player-title")
                        HStack(spacing: 6) {
                            Text(vm.currentTrack?.artist ?? "")
                                .font(.system(size: 11))
                                .foregroundStyle(Color.white.opacity(0.68))
                                .lineLimit(1)
                            if vm.abLoop.isActive {
                                Text(vm.abLoop.badgeText)
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(Color(red: 0.19, green: 0.82, blue: 0.35))
                            }
                        }
                    }
                }
            }
            .accessibilityIdentifier("btn-expand-player")

            Spacer()

            Button {
                vm.togglePlayPause()
            } label: {
                Image(systemName: vm.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
            }
            .accessibilityIdentifier("mini-play-pause")

            Button {
                _ = vm.nextTrack()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
            }
            .accessibilityIdentifier("mini-next")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(red: 0.12, green: 0.13, blue: 0.18).opacity(0.96), in: RoundedRectangle(cornerRadius: 18))
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .stroke(Color.white.opacity(0.14), lineWidth: 1)
        )
        .accessibilityIdentifier("mini-player")
    }
}

// MARK: - Fullscreen Now Playing View (with A-B Loop, Synced Lyrics & Queue)

struct NowPlayingFullscreenView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.12, green: 0.10, blue: 0.22),
                    Color(red: 0.04, green: 0.04, blue: 0.07)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 14) {
                // Header
                HStack {
                    Button {
                        vm.isNowPlayingExpanded = false
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.down")
                            Text("Close")
                        }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Color.white.opacity(0.12), in: Capsule())
                    }
                    .accessibilityIdentifier("btn-close-player")

                    Spacer()
                    Text("NOW PLAYING")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.white.opacity(0.6))
                    Spacer()

                    Button {
                        _ = vm.toggleLike()
                    } label: {
                        Image(systemName: (vm.currentTrack?.isLiked == true) ? "heart.fill" : "heart")
                            .font(.system(size: 20))
                            .foregroundStyle((vm.currentTrack?.isLiked == true) ? Color(red: 1.0, green: 0.22, blue: 0.37) : .white)
                    }
                    .accessibilityIdentifier("btn-player-like")
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)

                // Center Panel: Artwork OR Synced Lyrics OR Queue
                if vm.showLyricsPanel {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Synced Lyrics")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color(red: 0.39, green: 0.82, blue: 1.0))
                            .accessibilityIdentifier("lyrics-panel-header")
                        ScrollView {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(vm.parsedLyrics) { line in
                                    let isActive = vm.activeLyricIndex == line.id
                                    Button {
                                        vm.seek(to: line.timeSeconds)
                                    } label: {
                                        Text(line.text)
                                            .font(.system(size: isActive ? 17 : 14, weight: isActive ? .bold : .medium))
                                            .foregroundStyle(isActive ? .white : Color.white.opacity(0.55))
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, maxHeight: 230)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
                    .padding(.horizontal, 20)
                } else if vm.showQueuePanel {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Up Next in Queue (\(vm.queue.count))")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color(red: 0.19, green: 0.82, blue: 0.35))
                            .accessibilityIdentifier("queue-panel-header")
                        ScrollView {
                            VStack(spacing: 6) {
                                ForEach(vm.queue) { t in
                                    Button {
                                        vm.playTrack(t, in: vm.queue)
                                    } label: {
                                        HStack {
                                            Text(t.title)
                                                .font(.system(size: 13, weight: .semibold))
                                                .foregroundStyle(vm.currentTrack?.id == t.id ? Color(red: 0.19, green: 0.82, blue: 0.35) : .white)
                                                .lineLimit(1)
                                            Spacer()
                                            Text(t.artist)
                                                .font(.system(size: 11))
                                                .foregroundStyle(Color.white.opacity(0.6))
                                                .lineLimit(1)
                                        }
                                        .padding(.vertical, 4)
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, maxHeight: 230)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
                    .padding(.horizontal, 20)
                } else {
                    RoundedRectangle(cornerRadius: 24)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color(red: 0.39, green: 0.82, blue: 1.0).opacity(0.35),
                                    Color(red: 0.75, green: 0.35, blue: 0.95).opacity(0.35)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 210, height: 210)
                        .overlay(
                            Image(systemName: "music.note.house.fill")
                                .font(.system(size: 64))
                                .foregroundStyle(.white.opacity(0.85))
                        )
                }

                // Title & Artist
                VStack(spacing: 4) {
                    Text(vm.currentTrack?.title ?? "No Track")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .accessibilityIdentifier("player-track-title")
                    Text("\(vm.currentTrack?.artist ?? "") · \(vm.currentTrack?.album ?? "")")
                        .font(.system(size: 14))
                        .foregroundStyle(Color.white.opacity(0.7))
                        .lineLimit(1)
                }
                .padding(.horizontal, 20)

                // Progress & Timestamps
                VStack(spacing: 4) {
                    Slider(
                        value: Binding(
                            get: { vm.currentTime },
                            set: { vm.seek(to: $0) }
                        ),
                        in: 0...max(1, vm.duration)
                    )
                    .tint(Color(red: 0.39, green: 0.82, blue: 1.0))

                    HStack {
                        Text(TimeFormat.mmss(vm.currentTime))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.white.opacity(0.6))
                        Spacer()
                        Text("Volume \(vm.volume)%")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color.white.opacity(0.75))
                            .accessibilityIdentifier("player-volume-label")
                        Spacer()
                        Text(TimeFormat.mmss(vm.duration))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.white.opacity(0.6))
                    }
                }
                .padding(.horizontal, 24)

                // A-B Loop Controls
                VStack(spacing: 6) {
                    Text(vm.abLoop.badgeText)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(vm.abLoop.isActive ? Color(red: 0.19, green: 0.82, blue: 0.35) : Color.white.opacity(0.7))
                        .accessibilityIdentifier("ab-loop-status")

                    HStack(spacing: 10) {
                        Button("Set A") {
                            vm.setABPointA()
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(Color.white.opacity(0.14), in: Capsule())
                        .accessibilityIdentifier("btn-ab-set-a")

                        Button("Set B") {
                            vm.setABPointB()
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(Color.white.opacity(0.14), in: Capsule())
                        .accessibilityIdentifier("btn-ab-set-b")

                        Button("Clear A–B") {
                            vm.clearABLoop()
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(Color.white.opacity(0.14), in: Capsule())
                        .accessibilityIdentifier("btn-ab-clear")
                    }
                }

                // Primary Transport Row
                HStack(spacing: 26) {
                    Button {
                        _ = vm.setShuffle(nil)
                    } label: {
                        Image(systemName: "shuffle")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(vm.shuffleEnabled ? Color(red: 0.19, green: 0.82, blue: 0.35) : .white.opacity(0.7))
                    }
                    .accessibilityIdentifier("btn-player-shuffle")

                    Button {
                        _ = vm.previousTrack()
                    } label: {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 24))
                            .foregroundStyle(.white)
                    }
                    .accessibilityIdentifier("btn-player-prev")

                    Button {
                        vm.togglePlayPause()
                    } label: {
                        Image(systemName: vm.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 54))
                            .foregroundStyle(.white)
                    }
                    .accessibilityIdentifier("btn-player-play-pause")

                    Button {
                        _ = vm.nextTrack()
                    } label: {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 24))
                            .foregroundStyle(.white)
                    }
                    .accessibilityIdentifier("btn-player-next")

                    Button {
                        _ = vm.setRepeat(.cycle)
                    } label: {
                        Image(systemName: vm.repeatMode == .one ? "repeat.1" : "repeat")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(vm.repeatMode != .off ? Color(red: 0.19, green: 0.82, blue: 0.35) : .white.opacity(0.7))
                    }
                    .accessibilityIdentifier("btn-player-repeat")
                }

                // Bottom Panel Toggles (Lyrics & Queue)
                HStack(spacing: 14) {
                    Button {
                        vm.showLyricsPanel.toggle()
                        if vm.showLyricsPanel { vm.showQueuePanel = false }
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "quote.bubble.fill")
                            Text("Lyrics")
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(vm.showLyricsPanel ? .black : .white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(vm.showLyricsPanel ? Color(red: 0.39, green: 0.82, blue: 1.0) : Color.white.opacity(0.12), in: Capsule())
                    }
                    .accessibilityIdentifier("btn-toggle-lyrics")

                    Button {
                        vm.showQueuePanel.toggle()
                        if vm.showQueuePanel { vm.showLyricsPanel = false }
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "list.bullet")
                            Text("Queue")
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(vm.showQueuePanel ? .black : .white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(vm.showQueuePanel ? Color(red: 0.19, green: 0.82, blue: 0.35) : Color.white.opacity(0.12), in: Capsule())
                    }
                    .accessibilityIdentifier("btn-toggle-queue")
                }
                .padding(.bottom, 16)
            }
        }
    }
}

// MARK: - Import Link Overlay View

struct ImportLinkOverlayView: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        ZStack {
            Color.black.opacity(0.75).ignoresSafeArea()

            VStack(alignment: .leading, spacing: 14) {
                Text("Import Playlist or Song Link")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("import-sheet-title")

                Text("Paste any Spotify playlist, album, or track link, or any YouTube playlist link.")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.white.opacity(0.7))

                TextField("https://open.spotify.com/playlist/…", text: $vm.importLinkInput)
                    .foregroundStyle(.white)
                    .autocorrectionDisabled(true)
                    .textInputAutocapitalization(.never)
                    .padding(12)
                    .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityIdentifier("import-link-input")

                HStack(spacing: 10) {
                    Button("Cancel") {
                        vm.showImportLinkSheet = false
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.white.opacity(0.14), in: Capsule())
                    .accessibilityIdentifier("btn-cancel-import")

                    Spacer()

                    Button("Import Now") {
                        let url = vm.importLinkInput.isEmpty
                            ? "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
                            : vm.importLinkInput
                        vm.showImportLinkSheet = false
                        vm.selectedTab = .library
                        Task {
                            await vm.importFromLink(url)
                        }
                    }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(Color(red: 0.19, green: 0.82, blue: 0.35), in: Capsule())
                    .accessibilityIdentifier("btn-confirm-import")
                }
            }
            .padding(20)
            .background(Color(red: 0.11, green: 0.12, blue: 0.16), in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 24)
        }
    }
}

// MARK: - Bottom Tab Navigation Bar

struct BottomNavBar: View {
    @ObservedObject var vm: AppViewModel

    var body: some View {
        HStack(spacing: 0) {
            navButton(tab: .home, icon: "house.fill", id: "tab-home")
            navButton(tab: .search, icon: "magnifyingglass", id: "tab-search")
            navButton(tab: .library, icon: "books.vertical.fill", id: "tab-library")
            navButton(tab: .settings, icon: "gearshape.fill", id: "tab-settings")
        }
        .padding(.vertical, 8)
        .background(Color(red: 0.08, green: 0.08, blue: 0.11).opacity(0.96))
    }

    private func navButton(tab: MainTab, icon: String, id: String) -> some View {
        let selected = vm.selectedTab == tab
        return Button {
            vm.selectedTab = tab
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: selected ? .bold : .regular))
                Text(tab.rawValue)
                    .font(.system(size: 11, weight: selected ? .bold : .medium))
            }
            .foregroundStyle(selected ? Color(red: 0.39, green: 0.82, blue: 1.0) : Color.white.opacity(0.55))
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier(id)
    }
}
