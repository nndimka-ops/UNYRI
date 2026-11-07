import Foundation
func snapshot(id: UInt32 = 1, number: String = "123", trusted: Data? = Data([1]), pending: Data? = nil, verified: Bool = false) -> UnyriVerifiedDevice {
    UnyriVerifiedDevice(id: id, number: number, trusted: trusted, pending: pending, verified: verified)
}
func check(_ condition: Bool, _ label: String) { if !condition { fatalError(label) } }
let initial = snapshot()
let pending = "pending_identity." + SignalStorageKeys.encodeAddress(name: "peer", deviceId: 1)
check(SignalStorageKeys.hasPendingIdentity(in: [pending], peer: "peer"), "pending peer shows review action")
check(!SignalStorageKeys.hasPendingIdentity(in: [pending], peer: "other-peer"), "another peer does not inherit warning")
check(!SignalStorageKeys.hasPendingIdentity(in: [SignalStorageKeys.trustedIdentity(name: "peer", deviceId: 1)], peer: "peer"), "trusted identity alone does not warn")
check(!SignalStorageKeys.hasPendingIdentity(in: [], peer: "peer"), "accepted pending identity clears warning")
check(!SignalStorageKeys.hasPendingIdentity(in: ["pending_identity.bad", "pending_identity.000.peer", "pending_identity.128.peer"], peer: "peer"), "malformed or unsupported device keys do not create unusable action")
check(UnyriVerificationDecision.canApply(expected: initial, current: initial, acceptChange: false), "unchanged comparison can be verified")
check(!UnyriVerificationDecision.canApply(expected: initial, current: nil, acceptChange: false), "removed key rejected")
check(!UnyriVerificationDecision.canApply(expected: initial, current: snapshot(number: "456"), acceptChange: false), "local identity rotation invalidates comparison")
check(!UnyriVerificationDecision.canApply(expected: initial, current: snapshot(trusted: Data([2])), acceptChange: false), "peer key replacement invalidates comparison")
check(!UnyriVerificationDecision.canApply(expected: initial, current: snapshot(id: 2), acceptChange: false), "different device rejected")
let changed = snapshot(number: "456", pending: Data([2]))
check(!UnyriVerificationDecision.canApply(expected: changed, current: changed, acceptChange: false), "changed key cannot be marked verified")
check(UnyriVerificationDecision.canApply(expected: changed, current: changed, acceptChange: true), "explicit acceptance permitted")
check(!UnyriVerificationDecision.canApply(expected: changed, current: snapshot(number: "789", pending: Data([3])), acceptChange: true), "new change during dialog rejected")
check(!UnyriVerificationDecision.canApply(expected: initial, current: initial, acceptChange: true), "no silent session reset for unchanged key")
print("Identity verification: 14 contracts passed")

let origin = "http://192.168.0.106:8080"
let account = "user:installation"
let prefix = "server." + Data(origin.utf8).base64EncodedString() + "."
for initialPrefix in ["", prefix] {
    var records = [String: Data]()
    records[initialPrefix + account + ":identity.key_pair"] = Data([1,2,3])
    let first = try SignalNamespaceBinding.resolve(origin: origin, owner: account, legacyPrefix: initialPrefix,
        read: { records[$0] }, write: { records[$0] = $1 })
    let second = try SignalNamespaceBinding.resolve(origin: origin, owner: account, legacyPrefix: initialPrefix.isEmpty ? prefix : "",
        read: { records[$0] }, write: { records[$0] = $1 })
    check(first == second && first == initialPrefix + account, "update and relogin keep exact namespace")
    check(records[first + ":identity.key_pair"] == Data([1,2,3]), "private material retained")
    let other = try SignalNamespaceBinding.resolve(origin: "https://other.invalid", owner: account,
        legacyPrefix: "", read: { records[$0] }, write: { records[$0] = $1 })
    check(other != first, "another server remains isolated")
}
do {
    _ = try SignalNamespaceBinding.resolve(origin: origin, owner: account, legacyPrefix: "",
        read: { _ in nil }, write: { _,_ in })
    fatalError("failed persistence must stop bootstrap")
} catch SignalNamespaceBinding.Failure.persistence { }
print("Signal namespace persistence: 7 contracts passed")
