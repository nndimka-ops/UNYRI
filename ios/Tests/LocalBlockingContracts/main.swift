import Foundation
func check(_ condition: @autoclosure () throws -> Bool) {
    do { let value = try condition(); precondition(value) } catch { preconditionFailure("Unexpected fixture error: \(error)") }
}
func rejects(_ operation: () throws -> Void) {
    do { try operation(); preconditionFailure("Expected rejection") } catch {}
}
final class MemoryRecord {
    var bytes: Data?; var failWrites = false; var readbackFails = false
    func open(origin: String = "https://one.invalid", user: String = "owner") -> LocalSecurityDecisions {
        LocalSecurityDecisions(origin: origin, userID: user, read: { self.readbackFails ? nil : self.bytes },
            write: { if self.failWrites { throw LocalSecurityDecisions.Failure.persistence }; self.bytes = $0 }, remove: { self.bytes = nil })
    }
}
struct MessageDto { let chatId: String; let clientMessageId: String; let senderId: String; let senderDeviceId: String }
@MainActor final class AuthProbe { let localSecurity: LocalSecurityDecisions?; init(_ value: LocalSecurityDecisions) { localSecurity = value } }
@MainActor final class StoreProbe {
    var values: [String: String] = [:]; var fail = false; var deletedPending = [String]()
    func chatValue(chatId: String, name: String) throws -> String? { values[chatId + name] }
    func saveChatValue(chatId: String, name: String, value: String) throws { if fail { throw LocalSecurityDecisions.Failure.persistence }; values[chatId + name] = value }
    func deletePendingDecrypt(clientMessageId: String) throws { deletedPending.append(clientMessageId) }
}
@MainActor final class SignalProbe { var finished = [String](); func finishReceive(id: String) async throws { finished.append(id) } }
@main struct Main {
    @MainActor static func main() async {
        do {
            let disk = MemoryRecord(), security = disk.open(), subject = IngressSubject(security)
            let dto = MessageDto(chatId: "chat", clientMessageId: "blocked-id", senderId: "peer", senderDeviceId: "device")
            let admitted = try await subject.excludeIncoming(dto, journalID: "j"); check(!admitted)
            try security.setBlocked("peer", blocked: true)
            let hidden = try await subject.excludeIncoming(dto, journalID: "j"); check(hidden)
            check(subject.store.values["chatsecurity_excluded:blocked-id"] == "1" && subject.sessionManager.finished == ["j"])
            check(try disk.open().blockDecision("peer") == true)
            try security.setBlocked("peer", blocked: false)
            let replay = try await subject.excludeIncoming(dto, journalID: "j"); check(replay)
            let fresh = MessageDto(chatId: "chat", clientMessageId: "fresh-id", senderId: "peer", senderDeviceId: "device")
            let unblocked = try await subject.excludeIncoming(fresh, journalID: "fresh-j"); check(!unblocked)
            try security.setBlocked("peer", blocked: true); subject.store.fail = true
            do { _ = try await subject.excludeIncoming(fresh, journalID: "fresh-j"); preconditionFailure("Failed exclusion write accepted") } catch {}
            check(!subject.sessionManager.finished.contains("fresh-j"))
            check(try disk.open().blockDecision("peer") == true)
        } catch { preconditionFailure("Blocking fixture failed: \(error)") }
        print("PASS H5: actual pre-decrypt/post-await exclusion, durable local actions, replay stays hidden, write failure")
    }
}
