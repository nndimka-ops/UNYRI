import Foundation

@MainActor final class UnyriChatAppearanceStore {
    static let shared = UnyriChatAppearanceStore()
    private(set) var clearCount = 0
    func clearAccount() { clearCount += 1 }
}

// Compile the real AuthRepository with in-memory storage and a deliberately
// suspended revocation transport. No Keychain or network is touched.
struct DeviceInfo {
    static let currentAppVersion = "test"
    static let currentOSVersion = "test"
    let installationId = "installation"
    let platform = "ios"
    let deviceName = "test"
    let osVersion = "test"
    let appVersion = "test"
}

final class SessionStore {
    // Verbatim production policy; source_contract.py checks both blocks for drift.
    static func refreshMargin(accessToken: String?) -> TimeInterval {
        guard let token = accessToken, let lifetime = accessLifetime(token) else { return 30 }
        return min(30, max(1, lifetime / 12))
    }

    static func accessLifetime(_ accessToken: String) -> TimeInterval? {
        let parts = accessToken.split(separator: ".")
        guard parts.count == 3 else { return nil }
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let issued = (claims["iat"] as? NSNumber)?.doubleValue,
              let expires = (claims["exp"] as? NSNumber)?.doubleValue,
              expires > issued, expires - issued <= 7 * 24 * 3600 else { return nil }
        return expires - issued
    }

    var boundKeyPresent = true
    var failDeviceBindingErasure = false
    func eraseDeviceBinding() throws {
        // A failed key deletion retains the binding for a later cleanup retry.
        if failDeviceBindingErasure { throw APIError.network("test device key deletion failure") }
        boundKeyPresent = false
    }

    private var roomAccess: [String: String] = [:]
    func ownedRoomAccessSnapshot(server: URL) throws -> (binding: String, raw: String?)? {
        try requireReadableSession()
        let binding = server.absoluteString + "|fixture"
        return (binding, roomAccess[binding])
    }
    func saveOwnedRoomAccess(_ raw: String, server: URL, binding: String) throws {
        try requireReadableSession()
        guard binding == server.absoluteString + "|fixture", raw.utf8.count <= 12_288 else { throw APIError.missingSession }
        roomAccess[binding] = raw
    }
    struct PendingLogout { let id = UUID(); let origin: URL; let refreshToken: String }
    private var logoutQueue: [PendingLogout] = []
    func pendingLogouts() throws -> [PendingLogout] { logoutQueue }
    func enqueueLogout(origin: URL, refreshToken: String) throws { logoutQueue.append(PendingLogout(origin: origin, refreshToken: refreshToken)) }
    func finishPendingLogout(_ id: UUID) throws { logoutQueue.removeAll { $0.id == id } }

    private var screens: [String: Data] = [:]
    func deviceId() -> String? { "test-device" }
    func cachedScreen<T: Decodable>(_ key: String, owner: String, as type: T.Type) -> T? {
        screens[owner + "|" + key].flatMap { try? JSONDecoder().decode(type, from: $0) }
    }
    func saveScreen<T: Encodable>(_ key: String, owner: String, value: T) {
        screens[owner + "|" + key] = try? JSONEncoder().encode(value)
    }
    var readable = true
    func requireReadableSession() throws { if !readable { throw APIError.missingSession } }
    func requireReadableInstallation() throws { try requireReadableSession() }
    func retireSessionForMissingWorkspace(origin: URL? = nil) throws {
        try requireReadableSession()
        if let refresh, let origin { try enqueueLogout(origin: origin, refreshToken: refresh) }
        clearSession()
    }
    var token: String? = "old-access"
    var refresh: String? = "old-refresh"
    var historyBoundary: Int64?
    var historyChoicePending = false
    var context: HistoryContext?
    func historyContext() -> HistoryContext? { context }
    func saveHistoryContext(_ value: HistoryContext) throws { context = value; historyBoundary = historyBoundary ?? value.beforeSequence }
    func historyBeforeSequence() -> Int64? { historyBoundary }
    func needsHistoryChoice() -> Bool { historyChoicePending }
    func completeHistoryChoice() throws { historyChoicePending = false }
    func accessToken() -> String? { token }
    func refreshToken() -> String? { refresh }
    private var tokenExpiry = Date().addingTimeInterval(3600)
    func accessTokenExpiresAt() -> Date? { tokenExpiry }
    func expireAccessToken(matching rejected: String) {
        if token == rejected { tokenExpiry = .distantPast }
    }
    struct Tokens {
        let access: String?
        let expiresAt: Date?
        let refresh: String?
    }
    func readTokensAsync() async throws -> Tokens {
        try Task.checkCancellation()
        try requireReadableSession()
        let tokens = Tokens(access: accessToken(), expiresAt: accessTokenExpiresAt(), refresh: refreshToken())
        try Task.checkCancellation()
        return tokens
    }
    func currentUser() -> User? { nil }
    func clearSession() { try? eraseDeviceBinding(); roomAccess.removeAll(); screens.removeAll(); token = nil; refresh = nil; historyBoundary = nil; historyChoicePending = false; context = nil }
    func saveTokens(accessToken: String, accessTokenExpiresAt: Date, refreshToken: String) {
        token = accessToken; tokenExpiry = accessTokenExpiresAt; refresh = refreshToken
    }
    func refreshRequestId() throws -> String { "00000000-0000-4000-8000-000000000001" }
    func saveSession(_ response: AuthResponse) throws {
        token = response.accessToken; tokenExpiry = response.accessTokenExpiresAt; refresh = response.refreshToken
        historyBoundary = response.historyBeforeSequence
        historyChoicePending = (historyBoundary ?? 0) > 0
    }
    func saveUser(id: String, username: String, displayName: String) {}
    func saveDeviceId(_ id: String) {}
}

@MainActor
struct AuthAPI {
    let client: APIClient
    func signupRecovery(accessToken: String, password: String) async throws -> String { throw APIError.missingSession }
    static var requestedToken: String?
    static var revocation: CheckedContinuation<Void, Never>?
    func historyContext(accessToken: String) async throws -> HistoryContext { throw APIError.network("offline") }
    func login(_ request: LoginRequest, deviceProof: String? = nil) async throws -> AuthResponse { throw APIError.missingSession }
    func register(_ request: RegisterRequest, deviceProof: String? = nil) async throws -> AuthResponse { throw APIError.missingSession }
    func bindInstallationCredential(_ credential: String, accessToken: String) async throws { throw APIError.missingSession }
    func refresh(_ request: RefreshRequest, deviceProof: String? = nil) async throws -> RefreshResponse { throw APIError.missingSession }
    func logout(accessToken: String) async throws {
        Self.requestedToken = accessToken
        await withCheckedContinuation { Self.revocation = $0 }
    }
}

@main
struct OfflineLogoutContracts {
    @MainActor static func main() async {
        let deletionStore = SessionStore()
        var deletionErased = false
        let deletionAuth = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
            store: deletionStore, device: DeviceInfo(), clearLocalData: { deletionErased = true })
        do {
            try await deletionAuth.deleteAccount(password: "wrong") { _, _ in throw APIError.http(400) }
            fatalError("Rejected deletion unexpectedly succeeded")
        } catch {}
        precondition(!deletionErased && deletionStore.accessToken() == "old-access", "Rejected deletion cleared the session")
        do {
            try await deletionAuth.deleteAccount(password: "correct") { token, password in
                precondition(token == "old-access" && password == "correct")
                precondition(!deletionErased, "Local purge happened before server confirmation")
            }
        } catch { fatalError("Confirmed deletion failed: \(error)") }
        precondition(deletionErased && deletionStore.accessToken() == nil && deletionStore.refreshToken() == nil)
        guard case .loggedOut = deletionAuth.state else { fatalError("Confirmed deletion did not finish local logout") }
        let unavailableStore = SessionStore()
        unavailableStore.readable = false
        var incorrectlyErased = false
        let unavailableAuth = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
            store: unavailableStore, device: DeviceInfo(), clearLocalData: { incorrectlyErased = true })
        await unavailableAuth.bootstrap()
        guard case .sessionUnavailable = unavailableAuth.state else { fatalError("Keychain error must not become logout") }
        precondition(!incorrectlyErased && unavailableStore.refreshToken() == "old-refresh")
        unavailableStore.readable = true
        await unavailableAuth.bootstrap()
        guard case .loggedOut = unavailableAuth.state else { fatalError("Retry can resolve an actually missing identity") }
        precondition(incorrectlyErased)

        let store = SessionStore()
        var prepared = false
        var erased = false
        let auth = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
                                  store: store, device: DeviceInfo(), clearLocalData: {
            precondition(prepared, "Stop cache writers before deleting files")
            precondition(store.accessToken() == nil, "Invalidate credentials before erasure")
            erased = true
        })
        auth.prepareForCacheErasure = { prepared = true }
        await auth.logout()
        precondition(auth.sessionGeneration == 1)
        guard case .loggedOut = auth.state else { fatalError("Logout must be local") }
        precondition(store.accessToken() == nil && store.refreshToken() == nil)
        precondition(erased && auth.prepareForCacheErasure == nil)
        for _ in 0..<100 where AuthAPI.revocation == nil { await Task.yield() }
        precondition(AuthAPI.requestedToken == "old-access", "Revoke only the captured old token")
        precondition(AuthAPI.revocation != nil, "Server revocation is still pending after logout returns")
        // A late response must not erase a subsequent account's credentials.
        store.token = "new-access"
        store.refresh = "new-refresh"
        AuthAPI.revocation?.resume()
        AuthAPI.revocation = nil
        await Task.yield()
        precondition(store.token == "new-access" && store.refresh == "new-refresh")
        let failedStore = SessionStore()
        failedStore.token = nil
        var shouldFail = true
        let blocked = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
            store: failedStore, device: DeviceInfo(), clearLocalData: {
                if shouldFail { throw APIError.network("test deletion failure") }
            })
        await blocked.logout()
        guard case .cleanupFailed = blocked.state else { fatalError("Failed erasure must not be reported as clean logout") }
        precondition(failedStore.refreshToken() == nil)
        shouldFail = false
        await blocked.retryLocalCleanup()
        guard case .loggedOut = blocked.state else { fatalError("Successful retry must enable sign-in") }
        let bindingStore = SessionStore()
        // Keep the earlier suspended-revocation test independent of cleanup failure.
        bindingStore.token = nil
        bindingStore.failDeviceBindingErasure = true
        var bindingPrepared = false
        var bindingEraseCount = 0
        let bindingBlocked = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
            store: bindingStore, device: DeviceInfo(), clearLocalData: {
                bindingEraseCount += 1
                UnyriChatAppearanceStore.shared.clearAccount()
            })
        bindingBlocked.prepareForCacheErasure = { bindingPrepared = true }
        bindingStore.saveScreen("profile", owner: "fixture", value: "retained cache")
        let appearanceClears = UnyriChatAppearanceStore.shared.clearCount
        await bindingBlocked.logout()
        guard case .cleanupFailed = bindingBlocked.state else { fatalError("Failed device key deletion must block logout cleanup") }
        precondition(bindingStore.boundKeyPresent && bindingStore.refreshToken() == "old-refresh")
        precondition(!bindingPrepared && bindingEraseCount == 0 && bindingBlocked.prepareForCacheErasure != nil,
                     "Device key deletion must succeed before credentials, writers or account files are cleared")
        precondition(bindingStore.cachedScreen("profile", owner: "fixture", as: String.self) == "retained cache")
        precondition(UnyriChatAppearanceStore.shared.clearCount == appearanceClears)
        let blockedGeneration = bindingBlocked.sessionGeneration
        do {
            try await bindingBlocked.login(username: "next", password: "unused")
            fatalError("Failed device key deletion admitted a new sign-in")
        } catch {}
        precondition(bindingBlocked.sessionGeneration == blockedGeneration, "Cleanup failure must stop sign-in before a new session begins")
        precondition(bindingStore.boundKeyPresent && bindingStore.refreshToken() == "old-refresh")
        bindingStore.failDeviceBindingErasure = false
        await bindingBlocked.retryLocalCleanup()
        guard case .loggedOut = bindingBlocked.state else { fatalError("Device key deletion retry must finish logout") }
        precondition(!bindingStore.boundKeyPresent && bindingStore.refreshToken() == nil)
        precondition(bindingPrepared && bindingEraseCount == 1 && bindingBlocked.prepareForCacheErasure == nil)
        precondition(bindingStore.cachedScreen("profile", owner: "fixture", as: String.self) == nil)
        precondition(UnyriChatAppearanceStore.shared.clearCount == appearanceClears + 1)
        await bindingBlocked.retryLocalCleanup()
        precondition(bindingEraseCount == 1, "A completed cleanup must not run again")
        let revokedStore = SessionStore()
        var revokedErased = false
        let revoked = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
            store: revokedStore, device: DeviceInfo(), clearLocalData: { revokedErased = true })
        let rejection = APIError.server(code: "session_revoked", message: "Session revoked")
        await revoked.handleAuthenticatedError(rejection, generation: revoked.sessionGeneration + 1)
        precondition(revokedStore.token != nil && !revokedErased, "Foreign-generation rejection cannot clear credentials")
        await revoked.handleAuthenticatedError(.network("offline"), generation: revoked.sessionGeneration)
        await revoked.handleAuthenticatedError(.http(401), generation: revoked.sessionGeneration)
        precondition(revokedStore.token != nil && !revokedErased, "Connectivity or an ambiguous HTTP failure is not revocation")
        await revoked.handleAuthenticatedError(rejection, generation: revoked.sessionGeneration)
        guard case .loggedOut = revoked.state else { fatalError("REST revocation must return to login") }
        precondition(revokedStore.token == nil && revokedErased)
        for code in ["account_deleted", "account_suspended", "session_revoked", "invalid_refresh_token"] {
            let endingStore = SessionStore()
            var erased = false
            let ending = AuthRepository(api: AuthAPI(client: APIClient(baseURL: URL(string: "https://offline.invalid")!)),
                store: endingStore, device: DeviceInfo(), clearLocalData: { erased = true })
            await ending.handleAuthenticatedError(.server(code: code, message: "untrusted server text"), generation: ending.sessionGeneration)
            precondition(erased && endingStore.token == nil, "Every terminal code erases account credentials")
            precondition(ending.sessionEndMessage != nil, "Terminal cleanup must explain the sign-in boundary")
        }
        print("Offline logout contracts passed")
    }
}
