import AVFoundation
import Foundation
import Speech

// MARK: - Jerry Voice Commands (1:1 Parity with JerryCommand.kt)

public enum JerryNavTarget: String, Equatable {
    case home = "HOME"
    case search = "SEARCH"
    case library = "LIBRARY"
    case settings = "SETTINGS"
    case mixForYou = "MIX_FOR_YOU"
    case analytics = "ANALYTICS"
    case nowPlaying = "NOW_PLAYING"
    case queue = "QUEUE"
    case fullscreen = "FULLSCREEN"
    case back = "BACK"
}

public enum JerryCommand: Equatable {
    case playQuery(String)
    case playArtist(String, shuffle: Bool)
    case playSong(String)
    case playAlbum(String)
    case playPlaylist(String)
    case playGenreOrMood(String)
    case playRadio(String?)
    case playNextQuery(String)
    case addToQueueQuery(String)
    case clearQueue
    case showQueue
    case pause
    case resume
    case stop
    case next
    case previous
    case restartTrack
    case volumeUp
    case volumeDown
    case setVolume(Int)
    case mute
    case unmute
    case seekForward(Int)
    case seekBackward(Int)
    case seekToPercent(Int)
    case setShuffle(Bool?)
    case setRepeat(RepeatTarget)
    case toggleLike(Bool?)
    case dislike
    case setSleepTimer(Int?)
    case navigate(JerryNavTarget)
}

public enum JarvisPhase: String, Equatable {
    case off = "Off"
    case listening = "Listening"
    case awake = "Awake"
    case thinking = "Thinking"
    case result = "Result"
    case error = "Error"
}

public enum JarvisSensitivity: String, CaseIterable, Codable {
    case high = "High (Quiet Mics)"
    case normal = "Normal (Recommended)"
    case low = "Low (Noisy Rooms)"
}

// MARK: - Offline STT Normalizer (Parity with JerrySttNormalizer.kt)

public enum JerrySttNormalizer {
    private static let oneTokenLeadVerbs: [String: String] = [
        "ply": "play", "pley": "play", "plai": "play", "plae": "play", "playe": "play", "plsy": "play",
        "plea": "play", "pray": "play", "blay": "play",
        "strem": "stream", "streem": "stream",
        "shuffel": "shuffle", "shufle": "shuffle", "shuffl": "shuffle", "shaffle": "shuffle", "shufl": "shuffle"
    ]

    private static let commandTokens: [String: String] = [
        "nex": "next", "neks": "next", "nekt": "next", "nxt": "next",
        "previus": "previous", "previos": "previous", "preveous": "previous", "prevus": "previous",
        "paws": "pause", "pos": "pause", "paus": "pause", "pauz": "pause",
        "unpaws": "unpause", "unpaus": "unpause",
        "resum": "resume", "rezume": "resume",
        "stahp": "stop", "stob": "stop", "stopp": "stop",
        "shuffel": "shuffle", "shufle": "shuffle", "shuffl": "shuffle", "shaffle": "shuffle", "shufl": "shuffle",
        "repet": "repeat", "repat": "repeat", "repeet": "repeat", "reapeat": "repeat",
        "valume": "volume", "volum": "volume", "volumn": "volume", "vollume": "volume", "valum": "volume",
        "mut": "mute", "moot": "mute", "myut": "mute",
        "unmut": "unmute", "unmoot": "unmute", "unmyut": "unmute",
        "que": "queue", "cue": "queue", "queu": "queue", "qeue": "queue", "kew": "queue",
        "artest": "artist", "artst": "artist", "artits": "artist", "artis": "artist",
        "albm": "album", "albun": "album", "albem": "album", "allbum": "album",
        "playlst": "playlist", "playlis": "playlist", "plylist": "playlist", "plalist": "playlist",
        "genere": "genre", "jenre": "genre", "genra": "genre", "gnre": "genre",
        "radieo": "radio", "radeo": "radio",
        "sng": "song", "sogn": "song", "trak": "track", "trck": "track",
        "forwrd": "forward", "foward": "forward", "backwrd": "backward", "rewnd": "rewind",
        "seak": "seek", "sek": "seek", "restar": "restart", "restrt": "restart",
        "setings": "settings", "seting": "settings", "librry": "library", "libary": "library",
        "serch": "search", "srch": "search", "fulscreen": "fullscreen",
        "dislik": "dislike", "unlik": "unlike",
        "louda": "louder", "lowder": "louder", "quietr": "quieter", "quiter": "quieter",
        "percnt": "percent", "precent": "percent",
        "minuts": "minutes", "minits": "minutes", "mins": "minutes",
        "secnds": "seconds", "secs": "seconds"
    ]

    private static let leadingDescriptors: [String: String] = [
        "artest": "artist", "artst": "artist", "artits": "artist", "artis": "artist",
        "albm": "album", "albun": "album", "albem": "album", "allbum": "album",
        "playlst": "playlist", "playlis": "playlist", "plylist": "playlist", "plalist": "playlist",
        "genere": "genre", "jenre": "genre", "genra": "genre", "gnre": "genre",
        "radieo": "radio", "radeo": "radio", "sng": "song", "sogn": "song", "trak": "track", "trck": "track"
    ]

    private static let multiWordGenres: [String] = [
        "smooth jazz", "acid jazz", "vocal jazz",
        "classic rock", "hard rock", "soft rock", "indie rock", "alternative rock",
        "punk rock", "folk rock", "progressive rock", "psychedelic rock", "pop rock",
        "hip hop", "drum and bass", "deep house", "tech house", "tropical house",
        "synth pop", "dream pop", "indie pop", "city pop",
        "heavy metal", "death metal", "nu metal", "neo soul", "bossa nova",
        "latin pop", "new age", "easy listening", "feel good", "late night",
        "rainy day", "good vibes", "high energy", "coffee shop", "road trip", "acoustic chill"
    ]

    private static let singleWordGenres: Set<String> = [
        "jazz", "rock", "pop", "metal", "indie", "alternative", "electronic", "edm",
        "house", "techno", "trance", "dubstep", "ambient", "acoustic", "blues",
        "soul", "funk", "reggae", "latin", "salsa", "country", "folk", "bluegrass",
        "classical", "orchestral", "opera", "soundtrack", "instrumental", "piano",
        "guitar", "punk", "grunge", "emo", "gospel", "christian", "afrobeats",
        "bollywood", "disco", "rap", "trap", "phonk", "chill", "chillout",
        "focus", "study", "sleep", "sleeping", "workout", "gym", "fitness",
        "party", "dance", "relax", "relaxing", "calm", "meditation", "yoga",
        "romance", "romantic", "sad", "melancholy", "happy", "upbeat",
        "energize", "energy", "commute", "driving", "morning", "gaming",
        "lo-fi", "r&b", "k-pop", "j-pop"
    ]

    private static let genreAliases: [String: String] = [
        "lofi": "lo-fi", "low fi": "lo-fi", "lo fi": "lo-fi",
        "rnb": "r&b", "r n b": "r&b", "r and b": "r&b", "r & b": "r&b",
        "hiphop": "hip hop", "hip-hop": "hip hop",
        "kpop": "k-pop", "k pop": "k-pop",
        "jpop": "j-pop", "j pop": "j-pop",
        "dnb": "drum and bass", "drum n bass": "drum and bass", "drum & bass": "drum and bass",
        "e d m": "edm", "work out": "workout", "chill out": "chillout",
        "smoth jas": "smooth jazz", "clasic rok": "classic rock"
    ]

    private static let trailingGenreCues: Set<String> = [
        "music", "songs", "song", "tracks", "vibes", "beats", "playlist", "station"
    ]

    public static func normalizeLeadVerb(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return trimmed }
        let parts = trimmed.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard !parts.isEmpty else { return trimmed }

        if parts.count >= 2 {
            let w0 = parts[0].lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ",."))
            let w1 = parts[1].lowercased()
            if w0 == "putt" && w1 == "on" {
                let tail = parts.dropFirst(2).joined(separator: " ")
                return tail.isEmpty ? "put on" : "put on \(tail)"
            }
            if ["liston", "lisen", "listn", "listen"].contains(w0) && ["to", "too"].contains(w1) {
                let tail = parts.dropFirst(2).joined(separator: " ")
                return tail.isEmpty ? "listen to" : "listen to \(tail)"
            }
        }
        let w0 = parts[0].lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ",."))
        if let mapped = oneTokenLeadVerbs[w0] {
            let tail = parts.dropFirst().joined(separator: " ")
            return tail.isEmpty ? mapped : "\(mapped) \(tail)"
        }
        return trimmed
    }

    public static func normalizeCommandTokens(_ lower: String) -> String {
        let merged = lower
            .replacingOccurrences(of: "un mute", with: "unmute")
            .replacingOccurrences(of: "play list", with: "playlist")
            .replacingOccurrences(of: "full screen", with: "fullscreen")
        let tokens = merged.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        return tokens.map { commandTokens[$0] ?? $0 }.joined(separator: " ")
    }

    public static func normalizeLeadingDescriptor(_ body: String) -> String {
        let parts = body.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard !parts.isEmpty else { return body }
        let firstLower = parts[0].lowercased()
        if let repl = leadingDescriptors[firstLower] {
            return ([repl] + parts.dropFirst()).joined(separator: " ")
        }
        if parts.count >= 2 && ["the", "a", "some"].contains(firstLower) {
            let secondLower = parts[1].lowercased()
            if let repl = leadingDescriptors[secondLower] {
                return ([parts[0], repl] + parts.dropFirst(2)).joined(separator: " ")
            }
        }
        return body
    }

    public static func matchGenreOrMood(_ rawQuery: String) -> String? {
        var cleaned = rawQuery.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("some ") && cleaned.count > 5 {
            cleaned = String(cleaned.dropFirst(5)).trimmingCharacters(in: .whitespaces)
        }
        var tokens = cleaned.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        if tokens.count >= 2, let last = tokens.last, trailingGenreCues.contains(last) {
            tokens.removeLast()
        }
        let candidate = tokens.joined(separator: " ")
        if let alias = genreAliases[candidate] { return alias }
        if multiWordGenres.contains(candidate) || singleWordGenres.contains(candidate) {
            return candidate
        }
        return nil
    }
}

// MARK: - Jerry Command Parser (Parity with JerryCommandParser.kt)

public enum JerryCommandParser {
    private static let politePrefixRegex = try! NSRegularExpression(
        pattern: #"^(?:(?:hey[\s,]+jarvis|j[\s,]+jarvis|jay[\s,]+jarvis|jarvis|please|can\s+you|could\s+you|would\s+you|will\s+you|i\s+want\s+you\s+to|i\s+need\s+you\s+to|go\s+ahead\s+and|just|right\s+now|now|um|uh|well)\b[\s,.:;!?-]*)+"#,
        options: [.caseInsensitive]
    )
    private static let suffixPoliteRegex = try! NSRegularExpression(
        pattern: #"(?:[\s,.:;!?-]+\b(?:hey[\s,]+jarvis|jarvis|please|for\s+me|right\s+now|now|thanks|thank\s+you|already))+$"#,
        options: [.caseInsensitive]
    )

    private static let numberWords: [String: Int] = [
        "zero": 0, "oh": 0, "a": 1, "an": 1, "one": 1, "won": 1,
        "two": 2, "to": 2, "too": 2, "three": 3, "tree": 3, "free": 3,
        "four": 4, "for": 4, "fore": 4, "five": 5, "six": 6, "seven": 7,
        "eight": 8, "ate": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
        "fifteen": 15, "twenty": 20, "twenty five": 25, "twenty-five": 25,
        "thirty": 30, "forty": 40, "forty five": 45, "forty-five": 45,
        "fifty": 50, "sixty": 60, "seventy": 70, "seventy five": 75,
        "eighty": 80, "ninety": 90, "hundred": 100, "one hundred": 100
    ]

    public static func parse(_ rawText: String) -> JerryCommand? {
        var clean = rawText
            .replacingOccurrences(of: "[unk]", with: " ", options: .caseInsensitive)
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: ",.!?:;- "))
        guard !clean.isEmpty else { return nil }

        clean = replaceRegex(politePrefixRegex, in: clean, with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: ",.!?:;- "))
        guard !clean.isEmpty else { return nil }

        let verbNormalized = JerrySttNormalizer.normalizeLeadVerb(clean)
        let transportCandidate = replaceRegex(suffixPoliteRegex, in: verbNormalized, with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: ",.!?:;- "))
        let transportNorm = JerrySttNormalizer.normalizeCommandTokens(transportCandidate.lowercased())

        if !transportNorm.isEmpty {
            if let vol = parseVolume(transportNorm) { return vol }
            if let sleep = parseSleepTimer(transportNorm) { return sleep }
            if let seek = parseSeek(transportNorm) { return seek }
            if let shuf = parseShuffleMode(transportNorm) { return shuf }
            if let rep = parseRepeatMode(transportNorm) { return rep }
            if let like = parseLikeDislike(transportNorm) { return like }
            if let nav = parseNavigation(transportNorm) { return nav }
            if let transport = parseTransport(transportNorm) { return transport }
        }

        let queryCleaned = replaceRegex(suffixPoliteRegex, in: verbNormalized, with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: ",.!? "))
        if !queryCleaned.isEmpty {
            if let qCmd = parseQueueAdd(queryCleaned) { return qCmd }
            if let shufTarget = parseShuffleTarget(queryCleaned) { return shufTarget }
            if let radio = parseRadio(queryCleaned) { return radio }
            if let playCmd = parsePlayTarget(queryCleaned) { return playCmd }
        }
        return nil
    }

    private static func parseTransport(_ lower: String) -> JerryCommand? {
        let nextSet: Set<String> = [
            "next", "next song", "next track", "next one", "next up", "skip", "skip song",
            "skip track", "skip this", "skip this song", "skip it", "play next", "play next song",
            "play the next song", "another song", "play another song", "change song", "switch song"
        ]
        if nextSet.contains(lower) { return .next }

        let prevSet: Set<String> = [
            "previous", "previous song", "previous track", "back", "go back", "last song",
            "last track", "play previous", "play previous song", "play the previous song",
            "song before", "the song before", "go back a song"
        ]
        if prevSet.contains(lower) { return .previous }

        let pauseSet: Set<String> = [
            "pause", "pause music", "pause the music", "pause it", "pause song",
            "pause the song", "pause playback", "hold on"
        ]
        if pauseSet.contains(lower) { return .pause }

        let stopSet: Set<String> = [
            "stop", "stop music", "stop the music", "stop it", "stop playing",
            "stop playback", "quiet", "be quiet", "shut up"
        ]
        if stopSet.contains(lower) { return .stop }

        let resumeSet: Set<String> = [
            "play", "resume", "play music", "play the music", "resume music", "play some music",
            "start music", "start playing", "resume playback", "unpause", "continue", "keep playing"
        ]
        if resumeSet.contains(lower) { return .resume }

        let restartSet: Set<String> = [
            "restart", "restart song", "restart the song", "restart track", "start over", "replay song"
        ]
        if restartSet.contains(lower) { return .restartTrack }

        if ["mute", "mute volume", "mute audio"].contains(lower) { return .mute }
        if ["unmute", "unmute volume", "unmute audio", "sound on", "turn sound on"].contains(lower) { return .unmute }
        if ["clear queue", "clear the queue", "empty queue"].contains(lower) { return .clearQueue }
        if ["show queue", "show the queue", "open queue", "what is up next"].contains(lower) { return .showQueue }

        let volUpSet: Set<String> = [
            "volume up", "turn volume up", "turn the volume up", "louder", "turn it up",
            "turn up", "make it louder", "increase volume", "raise volume", "higher"
        ]
        if volUpSet.contains(lower) { return .volumeUp }

        let volDownSet: Set<String> = [
            "volume down", "turn volume down", "turn the volume down", "quieter", "turn it down",
            "turn down", "make it quieter", "decrease volume", "lower volume", "softer", "lower"
        ]
        if volDownSet.contains(lower) { return .volumeDown }
        return nil
    }

    private static func parseVolume(_ lower: String) -> JerryCommand? {
        if ["max volume", "maximum volume", "full volume", "volume all the way up", "all the way up", "crank it up"].contains(lower) {
            return .setVolume(100)
        }
        if ["min volume", "minimum volume", "lowest volume", "volume all the way down", "all the way down"].contains(lower) {
            return .setVolume(10)
        }
        if ["half volume", "medium volume", "middle volume", "normal volume"].contains(lower) {
            return .setVolume(50)
        }

        let pattern = try! NSRegularExpression(
            pattern: #"\b(?:(?:set|put|change|turn|make|adjust)\s+)?(?:the\s+)?(?:volume|sound|audio|level)(?:\s+(?:level|to|at))?\s+([a-z0-9%-]+(?:\s+five)?)\s*(percent|%)?\b"#,
            options: [.caseInsensitive]
        )
        let ns = lower as NSString
        if let m = pattern.firstMatch(in: lower, options: [], range: NSRange(location: 0, length: ns.length)) {
            let rawTok = ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "%", with: "").trimmingCharacters(in: .whitespaces)
            let hasPct = m.range(at: 2).location != NSNotFound || ns.substring(with: m.range(at: 1)).hasSuffix("%")
            if let val = Int(rawTok) ?? numberWords[rawTok] {
                let scaled = (!hasPct && val >= 1 && val <= 10) ? val * 10 : val
                if scaled >= 0 && scaled <= 100 { return .setVolume(scaled) }
            }
        }
        return nil
    }

    private static func parseSleepTimer(_ lower: String) -> JerryCommand? {
        if ["cancel sleep timer", "stop sleep timer", "sleep timer off", "disable sleep timer"].contains(lower) {
            return .setSleepTimer(nil)
        }
        let pattern = try! NSRegularExpression(
            pattern: #"^(?:(?:set|start)\s+)?(?:a\s+|the\s+)?sleep\s+timer(?:\s+(?:for|to|in))?\s+(\d{1,3}|[a-z]+)\s*(hours?|minutes?|mins?)?$"#,
            options: [.caseInsensitive]
        )
        let ns = lower as NSString
        if let m = pattern.firstMatch(in: lower, options: [], range: NSRange(location: 0, length: ns.length)) {
            let tok = ns.substring(with: m.range(at: 1))
            let unit = m.range(at: 2).location != NSNotFound ? ns.substring(with: m.range(at: 2)) : "minutes"
            if ["off", "cancel", "stop", "zero"].contains(tok) { return .setSleepTimer(nil) }
            if let n = Int(tok) ?? numberWords[tok] {
                let mins = unit.hasPrefix("h") ? n * 60 : n
                return .setSleepTimer(mins > 0 ? mins : nil)
            }
        }
        return nil
    }

    private static func parseSeek(_ lower: String) -> JerryCommand? {
        if ["fast forward", "seek forward", "jump forward"].contains(lower) { return .seekForward(10) }
        if ["rewind", "seek back", "seek backward", "jump back"].contains(lower) { return .seekBackward(10) }

        let pctRegex = try! NSRegularExpression(pattern: #"^(?:seek|jump|skip|go)\s+(?:to\s+)?(\d{1,3}|[a-z]+)\s*(?:percent|%)$"#, options: [])
        let ns = lower as NSString
        if let m = pctRegex.firstMatch(in: lower, options: [], range: NSRange(location: 0, length: ns.length)) {
            let tok = ns.substring(with: m.range(at: 1))
            if let n = Int(tok) ?? numberWords[tok], (0...100).contains(n) {
                return .seekToPercent(n)
            }
        }

        let fwdRegex = try! NSRegularExpression(pattern: #"^(?:fast\s+forward|seek\s+forward|skip\s+forward|jump\s+forward|forward)\s+(?:by\s+)?(\d{1,4}|[a-z]+)\s*(seconds?|secs?|minutes?|mins?)?$"#, options: [])
        if let m = fwdRegex.firstMatch(in: lower, options: [], range: NSRange(location: 0, length: ns.length)) {
            let tok = ns.substring(with: m.range(at: 1))
            let unit = m.range(at: 2).location != NSNotFound ? ns.substring(with: m.range(at: 2)) : "seconds"
            if let n = Int(tok) ?? numberWords[tok], n > 0 {
                return .seekForward(unit.hasPrefix("m") ? n * 60 : n)
            }
        }

        let bwdRegex = try! NSRegularExpression(pattern: #"^(?:rewind|seek\s+back(?:ward)?|skip\s+back(?:ward)?|jump\s+back(?:ward)?)\s+(?:by\s+)?(\d{1,4}|[a-z]+)\s*(seconds?|secs?|minutes?|mins?)?$"#, options: [])
        if let m = bwdRegex.firstMatch(in: lower, options: [], range: NSRange(location: 0, length: ns.length)) {
            let tok = ns.substring(with: m.range(at: 1))
            let unit = m.range(at: 2).location != NSNotFound ? ns.substring(with: m.range(at: 2)) : "seconds"
            if let n = Int(tok) ?? numberWords[tok], n > 0 {
                return .seekBackward(unit.hasPrefix("m") ? n * 60 : n)
            }
        }
        return nil
    }

    private static func parseShuffleMode(_ lower: String) -> JerryCommand? {
        if ["shuffle on", "turn shuffle on", "turn on shuffle", "enable shuffle"].contains(lower) { return .setShuffle(true) }
        if ["shuffle off", "turn shuffle off", "turn off shuffle", "disable shuffle"].contains(lower) { return .setShuffle(false) }
        if ["shuffle", "toggle shuffle", "shuffle queue"].contains(lower) { return .setShuffle(nil) }
        return nil
    }

    private static func parseRepeatMode(_ lower: String) -> JerryCommand? {
        if ["repeat off", "turn repeat off", "loop off", "no repeat"].contains(lower) { return .setRepeat(.off) }
        if ["repeat one", "repeat 1", "repeat this song", "repeat song", "loop this song", "loop song", "loop one"].contains(lower) { return .setRepeat(.one) }
        if ["repeat all", "repeat queue", "repeat on", "loop all", "loop on"].contains(lower) { return .setRepeat(.all) }
        if ["repeat", "toggle repeat", "loop", "toggle loop"].contains(lower) { return .setRepeat(.cycle) }
        return nil
    }

    private static func parseLikeDislike(_ lower: String) -> JerryCommand? {
        if ["like", "like song", "like this", "like this song", "love this song", "thumbs up", "add to liked"].contains(lower) {
            return .toggleLike(true)
        }
        if ["unlike", "unlike this song", "unlike song", "remove like"].contains(lower) {
            return .toggleLike(false)
        }
        if lower == "toggle like" { return .toggleLike(nil) }
        if ["dislike", "dislike this song", "dislike song", "thumbs down"].contains(lower) { return .dislike }
        return nil
    }

    private static func parseNavigation(_ lower: String) -> JerryCommand? {
        if ["home", "open home", "go home", "go to home"].contains(lower) { return .navigate(.home) }
        if ["search", "open search", "go to search"].contains(lower) { return .navigate(.search) }
        if ["library", "open library", "go to library", "my library"].contains(lower) { return .navigate(.library) }
        if ["settings", "open settings", "go to settings"].contains(lower) { return .navigate(.settings) }
        if ["mix for you", "open mix for you"].contains(lower) { return .navigate(.mixForYou) }
        if ["analytics", "open analytics", "show analytics"].contains(lower) { return .navigate(.analytics) }
        if ["now playing", "open now playing", "show now playing"].contains(lower) { return .navigate(.nowPlaying) }
        if ["fullscreen", "full screen", "open fullscreen"].contains(lower) { return .navigate(.fullscreen) }
        if ["navigate back", "previous screen", "close screen", "close player", "exit fullscreen"].contains(lower) { return .navigate(.back) }
        return nil
    }

    private static func parseQueueAdd(_ text: String) -> JerryCommand? {
        let lower = text.lowercased()
        if lower.hasPrefix("play next ") {
            let target = String(text.dropFirst("play next ".count)).trimmingCharacters(in: .whitespaces)
            if !target.isEmpty && !["song", "track", "one"].contains(target.lowercased()) {
                return .playNextQuery(target)
            }
        }
        let addEndRegex = try! NSRegularExpression(pattern: #"^add\s+(.+?)\s+to\s+(?:the\s+)?(?:queue|cue|que)$"#, options: [.caseInsensitive])
        let ns = text as NSString
        if let m = addEndRegex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            let target = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            if !target.isEmpty { return .addToQueueQuery(target) }
        }
        let qPrefixRegex = try! NSRegularExpression(pattern: #"^(?:queue|cue|que|enqueue)(?:\s+up)?\s+(.+)$"#, options: [.caseInsensitive])
        if let m = qPrefixRegex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            let target = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            if !target.isEmpty { return .addToQueueQuery(target) }
        }
        return nil
    }

    private static func parseShuffleTarget(_ text: String) -> JerryCommand? {
        let lower = text.lowercased()
        guard lower.hasPrefix("shuffle ") else { return nil }
        let body = String(text.dropFirst("shuffle ".count)).trimmingCharacters(in: .whitespaces)
        if ["on", "off", "mode", "queue"].contains(body.lowercased()) || body.isEmpty { return nil }
        return .playArtist(body, shuffle: true)
    }

    private static func parseRadio(_ text: String) -> JerryCommand? {
        let lower = text.lowercased()
        if ["radio", "play radio", "start radio"].contains(lower) { return .playRadio(nil) }
        if lower.hasSuffix(" radio") {
            var body = String(text.dropLast(" radio".count)).trimmingCharacters(in: .whitespaces)
            if body.lowercased().hasPrefix("play ") { body = String(body.dropFirst(5)).trimmingCharacters(in: .whitespaces) }
            else if body.lowercased().hasPrefix("start ") { body = String(body.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
            return .playRadio(body.isEmpty ? nil : body)
        }
        return nil
    }

    private static func parsePlayTarget(_ text: String) -> JerryCommand? {
        let lower = text.lowercased()
        let prefixes = [
            "play ", "put on ", "throw on ", "spin up ", "spin ",
            "let's listen to ", "lets listen to ", "listen to ",
            "i want to hear ", "i wanna hear ", "stream "
        ]
        var extracted: String?
        for p in prefixes {
            if lower.hasPrefix(p) {
                extracted = String(text.dropFirst(p.count)).trimmingCharacters(in: CharacterSet(charactersIn: ",.!? "))
                break
            }
        }
        guard let rawBody = extracted, !rawBody.isEmpty else { return nil }
        let desc = JerrySttNormalizer.normalizeLeadingDescriptor(rawBody)
        let dLower = desc.lowercased()

        if ["something", "some", "a", "an", "the"].contains(dLower) { return nil }

        if dLower.hasPrefix("artist ") {
            return .playArtist(String(desc.dropFirst(7)).trimmingCharacters(in: .whitespaces), shuffle: false)
        }
        if dLower.hasPrefix("the artist ") {
            return .playArtist(String(desc.dropFirst(11)).trimmingCharacters(in: .whitespaces), shuffle: false)
        }
        let byRegex = try! NSRegularExpression(pattern: #"^(?:songs|music|tracks)\s+(?:by|from)\s+(.+)$"#, options: [.caseInsensitive])
        let ns = desc as NSString
        if let m = byRegex.firstMatch(in: desc, options: [], range: NSRange(location: 0, length: ns.length)) {
            return .playArtist(ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces), shuffle: false)
        }
        if dLower.hasPrefix("album ") {
            return .playAlbum(String(desc.dropFirst(6)).trimmingCharacters(in: .whitespaces))
        }
        if dLower.hasPrefix("the album ") {
            return .playAlbum(String(desc.dropFirst(10)).trimmingCharacters(in: .whitespaces))
        }
        if dLower.hasPrefix("playlist ") {
            return .playPlaylist(String(desc.dropFirst(9)).trimmingCharacters(in: .whitespaces))
        }
        if dLower.hasPrefix("the playlist ") {
            return .playPlaylist(String(desc.dropFirst(13)).trimmingCharacters(in: .whitespaces))
        }
        if dLower.hasPrefix("song ") {
            return .playSong(String(desc.dropFirst(5)).trimmingCharacters(in: .whitespaces))
        }
        if dLower.hasPrefix("the song ") {
            return .playSong(String(desc.dropFirst(9)).trimmingCharacters(in: .whitespaces))
        }

        if let genre = JerrySttNormalizer.matchGenreOrMood(desc) {
            return .playGenreOrMood(genre)
        }

        if dLower.hasSuffix(" songs") {
            let artist = String(desc.dropLast(6)).trimmingCharacters(in: .whitespaces)
            if artist.count >= 2 && !["some", "the", "my", "good", "new"].contains(artist.lowercased()) {
                return .playArtist(artist, shuffle: false)
            }
        }
        return .playQuery(desc)
    }

    private static func replaceRegex(_ regex: NSRegularExpression, in text: String, with replacement: String) -> String {
        let range = NSRange(location: 0, length: (text as NSString).length)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: replacement)
    }
}

// MARK: - Jerry Wake Session (Parity with JerryWakeSession.kt & DesktopJarvis noise stripping)

public final class JerryWakeSession {
    private var deadlineMs: Int64?
    public var awaitingCommand: Bool { deadlineMs != nil }

    private static let wakeRegex = try! NSRegularExpression(
        pattern: #"^(?:(?:(?:the|a|an|um|uh|oh|well|and|okay|ok|please)[\s,]+){0,3}(?:hey|hay|hi|hello|yo|okay|ok|um|uh|a|i'm|im|i|m|home|how|ho|j|jay|he|eh|they|o|k|kay|ay|aye|ey)[\s,]+(?:jarvis|javis|jarvus|jervis|harvis|jave|jarv|chavez|jar\s+vis|jar\s+this|job\s+is|jobs|job's|java|travis|service)|(?:jarvis|javis|jarvus|jervis|harvis))\b"#,
        options: [.caseInsensitive]
    )
    private static let embeddedWakeRegex = try! NSRegularExpression(
        pattern: #"\b(?:hey|hay|hi|hello|yo|okay|ok)[\s,]+(?:jarvis|javis|jarvus|jervis|harvis)\b"#,
        options: [.caseInsensitive]
    )
    private static let quotedGuardRegex = try! NSRegularExpression(
        pattern: #"\b(?:said|says|say|told|tell|asked|ask|called|call|heard|hear|mean|means|wrote|write)\b"#,
        options: [.caseInsensitive]
    )

    public init() {}

    public static func stripLeadingStage1Noise(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return trimmed }
        let ns = trimmed as NSString
        let full = NSRange(location: 0, length: ns.length)
        if wakeRegex.firstMatch(in: trimmed, options: [], range: full) != nil {
            return trimmed
        }
        guard let m = embeddedWakeRegex.firstMatch(in: trimmed, options: [], range: full) else {
            return trimmed
        }
        let prefix = ns.substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
        let words = prefix.split(separator: " ", omittingEmptySubsequences: true)
        let prefixRange = NSRange(location: 0, length: (prefix as NSString).length)
        if words.count <= 2 && quotedGuardRegex.firstMatch(in: prefix, options: [], range: prefixRange) == nil {
            return ns.substring(from: m.range.location).trimmingCharacters(in: .whitespaces)
        }
        return trimmed
    }

    public func hasWakeWord(_ text: String) -> Bool {
        let clean = normalize(text)
        guard !clean.isEmpty else { return false }
        let range = NSRange(location: 0, length: (clean as NSString).length)
        return Self.wakeRegex.firstMatch(in: clean, options: [], range: range) != nil
    }

    @discardableResult
    public func observePartial(_ text: String, nowMs: Int64) -> Bool {
        _ = expire(nowMs)
        let cleaned = Self.stripLeadingStage1Noise(normalize(text))
        if deadlineMs == nil && hasWakeWord(cleaned) {
            deadlineMs = nowMs + 8_000
            return true
        }
        return false
    }

    public func acceptFinal(_ text: String, nowMs: Int64) -> JerryCommand? {
        _ = expire(nowMs)
        let clean = Self.stripLeadingStage1Noise(normalize(text))
        guard !clean.isEmpty else { return nil }
        let ns = clean as NSString
        let range = NSRange(location: 0, length: ns.length)
        if let m = Self.wakeRegex.firstMatch(in: clean, options: [], range: range) {
            if deadlineMs == nil { deadlineMs = nowMs + 8_000 }
            let after = ns.substring(from: m.range.location + m.range.length)
                .trimmingCharacters(in: CharacterSet(charactersIn: " ,.!?:;-"))
            if after.isEmpty { return nil }
            reset()
            return JerryCommandParser.parse(after)
        }
        guard deadlineMs != nil else { return nil }
        reset()
        return JerryCommandParser.parse(clean)
    }

    @discardableResult
    public func expire(_ nowMs: Int64) -> Bool {
        if let d = deadlineMs, nowMs >= d {
            reset()
            return true
        }
        return false
    }

    public func reset() {
        deadlineMs = nil
    }

    private func normalize(_ text: String) -> String {
        text.replacingOccurrences(of: "[unk]", with: " ", options: .caseInsensitive)
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
