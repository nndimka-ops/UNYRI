import Foundation

@main private struct PushCallBookContracts {
    @MainActor static func main() {
        var count = 0
        func check(_ value: Bool, _ name: String) { count += 1; precondition(value, name); print("PASS " + name) }
        let scope = UnyriPushScope(origin: "https://one.example", user: UUID(), device: UUID())
        let session = UnyriPushSession(scope: scope, generation: 10)
        func key(_ scope: UnyriPushScope, call: UUID = UUID()) -> UnyriPushCallBook.Key {
            UnyriPushCallBook.Key(scope: scope, registration: UUID(), revision: 1, call: call)
        }
        var book = UnyriPushCallBook()
        let rejected = key(scope); let ticket = book.begin(rejected)!
        check(book.end(ticket, reason: .declined, rejected: true) == nil, "Reject before report completion defers platform End")
        check(!book.adopt(ticket, session: session), "Reject before auth blocks delayed ringing adoption")
        check(book.reportFinished(ticket, success: true) == .declined, "Late report success ends exactly rejected stub")
        book.release(ticket)
        check(book.ticket(rejected.call) == ticket, "Rejected stub retains one remote-reject intent through report completion")
        check(book.takeReject(ticket, session: session), "Authenticated exact owner takes backend Reject once")
        check(!book.takeReject(ticket, session: session), "Duplicate recovery cannot repeat Reject")
        book.release(ticket)
        check(book.ticket(rejected.call) == nil && book.terminal(rejected), "Release retains bounded terminal fence, not active report")
        check(book.begin(rejected) == nil && !book.allowsIncoming(rejected.call, session: session), "Duplicate push or WS cannot re-ring rejected call")

        let ended = key(scope); let ending = book.begin(ended)!
        check(book.end(ending, reason: .remoteEnded) == nil, "Remote end before report completion is deferred")
        check(book.reportFinished(ending, success: true) == .remoteEnded, "Late success emits one exact remote End")
        check(book.reportFinished(ending, success: true) == nil && book.end(ending, reason: .failed) == nil, "Duplicate completion and terminal cannot emit second End")
        book.release(ending)
        check(!book.adopt(ending, session: session), "Terminal-before-report cannot recreate incoming state")

        let active = key(scope); let owned = book.begin(active)!
        let foreign = UnyriPushSession(scope: UnyriPushScope(origin: "https://other.example", user: scope.user, device: scope.device), generation: 10)
        check(!book.adopt(owned, session: foreign), "Different origin cannot adopt pending generic report")
        let otherUser = UnyriPushSession(scope: UnyriPushScope(origin: scope.origin, user: UUID(), device: scope.device), generation: 10)
        check(!book.adopt(owned, session: otherUser), "Different account cannot adopt pending generic report")
        check(book.adopt(owned, session: session), "Authoritative exact account may adopt")
        check(!book.adopt(owned, session: UnyriPushSession(scope: scope, generation: 11)), "Adopted generation cannot be replaced by successor")
        let wrongTicket = UnyriPushCallBook.Ticket(key: active, nonce: UUID())
        check(book.end(wrongTicket, reason: .failed) == nil && book.admitted(owned, session: session), "Wrong late ticket cannot end active replacement owner")
        _ = book.reportFinished(owned, success: true)
        let detached = book.retire(scope)
        check(detached.count == 1 && detached[0].0 == owned && detached[0].1 == .failed, "Logout terminalizes only matching report scope")
        let successorKey = key(foreign.scope); let successor = book.begin(successorKey)!
        check(book.adopt(successor, session: foreign), "Successor has its own report membership")
        check(book.reportFinished(owned, success: true) == nil && book.admitted(successor, session: foreign), "Late old report cannot ring or end successor")
        book.release(owned)

        let failedKey = key(scope); let failed = book.begin(failedKey)!
        check(book.reportFinished(failed, success: false) == nil && !book.adopt(failed, session: session), "Failed platform report is terminal and cannot start media")
        book.release(failed)
        var completions = 0; let once = UnyriPushCompletion { completions += 1 }
        once.finish(); once.finish()
        check(completions == 1 && once.finished, "Timeout and late platform callback complete PushKit once")

        var bounded = UnyriPushCallBook(); var first: UnyriPushCallBook.Key?
        for _ in 0..<129 {
            let k = key(scope); if first == nil { first = k }
            let t = bounded.begin(k)!; _ = bounded.reportFinished(t, success: true); _ = bounded.end(t, reason: .failed); bounded.release(t)
        }
        check(!bounded.terminal(first!), "Terminal report history remains bounded to128")
        var local = UnyriPushCallBook()
        let localKey = UnyriPushCallBook.Key(scope: scope, registration: UUID(), revision: 1, call: UUID())
        let localTicket = local.begin(localKey)!
        local.locallyEnded(localTicket)
        check(local.terminal(localKey) && !local.admitted(localTicket, session: session),
              "fulfilled local End synchronously tombstones pending incoming report")
        check(local.reportFinished(localTicket, success: true) == nil,
              "late platform report after fulfilled End does not emit duplicate End")
        local.release(localTicket)
        check(!local.hasReports && !local.allowsIncoming(localKey.call, session: session),
              "local End releases live report and retains duplicate-ring fence")
        for revision in 2...13 {
            let nextKey = UnyriPushCallBook.Key(scope: scope, registration: UUID(), revision: Int64(revision), call: UUID())
            let next = local.begin(nextKey)!
            _ = local.reportFinished(next, success: true); local.locallyEnded(next)
            check(!local.hasReports && local.terminal(nextKey),
                  "repeated fulfilled local End releases exactly its member beyond admission cap")
        }
        print("PUSH_CALL_BOOK \(count) PASS")
    }
}
