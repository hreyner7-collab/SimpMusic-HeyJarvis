import Foundation

// MARK: - Core Track & Playlist Models

public struct TrackItem: Identifiable, Codable, Equatable {
    public let id: String
    public let videoId: String
    public var title: String
    public var artist: String
    public var album: String
    public var durationSeconds: Double
    public var thumbnailUrl: String?
    public var streamUrl: String?
    public var isLiked: Bool
    public var genre: String
    public var lrcLyrics: String?

    public init(
        id: String? = nil,
        videoId: String,
        title: String,
        artist: String,
        album: String = "Single",
        durationSeconds: Double = 210,
        thumbnailUrl: String? = nil,
        streamUrl: String? = nil,
        isLiked: Bool = false,
        genre: String = "pop",
        lrcLyrics: String? = nil
    ) {
        self.id = id ?? videoId
        self.videoId = videoId
        self.title = title
        self.artist = artist
        self.album = album
        self.durationSeconds = durationSeconds
        self.thumbnailUrl = thumbnailUrl
        self.streamUrl = streamUrl
        self.isLiked = isLiked
        self.genre = genre
        self.lrcLyrics = lrcLyrics
    }

    public var formattedDuration: String {
        TimeFormat.mmss(durationSeconds)
    }
}

public enum PlaylistSourceKind: String, Codable {
    case local = "Local"
    case spotify = "Spotify"
    case youtube = "YouTube"
}

public struct PlaylistItem: Identifiable, Codable, Equatable {
    public let id: String
    public var title: String
    public var subtitle: String
    public var thumbnailUrl: String?
    public var tracks: [TrackItem]
    public var source: PlaylistSourceKind
    public var externalId: String?
    public var lastSyncedAt: Date?

    public init(
        id: String = UUID().uuidString,
        title: String,
        subtitle: String,
        thumbnailUrl: String? = nil,
        tracks: [TrackItem] = [],
        source: PlaylistSourceKind = .local,
        externalId: String? = nil,
        lastSyncedAt: Date? = nil
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
        self.tracks = tracks
        self.source = source
        self.externalId = externalId
        self.lastSyncedAt = lastSyncedAt
    }
}

public struct HomeSection: Identifiable, Equatable {
    public let id: String
    public let title: String
    public let subtitle: String
    public let tracks: [TrackItem]
}

// MARK: - A-B Loop State (Parity with Compose AbLoopState)

public struct ABLoopState: Equatable, Codable {
    public static let minLoopSpanSeconds: Double = 0.5

    public var pointASeconds: Double?
    public var pointBSeconds: Double?

    public init(pointASeconds: Double? = nil, pointBSeconds: Double? = nil) {
        self.pointASeconds = pointASeconds
        self.pointBSeconds = pointBSeconds
    }

    public var isActive: Bool {
        guard let a = pointASeconds, let b = pointBSeconds else { return false }
        return b - a >= Self.minLoopSpanSeconds
    }

    public mutating func setA(_ seconds: Double) {
        let clamped = max(0, seconds)
        pointASeconds = clamped
        if let b = pointBSeconds, b - clamped < Self.minLoopSpanSeconds {
            pointBSeconds = nil
        }
    }

    public mutating func setB(_ seconds: Double) {
        let clamped = max(0, seconds)
        let a = pointASeconds ?? 0
        if clamped - a >= Self.minLoopSpanSeconds {
            if pointASeconds == nil { pointASeconds = 0 }
            pointBSeconds = clamped
        }
    }

    public mutating func clear() {
        pointASeconds = nil
        pointBSeconds = nil
    }

    public func shouldSeekToA(currentPositionSeconds: Double) -> Double? {
        guard isActive, let a = pointASeconds, let b = pointBSeconds else { return nil }
        return currentPositionSeconds >= b ? a : nil
    }

    public var badgeText: String {
        switch (pointASeconds, pointBSeconds) {
        case let (a?, b?) where b - a >= Self.minLoopSpanSeconds:
            return "A–B \(TimeFormat.mmss(a)) → \(TimeFormat.mmss(b))"
        case let (a?, nil):
            return "A: \(TimeFormat.mmss(a)) (Set B)"
        default:
            return "A–B Off"
        }
    }
}

// MARK: - Playback & Performance Enums

public enum RepeatTarget: String, Codable, CaseIterable {
    case off = "OFF"
    case all = "ALL"
    case one = "ONE"
    case cycle = "CYCLE"

    public func nextMode() -> RepeatTarget {
        switch self {
        case .off: return .all
        case .all: return .one
        case .one, .cycle: return .off
        }
    }
}

public enum PerformanceModeOption: String, Codable, CaseIterable {
    case auto = "Auto (Recommended)"
    case ultraLight = "Ultra-Light (Old Devices / Max Speed)"
    case balanced = "Balanced"
    case fullVisuals = "Full Visuals"
}

public enum MainTab: String, CaseIterable {
    case home = "Home"
    case search = "Search"
    case library = "Library"
    case settings = "Settings"
}

// MARK: - Synced LRC Lyrics Parser

public struct LrcLine: Identifiable, Equatable, Codable {
    public let id: Int
    public let timeSeconds: Double
    public let text: String
}

public enum LrcParser {
    private static let lineRegex = try! NSRegularExpression(
        pattern: #"\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)"#,
        options: []
    )

    public static func parse(_ rawLrc: String) -> [LrcLine] {
        var parsed: [(Double, String)] = []
        let ns = rawLrc as NSString
        let lines = rawLrc.components(separatedBy: .newlines)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            let range = NSRange(location: 0, length: (trimmed as NSString).length)
            guard let match = lineRegex.firstMatch(in: trimmed, options: [], range: range) else {
                continue
            }
            let minStr = (trimmed as NSString).substring(with: match.range(at: 1))
            let secStr = (trimmed as NSString).substring(with: match.range(at: 2))
            let fracStr: String = match.range(at: 3).location != NSNotFound
                ? (trimmed as NSString).substring(with: match.range(at: 3))
                : "0"
            let content = (trimmed as NSString).substring(with: match.range(at: 4))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !content.isEmpty else { continue }
            let mins = Double(minStr) ?? 0
            let secs = Double(secStr) ?? 0
            let fracScale: Double = fracStr.count == 3 ? 1000.0 : (fracStr.count == 2 ? 100.0 : 10.0)
            let frac = (Double(fracStr) ?? 0) / fracScale
            parsed.append((mins * 60.0 + secs + frac, content))
        }
        _ = ns
        parsed.sort { $0.0 < $1.0 }
        return parsed.enumerated().map { idx, pair in
            LrcLine(id: idx, timeSeconds: pair.0, text: pair.1)
        }
    }

    public static func activeIndex(in lines: [LrcLine], at timeSeconds: Double) -> Int? {
        guard !lines.isEmpty else { return nil }
        var active = 0
        for (idx, line) in lines.enumerated() {
            if timeSeconds + 0.15 >= line.timeSeconds {
                active = idx
            } else {
                break
            }
        }
        return active
    }
}

// MARK: - Spotify / YouTube Link Parser (Parity with PlaylistLinkParser.kt)

public enum ParsedPlaylistLink: Equatable {
    case spotifyPlaylist(id: String)
    case spotifyAlbum(id: String)
    case spotifyTrack(id: String)
    case youtubePlaylist(id: String)
}

public enum PlaylistLinkParser {
    private static let spotifyUriRegex = try! NSRegularExpression(
        pattern: #"^spotify:(playlist|album|track):([A-Za-z0-9]{10,40})$"#,
        options: [.caseInsensitive]
    )
    private static let spotifyUrlRegex = try! NSRegularExpression(
        pattern: #"https?://(?:open|play|embed)\.spotify\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]{10,40})"#,
        options: [.caseInsensitive]
    )
    private static let ytListRegex = try! NSRegularExpression(
        pattern: #"[?&]list=([A-Za-z0-9_-]{10,80})"#,
        options: [.caseInsensitive]
    )

    public static func parse(_ rawInput: String) -> ParsedPlaylistLink? {
        let input = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else { return nil }
        let ns = input as NSString
        let fullRange = NSRange(location: 0, length: ns.length)

        if let m = spotifyUriRegex.firstMatch(in: input, options: [], range: fullRange) {
            let kind = ns.substring(with: m.range(at: 1)).lowercased()
            let id = ns.substring(with: m.range(at: 2))
            return mapSpotify(kind: kind, id: id)
        }
        if let m = spotifyUrlRegex.firstMatch(in: input, options: [], range: fullRange) {
            let kind = ns.substring(with: m.range(at: 1)).lowercased()
            let id = ns.substring(with: m.range(at: 2))
            return mapSpotify(kind: kind, id: id)
        }
        if input.lowercased().contains("youtube.com") || input.lowercased().contains("youtu.be") {
            if let m = ytListRegex.firstMatch(in: input, options: [], range: fullRange) {
                let id = ns.substring(with: m.range(at: 1))
                return .youtubePlaylist(id: id)
            }
        }
        if (input.hasPrefix("PL") || input.hasPrefix("OLAK5uy_") || input.hasPrefix("UU")) && input.count >= 12 && !input.contains("/") {
            return .youtubePlaylist(id: input)
        }
        return nil
    }

    private static func mapSpotify(kind: String, id: String) -> ParsedPlaylistLink? {
        switch kind {
        case "playlist": return .spotifyPlaylist(id: id)
        case "album": return .spotifyAlbum(id: id)
        case "track": return .spotifyTrack(id: id)
        default: return nil
        }
    }
}

public enum TimeFormat {
    public static func mmss(_ totalSeconds: Double) -> String {
        guard totalSeconds.isFinite && totalSeconds >= 0 else { return "0:00" }
        let total = Int(totalSeconds.rounded())
        let m = total / 60
        let s = total % 60
        return String(format: "%d:%02d", m, s)
    }
}
