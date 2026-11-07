import Foundation

enum SearchEntryPoint: Equatable { case search, people, chats }

/// A repeated drawer action is a new intent, even when its destination matches.
struct SearchNavigationRequest: Equatable {
    let id: UUID
    let destination: SearchEntryPoint
    init(_ destination: SearchEntryPoint, id: UUID = UUID()) { self.id = id; self.destination = destination }
}

/// Keep the match visible in a long-message result without cutting a grapheme.
enum SearchPresentation {
    static func snippet(_ text: String, query: String, limit: Int = 160) -> String {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let match = term.isEmpty ? nil : text.range(of: term, options: [.caseInsensitive, .diacriticInsensitive])
        let position = match?.lowerBound ?? text.startIndex
        let start = text.index(position, offsetBy: -36, limitedBy: text.startIndex) ?? text.startIndex
        let end = text.index(start, offsetBy: max(1, limit), limitedBy: text.endIndex) ?? text.endIndex
        return (start > text.startIndex ? "…" : "") + String(text[start..<end]) + (end < text.endIndex ? "…" : "")
    }
}
