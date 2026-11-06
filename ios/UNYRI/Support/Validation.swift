import Foundation

/// Client-side validation mirroring the backend rules in
/// `server/src/validation.rs`. Returns a readable message, or nil if valid.
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

    static func displayNameError(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.unicodeScalars.count <= 64 else {
            return "Display name must be 1-64 characters."
        }
        return nil
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
    static func display(shortVersion: String?, build: String?) -> String {
        let version = (shortVersion?.isEmpty == false) ? shortVersion! : "0.0.0"
        let buildNumber = (build?.isEmpty == false) ? build! : "0"
        return "Version \(version) (\(buildNumber))"
    }
}

/// Locale-independent human-readable byte formatting (Phase 9.1), matching
/// Android's `FileSizeFormatter`: whole KB, one decimal for MB/GB, trailing
/// ".0" trimmed. Never emits a locale decimal separator.
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
