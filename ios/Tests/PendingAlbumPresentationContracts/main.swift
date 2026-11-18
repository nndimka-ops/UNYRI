import Foundation

func require(_ condition: @autoclosure () -> Bool, _ label: String) {
    precondition(condition(), label)
}

let ownScope = UnyriPendingAlbumPresentation.Scope(origin: "https://fixture.invalid", owner: "owner", generation: 1, chat: "chat")
func key(_ id: String, _ date: Double, sequence: Int64? = nil, call: Bool = false) -> UnyriPendingAlbumPresentation.SortKey {
    .init(date: Date(timeIntervalSince1970: date), isCall: call, sequence: sequence, id: id)
}
func member(_ id: String, _ date: Double, pending: Bool = true, album: String? = "album",
            sender: String = "owner", chat: String = "chat", eligible: Bool = true) -> UnyriPendingAlbumPresentation.Member {
    .init(id: id, chat: chat, sender: sender, album: album, sort: key(id, date), pending: pending, eligible: eligible)
}
func row(_ values: [UnyriPendingAlbumPresentation.Member]) -> UnyriPendingAlbumPresentation.Row {
    .init(id: values[0].id, sort: values[0].sort, members: values)
}

func boundaryContracts() {
    let ledger = UnyriPendingAlbumPresentation()
    let a = member("A", 1), b = member("B", 2)
    let first = ledger.project(scope: ownScope, rows: [row([a])])
    require(first[0].id != "A", "Pending album gets its stable token at one member")
    let two = ledger.project(scope: ownScope, rows: [row([a, b])])
    require(first[0].id == two[0].id && two[0].members == ["A", "B"], "One-to-two admission must retain identity")
    let changed = ledger.project(scope: ownScope, rows: [row([member("B", 2), member("A", 8, pending: false)])])
    require(changed[0].id == first[0].id && changed[0].members == ["A", "B"], "ACK must not swap known tile order")
    let missing = ledger.project(scope: ownScope, rows: [row([member("B", 9, pending: false)])])
    require(missing[0].id == first[0].id && missing[0].members == ["B"], "Missing anchor cannot resurrect, but its opaque token remains")
    let restored = ledger.project(scope: ownScope, rows: [row([member("B", 9, pending: false), member("A", 10, pending: false)])])
    require(restored[0].members == ["B", "A"], "Restored unknown member uses first current canonical key, not removed metadata")
    _ = ledger.project(scope: ownScope, rows: [])
    let reopened = ledger.project(scope: ownScope, rows: [row([member("B", 9, pending: false), member("A", 10, pending: false)])])
    require(reopened[0].id == "B", "A pruned fully confirmed group must return to canonical history")

    let pagination = UnyriPendingAlbumPresentation()
    let initial = pagination.project(scope: ownScope, rows: [row([member("B", 2), member("C", 3)])])
    let paged = pagination.project(scope: ownScope, rows: [row([member("X", 0, pending: false), member("C", 30, pending: false), member("B", 20, pending: false), member("D", 40, pending: false)])])
    require(paged[0].id == initial[0].id && paged[0].members == ["X", "B", "C", "D"], "New page IDs use canonical insertion while known relative order survives ACK changes")

    let incoming = UnyriPendingAlbumPresentation()
    require(incoming.project(scope: ownScope, rows: [row([member("foreign", 1, sender: "peer")])])[0].id == "foreign", "Incoming pending rows remain canonical")
    require(incoming.project(scope: ownScope, rows: [row([member("done", 1, pending: false)])])[0].id == "done", "New confirmed history remains canonical")
    require(incoming.project(scope: ownScope, rows: [row([member("plain", 1, album: nil)])])[0].id == "plain", "Nonalbum messages remain canonical")

    let boundaries = UnyriPendingAlbumPresentation()
    let isolated = boundaries.project(scope: ownScope, rows: [
        row([member("own", 1)]), row([member("peer", 2, sender: "peer")]),
        row([member("other-chat", 3, chat: "other")]), row([member("other-album", 4, album: "other")])])
    require(Set(isolated.map(\.id)).count == 4 && isolated.flatMap(\.members) == ["own", "peer", "other-chat", "other-album"], "Chat/sender/album identity boundaries must stay separate")
    let broken = boundaries.project(scope: ownScope, rows: [row([a]), row([member("deleted", 1.5, eligible: false)]), row([b])])
    require(broken.map(\.id) == ["A", "deleted", "B"], "Tombstone/decryption/repeated-key breaks use canonical grouping")
    let emptyAndLong = boundaries.project(scope: ownScope, rows: [row([member("empty", 1, album: "")]), row([member("long", 2, album: String(repeating: "a", count: 129))])])
    require(emptyAndLong.map(\.id) == ["empty", "long"], "Malformed album IDs must not get shared tokens")

    let oversized = (0..<21).map { member("m\($0)", Double($0)) }
    let chunks = [row(Array(oversized[0..<10])), row(Array(oversized[10..<20])), row(Array(oversized[20..<21]))]
    let large = boundaries.project(scope: ownScope, rows: chunks)
    require(large.map(\.id) == ["m0", "m10", "m20"] && Set(large.map(\.id)).count == 3, "Twenty-one members retain unique canonical chunk identities")
    require(large.map { $0.members.count } == [10, 10, 1] && Set(large.flatMap(\.members)).count == 21, "Fallback must retain every distinct member once with the existing ten limit")

    for changedScope in [
        UnyriPendingAlbumPresentation.Scope(origin: "https://other.invalid", owner: "owner", generation: 1, chat: "chat"),
        .init(origin: ownScope.origin, owner: "other", generation: 1, chat: "chat"),
        .init(origin: ownScope.origin, owner: "owner", generation: 2, chat: "chat"),
        .init(origin: ownScope.origin, owner: "owner", generation: 1, chat: "other")
    ] {
        let scoped = UnyriPendingAlbumPresentation()
        _ = scoped.project(scope: ownScope, rows: [row([a, b])])
        let next = scoped.project(scope: changedScope, rows: [row([member("B", 2, pending: false), member("A", 8, pending: false)])])
        require(next[0].id == "B" && next[0].members == ["B", "A"], "Origin/owner/generation/chat replacement must retire previous metadata")
    }

    let anchored = UnyriPendingAlbumPresentation()
    let text = row([member("text", 3, pending: false, album: nil)])
    _ = anchored.project(scope: ownScope, rows: [row([a, b]), text])
    let moved = anchored.project(scope: ownScope, rows: [text, row([member("A", 6, pending: false), member("B", 8, pending: false)])])
    require(moved.map(\.members) == [["A", "B"], ["text"]], "Own pending display anchor survives both ACKs around an interleaved text")
    let canonicalAgain = UnyriPendingAlbumPresentation().project(scope: ownScope, rows: [text, row([member("A", 6, pending: false), member("B", 8, pending: false)])])
    require(canonicalAgain.map(\.members) == [["text"], ["A", "B"]], "View reopen intentionally uses canonical confirmed history")
    let ordinaryOrder = UnyriPendingAlbumPresentation().project(scope: ownScope, rows: [
        row([member("z", 4, pending: false, album: nil)]), row([member("a", 1, pending: false, album: nil)])])
    require(ordinaryOrder.map(\.id) == ["z", "a"], "The helper cannot independently sort ordinary input rows")
    print("PASS: metadata-only album boundaries, admission, ACK order, missing/restore/paging, scope retirement, canonical fallback and anchor")
}

// The runner inserts actual production model metadata, comparator, grouping,
// reconciliation and ChatScreen integration below. Transport adapters are not
// renderers or replacements for those production algorithms.
// ACTUAL_PRODUCTION_PIPELINE

@main struct PendingAlbumChecks {
    static func main() {
        if CommandLine.arguments.contains("boundaries") { boundaryContracts(); return }
        actualIntegrationContracts()
    }
}
