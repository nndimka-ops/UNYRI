import Foundation

/// Numeric, explicitly enabled Alpha QA trace. Never records identities,
/// request UUIDs, messages, URLs, keys or authentication material.
enum UnyriSavedRouteQA {
    static func record(_ stage: Int, _ value: Int = 0) {
        #if UNYRI_ALPHA_LAN
        guard ProcessInfo.processInfo.environment["UNYRI_QA_SAVED_ROUTE_TRACE"] == "1" else { return }
        NSLog("UNYRI_SAVED_QA stage=%d value=%d", stage, value)
        #endif
    }
}

/// Owns one asynchronous chat lookup. A cancelled lookup cannot complete a newer one.
struct ChatOpeningGate {
    private var generation = 0
    private var pending: Int?
    var isPending: Bool { pending != nil }

    mutating func begin() -> Int? {
        guard pending == nil else { return nil }
        generation += 1
        pending = generation
        return generation
    }

    mutating func finish(_ ticket: Int) -> Bool {
        guard pending == ticket else { return false }
        pending = nil
        return true
    }

    mutating func cancel() { pending = nil }
}
