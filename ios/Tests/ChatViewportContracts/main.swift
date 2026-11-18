precondition(ChatViewportPolicy.isNearBottom(lastBottom: 750, viewportHeight: 700))
precondition(!ChatViewportPolicy.isNearBottom(lastBottom: 1100, viewportHeight: 700))
precondition(!ChatViewportPolicy.isNearBottom(lastBottom: -10, viewportHeight: 700))
precondition(ChatViewportPolicy.followsNewItem(nearBottom: false, isOwnMessage: true))
precondition(ChatViewportPolicy.followsNewItem(nearBottom: true, isOwnMessage: false))
precondition(!ChatViewportPolicy.followsNewItem(nearBottom: false, isOwnMessage: false))
print("Chat viewport contracts passed")

precondition(!ChatViewportPolicy.showsJump(hasMessages: true, positioned: true, nearBottom: true, resizing: false, pendingPosition: false),
             "Actual bottom stays hidden independently of any pending-count badge")
precondition(ChatViewportPolicy.showsJump(hasMessages: true, positioned: true, nearBottom: false, resizing: false, pendingPosition: false))
precondition(!ChatViewportPolicy.showsJump(hasMessages: false, positioned: true, nearBottom: false, resizing: false, pendingPosition: false),
             "Empty/deleted-message/call-only timelines do not qualify as messages")
precondition(!ChatViewportPolicy.showsJump(hasMessages: true, positioned: false, nearBottom: false, resizing: false, pendingPosition: false))
precondition(!ChatViewportPolicy.showsJump(hasMessages: true, positioned: true, nearBottom: nil, resizing: false, pendingPosition: false))
precondition(!ChatViewportPolicy.showsJump(hasMessages: true, positioned: true, nearBottom: false, resizing: true, pendingPosition: false))
precondition(!ChatViewportPolicy.showsJump(hasMessages: true, positioned: true, nearBottom: false, resizing: false, pendingPosition: true))
print("Jump visibility: empty/calls/loading, at the end, unknown position, resizing and pending position")

let presentation = ChatPresentationCache<Int, [Int]>()
var builds = 0
let baseline = presentation.value(for: 1) { builds += 1; return Array(0..<10000) }
for _ in 0..<1000 {
    let cached = presentation.value(for: 1) { fatalError("unchanged revision rebuilt timeline") }
    precondition(cached.count == baseline.count)
}
let updated = presentation.value(for: 2) { builds += 1; return [42] }
precondition(builds == 2 && updated == [42], "new history must invalidate the cache")
print("Presentation cache: 1000 unrelated updates avoid rebuilding 10000 rows; revisions invalidate")

var orderedComparisons = 0
let orderedTimeline = ChatTimelineOrdering.sortedIfNeeded(Array(0..<10_000)) { lhs, rhs in
    orderedComparisons += 1
    return lhs < rhs
}
precondition(orderedTimeline == Array(0..<10_000))
precondition(orderedComparisons == 9_999, "ordered history only needs a linear order check")
var unorderedComparisons = 0
let unorderedInput = [4, 1, 3, 2]
let repairedTimeline = ChatTimelineOrdering.sortedIfNeeded(unorderedInput) { lhs, rhs in
    unorderedComparisons += 1
    return lhs < rhs
}
precondition(repairedTimeline == [1, 2, 3, 4])
precondition(unorderedComparisons > 3, "out-of-order history must use the existing full sort")
print("Timeline ordering: ordered 10k history stays linear; reordered rows preserve sorted fallback")

// Equal keys must keep their original order, just like Swift's stable sort.
struct OrderedFixture: Equatable { let key: Int; let identity: Int }
let fixtures = (0..<200).map { OrderedFixture(key: ($0 * 73) % 17, identity: $0) }
let fixtureOrder: (OrderedFixture, OrderedFixture) -> Bool = { $0.key < $1.key }
precondition(ChatTimelineOrdering.sortedIfNeeded(fixtures, by: fixtureOrder) == fixtures.sorted(by: fixtureOrder))
let sortedFixtures = fixtures.sorted(by: fixtureOrder)
precondition(ChatTimelineOrdering.sortedIfNeeded(sortedFixtures, by: fixtureOrder) == sortedFixtures)
precondition(ChatTimelineOrdering.sortedIfNeeded([Int](), by: <).isEmpty)
precondition(ChatTimelineOrdering.sortedIfNeeded([1], by: <) == [1])
print("Timeline ordering: duplicate keys preserve stable ordering; empty and single rows pass")

var arrivals = ChatTailTracker()
_ = arrivals.update(tail: "b", ids: ["a", "b"])
precondition(!arrivals.update(tail: "b", ids: ["older", "a", "b"]))
precondition(!arrivals.update(tail: "older", ids: ["older"]))
precondition(arrivals.update(tail: "new", ids: ["older", "new"]))
precondition(!arrivals.update(tail: "older", ids: ["older"]))
precondition(!arrivals.update(tail: nil, ids: []))
precondition(arrivals.update(tail: "first", ids: ["first"]))
precondition(arrivals.update(tail: "second-album-member", ids: ["first", "second-album-member"]))
print("Tail arrival contracts passed: prepend, deletion, simultaneous replacement, empty history and album append")

// The unread badge counts appended rows, not publish events.
var counted = ChatTailTracker()
precondition(counted.arrivals(tail: "a", ids: ["a"]) == 1)
precondition(counted.arrivals(tail: "a", ids: ["a"]) == 0, "unchanged snapshot is not an arrival")
precondition(counted.arrivals(tail: "d", ids: ["a", "b", "c", "d"]) == 3, "sync catch-up/album counts every new row")
precondition(counted.arrivals(tail: "d", ids: ["older", "a", "b", "c", "d"]) == 0, "prepended history is not an arrival")
precondition(counted.arrivals(tail: "e", ids: ["e"]) == 1, "replacement tail still counts at least one")
print("Tail arrival counts passed: single, batch, prepend and replacement")
