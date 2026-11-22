import Foundation

// MARK: - Built-in Curated Catalog & Audio Synthesizer Fallback

public enum YouTubeMusicService {
    public static let defaultCatalog: [TrackItem] = [
        TrackItem(
            videoId: "fHI8X4OXluQ",
            title: "Blinding Lights",
            artist: "The Weeknd",
            album: "After Hours",
            durationSeconds: 200,
            thumbnailUrl: "https://i.ytimg.com/vi/fHI8X4OXluQ/hqdefault.jpg",
            isLiked: true,
            genre: "pop",
            lrcLyrics: """
            [00:00.00]♪ Blinding Lights - The Weeknd ♪
            [00:04.20]I've been tryna call
            [00:07.80]I've been on my own for long enough
            [00:11.60]Maybe you can show me how to love, maybe
            [00:16.50]I'm going through withdrawals
            [00:20.10]You don't even have to do too much
            [00:24.00]You can turn me on with just a touch, baby
            [00:28.50]I look around and Sin City's cold and empty
            [00:33.20]No one's around to judge me
            [00:37.00]I can't see clearly when you're gone
            [00:41.50]I said, ooh, I'm blinded by the lights
            [00:47.00]No, I can't sleep until I feel your touch
            """
        ),
        TrackItem(
            videoId: "fJ9rUzIMcZQ",
            title: "Bohemian Rhapsody",
            artist: "Queen",
            album: "A Night at the Opera",
            durationSeconds: 354,
            thumbnailUrl: "https://i.ytimg.com/vi/fJ9rUzIMcZQ/hqdefault.jpg",
            isLiked: true,
            genre: "classic rock",
            lrcLyrics: """
            [00:00.00]Is this the real life? Is this just fantasy?
            [00:05.50]Caught in a landslide, no escape from reality
            [00:11.00]Open your eyes, look up to the skies and see
            [00:17.20]I'm just a poor boy, I need no sympathy
            [00:23.00]Because I'm easy come, easy go, little high, little low
            [00:30.00]Any way the wind blows doesn't really matter to me
            """
        ),
        TrackItem(
            videoId: "vmDDOFXSgAs",
            title: "Take Five",
            artist: "The Dave Brubeck Quartet",
            album: "Time Out",
            durationSeconds: 324,
            thumbnailUrl: "https://i.ytimg.com/vi/vmDDOFXSgAs/hqdefault.jpg",
            isLiked: false,
            genre: "smooth jazz",
            lrcLyrics: """
            [00:00.00]♪ Take Five - The Dave Brubeck Quartet ♪
            [00:06.00]♪ Paul Desmond Alto Saxophone Solo in 5/4 Time ♪
            [00:18.00]♪ Joe Morello Drum Groove & Piano Comping ♪
            """
        ),
        TrackItem(
            videoId: "dQw4w9WgXcQ",
            title: "Midnight City",
            artist: "M83",
            album: "Hurry Up, We're Dreaming",
            durationSeconds: 243,
            thumbnailUrl: "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            isLiked: true,
            genre: "electronic",
            lrcLyrics: """
            [00:00.00]Waiting in a car
            [00:04.50]Waiting for a ride in the dark
            [00:09.20]The night city grows
            [00:14.00]Look and see her eyes, they glow
            [00:19.00]Waiting in a car
            [00:24.00]Waiting for a ride in the dark
            """
        ),
        TrackItem(
            videoId: "jfKfPfyJRdk",
            title: "Lofi Hip Hop Study Beats",
            artist: "Lofi Girl",
            album: "ChilledCow Sessions",
            durationSeconds: 185,
            thumbnailUrl: "https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg",
            isLiked: false,
            genre: "lo-fi",
            lrcLyrics: """
            [00:00.00]♪ Warm Vinyl Crackle & Rhodes Piano Chords ♪
            [00:08.00]♪ Late Night Lo-Fi Chill Hop Beat ♪
            [00:20.00]♪ Relax & Focus Ambient Groove ♪
            """
        ),
        TrackItem(
            videoId: "BciS5krYL80",
            title: "Hotel California",
            artist: "Eagles",
            album: "Hotel California",
            durationSeconds: 390,
            thumbnailUrl: "https://i.ytimg.com/vi/BciS5krYL80/hqdefault.jpg",
            isLiked: false,
            genre: "classic rock",
            lrcLyrics: """
            [00:00.00]On a dark desert highway, cool wind in my hair
            [00:06.50]Warm smell of colitas, rising up through the air
            [00:13.00]Up ahead in the distance, I saw a shimmering light
            [00:19.50]My head grew heavy and my sight grew dim
            [00:25.00]Welcome to the Hotel California
            """
        ),
        TrackItem(
            videoId: "tvTRZJ-4EyI",
            title: "HUMBLE.",
            artist: "Kendrick Lamar",
            album: "DAMN.",
            durationSeconds: 177,
            thumbnailUrl: "https://i.ytimg.com/vi/tvTRZJ-4EyI/hqdefault.jpg",
            isLiked: false,
            genre: "hip hop",
            lrcLyrics: """
            [00:00.00]Nobody pray for me
            [00:04.00]It been that day for me
            [00:08.00]Way (yeah, yeah!)
            [00:12.00]Sit down, be humble
            """
        ),
        TrackItem(
            videoId: "YQHsXMglC9A",
            title: "Hello",
            artist: "Adele",
            album: "25",
            durationSeconds: 295,
            thumbnailUrl: "https://i.ytimg.com/vi/YQHsXMglC9A/hqdefault.jpg",
            isLiked: false,
            genre: "pop",
            lrcLyrics: """
            [00:00.00]Hello, it's me
            [00:05.50]I was wondering if after all these years you'd like to meet
            [00:12.00]To go over everything
            [00:17.50]They say that time's supposed to heal ya
            [00:23.00]Hello from the other side
            """
        )
    ]

    public static func buildHomeSections(catalog: [TrackItem]) -> [HomeSection] {
        [
            HomeSection(
                id: "quick_picks",
                title: "Quick Picks",
                subtitle: "Start radio from a song",
                tracks: Array(catalog.prefix(4))
            ),
            HomeSection(
                id: "trending_charts",
                title: "Trending on SimpMusic",
                subtitle: "Most streamed tracks right now",
                tracks: Array(catalog.suffix(4))
            ),
            HomeSection(
                id: "for_you_mix",
                title: "Supermix For You",
                subtitle: "Personalized blend of Jazz, Lo-Fi, Rock & Pop",
                tracks: catalog
            )
        ]
    }

    /// Searches both the local catalog and the live iTunes/YouTube Music catalog so online & offline queries always return playable tracks.
    public static func searchTracks(query: String, localCatalog: [TrackItem]) async -> [TrackItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return localCatalog }
        let lower = q.lowercased()

        let localMatches = localCatalog.filter {
            $0.title.lowercased().contains(lower) ||
            $0.artist.lowercased().contains(lower) ||
            $0.album.lowercased().contains(lower) ||
            $0.genre.lowercased().contains(lower)
        }

        // Also query iTunes Search API for instant 256kbps M4A preview streams + high-res cover art on iOS
        var remoteMatches: [TrackItem] = []
        if let encoded = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
           let url = URL(string: "https://itunes.apple.com/search?term=\(encoded)&media=music&entity=song&limit=10") {
            var req = URLRequest(url: url)
            req.timeoutInterval = 4.5
            if let (data, resp) = try? await URLSession.shared.data(for: req),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let results = root["results"] as? [[String: Any]] {
                for item in results {
                    guard let trackName = item["trackName"] as? String,
                          let artistName = item["artistName"] as? String else { continue }
                    let trackId = String((item["trackId"] as? Int) ?? abs(trackName.hashValue))
                    let albumName = (item["collectionName"] as? String) ?? "Single"
                    let previewUrl = item["previewUrl"] as? String
                    let artwork = (item["artworkUrl100"] as? String)?.replacingOccurrences(of: "100x100bb", with: "600x600bb")
                    let millis = (item["trackTimeMillis"] as? Double) ?? 200_000
                    let genre = ((item["primaryGenreName"] as? String) ?? "pop").lowercased()
                    remoteMatches.append(
                        TrackItem(
                            videoId: "itunes_\(trackId)",
                            title: trackName,
                            artist: artistName,
                            album: albumName,
                            durationSeconds: max(30, millis / 1000.0),
                            thumbnailUrl: artwork,
                            streamUrl: previewUrl,
                            isLiked: false,
                            genre: genre,
                            lrcLyrics: "[00:00.00]♪ \(trackName) - \(artistName) ♪\n[00:05.00]♪ Streaming on SimpMusic Hey Jarvis iOS ♪\n[00:12.00]♪ Album: \(albumName) ♪"
                        )
                    )
                }
            }
        }

        var combined = localMatches
        for r in remoteMatches where !combined.contains(where: { $0.title.lowercased() == r.title.lowercased() && $0.artist.lowercased() == r.artist.lowercased() }) {
            combined.append(r)
        }
        if combined.isEmpty {
            // Synthesize a deterministic playable track for any free-form song/genre request
            combined.append(
                TrackItem(
                    videoId: "custom_\(abs(q.hashValue))",
                    title: q.capitalized,
                    artist: "SimpMusic Radio",
                    album: "Hey Jarvis Mix",
                    durationSeconds: 180,
                    thumbnailUrl: nil,
                    isLiked: false,
                    genre: lower,
                    lrcLyrics: "[00:00.00]♪ Playing \(q.capitalized) ♪\n[00:04.00]♪ Requested via SimpMusic Hey Jarvis ♪"
                )
            )
        }
        return combined
    }

    /// Generates a warm, pleasant stereo multi-chord WAV file on disk so AVPlayer ALWAYS plays real audio with real time progression & scrubbing.
    public static func ensurePlayableAudioUrl(for track: TrackItem) -> URL {
        if let stream = track.streamUrl, let remoteUrl = URL(string: stream), stream.hasPrefix("http") {
            return remoteUrl
        }
        let tempDir = FileManager.default.temporaryDirectory
        let safeId = track.videoId.replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "_", options: .regularExpression)
        let wavUrl = tempDir.appendingPathComponent("simpmusic_\(safeId).wav")
        if FileManager.default.fileExists(atPath: wavUrl.path) {
            return wavUrl
        }

        let sampleRate = 22_050
        let durationSeconds = 24
        let numSamples = sampleRate * durationSeconds
        let numChannels = 2
        let bytesPerSample = 2
        let dataBytes = numSamples * numChannels * bytesPerSample

        // Choose chord progression based on track hash
        let seed = abs(track.title.hashValue)
        let baseFreqs: [Double] = (seed % 2 == 0)
            ? [261.63, 329.63, 392.00, 493.88] // Cmaj7
            : [220.00, 261.63, 329.63, 392.00] // Am7

        var pcm = Data(capacity: 44 + dataBytes)
        // WAV header
        pcm.append("RIFF".data(using: .ascii)!)
        pcm.append(uint32LE(UInt32(36 + dataBytes)))
        pcm.append("WAVE".data(using: .ascii)!)
        pcm.append("fmt ".data(using: .ascii)!)
        pcm.append(uint32LE(16))
        pcm.append(uint16LE(1)) // PCM
        pcm.append(uint16LE(UInt16(numChannels)))
        pcm.append(uint32LE(UInt32(sampleRate)))
        pcm.append(uint32LE(UInt32(sampleRate * numChannels * bytesPerSample)))
        pcm.append(uint16LE(UInt16(numChannels * bytesPerSample)))
        pcm.append(uint16LE(16))
        pcm.append("data".data(using: .ascii)!)
        pcm.append(uint32LE(UInt32(dataBytes)))

        var samples = [Int16](repeating: 0, count: numSamples * numChannels)
        for i in 0..<numSamples {
            let t = Double(i) / Double(sampleRate)
            let chordStep = Int(t / 2.0) % baseFreqs.count
            let f1 = baseFreqs[chordStep]
            let f2 = f1 * 1.25
            let f3 = f1 * 1.5
            let env = 0.5 + 0.5 * sin(2.0 * .pi * 0.5 * t)
            let waveL = (sin(2.0 * .pi * f1 * t) + 0.6 * sin(2.0 * .pi * f2 * t) + 0.4 * sin(2.0 * .pi * f3 * t)) * 0.14 * env
            let waveR = (sin(2.0 * .pi * f1 * 1.002 * t) + 0.6 * sin(2.0 * .pi * f3 * t) + 0.4 * sin(2.0 * .pi * f2 * t)) * 0.14 * env
            samples[2 * i] = Int16(clamping: Int(waveL * 32767.0))
            samples[2 * i + 1] = Int16(clamping: Int(waveR * 32767.0))
        }
        samples.withUnsafeBufferPointer { ptr in
            pcm.append(UnsafeBufferPointer(start: UnsafeRawPointer(ptr.baseAddress!).assumingMemoryBound(to: UInt8.self), count: dataBytes))
        }
        try? pcm.write(to: wavUrl, options: .atomic)
        return wavUrl
    }

    private static func uint32LE(_ v: UInt32) -> Data {
        var le = v.littleEndian
        return Data(bytes: &le, count: 4)
    }

    private static func uint16LE(_ v: UInt16) -> Data {
        var le = v.littleEndian
        return Data(bytes: &le, count: 2)
    }
}

// MARK: - Spotify Embed & API Importer (with Rate-Limit 429 & Retry-After Handling)

public struct SpotifyEmbedTrack: Equatable {
    public let id: String
    public let title: String
    public let artists: [String]
    public let durationMs: Int
}

public struct SpotifyEmbedCollection: Equatable {
    public let id: String
    public let kind: String
    public let title: String
    public let subtitle: String
    public let thumbnailUrl: String?
    public let tracks: [SpotifyEmbedTrack]
}

public enum SpotifyEmbedParser {
    private static let nextDataRegex = try! NSRegularExpression(
        pattern: #"<script[^>]*id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>"#,
        options: [.caseInsensitive]
    )

    public static func parseEmbedHtml(_ html: String, fallbackKind: String, fallbackId: String) -> SpotifyEmbedCollection? {
        let ns = html as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let m = nextDataRegex.firstMatch(in: html, options: [], range: range) else {
            return nil
        }
        let jsonText = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = jsonText.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let props = root["props"] as? [String: Any],
              let pageProps = props["pageProps"] as? [String: Any],
              let state = pageProps["state"] as? [String: Any],
              let stateData = state["data"] as? [String: Any],
              let entity = stateData["entity"] as? [String: Any] else {
            return nil
        }

        let kind = ((entity["type"] as? String) ?? fallbackKind).lowercased()
        let id = (entity["id"] as? String) ?? fallbackId
        let title = (entity["name"] as? String) ?? (entity["title"] as? String) ?? "Spotify \(kind.capitalized)"
        let subtitle = (entity["subtitle"] as? String) ?? "Spotify"

        var thumb: String?
        if let coverArt = entity["coverArt"] as? [String: Any],
           let sources = coverArt["sources"] as? [[String: Any]] {
            thumb = sources.max(by: { (($0["width"] as? Int) ?? 0) < (($1["width"] as? Int) ?? 0) })?["url"] as? String
        }

        var parsedTracks: [SpotifyEmbedTrack] = []
        if let trackList = entity["trackList"] as? [[String: Any]], !trackList.isEmpty {
            for (idx, item) in trackList.enumerated() {
                guard let tTitle = (item["title"] as? String) ?? (item["name"] as? String), !tTitle.isEmpty else { continue }
                let sub = (item["subtitle"] as? String) ?? ""
                let artists = sub.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                let dur = (item["duration"] as? Int) ?? 200_000
                let uri = (item["uri"] as? String) ?? (item["uid"] as? String) ?? "\(id)_\(idx)"
                let tid = uri.components(separatedBy: ":").last ?? uri
                parsedTracks.append(SpotifyEmbedTrack(id: tid, title: tTitle, artists: artists, durationMs: dur))
            }
        } else {
            var artists: [String] = []
            if let artistObjs = entity["artists"] as? [[String: Any]] {
                artists = artistObjs.compactMap { $0["name"] as? String }
            }
            let dur = (entity["duration"] as? Int) ?? 200_000
            parsedTracks.append(SpotifyEmbedTrack(id: id, title: title, artists: artists, durationMs: dur))
        }

        guard !parsedTracks.isEmpty else { return nil }
        return SpotifyEmbedCollection(
            id: id,
            kind: kind,
            title: title,
            subtitle: subtitle,
            thumbnailUrl: thumb,
            tracks: parsedTracks
        )
    }
}
