import Combine
import Foundation

/// Authenticated chat-list / direct-chat access. Reuses `AuthRepository` for
/// token refresh (never duplicates refresh logic). 1:1 deduplication is owned
/// by the backend (`POST /chats/direct` is idempotent) — no local chat-id
/// generation here.
@MainActor
final class ChatsRepository: ObservableObject {
    private let api: ChatsAPI
    private let auth: AuthRepository
    private let generation: UInt64

    private struct PreferenceIntent: Codable {
        var id = UUID().uuidString
        var chat: String
        var kind: String
        var enabled: Bool
    }
    private let preferenceQueueID = "__pending_preferences__"
    private var preferenceRevision: UInt64 = 0
    private var accountBlockOverrides: [String: Bool] = [:]
    var onAccountBlockChange: ((String, Bool) -> Void)?

    /// Project account security changes only onto already-known conversations.
    /// Never fetch/create a chat or resurrect a deleted history entry here.
    func applyAccountBlock(userID: String, blocked: Bool) {
        guard generation == auth.sessionGeneration, preferenceOrigin == AppConfig.storageOrigin else { return }
        accountBlockOverrides[userID] = blocked
        preferenceRevision &+= 1
        let affected = preferenceCache.values.filter { $0.otherUser?.id == userID && !isDeleted($0.chatId) }
        for original in affected {
            let chat = projectPreference(original)
            try? store?.upsertChats([chat])
            preferenceCache[chat.chatId] = chat
        }
        cacheRevision &+= 1
    }

    func accountBlocksChangedRemotely() {
        accountBlockOverrides.removeAll()
        preferenceRevision &+= 1
    }
    private var replayingPreferences = false
    private var replayPreferencesAgain = false
    private let preferenceOrigin = AppConfig.storageOrigin
    private func preferenceIdentity() throws {
        guard generation == auth.sessionGeneration, auth.currentUser != nil,
              preferenceOrigin == AppConfig.storageOrigin else { throw CancellationError() }
    }
    private func preferenceIntents() throws -> [String: PreferenceIntent] {
        guard let messageStore else { throw CancellationError() }
        return try messageStore.workspaceValues(chatId: preferenceQueueID).mapValues {
            try JSONDecoder().decode(PreferenceIntent.self, from: Data($0.utf8))
        }
    }
    private func enqueuePreference(chat: String, kind: String, enabled: Bool) throws {
        try preferenceIdentity()
        guard let messageStore, !isDeleted(chat) else { throw CancellationError() }
        let intent = PreferenceIntent(chat: chat, kind: kind, enabled: enabled)
        let value = String(decoding: try JSONEncoder().encode(intent), as: UTF8.self)
        try messageStore.saveChatValue(chatId: preferenceQueueID, name: kind + "|" + chat, value: value)
        preferenceRevision &+= 1
    }
    private func projectPreference(_ original: ChatSummary) -> ChatSummary {
        var chat = original
        if let userID = chat.otherUser?.id, let blocked = accountBlockOverrides[userID] {
            chat.isBlocked = blocked
            chat.blockedUserId = blocked ? userID : nil
        }
        if let raw = try? messageStore?.chatValue(chatId: preferenceQueueID, name: "mute|" + chat.chatId),
           let intent = try? JSONDecoder().decode(PreferenceIntent.self, from: Data(raw.utf8)) {
            chat.isMuted = intent.enabled; chat.mutedUntil = nil
        }
        return chat
    }
    private func preserveLocalMute(_ original: ChatSummary) -> ChatSummary {
        var chat = original
        if let local = preferenceCache[chat.chatId] {
            chat.isMuted = local.isMuted; chat.mutedUntil = local.mutedUntil
            chat.isBlocked = local.isBlocked; chat.blockedUserId = local.blockedUserId
        }
        return projectPreference(chat)
    }
    private var pinsKey: String { "chatPins|" + preferenceOrigin + "|" + (auth.currentUser?.id ?? "") }
    func cachedPins() -> Set<String> {
        projectPins(Set(UserDefaults.standard.stringArray(forKey: pinsKey) ?? []))
    }
    private func projectPins(_ base: Set<String>) -> Set<String> {
        var pins = base
        for intent in (try? preferenceIntents())?.values ?? Dictionary<String, PreferenceIntent>().values where intent.kind == "pin" {
            if intent.enabled && !isDeleted(intent.chat) { pins.insert(intent.chat) } else { pins.remove(intent.chat) }
        }
        return pins
    }
    func replayPreferences() async {
        if replayingPreferences { replayPreferencesAgain = true; return }
        replayingPreferences = true
        defer { replayingPreferences = false }
        repeat {
            replayPreferencesAgain = false
            do {
                try preferenceIdentity()
                for (key, intent) in try preferenceIntents() {
                    try preferenceIdentity()
                    do {
                        if !isDeleted(intent.chat) {
                            let token = try await currentToken()
                            try preferenceIdentity()
                            switch intent.kind {
                            case "pin": _ = try await api.setPins(accessToken: token, ids: [intent.chat], pinned: intent.enabled)
                            case "mute": try await api.updatePreference(accessToken: token, chatId: intent.chat, action: intent.enabled ? "mute" : "unmute")
                            case "archive":
                                let response = try await api.setArchived(accessToken: token, ids: [intent.chat], archived: intent.enabled)
                                try preferenceIdentity()
                                // Persist the server snapshot before dropping the durable intent.
                                // Newer local changes are projected over this snapshot below.
                                try saveArchiveSnapshot(Set(response.chatIds))
                            default: throw APIError.http(400)
                            }
                        }
                        try preferenceIdentity()
                        if try preferenceIntents()[key]?.id == intent.id {
                            if intent.kind == "mute", let chat = loadCachedChats(includePreviews: false).first(where: { $0.chatId == intent.chat }) {
                                try store?.upsertChats([chat])
                                preferenceCache[intent.chat] = chat
                            }
                            if intent.kind == "pin" { UserDefaults.standard.set(Array(cachedPins()), forKey: pinsKey) }
                            try messageStore?.saveChatValue(chatId: preferenceQueueID, name: key, value: nil)
                            preferenceRevision &+= 1
                        }
                        if intent.kind == "archive" {
                            archivedChatIDs = cachedArchives()
                            cacheRevision &+= 1
                        }
                    } catch is CancellationError { return }
                      catch APIError.server(let code, _) where intent.kind == "archive" && code == "chat_not_found" {
                        // A typed membership rejection is terminal. Generic 404
                        // from an older backend remains queued in the catch below.
                        try preferenceIdentity()
                        if try preferenceIntents()[key]?.id == intent.id {
                            var snapshot = try archiveSnapshot()
                            snapshot.remove(intent.chat)
                            try saveArchiveSnapshot(snapshot)
                            try messageStore?.saveChatValue(chatId: preferenceQueueID, name: key, value: nil)
                            preferenceRevision &+= 1
                            archivedChatIDs = cachedArchives()
                            cacheRevision &+= 1
                            archiveReplayError = UnyriL10n.string("This chat is no longer available. Its archive change was not applied.")
                        }
                      } catch { /* Keep the durable intent for the next reconnect. */ }
                }
            } catch { return }
        } while replayPreferencesAgain && !Task.isCancelled
    }

    /// Best-effort local chat-list cache (created after login, in the same
    /// account-scoped directory as the message store). Set once by
    /// `AccountServices`; the list still works if it is nil (memory-only).
    var store: ChatStore? {
        didSet { preferenceCache = Dictionary(((try? store?.loadChats()) ?? []).map { ($0.chatId, $0) }, uniquingKeysWith: { _, last in last }) }
    }
    // In-memory only, tied to this account/session; never uploaded or persisted.
    @Published var recentSearchQueries: [String] = []
    @Published private(set) var preferenceCache: [String: ChatSummary] = [:]
    var onPeerReceipt: ((String, Int64, Int64) -> Void)?
    private var recoveringPeerReceipts = Set<String>()

    /// /sync recovers messages, not a missed peer receipt. Refresh only the
    /// currently visible, locally known direct chat; never enumerate history
    /// or persist a server chat merely to recover delivered/read watermarks.
    func recoverVisiblePeerReceipts(activeChat: @MainActor () -> String?) async {
        guard let chatId = activeChat(), UUID(uuidString: chatId) != nil,
              let owner = auth.currentUser?.id, let messageStore,
              let local = preferenceCache[chatId], local.chatType == "direct",
              let peer = UUID(uuidString: local.otherUser?.id ?? "") else { return }
        func current() -> Bool {
            !Task.isCancelled && generation == auth.sessionGeneration &&
                owner == auth.currentUser?.id && preferenceOrigin == AppConfig.storageOrigin &&
                activeChat() == chatId && !isDeleted(chatId) &&
                preferenceCache[chatId]?.chatType == "direct" &&
                UUID(uuidString: preferenceCache[chatId]?.otherUser?.id ?? "") == peer
        }
        guard current(), recoveringPeerReceipts.insert(chatId).inserted else { return }
        defer { recoveringPeerReceipts.remove(chatId) }
        do {
            let needed = try await messageStore.readAsync { reader in
                try reader.hasPeerReceiptCandidates(chatId: chatId, ownerId: owner)
            }
            guard needed, current() else { return }
            let token = try await currentToken()
            guard current() else { return }
            let remote = try await api.getChat(accessToken: token, chatId: chatId)
            guard current(), remote.chatId == chatId, remote.chatType == "direct",
                  UUID(uuidString: remote.otherUser?.id ?? "") == peer else { return }
            onPeerReceipt?(chatId, remote.otherMemberDeliveredSequence, remote.otherMemberReadSequence)
        } catch {
            // The existing foreground/recovery timer retries at most once per
            // pass. A failed request cannot invent delivery or change history.
        }
    }

    /// The account-scoped message store, used only to overlay the local
    /// last-message preview onto the cached chat list. Nil means previews are
    /// simply unavailable (rows fall back to @username).
    var messageStore: MessageStore? {
        didSet { archivedChatIDs = cachedArchives() }
    }

    /// A one-shot "open this chat" request consumed by `ChatsListView`
    /// (Runtime Correction D foreground banner tap). Never a second
    /// navigation stack — the list view drives its own `ChatScreen`.
    @Published var managementRevision: UInt64 = 0
    @Published var openChatRequest: String?
    @Published var isChatListVisible = true
    @Published var navigationChatId: String?
    @Published private(set) var deletedChatIDs = Set<String>()
    func isDeleted(_ chatId: String) -> Bool { deletedChatIDs.contains(chatId) || (try? store?.isDeleted(chatId)) == true }
    func removeDeletedChat(_ chatId: String) throws {
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        try store?.deleteChat(chatId)
        deletedChatIDs.insert(chatId)
        unreadCounts[chatId] = nil
        if openChatRequest == chatId { openChatRequest = nil }
        if navigationChatId == chatId { navigationChatId = nil }
        cacheRevision &+= 1
    }
    @Published var notificationHeaderHeight: Double = 44
    @Published private(set) var unreadCounts: [String: Int] = [:]
    var unreadTotal: Int { unreadCounts.values.reduce(0, +) }

    func refreshUnreadCounts() async {
        guard let messageStore, let owner = auth.currentUser?.id else { return }
        let cutoff = auth.historyBeforeSequence ?? 0
        let cached = (try? await store?.loadChatsAsync()) ?? []
        let cachedIDs = Set(cached.map(\.chatId))
        guard !Task.isCancelled, generation == auth.sessionGeneration,
              owner == auth.currentUser?.id, cutoff == (auth.historyBeforeSequence ?? 0),
              preferenceOrigin == AppConfig.storageOrigin else { return }
        let snapshot = try? await messageStore.readAsync { reader in
            var ready = Set<String>()
            // Read completion before projections: a repair COMMIT between an
            // older count query and this marker check must not bless stale data.
            for id in cachedIDs where try reader.legacyLifecycleRepairIsComplete(chatId: id) { ready.insert(id) }
            let visible = try reader.visibleChatIDs(historyCutoff: cutoff, ownerId: owner).intersection(ready)
            return try reader.unreadCounts(ownerId: owner, historyCutoff: cutoff).filter { visible.contains($0.key) }
        }
        guard !Task.isCancelled, generation == auth.sessionGeneration,
              owner == auth.currentUser?.id, cutoff == (auth.historyBeforeSequence ?? 0),
              preferenceOrigin == AppConfig.storageOrigin else { return }
        if let snapshot {
            let counts = snapshot.filter { !deletedChatIDs.contains($0.key) }
            if unreadCounts != counts { unreadCounts = counts }
        }
    }
    @Published private(set) var cacheRevision: UInt64 = 0
    private var fetchingChats = Set<String>()

    func messagesChanged(in chatIds: Set<String>) async {
        let owner = auth.currentUser?.id
        guard generation == auth.sessionGeneration else { return }
        await refreshUnreadCounts()
        cacheRevision &+= 1
        let known = Set(((try? store?.loadChats()) ?? []).map(\.chatId))
        let cutoff = auth.historyBeforeSequence ?? 0
        guard let messageStore,
              let visible = try? await messageStore.readAsync({ try $0.visibleChatIDs(historyCutoff: cutoff, ownerId: owner) }),
              generation == auth.sessionGeneration else { return }
        for chatId in chatIds.intersection(visible).subtracting(known) where !isDeleted(chatId) {
            guard fetchingChats.insert(chatId).inserted else { continue }
            defer { fetchingChats.remove(chatId) }
            if let chat = try? await getChat(chatId: chatId), generation == auth.sessionGeneration {
                persistChats([chat])
            }
        }
    }

    init(api: ChatsAPI, auth: AuthRepository) {
        self.api = api
        self.auth = auth
        self.generation = auth.sessionGeneration
    }

    @Published private(set) var lastListSuccess: Date?
    func listChats(limit: Int = 50, offset: Int64 = 0) async throws -> ChatListResponse {
        let owner = auth.currentUser?.id
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        let cutoff = auth.historyBeforeSequence ?? 0
        var known = Set<String>()
        if let messageStore { known = try await messageStore.readAsync { try $0.visibleChatIDs(historyCutoff: cutoff, ownerId: owner) } }
        known.formUnion(((try? store?.loadChats()) ?? []).filter { $0.chatType == "saved" }.map(\.chatId))
        let ids = known.filter { !isDeleted($0) }.sorted()
        let start = Int(min(max(0,offset),Int64(ids.count)))
        let end = min(ids.count, start + max(1,min(limit,100)))
        var chats = [ChatSummary]()
        for index in stride(from:start,to:end,by:50) {
            let batch = Array(ids[index..<min(end,index+50)])
            let revision = preferenceRevision
            let response = try await api.listKnownChats(accessToken: try await currentToken(), chatIds:batch)
            guard response.items.allSatisfy({ batch.contains($0.chatId) }) else { throw APIError.http(502) }
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            chats.append(contentsOf:response.items.map { revision == preferenceRevision ? projectPreference($0) : preserveLocalMute($0) })
        }
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        lastListSuccess = Date()
        for chat in chats { onPeerReceipt?(chat.chatId, chat.otherMemberDeliveredSequence, chat.otherMemberReadSequence) }
        return ChatListResponse(items:chats.map(projectPreference),nextOffset:end < ids.count ? Int64(end) : nil)
    }

    func directPeerName(_ userId: String) async throws -> String? {
        // Resolve this caller only; do not enumerate or open old conversations in the UI.
        let chat = try await api.getPeerChat(accessToken:try await currentToken(),userId:userId)
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        return chat?.otherUser?.displayName
    }

    func resolveCallChat(_ call: CallDTO, peer: String) async throws -> ChatSummary? {
        guard call.chatVisible != false else { return nil }
        let chat: ChatSummary?
        if let id = call.chatId?.uuidString.lowercased() {
            guard !isDeleted(id) else { return nil }
            chat = try await getChat(chatId: id)
        } else if call.chatVisible == nil {
            chat = try await api.getPeerChat(accessToken: try await currentToken(), userId: peer)
        } else { return nil }
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        guard let chat, !isDeleted(chat.chatId), chat.chatType == "direct",
              UUID(uuidString: chat.otherUser?.id ?? "") == UUID(uuidString: peer),
              let created = ISO8601Cocoa.parse(chat.createdAt), created <= call.createdAt else { return nil }
        if preferenceCache[chat.chatId] != chat { persistChats([chat]) }
        return chat
    }

    func createOrOpenDirectChat(otherUserId: String) async throws -> ChatSummary {
        let chat = try await api.createDirect(accessToken: try await currentToken(), otherUserId: otherUserId)
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        try messageStore?.saveChatValue(chatId: chat.chatId, name: "opened_on_device", value: "1")
        persistChats([chat])
        return chat
    }

    func getOrCreateSavedChat() async throws -> ChatSummary {
        #if UNYRI_ALPHA_LAN
        UnyriSavedRouteQA.record(7)
        #endif
        let cached = await loadCachedChatsAsync()
        #if UNYRI_ALPHA_LAN
        UnyriSavedRouteQA.record(8, min(cached.count, 100))
        #endif
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        if let chat = cached.first(where: { $0.chatType == "saved" }) { return chat }
        #if UNYRI_ALPHA_LAN
        UnyriSavedRouteQA.record(9)
        #endif
        let chat = try await api.getSaved(accessToken: try await currentToken())
        #if UNYRI_ALPHA_LAN
        UnyriSavedRouteQA.record(10)
        #endif
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        persistChats([chat])
        return chat
    }

    func getChat(chatId: String) async throws -> ChatSummary {
        guard !isDeleted(chatId) else { throw APIError.http(404) }
        let revision = preferenceRevision
        let chat = try await api.getChat(accessToken: try await currentToken(), chatId: chatId)
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        guard !isDeleted(chatId) else { throw APIError.http(404) }
        onPeerReceipt?(chat.chatId, chat.otherMemberDeliveredSequence, chat.otherMemberReadSequence)
        return revision == preferenceRevision ? projectPreference(chat) : preserveLocalMute(chat)
    }

    func updatePreference(chatId: String, action: String, body: ChatsAPI.PreferenceBody = .init()) async throws -> ChatSummary {
        guard ["mute", "unmute", "block", "unblock"].contains(action),
              action != "block" || body.blockedUserId != nil else { throw APIError.http(400) }
        if action == "mute" || action == "unmute" {
            try enqueuePreference(chat: chatId, kind: "mute", enabled: action == "mute")
            guard let cached = preferenceCache[chatId] ?? loadCachedChats(includePreviews: false).first(where: { $0.chatId == chatId }) else { throw APIError.http(404) }
            let chat = projectPreference(cached)
            try store?.upsertChats([chat])
            preferenceCache[chatId] = chat
            cacheRevision &+= 1
            Task { [weak self] in await self?.replayPreferences() }
            return chat
        }
        try await api.updatePreference(accessToken: try await currentToken(), chatId: chatId, action: action, body: body)
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        guard !isDeleted(chatId) else { throw APIError.http(404) }
        // A successful mutation must not depend on a second network request.
        // Read the latest cache after the await to preserve unrelated changes.
        var chat: ChatSummary
        if let cached = preferenceCache[chatId] ?? loadCachedChats(includePreviews: false).first(where: { $0.chatId == chatId }) {
            chat = cached
        } else {
            chat = try await getChat(chatId: chatId)
        }
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        switch action {
        case "mute": chat.isMuted = true; chat.mutedUntil = nil
        case "unmute": chat.isMuted = false; chat.mutedUntil = nil
        case "block": chat.isBlocked = true; chat.blockedUserId = body.blockedUserId
        case "unblock": chat.isBlocked = false; chat.blockedUserId = nil
        default: break
        }
        try store?.upsertChats([chat])
        preferenceCache[chatId] = chat
        cacheRevision &+= 1
        if action == "block" || action == "unblock" {
            if let userID = body.blockedUserId ?? chat.otherUser?.id {
                applyAccountBlock(userID: userID, blocked: action == "block")
                onAccountBlockChange?(userID, action == "block")
            }
        }
        return chat
    }

    var onPersonalClear: ((String, Int64) async throws -> Bool)?
    var onCallClear: ((String, Date) -> Void)?
    var onLocalBoundedClear: ((String, Set<String>) -> Void)?
    var clearServerOrigin: String?
    var currentClearServer: (() -> String)?
    var clearSyncSequence: (() -> Int64)?
    private var replayingClears = false
    private var replayClearsAgain = false
    @Published private(set) var clearReplayError: String?
    var onDeleteConversation: ((String) async throws -> Void)?
    func deleteForBoth(_ chatId: String, forEveryone: Bool = true, requestId: String) async throws {
        let (owner, server) = try clearIdentity()
        guard let messageStore, !isDeleted(chatId) else { throw CancellationError() }
        if let existing = try messageStore.chatClearCommand(owner:owner,server:server,requestId:requestId) {
            guard existing.chatId == chatId, existing.forEveryone == forEveryone else { throw APIError.http(409) }
        } else {
            let command = try messageStore.captureChatClear(owner:owner,chat:chatId,requestId:requestId,everyone:forEveryone,
                minimumSequence:max(auth.historyBeforeSequence ?? 0,clearSyncSequence?() ?? 0))
            let cancelled = try messageStore.enqueueChatClear(owner:owner,server:server,command:command)
            onLocalBoundedClear?(chatId,cancelled)
            if let before = command.callsBefore { onCallClear?(chatId,before) }
            cacheRevision &+= 1
            await refreshUnreadCounts()
            _ = try clearIdentity(owner:owner,server:server)
        }
        replayClearsAgain = true
        Task { [weak self] in await self?.replayPendingChatClears() }
    }
    private func clearIdentity(owner expectedOwner: String? = nil, server expectedServer: String? = nil) throws -> (String, String) {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration, let owner = auth.currentUser?.id, let server = clearServerOrigin,
              currentClearServer?() == server, expectedOwner == nil || owner == expectedOwner,
              expectedServer == nil || server == expectedServer else { throw CancellationError() }
        return (owner,server)
    }
    func replayPendingChatClears() async {
        if replayingClears { replayClearsAgain = true; return }
        replayingClears = true
        defer { replayingClears = false }
        repeat {
            replayClearsAgain = false
            do {
                let (owner,server) = try clearIdentity()
                guard let messageStore else { return }
                for command in try messageStore.pendingChatClears(owner:owner,server:server) {
                    _ = try clearIdentity(owner:owner,server:server)
                    do {
                        do { _ = try await onPersonalClear?(command.chatId,command.throughSequence) }
                        catch {
                            _ = try clearIdentity(owner:owner,server:server)
                            // Recoverable file cleanup must not block server clear.
                        }
                        _ = try clearIdentity(owner:owner,server:server)
                        try await api.clearHistory(accessToken:try await currentToken(),command:command)
                        _ = try clearIdentity(owner:owner,server:server)
                        try messageStore.completeChatClear(owner:owner,server:server,requestId:command.requestId)
                        clearReplayError = nil
                    } catch is CancellationError { return }
                    catch APIError.server(let code, _) where code == "chat_not_found" {
                        _ = try clearIdentity(owner:owner,server:server)
                        try messageStore.completeChatClear(owner:owner,server:server,requestId:command.requestId)
                        clearReplayError = nil
                    }
                    catch APIError.network {
                        _ = try clearIdentity(owner:owner,server:server)
                    }
                    catch {
                        _ = try clearIdentity(owner:owner,server:server)
                        // Unsupported older endpoints retain the bounded command.
                        // Never substitute the unbounded /delete operation.
                        clearReplayError = error.localizedDescription
                    }
                }
            } catch { return }
        } while replayClearsAgain && !Task.isCancelled
    }
    func refreshPersonalClears() async throws {
        let clears = try await api.personalClears(accessToken: try await currentToken())
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        var changed = false
        for clear in clears {
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            if let before = clear.callsBefore, let messageStore {
                let callChanged = try messageStore.clearCallActivity(chat: clear.chatId, before: before)
                onCallClear?(clear.chatId, before)
                changed = changed || callChanged
            }
            guard let onPersonalClear else { throw CancellationError() }
            if try await onPersonalClear(clear.chatId, clear.throughSequence) { changed = true }
        }
        if changed {
            cacheRevision &+= 1
            await refreshUnreadCounts()
        }
    }
    func pins() async throws -> Set<String> {
        try preferenceIdentity()
        let revision = preferenceRevision
        let result = try await api.pins(accessToken: try await currentToken())
        try preferenceIdentity()
        let pins = revision == preferenceRevision ? projectPins(Set(result.chatIds)) : cachedPins()
        UserDefaults.standard.set(Array(pins), forKey: pinsKey)
        return pins
    }
    func setPins(_ ids: Set<String>, pinned: Bool) async throws -> Set<String> {
        try preferenceIdentity()
        for id in ids { try enqueuePreference(chat: id, kind: "pin", enabled: pinned) }
        let result = cachedPins()
        UserDefaults.standard.set(Array(result), forKey: pinsKey)
        Task { [weak self] in await self?.replayPreferences() }
        return result
    }
    // MARK: - Account-scoped durable archive preferences
    // The snapshot lives in the existing account database, never in a global
    // device preference. A different server cannot reuse its cached archive.
    private let archivesWorkspace = "__chat_archives__"
    private var archivesSnapshotKey: String { "server|" + preferenceOrigin }
    @Published private(set) var archivedChatIDs = Set<String>()
    @Published private(set) var archiveReplayError: String?
    private var refreshingArchives = false
    private var refreshArchivesAgain = false

    private func saveArchiveSnapshot(_ ids: Set<String>) throws {
        try preferenceIdentity()
        guard let messageStore else { throw CancellationError() }
        let value = String(decoding: try JSONEncoder().encode(ids.sorted()), as: UTF8.self)
        try messageStore.saveChatValue(chatId: archivesWorkspace, name: archivesSnapshotKey, value: value)
    }
    private func archiveSnapshot() throws -> Set<String> {
        guard let messageStore else { throw CancellationError() }
        guard let raw = try messageStore.chatValue(chatId: archivesWorkspace, name: archivesSnapshotKey) else { return [] }
        return Set(try JSONDecoder().decode([String].self, from: Data(raw.utf8)))
    }
    func cachedArchives() -> Set<String> {
        guard generation == auth.sessionGeneration, auth.currentUser != nil,
              preferenceOrigin == AppConfig.storageOrigin else { return [] }
        var result = (try? archiveSnapshot()) ?? []
        for intent in (try? preferenceIntents())?.values ?? Dictionary<String, PreferenceIntent>().values where intent.kind == "archive" {
            if intent.enabled && !isDeleted(intent.chat) { result.insert(intent.chat) } else { result.remove(intent.chat) }
        }
        return result.filter { !isDeleted($0) }
    }
    func archives() async throws -> Set<String> {
        try preferenceIdentity()
        if refreshingArchives { refreshArchivesAgain = true; return cachedArchives() }
        refreshingArchives = true
        defer { refreshingArchives = false }
        repeat {
            refreshArchivesAgain = false
            let revision = preferenceRevision
            let response = try await api.archives(accessToken: try await currentToken())
            try preferenceIdentity()
            // An older GET cannot overwrite a local change/replay completed during it.
            if revision == preferenceRevision { try saveArchiveSnapshot(Set(response.chatIds)) }
            archivedChatIDs = cachedArchives()
        } while refreshArchivesAgain && !Task.isCancelled
        return archivedChatIDs
    }
    func setArchived(_ ids: Set<String>, archived: Bool) async throws -> Set<String> {
        try preferenceIdentity()
        guard !ids.isEmpty, ids.count <= 100, ids.allSatisfy({ id in
            UUID(uuidString: id) != nil && !isDeleted(id) && preferenceCache[id]?.chatType == "direct"
        }) else { throw APIError.http(400) }
        for id in ids { try enqueuePreference(chat: id, kind: "archive", enabled: archived) }
        archiveReplayError = nil
        let result = cachedArchives()
        archivedChatIDs = result
        cacheRevision &+= 1
        Task { [weak self] in await self?.replayPreferences() }
        return result
    }
    // MARK: - End archive preferences

    func reportChat(chatId: String, reason: String, peer: String?, description: String) async throws {
        try await api.updatePreference(accessToken: try await currentToken(), chatId: chatId, action: "report", body: .init(reason: reason, reportedUserId: peer, description: description))
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }

    // MARK: - Local chat-list cache

    /// Hydrates the cached chat list before any network round-trip, so the
    /// home screen shows known chats even when offline or on a cold start.
    /// Overlays the LOCAL last-message preview (never a network round-trip;
    /// never the backend, which exposes no preview text).
    func loadCachedChats(includePreviews: Bool = true) -> [ChatSummary] {
        let owner = auth.currentUser?.id
        var chats = ((try? store?.loadChats()) ?? []).map(projectPreference)
        var repaired = Set<String>()
        if includePreviews, let messageStore {
            // The synchronous path never starts repair on the main actor.
            // Until the asynchronous gate completes, show no stale plaintext.
            repaired = Set(chats.compactMap { chat in
                (try? messageStore.legacyLifecycleRepairIsComplete(chatId: chat.chatId)) == true ? chat.chatId : nil
            })
            let previews = repaired.isEmpty ? [:] : (try? messageStore.latestPreviewByChat()) ?? [:]
            for index in chats.indices {
                let id = chats[index].chatId
                chats[index].lastPreview = repaired.contains(id) ? previews[id].map(Self.chatPreview) : nil
            }
        }
        if includePreviews {
            let visible = (try? messageStore?.visibleChatIDs(historyCutoff: auth.historyBeforeSequence ?? 0, ownerId: owner)) ?? []
            chats = chats.filter { $0.chatType == "saved" || (repaired.contains($0.chatId) && visible.contains($0.chatId)) }
        }
        return chats.filter { !isDeleted($0.chatId) && (try? messageStore?.personalChatHidden($0.chatId)) != true }
    }

    func loadCachedChatsAsync() async -> [ChatSummary] {
        let owner = auth.currentUser?.id
        let cutoff = auth.historyBeforeSequence ?? 0
        let cached = (try? await store?.loadChatsAsync()) ?? []
        func currentSnapshot() -> Bool {
            !Task.isCancelled && generation == auth.sessionGeneration && owner != nil &&
                owner == auth.currentUser?.id && cutoff == (auth.historyBeforeSequence ?? 0) &&
                preferenceOrigin == AppConfig.storageOrigin
        }
        guard currentSnapshot(), let messageStore else { return [] }
        let cachedIDs = Set(cached.map(\.chatId))
        do {
            // An old ACK may have committed a lifecycle event without applying
            // its projection. Repair in bounded background chunks before the
            // first preview, visibility or unread snapshot can expose it.
            for chatId in cachedIDs.sorted() {
                guard currentSnapshot() else { return [] }
                try await messageStore.repairLegacyLifecycle(chatId: chatId)
                guard currentSnapshot() else { return [] }
            }
        } catch { return [] }
        let queueID = preferenceQueueID
        guard let snapshot = try? await messageStore.readAsync({ reader in
            let previews = try reader.latestPreviewByChat()
            let activity = try reader.latestActivityByChat()
            let visible = try reader.visibleChatIDs(historyCutoff: cutoff, ownerId: owner)
            let hidden = try reader.personallyHiddenChatIDs()
            let unread = try reader.unreadCounts(ownerId: owner ?? "", historyCutoff: cutoff)
            let intents = try reader.workspaceValues(chatId: queueID).values.compactMap {
                try? JSONDecoder().decode(PreferenceIntent.self, from: Data($0.utf8))
            }
            var chats = cached.filter { ($0.chatType == "saved" || visible.contains($0.chatId)) && !hidden.contains($0.chatId) }
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            for index in chats.indices {
                let id = chats[index].chatId
                chats[index].lastPreview = previews[id].map(Self.chatPreview)
                if let date = activity[id], date > (chats[index].lastMessageAt.flatMap(ISO8601Cocoa.parse) ?? .distantPast) {
                    chats[index].lastMessageAt = formatter.string(from: date)
                }
                if let intent = intents.first(where: { $0.chat == id && $0.kind == "mute" }) {
                    chats[index].isMuted = intent.enabled; chats[index].mutedUntil = nil
                }
            }
            chats.sort {
                let left = $0.lastMessageAt.flatMap(ISO8601Cocoa.parse) ?? .distantPast
                let right = $1.lastMessageAt.flatMap(ISO8601Cocoa.parse) ?? .distantPast
                return left == right ? $0.chatId > $1.chatId : left > right
            }
            return (chats, unread.filter { cachedIDs.contains($0.key) && visible.contains($0.key) && !hidden.contains($0.key) })
        }) else { return [] }
        guard currentSnapshot() else { return [] }
        let counts = snapshot.1.filter { !deletedChatIDs.contains($0.key) }
        if unreadCounts != counts { unreadCounts = counts }
        return snapshot.0.filter { !deletedChatIDs.contains($0.chatId) }
    }

    /// Maps the store's self-contained preview to the UI's `ChatPreview`.
    /// Lives here (not in MessageStore) because this file is compiled only
    /// into the full app, where both types are in scope.
    nonisolated private static func chatPreview(_ preview: MessagePreview) -> ChatPreview {
        switch preview {
        case .text(let text): return .text(text)
        case .conferenceInvitation: return .text(UnyriL10n.string(ConferenceInvitationMetadata.fallbackText))
        case .image: return .photo
        case .voice: return .voice
        case .video: return .video
        case .file(let filename): return .file(filename)
        case .deleted: return .deleted
        }
    }

    /// Merges a `GET /chats` page into the cache. Never deletes: a failed or
    /// empty refresh must not erase previously known chats.
    func persistChats(_ chats: [ChatSummary], refreshUnread: Bool = true) {
        guard generation == auth.sessionGeneration else { return }
        let chats = chats.map(projectPreference)
        try? store?.upsertChats(chats)
        for chat in chats { preferenceCache[chat.chatId] = chat }
        for chat in chats {
            if let sequence = chat.ownReadSequence, sequence > 0 {
                _ = try? messageStore?.markLocallyRead(chatId: chat.chatId, sequence: sequence)
            }
        }
        if refreshUnread { Task { await refreshUnreadCounts() } }
        cacheRevision &+= 1
    }

    func peerOnline(chatId: String) async throws -> Bool {
        let token = try await currentToken()
        let result = try await api.presence(accessToken: token, chatId: chatId)
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        return result.online
    }

    private func currentToken() async throws -> String {
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        let token = try await auth.currentAccessToken()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        return token
    }
}
