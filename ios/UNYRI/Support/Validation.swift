import Foundation

/// Client-side validation follows the backend sanitizing/format rules, with
/// an iOS-specific display-name limit. Returns a message, or nil if valid.
enum Validation {
    static func usernameError(_ value: String) -> String? {
        guard (3...32).contains(value.count) else {
            return "Username must be 3-32 characters."
        }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
        guard value.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            return "Username may only contain letters, digits, '_' and '.'."
        }
        return nil
    }

    /// Length and visibility only; the reserved-name check needs the account's
    /// role (see `DisplayNamePolicy.error(for:official:)`).
    static func displayNameError(_ value: String) -> String? {
        DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize(value)) ? nil : DisplayNamePolicy.lengthError
    }

    static func avatarURLError(_ value: String) -> String? {
        if value.count > 2048 {
            return "Avatar URL must be at most 2048 characters."
        }
        if value.lowercased().hasPrefix("data:") {
            return "Avatar URL may not be a data: URI."
        }
        return nil
    }

    static func bioError(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.unicodeScalars.count <= 160 ? nil : "Bio must be at most 160 characters."
    }
}

/// Pure version-string formatting (Phase 6 §20/§21). The Settings About
/// section reads real `Bundle` metadata and formats it here; the helper never
/// hardcodes a version, never leaks a git SHA, and degrades safely when
/// metadata is unexpectedly missing.
enum AppVersion {
    /// Locale-independent text for logs and the feedback payload.
    static func display(shortVersion: String?, build: String?) -> String {
        let (version, buildNumber) = parts(shortVersion: shortVersion, build: build)
        return "Version \(version) (\(buildNumber))"
    }

    /// The same text for the UI, in the selected app language ("Версия 0.4.5 (49)").
    static func localizedDisplay(shortVersion: String?, build: String?) -> String {
        let (version, buildNumber) = parts(shortVersion: shortVersion, build: build)
        return String(format: UnyriL10n.string("Version %@ (%@)"), version, buildNumber)
    }

    private static func parts(shortVersion: String?, build: String?) -> (String, String) {
        let version = (shortVersion?.isEmpty == false) ? shortVersion! : "0.0.0"
        let buildNumber = (build?.isEmpty == false) ? build! : "0"
        return (version, buildNumber)
    }
}

// MARK: - Server version policy (GET /client/config)

extension AppVersion {
    /// The server's `client_config::parse_version` rules exactly: up to four
    /// dotted numbers of 1-6 digits, an optional `-`/`+` suffix of 1-32
    /// `[0-9A-Za-z.-]` that never orders, at most 64 bytes. Padded to 4 parts.
    static func components(_ v: String) -> [Int]? {
        let bytes = Array(v.utf8)
        guard bytes.count <= 64 else { return nil }
        let dash = UInt8(ascii: "-"), plus = UInt8(ascii: "+"), dot = UInt8(ascii: ".")
        let split = bytes.firstIndex { $0 == dash || $0 == plus }
        if let split {
            let suffix = bytes[(split + 1)...]
            let allowed: (UInt8) -> Bool = { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == dot || $0 == dash }
            guard (1...32).contains(suffix.count), suffix.allSatisfy(allowed) else { return nil }
        }
        let parts = bytes[..<(split ?? bytes.endIndex)].split(separator: dot, omittingEmptySubsequences: false)
        guard (1...4).contains(parts.count) else { return nil }
        var values: [Int] = []
        for part in parts {
            guard (1...6).contains(part.count), part.allSatisfy({ (48...57).contains($0) }) else { return nil }
            values.append(part.reduce(0) { $0 * 10 + Int($1 - 48) })
        }
        return values + Array(repeating: 0, count: 4 - values.count)
    }

    /// nil when either side is not a version; callers treat nil as "never block".
    static func compare(_ a: String, _ b: String) -> ComparisonResult? {
        guard let left = components(a), let right = components(b) else { return nil }
        for (l, r) in zip(left, right) where l != r { return l < r ? .orderedAscending : .orderedDescending }
        return .orderedSame
    }

    static let trustedUpdateHosts: Set<String> = ["apps.apple.com", "itunes.apple.com", "testflight.apple.com"]

    /// The publisher's bundled URL is the product pin, never server metadata.
    /// A missing/invalid pin means no button; an unrelated server URL falls
    /// back to that bundled destination. No production ID is guessed here.
    static func trustedUpdateURL(_ raw: String?) -> URL? {
        trustedUpdateURL(raw, publisherURL: Bundle.main.object(forInfoDictionaryKey: "UNYRI_UPDATE_URL") as? String)
    }

    static func trustedUpdateURL(_ raw: String?, publisherURL: String?) -> URL? {
        guard let publisher = updateProduct(publisherURL) else { return nil }
        if let candidate = updateProduct(raw), candidate.product == publisher.product { return candidate.url }
        return publisher.url
    }

    private static func updateProduct(_ raw: String?) -> (product: String, url: URL)? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty, raw.utf8.count <= 512,
              let components = URLComponents(string: raw), components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(), trustedUpdateHosts.contains(host),
              components.user == nil, components.password == nil, components.port == nil,
              components.query == nil, components.fragment == nil,
              let url = components.url else { return nil }
        // Exact path grammar: no percent-encoded separators, dot-segment
        // normalization, trailing paths or query-driven navigation.
        let path = components.percentEncodedPath
        guard !path.contains("%"), !path.contains("\\") else { return nil }
        var parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.first == "" else { return nil }
        parts.removeFirst()
        func asciiLetters(_ value: String) -> Bool {
            !value.isEmpty && value.utf8.allSatisfy { (65...90).contains($0) || (97...122).contains($0) }
        }
        func asciiAlphanumeric(_ value: String) -> Bool {
            !value.isEmpty && value.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) }
        }
        if host == "testflight.apple.com" {
            guard parts.count == 2, parts[0] == "join", parts[1].utf8.count == 8,
                  asciiAlphanumeric(parts[1]) else { return nil }
            return ("testflight:" + parts[1], url)
        }
        if let country = parts.first, country.utf8.count == 2, asciiLetters(country) { parts.removeFirst() }
        guard (2...3).contains(parts.count), parts[0] == "app", let last = parts.last, last.hasPrefix("id") else { return nil }
        if parts.count == 3 {
            guard parts[1].utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 }),
                  !parts[1].isEmpty else { return nil }
        }
        let identifier = String(last.dropFirst(2))
        guard let first = identifier.utf8.first, (49...57).contains(first),
              identifier.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
        return ("appstore:" + identifier, url)
    }
}

// MARK: - Display names

/// Sanitizing and reserved-name tables follow the server. iOS now applies a
/// 32-scalar editing limit independently; reading never clips existing names.
enum DisplayNamePolicy {
    static let maximumScalars = 32
    static let lengthError = "Display name must be 1-32 characters."
    static let reservedError = "This name is reserved for the service. Choose another name."

    /// Brand fragments: a name whose skeleton contains one is reserved.
    static let brand = ["unyri", "юнири", "унири", "юнайри"]
    /// Staff words: reserved only as the whole name ("Support Team" stays free).
    static let staffExact = [
        "support", "techsupport", "customersupport", "helpdesk", "admin", "administrator", "administration",
        "moderator", "official", "staff", "поддержка", "техподдержка", "службаподдержки", "администратор",
        "администрация", "модератор", "официальный", "підтримка", "службапідтримки", "адміністратор",
        "адміністрація",
    ]

    /// One pass keeps only scalars that render something in context, then
    /// stacked marks are capped, whitespace collapses and the result is NFC.
    static func sanitize(_ raw: String) -> String {
        let input = Array(raw.unicodeScalars)
        var kept: [Unicode.Scalar] = []
        kept.reserveCapacity(input.count)
        for (index, scalar) in input.enumerated() {
            let c: Unicode.Scalar = scalar.properties.isWhitespace ? " " : scalar
            let prev = kept.last
            let next = index + 1 < input.count ? input[index + 1] : nil
            let keep: Bool
            switch c.value {
            case 0x200D: // ZWJ: emoji sequences and the scripts that need it
                if let p = prev, let n = next {
                    keep = (emojiLike(p) && emojiLike(n)) || (joiningScript(p) && joiningScript(n))
                } else { keep = false }
            case 0x200C: // ZWNJ
                if let p = prev, let n = next { keep = joiningScript(p) && joiningScript(n) } else { keep = false }
            case 0xE0020...0xE007F: // tags spell a subdivision flag after the black flag only
                keep = prev.map { $0.value == 0x1F3F4 || isTag($0) } ?? false
            case 0xFE0E, 0xFE0F:
                keep = prev.map { $0 != " " && !isVariationSelector($0) } ?? false
            default:
                keep = !removed(c)
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

    /// 1-32 scalars with at least one that is visible on its own.
    static func isValid(_ s: String) -> Bool {
        let scalars = s.unicodeScalars
        return (1...maximumScalars).contains(scalars.count) && scalars.contains {
            $0 != " " && !isMark($0) && $0.value != 0x200C && $0.value != 0x200D && !isVariationSelector($0) && !isTag($0)
        }
    }

    static func profileError(for raw: String, original: String) -> String? {
        // The exact untouched value is sent back for legacy names. Count that value,
        // not a silently shortened/sanitized replacement.
        let name = raw == original ? raw : sanitize(raw)
        if name.unicodeScalars.count > maximumScalars { return lengthError }
        return raw == original ? nil : Validation.displayNameError(raw)
    }

    /// Comparison skeleton: look-alikes, styled letters, digits used as
    /// letters, spacing and punctuation all fold away.
    static func key(_ s: String) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: skeleton(s))
        return String(view)
    }

    static func isReserved(_ name: String) -> Bool {
        let value = skeleton(name)
        guard !value.isEmpty else { return false }
        return brandSkeletons.contains { contains(value, $0) } || staffSkeletons.contains { $0 == value }
    }

    /// True when sanitising does more than collapse and trim whitespace, so the
    /// editor can say that hidden characters will be dropped.
    static func removesHiddenCharacters(_ raw: String) -> Bool {
        var collapsed: [Unicode.Scalar] = []
        for scalar in raw.unicodeScalars {
            let c: Unicode.Scalar = scalar.properties.isWhitespace ? " " : scalar
            if c == " " && (collapsed.last ?? " ") == " " { continue }
            collapsed.append(c)
        }
        while collapsed.last == " " { collapsed.removeLast() }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: collapsed)
        let plain = String(view).precomposedStringWithCanonicalMapping
        return !sanitize(raw).unicodeScalars.elementsEqual(plain.unicodeScalars)
    }

    /// The editor's message key, or nil. Officials may use reserved names.
    static func error(for raw: String, official: Bool) -> String? {
        let name = sanitize(raw)
        if !isValid(name) { return lengthError }
        if !official && isReserved(name) { return reservedError }
        return nil
    }

    // Scalar arrays, not String comparison: Swift compares strings by
    // canonical equivalence, the server compares bytes.
    private static let brandSkeletons = brand.map(skeleton)
    private static let staffSkeletons = staffExact.map(skeleton)

    private static func skeleton(_ s: String) -> [Unicode.Scalar] {
        let folded = s.precomposedStringWithCompatibilityMapping.decomposedStringWithCanonicalMapping
        var out: [Unicode.Scalar] = []
        for c in folded.unicodeScalars where !isMark(c) {
            if let mapped = mapUpper[c] { push(&out, mapped); continue }
            for lower in c.properties.lowercaseMapping.unicodeScalars {
                let mapped = mapLower[lower] ?? lower
                push(&out, mapped == "l" ? "i" : mapped)
            }
        }
        return out
    }

    private static func push(_ out: inout [Unicode.Scalar], _ c: Unicode.Scalar) {
        let c = mapSymbol[c] ?? c
        if c.properties.isAlphabetic { out.append(c) }
    }

    private static func contains(_ haystack: [Unicode.Scalar], _ needle: [Unicode.Scalar]) -> Bool {
        guard !needle.isEmpty else { return true }
        guard needle.count <= haystack.count else { return false }
        for start in 0...(haystack.count - needle.count) where haystack[start..<(start + needle.count)].elementsEqual(needle) {
            return true
        }
        return false
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
    /// ZWNJ, tags and U+FE0E/U+FE0F have their own rules in `sanitize`.
    private static func removed(_ c: Unicode.Scalar) -> Bool {
        let u = c.value
        if (u & 0xFFFE) == 0xFFFE { return true }
        switch u {
        case 0x0000...0x001F, 0x007F...0x009F,
             0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B...0x180F,
             0x200B, 0x200E, 0x200F, 0x202A...0x202E, 0x2060...0x2064, 0x2066...0x2069, 0x206A...0x206F,
             0x2800, 0x3164, 0xFE00...0xFE0D, 0xFEFF, 0xFFA0, 0xFFF9...0xFFFC,
             0x1BCA0...0x1BCA3, 0x1D173...0x1D17A, 0xE0000...0xE001F, 0xE0080...0xE00FF, 0xE0100...0xE01EF,
             0xE000...0xF8FF, 0xF0000...0xFFFFD, 0x100000...0x10FFFD,
             0xFDD0...0xFDEF:
            return true
        default: return false
        }
    }

    private static let mapUpper: [Unicode.Scalar: Unicode.Scalar] = ["И": "n", "Υ": "y", "Η": "h", "Ν": "n", "Μ": "m"]

    private static let mapLower: [Unicode.Scalar: Unicode.Scalar] = [
        // Cyrillic
        "а": "a", "в": "b", "г": "r", "е": "e", "и": "u", "і": "i", "к": "k", "м": "m",
        "н": "h", "о": "o", "п": "n", "р": "p", "с": "c", "т": "t", "у": "y", "х": "x",
        "ц": "u", "ь": "b", "ѕ": "s", "ј": "j", "ԁ": "d", "ԛ": "q", "ԝ": "w", "ү": "y",
        "һ": "h", "ӏ": "i",
        // Greek
        "α": "a", "β": "b", "γ": "y", "ε": "e", "η": "n", "ι": "i", "κ": "k", "μ": "u",
        "ν": "v", "ο": "o", "ρ": "p", "τ": "t", "υ": "u", "χ": "x", "ω": "w",
        // Armenian
        "ս": "u", "ո": "n", "օ": "o", "հ": "h",
        // Latin small capitals and look-alike letters
        "ı": "i", "ȷ": "j", "ɪ": "i", "ʏ": "y", "ɴ": "n", "ʀ": "r", "ᴜ": "u", "ᴀ": "a",
        "ʙ": "b", "ᴄ": "c", "ᴅ": "d", "ᴇ": "e", "ɢ": "g", "ʜ": "h", "ᴊ": "j", "ᴋ": "k",
        "ʟ": "l", "ᴍ": "m", "ᴏ": "o", "ᴘ": "p", "ꜱ": "s", "ᴛ": "t", "ᴠ": "v", "ᴡ": "w",
        "ᴢ": "z", "ł": "l", "ø": "o", "đ": "d", "ħ": "h", "ŧ": "t", "ſ": "s", "ƒ": "f",
    ]

    private static let mapSymbol: [Unicode.Scalar: Unicode.Scalar] = [
        "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b",
        "!": "i", "|": "i", "¡": "i", "@": "a", "$": "s",
    ]
}

/// Locale-independent human-readable byte formatting (Phase 9.1), matching
/// Android's `FileSizeFormatter`: whole KB, one decimal for MB/GB, trailing
/// ".0" trimmed. `format` never emits a locale decimal separator; `localized`
/// is the UI variant.
enum FileSizeFormatter {
    static func format(_ bytes: Int64) -> String {
        if bytes < 0 { return "0 B" }
        let kb: Int64 = 1024
        let mb = kb * 1024
        let gb = mb * 1024
        if bytes >= gb { return oneDecimal(Double(bytes) / Double(gb)) + " GB" }
        if bytes >= mb { return oneDecimal(Double(bytes) / Double(mb)) + " MB" }
        if bytes >= kb { return "\(bytes / kb) KB" }
        return "\(bytes) B"
    }

    private static func oneDecimal(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        if rounded == rounded.rounded() { return String(Int(rounded)) }
        let whole = Int(rounded)
        let tenth = Int((rounded * 10).rounded()) % 10
        return "\(whole).\(tenth)"
    }

    /// UI text in the selected app language: the same 1024-based steps and
    /// rounding as `format`, with the language's decimal separator and the
    /// catalog's unit names ("10,4 МБ"). `format` stays for logs and payloads.
    /// ByteCountFormatter is not used because it only follows the system locale,
    /// not the language chosen inside UNYRI.
    static func localized(_ bytes: Int64) -> String {
        let value = max(bytes, 0)
        let kb: Int64 = 1024
        let mb = kb * 1024
        let gb = mb * 1024
        let number: String
        let unit: String
        if value >= gb { number = localizedNumber(Double(value) / Double(gb), fractionDigits: 1); unit = "GB" }
        else if value >= mb { number = localizedNumber(Double(value) / Double(mb), fractionDigits: 1); unit = "MB" }
        else if value >= kb { number = localizedNumber(Double(value / kb), fractionDigits: 0); unit = "KB" }
        else { number = localizedNumber(Double(value), fractionDigits: 0); unit = "B" }
        // A no-break space keeps the number and its unit on one line.
        return number + "\u{00A0}" + UnyriL10n.string(unit)
    }

    private static func localizedNumber(_ value: Double, fractionDigits: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: UnyriL10n.language)
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = false // values stay below 1024 per unit: "1023 Б", not "1 023 Б"
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = fractionDigits
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }
}

// MARK: - Root navigation policy (Phase 8 §50/§51)

enum RootDestination: Hashable, CaseIterable {
    case chats, calls, rooms, search, settings
}

/// Pure root-navigation policy. `showCalls` only ever controls ROOT TAB
/// visibility + selection fallback — it must never gate call capability.
enum RootNavigation {
    static let defaultShowCalls = true

    static func visibleDestinations(showCalls: Bool, showSearch: Bool = true) -> [RootDestination] {
        RootDestination.allCases.filter { ($0 != .calls || showCalls) && ($0 != .search || showSearch) }
    }

    static func fallbackSelected(_ selected: RootDestination, showCalls: Bool, showSearch: Bool = true) -> RootDestination {
        visibleDestinations(showCalls: showCalls, showSearch: showSearch).contains(selected) ? selected : .chats
    }
}
