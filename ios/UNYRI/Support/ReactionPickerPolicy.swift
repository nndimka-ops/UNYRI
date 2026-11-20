import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

/// Pure window-space geometry shared by the picker and portable B-057 contracts.
/// Insets are values instead of UIEdgeInsets so the policy has no UIKit dependency.
enum UnyriReactionPanelGeometry {
    static func frame(viewport: CGSize, bubble: CGRect, top: CGFloat, bottom: CGFloat,
                      left: CGFloat, right: CGFloat, outgoing: Bool) -> CGRect {
        let safeTop = top + 8
        let safeBottom = max(safeTop, viewport.height - bottom - 8)
        let safeLeft = left + 16
        let safeRight = max(safeLeft, viewport.width - right - 16)
        let width = min(420, safeRight - safeLeft)
        let above = max(0, min(safeBottom, bubble.minY - 8) - safeTop)
        let below = max(0, safeBottom - max(safeTop, bubble.maxY + 8))
        // Prefer the pill's upper side while it can fit useful controls. For a
        // message near the top, move below rather than covering status-bar content.
        let useAbove = above >= 240 || above >= below
        let space = useAbove ? above : below
        let height = max(0, min(space, min(viewport.height * 0.6, safeBottom - safeTop)))
        let desiredX = outgoing ? bubble.maxX - width : bubble.minX
        let desiredY = useAbove ? bubble.minY - 8 - height : bubble.maxY + 8
        let x = min(max(safeLeft, desiredX), safeRight - width)
        let y = min(max(safeTop, desiredY), safeBottom - height)
        return CGRect(x: x, y: y, width: width, height: height)
    }
}

/// Local send intents only. Removing a reaction never calls recordSend.
struct UnyriReactionHistory: Codable, Equatable {
    struct Use: Codable, Equatable {
        let emoji: String
        let time: TimeInterval
    }

    static let defaults = ["👍", "❤️", "😂", "😮", "😢", "🙏", "🔥"]
    static let window: TimeInterval = 30 * 24 * 60 * 60
    private(set) var uses: [Use] = []
    private(set) var recent: [String] = []
    private var tones: [String: String] = [:]

    mutating func recordSend(_ emoji: String, at date: Date = Date()) {
        guard !emoji.isEmpty else { return }
        let now = date.timeIntervalSince1970
        uses.removeAll { $0.time < now - Self.window || $0.time > now }
        uses.append(Use(emoji: emoji, time: now))
        // A send from pasted search or an exact recent is also an explicit tone
        // choice. Its next base cell and personal pill retain that chosen variant.
        let base = UnyriReactionTones.base(of: emoji)
        if UnyriReactionTones.variants(of: base).contains(emoji) { setTone(emoji, for: base) }
        else if UnyriReactionSearch.baseKey(base) == UnyriReactionSearch.baseKey(emoji) {
            setTone(base, for: base)
        }
        recent.removeAll { $0 == emoji }
        recent.insert(emoji, at: 0)
        recent = Array(recent.prefix(16))
    }

    func ranked(quick: String, now date: Date = Date()) -> [String] {
        let now = date.timeIntervalSince1970
        let valid = uses.filter { $0.time >= now - Self.window && $0.time <= now }
        var counts: [String: Int] = [:]
        var latest: [String: TimeInterval] = [:]
        for use in valid {
            let base = UnyriReactionTones.base(of: use.emoji)
            counts[base, default: 0] += 1
            latest[base] = max(latest[base] ?? 0, use.time)
        }
        let personal = counts.keys.sorted {
            if counts[$0] != counts[$1] { return counts[$0, default: 0] > counts[$1, default: 0] }
            if latest[$0] != latest[$1] { return latest[$0, default: 0] > latest[$1, default: 0] }
            return $0 < $1
        }
        var result: [String] = []
        var seen: Set<String> = []
        for emoji in [quick] + personal + Self.defaults where !emoji.isEmpty {
            let base = UnyriReactionTones.base(of: emoji)
            guard seen.insert(UnyriReactionSearch.baseKey(base)).inserted else { continue }
            result.append(tone(for: emoji))
            if result.count == 7 { break }
        }
        return result
    }

    func tone(for base: String) -> String {
        let plain = UnyriReactionTones.base(of: base)
        return tones[plain] ?? base
    }

    mutating func setTone(_ variant: String, for base: String) {
        let plain = UnyriReactionTones.base(of: base)
        guard UnyriReactionTones.variants(of: plain).contains(variant) else { return }
        tones[plain] = variant
    }
}

/// A value snapshot per account, read only when a picker opens or a send occurs.
/// No repository observation, timers, or cross-account composer recents.
final class UnyriReactionStore {
    private let defaults: UserDefaults
    private let key: String

    init(account: String, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        key = Self.storageKey(account: account)
    }

    static func storageKey(account: String) -> String {
        "unyri-reaction-history-v1." + Data(account.utf8).base64EncodedString()
    }

    static func erase(account: String, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: storageKey(account: account))
    }

    var history: UnyriReactionHistory {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode(UnyriReactionHistory.self, from: data) else {
            return UnyriReactionHistory()
        }
        return decoded
    }

    func recordSend(_ emoji: String, at date: Date = Date()) {
        var value = history
        value.recordSend(emoji, at: date)
        save(value)
    }

    func preferred(_ base: String) -> String { history.tone(for: base) }

    func setTone(_ variant: String, for base: String) {
        var value = history
        value.setTone(variant, for: base)
        save(value)
    }

    private func save(_ value: UnyriReactionHistory) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }
}

enum UnyriReactionTones {
    static func base(of emoji: String) -> String {
        let scalars = emoji.unicodeScalars.filter { !$0.properties.isEmojiModifier }
        var result: [Unicode.Scalar] = []
        for index in scalars.indices {
            let scalar = scalars[index]
            result.append(scalar)
            if scalar.properties.isEmojiModifierBase, !scalar.properties.isEmojiPresentation {
                let next = scalars.index(after: index)
                if next == scalars.endIndex || scalars[next].value != 0xFE0F {
                    result.append("\u{FE0F}")
                }
            }
        }
        return String(String.UnicodeScalarView(result))
    }

    static func variants(of emoji: String) -> [String] {
        let plain = base(of: emoji)
        let scalars = Array(plain.unicodeScalars)
        // Multiple-person combinations need the CLDR sequence data; do not synthesize
        // unsupported family or mixed-tone sequences from scalar properties alone.
        let bases = scalars.indices.filter { scalars[$0].properties.isEmojiModifierBase }
        guard bases.count == 1, let index = bases.first else { return [] }
        return [plain] + (0x1F3FB...0x1F3FF).compactMap { value in
            guard let modifier = Unicode.Scalar(value) else { return nil }
            var toned = scalars
            if index + 1 < toned.count, toned[index + 1].value == 0xFE0F { toned.remove(at: index + 1) }
            toned.insert(modifier, at: index + 1)
            return String(String.UnicodeScalarView(toned))
        }
    }
}

enum UnyriReactionSearch {
    static func normalize(_ value: String) -> String {
        value.lowercased().replacingOccurrences(of: "ё", with: "е")
            .folding(options: [.diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }

    static func words(_ value: String) -> [String] {
        normalize(value).split { !$0.isLetter && !$0.isNumber }.map(String.init)
    }

    static func matches(query: String, emoji: String, keywords: [String]) -> Bool {
        let raw = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return false }
        if raw == emoji || baseKey(raw) == baseKey(emoji) { return true }
        let queryWords = words(raw)
        guard !queryWords.isEmpty else { return false }
        return matches(queryWords: queryWords, tokens: keywords.flatMap(words))
    }

    static func matches(queryWords: [String], tokens: [String]) -> Bool {
        !queryWords.isEmpty && queryWords.allSatisfy { prefix in tokens.contains { $0.hasPrefix(prefix) } }
    }

    /// Result order for a matching emoji, lower first: the whole name («огонь» -> 🔥), whole words
    /// of a name («сердце» -> the hearts), a whole search word («кот» -> 🐈 🐱), a name prefix,
    /// then any prefix. With 1,800+ CLDR entries a prefix alone matches too much to lead.
    static func rank(queryWords: [String], names: [String], tokens: [String]) -> Int {
        let nameWords = names.map(words)
        if nameWords.contains(queryWords) { return 0 }
        let wordsInNames = Set(nameWords.joined())
        if queryWords.allSatisfy(wordsInNames.contains) { return 1 }
        if queryWords.allSatisfy(Set(tokens).contains) { return 2 }
        if queryWords.allSatisfy({ query in wordsInNames.contains { $0.hasPrefix(query) } }) { return 3 }
        return 4
    }

    /// Presentation selectors do not change search identity (heart pasted without VS16).
    static func baseKey(_ emoji: String) -> String {
        String(String.UnicodeScalarView(emoji.unicodeScalars.filter { $0.value != 0xFE0F }))
    }
}
