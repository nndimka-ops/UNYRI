import Combine
import Foundation
#if os(iOS) && canImport(UserNotifications)
import UserNotifications
#endif

/// Single source of truth for authentication state. Stage 1: auth only.
@MainActor
final class AuthRepository: ObservableObject {
    enum State {
        case restoring
        case loggedOut
        case loggedIn(User)
        case cleanupFailed(String)
        case sessionUnavailable
    }

    @Published private(set) var state: State = .restoring
    @Published private(set) var needsHistoryChoice = false
    @Published private(set) var historyContext: HistoryContext?
    // Non-secret UI boundary, hydrated only at session/context transitions.
    // Reading it during layout must never synchronously query Keychain.
    @Published private(set) var historyBeforeSequence: Int64?
    @Published private(set) var installationProtectionIssue: String?
    private var historyContextLoading = false

    private var api: AuthAPI
    #if os(iOS)
    var authenticatedServerURL: URL { api.client.baseURL }
    #endif
    private let store: SessionStore
    private let device: DeviceInfo
    private(set) var sessionGeneration: UInt64 = 0
    var willChangeSession: (() -> Void)?
    /// Backend device UUID, read from the current authenticated session only.
    /// Installation identifiers are different and cannot decide call ownership.
    var currentDeviceId: UUID? {
        guard case .loggedIn = state else { return nil }
        return store.deviceId().flatMap(UUID.init(uuidString:))
    }
    private var refreshTask: Task<String, Error>?
    private let clearLocalData: () async throws -> Void
    private let hasLocalAccountWorkspace: (String) throws -> Bool
    private let hasLocalInstallation: () throws -> Bool
    private let recordLocalInstallation: () throws -> Void
    private let prepareServerForBootstrap: (() throws -> URL)?
    var prepareForCacheErasure: (() async -> Void)?
    private var localCleanupTask: Task<Void, Never>?
    private let installationProofs: InstallationProofCoordinator?
    private var installationBindingTask: Task<Void, Never>?
    private var boundInstallationSession: String?
    private var installationRetryAt: TimeInterval = 0

    init(api: AuthAPI, store: SessionStore, device: DeviceInfo, clearLocalData: @escaping () async throws -> Void = {},
         hasLocalAccountWorkspace: @escaping (String) throws -> Bool = { _ in true },
         hasLocalInstallation: @escaping () throws -> Bool = { true },
         recordLocalInstallation: @escaping () throws -> Void = {},
         prepareServerForBootstrap: (() throws -> URL)? = nil,
         installationProofs: InstallationProofCoordinator? = nil) {
        self.api = api
        self.store = store
        self.device = device
        self.clearLocalData = clearLocalData
        self.hasLocalAccountWorkspace = hasLocalAccountWorkspace
        self.hasLocalInstallation = hasLocalInstallation
        self.recordLocalInstallation = recordLocalInstallation
        self.prepareServerForBootstrap = prepareServerForBootstrap
        self.installationProofs = installationProofs
    }

    private var offlineScreenOwner: String? {
        guard case .loggedIn(let user) = state else { return nil }
        return "\(AppConfig.storageOrigin)|\(user.id)|\(store.deviceId() ?? "")"
    }
    func cachedScreen<T: Decodable>(_ key: String, as type: T.Type) -> T? {
        guard let owner = offlineScreenOwner else { return nil }
        return store.cachedScreen(key, owner: owner, as: type)
    }
    func saveScreen<T: Encodable>(_ key: String, generation: UInt64, value: T) {
        guard generation == sessionGeneration, let owner = offlineScreenOwner else { return }
        store.saveScreen(key, owner: owner, value: value)
    }
    func ownedRoomAccessSnapshot(server: URL, generation: UInt64) throws -> (binding: String, raw: String?)? {
        guard generation == sessionGeneration, case .loggedIn = state, server == AppConfig.apiBaseURL else { return nil }
        return try store.ownedRoomAccessSnapshot(server: server)
    }
    func saveOwnedRoomAccess(_ raw: String, server: URL, binding: String, generation: UInt64) throws {
        guard generation == sessionGeneration, case .loggedIn = state, server == AppConfig.apiBaseURL else { throw APIError.missingSession }
        try store.saveOwnedRoomAccess(raw, server: server, binding: binding)
    }

    /// Only available before authentication. No old bearer/refresh token can
    /// be reused against a newly entered server.
    func selectServer(_ address: String) throws {
        guard case .loggedOut = state else { throw APIError.missingSession }
        let url = try AppConfig.normalizedServerURL(address)
        guard url != AppConfig.apiBaseURL else { return }
        _ = beginSessionChange()
        store.clearSession()
        api = AuthAPI(client: APIClient(baseURL: url))
        AppConfig.saveServer(url)
    }

    /// Called once at app start. Restores a stored session and refreshes the
    /// access token when needed. A definitive auth rejection returns to login;
    /// a transient network failure keeps the session for a later retry
    /// (same policy as Android).
    func bootstrap() async {
        switch state {
        case .restoring, .sessionUnavailable: break
        default: return
        }
        do {
            // Every entry point, including background history work and unlock
            // retry, must establish the old account's origin before workspace
            // checks can retire it or any credential is sent to a server.
            if let prepareServerForBootstrap {
                let origin = try prepareServerForBootstrap()
                if api.client.baseURL != origin { api = AuthAPI(client: APIClient(baseURL: origin)) }
            }
            try store.requireReadableSession()
            try store.requireReadableInstallation()
        }
        catch { state = .sessionUnavailable; return }
        let restoredUser = store.refreshToken() == nil ? nil : store.currentUser()
        // The installation marker survives logout/cache erasure but disappears on
        // app deletion. Legacy active installs migrate from their existing database.
        do {
            let marked = try hasLocalInstallation()
            let workspace = try restoredUser.map { try hasLocalAccountWorkspace($0.id) } ?? false
            if (!marked && !workspace) || (restoredUser != nil && !workspace) {
                _ = beginSessionChange()
                // Keychain may survive uninstall; the local installation marker
                // does not. Reset this separate non-account proof before marking
                // the new installation, or fail closed if Keychain is locked.
                try installationProofs?.resetForFreshInstallation()
                try store.retireSessionForMissingWorkspace()
                try recordLocalInstallation()
                await finishLocalSession()
                return
            }
            if !marked { try recordLocalInstallation() }
        } catch {
            state = .sessionUnavailable
            return
        }

        guard let user = restoredUser else {
            _ = beginSessionChange()
            await finishLocalSession()
            return
        }

        needsHistoryChoice = store.needsHistoryChoice()
        historyContext = store.historyContext()
        historyBeforeSequence = store.historyBeforeSequence()
        state = .loggedIn(user)
        do {
            _ = try await currentAccessToken()
            await ensureInstallationBinding()
        } catch {
            // currentAccessToken handles terminal errors for its own generation.
        }
    }

    func login(username: String, password: String) async throws {
        try await requireCacheCleanup()
        let generation = beginSessionChange()
        let proof = try await prepareInstallationProof(generation: generation)
        guard generation == sessionGeneration else { throw CancellationError() }
        let response = try await api.login(LoginRequest(
            username: username,
            password: password,
            installationId: device.installationId,
            devicePlatform: device.platform,
            deviceName: device.deviceName,
            osVersion: device.osVersion,
            appVersion: device.appVersion,
            installationCredential: proof
        ))
        guard generation == sessionGeneration else { throw CancellationError() }
        try persist(response)
        if proof != nil { boundInstallationSession = installationSessionKey }
    }

    func register(username: String, password: String, displayName: String) async throws {
        try await requireCacheCleanup()
        let generation = beginSessionChange()
        let proof = try await prepareInstallationProof(generation: generation)
        guard generation == sessionGeneration else { throw CancellationError() }
        let response = try await api.register(RegisterRequest(
            username: username,
            password: password,
            displayName: displayName,
            installationId: device.installationId,
            devicePlatform: device.platform,
            deviceName: device.deviceName,
            osVersion: device.osVersion,
            appVersion: device.appVersion,
            installationCredential: proof
        ))
        guard generation == sessionGeneration else { throw CancellationError() }
        try persist(response)
        if proof != nil { boundInstallationSession = installationSessionKey }
    }

    private var installationSessionKey: String? {
        guard let owner = currentUser?.id else { return nil }
        return "\(sessionGeneration)|\(api.client.baseURL.absoluteString)|\(owner)"
    }

    private func prepareInstallationProof(generation: UInt64) async throws -> String? {
        guard let installationProofs else { return nil }
        let origin = api.client.baseURL
        do {
            if origin.scheme?.lowercased() != "https" {
                #if DEBUG || UNYRI_ALPHA_LAN
                // The existing dev-only origin policy restricts HTTP to local
                // addresses. Keep emulator sign-in, but never send this proof.
                _ = try AppConfig.normalizedServerURL(origin.absoluteString)
                installationProtectionIssue = "Installation protection requires HTTPS. This development connection uses legacy sign-in."
                return nil
                #else
                throw InstallationProofError.invalidOrigin
                #endif
            }
            let proof = try await installationProofs.credential(origin: origin) { [weak self] in
                self?.sessionGeneration == generation && self?.api.client.baseURL == origin
            }
            guard generation == sessionGeneration, api.client.baseURL == origin else { throw CancellationError() }
            installationProtectionIssue = proof == nil ? "This server does not support installation protection." : nil
            return proof
        } catch is CancellationError { throw CancellationError() }
        catch {
            guard generation == sessionGeneration, api.client.baseURL == origin else { throw CancellationError() }
            installationProtectionIssue = "Could not verify this installation. Try again later."
            throw APIError.server(code: "installation_protection_unavailable", message: "Could not verify this installation. Try again later.")
        }
    }

    /// Existing sessions enroll without signing out. One bounded retry per minute
    /// while foreground; failures never claim protection or erase the account.
    func ensureInstallationBinding() async {
        guard installationProofs != nil, let identity = installationSessionKey,
              identity != boundInstallationSession else { return }
        if let task = installationBindingTask { await task.value; return }
        guard ProcessInfo.processInfo.systemUptime >= installationRetryAt else { return }
        let generation = sessionGeneration
        let origin = api.client.baseURL
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if generation == self.sessionGeneration { self.installationBindingTask = nil } }
            do {
                guard let proof = try await self.prepareInstallationProof(generation: generation) else {
                    if generation == self.sessionGeneration { self.boundInstallationSession = identity }
                    return
                }
                let token = try await self.currentAccessToken()
                guard generation == self.sessionGeneration, self.installationSessionKey == identity,
                      self.api.client.baseURL == origin else { return }
                try await self.api.bindInstallationCredential(proof, accessToken: token)
                guard generation == self.sessionGeneration, self.installationSessionKey == identity,
                      self.api.client.baseURL == origin else { return }
                self.boundInstallationSession = identity
                self.installationProtectionIssue = nil
            } catch {
                guard generation == self.sessionGeneration, self.installationSessionKey == identity else { return }
                self.installationRetryAt = ProcessInfo.processInfo.systemUptime + 60
                self.installationProtectionIssue = "Could not verify this installation. Try again later."
            }
        }
        installationBindingTask = task
        await task.value
    }

    func logout() async {
        do {
            if let refresh = store.refreshToken() {
                try store.enqueueLogout(origin: api.client.baseURL, refreshToken: refresh)
            }
        } catch { state = .sessionUnavailable; return }
        let token = store.accessToken()
        let logoutAPI = api
        _ = beginSessionChange()
        await finishLocalSession()

        if let token {
            // Best-effort server-side revocation; local state is already clear.
            Task { try? await logoutAPI.logout(accessToken: token) }
        }
    }

    private var drainingLogouts = false
    func retryPendingLogouts() async {
        guard !drainingLogouts else { return }
        drainingLogouts = true
        defer { drainingLogouts = false }
        guard let items = try? store.pendingLogouts() else { return }
        for item in items {
            if Task.isCancelled { return }
            do {
                try await APIClient(baseURL: item.origin, preventRedirects: true).revokePendingSession(item.refreshToken)
                try store.finishPendingLogout(item.id)
            } catch APIError.server(let code, _) where code == "invalid_refresh_token" {
                try? store.finishPendingLogout(item.id)
            } catch { /* Keep the original endpoint and credential for the next foreground retry. */ }
        }
    }

    /// The destructive server operation must succeed before local credentials
    /// disappear. A late response must never erase a successor account.
    func deleteAccount(password: String, request: (String, String) async throws -> Void) async throws {
        guard !password.isEmpty, password.utf8.count <= 1024 else {
            throw APIError.network(UnyriL10n.string("Enter your account password (up to 1024 bytes)."))
        }
        let generation = sessionGeneration
        let token = try await currentAccessToken()
        guard generation == sessionGeneration else { throw CancellationError() }
        try await request(token, password)
        guard generation == sessionGeneration else { throw CancellationError() }
        _ = beginSessionChange()
        await finishLocalSession()
    }

    /// Returns a usable access token, proactively refreshing it with a 30s
    /// safety margin when missing or about to expire (same policy as Android).
    func currentAccessToken() async throws -> String {
        if let task = refreshTask { return try await task.value }
        let generation = sessionGeneration
        // Coalesce the complete lookup, not just HTTP refresh. A burst of
        // authenticated requests must not perform repeated synchronous
        // Keychain IPC on MainActor while a chat is scrolling.
        let task = Task { @MainActor [self] () async throws -> String in
            defer { if generation == sessionGeneration { refreshTask = nil } }
            do {
                let tokens = try await store.readTokensAsync()
                try Task.checkCancellation()
                guard generation == sessionGeneration else { throw CancellationError() }
                if let token = tokens.access, let expiresAt = tokens.expiresAt,
                   Date() < expiresAt.addingTimeInterval(-30) { return token }
                guard let refreshToken = tokens.refresh else { throw APIError.missingSession }
                let refreshed = try await api.refresh(RefreshRequest(refreshToken: refreshToken, requestId: try store.refreshRequestId()))
                try Task.checkCancellation()
                guard generation == sessionGeneration else { throw CancellationError() }
                try store.saveTokens(accessToken: refreshed.accessToken,
                    accessTokenExpiresAt: refreshed.accessTokenExpiresAt, refreshToken: refreshed.refreshToken)
                return refreshed.accessToken
            } catch {
                if generation == sessionGeneration, let apiError = error as? APIError,
                   Self.isTerminalAuthFailure(apiError) {
                    willChangeSession?()
                    sessionGeneration &+= 1
                    refreshTask = nil
                    await finishLocalSession()
                }
                throw error
            }
        }
        refreshTask = task
        return try await task.value
    }

    private func beginSessionChange() -> UInt64 {
        willChangeSession?()
        sessionGeneration &+= 1
        refreshTask?.cancel()
        refreshTask = nil
        installationBindingTask?.cancel()
        installationBindingTask = nil
        boundInstallationSession = nil
        installationRetryAt = 0
        installationProtectionIssue = nil
        return sessionGeneration
    }

    /// A late response from a discarded account must never log out its successor.
    func handleAuthenticatedError(_ error: APIError, generation: UInt64) async {
        guard generation == sessionGeneration, Self.isTerminalAuthFailure(error) else { return }
        _ = beginSessionChange()
        await finishLocalSession()
    }

    /// Refresh rotates bearer/session IDs without changing the account's local
    /// generation. A late rejection of the old bearer cannot erase its successor.
    func handleAuthenticatedRequestError(_ error: APIError, generation: UInt64, requestToken: String) async {
        guard generation == sessionGeneration, currentUser != nil, Self.isTerminalAuthFailure(error) else { return }
        if let refreshTask { _ = try? await refreshTask.value }
        guard generation == sessionGeneration, currentUser != nil else { return }
        // Only the rare definitive rejection path reads Keychain here. Keep
        // this final comparison synchronous on MainActor: an awaited snapshot
        // could otherwise return S0 after refresh has already committed S1.
        guard store.accessToken() == requestToken else { return }
        _ = beginSessionChange()
        await finishLocalSession()
    }

    private func requireCacheCleanup() async throws {
        if let task = localCleanupTask { await task.value }
        if case .cleanupFailed(let message) = state { throw APIError.network(message) }
    }

    func retryLocalCleanup() async {
        guard case .cleanupFailed = state else { return }
        await finishLocalSession()
    }

    private func finishLocalSession() async {
        #if os(iOS) && canImport(UserNotifications)
        UnyriLocalNotificationPreferences.resetForLogout()
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        #endif
        UserDefaults.standard.removeObject(forKey: "chatReminderRoute")
        if let task = localCleanupTask { await task.value; return }
        // Credentials disappear first. If the process dies during erasure,
        // bootstrap retries deletion before allowing another login.
        store.clearSession()
        state = .restoring
        needsHistoryChoice = false
        historyContext = nil
        historyBeforeSequence = nil
        let prepare = prepareForCacheErasure
        let task = Task { @MainActor [self] in
            await prepare?()
            do {
                #if os(iOS)
                await UnyriCrashDiagnostics.shared.eraseForLogout()
                await PerformanceMonitor.shared.eraseForLogout()
                #endif
                try await clearLocalData()
                prepareForCacheErasure = nil
                state = .loggedOut
            } catch {
                state = .cleanupFailed("Could not remove local account data. Retry before signing in.")
            }
        }
        localCleanupTask = task
        await task.value
        localCleanupTask = nil
    }

    var currentUser: User? {
        if case .loggedIn(let user) = state { return user }
        return nil
    }

    func reconcileHistoryContext() async {
        guard currentUser != nil, historyContext == nil, !historyContextLoading else { return }
        historyContextLoading = true
        let generation = sessionGeneration
        defer { historyContextLoading = false }
        do {
            let token = try await currentAccessToken()
            guard generation == sessionGeneration else { return }
            let context = try await api.historyContext(accessToken: token)
            guard generation == sessionGeneration else { return }
            try store.saveHistoryContext(context)
            historyContext = store.historyContext()
            historyBeforeSequence = store.historyBeforeSequence()
        } catch {
            // Optional metadata must never clear history or prevent offline use.
            if let error = error as? APIError { await handleAuthenticatedError(error, generation: generation) }
        }
    }

    private func persist(_ response: AuthResponse) throws {
        try store.saveSession(response)
        needsHistoryChoice = store.needsHistoryChoice()
        historyContext = store.historyContext()
        historyBeforeSequence = store.historyBeforeSequence()
        state = .loggedIn(response.user)
    }

    func startWithoutRestoredHistory() throws {
        guard currentUser != nil else { throw APIError.missingSession }
        try store.completeHistoryChoice()
        needsHistoryChoice = false
    }

    /// Only a definitive, terminal auth rejection clears the session.
    static func isTerminalAuthFailure(_ error: APIError) -> Bool {
        if case .server(let code, _) = error {
            return code == "invalid_refresh_token" || code == "session_revoked"
        }
        return false
    }
}
