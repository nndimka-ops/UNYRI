import Foundation

/// Non-secret Signal metadata (counters, ids, flags) persisted through
/// `SignalStateStore`, sharing its account/device namespace. Identity/prekey
/// *bytes* are written by the future crypto manager via `SignalStateStore`;
/// this type only tracks the small, non-secret values the bootstrap planner
/// and upload flow need.
final class SignalMetadataStore {
    private let store: SignalStateStore

    init(store: SignalStateStore) {
        self.store = store
    }

    var hasLocalIdentity: Bool { get throws { try store.contains(Key.identityKeyPair) } }

    var registrationId: Int32? { get throws { try int32(for: Key.registrationId, minimum: 0) } }
    func setRegistrationId(_ value: Int32) throws { try setInt(Int(value), for: Key.registrationId, minimum: 0) }

    var currentSignedPreKeyId: Int32? { get throws { try int32(for: Key.signedPreKeyId) } }
    func setSignedPreKeyId(_ value: Int32) throws { try setInt(Int(value), for: Key.signedPreKeyId) }

    var currentKyberPreKeyId: Int32? { get throws { try int32(for: Key.kyberPreKeyId) } }
    func setKyberPreKeyId(_ value: Int32) throws { try setInt(Int(value), for: Key.kyberPreKeyId) }

    var nextOneTimePreKeyId: Int32? { get throws { try int32(for: Key.nextOneTimePreKeyId) } }
    func setNextOneTimePreKeyId(_ value: Int32) throws { try setInt(Int(value), for: Key.nextOneTimePreKeyId) }

    var initialUploadCompleted: Bool { get throws { try bool(for: Key.initialUploadCompleted) } }
    func setInitialUploadCompleted(_ completed: Bool) throws { try setBool(completed, for: Key.initialUploadCompleted) }

    var localOneTimePreKeyCount: Int { get throws { try int(for: Key.localOneTimePreKeyCount, minimum: 0) ?? 0 } }
    func setLocalOneTimePreKeyCount(_ count: Int) throws { try setInt(count, for: Key.localOneTimePreKeyCount, minimum: 0) }

    // Only an absent item gets an initial default. Invalid UTF-8, non-integers,
    // overflow and invalid ranges must not recreate a registration/counter.
    private func int32(for key: String, minimum: Int = 1) throws -> Int32? {
        try int(for: key, minimum: minimum).map(Int32.init)
    }

    private func int(for key: String, minimum: Int = 1) throws -> Int? {
        guard let data = try store.data(forKey: key) else { return nil }
        guard let text = String(data: data, encoding: .utf8), let value = Int(text),
              value >= minimum, value <= Int(Int32.max) else { throw SignalStorageError.corruptMetadata }
        return value
    }

    private func setInt(_ value: Int, for key: String, minimum: Int = 1) throws {
        guard value >= minimum, value <= Int(Int32.max) else { throw SignalStorageError.corruptMetadata }
        try store.setData(Data(String(value).utf8), forKey: key)
    }

    private func bool(for key: String) throws -> Bool {
        guard let data = try store.data(forKey: key) else { return false }
        if data == Data("0".utf8) { return false }
        if data == Data("1".utf8) { return true }
        throw SignalStorageError.corruptMetadata
    }

    private func setBool(_ value: Bool, for key: String) throws {
        try store.setData(Data((value ? "1" : "0").utf8), forKey: key)
    }

    private enum Key {
        static let identityKeyPair = "\(SignalStateStore.Namespace.identity).key_pair"
        static let registrationId = "\(SignalStateStore.Namespace.metadata).registration_id"
        static let signedPreKeyId = "\(SignalStateStore.Namespace.metadata).signed_prekey_id"
        static let kyberPreKeyId = "\(SignalStateStore.Namespace.metadata).kyber_prekey_id"
        static let nextOneTimePreKeyId = "\(SignalStateStore.Namespace.metadata).next_otp_id"
        static let initialUploadCompleted = "\(SignalStateStore.Namespace.metadata).initial_upload_completed"
        static let localOneTimePreKeyCount = "\(SignalStateStore.Namespace.metadata).local_otp_count"
    }
}
