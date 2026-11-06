import Foundation

/// Pure key-bootstrap policy + id allocation. Mirrors Android's
/// `SignalSessionManager.bootstrapKeysIfNeeded` and `SignalKeyStore` counters
/// (verified against the Android source). No libsignal dependency, so this
/// file can be exercised on Windows with Swift alone.
enum KeyBootstrapPolicy {
    /// Android generates registration ids with `Random.nextInt(1, 16381)`,
    /// i.e. the inclusive range [1, 16380], exactly once per account/device.
    static let registrationIdRange = 1...16380

    static let oneTimePreKeyLowWatermark = 5
    static let oneTimePreKeyBatchSize = 20

    /// One-time registration id in [1, 16380].
    static func randomRegistrationId() -> Int32 {
        Int32.random(in: 1...16380)
    }

    static func isValidRegistrationId(_ id: Int32) -> Bool {
        (1...16380).contains(id)
    }

    /// Mirrors Android's single-pass gate: work is needed if the signed prekey
    /// is missing, the kyber prekey is missing, or the one-time pool is running
    /// low on EITHER side (server = what peers can fetch, local = what this
    /// device can decrypt with — after a reinstall the server may look healthy
    /// while the local pool is empty, so both are authoritative).
    static func needsWork(
        hasSignedPreKey: Bool,
        hasKyberPreKey: Bool,
        localOneTimePreKeyCount: Int,
        serverOneTimePreKeyCount: Int
    ) -> KeyBootstrapDecision {
        let needsSigned = !hasSignedPreKey
        let needsKyber = !hasKyberPreKey
        let needsOneTime = serverOneTimePreKeyCount < oneTimePreKeyLowWatermark
            || localOneTimePreKeyCount < oneTimePreKeyLowWatermark
        return KeyBootstrapDecision(
            needsSignedPreKey: needsSigned,
            needsKyberPreKey: needsKyber,
            needsOneTimePreKeys: needsOneTime,
            oneTimePreKeyBatchSize: needsOneTime ? oneTimePreKeyBatchSize : 0
        )
    }
}

/// The decision the key manager acts on. Pure data.
struct KeyBootstrapDecision: Equatable {
    let needsSignedPreKey: Bool
    let needsKyberPreKey: Bool
    let needsOneTimePreKeys: Bool
    let oneTimePreKeyBatchSize: Int

    var needsAnyWork: Bool {
        needsSignedPreKey || needsKyberPreKey || needsOneTimePreKeys
    }
}

/// Monotonic one-time prekey id allocation. Ids are never reused within an
/// account/device, so a re-upload can never collide with ids the server may
/// still hold from a previous batch.
enum OneTimePreKeyAllocation {
    /// Returns the fresh ids to allocate and the new `nextId` to persist AFTER
    /// the records are durably written. Callers must not advance `nextId`
    /// before the records are stored, otherwise a mid-batch failure would
    /// leave orphaned private records that a later batch would re-id.
    static func nextBatch(nextId: Int32, count: Int) -> (ids: [Int32], nextId: Int32) {
        let ids = (0..<count).map { nextId + Int32($0) }
        return (ids, nextId + Int32(count))
    }
}
