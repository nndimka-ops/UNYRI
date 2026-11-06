import Foundation

/// View-owned, main-thread-only queue. Geometry preferences must not publish
/// state changes synchronously inside SwiftUI's layout pass. Keep only the
/// latest work per channel and apply anchors before read visibility.
final class ChatGeometryUpdates {
    private var pending: [Int: () -> Void] = [:]
    private var ticket: UUID?
    private let schedule: (@escaping () -> Void) -> Void

    init(schedule: @escaping (@escaping () -> Void) -> Void = { work in
        DispatchQueue.main.async(execute: work)
    }) { self.schedule = schedule }

    func submit(channel: Int, _ update: @escaping () -> Void) {
        pending[channel] = update
        guard ticket == nil else { return }
        let next = UUID()
        ticket = next
        schedule { [weak self] in
            guard let self, self.ticket == next else { return }
            let work = self.pending.sorted { $0.key < $1.key }
            self.pending.removeAll()
            self.ticket = nil
            for (_, update) in work {
                update()
            }
        }
    }

    func cancel() {
        ticket = nil
        pending.removeAll()
    }
}

enum ChatViewportPolicy {
    /// nil means no coherent layout measurement, never proof of content below.
    static func measuredNearBottom(contentTop: Double, contentHeight: Double, endBottom: Double?,
                                   viewportHeight: Double, endPadding: Double = 8) -> Bool? {
        guard contentTop.isFinite, contentHeight.isFinite, contentHeight > 0,
              viewportHeight.isFinite, viewportHeight > 0, endPadding.isFinite, endPadding >= 0 else { return nil }
        let contentBottom = contentTop + contentHeight
        guard contentBottom.isFinite else { return nil }
        if let endBottom {
            guard endBottom.isFinite, endBottom >= contentTop,
                  abs(endBottom + endPadding - contentBottom) <= 2 else { return nil }
        }
        // Lazy stacks can omit the end sentinel while reading older history.
        // Their measured full content bounds still describe the scroll extent.
        if contentHeight <= viewportHeight + 1 { return true }
        return contentBottom <= viewportHeight + 120
    }

    static func strictTailAcknowledged(actualEndBottom: Double?, viewportHeight: Double,
                                      endPadding: Double = 8) -> Bool {
        guard let actualEndBottom, actualEndBottom.isFinite,
              viewportHeight.isFinite, viewportHeight > 0,
              endPadding.isFinite, endPadding >= 0 else { return false }
        let actualTail = actualEndBottom + endPadding
        return actualTail.isFinite && abs(actualTail - viewportHeight) <= 2
    }

    static func showsJump(hasMessages: Bool, positioned: Bool, nearBottom: Bool?, resizing: Bool,
                          pendingPosition: Bool) -> Bool {
        hasMessages && positioned && !resizing && !pendingPosition && nearBottom == false
    }

    static func isNearBottom(lastBottom: Double, viewportHeight: Double) -> Bool {
        lastBottom > 0 && lastBottom <= viewportHeight + 120
    }
    /// Native size-change anchoring owns container resize on iOS18+.
    /// Keep the older-system fallback without issuing a second scroll command.
    static func followsViewportResize(nativeSizeChanges: Bool, visible: Bool, positioned: Bool,
                                      nearBottom: Bool, legacyFollow: Bool, hasItems: Bool) -> Bool {
        !nativeSizeChanges && visible && positioned && (nearBottom || legacyFollow) && hasItems
    }
    static func followsNewItem(nearBottom: Bool, isOwnMessage: Bool) -> Bool {
        nearBottom || isOwnMessage
    }
}

/// Follow one completed keyboard opening only when the user was at the tail
/// before it started. Safe-area inset changes do not necessarily resize the
/// ScrollView's container, so native size-change anchoring alone is insufficient.
/// No work is issued for interactive dismissal, reversal or a history reader.
struct ChatKeyboardOpeningFollow {
    private var pendingContext: String?

    mutating func willShow(context: String, visible: Bool, positioned: Bool,
                           nearBottom: Bool, hasItems: Bool) {
        pendingContext = visible && positioned && nearBottom && hasItems ? context : nil
    }

    mutating func cancel() { pendingContext = nil }

    mutating func didShow(context: String, visible: Bool, positioned: Bool, hasItems: Bool) -> Bool {
        let follows = pendingContext == context && visible && positioned && hasItems
        pendingContext = nil
        return follows
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
}

/// A visibility change does not replace a still-mounted native scroll view.
/// Cold/recreated readers keep the existing bookmark restoration path.
struct ChatReaderVisibility {
    private var mounted: UUID?
    private var hidden: UUID?

    mutating func appeared() { mounted = UUID() }
    mutating func disappeared() { mounted = nil }

    mutating func visibilityChanged(visible: Bool) -> Bool {
        guard visible else { hidden = mounted; return false }
        defer { hidden = nil }
        if let mounted, hidden == mounted { return false }
        return true
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
