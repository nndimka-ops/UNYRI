import Foundation
@main struct Main {
    static func main() throws {
        let old = Data([1]), added = Data([2])
        precondition(!DeviceTrustPolicy.mayUse(trusted: nil, pending: nil, presented: added))
        precondition(!DeviceTrustPolicy.mayUse(trusted: nil, pending: added, presented: added))
        precondition(DeviceTrustPolicy.mayUse(trusted: old, pending: nil, presented: old))
        precondition(!DeviceTrustPolicy.mayUse(trusted: old, pending: added, presented: old))
        precondition(!DeviceTrustPolicy.mayUse(trusted: old, pending: nil, presented: added))
        let new = UnyriVerifiedDevice(id: 2, number: "123", trusted: nil, pending: added, verified: false)
        precondition(UnyriVerificationDecision.canApply(expected: new, current: new, acceptChange: true))
        let replacement = UnyriVerifiedDevice(id: 2, number: "456", trusted: nil, pending: Data([3]), verified: false)
        precondition(!UnyriVerificationDecision.canApply(expected: new, current: replacement, acceptChange: true))
        let keys = ["pending_identity.002.peer", "pending_identity.003.owner"]
        precondition(SignalStorageKeys.pendingIdentityPeers(in: keys, among: ["peer", "owner"]) == ["peer", "owner"])
        // A held device never blocks the admitted known device's own decision.
        let admitted = [old, added].filter { DeviceTrustPolicy.mayUse(trusted: $0 == old ? old : nil, pending: $0 == added ? added : nil, presented: $0) }
        precondition(admitted == [old])
        print("Hostile device admission, own-device warning and stale approval contracts passed")
    }
}
