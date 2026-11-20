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

    @Published private(set) var state: State = .restoring {
        didSet {
            #if os(iOS)
            ownerSetupAvailable = false
            ownerSetupTask?.cancel()
            if currentUser != nil {
                ownerSetupTask = Task { @MainActor [weak self] in await self?.refreshOwnerSetupAvailability() }
            }
            #endif
            if let user = currentUser {
                // B-049 (owner 19 Nov): sign-up has no recovery gate; the key is created when wanted in
                // Settings -> «Восстановление аккаунта». Clear a flag an older build left behind.
                UserDefaults.standard.removeObject(forKey: signupRecoveryKey(user.id))
                needsRegistrationRecovery = false
                // The retained sign-up password belongs to one account; never carry it across.
                if signupPasswordOwner != user.id { signupPassword = nil; signupPasswordOwner = nil }
            } else { needsRegistrationRecovery = false; signupPassword = nil; signupPasswordOwner = nil }
            #if os(iOS)
            syncShareInboxAccount()
            if shareAccountKey == nil { UnyriIncomingFile.shared.close(discardBatch: false) }
            #endif
        }
    }
    #if os(iOS)
    @Published private(set) var ownerSetupAvailable = false
    private var ownerSetupTask: Task<Void, Never>?
    private var ownerSetupRevision: UInt64 = 0
    func refreshOwnerSetupAvailability() async {
        ownerSetupRevision &+= 1
        let revision = ownerSetupRevision
        ownerSetupAvailable = false
        guard currentUser != nil else { return }
        let generation = sessionGeneration
        let server = api.client.baseURL
        guard SelfHostingPolicy.origin(server.absoluteString, allowLocalHTTP: SelfHostingLinks.allowLocalHTTP) != nil else { return }
        do {
            let access = try await currentAccessToken()
            guard generation == sessionGeneration, !Task.isCancelled else { return }
            struct Availability: Decodable { let available: Bool }
            let reply = try await APIClient(baseURL: server, preventRedirects: true, ephemeral: true)
                .get("/auth/owner-setup", accessToken: access, as: Availability.self)
            guard generation == sessionGeneration, revision == ownerSetupRevision, !Task.isCancelled else { return }
            ownerSetupAvailable = reply.available
        } catch { /* Hidden on old servers, outages and retired bootstrap. Retry on Settings entry. */ }
    }
    func claimOwner(_ raw: String) async throws {
        guard let normalized = SelfHostingPolicy.ownerCode(raw), currentUser != nil else {
            throw APIError.server(code: "setup_invalid", message: "")
        }
        let generation = sessionGeneration
        let server = api.client.baseURL
        guard SelfHostingPolicy.origin(server.absoluteString, allowLocalHTTP: SelfHostingLinks.allowLocalHTTP) != nil else { throw APIError.invalidURL }
        let access = try await currentAccessToken()
        guard generation == sessionGeneration, !Task.isCancelled else { throw CancellationError() }
        struct Claim: Decodable { let ok: Bool; let role: String }
        let reply = try await APIClient(baseURL: server, preventRedirects: true, ephemeral: true)
            .post("/auth/owner-setup", accessToken: access, body: ["code": normalized], as: Claim.self)
        guard generation == sessionGeneration, !Task.isCancelled else { throw CancellationError() }
        guard reply.ok, reply.role == "owner" else { throw APIError.decoding("Owner claim response") }
        ownerSetupRevision &+= 1
        ownerSetupAvailable = false
    }
    var shareAccountKey: String? {
        currentUser.map { UnyriShareStore.accountKey(storageOrigin: AppConfig.storageOrigin, userID: $0.id) }
    }
    var shareInboxScope: UnyriShareStore.InboxScope? {
        shareAccountKey.map { .init(accountKey: $0, generation: sessionGeneration) }
    }
    private var sharePublication: Task<Void, Never>?
    private func invalidateShareInboxAccount() {
        sharePublication?.cancel(); sharePublication = nil
        UnyriShareStore.publishActiveBinding(nil)
    }
    func syncShareInboxAccount() {
        sharePublication?.cancel(); sharePublication = nil
        guard let scope = shareInboxScope else { UnyriShareStore.publishActiveBinding(nil); return }
        // Foreground refresh can keep this session's existing binding usable
        // while I/O runs. Session changes invalidate it synchronously below.
        if UnyriShareStore.activeBinding()?.accountKey != scope.accountKey { UnyriShareStore.publishActiveBinding(nil) }
        sharePublication = Task { @MainActor [weak self] in
            do {
                guard let self, !Task.isCancelled, self.shareInboxScope == scope else { return }
                let restored = try await ShareContainer.disk.perform {
                    try ShareContainer.restoreSnapshotAccount(scope.accountKey, root: ShareContainer.root())
                }
                guard !Task.isCancelled, self.shareInboxScope == scope else { return }
                if restored { NotificationCenter.default.post(name: ShareContainer.snapshotAuthorityChanged, object: nil) }
                let binding = try await UnyriShareStore.inboxIO.prepareAccount(scope.accountKey)
                guard !Task.isCancelled, self.shareInboxScope == scope else { return }
                UnyriShareStore.publishActiveBinding(binding)
            } catch { /* Retry at the next session/foreground/unlock event. */ }
        }
    }
    #endif
    @Published private(set) var sessionEndMessage: String?
    @Published private(set) var needsHistoryChoice = false
    @Published private(set) var needsRegistrationRecovery = false
    private var signupPassword: String?
    private var signupPasswordOwner: String?
    /// The gate creates the key automatically only while the sign-up password is still in memory.
    var hasSignupPassword: Bool { signupPassword != nil }
    private func signupRecoveryKey(_ user: String) -> String { "login2.save-recovery|" + api.client.baseURL.absoluteString + "|" + user }
    func issueSignupRecovery(password: String = "") async throws -> String {
        guard needsRegistrationRecovery, let owner = currentUser?.id else { throw APIError.missingSession }
        let generation = sessionGeneration
        let supplied = signupPassword ?? password
        signupPassword = nil; signupPasswordOwner = nil
        guard !supplied.isEmpty else { throw APIError.missingSession }
        let token = try await currentAccessToken()
        guard generation == sessionGeneration, currentUser?.id == owner else { throw CancellationError() }
        let code = try await api.signupRecovery(accessToken: token, password: supplied)
        guard generation == sessionGeneration, currentUser?.id == owner else { throw CancellationError() }
        return code
    }
    func discardSignupPassword() { signupPassword = nil; signupPasswordOwner = nil }

    func confirmSignupRecovery() {
        guard let owner = currentUser?.id, needsRegistrationRecovery else { return }
        UserDefaults.standard.removeObject(forKey: signupRecoveryKey(owner))
        signupPassword = nil; signupPasswordOwner = nil; needsRegistrationRecovery = false
    }
    @Published private(set) var historyContext: HistoryContext?
    // Non-secret UI boundary, hydrated only at session/context transitions.
    // Reading it during layout must never synchronously query Keychain.
    @Published private(set) var historyBeforeSequence: Int64?
    @Published private(set) var installationProtectionIssue: String?
    private var historyContextLoading = false

    #if os(iOS)


    /// Runs after live I/O is drained and before any session store is erased,
    /// including a launch-time 401 before AccountServices was ever initialized.
    private static func preserveIdentityPins(origin: String, storageOrigin: String, userID: String, installationID: String, keychain: SignalKeychainStore = SignalKeychainStore()) throws {
        let security = LocalSecurityDecisions.account(origin: origin, userID: userID)
        let owner = SignalNamespace.make(userId: userID, installationId: installationID)
        let selected = try SignalNamespaceBinding.resolve(origin: storageOrigin, owner: owner,
            legacyPrefix: "server." + Data(storageOrigin.utf8).base64EncodedString() + ".",
            read: { try keychain.data(forKey: $0) }, write: { try keychain.setData($1, forKey: $0) })
        let namespaces = [selected]
        let saved = try security.pins()
        let keys = try keychain.keys()
        for namespace in namespaces {
            let prefix = namespace + ":trusted_identity."
            for key in keys where key.hasPrefix(prefix) {
                guard let address = SignalStorageKeys.decodeAddress(String(key.dropFirst(prefix.count))),
                      !saved.contains(where: { $0.userId == address.name && $0.signalDeviceId == address.deviceId }),
                      let identity = try keychain.data(forKey: key) else { continue }
                let verified = try keychain.contains(namespace + ":verified_identity." + SignalStorageKeys.encodeAddress(name: address.name, deviceId: address.deviceId))
                try security.pin(user: address.name, signal: address.deviceId, identity: identity, verified: verified)
            }
        }
    }
    @Published var localSecurityWarning: String?
    var revokeOwnDeviceLocally: ((String) async throws -> Void)?
    var localSecurity: LocalSecurityDecisions? {
        currentUser.map { LocalSecurityDecisions.account(origin: api.client.baseURL.absoluteString, userID: $0.id) }
    }
    #endif
    private var api: AuthAPI
    #if os(iOS)
    var authenticatedServerURL: URL { api.client.baseURL }
    #endif
    private var store: SessionStore
    private let device: DeviceInfo
    private(set) var sessionGeneration: UInt64 = 0
    var willChangeSession: (() -> Void)?
    /// Multi-account host (UnyriAccountManager). Nil keeps the historical
    /// single-account behavior unchanged.
    weak var accounts: UnyriAccountSessionHost?
    /// True only while `switchAccount` begins its session change: the leaving
    /// account stays signed in, so its push registration is kept, not retired.
    private(set) var switchingAccount = false
    private var accountTransition = false
    private var cleanupActivatedAccount = false
    /// Between leaving one account and the next one's restore, no bearer is served.
    private var withholdCredentials = false
    /// The account slot this repository's session store is bound to.
    private var storeSlotID = UnyriActiveAccountSlot.current.id
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
    #if os(iOS)
    private var eraseSecurityAfterSignOut = false
    private var securityCleanup: (origin: String, storageOrigin: String, user: String, installation: String)?
    private struct DeletedSecurityCleanup: Codable {
        let origin: String
        let user: String
    }
    private static func deletedSecurityCleanupFile() throws -> URL {
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { throw CocoaError(.fileNoSuchFile) }
        return support.appendingPathComponent("COCOInstallation", isDirectory: true).appendingPathComponent("deleted-security-cleanup.json")
    }
    private static func queueDeletedSecurityCleanup(origin: String, user: String) throws {
        let file = try deletedSecurityCleanupFile()
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(DeletedSecurityCleanup(origin: origin, user: user)).write(to: file, options: .atomic)
        try LocalSecurityDecisions.account(origin: origin, userID: user).erase()
    }
    private static func retryDeletedSecurityCleanup() throws {
        let file = try deletedSecurityCleanupFile()
        guard FileManager.default.fileExists(atPath: file.path) else { return }
        let scope = try JSONDecoder().decode(DeletedSecurityCleanup.self, from: Data(contentsOf: file))
        try LocalSecurityDecisions.account(origin: scope.origin, userID: scope.user).erase()
        try FileManager.default.removeItem(at: file)
    }
    #endif
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
        #if os(iOS)
        UnyriShareStore.publishActiveBinding(nil)
        #endif
    }

    private var offlineScreenOwner: String? {
        guard case .loggedIn(let user) = state else { return nil }
        return "\(AppConfig.storageOrigin)|\(user.id)|\(store.deviceId() ?? "")"
    }
    /// Decoded offline screens, kept for the session generation. Each disk lookup is two
    /// synchronous Keychain reads plus JSON decoding on the main thread, and
    /// CallsRepository.visibleSince evaluates one per call row (~110 ms of main-thread
    /// time in a 75 s chat profile). A miss is remembered too; saveScreen updates it.
    private var offlineScreenMemory: (generation: UInt64, values: [String: Any?])?
    func cachedScreen<T: Decodable>(_ key: String, as type: T.Type) -> T? {
        guard case .loggedIn = state else { return nil }
        if let memory = offlineScreenMemory, memory.generation == sessionGeneration, let entry = memory.values[key] {
            return entry as? T
        }
        guard let owner = offlineScreenOwner else { return nil }
        let value = store.cachedScreen(key, owner: owner, as: type)
        rememberScreen(key, value)
        return value
    }
    func saveScreen<T: Encodable>(_ key: String, generation: UInt64, value: T) {
        guard generation == sessionGeneration, let owner = offlineScreenOwner else { return }
        store.saveScreen(key, owner: owner, value: value)
        rememberScreen(key, value)
    }
    private func rememberScreen(_ key: String, _ value: Any?) {
        var values = offlineScreenMemory?.generation == sessionGeneration ? (offlineScreenMemory?.values ?? [:]) : [:]
        values[key] = .some(value)
        offlineScreenMemory = (sessionGeneration, values)
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
        // An account switch restores its target itself, after its writers drained.
        guard !accountTransition else { return }
        do {
            #if os(iOS)
            // Retry before any old Signal writer can recreate account trust.
            try Self.retryDeletedSecurityCleanup()
            #endif
            // Every entry point, including background history work and unlock
            // retry, must establish the old account's origin before workspace
            // checks can retire it or any credential is sent to a server.
            if let prepareServerForBootstrap {
                let origin = try prepareServerForBootstrap()
                followActiveAccountSlot()
                if api.client.baseURL != origin { api = AuthAPI(client: APIClient(baseURL: origin)) }
            }
            try store.requireReadableSession()
            try store.requireReadableInstallation()
        }
        catch { state = .sessionUnavailable; return }
        let restoredUser = store.refreshToken() == nil ? nil : store.currentUser()
        #if canImport(UIKit)
        do { try AccountSecretScope.freezeSavedAccounts(restoredOwner: restoredUser?.id) }
        catch { state = .sessionUnavailable; return }
        #endif
        // The installation marker survives logout/cache erasure but disappears on
        // app deletion. Legacy active installs migrate from their existing database.
        do {
            let marked = try hasLocalInstallation()
            let workspace = try restoredUser.map { try hasLocalAccountWorkspace($0.id) } ?? false
            if marked, restoredUser != nil, !workspace, accounts?.sharesInstallation == true {
                // Another signed-in account shares this installation identity:
                // rotating it would orphan that account's Signal keys. Sign only
                // this account out (its server session is revoked when online).
                if let refresh = store.refreshToken() { try? store.enqueueLogout(origin: api.client.baseURL, refreshToken: refresh) }
                _ = beginSessionChange()
                await finishLocalSession()
                return
            }
            if (!marked && !workspace) || (restoredUser != nil && !workspace) {
                _ = beginSessionChange()
                // Keychain may survive uninstall; the local installation marker
                // does not. Reset this separate non-account proof before marking
                // the new installation, or fail closed if Keychain is locked.
                try installationProofs?.resetForFreshInstallation()
                try store.retireSessionForMissingWorkspace(origin: api.client.baseURL)
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
            // A reserved "Add account" slot has nothing to clean: show sign-in.
            if accounts?.activeSlotAwaitsSignIn == true { state = .loggedOut; return }
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
        if accounts?.alreadySignedIn(username: username, origin: api.client.baseURL) == true { throw Self.accountAlreadyAdded }
        let generation = beginSessionChange()
        let proof = try await prepareInstallationProof(generation: generation)
        guard generation == sessionGeneration else { throw CancellationError() }
        let request = LoginRequest(
            username: username,
            password: password,
            installationId: try device.installationId,
            devicePlatform: device.platform,
            deviceName: device.deviceName,
            osVersion: device.osVersion,
            appVersion: device.appVersion,
            installationCredential: proof
        )
        #if os(iOS)
        let response = try await store.loginWithDeviceKey(request, api: api)
        #else
        let response = try await api.login(request)
        #endif
        guard generation == sessionGeneration else { throw CancellationError() }
        try admitAddedAccount(response)
        try persist(response)
        if proof != nil { boundInstallationSession = installationSessionKey }
    }

    private static let accountAlreadyAdded = APIError.server(code: "account_already_added",
        message: "This account is already added on this device.")

    /// The same person on the same server is never added twice. The duplicate
    /// session just created is revoked; the existing account is not touched
    /// (the server keeps push tokens while another session of the device lives).
    private func admitAddedAccount(_ response: AuthResponse) throws {
        guard let accounts, !accounts.admits(user: response.user.id, origin: api.client.baseURL) else { return }
        let revoke = api
        let token = response.accessToken
        Task { try? await revoke.logout(accessToken: token) }
        throw Self.accountAlreadyAdded
    }

    /// Account switch. The leaving account stays signed in on this device: its
    /// Keychain slot, files and server session are untouched. Every account
    /// writer is stopped and drained exactly as before cache erasure, then
    /// `activate` moves the active slot and the ordinary bootstrap restores the
    /// target from its own storage. If `activate` throws, the previous account
    /// is restored the same way.
    func switchAccount(activate: @MainActor () throws -> Void) async throws {
        guard !accountTransition, localCleanupTask == nil else { throw CancellationError() }
        switch state {
        case .loggedIn, .loggedOut: break
        default: throw CancellationError()
        }
        accountTransition = true
        withholdCredentials = true
        switchingAccount = true
        _ = beginSessionChange()
        switchingAccount = false
        state = .restoring
        needsHistoryChoice = false
        historyContext = nil
        historyBeforeSequence = nil
        offlineScreenMemory = nil
        let prepare = prepareForCacheErasure
        prepareForCacheErasure = nil
        await prepare?()
        var failure: Error?
        do { try activate() } catch { failure = error }
        followActiveAccountSlot()
        accountTransition = false
        withholdCredentials = false
        await bootstrap()
        if let failure { throw failure }
    }

    /// Credentials and the server come only from the active account's slot.
    /// The installation id inside DeviceInfo is device-wide in every slot.
    private func followActiveAccountSlot() {
        let slot = UnyriActiveAccountSlot.current
        guard slot.id != storeSlotID, let accounts else { return }
        store = accounts.sessionStore(for: slot)
        storeSlotID = slot.id
        offlineScreenMemory = nil
        // Sign-in, selectServer and refresh use the new slot's server from now on.
        api = AuthAPI(client: APIClient(baseURL: AppConfig.apiBaseURL))
    }

    func register(username: String, password: String, displayName: String, inviteCode: String? = nil) async throws {
        try await requireCacheCleanup()
        let generation = beginSessionChange()
        let proof = try await prepareInstallationProof(generation: generation)
        guard generation == sessionGeneration else { throw CancellationError() }
        let request = RegisterRequest(
            inviteCode: inviteCode,
            username: username,
            password: password,
            displayName: displayName,
            installationId: try device.installationId,
            devicePlatform: device.platform,
            deviceName: device.deviceName,
            osVersion: device.osVersion,
            appVersion: device.appVersion,
            installationCredential: proof
        )
        #if os(iOS)
        let response = try await store.registerWithDeviceKey(request, api: api)
        #else
        let response = try await api.register(request)
        #endif
        guard generation == sessionGeneration else { throw CancellationError() }
        try admitAddedAccount(response)
        // No recovery step after sign-up (B-049): nothing is flagged and the password is not kept.
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
                if AppConfig.hasIdentityHTTPConsent(origin) {
                    installationProtectionIssue = "Installation protection requires HTTPS. This HTTP connection uses legacy sign-in."
                    return nil
                }
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
            } catch APIError.server(let code, _) where ["invalid_refresh_token", "session_revoked", "account_deleted", "account_suspended"].contains(code) {
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
        #if os(iOS)
        eraseSecurityAfterSignOut = true
        #endif
        _ = beginSessionChange()
        await finishLocalSession()
    }

    /// Returns a usable access token, proactively refreshing it with a 30s
    /// safety margin when missing or about to expire (same policy as Android).
    func currentAccessToken() async throws -> String {
        // No bearer while an account handover is in progress.
        guard !withholdCredentials else { throw APIError.missingSession }
        if let task = refreshTask { return try await task.value }
        let generation = sessionGeneration
        let origin = api.client.baseURL
        // Coalesce the complete lookup, not just HTTP refresh. A burst of
        // authenticated requests must not perform repeated synchronous
        // Keychain IPC on MainActor while a chat is scrolling.
        let task = Task { @MainActor [self] () async throws -> String in
            defer { if generation == sessionGeneration { refreshTask = nil } }
            do {
                let tokens = try await store.readTokensAsync()
                try Task.checkCancellation()
                guard generation == sessionGeneration else { throw CancellationError() }
                let refreshMargin = SessionStore.refreshMargin(accessToken: tokens.access)
                if let token = tokens.access, let expiresAt = tokens.expiresAt,
                   Date() < expiresAt.addingTimeInterval(-refreshMargin) {
                    accounts?.bearerIssued(token, origin: origin)
                    return token
                }
                guard let refreshToken = tokens.refresh else { throw APIError.missingSession }
                let refreshStarted = ProcessInfo.processInfo.systemUptime
                UnyriDiag.auth(.refreshStart)
                let refreshed: RefreshResponse
                do {
                    let request = RefreshRequest(refreshToken: refreshToken, requestId: try store.refreshRequestId())
                    #if os(iOS)
                    refreshed = try await store.refreshWithDeviceKey(request, api: api)
                    #else
                    refreshed = try await api.refresh(request)
                    #endif
                } catch {
                    UnyriDiag.auth(.refreshFailed, code: UnyriDiag.code(for: error),
                                   durationMs: (ProcessInfo.processInfo.systemUptime - refreshStarted) * 1000)
                    throw error
                }
                UnyriDiag.auth(.refreshOK, durationMs: (ProcessInfo.processInfo.systemUptime - refreshStarted) * 1000)
                try Task.checkCancellation()
                guard generation == sessionGeneration else { throw CancellationError() }
                try store.saveTokens(accessToken: refreshed.accessToken,
                    accessTokenExpiresAt: refreshed.accessTokenExpiresAt, refreshToken: refreshed.refreshToken)
                accounts?.bearerIssued(refreshed.accessToken, origin: origin)
                return refreshed.accessToken
            } catch {
                if generation == sessionGeneration, let apiError = error as? APIError,
                   Self.isTerminalAuthFailure(apiError) {
                    recordTerminalFailure(apiError)
                    UnyriDiag.auth(.terminalLogout, code: UnyriDiag.code(for: apiError))
                    #if os(iOS)
                    invalidateShareInboxAccount()
                    UnyriIncomingFile.shared.close(discardBatch: false)
                    #endif
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

    #if os(iOS)
    /// A legacy access token must enroll its key through refresh before its first
    /// signed upgrade. The signature is produced off MainActor by Security.
    func webSocketCredentials() async throws -> (token: String, proof: String) {
        let generation = sessionGeneration
        do {
            var token = try await currentAccessToken()
            guard generation == sessionGeneration else { throw CancellationError() }
            if try store.deviceBindingSessionID() != DeviceBoundProof.accessSession(token) {
                store.expireAccessToken(matching: token)
                token = try await currentAccessToken()
            }
            guard generation == sessionGeneration else { throw CancellationError() }
            let proof = try await store.webSocketDeviceProof(token: token)
            guard generation == sessionGeneration else { throw CancellationError() }
            return (token, proof)
        } catch let error as APIError {
            await handleAuthenticatedError(error, generation: generation)
            throw error
        }
    }

    func correctDeviceProofClock(serverTime: Int64) {
        store.deviceBindingClock.correct(serverTime: serverTime)
    }
    #endif

    private func beginSessionChange() -> UInt64 {
        #if os(iOS)
        localSecurityWarning = nil; revokeOwnDeviceLocally = nil
        ownerSetupTask?.cancel(); ownerSetupTask = nil; ownerSetupRevision &+= 1; ownerSetupAvailable = false
        UnyriShareLifecycle.retire?()
        invalidateShareInboxAccount()
        UnyriIncomingFile.shared.close(discardBatch: false)
        #endif
        willChangeSession?()
        #if os(iOS)
        // Remembered row heights hold the old account's message content.
        ChatTimelineHeightMemory.shared.forgetAll()
        #endif
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
        recordTerminalFailure(error)
        _ = beginSessionChange()
        await finishLocalSession()
    }

    /// Refresh rotates bearer/session IDs without changing the account's local
    /// generation. A late rejection of the old bearer cannot erase its successor.
    func handleAuthenticatedRequestError(_ error: APIError, generation: UInt64, requestToken: String) async {
        guard generation == sessionGeneration, currentUser != nil else { return }
        if Self.isExpiredBearer(error) {
            UnyriDiag.auth(.bearerRejected, status: 401)
            accessTokenRejected(requestToken)
            return
        }
        guard Self.isTerminalAuthFailure(error) else { return }
        if let refreshTask { _ = try? await refreshTask.value }
        guard generation == sessionGeneration, currentUser != nil else { return }
        // Only the rare definitive rejection path reads Keychain here. Keep
        // this final comparison synchronous on MainActor: an awaited snapshot
        // could otherwise return S0 after refresh has already committed S1.
        guard store.accessToken() == requestToken else { return }
        recordTerminalFailure(error)
        _ = beginSessionChange()
        await finishLocalSession()
    }

    private func recordTerminalFailure(_ error: APIError) {
        guard case .server(let code, _) = error else { return }
        sessionEndMessage = APIError.localizedServerMessage(code: code == "invalid_refresh_token" ? "session_revoked" : code, message: "Session ended")
        #if os(iOS)
        if code == "account_deleted" { eraseSecurityAfterSignOut = true }
        #endif
    }

    /// A wake is only a hint. Only a terminal authenticated refresh erases data.
    func checkSessionNow() async {
        if case .restoring = state { await bootstrap() }
        guard currentUser != nil else { return }
        if let token = store.accessToken() { store.expireAccessToken(matching: token) }
        _ = try? await currentAccessToken()
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
        do { try store.eraseDeviceBinding() }
        catch { state = .cleanupFailed("Could not remove local account data. Retry cleanup before signing in."); return }
        // Multi-account: only this account's data and notifications go and the
        // next signed-in account becomes active. Nil host = historical path.
        let endingUser = currentUser?.id ?? store.currentUser()?.id
        #if os(iOS)
        let diagnosticOwner = endingUser.map { authenticatedServerURL.absoluteString + "|" + $0 }
        #endif
        let ending = accounts?.endingAccount(user: endingUser)
        #if os(iOS)
        if securityCleanup == nil, let endingUser {
            do {
                securityCleanup = (api.client.baseURL.absoluteString, AppConfig.storageOrigin, endingUser, try store.durableInstallationId())
            } catch { state = .sessionUnavailable; return }
        }
        #endif
        #if os(iOS)
        // Preserve legacy trust before removing the only durable account/user
        // metadata. A process death before the later drain/export must not lose it.
        if let scope = securityCleanup, !eraseSecurityAfterSignOut {
            do { try Self.preserveIdentityPins(origin: scope.origin, storageOrigin: scope.storageOrigin, userID: scope.user, installationID: scope.installation) }
            catch { state = .cleanupFailed("Could not remove local account data. Retry cleanup before signing in."); return }
        }
        #endif
        // Another signed-in account stays: what it owns is not erased here.
        let lastAccount = accounts?.sharesInstallation != true
        #if os(iOS) && canImport(UserNotifications)
        UnyriLocalNotificationPreferences.resetForLogout(owner: ending?.user ?? endingUser, lastAccount: lastAccount)
        if ending == nil {
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        }
        UNUserNotificationCenter.current().setBadgeCount(0) { _ in }
        #endif
        UserDefaults.standard.removeObject(forKey: "chatReminderRoute")
        #if os(iOS)
        UnyriRecentFiles.eraseAll()
        ChatTimelineHeightMemory.shared.forgetAll()
        // Device-wide played markers (opaque ids) stay while another account uses them.
        // Transcripts are per account: with another account signed in, only the ending
        // account's go, together with the rest of its data (UnyriAccountPurger).
        if lastAccount {
            UnyriVoicePlayedStore.eraseAll()
            VoiceTranscriber.eraseAll()
        }
        #endif
        if let task = localCleanupTask { await task.value; return }
        #if os(iOS)
        if eraseSecurityAfterSignOut, let scope = securityCleanup {
            do { try Self.queueDeletedSecurityCleanup(origin: scope.origin, user: scope.user) }
            catch { state = .cleanupFailed("Could not remove local account data. Retry cleanup before signing in."); return }
        }
        #endif
        // Credentials disappear first. If the process dies during erasure,
        // bootstrap retries deletion before allowing another login.
        store.clearSession()
        state = .restoring
        needsHistoryChoice = false
        historyContext = nil
        historyBeforeSequence = nil
        let prepare = prepareForCacheErasure
        cleanupActivatedAccount = false
        let task = Task { @MainActor [self] in
            await prepare?()
            do {
                #if os(iOS)
                if let scope = securityCleanup {
                    if eraseSecurityAfterSignOut { try LocalSecurityDecisions.account(origin: scope.origin, userID: scope.user).erase() }
                    else { try Self.preserveIdentityPins(origin: scope.origin, storageOrigin: scope.storageOrigin, userID: scope.user, installationID: scope.installation) }
                }
                if let diagnosticOwner { await AutomaticDiagnosticReports.shared.eraseForLogout(owner: diagnosticOwner) }
                await UnyriCrashDiagnostics.shared.eraseForLogout()
                await PerformanceMonitor.shared.eraseForLogout()
                #endif
                if let ending, let accounts {
                    cleanupActivatedAccount = try await accounts.finishSignOut(ending) != nil
                } else {
                    try await clearLocalData()
                }
                prepareForCacheErasure = nil
                #if os(iOS)
                if eraseSecurityAfterSignOut { try Self.retryDeletedSecurityCleanup() }
                securityCleanup = nil; eraseSecurityAfterSignOut = false
                #endif
                // Also after the last account: sign-in must use the primary slot and its server.
                if cleanupActivatedAccount { withholdCredentials = true }
                followActiveAccountSlot()
                if !cleanupActivatedAccount { state = .loggedOut }
            } catch {
                // The localized key (Russian and others exist in Localizable.xcstrings).
                state = .cleanupFailed("Could not remove local account data. Retry cleanup before signing in.")
            }
        }
        localCleanupTask = task
        await task.value
        localCleanupTask = nil
        if cleanupActivatedAccount {
            cleanupActivatedAccount = false
            withholdCredentials = false
            await bootstrap()
        }
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
        accounts?.bearerIssued(response.accessToken, origin: api.client.baseURL)
        needsHistoryChoice = store.needsHistoryChoice()
        historyContext = store.historyContext()
        historyBeforeSequence = store.historyBeforeSequence()
        sessionEndMessage = nil
        state = .loggedIn(response.user)
    }

    func startWithoutRestoredHistory() throws {
        guard currentUser != nil else { throw APIError.missingSession }
        try store.completeHistoryChoice()
        needsHistoryChoice = false
    }

    /// "unauthorized" means the server could not accept the bearer itself (expired by
    /// its clock). It is not a logout: the session is still valid, so refresh.
    static func isExpiredBearer(_ error: APIError) -> Bool {
        if case .server(let code, _) = error { return code == "unauthorized" }
        if case .http(let status) = error { return status == 401 }
        return false
    }

    /// The next `currentAccessToken()` refreshes instead of resending this bearer.
    func accessTokenRejected(_ token: String) {
        guard currentUser != nil, refreshTask == nil, !token.isEmpty else { return }
        store.expireAccessToken(matching: token)
    }

    /// Only a definitive, terminal auth rejection clears the session.
    static func isTerminalAuthFailure(_ error: APIError) -> Bool {
        if case .server(let code, _) = error {
            return ["invalid_refresh_token", "session_revoked", "account_deleted", "account_suspended"].contains(code)
        }
        return false
    }
}

/// The account whose local session is ending, captured before its credentials
/// are cleared (its slot's server preference still resolves its folder).
struct UnyriEndingAccount: Equatable {
    let slot: UnyriAccountSlot
    let user: String?
    let storageOrigin: String
}

// MARK: - AuthRepository host

/// What AuthRepository needs from the account list. Nil host = single account.
@MainActor
protocol UnyriAccountSessionHost: AnyObject {
    /// The active slot is a reserved "Add account" slot (no finished sign-in yet).
    var activeSlotAwaitsSignIn: Bool { get }
    /// Another signed-in account shares this installation's identity.
    var sharesInstallation: Bool { get }
    func alreadySignedIn(username: String, origin: URL) -> Bool
    func admits(user: String, origin: URL) -> Bool
    func endingAccount(user: String?) -> UnyriEndingAccount?
    /// Erases the ending account and selects the next one. Nil = no account left.
    func finishSignOut(_ ending: UnyriEndingAccount) async throws -> UnyriAccountSlot?
    /// A session store bound to `slot` for its whole lifetime.
    func sessionStore(for slot: UnyriAccountSlot) -> SessionStore
    /// Records which server issued an access token (APIClient refuses to send it elsewhere).
    func bearerIssued(_ token: String, origin: URL)
}
