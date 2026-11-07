import Foundation
import Security

/// Never includes a key name or stored bytes. Absence is represented only by
/// errSecItemNotFound; inaccessible or malformed storage is an error.
enum SignalStorageError: Error {
    case keychain(OSStatus)
    case malformedResult
    case corruptMetadata
    case exhaustedMetadata
}

/// Keychain-backed opaque `Data` storage for Signal state. Private/state
/// material is never written to UserDefaults.
final class SignalKeychainStore {
    private let service: String

    init(serviceSuffix: String = "signal") {
        let bundle = Bundle.main.bundleIdentifier ?? "com.messenger.coco"
        self.service = "\(bundle).\(serviceSuffix)"
    }

    func data(forKey key: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw SignalStorageError.keychain(status) }
        guard let data = item as? Data else { throw SignalStorageError.malformedResult }
        return data
    }

    func setData(_ data: Data, forKey key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let attributes: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(addQuery as CFDictionary, nil)
            guard added == errSecSuccess else { throw SignalStorageError.keychain(added) }
        } else if status != errSecSuccess {
            throw SignalStorageError.keychain(status)
        }
    }

    func remove(forKey key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SignalStorageError.keychain(status) }
    }

    func keys() throws -> [String] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw SignalStorageError.keychain(status) }
        guard let rows = result as? [[String: Any]] else { throw SignalStorageError.malformedResult }
        return try rows.map {
            guard let key = $0[kSecAttrAccount as String] as? String else { throw SignalStorageError.malformedResult }
            return key
        }
    }

    func contains(_ key: String) throws -> Bool {
        try data(forKey: key) != nil
    }
}

/// Namespaced Signal state storage. Records are stored as opaque `Data`;
/// the concrete libsignal record serialization is added later, not here.
/// Keys are namespaced by the current UNYRI account/device so that switching
/// accounts can never share identity material, prekeys, or sessions.
final class SignalStateStore {
    private let ownershipLock = NSRecursiveLock()
    private var active = true
    private let keychain: SignalKeychainStore
    private let namespace: () -> String

    init(keychain: SignalKeychainStore = SignalKeychainStore(),
         namespace: @escaping () -> String) {
        self.keychain = keychain
        self.namespace = namespace
    }

    // Logical namespaces reserved for future records (prefix conventions).
    enum Namespace {
        static let identity = "identity"
        static let preKey = "prekey"
        static let signedPreKey = "signed_prekey"
        static let kyberPreKey = "kyber_prekey"
        static let session = "session"
        static let trustedIdentity = "trusted_identity"
        static let metadata = "metadata"
    }

    func data(forKey key: String) throws -> Data? {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try keychain.data(forKey: scoped(key))
    }

    func setData(_ data: Data, forKey key: String) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try keychain.setData(data, forKey: scoped(key))
    }

    func remove(forKey key: String) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try keychain.remove(forKey: scoped(key))
    }

    func contains(_ key: String) throws -> Bool {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try keychain.contains(scoped(key))
    }

    func keys(withPrefix prefix: String) throws -> [String] {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        let scope = namespace() + ":"
        return try keychain.keys().filter { $0.hasPrefix(scope + prefix) }.map { String($0.dropFirst(scope.count)) }
    }

    /// Synchronous ownership fence before the account Keychain service is
    /// erased. A late discarded Signal operation cannot recreate private keys.
    func invalidate() {
        ownershipLock.lock(); active = false; ownershipLock.unlock()
    }

    private func scoped(_ key: String) -> String {
        "\(namespace()):\(key)"
    }
}
