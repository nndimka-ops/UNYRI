import Foundation

/// Presentation only. Never persists, validates or rewrites a stored profile.
/// Keep presentation vectors in sync with Android DisplayNameSanitizerTest.
enum DisplayNameSanitizer {
    static let maximumScalars = 64

    static let maximumInputUnits = 1024
    private struct Input: Hashable { let value: String; let clipped: Bool }
    private struct Key: Hashable { let raw: Input; let fallback: Input }
    private final class Memo: @unchecked Sendable {
        struct Entry { let value: String; var used: UInt64 }
        let lock = NSLock()
        var values: [Key: Entry] = [:]
        var tick: UInt64 = 0
        var computations = 0
        func get(_ key: Key) -> String? {
            lock.lock(); defer { lock.unlock() }
            guard var entry = values[key] else { return nil }
            tick &+= 1; entry.used = tick; values[key] = entry
            return entry.value
        }
        func put(_ key: Key, _ value: String) {
            lock.lock(); defer { lock.unlock() }
            tick &+= 1; computations += 1
            if values.count >= 256, values[key] == nil, let oldest = values.min(by: { $0.value.used < $1.value.used })?.key {
                values.removeValue(forKey: oldest)
            }
            values[key] = Entry(value: value, used: tick)
        }
    }
    private static let memo = Memo()
    static var computationCount: Int {
        memo.lock.lock(); defer { memo.lock.unlock() }; return memo.computations
    }
    private static func bounded(_ raw: String) -> Input {
        // Inspect a bounded prefix only: even count/hash must not walk a 1 MB value.
        let units = Array(raw.utf16.prefix(maximumInputUnits + 1))
        let clipped = units.count > maximumInputUnits
        var prefix = Array(units.prefix(maximumInputUnits))
        if clipped, let last = prefix.last, (0xD800...0xDBFF).contains(last) { prefix.removeLast() }
        return Input(value: String(decoding: prefix, as: UTF16.self), clipped: clipped)
    }
    static func display(_ raw: String, fallback: String = "") -> String {
        let key = Key(raw: bounded(raw), fallback: bounded(fallback))
        if let cached = memo.get(key) { return cached }
        let first = clean(key.raw.value)
        let source = first.isEmpty ? key.fallback : key.raw
        let name = first.isEmpty ? clean(source.value) : first
        let result: String
        if name.unicodeScalars.count > maximumScalars || (source.clipped && !name.isEmpty) {
            let prefix = String(String.UnicodeScalarView(name.unicodeScalars.prefix(maximumScalars - 1)))
            result = clean(prefix) + "…"
        } else { result = name }
        memo.put(key, result)
        return result
    }
    static func peer(_ raw: String, username: String? = nil, unknown: String = "Unknown user") -> String {
        // Kept as an ignored source-compatible parameter for older adapters.
        // A peer's legacy username can be their private sign-in credential.
        return display(raw, fallback: unknown.isEmpty ? "Unknown user" : unknown)
    }
    private static func clean(_ raw: String) -> String {
        if raw.utf8.allSatisfy({ (0x20...0x7E).contains($0) }) {
            return raw.split(separator: " ").joined(separator: " ")
        }
        let input = Array(raw.decomposedStringWithCanonicalMapping.unicodeScalars)
        var kept: [Unicode.Scalar] = []
        kept.reserveCapacity(input.count)
        for (index, scalar) in input.enumerated() {
            let c: Unicode.Scalar = scalar.properties.isWhitespace ? " " : scalar
            let prev = kept.last
            let next = index + 1 < input.count ? input[index + 1] : nil
            let keep: Bool
            switch c.value {
            case 0xE0020...0xE007F: // tags spell a subdivision flag after the black flag only
                keep = prev.map { $0.value == 0x1F3F4 || isTag($0) } ?? false
            default:
                keep = !removed(c) && !isFormat(c)
            }
            if keep { kept.append(c) }
        }
        // Baseless marks go before the whitespace collapse, so a dropped mark
        // never leaves two spaces behind (same result as collapse-then-drop).
        var marked: [Unicode.Scalar] = []
        var run = 0
        for c in kept {
            if isMark(c) {
                guard let base = marked.last, base != " ", run < 4 else { continue }
                run += 1
            } else {
                run = 0
            }
            marked.append(c)
        }
        var collapsed: [Unicode.Scalar] = []
        for c in marked where !(c == " " && (collapsed.last ?? " ") == " ") { collapsed.append(c) }
        while collapsed.last == " " { collapsed.removeLast() }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: collapsed)
        return String(view).precomposedStringWithCanonicalMapping
    }

    // Explicit format ranges match Android without depending on OS categories.
    private static func isFormat(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0xAD, 0x600...0x605, 0x61C, 0x6DD, 0x70F, 0x890...0x891, 0x8E2, 0x180E,
             0x200B...0x200F, 0x202A...0x202E, 0x2060...0x206F, 0xFEFF, 0xFFF9...0xFFFB,
             0x110BD, 0x110CD, 0x13430...0x13440, 0x1BCA0...0x1BCA3, 0x1D173...0x1D17A,
             0xE0001, 0xE0020...0xE007F: return true
        default: return false
        }
    }

    private static func isMark(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    private static func isTag(_ c: Unicode.Scalar) -> Bool { (0xE0020...0xE007F).contains(c.value) }
    private static func isVariationSelector(_ c: Unicode.Scalar) -> Bool { c.value == 0xFE0E || c.value == 0xFE0F }

    private static func emojiLike(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x2194...0x21AA, 0x231A...0x23FF,
             0x24C2, 0x25AA...0x25FE, 0x2600...0x27BF, 0x2934...0x2935, 0x2B05...0x2B55, 0x3030,
             0x303D, 0x3297, 0x3299, 0x1F000...0x1FAFF, 0xFE0F, 0x20E3:
            return true
        default: return false
        }
    }

    private static func joiningScript(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x0600...0x06FF, 0x0700...0x074F, 0x0750...0x077F, 0x08A0...0x08FF, 0x0900...0x0DFF,
             0xFB50...0xFDFF, 0xFE70...0xFEFE:
            return true
        default: return false
        }
    }

    /// Invisible, direction-changing, filler and private-use scalars. ZWJ,
    /// ZWNJ, tags and U+FE0E/U+FE0F have their own rules in `clean`.
    private static func removed(_ c: Unicode.Scalar) -> Bool {
        let u = c.value
        if (u & 0xFFFE) == 0xFFFE { return true }
        switch u {
        case 0x0000...0x001F, 0x007F...0x009F,
             0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B...0x180F,
             0x200B...0x200F, 0x202A...0x202E, 0x2060...0x2064, 0x2066...0x2069, 0x206A...0x206F,
             0x2800, 0x3164, 0xFE00...0xFE0F, 0xFEFF, 0xFFA0, 0xFFF0...0xFFFC,
             0x1BCA0...0x1BCA3, 0x1D173...0x1D17A, 0xE0000...0xE0FFF,
             0xE000...0xF8FF, 0xF0000...0xFFFFD, 0x100000...0x10FFFD,
             0xFDD0...0xFDEF:
            return true
        default: return false
        }
    }

}
