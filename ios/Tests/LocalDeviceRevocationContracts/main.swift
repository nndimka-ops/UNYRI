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
@main struct Main {
    static func main() {
        do {
            let disk = MemoryRecord(), security = disk.open(), identity = Data([5, 1, 2, 3])
            try security.observe(user: "owner", device: "device-a", signal: 7, identity: identity)
            try security.revoke(.init(userId: "owner", serverDeviceId: "device-a", signalDeviceId: nil, identityFingerprint: nil))
            let restarted = disk.open()
            check(try restarted.isRevoked(user: "owner", device: "device-a"))
            check(try restarted.isRevoked(user: "owner", device: "relisted-id", signal: 7))
            check(try restarted.isRevoked(user: "owner", device: "relisted-id", signal: 8, identity: identity))
            check(try !restarted.isRevoked(user: "peer", device: "ordinary", signal: 7, identity: Data([9])))
            let replacement = Data([5, 8, 9])
            disk.failWrites = true
            rejects { try restarted.observe(user: "owner", device: "device-a", signal: 8, identity: replacement) }
            disk.failWrites = false
            check(try !disk.open().isRevoked(user: "owner", signal: 8, identity: replacement))
            try restarted.observe(user: "owner", device: "device-a", signal: 8, identity: replacement)
            check(try disk.open().isRevoked(user: "owner", device: "relabelled", signal: 8, identity: replacement))
            check(try disk.open().isRevoked(user: "owner", signal: 7, identity: identity))
            check(try restarted.claimRelistingWarning(["device-a"]))
            check(try !disk.open().claimRelistingWarning(["device-a"]))
            rejects { _ = try disk.open(origin: "https://other.invalid").blockedUsers() }
            rejects { _ = try disk.open(user: "other-owner").pins() }
            check(LocalSecurityDecisions.storageKey(origin: "HTTPS://ONE.INVALID:443/", userID: "OWNER") == LocalSecurityDecisions.storageKey(origin: "https://one.invalid", userID: "owner"))
            disk.failWrites = true
            rejects { try restarted.revoke(.init(userId: "owner", serverDeviceId: "new-device", signalDeviceId: nil, identityFingerprint: nil)) }
            disk.failWrites = false
            check(try !disk.open().isRevoked(user: "owner", device: "new-device"))
            var saturatedJSON = try JSONSerialization.jsonObject(with: disk.bytes!) as! [String: Any]
            let template = (saturatedJSON["revoked"] as! [[String: Any]])[0]
            saturatedJSON["revoked"] = (0..<1024).map { index -> [String: Any] in
                var record = template; record["serverDeviceId"] = "revoked-" + String(index); return record
            }
            disk.bytes = try JSONSerialization.data(withJSONObject: saturatedJSON)
            check(try disk.open().isRevoked(user: "owner", device: "fresh-unrecorded", signal: 126, identity: Data([9])))
            try restarted.erase(); check(try !disk.open().isRevoked(user: "owner", device: "device-a"))
        } catch { preconditionFailure("Revocation fixture failed: \(error)") }
        print("PASS H2: durable UUID/Signal-ID/fingerprint tombstones, warning once, scope and write failure")
    }
}
