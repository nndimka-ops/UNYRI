import Foundation

@MainActor final class UnyriChatAppearanceStore {
    static let shared = UnyriChatAppearanceStore()
    private(set) var clearCount = 0
    func clearAccount() { clearCount += 1 }
}

// Compile the real AuthRepository with in-memory storage and a deliberately
// suspended revocation transport. No Keychain or network is touched.
struct DeviceInfo {
    let installationId = "installation"
    let platform = "ios"
    let deviceName = "test"
    let osVersion = "test"
    let appVersion = "test"
}

final class SessionStore {
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
    func retireSessionForMissingWorkspace() throws { try requireReadableSession(); clearSession() }
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
    func accessTokenExpiresAt() -> Date? { Date().addingTimeInterval(3600) }
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
    func clearSession() { roomAccess.removeAll(); screens.removeAll(); token = nil; refresh = nil; historyBoundary = nil; historyChoicePending = false; context = nil }
    func saveTokens(accessToken: String, accessTokenExpiresAt: Date, refreshToken: String) {
        token = accessToken; refresh = refreshToken
    }
    func refreshRequestId() throws -> String { "00000000-0000-4000-8000-000000000001" }
    func saveSession(_ response: AuthResponse) throws {
        token = response.accessToken; refresh = response.refreshToken
        historyBoundary = response.historyBeforeSequence
        historyChoicePending = (historyBoundary ?? 0) > 0
    }
    func saveUser(id: String, username: String, displayName: String) {}
    func saveDeviceId(_ id: String) {}
}

@MainActor
struct AuthAPI {
    let client: APIClient
    static var requestedToken: String?
    static var revocation: CheckedContinuation<Void, Never>?
    func historyContext(accessToken: String) async throws -> HistoryContext { throw APIError.network("offline") }
    func login(_ request: LoginRequest) async throws -> AuthResponse { throw APIError.missingSession }
    func register(_ request: RegisterRequest) async throws -> AuthResponse { throw APIError.missingSession }
    func bindInstallationCredential(_ credential: String, accessToken: String) async throws { throw APIError.missingSession }
    func refresh(_ request: RefreshRequest) async throws -> RefreshResponse { throw APIError.missingSession }
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
        print("Offline logout contracts passed")
    }
}
