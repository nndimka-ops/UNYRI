import Foundation

@main struct NotificationBatchAlertContracts {
    static func main() {
        var checks = 0
        func check(_ value: Bool, _ message: String) { precondition(value, message); checks += 1 }
        let scope = UnyriNotificationBatchScope(server: "https://server.example", owner: "owner", generation: 7)
        var ledger = UnyriNotificationBatchLedger()
        for index in 0..<10 {
            check(ledger.claim(message: "photo-\(index)", chat: "chat", sender: "peer", album: "album", in: scope) == (index == 0),
                  "Exactly one alert for ten known members of one encrypted batch")
        }
        check(!ledger.claim(message: "photo-0", chat: "chat", sender: "peer", album: "album", in: scope), "Duplicate event is silent")
        for index in 0..<5 {
            check(ledger.claim(message: "independent-\(index)", chat: "chat", sender: "peer", album: nil, in: scope),
                  "Independent messages retain their alerts without a clock debounce")
        }
        check(ledger.claim(message: "new-batch", chat: "chat", sender: "peer", album: "album-2", in: scope), "A new batch has its own first alert")
        check(ledger.claim(message: "other-chat", chat: "other-chat", sender: "peer", album: "album", in: scope), "Chat fence")
        check(ledger.claim(message: "other-sender", chat: "chat", sender: "other-peer", album: "album", in: scope), "Authenticated sender fence")
        for (index, album) in [nil, "", "  ", String(repeating: "a", count: 257)].enumerated() {
            check(ledger.claim(message: "no-valid-batch-\(index)", chat: "chat", sender: "peer", album: album, in: scope),
                  "Missing, blank or oversized metadata must not merge independent events")
        }
        for changed in [UnyriNotificationBatchScope(server: "https://other.example", owner: scope.owner, generation: scope.generation),
                        UnyriNotificationBatchScope(server: scope.server, owner: "other-owner", generation: scope.generation),
                        UnyriNotificationBatchScope(server: scope.server, owner: scope.owner, generation: 8)] {
            var fenced = UnyriNotificationBatchLedger()
            check(fenced.claim(message: "first", chat: "chat", sender: "peer", album: "album", in: scope), "Initial session alert")
            check(fenced.claim(message: "first", chat: "chat", sender: "peer", album: "album", in: changed), "Owner, origin and login-generation fences")
        }
        var bounded = UnyriNotificationBatchLedger(capacity: 2)
        for value in ["a", "b", "c"] {
            check(bounded.claim(message: value, chat: "chat", sender: "peer", album: value, in: scope), "Bounded independent insertion")
        }
        check(!bounded.claim(message: "c-next", chat: "chat", sender: "peer", album: "c", in: scope), "Recent batch stays silent")
        check(bounded.claim(message: "a-next", chat: "chat", sender: "peer", album: "a", in: scope), "Old state is bounded, not an unbounded account history")
        print("PASS: \(checks) actual Foundation notification batch policy checks; no clock guessing, network or physical sound claim")
    }
}
