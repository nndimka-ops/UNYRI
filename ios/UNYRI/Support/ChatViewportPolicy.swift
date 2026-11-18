import Foundation

enum ChatViewportPolicy {
    static func showsJump(hasMessages: Bool, positioned: Bool, nearBottom: Bool?, resizing: Bool,
                          pendingPosition: Bool) -> Bool {
        hasMessages && positioned && !resizing && !pendingPosition && nearBottom == false
    }

    static func isNearBottom(lastBottom: Double, viewportHeight: Double) -> Bool {
        lastBottom > 0 && lastBottom <= viewportHeight + 120
    }
    static func followsNewItem(nearBottom: Bool, isOwnMessage: Bool) -> Bool {
        nearBottom || isOwnMessage
    }
}

/// Messages normally arrive from the local store in timeline order.  Avoid an
/// unnecessary O(n log n) sort on the main actor for that common path, while
/// retaining the exact sort as a safe fallback for a locally pending or
/// otherwise reordered row.
enum ChatTimelineOrdering {
    static func sortedIfNeeded<T>(_ values: [T], by areInIncreasingOrder: (T, T) -> Bool) -> [T] {
        guard values.count > 1 else { return values }
        var previous = values[0]
        for next in values.dropFirst() {
            if areInIncreasingOrder(next, previous) {
                return values.sorted(by: areInIncreasingOrder)
            }
            previous = next
        }
        return values
    }
}

/// One-entry, view-owned memoization. Revision keys avoid walking message arrays
/// for each keyboard, playback or scroll update. Never shared between accounts.
final class ChatPresentationCache<Key: Equatable, Value> {
    private var key: Key?
    private var cached: Value?
    func value(for next: Key, build: () -> Value) -> Value {
        if key == next, let cached { return cached }
        let result = build()
        key = next; cached = result
        return result
    }
}

/// Snapshot is replaced, not accumulated, and belongs to one chat screen.
struct ChatTailTracker {
    private var tail: String?
    private var known = Set<String>()
    mutating func update(tail next: String?, ids: Set<String>) -> Bool {
        let arrived = next != tail && next.map { !known.contains($0) } == true
        tail = next
        known = ids
        return arrived
    }
    /// Same arrival rule as `update`, but reports how many rows were appended in
    /// this snapshot (at least 1 when the tail advanced, 0 otherwise).
    mutating func arrivals(tail next: String?, ids: Set<String>) -> Int {
        let fresh = ids.subtracting(known).count
        return update(tail: next, ids: ids) ? max(1, fresh) : 0
    }
}

/// An in-memory reading position is valid only for the same measured row layout.
struct ChatReaderBookmarkOffset: Equatable {
    let originY: Double
    let rowHeight: Double
    let viewportWidth: Double
    func origin(rowHeight currentHeight: Double, viewportWidth currentWidth: Double) -> Double? {
        guard originY.isFinite, rowHeight.isFinite, rowHeight > 0,
              viewportWidth.isFinite, viewportWidth > 0,
              currentHeight.isFinite, currentWidth.isFinite,
              abs(rowHeight - currentHeight) <= 1, abs(viewportWidth - currentWidth) <= 1,
              originY + rowHeight > 1 else { return nil }
        return originY
    }
}
