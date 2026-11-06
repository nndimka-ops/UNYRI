import Foundation
import Combine

/// Transport only. Creating/accepting calls must wait until the media engine
/// and signaling are ready; HTTP success does not mean media is connected.
struct CallsAPI {
    let client: APIClient

    func history(accessToken: String, peer: UUID?, before: UUID?) async throws -> CallHistoryPage {
        var query: [URLQueryItem] = []
        if let peer { query.append(URLQueryItem(name: "other_user_id", value: peer.uuidString.lowercased())) }
        if let before { query.append(URLQueryItem(name: "before", value: before.uuidString.lowercased())) }
        return try await client.get("/calls/history", accessToken: accessToken, query: query, as: CallHistoryPage.self)
    }

    func historyDeletions(accessToken: String, after: Int64) async throws -> CallDeletionPage {
        try await client.get("/calls/history/deletions", accessToken: accessToken,
            query: [URLQueryItem(name: "after", value: String(after))], as: CallDeletionPage.self)
    }
    func restoredHistoryDeletions(accessToken: String, ids: [UUID]) async throws -> Set<UUID> {
        guard ids.count <= 500 else { throw APIError.http(400) }
        struct Request: Encodable { let messageIds: [String] = []; let callIds: [UUID] }
        struct Response: Decodable { let deletedCallIds: [UUID]? }
        let response: Response = try await client.post("/history-backup/restore-check", accessToken: accessToken,
            body: Request(callIds: ids), as: Response.self)
        guard let removed = response.deletedCallIds, Set(removed).count == removed.count,
              Set(removed).isSubset(of: Set(ids)) else { throw APIError.http(426) }
        return Set(removed)
    }
    private struct DeleteRequest: Encodable { let everyone: Bool }
    private struct DeleteResponse: Decodable { let ok: Bool }
    func deleteHistory(accessToken: String, id: UUID, everyone: Bool) async throws {
        let _: DeleteResponse = try await client.post("/calls/\(id.uuidString.lowercased())/history/delete", accessToken: accessToken,
            body: DeleteRequest(everyone: everyone), as: DeleteResponse.self)
    }

    enum Action: String {
        case accept, reject, cancel, hangup, connected, failed
    }

    private struct CreateRequest: Encodable {
        let calleeUserId: UUID
        let mediaKind: CallMediaKind
    }
    private struct EmptyBody: Encodable {}
    func markRead(accessToken: String, callId: UUID) async throws {
        let _: Bool? = try await client.post("/calls/\(callId.uuidString.lowercased())/read", accessToken: accessToken,
                                            body: EmptyBody(), as: Optional<Bool>.self)
    }
    private struct ICEResponse: Decodable { let iceServers: [CallICEServer] }

    func create(accessToken: String, calleeUserId: UUID, mediaKind: CallMediaKind) async throws -> CallDTO {
        try await client.post("/calls", accessToken: accessToken,
                              body: CreateRequest(calleeUserId: calleeUserId, mediaKind: mediaKind),
                              as: CallDTO.self)
    }

    func active(accessToken: String) async throws -> CallDTO? {
        // No active call is JSON null (200), not an empty object or 404.
        try await client.get("/calls/active", accessToken: accessToken, as: Optional<CallDTO>.self)
    }

    func get(accessToken: String, callId: UUID) async throws -> CallDTO {
        try await client.get("/calls/\(callId.uuidString.lowercased())", accessToken: accessToken, as: CallDTO.self)
    }

    func perform(_ action: Action, accessToken: String, callId: UUID) async throws -> CallDTO {
        try await client.post("/calls/\(callId.uuidString.lowercased())/\(action.rawValue)",
                              accessToken: accessToken, body: EmptyBody(), as: CallDTO.self)
    }

    func iceServers(accessToken: String) async throws -> [CallICEServer] {
        let response = try await client.get("/calls/ice-servers", accessToken: accessToken, as: ICEResponse.self)
        return response.iceServers
    }
}

/// Account-scoped access. TURN credentials remain in memory and are fetched
/// from the server; no permanent TURN secret is bundled with the application.
@MainActor
final class CallsRepository: ObservableObject {
    private var deletedHistory = CallDeletionState()
    private(set) var historyRevision: UInt64 = 0
    @Published private(set) var history: [CallDTO] = [] { didSet { historyRevision &+= 1 } }
    private let historyStore: CallHistoryStore?
    let ownerId: UUID?
    var currentSessionGeneration: UInt64 { auth.sessionGeneration }
    var currentDeviceId: UUID? { generation == auth.sessionGeneration ? auth.currentDeviceId : nil }
    private let api: CallsAPI
    private let auth: AuthRepository
    private let generation: UInt64
    private let deletionServerOrigin: String?
    var currentDeletionServer: (() -> String)?
    var currentDeletionOwner: (() -> UUID?)?
    private var clearedBefore: Date = .distantPast
    var messageStore: MessageStore? { didSet {
        if visibleSince == .distantFuture { try? messageStore?.suspendCallActivity(owner: ownerId?.uuidString.lowercased() ?? "") }
        try? hydrateLocalDeletions()
    } }
    var beforeHistoryRefresh: (() async throws -> Void)?
    var projectHistory: (([CallDTO], Date) async throws -> (rows: [CallDTO], changed: Bool))?
    var activityChanged: (() async -> Void)?
    private var reconciliation: Task<Void, Never>?
    private var reconcileAgain = false
    private var requestedCalls = Set<UUID>()
    private var restoredHistoryIds = Set<UUID>()
    private var visibleSince: Date { auth.cachedScreen("calls.history.since", as: Date.self) ?? auth.historyContext?.beforeDate ?? .distantFuture }
    private func locallyVisible(_ call: CallDTO) -> Bool { call.createdAt >= visibleSince || restoredHistoryIds.contains(call.id) }

    var chatHistory: [CallDTO] { history.filter { call in
        guard call.chatVisible != false, let chat = call.chatId?.uuidString.lowercased() else { return false }
        return (try? messageStore?.callVisible(owner: ownerId?.uuidString.lowercased() ?? "", id: call.id.uuidString.lowercased())) == true &&
            (try? messageStore?.isChatDeleted(chat)) != true && call.createdAt > ((try? messageStore?.callChatCutoff(chat)) ?? .distantPast)
    } }

    func reconcile(callId: UUID? = nil) {
        guard generation == auth.sessionGeneration else { return }
        if let callId { requestedCalls.insert(callId) }
        reconcileAgain = true
        guard reconciliation == nil else { return }
        reconciliation = Task { [weak self] in
            guard let self else { return }
            defer { self.reconciliation = nil }
            while self.reconcileAgain && !Task.isCancelled {
                self.reconcileAgain = false
                let ids = self.requestedCalls; self.requestedCalls.removeAll()
                // Deletion commands must not depend on a successful history refresh.
                do { try await self.flushPendingDeletions() } catch { }
                do {
                    _ = try await self.refreshHistory()
                    for id in ids { _ = try? await self.get(callId: id) }
                    try await self.flushPendingReads()
                } catch { /* Retry on the next socket/resume/recovery event. */ }
            }
        }
    }
    func stopReconciliation() { reconciliation?.cancel(); reconciliation = nil; reconcileAgain = false; requestedCalls.removeAll() }
    func finishSessionCacheWrites() async { await historyStore?.barrier() }

    func acknowledgeViewed(_ ids: Set<UUID>) {
        guard generation == auth.sessionGeneration, let ownerId, let messageStore else { return }
        let eligible = Set(history.filter { ids.contains($0.id) && $0.unread && $0.isEnded && $0.createdAt >= visibleSince }.map { $0.id.uuidString.lowercased() })
        guard !eligible.isEmpty else { return }
        do {
            try messageStore.markCallsViewed(owner: ownerId.uuidString.lowercased(), ids: eligible)
            history = history.map { var call = $0; if eligible.contains(call.id.uuidString.lowercased()) { call.unread = false }; return call }
            Task { [weak self] in await self?.activityChanged?() }
            reconcile()
        } catch { /* Do not claim read if durable local acknowledgement failed. */ }
    }

    func applyCallClear(chat: String, before: Date) {
        try? hydrateLocalDeletions()
        let rows = history.map { value -> CallDTO in
            var call = value
            if call.chatId == UUID(uuidString: chat), call.createdAt <= before { call.chatVisible = false; call.unread = false }
            return call
        }
        if rows != history { history = rows }
    }
    func chatDeleted(_ chat: String) { applyCallClear(chat: chat, before: .distantFuture) }

    private func synchronizeProjection() async throws {
        try requireSession()
        guard visibleSince != .distantFuture || !restoredHistoryIds.isEmpty, let projectHistory else { return }
        let snapshot = history
        let snapshotById = Dictionary(uniqueKeysWithValues: snapshot.map { ($0.id, $0) })
        let projected = try await projectHistory(snapshot, restoredHistoryIds.isEmpty ? visibleSince : .distantPast)
        try requireSession()
        var current = Dictionary(uniqueKeysWithValues: history.map { ($0.id, $0) })
        for var call in projected.rows where current[call.id] == snapshotById[call.id] {
            if let ownerId, let messageStore {
                call.unread = (try? messageStore.callUnread(owner: ownerId.uuidString.lowercased(), id: call.id.uuidString.lowercased())) ?? false
            }
            current[call.id] = call
        }
        let updated = current.values.sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString > $1.id.uuidString : $0.createdAt > $1.createdAt }
        if updated != history { history = updated }
        if projected.changed { await activityChanged?() }
    }
    private func flushPendingReads() async throws {
        guard let ownerId, let messageStore, visibleSince != .distantFuture else { return }
        let owner = ownerId.uuidString.lowercased()
        let ids = try messageStore.pendingCallReads(owner: owner, since: visibleSince)
        for id in ids {
            guard let uuid = UUID(uuidString: id) else { continue }
            let token = try await accessToken()
            do { try await api.markRead(accessToken: token, callId: uuid) }
            catch APIError.http(404) { }
            catch APIError.server(let code, _) where code == "not_found" { }
            try requireSession()
            try messageStore.completeCallRead(owner: owner, id: id)
        }
    }

    private func hydrateLocalDeletions() throws {
        try requireSession()
        guard let ownerId, let deletionServerOrigin, let messageStore else { return }
        let ids = try messageStore.callDeletionIDs(owner: ownerId.uuidString.lowercased(), server: deletionServerOrigin)
        deletedHistory.ids.formUnion(ids.compactMap(UUID.init(uuidString:)))
        pruneDeletedHistory()
    }
    private func flushPendingDeletions() async throws {
        try requireSession()
        guard let ownerId, let deletionServerOrigin, let messageStore else { return }
        let owner = ownerId.uuidString.lowercased()
        try hydrateLocalDeletions()
        let pending = try messageStore.pendingCallDeletions(owner: owner, server: deletionServerOrigin)
        for command in pending {
            guard let id = UUID(uuidString: command.callId) else { continue }
            let token = try await accessToken()
            do { try await api.deleteHistory(accessToken: token, id: id, everyone: command.everyone) }
            catch APIError.http(let status) where status == 404 || status == 403 { }
            catch APIError.server(let code, _) where code == "not_found" || code == "forbidden" { }
            catch {
                try requireSession()
                continue // One transient failure cannot starve the remaining commands.
            }
            try requireSession()
            try messageStore.completeCallDeletion(owner: owner, server: deletionServerOrigin, id: command.callId, everyone: command.everyone)
        }
    }

    init(api: CallsAPI, auth: AuthRepository, historyFile: URL? = nil, ownerId: UUID? = nil, deletionServerOrigin: String? = nil) {
        self.api = api
        self.auth = auth
        self.generation = auth.sessionGeneration
        self.historyStore = historyFile.map { CallHistoryStore(file: $0) }
        self.ownerId = ownerId
        self.deletionServerOrigin = deletionServerOrigin
    }

    func clearServerHistory(beforeMs: Int64) async throws {
        try requireSession()
        guard beforeMs >= 0 else { throw CocoaError(.coderInvalidValue) }
        clearedBefore = max(clearedBefore, Date(timeIntervalSince1970: Double(beforeMs) / 1000))
        try messageStore?.clearCallActivity(before: clearedBefore)
        let rows = try await historyStore?.clear(beforeMs: beforeMs) ?? []
        try requireSession()
        history = history.filter { $0.createdAt >= clearedBefore }
        mergeHistory(rows)
    }
    func loadCachedHistory() async {
        guard let historyStore else { return }
        do {
            try hydrateLocalDeletions()
            let removed = try await historyStore.deletions()
            let rows = try await historyStore.load()
            let restored = try await historyStore.restoredIDs()
            try requireSession()
            restoredHistoryIds.formUnion(restored)
            deletedHistory.ids.formUnion(removed.ids)
            deletedHistory.cursor = max(deletedHistory.cursor, removed.cursor)
            if visibleSince != .distantFuture && restoredHistoryIds.isEmpty { try messageStore?.clearCallActivity(before: visibleSince) }
            mergeHistory(rows)
            try await synchronizeProjection()
        } catch { /* Keep current history if cache is unavailable. */ }
    }

    @discardableResult
    func refreshHistory(peer: UUID? = nil, before: UUID? = nil) async throws -> UUID? {
        try hydrateLocalDeletions()
        let token = try await accessToken()
        try await beforeHistoryRefresh?()
        try requireSession()
        let restoredIds = restoredHistoryIds.sorted { $0.uuidString < $1.uuidString }
        for start in stride(from: 0, to: restoredIds.count, by: 500) {
            let ids = Array(restoredIds[start..<min(start + 500, restoredIds.count)])
            let deleted = try await api.restoredHistoryDeletions(accessToken: token, ids: ids)
            try requireSession()
            _ = try await historyStore?.remove(ids: deleted)
            try requireSession()
            deletedHistory.ids.formUnion(deleted); restoredHistoryIds.subtract(deleted); pruneDeletedHistory()
        }
        do { try await syncHistoryDeletions(token: token) }
        catch APIError.http(404) { /* Older server: history still works; deletion reports an error. */ }
        catch APIError.server(let code, _) where code == "not_found" { }
        let page = try await api.history(accessToken: token, peer: peer, before: before)
        try requireSession()
        if let since = page.visibleSince { auth.saveScreen("calls.history.since", generation: generation, value: since) }
        if visibleSince != .distantFuture && restoredHistoryIds.isEmpty { try messageStore?.clearCallActivity(before: visibleSince) }
        let rows = page.items.filter { ($0.callerUserId == ownerId || $0.calleeUserId == ownerId) && $0.createdAt >= visibleSince }
        let saved = try await historyStore?.merge(rows) ?? rows
        try requireSession()
        mergeHistory(saved)
        try await synchronizeProjection()
        return page.nextCursor
    }

    func deleteHistory(id: UUID, everyone: Bool) async throws {
        try requireSession()
        guard let ownerId, let deletionServerOrigin, let messageStore else { throw APIError.http(400) }
        if !deletedHistory.ids.contains(id) {
            guard let call = history.first(where: { $0.id == id }), call.isEnded,
                  locallyVisible(call), CallActivityPolicy.eligible(call, owner: ownerId, since: .distantPast) else { throw APIError.http(400) }
        }
        try messageStore.enqueueCallDeletion(owner: ownerId.uuidString.lowercased(), server: deletionServerOrigin,
                                             id: id.uuidString.lowercased(), everyone: everyone)
        deletedHistory.ids.insert(id)
        history.removeAll { $0.id == id }
        reconcile()
        await activityChanged?()
        try requireSession()
    }

    private func syncHistoryDeletions(token: String) async throws {
        if let historyStore {
            let stored = try await historyStore.deletions()
            try requireSession()
            deletedHistory.ids.formUnion(stored.ids)
            deletedHistory.cursor = max(deletedHistory.cursor, stored.cursor)
        }
        while true {
            let page = try await api.historyDeletions(accessToken: token, after: deletedHistory.cursor)
            try requireSession()
            if page.items.isEmpty { pruneDeletedHistory(); return }
            let ids = Set(page.items.map(\.callId))
            let cursor = max(deletedHistory.cursor, page.items.map(\.sequence).max() ?? 0)
            if let historyStore { _ = try await historyStore.remove(ids: ids, cursor: cursor) }
            try requireSession()
            deletedHistory.ids.formUnion(ids); deletedHistory.cursor = max(deletedHistory.cursor, cursor)
            pruneDeletedHistory()
            if !page.hasMore || page.items.isEmpty { return }
        }
    }

    private func pruneDeletedHistory() {
        let removed = deletedHistory.ids
        try? messageStore?.removeCallActivity(owner: ownerId?.uuidString.lowercased() ?? "", ids: Set(removed.map { $0.uuidString.lowercased() }))
        if history.contains(where: { removed.contains($0.id) }) {
            history.removeAll { removed.contains($0.id) }
        }
    }

    private func remember(_ call: CallDTO) async throws {
        try requireSession()
        guard call.callerUserId == ownerId || call.calleeUserId == ownerId else { return }
        let saved = (try? await historyStore?.merge([call])) ?? [call]
        try requireSession()
        mergeHistory(saved)
        try await synchronizeProjection()
    }

    private func mergeHistory(_ rows: [CallDTO]) {
        var merged = Dictionary(uniqueKeysWithValues: history.filter { locallyVisible($0) && $0.createdAt >= clearedBefore && !deletedHistory.ids.contains($0.id) }.map { ($0.id, $0) })
        for row in rows where locallyVisible(row) && row.createdAt >= clearedBefore && !deletedHistory.ids.contains(row.id) { merged[row.id] = CallActivityPolicy.merged(merged[row.id], row) }
        let updated = merged.values.sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString > $1.id.uuidString : $0.createdAt > $1.createdAt }
        // A poll/cache read with identical rows must not redraw all history subscribers.
        if updated != history { history = updated }
    }

    /// Local terminal calls and deletion policy join the encrypted archive.
    /// No historical call metadata is fetched as a prerequisite for restoration.
    func exportBackupCalls(_ input: HistoryArchive) async throws -> HistoryArchive {
        try requireSession(); try hydrateLocalDeletions()
        guard let ownerId else { throw APIError.missingSession }
        let stored = try await historyStore?.load() ?? []
        let cutoff = try await historyStore?.cutoff() ?? clearedBefore
        try requireSession()
        var byId = Dictionary(uniqueKeysWithValues: stored.map { ($0.id, $0) })
        for call in history { byId[call.id] = CallActivityPolicy.merged(byId[call.id], call) }
        let cached = byId.values.sorted { $0.createdAt == $1.createdAt ? $0.id.uuidString < $1.id.uuidString : $0.createdAt < $1.createdAt }
        var result = input; result.format = 2; result.media = result.media ?? []; result.chats = result.chats ?? []
        result.calls = cached.filter { locallyVisible($0) && !deletedHistory.ids.contains($0.id) }.compactMap { call in
            guard var row = call.archived(owner: ownerId) else { return nil }
            if let chat = row.chat, input.deletedChats.contains(chat) { row.chatVisible = false }
            return row
        }
        if result.calls?.isEmpty == true { result.calls = nil }
        let deleted = Set(deletedHistory.ids.map { $0.uuidString.lowercased() })
        result.callDeletedIds = deleted.isEmpty ? nil : deleted
        let ms = max(0, Int64((max(cutoff, Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000).rounded()))
        result.callClearedBeforeMs = ms == 0 ? nil : ms
        result.callChatClears = try messageStore?.exportCallChatClears()
        if result.callChatClears?.isEmpty == true { result.callChatClears = nil }
        result.calls = result.calls?.map { value in
            var row = value
            if let chat = row.chat, row.createdAtMs <= (result.callChatClears?[chat] ?? -1) { row.chatVisible = false }
            return row
        }
        try result.validate(server: result.server, owner: result.owner)
        return result
    }

    func prepareBackupHistory() async throws {
        var cursor: UUID? = nil
        var seen = Set<UUID>()
        for _ in 0..<1000 {
            try requireSession()
            guard let next = try await refreshHistory(before: cursor) else { return }
            guard seen.insert(next).inserted else { throw HistoryArchive.Failure.conflict }
            cursor = next
        }
        throw HistoryArchive.Failure.invalidArchive
    }

    func restoreBackupCalls(_ input: HistoryArchive) async throws {
        try requireSession()
        guard let ownerId, let historyStore else { throw APIError.missingSession }
        try input.validate(server: input.server, owner: ownerId.uuidString.lowercased())
        let local = try await exportBackupCalls(HistoryArchive(server: input.server, owner: input.owner, snapshot: input.snapshot, messages: []))
        let merged = try HistoryArchiveMerge.merge(local: local, restored: input)
        let deleted = Set((merged.callDeletedIds ?? []).compactMap(UUID.init(uuidString:)))
        _ = try await historyStore.remove(ids: deleted)
        if let cutoff = merged.callClearedBeforeMs { _ = try await historyStore.clear(beforeMs: cutoff) }
        try requireSession()
        for (chat, ms) in merged.callChatClears ?? [:] {
            try messageStore?.clearCallActivity(chat: chat, before: Date(timeIntervalSince1970: Double(ms) / 1000))
        }
        let rows = try (merged.calls ?? []).map { try CallDTO(restored: $0) }
        try await historyStore.restore(rows)
        try requireSession()
        deletedHistory.ids.formUnion(deleted); restoredHistoryIds.formUnion(rows.map(\.id))
        pruneDeletedHistory(); mergeHistory(rows)
        // Projection is refreshed after call-only chat descriptors are imported.
    }

    func active() async throws -> CallDTO? {
        let token = try await accessToken()
        let call = try await api.active(accessToken: token)
        try requireSession()
        if let call { try await remember(call) }
        return call
    }

    func get(callId: UUID) async throws -> CallDTO {
        let token = try await accessToken()
        let call = try await api.get(accessToken: token, callId: callId)
        try requireSession()
        try await remember(call)
        return call
    }

    func create(calleeUserId: UUID, mediaKind: CallMediaKind) async throws -> CallDTO {
        let token = try await accessToken()
        let call = try await api.create(accessToken: token, calleeUserId: calleeUserId, mediaKind: mediaKind)
        try requireSession()
        try await remember(call)
        return call
    }

    func perform(_ action: CallsAPI.Action, callId: UUID) async throws -> CallDTO {
        let token = try await accessToken()
        let call = try await api.perform(action, accessToken: token, callId: callId)
        try requireSession()
        try await remember(call)
        return call
    }

    func iceServers() async throws -> [CallICEServer] {
        let token = try await accessToken()
        let servers = try await api.iceServers(accessToken: token)
        try requireSession()
        return servers
    }

    private func accessToken() async throws -> String {
        try requireSession()
        let token = try await auth.currentAccessToken()
        try requireSession()
        return token
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
        if let deletionServerOrigin, let currentDeletionServer,
           deletionServerOrigin != currentDeletionServer() { throw CancellationError() }
        if let currentDeletionOwner, currentDeletionOwner() != ownerId { throw CancellationError() }
    }
}
