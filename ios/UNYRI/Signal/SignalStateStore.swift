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

/// One closure per Security API call keeps the production call count observable
/// in contracts without touching the test runner's real Keychain.
struct SignalKeychainOperations {
    let matching: ([String: Any]) -> (OSStatus, CFTypeRef?)
    let update: ([String: Any], [String: Any]) -> OSStatus
    let add: ([String: Any]) -> OSStatus
    let delete: ([String: Any]) -> OSStatus

    static var system: SignalKeychainOperations {
        SignalKeychainOperations(
            matching: { (query: [String: Any]) -> (OSStatus, CFTypeRef?) in
                var result: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &result)
                return (status, result)
            },
            update: { (query: [String: Any], attributes: [String: Any]) -> OSStatus in
                SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            },
            add: { (query: [String: Any]) -> OSStatus in
                SecItemAdd(query as CFDictionary, nil)
            },
            delete: { (query: [String: Any]) -> OSStatus in
                SecItemDelete(query as CFDictionary)
            })
    }
}

/// Keychain-backed opaque `Data` storage for Signal state. Private/state
/// material is never written to UserDefaults.
final class SignalKeychainStore {
    private let service: String
    private let operations: SignalKeychainOperations

    init(serviceSuffix: String = "signal", operations: SignalKeychainOperations = .system) {
        let bundle = Bundle.main.bundleIdentifier ?? "com.messenger.coco"
        self.service = "\(bundle).\(serviceSuffix)"
        self.operations = operations
    }

    func data(forKey key: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        let (status, item) = operations.matching(query)
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
        let status = operations.update(query, attributes)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = operations.add(addQuery)
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
        let status = operations.delete(query)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SignalStorageError.keychain(status) }
    }

    func keys() throws -> [String] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll]
        let (status, result) = operations.matching(query)
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
final class SignalStateStore: @unchecked Sendable {
    // All mutable state (including the lazy journal) is protected by this
    // recursive lock. The queue runs complete Signal operations, never a
    // detached task per individual store callback.
    private let ioQueue = DispatchQueue(label: "unyri.signal.io", qos: .userInitiated)
    func withExclusiveAccess<T>(_ body: () throws -> T) throws -> T {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try body()
    }
    func perform<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            ioQueue.async {
                do { continuation.resume(returning: try self.withExclusiveAccess(body)) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }
    private let ownershipLock = NSRecursiveLock()
    private var active = true
    private let keychain: SignalKeychainStore
    private let namespace: () -> String

    init(keychain: SignalKeychainStore = SignalKeychainStore(),
         namespace: @escaping () -> String) {
        self.keychain = keychain
        self.namespace = namespace
    }

    private lazy var receiveStorage = SignalReceiveJournal(
        read: { [keychain = self.keychain, namespace = self.namespace] key in try keychain.data(forKey: namespace() + ":" + key) },
        write: { [keychain = self.keychain, namespace = self.namespace] key, value in try keychain.setData(value, forKey: namespace() + ":" + key) },
        remove: { [keychain = self.keychain, namespace = self.namespace] key in try keychain.remove(forKey: namespace() + ":" + key) },
        keys: { [keychain = self.keychain, namespace = self.namespace] in
            let scope = namespace() + ":"
            return try keychain.keys().filter { $0.hasPrefix(scope) }.map { String($0.dropFirst(scope.count)) }
        })

    func receive(id: String, metadata: Data, decrypt: () throws -> Data) throws -> Data {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try receiveStorage.receive(id: id, metadata: metadata, decrypt: decrypt)
    }
    func discardReceiveJournals(chatID: String, through: Int64? = nil) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try receiveStorage.discard(chatID: chatID, through: through)
    }
    func pendingReceiveMetadata() throws -> [Data] {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try receiveStorage.pendingMetadata()
    }
    func recoveredReceive(id: String) throws -> Data? {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try receiveStorage.recovered(id: id)
    }
    func finishReceive(id: String) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try receiveStorage.finish(id: id)
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
        return try receiveStorage.data(key)
    }

    func setData(_ data: Data, forKey key: String) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try receiveStorage.set(key, value: data)
    }

    func remove(forKey key: String) throws {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try receiveStorage.set(key, value: nil)
    }

    func contains(_ key: String) throws -> Bool {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        return try receiveStorage.data(key) != nil
    }

    func keys(withPrefix prefix: String) throws -> [String] {
        ownershipLock.lock(); defer { ownershipLock.unlock() }
        guard active else { throw CancellationError() }
        try receiveStorage.recover()
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
