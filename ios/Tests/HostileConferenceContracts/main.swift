import Foundation
@main struct Main {
    static func main() {
        let device = SignalDeviceTrustSnapshot(userID: "peer", deviceID: "backend-a", signalID: 1, identity: Data([1]))
        var policy = ConferenceKeyReleasePolicy()
        precondition(!policy.mayRelease(binding: "room-a/request-1", current: device, trusted: true))
        policy.approve(binding: "room-a/request-1", device: device)
        precondition(policy.mayRelease(binding: "room-a/request-1", current: device, trusted: true))
        precondition(!policy.mayRelease(binding: "room-b/request-1", current: device, trusted: true))
        precondition(!policy.mayRelease(binding: "room-a/request-2", current: device, trusted: true))
        precondition(!policy.mayRelease(binding: "room-a/request-1", current: device, trusted: false))
        for other in [SignalDeviceTrustSnapshot(userID: "peer", deviceID: "backend-b", signalID: 1, identity: Data([1])),
                      SignalDeviceTrustSnapshot(userID: "peer", deviceID: "backend-a", signalID: 1, identity: Data([2]))] {
            precondition(!policy.mayRelease(binding: "room-a/request-1", current: other, trusted: true))
        }
        policy.retain([])
        precondition(!policy.mayRelease(binding: "room-a/request-1", current: device, trusted: true))
        print("Hostile conference exact-request and device identity approval contracts passed")
    }
}
