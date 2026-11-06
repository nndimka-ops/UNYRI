import Foundation

/// Account-scoped cache of backend device UUID -> Signal device id.
///
/// Stores ONLY the small-int mapping (user/device UUID strings and an Int32) —
/// no keys, ciphertext, sessions, or any other cryptographic material. The
/// owning `SignalSessionManager` is created per account inside
/// `AccountServices`, so this cache is destroyed on logout/account switch and
/// can never leak a mapping across accounts.
final class SignalDeviceIdCache {
    private var mapping: [String: Int32] = [:]

    /// Composite key scoped by BOTH remote user and backend device UUID, so a
    /// device id can never be resolved against the wrong user's mapping.
    static func key(userId: String, deviceId: String) -> String {
        "\(userId)|\(deviceId)"
    }

    /// The cached Signal device id for a (user, backend device) pair, if known.
    func cached(userId: String, deviceId: String) -> Int32? {
        mapping[Self.key(userId: userId, deviceId: deviceId)]
    }

    /// Stores only successful resolutions: the caller passes exactly what a
    /// successful bundle fetch returned. There is deliberately no API to store
    /// a failed or unresolved lookup, so a transient HTTP failure or an unknown
    /// device can never poison the cache.
    func store(userId: String, devices: [(deviceId: String, signalDeviceId: Int32)]) {
        for device in devices {
            mapping[Self.key(userId: userId, deviceId: device.deviceId)] = device.signalDeviceId
        }
    }

    /// Empties the cache (logout / account switch / session teardown).
    func clear() {
        mapping.removeAll()
    }
}
