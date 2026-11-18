import Foundation

/// An exact request is approved locally. Neither a server membership row nor a
/// familiar account name grants room authority. Approval also pins device bytes.
struct ConferenceKeyReleasePolicy {
    private var approvals: [String: SignalDeviceTrustSnapshot] = [:]
    mutating func approve(binding: String, device: SignalDeviceTrustSnapshot) { approvals[binding] = device }
    func device(for binding: String) -> SignalDeviceTrustSnapshot? { approvals[binding] }
    func mayRelease(binding: String, current: SignalDeviceTrustSnapshot?, trusted: Bool) -> Bool {
        guard trusted, let current else { return false }
        return approvals[binding] == current
    }
    mutating func retain(_ bindings: Set<String>) { approvals = approvals.filter { bindings.contains($0.key) } }
}
