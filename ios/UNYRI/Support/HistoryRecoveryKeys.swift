import Foundation
#if canImport(UIKit) && canImport(Security)
import Security
#endif

/// Missing, inaccessible and corrupt storage are deliberately distinct. Never
/// replace a recovery key merely because protected storage could not be read.
struct HistoryRecoveryKeys {
    enum Failure: Error { case damaged, conflict, persistence }
    private static let lock = NSRecursiveLock()
    private let replace: ((String, Data) throws -> Void)?
    private let name: String
    private let read: (String) throws -> Data?
    private let insert: (String, Data) throws -> Void
    init(server: String, owner: String, scope: AccountSecretScope? = nil, read: @escaping (String) throws -> Data?, insert: @escaping (String, Data) throws -> Void, replace: ((String, Data) throws -> Void)? = nil) throws {
        try HistoryArchive.validateId(server); try HistoryArchive.validateId(owner)
        name = scope.map { $0.historyPrefix + server.lowercased() } ?? "history-v1:\(server):\(owner)"
        self.read = read; self.insert = insert; self.replace = replace
    }
    func load() throws -> Data? {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let data = try read(name) else { return nil }
        guard data.count == 32 else { throw Failure.damaged }
        return data
    }
    /// Only after confirming the saved paper key or decrypting an existing copy.
    /// The platform insert is atomic and refuses replacement on duplicate keys.
    func saveConfirmed(_ key: Data) throws {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard key.count == 32 else { throw Failure.damaged }
        if let existing = try load() {
            guard existing == key else { throw Failure.conflict }
            return
        }
        try insert(name, key)
        guard try load() == key else { throw Failure.persistence }
    }
    /// Caller must verify the candidate against the current active backup and
    /// obtain explicit replacement consent first. All in-process writers share
    /// this lock; protected-store read failures never turn into an empty slot.
    func replaceVerified(_ key: Data, expected: Data?) throws {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard key.count == 32 else { throw Failure.damaged }
        guard try load() == expected else { throw Failure.conflict }
        if expected == key { return }
        if expected == nil { try insert(name, key) }
        else { guard let replace else { throw Failure.persistence }; try replace(name, key) }
        guard try load() == key else { throw Failure.persistence }
    }
}

#if canImport(UIKit) && canImport(Security)
extension HistoryRecoveryKeys {
    static func deviceStore(server: String, owner: String) throws -> HistoryRecoveryKeys {
        Self.lock.lock(); defer { Self.lock.unlock() }
        try HistoryArchive.validateId(server); try HistoryArchive.validateId(owner)
        let scope = AccountSecretScope.current(owner: owner)
        let service = (Bundle.main.bundleIdentifier ?? "com.messenger.coco") + ".history-recovery"
        func query(_ name: String) -> [String: Any] {
            [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
             kSecAttrAccount as String: name, kSecAttrSynchronizable as String: false]
        }
        func read(_ name: String) throws -> Data? {
            var request = query(name)
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            var item: CFTypeRef?
            let status = SecItemCopyMatching(request as CFDictionary, &item)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
            guard let data = item as? Data else { throw Failure.damaged }
            return data
        }
        func insert(_ name: String, _ key: Data) throws {
            var request = query(name)
            request[kSecValueData as String] = key
            request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(request as CFDictionary, nil)
            // Never SecItemUpdate here: two concurrent setup screens must not
            // replace each other's recovery key. Caller can reload after conflict.
            guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        }
        let name = scope.historyPrefix + server.lowercased()
        if scope.ownsLegacy(defaults: .standard) {
            _ = try AccountSecretScope.migrate(old: "history-v1:\(server):\(owner)", new: name, read: read, insert: { name, data in
                guard data.count == 32 else { throw Failure.damaged }
                try insert(name, data)
            }, remove: { old in
                let status = SecItemDelete(query(old) as CFDictionary)
                guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure.persistence }
            })
        }
        return try HistoryRecoveryKeys(server: server, owner: owner, scope: scope, read: read, insert: insert, replace: { name, key in
            let status = SecItemUpdate(query(name) as CFDictionary, [kSecValueData as String: key] as CFDictionary)
            guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        })
    }
}
#endif
