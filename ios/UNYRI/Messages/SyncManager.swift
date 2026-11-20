import Foundation

/// Drives `GET /sync` forward through `MessageIngestor` until fully caught
/// up. Triggered on login/app-start and every WebSocket reconnect. Serialized
/// on the MainActor; overlapping triggers request one trailing pass, even when
/// the previous network request fails.
@MainActor
final class SyncManager {
    enum HistoryPreparationFailure: Error { case incompleteSource }
    private let api: SyncAPI
    private let ingestor: MessageIngestor
    private let auth: AuthRepository
    private let sessionStore: SessionStore
    private let clock: () -> TimeInterval
    private var warnedFutureHistoryClear = false
    private func admitsHistoryClear(_ sequence: Int64) -> Bool {
        guard sequence <= (sessionStore.lastSyncedSequence() ?? 0) else {
            if !warnedFutureHistoryClear {
                warnedFutureHistoryClear = true
                NSLog("UNYRI: refused history clear above local sync cursor")
            }
            return false
        }
        return sequence >= 0
    }
    private var isRunning = false
    private var syncRequested = false
    private var catchUpPages = 0
    private var retryAfter: TimeInterval = 0
    var onHistoryClear: ((Int64, Int64) async throws -> Void)?
    var beforeSync: (() async throws -> Void)?
    var onDeletedChat: ((String) async throws -> Void)?
    /// True when a deletion was already applied completely. The server lists every
    /// deleted chat on every sync; replaying them purged files and wiped every decoded
    /// image on each return to the app (owner report b71e022e: all photos re-decoded,
    /// placeholders flashed). A deletion that failed half-way is still retried.
    var isDeletionApplied: ((String) -> Bool)?
    var onPhaseChange: ((ConnectionSyncPhase) -> Void)?

    init(api: SyncAPI, ingestor: MessageIngestor, auth: AuthRepository, sessionStore: SessionStore,
         clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.api = api
        self.ingestor = ingestor
        self.auth = auth
        self.sessionStore = sessionStore
        self.clock = clock
    }

    func runCatchUpSync() async {
        guard clock() >= retryAfter else { return }
        syncRequested = true
        guard !isRunning else { return }
        isRunning = true
        defer { isRunning = false; retryAfter = max(retryAfter, clock() + 2) }
        catchUpPages = 0
        let started = clock()
        for _ in 0..<2 {
            syncRequested = false
            let completed = await ServerWorkDeadline.$uptime.withValue(started + 10) { await runOnePass(started: started) }
            guard completed, syncRequested, !Task.isCancelled, clock() >= retryAfter else { break }
        }
    }

    private func runOnePass(started: TimeInterval) async -> Bool {
        guard !Task.isCancelled else { return false }
        let generation = auth.sessionGeneration
        var completed = false
        var syncedItems = 0
        var budget = ServerWorkBudget(now: started)
        UnyriDiag.sync(finished: false)
        onPhaseChange?(.running)
        defer {
            if !completed { retryAfter = clock() + 30 }
            UnyriDiag.sync(finished: true, items: syncedItems, durationMs: (clock() - started) * 1000, success: completed)
            if generation == auth.sessionGeneration { onPhaseChange?(completed ? .idle : .failed) }
        }
        let token: String
        do { token = try await auth.currentAccessToken() } catch { return false }
        guard generation == auth.sessionGeneration else { return false }

        do { try await beforeSync?() } catch { return false }
        guard generation == auth.sessionGeneration else { return false }
        var after = sessionStore.lastSyncedSequence() ?? 0
        while true {
            guard !Task.isCancelled, generation == auth.sessionGeneration,
                  catchUpPages < 20, (try? budget.check(now: clock())) != nil else { return false }
            let response: SyncResponse
            do {
                response = try await api.sync(accessToken: token, afterSequence: after, limit: SyncManager.pageLimit)
            } catch {
                // Transient — a later trigger (reconnect/app-foreground) retries.
                return false
            }
            guard generation == auth.sessionGeneration else { return false }
            do {
                try Task.checkCancellation()
                try budget.accept(after: after, next: response.nextSequence, sequences: response.messages.map(\.sequence),
                    hasMore: response.hasMore, deletions: response.deletedChatIds.count, now: clock())
                catchUpPages += 1
                if let cutoff = response.historyClearedBeforeMs {
                    guard (0...253_402_300_799_000).contains(cutoff),
                          let sequence = response.historyClearedThroughSequence, sequence >= 0 else { return false }
                    if admitsHistoryClear(sequence) {
                        guard let onHistoryClear else { return false }
                        try await onHistoryClear(cutoff, sequence)
                    }
                    guard generation == auth.sessionGeneration else { return false }
                }
                for chatId in response.deletedChatIds {
                    try Task.checkCancellation()
                    await Task.yield()
                    try budget.check(now: clock())
                    guard UUID(uuidString: chatId) != nil, isDeletionApplied?(chatId) != true else { continue }
                    try await onDeletedChat?(chatId)
                    guard generation == auth.sessionGeneration else { return false }
                }
            } catch { return false } // Keep the cursor for a later cleanup retry.
            let advanced = await ingestor.ingest(response.messages, advanceCursor: true)
            guard advanced else { return false } // session changed mid-sync; abort silently
            syncedItems += response.messages.count
            after = max(response.nextSequence, sessionStore.lastSyncedSequence() ?? response.nextSequence)
            if !response.hasMore { break }
            await Task.yield()
        }
        guard generation == auth.sessionGeneration else { return false }
        await ingestor.retryPendingDecrypts(automatic: true)
        completed = true
        // Demand-driven cooldown also bounds reconnect/foreground trigger floods.
        return true
    }

    private static let pageLimit = 100

    /// Explicit admission for a complete backup. Unlike opportunistic catch-up,
    /// no transport/decrypt/storage error is swallowed and every page is read.
    func prepareHistoryBackup() async throws {
        try await ServerWorkDeadline.$uptime.withValue(clock() + 120) { try await prepareHistoryWithinBudget() }
    }

    private func prepareHistoryWithinBudget() async throws {
        while isRunning { try await Task.sleep(nanoseconds: 50_000_000) }
        isRunning = true; defer { isRunning = false }
        let generation = auth.sessionGeneration
        try await beforeSync?()
        var after: Int64 = 0
        var budget = ServerWorkBudget(now: clock(), maxPages: 1000, seconds: 120)
        for _ in 0..<1000 {
            try Task.checkCancellation()
            guard (try? budget.check(now: clock())) != nil else { throw HistoryPreparationFailure.incompleteSource }
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            let response = try await api.sync(accessToken: auth.currentAccessToken(), afterSequence: after, limit: Self.pageLimit)
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            do {
                try budget.accept(after: after, next: response.nextSequence, sequences: response.messages.map(\.sequence),
                    hasMore: response.hasMore, deletions: response.deletedChatIds.count, now: clock())
            } catch { throw HistoryPreparationFailure.incompleteSource }
            if let cutoff = response.historyClearedBeforeMs {
                guard (0...253_402_300_799_000).contains(cutoff),
                      let sequence = response.historyClearedThroughSequence, sequence >= 0 else { throw HistoryPreparationFailure.incompleteSource }
                if admitsHistoryClear(sequence) {
                    guard let onHistoryClear else { throw HistoryPreparationFailure.incompleteSource }
                    try await onHistoryClear(cutoff, sequence)
                }
            }
            for chat in response.deletedChatIds {
                try Task.checkCancellation()
                guard UUID(uuidString: chat) != nil, isDeletionApplied?(chat) != true else { continue }
                guard let onDeletedChat else { throw HistoryPreparationFailure.incompleteSource }
                try await onDeletedChat(chat)
            }
            guard await ingestor.ingest(response.messages, advanceCursor: false) else { throw HistoryPreparationFailure.incompleteSource }
            if !response.hasMore { return }
            guard response.nextSequence > after else { throw HistoryPreparationFailure.incompleteSource }
            after = response.nextSequence
        }
        throw HistoryPreparationFailure.incompleteSource
    }
}
