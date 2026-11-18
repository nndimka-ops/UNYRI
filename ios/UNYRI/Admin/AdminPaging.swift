import Foundation

// ---------------------------------------------------------------------------
// ADMIN PRODUCT PACK 2.1 - cursor pagination (iOS).
//
// Pure Foundation logic so the paging contract stays testable without SwiftUI
// or a network. The server owns ordering and the page boundary: the client only
// ever appends the page it was handed and must never derive a boundary itself.
// ---------------------------------------------------------------------------

/// One server page plus what the interface needs to render it.
struct AdminPage<T> {
    var rows: [T] = []
    var nextCursor: String?
    var hasMore: Bool = false
}

/// What a list screen is doing, so every list reports the same states.
enum AdminLoadState: Equatable {
    case idle, loading, loadingMore, ready, failed
}

enum AdminPaging {
    /// Page size for every administration list.
    static let pageSize = 25

    /// Filters are bounded before they go on the wire.
    static let maxFilterLength = 200

    /// Query string built from whitelisted filters. Blank and nil values are
    /// omitted entirely rather than sent as empty parameters, so the server sees
    /// exactly the filters the administrator chose.
    static func query(_ params: [(String, String?)]) -> String {
        params.compactMap { pair -> String? in
            guard let trimmed = trimmedFilter(pair.1) else { return nil }
            return "\(pair.0)=\(encode(trimmed))"
        }.joined(separator: "&")
    }

    /// The request path for one page of `path`.
    static func path(_ path: String, query: String = "", cursor: String? = nil) -> String {
        var parts = ["limit=\(pageSize)"]
        if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { parts.append(query) }
        if let cursor, !cursor.isEmpty { parts.append("cursor=\(encode(cursor))") }
        return path + "?" + parts.joined(separator: "&")
    }

    /// Appends a page, dropping rows that are already present. Ordering is the
    /// server's contract, so a repeated identity can only mean a boundary
    /// overlap - the interface must never show the same row twice.
    static func append<T>(_ current: AdminPage<T>, _ next: AdminPage<T>, id: (T) -> String?) -> AdminPage<T> {
        AdminPage(rows: merge(current.rows, next.rows, id: id), nextCursor: next.nextCursor, hasMore: next.hasMore)
    }

    static func merge<T>(_ current: [T], _ next: [T], id: (T) -> String?) -> [T] {
        var seen = Set(current.compactMap { id($0) })
        var merged = current
        for row in next {
            guard let key = id(row) else { merged.append(row); continue }
            if seen.insert(key).inserted { merged.append(row) }
        }
        return merged
    }

    /// True when a response that has just arrived still belongs to the request
    /// the screen is waiting for. A late answer from a previous filter, account
    /// or panel generation must never be merged into the current list.
    static func isCurrent(expected: Int, actual: Int, expectedFilter: String, currentFilter: String) -> Bool {
        expected == actual && expectedFilter == currentFilter
    }

    static func trimmedFilter(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxFilterLength))
    }

    /// Percent-encodes a value. A filter that looks like SQL is transported as
    /// data, never interpolated, and the server re-validates every parameter.
    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}

/// Client-side guard mirroring the server's internal-note limits.
enum AdminNotePolicy {
    static let max = 2000

    static func valid(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return (1...max).contains(trimmed.count)
    }

    /// The text that would actually be sent: trimmed, never padded.
    static func payload(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Destructive administration actions always require an explicit second step.
enum AdminConfirmations {
    static func forReportStatus(_ status: String) -> Bool { status == "resolved" || status == "dismissed" }
    static func forFeedbackStatus(_ status: String) -> Bool { status != "open" }
    static func forRestore(blocked: Bool) -> Bool { !blocked }
    static func forCleanup(plannedCount: Int) -> Bool { plannedCount > 0 }
}

/// Two-step storage cleanup: nothing can be deleted before a preview exists.
enum AdminCleanupStage: Equatable { case idle, previewing, previewed, executing }

enum AdminCleanupPolicy {
    static func canExecute(stage: AdminCleanupStage, plannedCount: Int, planId: String?) -> Bool {
        stage == .previewed && plannedCount > 0 && !(planId ?? "").isEmpty
    }
}
