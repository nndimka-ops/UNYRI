import Foundation
#if canImport(Security)
import Security
#endif

struct InstallationCredentialResponse: Decodable { let installationCredential: String }
struct InstallationCredentialBinding: Encodable { let installationCredential: String }

enum InstallationProofError: Error {
    case unsupportedServer, invalidCredential, persistence, invalidOrigin
}

protocol InstallationProofPersisting {
    func read(origin: String) throws -> String?
    func save(_ value: String, origin: String) throws
    func eraseAll() throws
}

/// One non-account credential per server. It cannot restore login or history.
/// The coordinator is injected into AuthRepository, including for existing logins.
@MainActor
final class InstallationProofCoordinator {
    private let store: InstallationProofPersisting
    private let issue: (URL) async throws -> String
    private var unsupported = Set<String>()
    private var resetEpoch: UInt64 = 0

    init(store: InstallationProofPersisting, issue: @escaping (URL) async throws -> String) {
        self.store = store
        self.issue = issue
    }

    static func origin(_ url: URL) throws -> String {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = parts.scheme?.lowercased(), scheme == "https",
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/" else { throw InstallationProofError.invalidOrigin }
        parts.scheme = scheme
        parts.host = host
        parts.path = ""
        if (scheme == "https" && parts.port == 443) || (scheme == "http" && parts.port == 80) { parts.port = nil }
        guard let value = parts.url?.absoluteString else { throw InstallationProofError.invalidOrigin }
        return value
    }

    static func valid(_ value: String) -> Bool {
        value.utf8.count == 43 && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }
    }

    func credential(origin url: URL, current: () -> Bool) async throws -> String? {
        let origin = try Self.origin(url)
        let epoch = resetEpoch
        try Task.checkCancellation()
        guard current() else { throw CancellationError() }
        if unsupported.contains(origin) { return nil }
        if let value = try store.read(origin: origin) {
            guard Self.valid(value) else { throw InstallationProofError.invalidCredential }
            return value
        }
        do {
            let value = try await issue(url)
            try Task.checkCancellation()
            guard current(), epoch == resetEpoch else { throw CancellationError() }
            guard Self.valid(value) else { throw InstallationProofError.invalidCredential }
            // Two callers may have observed an empty store before issuing. The
            // first durable proof wins; never overwrite a proof already used by
            // another account on this installation.
            if let existing = try store.read(origin: origin) {
                guard Self.valid(existing) else { throw InstallationProofError.invalidCredential }
                return existing
            }
            try store.save(value, origin: origin)
            guard try store.read(origin: origin) == value else { throw InstallationProofError.persistence }
            return value
        } catch InstallationProofError.unsupportedServer {
            guard current(), epoch == resetEpoch else { throw CancellationError() }
            unsupported.insert(origin)
            return nil
        }
    }

    func resetForFreshInstallation() throws {
        resetEpoch &+= 1
        try store.eraseAll()
        unsupported.removeAll()
    }
}

#if canImport(Security)
/// Separate Keychain service is deliberately outside account erasure. Never sync
/// this installation marker through iCloud or migrate it to a different device.
final class KeychainInstallationProofStore: InstallationProofPersisting {
    private let service = (Bundle.main.bundleIdentifier ?? "com.messenger.coco") + ".installation-proof"
    private func query(origin: String? = nil) -> [String: Any] {
        var value: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrSynchronizable as String: false]
        if let origin { value[kSecAttrAccount as String] = origin }
        return value
    }
    func read(origin: String) throws -> String? {
        var request = query(origin: origin)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        guard let data = item as? Data, let value = String(data: data, encoding: .utf8) else { throw InstallationProofError.persistence }
        return value
    }
    func save(_ value: String, origin: String) throws {
        let request = query(origin: origin)
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(request as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(request.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    func eraseAll() throws {
        let status = SecItemDelete(query() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}
#endif
