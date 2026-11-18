import Foundation
private func check(_ value: Bool, file: StaticString = #filePath, line: UInt = #line) {
    precondition(value, "Receive journal contract failed", file: file, line: line)
}
private enum Fault: Error { case disk, stop }
private final class Memory {
    var values: [String: Data] = [:]
    var failKey: String?
    var deletes = 0
    func journal() -> SignalReceiveJournal {
        SignalReceiveJournal(read: { self.values[$0] }, write: { key, value in
            if key == self.failKey { throw Fault.disk }; self.values[key] = value
        }, remove: { key in self.deletes += 1; if key == self.failKey { throw Fault.disk }; self.values[key] = nil }, keys: { Array(self.values.keys) })
    }
}
private func rejects(_ body: () throws -> Void) {
    do { try body(); preconditionFailure("Expected persistence failure") } catch {}
}
@main struct Main {
    static func main() throws {
        let text = Data("plaintext must survive".utf8), ratchet = Data([2])
        let metadata = Data("durable-wire-context".utf8)
        let duplicate = Memory(), duplicateJournal = duplicate.journal()
        for index in 0..<500 { try duplicateJournal.finish(id: "stored-" + String(index)) }
        check(duplicate.deletes == 0)
        // Failure of the atomic journal leaves every ratchet/prekey untouched.
        let a = Memory(); a.values["session"] = Data([1]); a.values["prekey"] = Data([7])
        let first = a.journal(); a.failKey = "receive_commit.v1"
        rejects { _ = try first.receive(id: "message", metadata: metadata) { try first.set("session", value: ratchet); try first.set("prekey", value: nil); return text } }
        check(a.values["session"] == Data([1]) && a.values["prekey"] == Data([7]))
        check(a.values["receive_commit.v1"] == nil)
        a.failKey = nil
        // The failed atomic write persisted no DTO. A redelivery must supply
        // the same full wire context, as the production ingestor does.
        // Simulate process death/write failure halfway through applying it.
        a.failKey = "session"
        rejects { _ = try first.receive(id: "message", metadata: metadata) { try first.set("session", value: ratchet); try first.set("prekey", value: nil); return text } }
        check(a.values["receive_commit.v1"] != nil)
        a.failKey = nil
        let restarted = a.journal()
        check(try restarted.pendingMetadata() == [metadata])
        // Recovery must precede even a later unrelated encryption operation.
        check(try restarted.data("session") == ratchet)
        check(a.values["prekey"] == nil)
        try restarted.set("session", value: Data([3]))
        // SQLite fails: no finish acknowledgement. Retry returns plaintext and
        // never decrypts again or rolls back the later send ratchet.
        let retry = a.journal()
        var decryptedAgain = false
        let recovered = try retry.receive(id: "message") { decryptedAgain = true; throw Fault.stop }
        check(recovered == text && !decryptedAgain)
        check(try retry.data("session") == Data([3]))
        try retry.finish(id: "message")
        check(a.values["receive_plaintext.v1.message"] == nil)
        let removedOnce = a.deletes
        for _ in 0..<500 { try retry.finish(id: "message") }
        check(a.deletes == removedOnce)
        // Failure after ratchet application, before plaintext-journal promotion.
        let b = Memory(), second = b.journal(); b.failKey = "receive_plaintext.v1.other"
        rejects { _ = try second.receive(id: "other") { try second.set("session", value: ratchet); return text } }
        b.failKey = nil
        let promoted = try b.journal().receive(id: "other") { throw Fault.stop }
        check(promoted == text)
        // A rejected packet stages nothing, but its device warning survives.
        let c = Memory(), rejected = c.journal()
        rejects { _ = try rejected.receive(id: "bad") { try rejected.set("session", value: ratchet); try rejected.set("pending_identity.device", value: Data([9])); throw Fault.stop } }
        check(c.values["session"] == nil && c.values["pending_identity.device"] == Data([9]))
        let deletion = Memory(), beforeClear = deletion.journal()
        let prefix = SignalReceiveJournal.chatPrefix("chat-A")
        _ = try beforeClear.receive(id: prefix + "10.hash") { try beforeClear.set("ratchet", value: Data([9])); return text }
        _ = try beforeClear.receive(id: prefix + "20.hash") { text }
        _ = try beforeClear.receive(id: SignalReceiveJournal.chatPrefix("chat-B") + "1.hash") { text }
        let afterClear = deletion.journal()
        try afterClear.discard(chatID: "chat-A", through: 10)
        check(try afterClear.recovered(id: prefix + "10.hash") == nil)
        check(try afterClear.recovered(id: prefix + "20.hash") == text)
        check(try afterClear.data("ratchet") == Data([9]))
        try afterClear.discard(chatID: "chat-A")
        check(try afterClear.recovered(id: prefix + "20.hash") == nil)
        check(try afterClear.recovered(id: SignalReceiveJournal.chatPrefix("chat-B") + "1.hash") == text)
        let bounded = Memory(), boundedJournal = bounded.journal()
        for index in 0..<SignalReceiveJournal.maximumRetainedMessages { _ = try boundedJournal.receive(id: String(index)) { Data([1]) } }
        var consumedAtLimit = false
        rejects { _ = try boundedJournal.receive(id: "overflow") { consumedAtLimit = true; return text } }
        check(!consumedAtLimit)
        print("Hostile receive journal crash, disk failure, replay and normal acknowledgement contracts passed")
    }
}
