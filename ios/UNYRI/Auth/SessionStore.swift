import Foundation
import Security

/// Minimal generic-password Keychain wrapper.
enum KeychainStore {
    private static var service: String {
        Bundle.main.bundleIdentifier ?? "com.messenger.coco"
    }

    @discardableResult
    static func set(_ value: String, forKey key: String) -> Bool {
        let data = Data(value.utf8)
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
            return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
        }
        return status == errSecSuccess
    }

    static func value(forKey key: String) -> String? {
        try? checkedValue(forKey: key)
    }

    static func checkedValue(forKey key: String) throws -> String? {
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
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        guard let data = item as? Data, let value = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return value
    }

    static func remove(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// Keychain-backed session storage. Tokens and identity live in the Keychain,
/// never in UserDefaults. Passwords are never stored.
final class SessionStore {
    enum PersistenceError: Error { case failed }
    private let readValue: (String) throws -> String?
    private let write: (String, String) -> Bool
    private let remove: (String) -> Void
    private let tokenReadQueue = DispatchQueue(label: "unyri.session-token-read", qos: .userInitiated)
    init(read: @escaping (String) throws -> String? = { try KeychainStore.checkedValue(forKey: $0) },
         write: @escaping (String, String) -> Bool = { KeychainStore.set($0, forKey: $1) },
         remove: @escaping (String) -> Void = { KeychainStore.remove($0) }) {
        self.readValue = read; self.write = write; self.remove = remove
    }
    private func read(_ key: String) -> String? { try? readValue(key) }

    private struct OfflineScreen: Codable { let owner: String; let body: Data }
    func cachedScreen<T: Decodable>(_ key: String, owner: String, as type: T.Type) -> T? {
        guard ["profile", "devices", "calls.history.restored", "calls.history.since"].contains(key),
              let text = read("offline_" + key), let data = Data(base64Encoded: text),
              let entry = try? JSONDecoder().decode(OfflineScreen.self, from: data),
              entry.owner == owner else { return nil }
        return try? JSONDecoder().decode(type, from: entry.body)
    }
    func saveScreen<T: Encodable>(_ key: String, owner: String, value: T) {
        guard ["profile", "devices", "calls.history.restored", "calls.history.since"].contains(key),
              let body = try? JSONEncoder().encode(value), body.count <= 65536,
              let data = try? JSONEncoder().encode(OfflineScreen(owner: owner, body: body)) else { return }
        _ = write(data.base64EncodedString(), "offline_" + key)
    }

    /// Missing credentials and temporarily inaccessible Keychain items are
    /// different states. Never erase account data based on the latter.
    func requireReadableSession() throws {
        if try readValue(Key.snapshot) != nil { return }
        for key in [Key.refreshToken, Key.userId, Key.username, Key.displayName] {
            _ = try readValue(key)
        }
    }

    /// Checked startup evidence only. A locked/corrupt Keychain must never
    /// resemble an empty fresh installation when selecting a new default host.
    func checkedServerContinuityUserID() throws -> String? {
        if let raw = try readValue(Key.snapshot) {
            let value = try JSONDecoder().decode(Snapshot.self, from: Data(raw.utf8))
            guard !value.refreshToken.isEmpty, let user = value.user, !user.id.isEmpty else {
                throw CocoaError(.fileReadCorruptFile)
            }
            return user.id
        }
        let refresh = try readValue(Key.refreshToken)
        let user = try readValue(Key.userId)
        if refresh == nil && user == nil { return nil }
        guard let refresh, !refresh.isEmpty, let user, !user.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
        return user
    }
    private struct Snapshot: Codable {
        let accessToken: String
        let expiresAt: Date
        let refreshToken: String
        let user: User?
        let deviceId: String?
        var historyBeforeSequence: Int64? = nil
        var historyChoiceCompleted: Bool? = nil
        var historyContext: HistoryContext? = nil
        var roomAccessSessionID: String? = nil
    }
    private func snapshot() -> Snapshot? {
        guard let value = read(Key.snapshot) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: Data(value.utf8))
    }

    struct Tokens {
        let access: String?
        let expiresAt: Date?
        let refresh: String?
    }

    /// One checked Keychain read on the ordinary authenticated-request path.
    /// No credentials are cached outside this request or written to preferences.
    /// The caller must reject a result belonging to a retired session generation.
    func readTokensAsync() async throws -> Tokens {
        try Task.checkCancellation()
        let tokens: Tokens = try await withCheckedThrowingContinuation { continuation in
            tokenReadQueue.async {
                do {
                    let raw = try self.readValue(Key.snapshot)
                    if let raw, let snapshot = try? JSONDecoder().decode(Snapshot.self, from: Data(raw.utf8)) {
                        continuation.resume(returning: Tokens(access: snapshot.accessToken,
                            expiresAt: snapshot.expiresAt, refresh: snapshot.refreshToken))
                        return
                    }
                    // Preserve legacy installations and distinguish an inaccessible
                    // Keychain from missing credentials. A read failure is not logout.
                    for key in [Key.refreshToken, Key.userId, Key.username, Key.displayName] {
                        _ = try self.readValue(key)
                    }
                    let access = try self.readValue(Key.accessToken)
                    let expires = try self.readValue(Key.accessTokenExpiresAt).flatMap(TimeInterval.init)
                    let refresh = try self.readValue(Key.refreshToken)
                    continuation.resume(returning: Tokens(access: access,
                        expiresAt: expires.map { Date(timeIntervalSince1970: $0) }, refresh: refresh))
                } catch { continuation.resume(throwing: error) }
            }
        }
        try Task.checkCancellation()
        return tokens
    }
    private func save(_ snapshot: Snapshot, resetRefreshRequest: Bool = true) throws {
        let data = try JSONEncoder().encode(snapshot)
        guard let value = String(data: data, encoding: .utf8), write(value, Key.snapshot) else { throw PersistenceError.failed }
        if resetRefreshRequest { remove(Key.refreshRequest) }
    }

    private enum Key {
        static let snapshot = "session_snapshot_v1"
        static let refreshRequest = "refresh_request_id"
        static let accessToken = "access_token"
        static let accessTokenExpiresAt = "access_token_expires_at"
        static let refreshToken = "refresh_token"
        static let deviceId = "device_id"
        static let installationId = "installation_id"
        static let userId = "user_id"
        static let username = "username"
        static let displayName = "display_name"
        static let lastSyncedSequence = "last_synced_sequence"
    }

    func saveTokens(accessToken: String, accessTokenExpiresAt: Date, refreshToken: String) throws {
        try save(Snapshot(accessToken: accessToken, expiresAt: accessTokenExpiresAt, refreshToken: refreshToken,
                          user: currentUser(), deviceId: deviceId(), historyBeforeSequence: historyBeforeSequence(),
                          historyChoiceCompleted: snapshot()?.historyChoiceCompleted, historyContext: snapshot()?.historyContext,
                          roomAccessSessionID: snapshot()?.roomAccessSessionID))
    }
    func saveSession(_ response: AuthResponse) throws {
        try save(Snapshot(accessToken: response.accessToken, expiresAt: response.accessTokenExpiresAt,
                          refreshToken: response.refreshToken, user: response.user, deviceId: response.deviceId,
                          historyBeforeSequence: response.historyBeforeSequence,
                          historyChoiceCompleted: (response.historyBeforeSequence ?? 0) <= 0,
                          roomAccessSessionID: UUID().uuidString.lowercased()))
        remove("conference_owned_access_v1")
    }

    /// Checked reads distinguish locked Keychain from a missing re-entry record.
    /// A fresh login changes this nonce even for the same account/device; a late
    /// old-controller write cannot repopulate a newly logged-in workspace.
    private func ownedRoomAccessBinding(server: URL) throws -> String? {
        guard let raw = try readValue(Key.snapshot) else { return nil }
        var value = try JSONDecoder().decode(Snapshot.self, from: Data(raw.utf8))
        guard let user = value.user, UUID(uuidString: user.id) != nil,
              let device = value.deviceId, UUID(uuidString: device) != nil,
              !value.refreshToken.isEmpty else { return nil }
        if value.roomAccessSessionID == nil {
            value.roomAccessSessionID = UUID().uuidString.lowercased()
            try save(value, resetRefreshRequest: false)
        }
        guard let nonce = value.roomAccessSessionID, UUID(uuidString: nonce) != nil,
              let parts = URLComponents(url: server, resolvingAgainstBaseURL: false),
              parts.scheme == "https", parts.host != nil, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty else { return nil }
        return [server.absoluteString, user.id.lowercased(), device.lowercased(), nonce].joined(separator: "|")
    }
    func ownedRoomAccessSnapshot(server: URL) throws -> (binding: String, raw: String?)? {
        guard let binding = try ownedRoomAccessBinding(server: server) else { return nil }
        return (binding, try readValue("conference_owned_access_v1"))
    }
    func saveOwnedRoomAccess(_ raw: String, server: URL, binding: String) throws {
        guard raw.utf8.count <= 12_288, try ownedRoomAccessBinding(server: server) == binding,
              write(raw, "conference_owned_access_v1") else { throw PersistenceError.failed }
    }
    func historyBeforeSequence() -> Int64? { snapshot()?.historyBeforeSequence }
    func historyContext() -> HistoryContext? { snapshot()?.historyContext }
    func saveHistoryContext(_ context: HistoryContext) throws {
        guard context.beforeSequence >= 0, let access = accessToken(), let refresh = refreshToken(),
              let expires = accessTokenExpiresAt(), let user = currentUser() else { throw PersistenceError.failed }
        var value = snapshot() ?? Snapshot(accessToken: access, expiresAt: expires,
            refreshToken: refresh, user: user, deviceId: deviceId())
        // Existing login boundaries remain authoritative. Backfill only missing metadata.
        value.historyBeforeSequence = value.historyBeforeSequence ?? context.beforeSequence
        value.historyContext = HistoryContext(beforeSequence: value.historyBeforeSequence ?? context.beforeSequence, beforeDate: context.beforeDate)
        try save(value, resetRefreshRequest: false)
    }
    func needsHistoryChoice() -> Bool { snapshot()?.historyChoiceCompleted == false }
    func completeHistoryChoice() throws {
        guard var value = snapshot() else { throw PersistenceError.failed }
        value.historyChoiceCompleted = true
        try save(value, resetRefreshRequest: false)
    }
    func refreshRequestId() throws -> String {
        if let id = read(Key.refreshRequest), UUID(uuidString: id) != nil { return id }
        let id = UUID().uuidString.lowercased()
        guard write(id, Key.refreshRequest) else { throw PersistenceError.failed }
        return id
    }
    func accessToken() -> String? { snapshot()?.accessToken ?? read(Key.accessToken) }
    func refreshToken() -> String? { snapshot()?.refreshToken ?? read(Key.refreshToken) }
    func deviceId() -> String? { snapshot()?.deviceId ?? read(Key.deviceId) }
    func userId() -> String? { snapshot()?.user?.id ?? read(Key.userId) }
    func username() -> String? { snapshot()?.user?.username ?? read(Key.username) }
    func displayName() -> String? { snapshot()?.user?.displayName ?? read(Key.displayName) }
    func accessTokenExpiresAt() -> Date? {
        if let snapshot = snapshot() { return snapshot.expiresAt }
        guard let raw = read(Key.accessTokenExpiresAt), let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    func currentUser() -> User? {
        guard let id = userId(),
              let username = username(),
              let displayName = displayName() else { return nil }
        return User(id: id, username: username, displayName: displayName)
    }

    /// Stable installation identifier. Generated once and persisted for the
    /// lifetime of the installation (survives logout), so the backend can reuse
    /// the same device row across logins and different accounts.
    func installationId() -> String {
        if let existing = read(Key.installationId), !existing.isEmpty {
            return existing
        }
        let generated = UUID().uuidString.lowercased()
        _ = write(generated, Key.installationId)
        return generated
    }

    func durableInstallationId() throws -> String {
        if let existing = try readValue(Key.installationId), !existing.isEmpty { return existing }
        let generated = UUID().uuidString.lowercased()
        guard write(generated, Key.installationId) else { throw PersistenceError.failed }
        return generated
    }

    func requireReadableInstallation() throws { _ = try readValue(Key.installationId) }

    /// A lost app workspace must enroll as a fresh device on the next login.
    /// Never erase Signal keys or server history; a new installation namespaces keys.
    func retireSessionForMissingWorkspace() throws {
        try requireReadableSession()
        let generated = UUID().uuidString.lowercased()
        guard write(generated, Key.installationId),
              try readValue(Key.installationId) == generated else { throw PersistenceError.failed }
        clearSession()
        // Removal APIs can fail while Keychain is unavailable. Do not admit login
        // until all old credential/cursor fallbacks are confirmed absent.
        for key in [Key.accessToken, Key.accessTokenExpiresAt, Key.refreshToken,
                    Key.deviceId, Key.userId, Key.username, Key.displayName,
                    Key.lastSyncedSequence, Key.refreshRequest, Key.snapshot] {
            guard try readValue(key) == nil else { throw PersistenceError.failed }
        }
    }

    /// Durable device-scoped `/sync` cursor (global `messages.sequence`).
    /// Cleared on logout so a different account's device never resumes from
    /// another account's position (mirrors Android's KEY_LAST_SYNCED_SEQUENCE).
    func lastSyncedSequence() -> Int64? {
        guard let raw = read(Key.lastSyncedSequence) else { return nil }
        return Int64(raw)
    }

    func saveLastSyncedSequence(_ sequence: Int64) {
        _ = write(String(sequence), Key.lastSyncedSequence)
    }

    /// Clears everything session-scoped. `installation_id` is intentionally
    /// kept so a different account can reuse the same installation row.
    struct PendingLogout: Codable { let id: UUID; let origin: URL; let refreshToken: String }
    func pendingLogouts() throws -> [PendingLogout] {
        guard let raw = try readValue("pending_logouts") else { return [] }
        return try JSONDecoder().decode([PendingLogout].self, from: Data(raw.utf8))
    }
    private func savePendingLogouts(_ items: [PendingLogout]) throws {
        let raw = String(decoding: try JSONEncoder().encode(items), as: UTF8.self)
        guard write(raw, "pending_logouts") else { throw PersistenceError.failed }
    }
    func enqueueLogout(origin: URL, refreshToken: String) throws {
        var items = try pendingLogouts()
        if !items.contains(where: { $0.origin == origin && $0.refreshToken == refreshToken }) {
            items.append(PendingLogout(id: UUID(), origin: origin, refreshToken: refreshToken))
            try savePendingLogouts(items)
        }
    }
    func finishPendingLogout(_ id: UUID) throws {
        try savePendingLogouts(pendingLogouts().filter { $0.id != id })
    }

    func clearSession() {
        remove("conference_owned_access_v1")
        remove("offline_profile")
        remove("offline_devices")
        remove("offline_calls.history.restored")
        remove("offline_calls.history.since")
        for key in [Key.accessToken, Key.accessTokenExpiresAt, Key.refreshToken,
                    Key.deviceId, Key.userId, Key.username, Key.displayName,
                    Key.lastSyncedSequence, Key.refreshRequest, Key.snapshot] {
            remove(key)
        }
    }
}

/// Erases all account material after account writers are drained. The only
/// retained credentials are inaccessible-to-login queued logout proofs, which
/// exist solely to revoke an offline session when transport returns.
enum LocalAccountCachePurger {
    static func erase() async throws {
        try erasePrivateKeychain()
        eraseAccountPreferences()
        URLCache.shared.removeAllCachedResponses()
        let manager = FileManager.default
        let support = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        let caches = manager.urls(for: .cachesDirectory, in: .userDomainMask).first
        let roots = [support?.appendingPathComponent("COCO", isDirectory: true),
                     caches?.appendingPathComponent("COCOAvatars", isDirectory: true),
                     caches?.appendingPathComponent("COCODirectory", isDirectory: true)].compactMap { $0 }
        let temporary = manager.temporaryDirectory
        try await erase(accountRoots: roots, temporaryDirectory: temporary)
    }

    static func erasePrivateKeychain() throws {
        let bundle = Bundle.main.bundleIdentifier ?? "com.messenger.coco"
        for service in [bundle + ".signal", bundle + ".history-recovery"] {
            let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        }
        // Verify credentials/profile remnants disappeared even if the ordinary
        // nonthrowing clearSession failed because Keychain was unavailable.
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: bundle,
            kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return }
        guard status == errSecSuccess, let rows = item as? [[String: Any]] else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        for row in rows {
            guard let account = row[kSecAttrAccount as String] as? String else { throw CocoaError(.fileReadCorruptFile) }
            guard account != "installation_id", account != "pending_logouts" else { continue }
            let scoped: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: bundle,
                kSecAttrAccount as String: account, kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
            let removed = SecItemDelete(scoped as CFDictionary)
            guard removed == errSecSuccess || removed == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(removed)) }
        }
    }
    static func eraseAccountPreferences(defaults: UserDefaults = .standard) {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("history-auto-v1:") ||
            key.hasPrefix("history-auto-server:") || key.hasPrefix("chatPins|") ||
            key == "openHistoryBackupForAccount" || key == "chatReminderRoute" {
            defaults.removeObject(forKey: key)
        }
    }

    static func erase(accountRoots: [URL], temporaryDirectory: URL) async throws {
        try await Task.detached(priority: .userInitiated) {
            for root in accountRoots where FileManager.default.fileExists(atPath: root.path) {
                try FileManager.default.removeItem(at: root)
            }
            let staged = try FileManager.default.contentsOfDirectory(at: temporaryDirectory, includingPropertiesForKeys: nil)
            for file in staged where file.lastPathComponent.hasPrefix("coco-photo-") ||
                file.lastPathComponent.hasPrefix("coco-video-") ||
                file.lastPathComponent.hasPrefix("coco-camera-") || file.lastPathComponent.hasPrefix("coco-gallery-") ||
                file.lastPathComponent.hasPrefix("coco-file-") || file.lastPathComponent.hasPrefix("voice_") ||
                file.lastPathComponent.hasPrefix("voice-join-") ||
                (file.pathExtension == "jpg" && UUID(uuidString: file.deletingPathExtension().lastPathComponent) != nil) {
                try FileManager.default.removeItem(at: file)
            }
        }.value
    }
}
