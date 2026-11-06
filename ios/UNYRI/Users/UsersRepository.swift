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
    private var clearingAvatars = false
    private var cacheWrites: [UUID: Task<Void, Error>] = [:]
    private var profileMutationRevision: UInt64 = 0
    @Published private(set) var ownProfile: UserProfile?
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
        guard generation == auth.sessionGeneration, auth.currentUser?.id == directoryOwner,
              AppConfig.storageOrigin == directoryOrigin, !directoryOwner.isEmpty else { return nil }
        return "\(generation)|\(directoryOrigin)|\(directoryOwner)"
    }
    var blockedAccountOwnerID: String { directoryOwner }

    func blockedAccounts(query: String, cursor: String?) async throws -> BlockedAccountsPage {
        guard let scope = blockedAccountScope else { throw CancellationError() }
        let result = try await api.blockedAccounts(accessToken: try await currentToken(), query: query, cursor: cursor)
        try Task.checkCancellation()
        guard blockedAccountScope == scope else { throw CancellationError() }
        return result
    }

    func setAccountBlocked(userID: String, blocked: Bool) async throws {
        guard let scope = blockedAccountScope, userID != directoryOwner else { throw CancellationError() }
        guard blockMutations.insert(userID).inserted else { throw APIError.http(409) }
        defer { blockMutations.remove(userID) }
        try await api.setBlocked(accessToken: try await currentToken(), userID: userID, blocked: blocked)
        guard blockedAccountScope == scope else { throw CancellationError() }
        // Publish only an acknowledged account mutation. No optimistic offline success.
        accountBlockAcknowledged(userID: userID, blocked: blocked)
        onAccountBlockChange?(userID, blocked)
    }

    func accountBlockAcknowledged(userID: String, blocked: Bool) {
        guard blockedAccountScope != nil else { return }
        identityPrivacyRevision &+= 1
        if blocked { locallyBlockedUsers.insert(userID); invalidateKnownIdentity(userID) }
        else { locallyBlockedUsers.remove(userID) }
        blockedAccountsRevision &+= 1
    }

    func accountBlocksChanged() {
        guard blockedAccountScope != nil else { return }
        identityPrivacyRevision &+= 1
        locallyBlockedUsers.removeAll()
        blockedAccountsRevision &+= 1
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
        for task in avatarTasks.values { task.cancel() }
        // Await disk work only: a network task may itself be awaiting the
        // refresh request that is currently ending this session.
        for task in Array(cacheWrites.values) { _ = try? await task.value }
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
        self.directoryOrigin = AppConfig.storageOrigin
        self.directoryOwner = auth.currentUser?.id ?? ""
    }

    func cachedMe() -> UserProfile? {
        guard (try? currentProfileOwner()) != nil else { return nil }
        var cached = ownProfile ?? auth.cachedScreen("profile", as: UserProfile.self)
        cached?.adminRole = nil
        return cached
    }

    private func cacheProfile(_ profile: UserProfile) {
        guard (try? currentProfileOwner()) == profile.id.lowercased(),
              profile.profileVersion >= 0, profile.avatarVersion >= 0,
              ownProfile.map({ $0.profileVersion <= profile.profileVersion && $0.avatarVersion <= profile.avatarVersion }) ?? true else { return }
        if ownProfile != profile { ownProfile = profile }
        var cached = profile
        cached.adminRole = nil
        auth.saveScreen("profile", generation: generation, value: cached)
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
                do { _ = try await self.getMe() } catch { return }
                guard !Task.isCancelled, (try? self.currentProfileOwner()) == owner,
                      self.profileHintRevision != revision,
                      (self.ownProfile?.profileVersion ?? -1) < self.wantedProfileVersion else { return }
            }
        }
    }

    func stopOwnProfileSync() {
        ownProfileRetired = true
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
            guard let bytes = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, bytes <= 1_048_576,
                  let data = try? Data(contentsOf: file), let entry = try? JSONDecoder().decode(DirectoryIdentityCache.self, from: data) else { return [] as [PublicUser] }
            return DirectoryCachePolicy.restored(entry, scope: scope, query: query)
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

    /// Resolves a displayable avatar URL:
    /// - nil/empty `avatarUrl` → nil (initials fallback)
    /// - `http(s)://…` → used directly (legacy external avatar)
    /// - `avatars/…` key → presigned download URL via `GET /users/:id/avatar`
    func avatarURL(id: String, avatarUrl: String?, avatarVersion: Int64) async -> URL? {
        guard let scope = blockedAccountScope, !Task.isCancelled, !clearingAvatars, !invalidatingAvatarIDs.contains(id) else { return nil }
        let privacyRevision = identityPrivacyRevision
        guard let raw = avatarUrl, !raw.isEmpty else { return nil }

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
                let (data, response) = try await URLSession.shared.data(for: request)
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
        if let result { avatarURLCache[key] = (result, .distantFuture) }
        return result
    }

    private func remoteAvatarURL(id: String, avatarUrl: String, avatarVersion: Int64) async -> URL? {
        guard let scope = blockedAccountScope, !Task.isCancelled else { return nil }
        let raw = avatarUrl

        if raw.hasPrefix("http://") || raw.hasPrefix("https://") {
            return URL(string: raw)
        }

        if raw.hasPrefix("avatars/") {
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
