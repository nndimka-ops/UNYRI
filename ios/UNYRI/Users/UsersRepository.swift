import Combine
import Foundation
import CryptoKit

/// An owner-only hint. Canonical profile fields are fetched over the existing API.
struct SelfProfileInvalidationHint: Equatable {
    let userID: String
    let version: Int64
    static func parse(_ data: Data) -> SelfProfileInvalidationHint? {
        struct Envelope: Decodable { let type: String; let user_id: UUID; let profile_version: Int64 }
        guard let value = try? JSONDecoder().decode(Envelope.self, from: data),
              value.type == "profile.changed", value.profile_version >= 0 else { return nil }
        return SelfProfileInvalidationHint(userID: value.user_id.uuidString.lowercased(), version: value.profile_version)
    }
}

/// Authenticated profile + search access. Reuses `AuthRepository` for token
/// refresh (never duplicates refresh logic) and caches resolved avatar URLs.
@MainActor
final class UsersRepository: ObservableObject {
    private let api: UsersAPI
    private let auth: AuthRepository
    private var avatarURLCache: [String: (url: URL, expiresAt: Date)] = [:]
    private let generation: UInt64
    private let directoryOrigin: String
    private let directoryOwner: String
    private var uploadedAvatar: (data: Data, key: String)?
    private var avatarTasks: [String: Task<URL?, Never>] = [:]
    private let accountAvatarSlot: String
    private let accountAvatarOwner: String
    private var accountAvatarTicket: AccountAvatarStore.Ticket?
    private var accountAvatarWritten: String?
    private var accountAvatarTask: Task<Void, Never>?
    private var clearingAvatars = false
    private var cacheWrites: [UUID: Task<Void, Error>] = [:]
    private var profileMutationRevision: UInt64 = 0
    @Published private(set) var ownProfile: UserProfile?
    @Published private(set) var ownIdentity: OwnerIdentity?
    // Endpoint capability changes discovery UI only, never peer-login visibility.
    @Published private(set) var legacyIdentityServer = false
    private var identityRead: (id: UUID, task: Task<OwnerIdentity, Error>)?

    @discardableResult func getIdentity() async throws -> OwnerIdentity {
        let owner = try currentProfileOwner()
        let flight: (id: UUID, task: Task<OwnerIdentity, Error>)
        if let pending = identityRead { flight = pending }
        else {
            let id = UUID()
            let task = Task { @MainActor [weak self] () async throws -> OwnerIdentity in
                guard let self else { throw CancellationError() }
                defer { if self.identityRead?.id == id { self.identityRead = nil } }
                return try await self.api.identity(accessToken: self.currentToken())
            }
            flight = (id, task); identityRead = flight
        }
        let result: OwnerIdentity
        do {
            result = try await flight.task.value
        } catch {
            guard try currentProfileOwner() == owner else { throw CancellationError() }
            let status: Int?
            if let failure = error as? APIStatusError { status = failure.status }
            else if let failure = error as? APIError, case .http(let code) = failure { status = code }
            else { status = nil }
            if ownIdentity == nil, status == 404 || status == 405 { legacyIdentityServer = true }
            throw error
        }
        guard try currentProfileOwner() == owner, result.userId.lowercased() == owner,
              PublicIdentityPolicy.number(result.unyriNumber)?.count == 9,
              PublicIdentityPolicy.origin(result.publicOrigin) != nil else { throw CancellationError() }
        legacyIdentityServer = false
        if (ownIdentity.map({ $0.revision <= result.revision }) ?? true), ownIdentity != result { ownIdentity = result }
        return ownIdentity ?? result
    }

    func updateIdentity(_ request: IdentityUpdate) async throws -> OwnerIdentity {
        let owner = try currentProfileOwner()
        let result = try await api.updateIdentity(accessToken: try await currentToken(), request: request)
        guard try currentProfileOwner() == owner, result.userId.lowercased() == owner else { throw CancellationError() }
        legacyIdentityServer = false
        if (ownIdentity.map({ $0.revision <= result.revision }) ?? true), ownIdentity != result { ownIdentity = result }
        return ownIdentity ?? result
    }

    enum IdentityLookupError: Error { case profileUnavailable }

    func lookup(_ target: PublicIdentityPolicy.Target) async throws -> DiscoveredProfile {
        let owner = try currentProfileOwner(), privacy = identityPrivacyRevision
        let result = try await api.lookup(accessToken: try await currentToken(), target: target)
        guard try currentProfileOwner() == owner, privacy == identityPrivacyRevision else { throw CancellationError() }
        guard !locallyBlockedUsers.contains(result.id.lowercased()) else { throw IdentityLookupError.profileUnavailable }
        return result
    }

    func publicProfile(_ id: String) async throws -> DiscoveredProfile {
        let owner = try currentProfileOwner(), privacy = identityPrivacyRevision
        let result = try await api.publicProfile(accessToken: try await currentToken(), userID: id)
        guard try currentProfileOwner() == owner, privacy == identityPrivacyRevision,
              !locallyBlockedUsers.contains(result.id) else { throw CancellationError() }
        return result
    }
    @Published private(set) var shareAvatarRevision: UInt64 = 0
    private var profileRequest: (id: UUID, revision: UInt64, task: Task<UserProfile, Error>)?
    private var profileSyncTask: Task<Void, Never>?
    private var profileSyncID: UUID?
    private var profileHintRevision: UInt64 = 0
    private var wantedProfileVersion: Int64 = -1
    private var ownProfileRetired = false
    @Published private(set) var blockedAccountsRevision: UInt64 = 0
    var onAccountBlockChange: ((String, Bool) -> Void)?
    private var blockMutations = Set<String>()
    private var identityPrivacyRevision: UInt64 = 0
    private var locallyBlockedUsers = Set<String>()
    private var invalidatingAvatarIDs = Set<String>()

    var blockedAccountScope: String? {
        guard !ownProfileRetired, generation == auth.sessionGeneration, auth.currentUser?.id == directoryOwner,
              AppConfig.storageOrigin == directoryOrigin, !directoryOwner.isEmpty else { return nil }
        return "\(generation)|\(directoryOrigin)|\(directoryOwner)"
    }
    var blockedAccountOwnerID: String { directoryOwner }

    func blockedAccounts(query: String, cursor: String?) async throws -> BlockedAccountsPage {
        guard let scope = blockedAccountScope else { throw CancellationError() }
        guard let security = auth.localSecurity else { throw CancellationError() }
        let localIDs = try security.blockedUsers()
        let result = (try? await api.blockedAccounts(accessToken: try await currentToken(), query: query, cursor: cursor)) ?? BlockedAccountsPage(items: [], nextCursor: nil)
        try Task.checkCancellation()
        guard blockedAccountScope == scope else { throw CancellationError() }
        var items = result.items.filter { localIDs.contains($0.id.lowercased()) }
        if cursor == nil {
            let seen = Set(items.map(\.id))
            for id in localIDs.sorted() where !seen.contains(id) && (query.isEmpty || id.localizedCaseInsensitiveContains(query)) {
                items.append(BlockedAccount(user: PublicUser(id: id, username: id, displayName: id, bio: nil, avatarUrl: nil,
                    profileVersion: 0, avatarVersion: 0, hasAvatar: false), blockedAt: ""))
            }
        }
        return BlockedAccountsPage(items: items, nextCursor: result.nextCursor)
    }

    func setAccountBlocked(userID: String, blocked: Bool) async throws {
        guard let scope = blockedAccountScope, userID != directoryOwner else { throw CancellationError() }
        guard blockMutations.insert(userID).inserted else { throw APIError.http(409) }
        defer { blockMutations.remove(userID) }
        guard let security = auth.localSecurity else { throw CancellationError() }
        try security.setBlocked(userID, blocked: blocked)
        accountBlockAcknowledged(userID: userID, blocked: blocked)
        onAccountBlockChange?(userID, blocked)
        // A failed server projection cannot undo the user's durable local action.
        try? await api.setBlocked(accessToken: try await currentToken(), userID: userID, blocked: blocked)
        guard blockedAccountScope == scope else { throw CancellationError() }
    }

    func accountBlockAcknowledged(userID: String, blocked: Bool) {
        guard blockedAccountScope != nil else { return }
        identityPrivacyRevision &+= 1
        if blocked { locallyBlockedUsers.insert(userID); invalidateKnownIdentity(userID) }
        else { locallyBlockedUsers.remove(userID) }
        blockedAccountsRevision &+= 1
        // The server hides blocked contacts; an unblock brings them back.
        if blocked { contactsStore?.hide(userID) } else { contactsStore?.markStale() }
    }

    func accountBlocksChanged() {
        guard blockedAccountScope != nil else { return }
        identityPrivacyRevision &+= 1
        locallyBlockedUsers = (try? auth.localSecurity?.blockedUsers()) ?? locallyBlockedUsers
        blockedAccountsRevision &+= 1
        contactsStore?.markStale()
    }

    // MARK: Contacts

    private var contactsStore: ContactsStore?
    private var contactChanges: AnyCancellable?
    /// One address book for this repository's account/server/session scope.
    var contacts: ContactsStore {
        if let contactsStore { return contactsStore }
        let store = ContactsStore(service: self)
        contactsStore = store
        return store
    }

    func startContactMembershipSync() {
        if contactChanges == nil {
            contactChanges = NotificationCenter.default.publisher(for: .login2ContactChanged).sink { [weak self] note in
                let owner = note.userInfo?["owner"] as? String
                Task { @MainActor [weak self] in
                    guard let self, self.blockedAccountScope != nil, owner == self.directoryOwner else { return }
                    self.contacts.markStale()
                }
            }
        }
        contacts.startSession()
    }

    /// Single membership gate shared by calls and files. A future mutual-circle
    /// policy can replace this without changing either transport's bindings.
    func isKnownPeerContact(_ userID: String) -> Bool {
        guard blockedAccountScope != nil, !ownProfileRetired else { return false }
        return contactsStore?.isKnownPeerContact(userID) ?? false
    }

    func refreshContactMembership() { contacts.markStale() }

    func contactsPage(cursor: String?) async throws -> ContactsPage {
        guard let scope = blockedAccountScope else { throw CancellationError() }
        let result = try await api.contacts(accessToken: try await currentToken(), cursor: cursor)
        try Task.checkCancellation()
        guard blockedAccountScope == scope else { throw CancellationError() }
        // A block acknowledged locally can be ahead of the server's page.
        return ContactsPage(items: result.items.filter { !locallyBlockedUsers.contains($0.id) }, nextCursor: result.nextCursor)
    }

    func contactStatus(userID: String) async throws -> Bool {
        guard let scope = blockedAccountScope, userID != directoryOwner else { throw CancellationError() }
        let result = try await api.contactStatus(accessToken: try await currentToken(), userID: userID)
        try Task.checkCancellation()
        guard blockedAccountScope == scope else { throw CancellationError() }
        // Same visibility as the list: a block acknowledged here hides the contact.
        return result && !locallyBlockedUsers.contains(userID)
    }

    func setContact(userID: String, contact: Bool) async throws {
        guard let scope = blockedAccountScope, userID != directoryOwner else { throw CancellationError() }
        try await api.setContact(accessToken: try await currentToken(), userID: userID, contact: contact)
        guard blockedAccountScope == scope else { throw CancellationError() }
    }

    func peerDeleted(_ userID: String) {
        guard blockedAccountScope != nil else { return }
        identityPrivacyRevision &+= 1
        invalidateKnownIdentity(userID)
        contactsStore?.hide(userID)
        // Also erase avatars loaded during earlier launches, whose file paths
        // are no longer present in the current in-memory URL index.
        Task { [weak self] in _ = await self?.clearAvatarCache() }
    }

    private func invalidateKnownIdentity(_ userID: String) {
        guard let scope = blockedAccountScope else { return }
        invalidatingAvatarIDs.insert(userID)
        let prefix = userID + ":"
        let files = avatarURLCache.filter { $0.key.hasPrefix(prefix) }.values.map(\.url).filter(\.isFileURL)
        for key in avatarURLCache.keys.filter({ $0.hasPrefix(prefix) }) { avatarURLCache[key] = nil }
        for key in avatarTasks.keys.filter({ $0.hasPrefix(prefix) }) { avatarTasks[key]?.cancel() }
        let pending = Array(cacheWrites.values)
        let directoryFile = directoryCacheFile
        let ticket = UUID()
        let purge = Task.detached(priority: .utility) {
            // Drain old first-page/avatar writes before removing this identity's
            // known cache. Future directory responses are revision-fenced below.
            for write in pending { _ = try? await write.value }
            for file in Set(files + [directoryFile]) where FileManager.default.fileExists(atPath: file.path) {
                try FileManager.default.removeItem(at: file)
            }
        }
        cacheWrites[ticket] = purge
        Task { [weak self] in
            _ = try? await purge.value
            guard let self else { return }
            self.cacheWrites[ticket] = nil
            if self.blockedAccountScope == scope { self.invalidatingAvatarIDs.remove(userID) }
        }
    }

    func finishSessionCacheWrites() async {
        clearingAvatars = true
        accountAvatarTask?.cancel()
        for task in avatarTasks.values { task.cancel() }
        // Await disk work only: a network task may itself be awaiting the
        // refresh request that is currently ending this session.
        for task in Array(cacheWrites.values) { _ = try? await task.value }
        AccountAvatarStore.device.invalidate(slot: accountAvatarSlot)
        avatarURLCache.removeAll()
        avatarTasks.removeAll()
        uploadedAvatar = nil
        onAccountBlockChange = nil
    }
    private var avatarDirectory: URL {
        let owner = "\(AppConfig.storageOrigin):\(auth.currentUser?.id ?? "")"
        let digest = SHA256.hash(data: Data(owner.utf8)).map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCOAvatars").appendingPathComponent(digest)
    }

    func avatarCacheBytes() async -> Int64 {
        let directory = avatarDirectory
        return await Task.detached(priority: .utility) {
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            return files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        }.value
    }

    func clearAvatarCache() async -> Bool {
        guard generation == auth.sessionGeneration, !clearingAvatars else { return false }
        clearingAvatars = true
        defer { clearingAvatars = false }
        let directory = avatarDirectory
        let tasks = Array(avatarTasks.values)
        for task in tasks { task.cancel() }
        for task in tasks { _ = await task.value }
        avatarURLCache.removeAll()
        avatarTasks.removeAll()
        return await Task.detached(priority: .utility) {
            guard FileManager.default.fileExists(atPath: directory.path) else { return true }
            do { try FileManager.default.removeItem(at: directory); return true } catch { return false }
        }.value
    }

    init(api: UsersAPI, auth: AuthRepository) {
        self.api = api
        self.auth = auth
        self.generation = auth.sessionGeneration
        self.locallyBlockedUsers = (try? auth.localSecurity?.blockedUsers()) ?? []
        self.directoryOrigin = AppConfig.storageOrigin
        self.directoryOwner = auth.currentUser?.id ?? ""
        self.accountAvatarSlot = UnyriActiveAccountSlot.current.id
        self.accountAvatarOwner = AccountAvatarStore.owner(origin: auth.authenticatedServerURL.absoluteString, user: auth.currentUser?.id ?? "")
    }

    func cachedMe() -> UserProfile? {
        guard (try? currentProfileOwner()) != nil else { return nil }
        var cached = ownProfile ?? auth.cachedScreen("profile", as: UserProfile.self)
        cached?.adminRole = nil
        if ownProfile == nil, let cached, cached.id.lowercased() == directoryOwner.lowercased(),
           cached.profileVersion >= 0, cached.avatarVersion >= 0 {
            keepAccountAvatar(cached)
        }
        return cached
    }

    private func cacheProfile(_ profile: UserProfile) {
        guard (try? currentProfileOwner()) == profile.id.lowercased(),
              profile.profileVersion >= 0, profile.avatarVersion >= 0,
              ownProfile.map({ $0.profileVersion <= profile.profileVersion && $0.avatarVersion <= profile.avatarVersion }) ?? true else { return }
        if ownProfile != profile { ownProfile = profile }
        keepAccountAvatar(profile)
        var cached = profile
        cached.adminRole = nil
        auth.saveScreen("profile", generation: generation, value: cached)
    }

    /// Profile publication starts at most one local copy per avatar identity.
    /// The ordinary resolver downloads only the active owner's picture.
    private func keepAccountAvatar(_ profile: UserProfile) {
        let revision = "\(profile.avatarVersion)|\(profile.avatarUrl ?? "")"
        guard accountAvatarTicket?.revision != revision else { return }
        accountAvatarTask?.cancel()
        accountAvatarWritten = nil
        accountAvatarTicket = try? AccountAvatarStore.device.begin(slot: accountAvatarSlot, owner: accountAvatarOwner, revision: revision)
        if profile.avatarUrl?.isEmpty ?? true, let ticket = accountAvatarTicket {
            // Register deletion before yielding so a switch/logout drains it too.
            let (id, write) = enqueueAccountAvatarWrite(ticket: ticket, file: nil, removed: true)
            accountAvatarTask = Task { [weak self] in
                _ = try? await write.value
                self?.cacheWrites[id] = nil
            }
            return
        }
        accountAvatarTask = Task { [weak self] in
            guard let self else { return }
            _ = await self.avatarURL(id: profile.id, avatarUrl: profile.avatarUrl, avatarVersion: profile.avatarVersion)
        }
    }

    private func copyOwnAccountAvatar(id: String, raw: String?, version: Int64, file: URL?) async {
        guard id.lowercased() == directoryOwner.lowercased(), directoryIdentityIsCurrent, !Task.isCancelled,
              let ticket = accountAvatarTicket, ticket.revision == "\(version)|\(raw ?? "")",
              accountAvatarWritten != ticket.revision else { return }
        // A failed download preserves the last local own photo; removal deletes it.
        let removed = raw?.isEmpty ?? true
        guard removed || file?.isFileURL == true else { return }
        let (ticketID, write) = enqueueAccountAvatarWrite(ticket: ticket, file: file, removed: removed)
        defer { cacheWrites[ticketID] = nil }
        if (try? await write.value) != nil, accountAvatarTicket == ticket { accountAvatarWritten = ticket.revision }
    }

    private func enqueueAccountAvatarWrite(ticket: AccountAvatarStore.Ticket, file: URL?, removed: Bool) -> (UUID, Task<Void, Error>) {
        let ticketID = UUID()
        let write = Task.detached(priority: .utility) { () async throws -> Void in
            if !removed, try AccountAvatarStore.device.read(slot: ticket.slot, owner: ticket.owner)?.revision == ticket.revision { return }
            let jpeg = removed ? nil : file.flatMap(AccountAvatarImage.jpeg(file:))
            guard removed || jpeg != nil else { throw CancellationError() }
            if try AccountAvatarStore.device.write(jpeg, ticket: ticket) {
                await AccountAvatarImage.didChange(slot: ticket.slot)
            }
        }
        cacheWrites[ticketID] = write
        return (ticketID, write)
    }

    func getMe() async throws -> UserProfile {
        _ = try currentProfileOwner()
        let flight: (id: UUID, revision: UInt64, task: Task<UserProfile, Error>)
        if let pending = profileRequest { flight = pending }
        else {
            let id = UUID(), revision = profileMutationRevision
            let task = Task { @MainActor [weak self] () async throws -> UserProfile in
                guard let self else { throw CancellationError() }
                defer { if self.profileRequest?.id == id { self.profileRequest = nil } }
                let result = try await self.api.getMe(accessToken: self.currentToken())
                try Task.checkCancellation()
                return result
            }
            flight = (id, revision, task)
            profileRequest = flight
        }
        let result = try await flight.task.value
        guard try currentProfileOwner() == result.id.lowercased(), result.profileVersion >= 0, result.avatarVersion >= 0 else { throw CancellationError() }
        // A GET started before an edit/avatar commit must not restore its old
        // snapshot into the offline cache after the mutation has succeeded.
        if flight.revision == profileMutationRevision { cacheProfile(result) }
        return ownProfile.flatMap { $0.profileVersion >= result.profileVersion || $0.avatarVersion > result.avatarVersion ? $0 : nil } ?? result
    }

    private func currentProfileOwner() throws -> String {
        try requireSession()
        guard !ownProfileRetired, !directoryOwner.isEmpty, auth.currentUser?.id.lowercased() == directoryOwner.lowercased(),
              AppConfig.storageOrigin == directoryOrigin else { throw CancellationError() }
        return directoryOwner.lowercased()
    }

    /// Reuses GET /users/me. No polling, authentication change or key work.
    /// New hints coalesce while a request is in flight; a failed/lagging read
    /// waits for another real hint/reconnect/foreground trigger instead of looping.
    func refreshOwnProfile(_ hint: SelfProfileInvalidationHint? = nil) {
        guard let owner = try? currentProfileOwner() else { return }
        if let hint {
            guard hint.userID == owner,
                  ownProfile.map({ $0.profileVersion < hint.version }) ?? true else { return }
            wantedProfileVersion = max(wantedProfileVersion, hint.version)
        }
        profileHintRevision &+= 1
        guard profileSyncTask == nil else { return }
        let id = UUID(); profileSyncID = id
        profileSyncTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if self.profileSyncID == id { self.profileSyncTask = nil; self.profileSyncID = nil } }
            // A UI GET may have started before the change was committed. Let it
            // finish without cancelling its other callers, then request fresh.
            if let pending = self.profileRequest { _ = try? await pending.task.value }
            guard !Task.isCancelled, (try? self.currentProfileOwner()) == owner else { return }
            while true {
                let revision = self.profileHintRevision
                do { _ = try await self.getMe(); _ = try? await self.getIdentity() } catch { return }
                guard !Task.isCancelled, (try? self.currentProfileOwner()) == owner,
                      self.profileHintRevision != revision,
                      (self.ownProfile?.profileVersion ?? -1) < self.wantedProfileVersion else { return }
            }
        }
    }

    func stopOwnProfileSync() {
        ownProfileRetired = true
        contactChanges?.cancel(); contactChanges = nil
        contactsStore?.stopSession()
        ownIdentity = nil; identityRead?.task.cancel(); identityRead = nil
        legacyIdentityServer = false
        ownProfile = nil; wantedProfileVersion = -1
        profileSyncTask?.cancel(); profileSyncTask = nil; profileSyncID = nil
        profileRequest?.task.cancel(); profileRequest = nil
    }

    func updateProfile(
        username: String,
        displayName: String,
        bio: Patch<String>,
        avatarUrl: Patch<String>
    ) async throws -> UserProfile {
        let request = UpdateProfileRequest(
            username: username,
            displayName: displayName,
            bio: bio,
            avatarUrl: avatarUrl
        )
        let result = try await api.updateProfile(accessToken: try await currentToken(), request: request)
        try requireSession()
        profileMutationRevision &+= 1
        cacheProfile(result)
        return result
    }

    func search(query: String, limit: Int = 20, offset: Int64 = 0) async throws -> SearchResponse {
        let result = try await api.search(accessToken: try await currentToken(), query: query, limit: limit, offset: offset)
        try requireSession()
        return SearchResponse(items: result.items.filter { !locallyBlockedUsers.contains($0.id) }, nextOffset: result.nextOffset)
    }

    func directory(query: String, limit: Int = 30, offset: Int64 = 0) async throws -> SearchResponse {
        let privacyRevision = identityPrivacyRevision
        let result = try await api.directory(accessToken: try await currentToken(), query: query, limit: limit, offset: offset)
        try requireSession()
        if privacyRevision == identityPrivacyRevision, query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && offset == 0 {
            await cacheDirectoryIdentities(result.items)
        }
        try requireSession()
        return SearchResponse(items: result.items.filter { !locallyBlockedUsers.contains($0.id) }, nextOffset: result.nextOffset)
    }

    private var directoryScope: String { directoryOrigin + "|" + directoryOwner }
    private var directoryIdentityIsCurrent: Bool {
        generation == auth.sessionGeneration && auth.currentUser?.id == directoryOwner &&
            AppConfig.storageOrigin == directoryOrigin && !directoryOwner.isEmpty && !clearingAvatars
    }
    private var directoryCacheFile: URL {
        let hash = SHA256.hash(data: Data(directoryScope.utf8)).map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCODirectory").appendingPathComponent(hash).appendingPathComponent("first-page.json")
    }

    func cachedDirectory(query: String) async -> [PublicUser] {
        guard directoryIdentityIsCurrent else { return [] }
        let file = directoryCacheFile
        let scope = directoryScope
        let identities = await Task.detached(priority: .utility) {
            DirectoryCachePolicy.load(file: file, scope: scope, query: query)
        }.value
        return directoryIdentityIsCurrent && !Task.isCancelled ? identities.filter { !locallyBlockedUsers.contains($0.id) } : []
    }

    private func cacheDirectoryIdentities(_ users: [PublicUser]) async {
        guard directoryIdentityIsCurrent,
              let data = try? JSONEncoder().encode(DirectoryIdentityCache(scope: directoryScope, users: DirectoryCachePolicy.identities(users))),
              data.count <= 1_048_576 else { return }
        let file = directoryCacheFile
        let scope = directoryScope
        let ticket = UUID()
        // Share the same drain that logout waits for before purging private
        // caches. A late disk completion cannot recreate a signed-out cache.
        let write = Task.detached(priority: .utility) {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: file, options: .atomic)
        }
        cacheWrites[ticket] = write
        defer { cacheWrites[ticket] = nil }
        _ = try? await write.value
        guard directoryIdentityIsCurrent, directoryScope == scope else { return }
    }

    /// Resolves a displayable avatar URL:
    func cachedAvatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) -> URL? {
        guard blockedAccountScope != nil, !invalidatingAvatarIDs.contains(id), let raw = avatarUrl, !raw.isEmpty else { return nil }
        return avatarURLCache["\(id):\(avatarVersion):\(raw)"]?.url
    }

    /// A local-only candidate for the share snapshot. No token or presigned URL
    /// crosses into the App Group; the writer checks/downs samples it off-main.
    func shareAvatarFileURL(id: String, avatarUrl: String?, avatarVersion: Int64) -> URL? {
        guard let raw = avatarUrl, !raw.isEmpty, !invalidatingAvatarIDs.contains(id) else { return nil }
        let key = "\(id):\(avatarVersion):\(raw)"
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return avatarDirectory.appendingPathComponent(digest + ".jpg")
    }

    /// Resolves a displayable avatar URL:
    /// - nil/empty `avatarUrl` → nil (initials fallback)
    /// - `avatars/…` key → presigned download URL via `GET /users/:id/avatar`
    func avatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) async -> URL? {
        let file = await resolveAvatarURL(id: id, avatarUrl: avatarUrl, avatarVersion: avatarVersion)
        await copyOwnAccountAvatar(id: id, raw: avatarUrl, version: avatarVersion, file: file)
        return file
    }

    private func resolveAvatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) async -> URL? {
        guard let scope = blockedAccountScope, !Task.isCancelled, !clearingAvatars, !invalidatingAvatarIDs.contains(id) else { return nil }
        let privacyRevision = identityPrivacyRevision
        guard let raw = avatarUrl, Self.isAvatarObjectKey(raw) else { return nil }

        let key = "\(id):\(avatarVersion):\(raw)"
        if let cached = avatarURLCache[key], cached.url.isFileURL {
            if await Task.detached(priority: .utility, operation: { FileManager.default.fileExists(atPath: cached.url.path) }).value {
                return blockedAccountScope == scope && privacyRevision == identityPrivacyRevision && !invalidatingAvatarIDs.contains(id) ? cached.url : nil
            }
            avatarURLCache[key] = nil
        }
        if let task = avatarTasks[key] {
            let result = await task.value
            return blockedAccountScope == scope && privacyRevision == identityPrivacyRevision && !Task.isCancelled && !invalidatingAvatarIDs.contains(id) ? result : nil
        }
        let owner = "\(AppConfig.storageOrigin):\(auth.currentUser?.id ?? "")"
        let digest = { (s: String) in SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCOAvatars").appendingPathComponent(digest(owner))
        let file = directory.appendingPathComponent(digest(key) + ".jpg")
        let task = Task<URL?, Never> { [weak self] in
            guard let self else { return nil }
            if await Task.detached(priority: .utility, operation: { FileManager.default.fileExists(atPath: file.path) }).value {
                guard self.blockedAccountScope == scope else { return nil }
                return file
            }
            guard let remote = await self.remoteAvatarURL(id: id, avatarUrl: raw, avatarVersion: avatarVersion), self.blockedAccountScope == scope else { return nil }
            do {
                var request = URLRequest(url: remote)
                request.timeoutInterval = 20
                let (data, response) = try await URLSession.shared.unyriBoundedData(for: request, maximumBytes: 3 * 1024 * 1024)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      !data.isEmpty, data.count <= 3 * 1024 * 1024 else { return nil }
                try self.requireSession()
                guard self.blockedAccountScope == scope else { return nil }
                let writeId = UUID()
                let write = Task.detached(priority: .utility) {
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    try data.write(to: file, options: .atomic)
                    let files = (try? FileManager.default.contentsOfDirectory(at: directory,
                        includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
                    let entries = files.compactMap { url -> (URL, Int, Date)? in
                        guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }
                        return (url, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
                    }.sorted { $0.2 < $1.2 }
                    var total = entries.reduce(0) { $0 + $1.1 }
                    for entry in entries where total > 32 * 1024 * 1024 && entry.0 != file {
                        if (try? FileManager.default.removeItem(at: entry.0)) != nil { total -= entry.1 }
                    }
                }
                self.cacheWrites[writeId] = write
                defer { self.cacheWrites[writeId] = nil }
                try await write.value
                try self.requireSession()
                guard self.blockedAccountScope == scope else { return nil }
                return file
            } catch { return nil }
        }
        avatarTasks[key] = task
        let result = await task.value
        avatarTasks[key] = nil
        guard blockedAccountScope == scope, !Task.isCancelled, !clearingAvatars,
              privacyRevision == identityPrivacyRevision, !invalidatingAvatarIDs.contains(id) else { return nil }
        if let result {
            let changed = avatarURLCache[key]?.url != result
            avatarURLCache[key] = (result, .distantFuture)
            if changed { shareAvatarRevision &+= 1 }
        }
        return result
    }

    nonisolated static func isAvatarObjectKey(_ value: String) -> Bool {
        value.hasPrefix("avatars/") && value.count > 8 && !value.contains(":") && !value.contains("\\") &&
            !value.contains("%") && !value.split(separator: "/").contains(where: { $0 == "." || $0 == ".." })
    }

    private func remoteAvatarURL(id: String, avatarUrl: String, avatarVersion: Int64) async -> URL? {
        guard let scope = blockedAccountScope, !Task.isCancelled else { return nil }
        let raw = avatarUrl

        if Self.isAvatarObjectKey(raw) {
            let cacheKey = "\(id):\(avatarVersion)"
            if let cached = avatarURLCache[cacheKey], cached.expiresAt > Date().addingTimeInterval(30) { return cached.url }
            do {
                let dto = try await api.avatarDownloadURL(accessToken: try await currentToken(), userId: id)
                try requireSession()
                guard blockedAccountScope == scope else { return nil }
                if let url = URL(string: dto.downloadUrl) {
                    if let expiry = ISO8601Cocoa.parse(dto.expiresAt) {
                        avatarURLCache[cacheKey] = (url, expiry)
                    }
                    return url
                }
            } catch {
                return nil
            }
        }
        return nil
    }

    private func currentToken() async throws -> String {
        try requireSession()
        let token = try await auth.currentAccessToken()
        try requireSession()
        return token
    }

    func uploadAvatar(jpeg: Data) async throws -> UserProfile {
        try requireSession()
        guard !jpeg.isEmpty, jpeg.count <= 3 * 1024 * 1024 else { throw APIError.network("Invalid avatar size") }
        let key: String
        if let uploadedAvatar, uploadedAvatar.data == jpeg {
            key = uploadedAvatar.key
        } else {
            let upload = try await api.createAvatar(accessToken: try await currentToken(), sizeBytes: jpeg.count)
            try requireSession()
            guard let url = URL(string: upload.uploadUrl), ["https", "http"].contains(url.scheme ?? "") else { throw APIError.invalidURL }
            var request = URLRequest(url: url)
            request.httpMethod = "PUT"
            request.timeoutInterval = 30
            request.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
            request.setValue(String(jpeg.count), forHTTPHeaderField: "Content-Length")
            // Presigned storage URL: never attach the UNYRI bearer token.
            let (_, response) = try await URLSession.shared.upload(for: request, from: jpeg)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.network("Avatar upload failed") }
            try requireSession()
            key = upload.objectKey
            uploadedAvatar = (jpeg, key)
        }
        let profile = try await api.completeAvatar(accessToken: try await currentToken(), objectKey: key)
        try requireSession()
        uploadedAvatar = nil
        avatarURLCache.removeAll()
        profileMutationRevision &+= 1
        cacheProfile(profile)
        return profile
    }

    func removeAvatar() async throws -> UserProfile {
        let profile = try await api.deleteAvatar(accessToken: try await currentToken())
        try requireSession()
        uploadedAvatar = nil
        avatarURLCache.removeAll()
        profileMutationRevision &+= 1
        cacheProfile(profile)
        return profile
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }
}

@MainActor
protocol ContactsService: AnyObject {
    var blockedAccountScope: String? { get }
    var blockedAccountOwnerID: String { get }
    func contactsPage(cursor: String?) async throws -> ContactsPage
    /// One person's membership, for a profile row (GET /users/:id/contact).
    func contactStatus(userID: String) async throws -> Bool
    func setContact(userID: String, contact: Bool) async throws
}

extension UsersRepository: ContactsService {}

/// WS `contacts.changed`. A current server names the one person who changed;
/// an older one sends the bare type, which only marks the list stale.
struct ContactsChangeHint: Equatable {
    let userID: String
    let isContact: Bool
    static func parse(_ data: Data) -> ContactsChangeHint? {
        struct Envelope: Decodable { let type: String; let user_id: UUID; let is_contact: Bool }
        guard let value = try? JSONDecoder().decode(Envelope.self, from: data), value.type == "contacts.changed" else { return nil }
        return ContactsChangeHint(userID: value.user_id.uuidString.lowercased(), isContact: value.is_contact)
    }
}

/// Account address book, kept apart from UsersRepository's published state so a
/// contact change re-renders only the views that observe contacts. The whole
/// book is loaded once at session start for transport admission. Existing sync
/// events refresh it even when no Contacts screen has ever been opened.
@MainActor
final class ContactsStore: ObservableObject {
    enum Phase: Equatable { case idle, loading, loaded, failed }
    /// Sorted by display name, then username; cached for this account scope.
    @Published private(set) var entries: [ContactEntry] = []
    /// Known members: the loaded list plus single answers, writes and hints.
    @Published private(set) var ids: Set<String> = []
    /// Known non-members from single answers, writes and hints.
    @Published private(set) var nonMembers: Set<String> = []
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var changing: Set<String> = []
    @Published private(set) var checking: Set<String> = []
    /// Bumped by a change that names nobody, so open profile rows ask again.
    @Published private(set) var revision: UInt64 = 0
    private weak var service: ContactsService?
    private let scope: String?
    /// Fences page results: a newer load or a write voids an older load.
    private var ticket = UUID()
    /// Per person, bumped by every write, hint and answer: an older single
    /// answer never overwrites a newer state.
    private var touched: [String: UInt64] = [:]
    private var loadedAt: Date?
    private var stale = true
    /// Open People lists and the session both consume the same address book.
    private var listViewers = 0
    private var sessionActive = false
    private var sessionLoad: Task<Void, Never>?
    /// Folded name and username per id, built off the main thread with the list.
    private var searchKeys: [String: String] = [:]
    private var hints: AnyCancellable?

    static let freshness: TimeInterval = 300
    /// 25 pages of 200 = the server's 5000-contact limit.
    static let pageLimit = 25

    init(service: ContactsService) {
        self.service = service
        scope = service.blockedAccountScope
        hints = NotificationCenter.default.publisher(for: .unyriContactsChanged).sink { [weak self] note in
            let hint = note.object as? ContactsChangeHint
            Task { @MainActor [weak self] in
                guard let self, self.service?.blockedAccountScope == self.scope else { return }
                self.receive(hint)
            }
        }
    }

    private var identityIsCurrent: Bool { scope != nil && service?.blockedAccountScope == scope }

    func isKnownPeerContact(_ userID: String) -> Bool {
        identityIsCurrent && loadedAt != nil && ids.contains(userID.lowercased())
    }

    func startSession() {
        guard identityIsCurrent, !sessionActive else { return }
        sessionActive = true
        restartIfListed()
    }

    func stopSession() {
        sessionActive = false
        sessionLoad?.cancel()
        sessionLoad = nil
        clear()
    }
    /// The loaded list answers for everyone while it is fresh. Hints missed
    /// while the socket was down cannot leave a profile row wrong for long.
    private var listIsCurrent: Bool {
        phase == .loaded && !stale && (loadedAt.map { Date().timeIntervalSince($0) < Self.freshness } ?? false)
    }

    // MARK: People list

    /// Called by the People list on appear and disappear.
    func listAppeared() { listViewers += 1 }
    func listDisappeared() { listViewers = max(0, listViewers - 1) }

    /// Loads once, then again only when stale, failed or older than 5 min.
    func ensureLoaded() async {
        guard identityIsCurrent else { clear(); return }
        guard phase != .loading else { return }
        let fresh = loadedAt.map { Date().timeIntervalSince($0) < Self.freshness } ?? false
        guard stale || phase == .failed || !fresh else { return }
        await reload()
    }

    func reload() async {
        guard identityIsCurrent, let service else { clear(); return }
        let current = UUID()
        ticket = current
        stale = false
        if phase != .loading { phase = .loading }
        let owner = service.blockedAccountOwnerID
        do {
            var collected: [ContactEntry] = []
            var cursor: String?
            var seen = Set<String>()
            for _ in 0..<Self.pageLimit {
                let page = try await service.contactsPage(cursor: cursor)
                guard ticket == current else { return }
                guard identityIsCurrent else { clear(); return }
                collected += page.items
                guard let next = page.nextCursor else { break }
                // A broken or repeating cursor must not page forever.
                guard UUID(uuidString: next) != nil, seen.insert(next).inserted else { throw ContactsLoadError.invalidPage }
                cursor = next
            }
            // Sorting and folding thousands of names stays off the main thread.
            let items = collected
            let prepared = await Task.detached(priority: .userInitiated) { Self.prepared(items, excluding: owner) }.value
            guard ticket == current else { return }
            guard identityIsCurrent else { clear(); return }
            publish(prepared)
            loadedAt = Date()
            phase = .loaded
        } catch {
            guard ticket == current else { return }
            guard identityIsCurrent else { clear(); return }
            stale = true
            // A failure keeps the rows already shown.
            phase = Task.isCancelled || error is CancellationError ? (loadedAt == nil ? .idle : .loaded) : .failed
        }
    }

    /// The list filtered by name or username, off the main thread.
    func matching(_ query: String) async -> [ContactEntry] {
        let term = Self.fold(query)
        let entries = self.entries
        guard !term.isEmpty else { return entries }
        let keys = searchKeys
        return await Task.detached(priority: .userInitiated) {
            entries.filter { (keys[$0.id] ?? Self.searchKey($0.user)).contains(term) }
        }.value
    }

    // MARK: One person

    /// Membership for a profile row: from the loaded list when it is current,
    /// else one small request. Never pages the book. A failed answer leaves
    /// the person unknown, which reads as "Add": the add is idempotent.
    func check(_ user: PublicUser) async {
        guard identityIsCurrent, let service, user.id != service.blockedAccountOwnerID else { return }
        let id = user.id
        if listIsCurrent {
            if !ids.contains(id) && !nonMembers.contains(id) { nonMembers.insert(id) }
            return
        }
        guard !changing.contains(id), !checking.contains(id) else { return }
        checking.insert(id)
        defer { if checking.contains(id) { checking.remove(id) } }
        // One more try when a change that names nobody lands mid-request.
        for _ in 0..<2 {
            let mark = (touched[id] ?? 0, revision)
            guard let member = try? await service.contactStatus(userID: id) else { return }
            guard identityIsCurrent else { clear(); return }
            // A write or hint for this person since the question is newer.
            guard (touched[id] ?? 0) == mark.0 else { return }
            guard revision == mark.1 else { continue }
            record(id, member: member)
            return
        }
    }

    /// A bare hint, unblock or reconnect refreshes the account's shared book.
    func markStale() {
        _ = supersede()
        stale = true
        guard identityIsCurrent else { return }
        if !nonMembers.isEmpty { nonMembers = [] }
        revision &+= 1
        restartIfListed()
    }

    /// Blocked: hidden at once (the server hides blocked contacts too), and a
    /// page already in flight is replaced.
    func hide(_ userID: String) {
        let interrupted = supersede()
        record(userID, member: false)
        if interrupted { restartIfListed() }
    }

    private func restartIfListed() {
        guard sessionActive || listViewers > 0 else { return }
        // Replacing the task also replaces reload's ticket before it publishes.
        sessionLoad?.cancel()
        sessionLoad = Task { [weak self] in
            guard !Task.isCancelled, let self else { return }
            await self.ensureLoaded()
        }
    }

    /// WS hint: patches the named person; a bare hint marks the list stale.
    private func receive(_ hint: ContactsChangeHint?) {
        guard let hint else { markStale(); return }
        guard identityIsCurrent, hint.userID != service?.blockedAccountOwnerID else { return }
        // A page fetched before this change must not undo it.
        let interrupted = supersede()
        record(hint.userID, member: hint.isContact)
        if interrupted || (stale && loadedAt != nil) { restartIfListed() }
    }

    /// Applies locally only after the server confirmed the change.
    @discardableResult
    func set(_ user: PublicUser, contact: Bool) async -> Bool {
        guard identityIsCurrent, let service, user.id != service.blockedAccountOwnerID, !changing.contains(user.id) else { return false }
        changing.insert(user.id)
        defer { changing.remove(user.id) }
        // A page fetched before this write must not restore the old row.
        var interrupted = supersede()
        do {
            try await service.setContact(userID: user.id, contact: contact)
            guard identityIsCurrent else { clear(); return false }
            // ...nor one that started while the write was in flight.
            interrupted = supersede() || interrupted
            apply(user, contact: contact)
            if interrupted { restartIfListed() }
            return true
        } catch {
            guard identityIsCurrent else { clear(); return false }
            if interrupted { restartIfListed() }
            return false
        }
    }

    /// Voids any running load; true when one was running (the caller restarts it).
    private func supersede() -> Bool {
        ticket = UUID()
        guard phase == .loading else { return false }
        phase = loadedAt == nil ? .idle : .loaded
        stale = true
        return true
    }

    private func apply(_ user: PublicUser, contact: Bool) {
        guard contact else { record(user.id, member: false); return }
        touched[user.id, default: 0] &+= 1
        if nonMembers.contains(user.id) { nonMembers.remove(user.id) }
        if !ids.contains(user.id) { ids.insert(user.id) }
        // A list that was never loaded is not started by one row; its load brings it.
        guard loadedAt != nil, !entries.contains(where: { $0.id == user.id }) else { return }
        var stored = user
        // Directory presence hints are never kept as contact data.
        stored.online = nil
        stored.lastSeenAt = nil
        let entry = ContactEntry(user: stored, source: "manual", addedAt: ISO8601DateFormatter().string(from: Date()))
        var next = entries
        var low = 0, high = next.count
        while low < high {
            let mid = (low + high) / 2
            if Self.precedes(next[mid], entry) { low = mid + 1 } else { high = mid }
        }
        next.insert(entry, at: low)
        searchKeys[user.id] = Self.searchKey(stored)
        entries = next
    }

    /// A server answer about one person (a single check or a hint).
    private func record(_ id: String, member: Bool) {
        touched[id, default: 0] &+= 1
        if member {
            if nonMembers.contains(id) { nonMembers.remove(id) }
            if !ids.contains(id) { ids.insert(id) }
            // Its row comes with the next load of the list.
            if loadedAt != nil && !entries.contains(where: { $0.id == id }) { stale = true }
        } else {
            if !nonMembers.contains(id) { nonMembers.insert(id) }
            if ids.contains(id) { ids.remove(id) }
            if entries.contains(where: { $0.id == id }) { entries.removeAll { $0.id == id } }
        }
    }

    /// Publishes only what changed, so an identical reload re-renders nothing.
    private func publish(_ prepared: (entries: [ContactEntry], ids: Set<String>, keys: [String: String])) {
        searchKeys = prepared.keys
        if entries != prepared.entries { entries = prepared.entries }
        if ids != prepared.ids { ids = prepared.ids }
        // The full list now answers for its members; known non-members stay known.
        if !nonMembers.isDisjoint(with: prepared.ids) { nonMembers.subtract(prepared.ids) }
    }

    private func clear() {
        ticket = UUID()
        if !entries.isEmpty { entries = [] }
        if !ids.isEmpty { ids = [] }
        if !nonMembers.isEmpty { nonMembers = [] }
        if phase != .idle { phase = .idle }
        if !changing.isEmpty { changing = [] }
        if !checking.isEmpty { checking = [] }
        touched = [:]
        searchKeys = [:]
        loadedAt = nil
        stale = true
    }

    nonisolated static func prepared(_ items: [ContactEntry], excluding owner: String)
        -> (entries: [ContactEntry], ids: Set<String>, keys: [String: String]) {
        var seen = Set<String>()
        let sorted = items.filter { $0.id != owner && seen.insert($0.id).inserted }.sorted(by: precedes)
        var keys: [String: String] = [:]
        keys.reserveCapacity(sorted.count)
        for entry in sorted { keys[entry.id] = searchKey(entry.user) }
        return (sorted, seen, keys)
    }

    /// Name and public identity folded once; private sign-in fields are excluded.
    nonisolated static func searchKey(_ user: PublicUser) -> String {
        fold([DisplayNameSanitizer.peer(user.displayName), user.publicUsername ?? "", user.unyriNumber ?? ""].joined(separator: "\n"))
    }

    nonisolated static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    nonisolated static func precedes(_ a: ContactEntry, _ b: ContactEntry) -> Bool {
        switch DisplayNameSanitizer.peer(a.user.displayName).localizedStandardCompare(DisplayNameSanitizer.peer(b.user.displayName)) {
        case .orderedAscending: return true
        case .orderedDescending: return false
        case .orderedSame:
            if a.user.publicUsername != b.user.publicUsername {
                return (a.user.publicUsername ?? "").localizedStandardCompare(b.user.publicUsername ?? "") == .orderedAscending
            }
            return a.id < b.id
        }
    }
}

private enum ContactsLoadError: Error { case invalidPage }
