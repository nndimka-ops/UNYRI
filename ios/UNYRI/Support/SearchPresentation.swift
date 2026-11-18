import Foundation

enum SearchEntryPoint: Equatable { case search, contacts, chats }

/// A repeated drawer action is a new intent, even when its destination matches.
struct SearchNavigationRequest: Equatable {
    let id: UUID
    let destination: SearchEntryPoint
    init(_ destination: SearchEntryPoint, id: UUID = UUID()) { self.id = id; self.destination = destination }
}

/// Keep the match visible in a long-message result without cutting a grapheme.
enum SearchPresentation {
    static let debounceNanoseconds: UInt64 = 300_000_000

    static func query(_ source: String) -> String {
        let value = String(source.prefix(256)).trimmingCharacters(in: .whitespacesAndNewlines)
        return value.hasPrefix("@") ? String(value.dropFirst()) : value
    }

    static func debounce() async throws {
        try await Task.sleep(nanoseconds: debounceNanoseconds)
        try Task.checkCancellation()
    }

    static func canSearchDirectory(_ source: String, connected: Bool) -> Bool {
        connected && query(source).count >= 3
    }

    static func sections(localCount: Int, messageCount: Int, query source: String) -> [SearchSection] {
        guard !query(source).isEmpty else { return [] }
        var result: [SearchSection] = []
        if localCount > 0 { result.append(.people) }
        if query(source).count >= 3 { result.append(.global) }
        if messageCount > 0 { result.append(.messages) }
        return result
    }

    /// Stable ranking, computed once per debounced snapshot, never in a row body.
    static func ranked(_ entries: [SearchCandidate], query source: String) -> [String] {
        let term = fold(query(source))
        guard !term.isEmpty else { return [] }
        return entries.compactMap { entry -> (String, Int)? in
            let keys = [fold(entry.title), fold(entry.username)]
            let score = keys.map { key in
                key == term ? 0 : key.hasPrefix(term) ? 1 : key.contains(term) ? 2 : 3
            }.min() ?? 3
            return score < 3 ? (entry.id, score) : nil
        }.sorted { $0.1 == $1.1 ? $0.0 < $1.0 : $0.1 < $1.1 }.map { $0.0 }
    }

    private static func fold(_ source: String) -> String {
        source.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }

    static func categories(mediaType: String?, mimeType: String?, text: String) -> Set<SearchCategory> {
        var result: Set<SearchCategory> = []
        let mime = mimeType?.lowercased() ?? ""
        if mediaType == "voice" { result.insert(.voice) }
        else if mediaType == "image" || mime.hasPrefix("image/") || mime.hasPrefix("video/") { result.insert(.media) }
        else if mediaType != nil { result.insert(.files) }
        if text.range(of: #"(?i)\b(?:https?://|www\.)\S+"#, options: .regularExpression) != nil { result.insert(.links) }
        return result
    }

    static func tabs(categories: [Set<SearchCategory>], hasPeople: Bool) -> [SearchCategory] {
        let available = categories.reduce(into: Set<SearchCategory>()) { $0.formUnion($1) }
        guard hasPeople || !categories.isEmpty else { return [] }
        return [.chats] + SearchCategory.allCases.filter { $0 != .chats && available.contains($0) }
    }

    static func snippet(_ text: String, query: String, limit: Int = 160) -> String {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let match = term.isEmpty ? nil : text.range(of: term, options: [.caseInsensitive, .diacriticInsensitive])
        let position = match?.lowerBound ?? text.startIndex
        let start = text.index(position, offsetBy: -36, limitedBy: text.startIndex) ?? text.startIndex
        let end = text.index(start, offsetBy: max(1, limit), limitedBy: text.endIndex) ?? text.endIndex
        return (start > text.startIndex ? "…" : "") + String(text[start..<end]) + (end < text.endIndex ? "…" : "")
    }
}

struct SearchCandidate: Sendable {
    let id: String
    let title: String
    let username: String
}

enum SearchCategory: String, CaseIterable, Sendable {
    case chats, media, links, files, voice
    var title: String {
        switch self {
        case .chats: return "Chats"
        case .media: return "Media"
        case .links: return "Links"
        case .files: return "Files"
        case .voice: return "Search voice"
        }
    }
}

enum SearchSection: Equatable { case people, global, messages }
