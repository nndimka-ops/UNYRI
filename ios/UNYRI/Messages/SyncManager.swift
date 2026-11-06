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
    private var isRunning = false
    private var syncRequested = false
    var onHistoryClear: ((Int64, Int64) async throws -> Void)?
    var beforeSync: (() async throws -> Void)?
    var onDeletedChat: ((String) async throws -> Void)?
    var onPhaseChange: ((ConnectionSyncPhase) -> Void)?

    init(api: SyncAPI, ingestor: MessageIngestor, auth: AuthRepository, sessionStore: SessionStore) {
        self.api = api
        self.ingestor = ingestor
        self.auth = auth
        self.sessionStore = sessionStore
    }

    func runCatchUpSync() async {
        syncRequested = true
        guard !isRunning else { return }
        isRunning = true
        defer { isRunning = false }
        repeat {
            syncRequested = false
            await runOnePass()
        } while syncRequested && !Task.isCancelled
    }

    private func runOnePass() async {
        guard !Task.isCancelled else { return }
        let generation = auth.sessionGeneration
        var completed = false
        onPhaseChange?(.running)
        defer { if generation == auth.sessionGeneration { onPhaseChange?(completed ? .idle : .failed) } }
        let token: String
        do { token = try await auth.currentAccessToken() } catch { return }
        guard generation == auth.sessionGeneration else { return }

        do { try await beforeSync?() } catch { return }
        guard generation == auth.sessionGeneration else { return }
        var after = sessionStore.lastSyncedSequence() ?? 0
        while true {
            guard generation == auth.sessionGeneration else { return }
            let response: SyncResponse
            do {
                response = try await api.sync(accessToken: token, afterSequence: after, limit: SyncManager.pageLimit)
            } catch {
                // Transient — a later trigger (reconnect/app-foreground) retries.
                return
            }
            guard generation == auth.sessionGeneration else { return }
            do {
                if let cutoff = response.historyClearedBeforeMs {
                    guard let onHistoryClear, let sequence = response.historyClearedThroughSequence else { return }
                    try await onHistoryClear(cutoff, sequence)
                    guard generation == auth.sessionGeneration else { return }
                }
                for chatId in response.deletedChatIds {
                    guard UUID(uuidString: chatId) != nil else { continue }
                    try await onDeletedChat?(chatId)
                    guard generation == auth.sessionGeneration else { return }
                }
            } catch { return } // Keep the cursor for a later cleanup retry.
            let advanced = await ingestor.ingest(response.messages, advanceCursor: true)
            guard advanced else { return } // session changed mid-sync; abort silently
            after = max(response.nextSequence, sessionStore.lastSyncedSequence() ?? response.nextSequence)
            if !response.hasMore { break }
        }
        guard generation == auth.sessionGeneration else { return }
        await ingestor.retryPendingDecrypts(automatic: true)
        completed = true
    }

    private static let pageLimit = 100

    /// Explicit admission for a complete backup. Unlike opportunistic catch-up,
    /// no transport/decrypt/storage error is swallowed and every page is read.
    func prepareHistoryBackup() async throws {
        while isRunning { try await Task.sleep(nanoseconds: 50_000_000) }
        isRunning = true; defer { isRunning = false }
        let generation = auth.sessionGeneration
        try await beforeSync?()
        var after: Int64 = 0
        for _ in 0..<1000 {
            try Task.checkCancellation()
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            let response = try await api.sync(accessToken: auth.currentAccessToken(), afterSequence: after, limit: Self.pageLimit)
            guard generation == auth.sessionGeneration else { throw CancellationError() }
            if let cutoff = response.historyClearedBeforeMs {
                guard let onHistoryClear, let sequence = response.historyClearedThroughSequence else { throw HistoryPreparationFailure.incompleteSource }
                try await onHistoryClear(cutoff, sequence)
            }
            for chat in response.deletedChatIds {
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
