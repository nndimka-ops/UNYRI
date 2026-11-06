import Combine
import Foundation
import UIKit
import UniformTypeIdentifiers
import LibSignalClient
import Security

/// Owns one account/device's direct transfers. Offers use the existing message
/// outbox; only tiny pairwise-Signal control packets use the opaque mailbox.
@MainActor
final class DirectFileTransfers: ObservableObject {
    enum Failure: Error, Equatable { case session, invalid, busy, callBusy, unavailable, unsupported, source, integrity, claimedElsewhere }
    @Published private(set) var records: [String: DirectFileRecord] = [:]
    private var stateRecords: [String: DirectFileRecord] = [:]
    let listState = DirectFileListState()
    private var rowStates: [String: DirectFileRowState] = [:]
    private var interruptedPreparations: [DirectFileAdmissionTicket] = []
    @Published private(set) var progress: [String: DirectFileProgress] = [:]
    @Published private(set) var preparing = false
    @Published var errorMessage: String?
    var canStream: () -> Bool = { false }

    private let auth: AuthRepository
    private let signal: SignalSessionManager
    private let outbox: MessageOutbox
    private let store: MessageStore
    private let api: DirectFileAPI
    private let callsAPI: CallsAPI
    private let disk: DirectFileDisk
    private let generation: UInt64
    private let owner: String
    private let device: String
    private var foreground = true
    private var foregroundEpoch: UInt64 = 0
    private let continuedProcessing = DirectFileContinuedProcessing()
    private var backgroundUserIntents: [String: TimeInterval] = [:]
    private var stopped = false
    private var loaded = false
    private var admissionBlocked = true
    private var pollTask: Task<Void, Never>?
    private var pollLease: UUID?
    private var pollJobs: [String: (lease: UUID, kind: DirectFilePollPlan.Kind, task: Task<Void, Never>)] = [:]
    private var pollRotation: [DirectFilePollPlan.Kind: String] = [:]
    private var cadenceDue: [String: (at: TimeInterval, wasHeld: Bool)] = [:]
    private var queuedWorkJobs: [String: (lease: UUID, task: Task<Void, Never>)] = [:]
    private var checkpointJobs: [String: (lease: UUID, task: Task<Void, Never>)] = [:]
    private var serialTails: [String: Task<Void, Never>] = [:]
    private var serialTailLeases: [String: UUID] = [:]
    private var preparationTasks: [String: Task<DirectFileDisk.Prepared, Error>] = [:]
    private var preparationOperations: [String: Task<Bool, Never>] = [:]
    private var controlPreparations: [String: Task<Void, Never>] = [:]
    private var preparingID: String?
    private var outboundActive: Active?
    private var inboundActive: Active?
    private var roleReservations: [Bool: String] = [:]
    private var receiverRequestLeases: [String: UUID] = [:]
    private var receiverRequestTasks: [String: (lease: UUID, task: Task<Bool, Error>)] = [:]
    private var manualStopLatches = Set<String>()
    private var manualActionLeases: [String: UUID] = [:]
    private var manualPendingIntents: [String: Bool] = [:]
    private var manualStopFences: [String: UUID] = [:]
    private var manualSaveFailures: [String: Bool] = [:]
    private var stopping: [String: (lease: UUID, task: Task<Int64?, Never>)] = [:]
    private var verifyingTransfers = Set<String>()
    private var cancellables = Set<AnyCancellable>()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var apiBackgroundLease: UUID?
    private var lastProgressPublish: TimeInterval = 0
    private var pollFailures = 0
    private var terminalRequests = Set<String>()
    private var retryAfter: [String: TimeInterval] = [:]
    private var recordFailures: [String: Int] = [:]
    private var passiveNextLookup: [String: TimeInterval] = [:]
    private var shutdownDrains: [Task<Void, Never>] = []

    @MainActor
    private final class Active {
        let id: String
        let attempt: String
        let peer: DirectFilePeer
        let lease = UUID()
        var stream: DirectFileStream?
        let execution = DirectFileExecutionPermit()
        var backgroundUserIntentDeadline: TimeInterval?
        var backgroundRequestPending = false
        var backgroundRequestDenied = false
        var member: DirectFileBackgroundMember { .init(id: id, outgoing: outgoing, attempt: attempt, lease: lease) }
        let outgoing: Bool
        init(id: String, attempt: String, peer: DirectFilePeer, outgoing: Bool) {
            self.id = id; self.attempt = attempt; self.peer = peer; self.outgoing = outgoing
        }
    }
    init(auth: AuthRepository, signal: SignalSessionManager, outbox: MessageOutbox,
         store: MessageStore, client: APIClient, accountDirectory: URL) {
        self.auth = auth; self.signal = signal; self.outbox = outbox; self.store = store
        api = DirectFileAPI(client: client); callsAPI = CallsAPI(client: client)
        disk = DirectFileDisk(accountDirectory: accountDirectory, maximumBytes: DirectFileOffer.maximumBytes)
        generation = auth.sessionGeneration
        owner = auth.currentUser?.id.lowercased() ?? ""
        device = auth.currentDeviceId?.uuidString.lowercased() ?? ""
    }

    private var current: Bool { !stopped && generation == auth.sessionGeneration && auth.currentUser?.id.lowercased() == owner }
    private func apiKey(_ id: String) -> String { "\(owner):\(generation):\(id)" }

    /// Only already-loaded private state is read by the final synchronous guard.
    /// Token/SessionStore/Keychain access belongs to the async prepare closure.
    private func apiContext(_ id: String, priority: DirectFileHTTPGate.Priority = .queued,
        terminal: Bool = false, preparation: Bool = false,
        background: (lease: UUID, attempt: String)? = nil) -> DirectFileAPI.Context {
        let bound = stateRecords[id]
        let key = background.map { "\(owner):\(generation):bg:\($0.lease):\(id)" } ?? apiKey(id)
        return DirectFileAPI.Context(key: key, priority: priority, current: { [self] in
            guard self.current else { return false }
            if preparation {
                return self.foreground && self.preparingID == id && self.preparationOperations[id] != nil && self.canStream()
            }
            guard let bound, let fresh = self.stateRecords[id], fresh.offer == bound.offer,
                  fresh.chatId == bound.chatId, fresh.outgoing == bound.outgoing,
                  fresh.localDeviceId == self.device, bound.localDeviceId == self.device else { return false }
            if let background {
                return !self.foreground && self.backgroundTask != .invalid && self.apiBackgroundLease == background.lease &&
                    fresh.attemptId == background.attempt && fresh.phase == .paused && fresh.errorCode == "background" &&
                    !self.terminalRequests.contains(id)
            }
            guard self.foreground || self.backgroundExecutionAllowed(id) else { return false }
            return terminal ? fresh.phase.terminal && fresh.phase == bound.phase :
                !fresh.phase.terminal && !fresh.mailboxClosed && !self.terminalRequests.contains(id)
        }, token: { [self] in
            try self.requireCurrent()
            let token = try await self.auth.currentAccessToken()
            try self.requireCurrent(); return token
        })
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }
    private func hardRecoveryFailure(_ record: DirectFileRecord) -> Bool {
        ["prefix_mismatch", "source_missing", "source_changed", "disk_full", "storage_error",
         "integrity_failed", "integrity_mismatch", "verification_failed", "security_error",
         "permission_denied", "unsupported", "claimed_elsewhere", "transfer_unavailable", "invalid_control"].contains(record.errorCode ?? "")
    }
    private func eligibleNetworkWaiter(_ record: DirectFileRecord) -> Bool {
        !record.outgoing && record.consentGiven && record.phase == .paused && !record.mailboxClosed &&
            [nil, "network_waiting", "connection_lost", "direct_unavailable", "background", "disconnected",
             "no_direct_route", "reconnect_required", "retry"].contains(record.errorCode)
    }
    private func requireCurrent() throws {
        try Task.checkCancellation()
        guard current else { throw Failure.session }
    }

    /// Serialize state/journal mutations across async network and IO boundaries.
    /// Data source reads remain independently streamed, never on the UI actor.
    private func serialized<T>(for id: String = "account-admission", _ operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let previous = serialTails[id]
        let lease = UUID(); serialTailLeases[id] = lease
        let task = Task { @MainActor in
            await previous?.value
            try self.requireCurrent()
            return try await operation()
        }
        serialTails[id] = Task { @MainActor in
            _ = try? await task.value
            if self.serialTailLeases[id] == lease {
                self.serialTails[id] = nil; self.serialTailLeases[id] = nil
            }
        }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }

    private func save(_ proposed: DirectFileRecord) async throws {
        var record = proposed
        // Settled completion/explicit own or peer holds naturally disarm a
        // grant. They never acquire a new local interruption reason on restart.
        // An already ambiguous OS-stop marker is NOT cleared by peer traffic.
        let hold = record.manualPause ?? DirectManualPauseState()
        if record.phase.terminal || record.continuedProcessing?.requiresContinue == false &&
            (hold.localPaused || hold.peerPaused) { record.continuedProcessing = nil }
        try requireCurrent()
        try await disk.journal.save(record)
        try requireCurrent()
        stateRecords[record.id] = record
        if !admissionBlocked && (record.mailboxClosed || record.phase.terminal && !(record.outgoing && record.phase == .completed)) {
            try await disk.releaseAdmission(record.id)
        }
        publishRecord(record)
    }

    private func publishRecord(_ record: DirectFileRecord) {
        // Signal cursor/journal housekeeping is not a visual change. In
        // particular it must never invalidate a chat's entire native List.
        var presentation = record
        presentation.signalCursor = 0; presentation.seenSignals = []
        presentation.incomingControls = []; presentation.outgoingSignals = []
        presentation.pendingInbound = nil
        presentation.signalBudget = nil; presentation.queue = nil
        if var state = presentation.manualPause {
            state.pendingResponseTo = nil; state.syncProbeCount = 0
            state.nextSyncAt = 0; state.syncCycleStartedAt = 0
            state.publishedLocalRevision = 0
            presentation.manualPause = state
        }
        presentation.usedAttempts = []; presentation.updatedAt = 0
        presentation.mailboxRegistered = false; presentation.mailboxClosed = false
        presentation.completionControlQueued = false; presentation.completionAckQueued = false
        presentation.completionReceived = false; presentation.completionRecoveryCount = 0
        presentation.completionRecoveryNextAt = nil
        presentation.attemptId = nil
        presentation.continuedProcessing = nil
        presentation.senderRequest = nil; presentation.requestStartedAt = nil
        presentation.requestAcknowledged = false; presentation.requestRetryCount = 0
        presentation.requestNextRetryAt = nil; presentation.requestAckSent = false
        if records[record.id] != presentation { records[record.id] = presentation }
        refreshPresentations()
    }

    func start() {
        guard current, UIApplication.shared.applicationState == .active else { return }
        let resumed = !foreground
        foreground = true
        for context in allActive { context.execution.setForeground(true) }
        if resumed { networkDidRecover() }
        guard pollTask == nil else { return }
        let lease = UUID(); pollLease = lease
        pollTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if self.pollLease == lease { self.pollTask = nil; self.pollLease = nil } }
            do {
                if !self.loaded {
                    try await self.serialized {
                        let saved = try await self.disk.records()
                        try self.requireCurrent()
                        self.admissionBlocked = await self.disk.admissionBlocked
                        for var record in saved {
                            guard record.localDeviceId == self.device,
                                  (record.outgoing ? record.offer.senderUserId : record.offer.recipientUserId) == self.owner else { continue }
                            if record.outgoing, !record.phase.terminal,
                               (try? self.store.message(byClientMessageId: record.offer.offerClientMessageId)) == nil {
                                // The journal-before-outbox admission gap never
                                // advertised this source. Retire it; do not send
                                // an invisible cancelled preparation on restart.
                                record.phase = .cancelled
                                try await self.disk.journal.save(record)
                                try await self.disk.discardUnfinished(record)
                            }
                            if record.phase == .connecting || record.phase == .transferring || record.phase == .verifying {
                                record.phase = .paused
                            }
                            do {
                                if let completed = try await self.disk.recoverCompleted(record) { record = completed }
                            } catch is CancellationError { throw CancellationError() }
                            catch {
                                // A missing/corrupt local original belongs to
                                // one transfer, never a startup-wide failure.
                                record.phase = .failed; record.errorCode = "integrity_failed"
                                try await self.disk.journal.save(record)
                            }
                            // A persisted grant marker is never restored as permission.
                            // Force-quit/expiration may have had no callback at all.
                            if var marker = record.continuedProcessing, !record.phase.terminal {
                                marker.requiresContinue = true; record.continuedProcessing = marker
                                if !self.hardRecoveryFailure(record) {
                                    record.phase = .paused; record.errorCode = "background_interrupted"
                                }
                                try await self.disk.journal.save(record)
                            }
                            self.stateRecords[record.id] = record
                            self.publishRecord(record)
                            self.progress[record.id] = DirectFileProgress(offset: record.durableOffset)
                        }
                        self.interruptedPreparations = self.admissionBlocked ? [] :
                            (try await self.disk.admissionTickets()).filter { $0.needsReselection }
                        self.loaded = true
                        if self.admissionBlocked {
                            self.errorMessage = "The transfer queue on this device could not be loaded. Existing completed files are retained."
                        }
                        self.refreshPresentations()
                        let recoveredOffers = (try? await self.store.readAsync { try $0.directFileOfferMessages() }) ?? []
                        try self.requireCurrent()
                        for message in recoveredOffers {
                            if let offer = message.content.directFile,
                               offer.recipientUserId == self.owner,
                               self.stateRecords[offer.transferId] == nil {
                                var record = DirectFileRecord(chatId: message.chatId, offer: offer, outgoing: false,
                                    phase: offer.expiresAt <= self.now ? .expired : .offered,
                                    peerDeviceId: offer.senderDeviceId, updatedAt: self.now)
                                record.localDeviceId = self.device
                                record.signalBudget = .unknown()
                                try await self.save(record)
                            }
                        }
                    }
                }
                while self.current && self.foreground && !Task.isCancelled {
                    guard self.stateRecords.values.contains(where: {
                        !$0.mailboxClosed && $0.phase != .expired &&
                        ($0.outgoing || $0.consentGiven || $0.phase == .offered || [.cancelled, .rejected].contains($0.phase))
                    }) else { return }
                    do {
                        try await self.poll()
                        self.pollFailures = 0
                    } catch is CancellationError { return }
                    catch {
                        self.pollFailures = min(6, self.pollFailures + 1)
                    }
                    let delay = self.pollFailures == 0
                        ? self.healthyPollingDelay(uptime: ProcessInfo.processInfo.systemUptime)
                        : Double(min(120, 2 << self.pollFailures))
                    try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }
            } catch { /* A later foreground transition resumes the durable journal. */ }
        }
    }

    /// Reuse the account's existing foreground/WebSocket connectivity event.
    /// A healthy connection and non-network failures are never reset here.
    func networkDidRecover() {
        guard current, foreground, UIApplication.shared.applicationState == .active else { return }
        Task { @MainActor in
            var restarted = false
            let ids = self.stateRecords.values.filter { $0.phase == .paused && !$0.phase.terminal }.map(\.id)
            for id in ids {
                try? await self.serialized(for: id) {
                    guard var record = self.stateRecords[id], self.ownedActive(id) == nil,
                          !record.phase.terminal, !record.mailboxClosed else { return }
                    if self.eligibleNetworkWaiter(record) {
                        record.requestRetryCount = 0; record.requestNextRetryAt = nil
                        self.retryAfter[id] = nil; self.recordFailures[id] = nil
                        record.queue?.remoteCapacityBlocked = false
                    }
                    if var manual = record.manualPause,
                       DirectManualTiming.canResetCycle(nowMillis: self.now * 1000, startedAtMillis: manual.syncCycleStartedAt) {
                        manual.syncProbeCount = 0; manual.nextSyncAt = 0
                        manual.syncCycleStartedAt = self.now * 1000; manual.syncNeeded = true
                        record.manualPause = manual
                    }
                    try await self.save(record); restarted = true
                }
            }
            if restarted { self.wakePolling() }
        }
    }

    func mediaOwnershipChanged() {
        guard current else { return }
        if !canStream() {
            preparationOperations.values.forEach { $0.cancel() }
            preparationTasks.values.forEach { $0.cancel() }
            controlPreparations.values.forEach { $0.cancel() }; controlPreparations = [:]
            pollTask?.cancel(); pollTask = nil; pollLease = nil
            cancelQueuedJobs()
            let owned = allActive
            let ids = Set(owned.map(\.id)).union(stateRecords.values.filter {
                !$0.phase.terminal && ($0.senderRequest != nil || $0.phase == .accepted)
            }.map(\.id))
            var checkpoints: [String: Task<Int64?, Never>] = [:]
            for context in owned { checkpoints[context.id] = stopTransportImmediately(context.id) }
            Task { @MainActor in
                for id in ids {
                    try? await self.serialized(for: id) {
                        guard var record = self.stateRecords[id], !record.phase.terminal else { return }
                        if let task = checkpoints[id], let offset = await task.value {
                            record = self.stateRecords[id] ?? record
                            record.durableOffset = max(record.durableOffset, offset)
                        }
                        guard !record.phase.terminal else { return }
                        record.pausedForLocalCall = true; record.phase = .paused; record.errorCode = "call_in_progress"
                        record.senderRequest = nil; record.requestStartedAt = nil
                        try await self.save(record)
                        if let attempt = record.attemptId, !record.outgoing || record.peerVerified {
                            try? await self.queueControl(record, type: .pause, attempt: attempt, reason: "call_in_progress")
                        }
                    }
                }
            }
        } else {
            Task { @MainActor in
                let ids = self.stateRecords.values.filter { $0.pausedForLocalCall && !$0.phase.terminal }.map(\.id)
                for id in ids {
                    try? await self.serialized(for: id) {
                        guard var record = self.stateRecords[id], record.pausedForLocalCall, !record.phase.terminal else { return }
                        record.pausedForLocalCall = false; record.errorCode = "reconnect_required"
                        try await self.save(record)
                        // A call ending never clears either manual hold.
                        if record.outgoing, record.peerVerified, self.manualByteEligible(id), let attempt = record.attemptId {
                            try await self.queueControl(record, type: .pause, attempt: attempt, reason: "reconnect_required")
                        }
                    }
                }
                self.wakePolling()
            }
        }
    }

    func destinationIssue(_ chat: ChatSummary) -> String? {
        DirectFileDestinationPolicy.issue(chatType: chat.chatType,
            recipient: chat.otherUser?.id.lowercased(), owner: owner, blocked: chat.isBlocked == true)
    }

    func offer(source: URL, chat: ChatSummary, filename: String? = nil, caption: String? = nil) async -> Bool {
        guard current else {
            errorMessage = "The account changed. Open the conversation again and try sending the file."; return false
        }
        guard foreground else { return false }
        if let issue = destinationIssue(chat) { errorMessage = issue; return false }
        guard loaded, !admissionBlocked else {
            errorMessage = loaded
                ? "The transfer queue on this device could not be loaded. Existing completed files are retained."
                : "Transfers are still loading. Try again shortly."
            return false
        }
        guard !preparing else {
            errorMessage = "Another file is being prepared. Wait or cancel it first."; return false
        }
        guard let recipient = chat.otherUser?.id.lowercased() else {
            errorMessage = DirectFileDestinationPolicy.unavailable; return false
        }
        let id = UUID().uuidString.lowercased(), messageID = UUID().uuidString.lowercased()
        noteBackgroundUserIntent(id)
        let name = DirectFileOffer.safeFilename(filename ?? source.lastPathComponent)
        preparing = true; errorMessage = nil
        preparingID = id
        // Track the complete operation before its first account/API/admission
        // await. Background or Cancel retires this exact task even if the
        // source worker has not been created yet; the caller retains its
        // security-scoped selection until the complete task actually exits.
        let operation = Task { @MainActor [self] in
            defer {
                preparationTasks[id] = nil; preparationOperations[id] = nil
                if preparingID == id { preparingID = nil; preparing = false }
            }
            do {
            let caption = try MessageTextPolicy.caption(caption)
            guard canStream() else { throw Failure.callBusy }
            // Preparation has its own bounded lane and does not consume either transport role.
            let capabilities: DirectFileAPI.Capabilities
            do { capabilities = try await api.capabilities(context: apiContext(id, preparation: true)) }
            catch let error where DirectFileAPI.classify(error) == .notFound { throw Failure.unsupported }
            try requireCurrent()
            guard capabilities.version == 1, capabilities.directOnly,
                  capabilities.maxFileBytes == DirectFileOffer.maximumBytes else { throw Failure.unsupported }
            let admission = try await disk.reserveAdmission(id: id, outgoing: true, bytes: 0, displayFilename: name, chatId: chat.chatId)
            try requireCurrent()
            let sourceTask = Task { try await self.disk.prepareSource(id: id, source: source) }
            preparationTasks[id] = sourceTask
            let prepared = try await withTaskCancellationHandler(operation: { try await sourceTask.value }, onCancel: { sourceTask.cancel() })
            try requireCurrent()
            let type = UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            let metadata = DirectFileOffer(transferId: id, offerClientMessageId: messageID,
                senderUserId: owner, senderDeviceId: device, recipientUserId: recipient,
                offerNonce: try Self.makeNonce(),
                filename: name, mimeType: type, size: prepared.bytes, sha256: prepared.digest,
                expiresAt: now + DirectFileOffer.lifetimeSeconds)
            var record = DirectFileRecord(chatId: chat.chatId, offer: metadata, outgoing: true,
                phase: .awaitingAcceptance, updatedAt: now)
            record.localDeviceId = device
            record.signalBudget = .fresh()
            record.queue = DirectFileQueueState(ordinal: admission.ordinal, reservedFutureBytes: 0)
            return try await serialized(for: id) {
                try await self.save(record)
                let fallback = DirectFileOffer.fallbackText + (caption.map { "\n\n" + $0 } ?? "")
                let content = MessageContent(kind: MessageContent.kindText, text: fallback, directFile: metadata)
                guard self.outbox.enqueueContent(chatId: chat.chatId, otherUserId: recipient, content: content,
                    targetMessageId: nil, clientMessageId: messageID) else {
                    var failed = record; failed.phase = .cancelled
                    try await self.save(failed); try await self.disk.discardUnfinished(failed)
                    throw Failure.source
                }
                self.start()
                return true
            }
        } catch {
            if current, (try? store.message(byClientMessageId: messageID)) == nil,
               var abandoned = try? await disk.journal.record(id), !abandoned.phase.terminal {
                abandoned.phase = .cancelled
                try? await disk.journal.save(abandoned)
                try? await disk.discardUnfinished(abandoned)
            }
            try? await disk.discardPreparedSource(id: id)
            if current, !(error is CancellationError) { errorMessage = userMessage(error) }
                return false
            }
        }
        preparationOperations[id] = operation
        return await withTaskCancellationHandler(operation: { await operation.value },
            onCancel: { operation.cancel() })
    }

    func cancelPreparing() {
        guard let id = preparingID else { return }
        preparationOperations[id]?.cancel(); preparationTasks[id]?.cancel()
        if stateRecords[id] != nil { cancel(id) }
    }

    /// Called only after the normal ingestion path verified Signal identity,
    /// explicit authentication, immutable IDs and persisted the message.
    func receivedOffer(chatId: String, offer: DirectFileOffer) {
        guard current, offer.hasValidShape, offer.recipientUserId == owner else { return }
        Task { @MainActor in
            do {
                try await self.serialized(for: offer.transferId) {
                    if let existing = self.stateRecords[offer.transferId] {
                        guard existing.offer == offer, existing.chatId == chatId else { throw Failure.invalid }
                        return
                    }
                    guard self.stateRecords.count < 1000 else { throw Failure.busy }
                    var record = DirectFileRecord(chatId: chatId, offer: offer, outgoing: false,
                        phase: offer.expiresAt <= self.now ? .expired : .offered,
                        peerDeviceId: offer.senderDeviceId, updatedAt: self.now)
                    record.localDeviceId = self.device
                    record.signalBudget = .fresh()
                    try await self.save(record)
                }
                self.start()
            } catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }

    func accept(_ id: String) { noteBackgroundUserIntent(id); action(id, kind: .accept) }
    func resume(_ id: String) { noteBackgroundUserIntent(id); action(id, kind: .resume) }
    func reject(_ id: String) { action(id, kind: .reject) }
    func canRetrySender(_ record: DirectFileRecord) -> Bool {
        record.outgoing && record.peerVerified && record.phase == .paused && canStream() &&
            [nil, "network_waiting", "connection_lost", "direct_unavailable", "busy", "reconnect_required",
             "background", "disconnected", "no_direct_route", "retry", "call_in_progress"].contains(record.errorCode)
    }
    func retrySender(_ id: String) {
        noteBackgroundUserIntent(id)
        Task { @MainActor in
            let apiKey = self.apiKey(id), apiLease = self.api.promote(self.apiKey(id))
            defer { self.api.demote(apiKey, lease: apiLease) }
            do {
                try await self.serialized(for: id) {
                    guard var record = self.stateRecords[id], self.canRetrySender(record), let attempt = record.attemptId else { return }
                    await self.retirePeer(id)
                    record.errorCode = "reconnect_required"; record.updatedAt = self.now
                    try await self.save(record)
                    try await self.queueControl(record, type: .pause, attempt: attempt, reason: "reconnect_required")
                }
                self.start()
            } catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }
    func cancel(_ id: String) {
        terminalRequests.insert(id)
        controlPreparations[id]?.cancel(); controlPreparations[id] = nil
        stopTransportImmediately(id)
        action(id, kind: .cancel)
    }

    func offerMessageRemoved(_ clientMessageId: String) {
        for record in stateRecords.values where record.offer.offerClientMessageId == clientMessageId && !record.phase.terminal {
            let id = record.id
            terminalRequests.insert(id); controlPreparations[id]?.cancel(); controlPreparations[id] = nil
            stopTransportImmediately(id)
            Task { @MainActor in try? await self.serialized(for: id) { try await self.retireHiddenOffer(id) } }
        }
    }
    func chatRemoved(_ chatId: String) {
        for record in stateRecords.values where record.chatId == chatId { offerMessageRemoved(record.offer.offerClientMessageId) }
    }
    private func retireHiddenOffer(_ id: String) async throws {
        guard var record = stateRecords[id], !record.phase.terminal else { return }
        record.phase = .cancelled; record.updatedAt = now
        // Removing one unaccepted local offer must not claim the mailbox away
        // from another device belonging to this recipient account.
        if !record.outgoing && !record.consentGiven { record.mailboxClosed = true }
        try await save(record)
        await retirePeer(id); try await disk.discardUnfinished(record)
        if record.mailboxRegistered {
            if record.outgoing && !record.peerVerified {
                try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
                record.mailboxClosed = true; try await save(record)
            } else { try await sendTerminalIfPossible(id, type: .cancel) }
        }
    }

    /// Invoked only after the native UI explains that the saved partial will
    /// be replaced. Ordinary reconnection never discards a mismatched prefix.
    func restartFromZero(_ id: String) {
        noteBackgroundUserIntent(id)
        guard loaded, !admissionBlocked else { return }
        Task { @MainActor in
            do {
                try await self.serialized(for: id) {
                    guard var record = self.stateRecords[id], !record.outgoing, !record.phase.terminal else { return }
                    await self.retirePeer(id); record.durableOffset = 0; record.errorCode = nil
                    record.attemptId = UUID().uuidString.lowercased()
                    record.phase = .accepted; record.consentGiven = true
                    try await self.save(record)
                    try await self.beginReceiverRequest(record, resume: true)
                }
                self.start()
            } catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }

    private func action(_ id: String, kind: DirectFileControl.Kind) {
        Task { @MainActor in
            let apiKey = self.apiKey(id), apiLease = self.api.promote(self.apiKey(id))
            defer { self.api.demote(apiKey, lease: apiLease) }
            defer { self.start() }
            do {
                try await self.serialized(for: id) {
                    guard var record = self.stateRecords[id], !record.phase.terminal else { return }
                    self.passiveNextLookup[id] = nil; self.retryAfter[id] = nil; self.recordFailures[id] = nil
                    if kind == .accept || kind == .resume {
                        guard self.loaded, !self.admissionBlocked else { throw DirectFileDisk.Failure.admissionUnavailable }
                        guard !record.outgoing, record.offer.expiresAt > self.now,
                              record.errorCode != "prefix_mismatch" else { throw Failure.busy }
                        // Job and disk admission precede consent/claim, so a
                        // full local queue never takes the mailbox from another device.
                        let alreadyAdmitted = record.consentGiven && record.queue != nil
                        let admission = try await self.disk.reserveAdmission(id: id, outgoing: false,
                            bytes: max(0, record.offer.size - record.durableOffset), displayFilename: record.offer.filename, declaredBytes: record.offer.size, chatId: record.chatId)
                        try self.requireCurrent()
                        record = self.stateRecords[id] ?? record
                        guard !record.phase.terminal else { try await self.disk.releaseAdmission(id); return }
                        record.queue = record.queue ?? DirectFileQueueState(ordinal: admission.ordinal,
                            reservedFutureBytes: admission.reservedFutureBytes)
                        // Consent is durable BEFORE any automatic retry or claim.
                        record.consentGiven = true; record.phase = .accepted; record.errorCode = nil
                        record.queue?.remoteCapacityBlocked = false
                        record.requestRetryCount = 0; record.requestNextRetryAt = nil
                        do { try await self.save(record) }
                        catch { if !alreadyAdmitted { try? await self.disk.releaseAdmission(id) }; throw error }
                        let requestEpoch = self.manualActionLeases[id]
                        guard self.receiverIdentityEligible(id) else { return }
                        try await self.claimIfNeeded(id, allowed: {
                            self.manualActionLeases[id] == requestEpoch && self.receiverIdentityEligible(id)
                        })
                        guard self.manualActionLeases[id] == requestEpoch,
                              self.receiverIdentityEligible(id), self.slotAvailable(id) else { return }
                        try await self.beginReceiverRequest(record, resume: kind == .resume)
                    } else {
                        let terminal: DirectFilePhase = kind == .reject ? .rejected : .cancelled
                        record.phase = terminal; record.updatedAt = self.now
                        try await self.save(record)
                        await self.retirePeer(id)
                        try await self.disk.discardUnfinished(record)
                        if record.outgoing, let message = try self.store.message(byClientMessageId: record.offer.offerClientMessageId), message.serverId == nil {
                            let ids = try self.store.cancelUnsentMessages(chatId: record.chatId, owner: self.owner, ids: [record.offer.offerClientMessageId])
                            ids.forEach { self.outbox.cancelMedia($0) }; self.outbox.changes.send([record.chatId])
                        }
                        // A peer may not have claimed yet. The sticky local
                        // tombstone is retained and sent as soon as it does.
                        if !record.outgoing { try await self.claimIfNeeded(id) }
                        try await self.sendTerminalIfPossible(id, type: kind)
                    }
                }
            } catch is CancellationError { }
            catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }

    private func beginReceiverRequest(_ source: DirectFileRecord, resume: Bool) async throws {
        guard receiverIdentityEligible(source.id), slotAvailable(source.id),
              receiverRequestLeases[source.id] == nil else { return }
        let id = source.id, requestLease = UUID(), actionEpoch = manualActionLeases[source.id]
        receiverRequestLeases[id] = requestLease; roleReservations[false] = id
        let allowed = { [self] in
            self.current && self.foreground && self.receiverRequestLeases[id] == requestLease &&
                self.manualActionLeases[id] == actionEpoch && self.receiverIdentityEligible(id)
        }
        var admitted = false
        defer {
            if receiverRequestLeases[id] == requestLease { receiverRequestLeases[id] = nil }
            if receiverRequestTasks[id]?.lease == requestLease { receiverRequestTasks[id] = nil }
            if !admitted, stopping[id] == nil, roleReservations[false] == id { roleReservations[false] = nil }
        }
        let work = Task { @MainActor () async throws -> Bool in
            try await self.claimIfNeeded(id, allowed: allowed)
            try self.requireCurrent(); guard allowed(), var record = self.stateRecords[id] else { throw CancellationError() }
            guard self.canStream() else {
                record.phase = .paused; record.errorCode = "call_in_progress"; record.pausedForLocalCall = true
                try await self.save(record); return false
            }
            let retiredOffset = await self.retirePeer(id, preservingReceiverLease: requestLease)
            try self.requireCurrent(); guard allowed(), self.slotAvailable(id) else { throw CancellationError() }
            record = self.stateRecords[id] ?? record
            if let retiredOffset { record.durableOffset = retiredOffset }
            else {
                let offset = try await self.disk.durableOffset(record)
                try self.requireCurrent(); guard allowed(), self.slotAvailable(id) else { throw CancellationError() }
                record = self.stateRecords[id] ?? record; record.durableOffset = offset
            }
            self.roleReservations[false] = id
            let checkpoint = try await self.disk.prepareReceiver(record)
            try self.requireCurrent(); guard allowed(), self.slotAvailable(id) else { throw CancellationError() }
            record = self.stateRecords[id] ?? record
            record.durableOffset = checkpoint.offset; record.attemptId = UUID().uuidString.lowercased()
            record.phase = .accepted; record.updatedAt = self.now
            record.requestAcknowledged = false; record.requestStartedAt = self.now
            record.requestRetryCount += 1; record.requestNextRetryAt = nil
            try await self.save(record)
            try self.requireCurrent(); guard allowed() else { throw CancellationError() }
            try await self.disk.beginAttempt(record)
            try self.requireCurrent(); guard allowed() else { throw CancellationError() }
            try await self.queueControl(record, type: resume ? .resume : .accept, attempt: record.attemptId!,
                offset: checkpoint.offset, prefix: checkpoint.prefixSHA256, readyAllowed: allowed)
            try self.requireCurrent(); guard allowed() else { throw CancellationError() }
            return true
        }
        receiverRequestTasks[id] = (requestLease, work)
        admitted = try await withTaskCancellationHandler(operation: { try await work.value }, onCancel: { work.cancel() })
    }

    private func claimIfNeeded(_ id: String, allowed: (() -> Bool)? = nil) async throws {
        guard var record = stateRecords[id], !record.outgoing else { return }
        guard !admissionBlocked else { throw DirectFileDisk.Failure.admissionUnavailable }
        guard allowed?() != false else { throw CancellationError() }
        try requireCurrent()
        guard allowed?() != false else { throw CancellationError() }
        let metadata: DirectFileMailbox
        do {
            metadata = try await api.claim(id, context: apiContext(id, priority: record.phase.terminal ? .control : .queued, terminal: record.phase.terminal), beforeAttempt: {
                try self.requireCurrent()
                guard allowed?() != false else { throw CancellationError() }
            })
        }
        catch let error where DirectFileAPI.classify(error) == .closed || DirectFileAPI.classify(error) == .claimedElsewhere {
            let elsewhere = DirectFileAPI.classify(error) == .claimedElsewhere
            if record.phase.terminal {
                record.mailboxClosed = true
                try await save(record)
                return
            }
            record.phase = .failed; record.mailboxClosed = true
            record.errorCode = elsewhere ? "claimed_elsewhere" : "transfer_unavailable"
            try await save(record)
            throw elsewhere ? Failure.claimedElsewhere : Failure.unavailable
        }
        try requireCurrent()
        guard allowed?() != false else { throw CancellationError() }
        guard let latest = stateRecords[id], latest.offer == record.offer else { throw Failure.invalid }
        record = latest
        guard metadata.binds(record), metadata.recipientDeviceId == device else { throw Failure.claimedElsewhere }
        record.mailboxRegistered = true; record.peerDeviceId = record.offer.senderDeviceId
        try await save(record)
    }

    private func pollCadence(_ record: DirectFileRecord) -> DirectFilePollPlan.Cadence {
        // This branch must match pollOne's passive visibility path first.
        if !record.outgoing && !record.consentGiven && !record.mailboxRegistered { return .passive }
        if record.phase.terminal || ownedActive(record.id) != nil || record.requestStartedAt != nil { return .reserved }
        let state = record.manualPause ?? DirectManualPauseState()
        if !record.mailboxRegistered || record.phase == .verifying || verifyingTransfers.contains(record.id) || terminalRequests.contains(record.id) ||
            roleReservations[record.outgoing] == record.id || controlPreparations[record.id] != nil ||
            preparationTasks[record.id] != nil || preparationOperations[record.id] != nil ||
            receiverRequestTasks[record.id] != nil || queuedWorkJobs[record.id] != nil ||
            checkpointJobs[record.id] != nil || manualPendingIntents[record.id] != nil ||
            record.senderRequest != nil || !record.outgoingSignals.isEmpty || !record.incomingControls.isEmpty ||
            record.pendingInbound != nil || state.pendingResponseTo != nil ||
            state.localRevision > state.publishedLocalRevision || state.syncNeeded && !state.syncLimitReached { return .urgent }
        return state.localPaused || state.peerPaused || state.acknowledgedLocalRevision < state.localRevision ? .held : .queued
    }

    private func refreshPollCadence(uptime: TimeInterval) ->
        (rows: [DirectFileRecord], classes: [String: DirectFilePollPlan.Cadence], held: TimeInterval) {
        let rows = stateRecords.values.filter { !$0.mailboxClosed && $0.phase != .expired }.sorted {
            let lhs = $0.queue?.ordinal ?? Int64.max, rhs = $1.queue?.ordinal ?? Int64.max
            return lhs == rhs ? $0.id < $1.id : lhs < rhs
        }
        let classes = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, pollCadence($0)) })
        let eligible = rows.filter {
            (retryAfter[$0.id] ?? 0) <= uptime &&
                (classes[$0.id] != .passive || (passiveNextLookup[$0.id] ?? 0) <= uptime)
        }
        // Only this actual terminal route has a known extra metadata GET.
        // Ordinary registered cold rows receive metadata in their inbox page.
        let extraReads = eligible.filter {
            $0.outgoing && $0.mailboxRegistered && [.cancelled, .rejected].contains($0.phase) && $0.peerDeviceId == nil
        }.count
        // Bootstrap polls can return before HTTP (outbox ACK or occupied IN),
        // then register/claim once. They are urgent without steady inbox cost.
        // Unknown-peer terminal metadata is the one-off extra read above.
        let steadyInbox = eligible.filter {
            classes[$0.id] == .passive || $0.mailboxRegistered &&
                !($0.outgoing && [.cancelled, .rejected].contains($0.phase) && $0.peerDeviceId == nil)
        }
        let held = DirectFilePollPlan.heldInterval(steadyInbox.compactMap { classes[$0.id] }, extraReads: extraReads)
        let quiet = Set(rows.lazy.filter {
            ($0.queue?.ordinal ?? 0) > 0 && (classes[$0.id] == .held || classes[$0.id] == .queued)
        }.prefix(16).map(\.id))
        cadenceDue = cadenceDue.filter { quiet.contains($0.key) }
        for id in Array(cadenceDue.keys) {
            guard let old = cadenceDue[id] else { continue }
            if classes[id] == .held {
                cadenceDue[id] = (DirectFilePollPlan.shortenedHeldDue(old.at, now: uptime, interval: held), true)
            } else { cadenceDue[id] = (old.at, false) }
        }
        return (rows, classes, held)
    }

    private func healthyPollingDelay(uptime: TimeInterval) -> TimeInterval {
        let snapshot = refreshPollCadence(uptime: uptime)
        if !pollJobs.isEmpty || !queuedWorkJobs.isEmpty { return 2 }
        let next = snapshot.rows.map { record -> TimeInterval in
            var due = max(uptime, retryAfter[record.id] ?? 0)
            if snapshot.classes[record.id] == .passive { due = max(due, passiveNextLookup[record.id] ?? 0) }
            else { due = max(due, cadenceDue[record.id]?.at ?? uptime) }
            return due
        }.min() ?? uptime + 10
        return min(10, max(2, next - uptime))
    }

    private func poll() async throws {
        try requireCurrent()
        guard foreground else { return }
        scheduleQueuedWork()
        let uptime = ProcessInfo.processInfo.systemUptime
        let snapshot = refreshPollCadence(uptime: uptime)
        let ordered = snapshot.rows.compactMap { record -> DirectFilePollPlan.Entry? in
            guard !record.mailboxClosed, record.phase != .expired,
                  (retryAfter[record.id] ?? 0) <= uptime else { return nil }
            let passive = !record.outgoing && !record.consentGiven && !record.mailboxRegistered
            if passive, (passiveNextLookup[record.id] ?? 0) > uptime { return nil }
            if !passive, (cadenceDue[record.id]?.at ?? 0) > uptime { return nil }
            return .init(id: record.id, kind: passive ? .passive : record.phase.terminal ? .bookkeeping : .active)
        }
        let plan = DirectFilePollPlan.select(ordered, running: pollJobs.mapValues { $0.kind }, after: pollRotation)
        for entry in plan {
            let id = entry.id, lease = UUID()
            if let cadence = snapshot.classes[id], cadence == .held || cadence == .queued,
               let record = stateRecords[id], (record.queue?.ordinal ?? 0) > 0, cadenceDue.count < 16 || cadenceDue[id] != nil {
                cadenceDue[id] = (uptime + DirectFilePollPlan.interval(cadence, heldInterval: snapshot.held), cadence == .held)
            }
            pollRotation[entry.kind] = id
            let task = Task { @MainActor [weak self] in
                guard let self else { return }
                defer { if self.pollJobs[id]?.lease == lease { self.pollJobs[id] = nil } }
                do {
                    try await self.serialized(for: id) {
                        guard self.foreground else { throw CancellationError() }
                        try await self.pollOne(id)
                    }
                } catch is CancellationError { }
                catch { /* Per-record recovery is handled by pollOne; account changes revoke current. */ }
            }
            pollJobs[id] = (lease, entry.kind, task)
        }
    }

    private func cancelQueuedJobs() {
        // Keep leases until the actual job exits; a rapid foreground transition
        // cannot create a duplicate request behind a cancelled predecessor.
        pollJobs.values.forEach { $0.task.cancel() }
        queuedWorkJobs.values.forEach { $0.task.cancel() }
        checkpointJobs.values.forEach { $0.task.cancel() }
        receiverRequestTasks.values.forEach { $0.task.cancel() }
    }

    private func pollOne(_ id: String) async throws {
            try requireCurrent()
            guard (retryAfter[id] ?? 0) <= ProcessInfo.processInfo.systemUptime else { return }
            do {
            guard var record = stateRecords[id] else { return }
            if !record.phase.terminal {
                let message = try store.message(byClientMessageId: record.offer.offerClientMessageId)
                let chatDeleted = try store.isChatDeleted(record.chatId)
                if message == nil || message?.hiddenLocally == true || message?.isDeletedForEveryone == true ||
                    chatDeleted {
                    try await retireHiddenOffer(id); return
                }
            }
            if record.offer.expiresAt <= now, record.phase != .completed {
                record.phase = .expired; record.updatedAt = now; try await save(record)
                await retirePeer(id); try await disk.discardUnfinished(record); return
            }
            if record.phase == .expired || record.mailboxClosed { return }
            if !record.outgoing, !record.consentGiven, !record.mailboxRegistered {
                // Read-only visibility refresh. No claim, Signal decryption,
                // reply or nonce disclosure is authorized by merely viewing.
                guard (passiveNextLookup[id] ?? 0) <= ProcessInfo.processInfo.systemUptime else { return }
                passiveNextLookup[id] = ProcessInfo.processInfo.systemUptime + 10
                do {
                    let metadata = try await api.metadata(id, context: apiContext(id, priority: .passive))
                    try requireCurrent(); guard metadata.binds(record) else { throw Failure.invalid }
                    if metadata.status == "closed" {
                        record.phase = .failed; record.errorCode = "transfer_unavailable"; record.mailboxClosed = true
                    } else if let claimed = metadata.recipientDeviceId, claimed != device {
                        record.phase = .failed; record.errorCode = "claimed_elsewhere"; record.mailboxClosed = true
                    }
                } catch let error where DirectFileAPI.classify(error) == .notFound {
                    // The offer can arrive before its sender registers the
                    // mailbox. Keep Accept visible and wait without claiming.
                } catch {
                    passiveNextLookup[id] = ProcessInfo.processInfo.systemUptime + ((error as? APIError)?.isRateLimited == true ? 120 : 30)
                }
                // Rotate waiting records fairly under the global read budget.
                record.updatedAt = now; try await save(record)
                return
            }
            if record.outgoing, !record.mailboxRegistered {
                guard let message = try store.message(byClientMessageId: record.offer.offerClientMessageId),
                      message.serverId != nil, [.sent, .delivered, .read].contains(message.status) else { return }
                let metadata = try await api.register(record, context: apiContext(id, priority: [.cancelled, .rejected].contains(record.phase) ? .control : .queued, terminal: [.cancelled, .rejected].contains(record.phase)))
                try requireCurrent(); guard metadata.binds(record) else { throw Failure.invalid }
                record.mailboxRegistered = true; record.peerDeviceId = metadata.recipientDeviceId
                try await save(record)
            }
            if !record.outgoing, record.consentGiven, !record.mailboxRegistered, !record.phase.terminal {
                guard slotAvailable(id) else { return }
                try await beginReceiverRequest(record, resume: record.durableOffset > 0)
                record = stateRecords[id] ?? record
            }
            if !record.outgoing, [.cancelled, .rejected].contains(record.phase), !record.mailboxRegistered {
                try await claimIfNeeded(id); record = stateRecords[id] ?? record
            }
            guard record.mailboxRegistered || record.outgoing else { return }
            if record.outgoing, [.cancelled, .rejected].contains(record.phase), record.peerDeviceId == nil {
                let metadata = try await api.metadata(id, context: apiContext(id, priority: .control, terminal: true))
                guard metadata.binds(record) else { throw Failure.invalid }
                record.peerDeviceId = metadata.recipientDeviceId; try await save(record)
                if record.peerDeviceId == nil {
                    try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
                    record.mailboxClosed = true; try await save(record); return
                }
            }
            let live = ownedActive(id) != nil || record.requestStartedAt != nil
            let page = try await api.signals(id, after: record.signalCursor, context: apiContext(id,
                priority: record.phase.terminal ? .control : (live ? .live : .queued), terminal: record.phase.terminal))
            try requireCurrent(); guard page.transfer.binds(record), page.signals.count <= 32 else { throw Failure.invalid }
            if record.outgoing {
                if record.peerVerified, let claimed = record.peerDeviceId, claimed != page.transfer.recipientDeviceId { throw Failure.claimedElsewhere }
                record.peerDeviceId = page.transfer.recipientDeviceId
            } else if page.transfer.recipientDeviceId != device { throw Failure.claimedElsewhere }
            try await save(record)
            for envelope in page.signals { try await consume(envelope, id: id) }
            guard let refreshed = stateRecords[id] else { return }
            for control in refreshed.incomingControls { try await apply(control, id: id) }
            try await flushSignals(id)
            try await drainManualState(id)
            if let requested = stateRecords[id], requested.outgoing, requested.senderRequest != nil,
               controlPreparations[id] == nil, !requested.phase.terminal, slotAvailable(id) {
                launchSenderPreparation(id)
            }
            if var waiting = stateRecords[id], !waiting.outgoing, !waiting.phase.terminal,
               ownedActive(id) == nil, [.accepted, .connecting].contains(waiting.phase),
               let began = waiting.requestStartedAt {
                let budget = waiting.requestAcknowledged
                    ? max(90, DirectFileStream.verificationBudget(bytes: waiting.offer.size)) : 90
                if Double(now - began) >= budget {
                    waiting.phase = .paused; waiting.errorCode = "network_waiting"
                    waiting.requestNextRetryAt = now + min(1800, 90 << min(5, max(0, waiting.requestRetryCount - 1)))
                    try await save(waiting)
                    if roleReservations[false] == id { roleReservations[false] = nil }
                }
            }
            if let completed = stateRecords[id], completed.phase == .completed, !completed.mailboxClosed {
                if !completed.outgoing, !completed.completionControlQueued, let attempt = completed.attemptId {
                    try await queueControl(completed, type: .complete, attempt: attempt,
                        offset: completed.offer.size, prefix: completed.offer.sha256)
                } else if completed.outgoing, completed.completionReceived, !completed.completionAckQueued, let attempt = completed.attemptId {
                    try await queueControl(completed, type: .completeAck, attempt: attempt,
                        offset: completed.offer.size, prefix: completed.offer.sha256)
                } else if completed.outgoing, !completed.completionReceived,
                    completed.completionRecoveryCount < 6, now - completed.updatedAt >= 10 {
                    try await requestCompletionReplay(id)
                }
            }
            if page.transfer.status == "closed", !page.hasMore, var closed = stateRecords[id] {
                closed.mailboxClosed = true
                if !closed.phase.terminal { closed.phase = .paused; closed.errorCode = "unavailable" }
                try await save(closed); await retirePeer(id); return
            }
            if let terminal = stateRecords[id], [.cancelled, .rejected].contains(terminal.phase), terminal.peerDeviceId != nil {
                try await sendTerminalIfPossible(id, type: terminal.phase == .rejected ? .reject : .cancel)
            } else if let paused = stateRecords[id], eligibleNetworkWaiter(paused),
                paused.requestRetryCount < 6, (paused.requestNextRetryAt ?? 0) <= now,
                slotAvailable(id), receiverRequestEligible(paused), page.transfer.status == "active" {
                try await beginReceiverRequest(paused, resume: true)
            }
            recordFailures[id] = nil; retryAfter[id] = nil
            } catch is CancellationError { throw CancellationError() }
            catch APIError.missingSession { throw Failure.session }
            catch { try await handlePollFailure(error, id: id) }
    }

    private func handlePollFailure(_ error: Error, id: String) async throws {
        try requireCurrent()
        if var terminal = stateRecords[id], terminal.mailboxRegistered, terminal.phase.terminal,
           DirectFileAPI.classify(error) == .notFound || DirectFileAPI.classify(error) == .closed {
            // The routing record can disappear after the peer removes
            // its account. Retire only terminal bookkeeping, never an
            // active transfer or a verified local original.
            terminal.mailboxClosed = true; try await save(terminal)
            return
        }
        let attempts = min(6, (recordFailures[id] ?? 0) + 1)
        recordFailures[id] = attempts
        let limited = (error as? APIError)?.isRateLimited == true
        retryAfter[id] = ProcessInfo.processInfo.systemUptime + Double(limited ? 120 : min(120, 2 << attempts))
        if var failed = stateRecords[id], !failed.phase.terminal, !failed.mailboxClosed {
            failed.phase = .paused
            failed.errorCode = failed.errorCode ?? "disconnected"
            try await save(failed)
        }
        if ownedActive(id) != nil { await pauseActive(id, reason: "disconnected", notify: false) }
        if limited { return }
        // An unavailable old offer cannot starve a newer transfer.
        return
    }

    private func consume(_ envelope: DirectFileSignalEnvelope, id: String) async throws {
        guard var record = stateRecords[id], envelope.sequence > record.signalCursor else { return }
        let identity = DirectSignalIdentity(senderDeviceId: envelope.senderDeviceId, clientSignalId: envelope.clientSignalId)
        if (try? identity.validated()) != nil {
            record.signalBudget = try (record.signalBudget ?? .unknown()).recording(identity: identity)
            try await save(record)
        }
        guard
              envelope.recipientDeviceId == device,
              envelope.senderUserId == (record.outgoing ? record.offer.recipientUserId : record.offer.senderUserId),
              envelope.senderDeviceId == record.peerDeviceId,
              [1, 2].contains(envelope.messageType),
              let cipher = Data(base64Encoded: envelope.ciphertext), !cipher.isEmpty, cipher.count <= 65536,
              UUID(uuidString: envelope.clientSignalId)?.uuidString.lowercased() == envelope.clientSignalId else { throw Failure.invalid }
        if record.seenSignals.contains(envelope.clientSignalId) {
            record.signalCursor = envelope.sequence; try await save(record); return
        }
        let recovering = record.pendingInbound == envelope
        if let pending = record.pendingInbound, pending != envelope { throw Failure.invalid }
        record.pendingInbound = envelope
        try await save(record)
        let plaintext: Data
        do {
            plaintext = try await signal.decryptExact(EnvelopeInput(recipientDeviceId: device,
                messageType: envelope.messageType, ciphertext: envelope.ciphertext),
                senderUserId: envelope.senderUserId, senderDeviceId: envelope.senderDeviceId)
        } catch SignalError.duplicatedMessage {
            record.pendingInbound = nil; record.signalCursor = envelope.sequence
            record.seenSignals.append(envelope.clientSignalId)
            if !record.phase.terminal {
                record.phase = .paused
                record.errorCode = recovering ? "reconnect_required" : "invalid_control"
            }
            try await save(record)
            if !record.phase.terminal { await retirePeer(id) }
            // No effect is inferred from the unavailable plaintext. Consent,
            // completion and sticky cancellation remain exactly as journaled.
            if recovering, record.outgoing, record.peerVerified, record.peerDeviceId != nil {
                if record.phase == .completed { try await requestCompletionReplay(id) }
                else if !record.phase.terminal {
                    try await queueControl(record, type: .pause,
                        attempt: record.attemptId ?? UUID().uuidString.lowercased(), reason: "reconnect_required")
                }
            }
            return
        }
        try requireCurrent()
        let validPlaintext = (try? MessageTextPolicy.requireFreshPlaintext(plaintext)) != nil
        let control = validPlaintext && plaintext.count <= 48 * 1024
            ? try? JSONDecoder().decode(DirectFileControl.self, from: plaintext) : nil
        guard let control, control.validShape,
              control.allowsContext(offer: record.offer, outgoing: record.outgoing),
              control.allowsManualContext(record: record),
              control.sdp == nil || DirectFilePeerPolicy.dataOnlyDescription(control.sdp!),
              control.transferId == id, control.chatId == record.chatId,
              control.offerClientMessageId == record.offer.offerClientMessageId,
              record.offer.matchesNonce(control.offerNonce),
              control.clientSignalId == envelope.clientSignalId,
              control.senderUserId == envelope.senderUserId, control.senderDeviceId == envelope.senderDeviceId,
              control.recipientUserId == owner, control.recipientDeviceId == device else {
            // The Signal ratchet consumed this authenticated packet. Remember
            // its ID/cursor as terminally rejected instead of decrypting it in
            // an endless loop and preventing subsequent valid controls.
            record.seenSignals.append(envelope.clientSignalId); record.signalCursor = envelope.sequence
            record.pendingInbound = nil
            // A wrong nonce/binding has NO transfer or connection effect. Only
            // retire its consumed cursor so a healthy stream stays healthy.
            try await save(record); return
        }
        // Applied-event data is durable BEFORE advancing the mailbox cursor.
        // A restart replays this journal entry rather than decrypting it again.
        record.incomingControls.append(control); record.seenSignals.append(control.clientSignalId)
        if !record.outgoing { record.queue?.identityProofObserved = true }
        if record.outgoing, control.type == .accept || control.type == .resume { record.peerVerified = true }
        record.pendingInbound = nil
        guard record.seenSignals.count <= 256 else { throw Failure.invalid }
        record.signalCursor = envelope.sequence
        try await save(record)
        try await apply(control, id: id)
    }

    private func apply(_ control: DirectFileControl, id: String) async throws {
        do { try await applyEffect(control, id: id) }
        catch {
            // Journaled but obsolete/failed effects must not poison every later
            // poll. A fresh authenticated resume is the recovery boundary.
            if var record = stateRecords[id] {
                record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                if !record.phase.terminal { record.phase = .paused; record.errorCode = record.errorCode ?? "disconnected" }
                try await save(record); await retirePeer(id)
            }
            throw error
        }
    }

    private func applyEffect(_ control: DirectFileControl, id: String) async throws {
        guard var record = stateRecords[id] else { return }
        if record.phase.terminal && record.phase != .completed {
            record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
            try await save(record); return
        }
        let observed = try control.validatedManualSnapshot()
        let manual = record.manualPause ?? DirectManualPauseState()
        let packetEpochCurrent = observed.map {
            $0.pauseRevision >= manual.peerRevision && $0.ackPauseRevision == manual.localRevision
        } ?? (manual.localRevision == 0 && manual.peerRevision == 0)
        let staleNegotiation = [.accept, .resume, .offer, .answer].contains(control.type) && !packetEpochCurrent
        if control.type == .pauseState || observed != nil {
            try await applyManualObservation(control, id: id)
            guard let latest = stateRecords[id] else { return }
            record = latest
        }
        if control.type == .pauseState || staleNegotiation {
            // An older state may still advance its independent ACK domain,
            // but it can never arm a new SDP/request behind a known revision.
            record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
            try await save(record)
            try await drainManualState(id)
            return
        }
        let isRequest = control.type == .accept || control.type == .resume
        let isTerminal = control.type == .cancel || control.type == .reject
        if record.phase == .completed, !record.outgoing, control.type == .pause,
           control.reason == "reconnect_required", control.attemptId == record.attemptId {
            if !record.completionRecoveryTriggers.contains(control.clientSignalId) {
                guard record.completionRecoveryTriggers.count < 64 else { throw Failure.invalid }
                record.completionRecoveryTriggers.append(control.clientSignalId)
                record.completionControlQueued = false; try await save(record)
                try await queueControl(record, type: .complete, attempt: control.attemptId,
                    offset: record.offer.size, prefix: record.offer.sha256)
            }
            if var complete = stateRecords[id] {
                complete.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                try await save(complete)
            }
            return
        }
        let recoveryRequest = control.type == .pause && control.reason == "reconnect_required" &&
            !record.outgoing && record.consentGiven && record.phase == .paused && ownedActive(id) == nil
        if !isTerminal && !isRequest && !recoveryRequest && control.attemptId != record.attemptId ||
            isRequest && record.usedAttempts.contains(control.attemptId) {
            record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
            try await save(record); return
        }
        switch control.type {
        case .accept, .resume:
            guard record.outgoing, !record.phase.terminal,
                  let offset = control.offset, offset <= record.offer.size, control.prefixSHA256 != nil else { throw Failure.invalid }
            if !slotAvailable(id) {
                record.phase = .paused; record.errorCode = "queued"; record.attemptId = control.attemptId
                record.usedAttempts.append(control.attemptId); record.senderRequest = nil
                record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                try await save(record)
                try await queueControl(record, type: .pause, attempt: control.attemptId, reason: "queued")
                return
            }
            if !manualByteEligible(id) {
                record.phase = .paused; record.attemptId = control.attemptId
                record.usedAttempts.append(control.attemptId); record.senderRequest = nil
                record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                try await save(record); try await drainManualState(id)
                return
            }
            guard canStream() else {
                record.phase = .paused; record.errorCode = "call_in_progress"; record.pausedForLocalCall = true
                record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                try await save(record)
                try await queueControl(record, type: .pause, attempt: control.attemptId, reason: "call_in_progress")
                return
            }
            // A fresh authenticated receiver generation replaces an older
            // negotiation too. Ignoring it while the old PC is connecting
            // would wedge both peers: the receiver only accepts its newest ID.
            // Already used attempt IDs were retired above before this effect.
            await retirePeer(id)
            controlPreparations[id]?.cancel(); controlPreparations[id] = nil
            record.attemptId = control.attemptId; record.phase = .connecting
            record.usedAttempts.append(control.attemptId)
            record.durableOffset = offset; record.updatedAt = now
            record.senderRequest = control; record.requestAckSent = false
            record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
            try await save(record)
            // Receipt is authenticated BEFORE the expensive resume-prefix hash.
            // The disk actor never blocks cancellation/control journaling.
            try await queueControl(record, type: .pause, attempt: control.attemptId, reason: "busy")
            if var acknowledged = stateRecords[id] { acknowledged.requestAckSent = true; try await save(acknowledged) }
            launchSenderPreparation(id)
        case .offer:
            guard manualByteEligible(id), !record.outgoing, record.consentGiven, !record.phase.terminal,
                  [.accepted, .connecting, .paused].contains(record.phase),
                  record.attemptId == control.attemptId,
                  let sdp = control.sdp, DirectFilePeerPolicy.dataOnlyDescription(sdp), slotAvailable(id) else { throw Failure.invalid }
            await retirePeer(id)
            roleReservations[false] = id
            let checkpoint = try await disk.prepareReceiver(record)
            record.attemptId = control.attemptId; record.phase = .connecting; record.durableOffset = checkpoint.offset
            try await save(record)
            try await disk.beginAttempt(record)
            let peer = try await makePeer(record, initiator: false, offset: checkpoint.offset)
            try await peer.setRemoteDescription(sdp, offer: true)
            let answer = try await peer.createAnswer()
            guard current, ownedActive(id)?.peer === peer, manualByteEligible(id), !terminalRequests.contains(id) else { throw CancellationError() }
            try await queueControl(stateRecords[id] ?? record, type: .answer, attempt: control.attemptId, sdp: answer)
        case .answer:
            guard manualByteEligible(id), record.outgoing, record.attemptId == control.attemptId,
                  let peer = ownedActive(id)?.peer, ownedActive(id)?.attempt == control.attemptId,
                  let sdp = control.sdp else { throw Failure.invalid }
            try await peer.setRemoteDescription(sdp, offer: false)
        case .pauseState: break // Handled transfer-wide before attempt filtering.
        case .pause:
            guard (record.attemptId == control.attemptId || recoveryRequest), !record.phase.terminal else { break }
            // Network/capacity/old preparation notices cannot turn a source,
            // integrity, storage or trust failure into automatic continuation.
            if hardRecoveryFailure(record) { break }
            if !record.outgoing, control.reason == "busy", record.attemptId == control.attemptId,
               record.requestStartedAt != nil, ownedActive(id) == nil,
               [.accepted, .connecting].contains(record.phase) {
                record.requestAcknowledged = true; record.requestStartedAt = now
                record.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
                try await save(record); return
            }
            controlPreparations[id]?.cancel(); controlPreparations[id] = nil
            await retirePeer(id)
            record = stateRecords[id] ?? record
            record.phase = .paused; record.errorCode = control.reason
            if control.reason == "queued", !record.outgoing {
                var queue = record.queue ?? DirectFileQueueState(ordinal: 1, reservedFutureBytes: max(0, record.offer.size - record.durableOffset))
                queue.remoteCapacityBlocked = true
                record.queue = queue; record.requestStartedAt = nil; record.requestAcknowledged = false
                record.attemptId = nil
            } else if control.reason == "reconnect_required", !record.outgoing {
                if record.queue?.lastCapacityHintId != control.clientSignalId {
                    record.queue?.lastCapacityHintId = control.clientSignalId
                    record.queue?.remoteCapacityBlocked = false
                }
            }
            try await disk.suspend(id); try await save(record)
        case .cancel, .reject:
            guard control.type != .reject || record.outgoing else { throw Failure.invalid }
            if record.phase != .completed {
                record.phase = control.type == .reject ? .rejected : .cancelled
                record.updatedAt = now; try await save(record); await retirePeer(id)
                try await disk.discardUnfinished(record)
            }
        case .complete:
            guard record.outgoing, record.attemptId == control.attemptId,
                  control.offset == record.offer.size, control.prefixSHA256 == record.offer.sha256 else { throw Failure.invalid }
            // The HTTP completion can beat the data-channel completion callback
            // onto the UI actor. The sender's final fsynced ACK checkpoint,
            // written BEFORE finish is sent, is the authoritative byte fact.
            guard try await disk.durableOffset(record) == record.offer.size else { throw Failure.invalid }
            record.phase = .completed; record.durableOffset = record.offer.size
            record.completionReceived = true
            try await save(record)
            try await queueControl(record, type: .completeAck, attempt: control.attemptId,
                offset: record.offer.size, prefix: record.offer.sha256)
            try await flushSignals(id)
            try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
            if var closed = stateRecords[id] { closed.mailboxClosed = true; try await save(closed) }
            await retirePeer(id)
        case .completeAck:
            guard !record.outgoing, record.phase == .completed, record.attemptId == control.attemptId,
                  control.offset == record.offer.size, control.prefixSHA256 == record.offer.sha256 else { throw Failure.invalid }
            try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
            if var closed = stateRecords[id] { closed.mailboxClosed = true; try await save(closed) }
            await retirePeer(id)
        }
        if var finished = stateRecords[id] {
            finished.incomingControls.removeAll { $0.clientSignalId == control.clientSignalId }
            try await save(finished)
        }
    }

    private func receiverIdentityEligible(_ id: String) -> Bool {
        guard current, loaded, !admissionBlocked, manualStopFences[id] == nil,
              manualSaveFailures[id] == nil, !terminalRequests.contains(id), let record = stateRecords[id],
              !record.outgoing, record.consentGiven, !record.phase.terminal, !record.mailboxClosed,
              record.continuedProcessing?.requiresContinue != true else { return false }
        let state = record.manualPause ?? DirectManualPauseState()
        guard !state.localPaused, !state.peerPaused else { return false }
        if manualByteEligible(id) { return true }
        guard record.queue?.identityProofObserved != true else { return false }
        // A normal ACK latch explains the sole bootstrap exception. An
        // otherwise byte-eligible process latch is an unresolved local stop.
        return !manualStopLatches.contains(id) || !state.manualByteEligible
    }

    private func receiverRequestEligible(_ record: DirectFileRecord) -> Bool {
        guard !record.outgoing, record.consentGiven, !record.phase.terminal,
              !record.mailboxClosed, record.queue?.remoteCapacityBlocked != true,
              [.accepted, .paused].contains(record.phase), !record.pausedForLocalCall,
              record.requestStartedAt == nil, record.requestRetryCount < 6,
              (record.requestNextRetryAt ?? 0) <= now,
              [nil, "network_waiting", "connection_lost", "direct_unavailable", "background", "disconnected",
               "no_direct_route", "reconnect_required", "retry", "queued"].contains(record.errorCode) else { return false }
        return receiverIdentityEligible(record.id)
    }

    private func launchQueuedWork(_ id: String, operation: @escaping @MainActor () async throws -> Void) {
        guard queuedWorkJobs[id] == nil, queuedWorkJobs.count < 16 else { return }
        let lease = UUID()
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if self.queuedWorkJobs[id]?.lease == lease { self.queuedWorkJobs[id] = nil } }
            do {
                try await self.serialized(for: id) {
                    guard self.foreground, self.canStream() else { throw CancellationError() }
                    try await operation()
                }
            } catch is CancellationError { }
            catch {
                try? await self.serialized(for: id) { try await self.handlePollFailure(error, id: id) }
            }
        }
        queuedWorkJobs[id] = (lease, task)
    }

    private func scheduleQueuedWork() {
        guard current, loaded, !admissionBlocked, foreground, canStream() else { return }
        let ordered = stateRecords.values.sorted {
            let lhs = $0.queue?.ordinal ?? Int64.max, rhs = $1.queue?.ordinal ?? Int64.max
            return lhs == rhs ? $0.id < $1.id : lhs < rhs
        }
        // A slow peer's own serial chain is not an account-wide admission lock.
        // FIFO applies among jobs currently able to acquire their role.
        if inboundActive == nil, roleReservations[false] == nil {
            for candidate in ordered where !candidate.outgoing && queuedWorkJobs[candidate.id] == nil &&
                receiverRequestEligible(candidate) {
                guard slotAvailable(candidate.id) else { continue }
                launchQueuedWork(candidate.id) {
                    guard let fresh = self.stateRecords[candidate.id], self.receiverRequestEligible(fresh),
                          self.slotAvailable(candidate.id) else { return }
                    try await self.beginReceiverRequest(fresh, resume: true)
                }
                break
            }
        }
        if outboundActive == nil, roleReservations[true] == nil {
            for candidate in ordered where candidate.outgoing && queuedWorkJobs[candidate.id] == nil &&
                candidate.peerVerified && !candidate.phase.terminal && !candidate.mailboxClosed &&
                candidate.phase == .paused && candidate.senderRequest == nil {
                guard manualByteEligible(candidate.id), !hardRecoveryFailure(candidate), let queue = candidate.queue,
                      queue.capacityHintCount < 6, queue.nextCapacityHintAt <= now,
                      (candidate.signalBudget ?? .unknown()).mayFreezeOptional(pendingCount: candidate.outgoingSignals.count),
                      candidate.attemptId != nil else { continue }
                launchQueuedWork(candidate.id) {
                    guard var fresh = self.stateRecords[candidate.id], !fresh.phase.terminal, !fresh.mailboxClosed,
                          fresh.phase == .paused, fresh.senderRequest == nil,
                          self.outboundActive == nil, self.roleReservations[true] == nil,
                          self.manualByteEligible(candidate.id), !self.hardRecoveryFailure(fresh),
                          var queue = fresh.queue, queue.capacityHintCount < 6, queue.nextCapacityHintAt <= self.now,
                          (fresh.signalBudget ?? .unknown()).mayFreezeOptional(pendingCount: fresh.outgoingSignals.count),
                          let attempt = fresh.attemptId else { return }
                    queue.capacityHintCount += 1
                    queue.nextCapacityHintAt = self.now + min(1800, 30 << min(5, queue.capacityHintCount - 1))
                    fresh.queue = queue; try await self.save(fresh)
                    try await self.queueControl(fresh, type: .pause, attempt: attempt, reason: "reconnect_required")
                }
                break
            }
        }
    }

    private func makePeer(_ record: DirectFileRecord, initiator: Bool, offset: Int64) async throws -> DirectFilePeer {
        guard canStream(), slotAvailable(record.id), manualByteEligible(record.id), let attempt = record.attemptId,
              !record.outgoing || record.peerVerified else { throw Failure.busy }
        let role = record.outgoing
        roleReservations[role] = record.id
        var admitted = false
        defer { if !admitted, roleReservations[role] == record.id { roleReservations[role] = nil } }
        let token = try await auth.currentAccessToken()
        let servers = try await callsAPI.iceServers(accessToken: token)
        try requireCurrent()
        guard let latest = stateRecords[record.id], latest.attemptId == attempt,
              !latest.phase.terminal, slotAvailable(record.id), manualByteEligible(record.id),
              !terminalRequests.contains(record.id), foreground, canStream() else { throw CancellationError() }
        let peer = try DirectFilePeer(stunURLs: servers.flatMap(\.urls), initiator: initiator)
        let owned = Active(id: record.id, attempt: attempt, peer: peer, outgoing: record.outgoing)
        // A foreground button authorizes only the first intended native
        // attempt, never an automatic retry or a later queued connection.
        if let deadline = backgroundUserIntents.removeValue(forKey: record.id),
           deadline >= ProcessInfo.processInfo.systemUptime {
            owned.backgroundUserIntentDeadline = deadline
        }
        if record.outgoing { outboundActive = owned } else { inboundActive = owned }
        admitted = true
        let lease = owned.lease
        let stream = DirectFileStream(record: record, disk: disk, peer: peer, execution: owned.execution) { [weak self] event in
            await self?.handleStreamEvent(event, id: record.id, attempt: attempt, lease: lease)
        }
        owned.stream = stream
        peer.onPacket = { bytes in try await stream.receive(bytes) }
        peer.onState = { [weak self, weak owned] state in
            guard let self, let owned, self.ownedActive(owned.id) === owned, self.current else { return }
            switch state {
            case .connected:
                Task { @MainActor in
                    try? await self.serialized(for: record.id) {
                        guard self.ownedActive(owned.id) === owned, self.manualByteEligible(record.id), var current = self.stateRecords[record.id], !current.phase.terminal else { return }
                        current.phase = .transferring
                        try await self.save(current)
                        await stream.start()
                        self.requestContinuedProcessing(owned)
                    }
                }
            case .failed, .disconnected:
                Task { @MainActor in
                    try? await self.serialized(for: record.id) {
                        if self.ownedActive(owned.id) === owned { await self.pauseActive(owned.id, reason: "no_direct_route", notify: true) }
                    }
                }
            case .closed:
                Task { @MainActor in
                    try? await self.serialized(for: record.id) {
                        if self.ownedActive(owned.id) === owned, self.stateRecords[record.id]?.phase == .completed {
                            await self.retirePeer(record.id)
                        }
                    }
                }
            case .connecting: break
            }
        }
        return peer
    }

    private func launchSenderPreparation(_ id: String) {
        guard controlPreparations[id] == nil, let record = stateRecords[id],
              let request = record.senderRequest, record.peerVerified, !record.phase.terminal,
              slotAvailable(id), manualByteEligible(id), canStream() else { return }
        roleReservations[true] = id
        controlPreparations[id] = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.stateRecords[id]?.senderRequest?.clientSignalId == request.clientSignalId {
                    self.controlPreparations[id] = nil
                }
            }
            do {
                if !record.requestAckSent {
                    try await self.serialized(for: id) {
                        guard let current = self.stateRecords[id], current.senderRequest?.clientSignalId == request.clientSignalId else { throw CancellationError() }
                        try await self.queueControl(current, type: .pause, attempt: request.attemptId, reason: "busy")
                        if var updated = self.stateRecords[id] { updated.requestAckSent = true; try await self.save(updated) }
                    }
                }
                guard try await self.disk.verifySourcePrefix(record, offset: request.offset!, sha256: request.prefixSHA256!) else { throw Failure.integrity }
                try Task.checkCancellation()
                let peer: DirectFilePeer = try await self.serialized(for: id) {
                    guard let fresh = self.stateRecords[id], fresh.senderRequest?.clientSignalId == request.clientSignalId,
                          !fresh.phase.terminal, self.foreground, self.canStream(), self.manualByteEligible(id), !self.terminalRequests.contains(id) else { throw CancellationError() }
                    try await self.disk.beginAttempt(fresh)
                    return try await self.makePeer(fresh, initiator: true, offset: fresh.durableOffset)
                }
                let sdp = try await peer.createOffer()
                try await self.serialized(for: id) {
                    guard var fresh = self.stateRecords[id], fresh.senderRequest?.clientSignalId == request.clientSignalId,
                          self.ownedActive(id)?.peer === peer, self.manualByteEligible(id), !fresh.phase.terminal, !self.terminalRequests.contains(id) else { throw CancellationError() }
                    try await self.queueControl(fresh, type: .offer, attempt: request.attemptId, sdp: sdp)
                    fresh = self.stateRecords[id] ?? fresh; fresh.senderRequest = nil
                    try await self.save(fresh); self.controlPreparations[id] = nil
                }
            } catch is CancellationError { }
            catch {
                try? await self.serialized(for: id) {
                    guard var fresh = self.stateRecords[id], fresh.senderRequest?.clientSignalId == request.clientSignalId, !fresh.phase.terminal else { return }
                    fresh.phase = .paused; fresh.errorCode = (error as? Failure) == .integrity ? "prefix_mismatch" : "source_changed"
                    fresh.senderRequest = nil; try await self.save(fresh)
                    await self.retirePeer(id)
                    try await self.queueControl(fresh, type: .pause, attempt: request.attemptId, reason: fresh.errorCode)
                }
                self.controlPreparations[id] = nil
            }
        }
    }

    private func handleStreamEvent(_ event: DirectFileStream.Event, id: String, attempt: String, lease: UUID) async {
        guard current, let owned = ownedActive(id), owned.id == id, owned.attempt == attempt, owned.lease == lease else { return }
        switch event {
        case .progress(let snapshot):
            publishProgress(id, snapshot: snapshot)
            continuedProcessing.progress(owned.member, offset: snapshot.offset)
            if let record = stateRecords[id] { rowState(id).update(presentation(record)) }
            if let record = stateRecords[id], !record.outgoing, record.requestRetryCount > 0,
               snapshot.offset > record.durableOffset, checkpointJobs[id] == nil {
                // Only newly fsynced bytes reset consecutive request failures;
                // an idle/duplicate ACK cannot keep an offline retry loop alive.
                let checkpointLease = UUID()
                let task = Task { @MainActor in
                    defer { if self.checkpointJobs[id]?.lease == checkpointLease { self.checkpointJobs[id] = nil } }
                    try? await self.serialized(for: id) {
                        guard self.ownedActive(owned.id) === owned, var fresh = self.stateRecords[id], fresh.attemptId == attempt,
                              !fresh.phase.terminal, fresh.requestRetryCount > 0,
                              snapshot.offset > fresh.durableOffset else { return }
                        fresh.requestRetryCount = 0; fresh.requestNextRetryAt = nil
                        self.retryAfter[id] = nil; self.recordFailures[id] = nil
                        try await self.save(fresh)
                    }
                }
                checkpointJobs[id] = (checkpointLease, task)
            }
        case .verifying:
            verifyingTransfers.insert(id)
            try? await serialized(for: id) {
                guard self.ownedActive(owned.id) === owned, var record = self.stateRecords[id], !record.phase.terminal else { return }
                record.phase = .verifying; try await self.save(record)
            }
        case .completed:
            verifyingTransfers.remove(id)
            try? await serialized(for: id) {
                guard self.ownedActive(owned.id) === owned, var record = self.stateRecords[id], record.attemptId == attempt else { return }
                record.durableOffset = record.offer.size; record.phase = .completed; record.updatedAt = self.now
                try await self.save(record)
                var progress = self.progress[id] ?? DirectFileProgress(offset: record.offer.size)
                progress.offset = record.offer.size; self.publishProgress(id, snapshot: progress, force: true)
                if !record.outgoing {
                    // A peer may close after receiving the binary final ACK
                    // before this serialized completion handler is resumed.
                    if !owned.peer.isOpen { await self.retirePeer(id) }
                    try await self.queueControl(record, type: .complete, attempt: attempt,
                        offset: record.offer.size, prefix: record.offer.sha256)
                } else {
                    // The native final ACK already arrived and the completed
                    // fact is durable. Signal recovery keeps its own journal;
                    // it must not occupy the one live file stream slot.
                    await self.retirePeer(id)
                }
            }
        case .paused(let reason):
            verifyingTransfers.remove(id)
            try? await serialized(for: id) {
                if self.ownedActive(owned.id) === owned { await self.pauseActive(owned.id, reason: reason, notify: true) }
            }
        }
    }
    /// Local hold closes only this transfer immediately. Durability/error is
    /// handled on its own ordered chain; another direction keeps streaming.
    func manualPause(_ id: String) { changeManualHold(id, paused: true) }
    func manualContinue(_ id: String) { noteBackgroundUserIntent(id); changeManualHold(id, paused: false) }
    private func changeManualHold(_ id: String, paused: Bool) {
        guard current, let initial = stateRecords[id], !initial.phase.terminal, !initial.mailboxClosed,
              initial.phase != .verifying, !verifyingTransfers.contains(id),
              initial.outgoing || initial.consentGiven else { return }
        let initialState = initial.manualPause ?? DirectManualPauseState()
        let needsRecovery = manualStopFences[id] != nil || manualSaveFailures[id] != nil || initial.continuedProcessing?.requiresContinue == true
        guard initialState.localPaused != paused || needsRecovery || (!paused && manualStopLatches.contains(id)) else { return }
        let actionLease = UUID(); manualActionLeases[id] = actionLease
        manualStopFences[id] = actionLease
        manualStopLatches.insert(id)
        let immediateCheckpoint: Task<Int64?, Never>?
        if paused {
            manualStopLatches.insert(id)
            controlPreparations[id]?.cancel()
            immediateCheckpoint = stopTransportImmediately(id)
        } else { immediateCheckpoint = nil }
        manualPendingIntents[id] = paused
        refreshPresentations()
        Task { @MainActor in
            let apiKey = self.apiKey(id), apiLease = self.api.promote(self.apiKey(id))
            defer { self.api.demote(apiKey, lease: apiLease) }
            defer {
                if self.manualActionLeases[id] == actionLease {
                    self.manualPendingIntents[id] = nil; self.refreshPresentations()
                }
            }
            var intentCommitted = false
            do {
                try await self.serialized(for: id) {
                    guard self.manualActionLeases[id] == actionLease,
                          var record = self.stateRecords[id], !record.phase.terminal, !record.mailboxClosed,
                          record.phase != .verifying, !self.verifyingTransfers.contains(id) else { return }
                    if !paused, self.manualStopLatches.contains(id) {
                        guard let durable = try await self.disk.journal.record(id), durable.offer == record.offer,
                              durable.chatId == record.chatId, durable.localDeviceId == self.device else { throw Failure.invalid }
                        record = self.stateRecords[id] ?? record
                        if (durable.manualPause?.localRevision ?? 0) >= (record.manualPause?.localRevision ?? 0) {
                            record.manualPause = durable.manualPause
                        }
                    }
                    let previous = record.manualPause ?? DirectManualPauseState()
                    if previous.localPaused == paused && self.manualStopFences[id] == nil && self.manualSaveFailures[id] == nil && !(self.manualStopLatches.contains(id) && !paused) { return }
                    let desired = try previous.changingLocal(paused: paused)
                    record.manualPause = desired
                    if !paused {
                        record.continuedProcessing = nil
                        if record.errorCode == "background_interrupted" { record.errorCode = nil }
                    }
                    if let oldAttempt = record.attemptId, !record.usedAttempts.contains(oldAttempt) {
                        guard record.usedAttempts.count < 256 else { throw Failure.invalid }
                        record.usedAttempts.append(oldAttempt)
                    }
                    record.attemptId = nil; record.senderRequest = nil
                    record.requestStartedAt = nil; record.requestAcknowledged = false
                    // A failed Continue commit keeps its old durable hold and
                    // stop latch. A failed Pause commit keeps the local latch.
                    do { try await self.save(record) }
                    catch {
                        try await self.reconcileManualWriteFailure(record)
                        throw error
                    }
                    self.controlPreparations[id]?.cancel(); self.controlPreparations[id] = nil
                    let offset: Int64?
                    if let immediateCheckpoint { offset = await immediateCheckpoint.value }
                    else { offset = await self.retirePeer(id) }
                    guard var fresh = self.stateRecords[id], !fresh.phase.terminal else { return }
                    if let offset {
                        guard offset >= 0, offset <= fresh.offer.size else { throw Failure.invalid }
                        fresh.durableOffset = max(fresh.durableOffset, offset)
                        self.stateRecords[id] = fresh
                    }
                    fresh.senderRequest = nil; fresh.requestStartedAt = nil; fresh.requestAcknowledged = false
                    fresh.phase = .paused; fresh.updatedAt = self.now
                    do { try await self.save(fresh) }
                    catch {
                        try await self.reconcileManualWriteFailure(fresh)
                        throw error
                    }
                    intentCommitted = true
                    if self.manualActionLeases[id] == actionLease {
                        self.manualSaveFailures[id] = nil; self.manualStopFences[id] = nil
                        self.manualPendingIntents[id] = nil; self.refreshPresentations()
                    }
                    if !paused, self.manualActionLeases[id] == actionLease { self.releaseManualStopLatchIfSafe(id) }
                    guard self.manualActionLeases[id] == actionLease else { return }
                    try await self.drainManualState(id)
                    if !paused, fresh.outgoing, self.manualByteEligible(id), fresh.peerVerified {
                        try await self.queueControl(fresh, type: .pause, attempt: UUID().uuidString.lowercased(), reason: "reconnect_required")
                    }
                }
                self.wakePolling()
            } catch {
                if self.current, self.manualActionLeases[id] == actionLease,
                   let latest = self.stateRecords[id], !latest.phase.terminal,
                   latest.phase != .verifying, !self.verifyingTransfers.contains(id) {
                    if !intentCommitted { self.manualSaveFailures[id] = paused }
                    self.refreshPresentations()
                    self.errorMessage = !intentCommitted
                        ? (paused ? "Stopped on this device. Could not confirm that the pause was saved. Retry before closing UNYRI."
                                  : "Could not confirm continuation. The transfer remains stopped.")
                        : self.userMessage(error)
                }
            }
        }
    }

    func retryManualSynchronization(_ id: String) {
        Task { @MainActor in
            let apiKey = self.apiKey(id), apiLease = self.api.promote(self.apiKey(id))
            defer { self.api.demote(apiKey, lease: apiLease) }
            do {
                try await self.serialized(for: id) {
                    guard var record = self.stateRecords[id], !record.phase.terminal else { return }
                    var state = record.manualPause ?? DirectManualPauseState()
                    guard DirectManualTiming.canResetCycle(nowMillis: self.now * 1000, startedAtMillis: state.syncCycleStartedAt) else { return }
                    state.syncProbeCount = 0; state.nextSyncAt = 0; state.syncCycleStartedAt = self.now * 1000; state.syncNeeded = true
                    record.manualPause = state
                    // Manual Retry is also one bounded capacity retry; neither
                    // operation clears local/peer holds or safety errors.
                    record.queue?.remoteCapacityBlocked = false
                    try await self.save(record); try await self.drainManualState(id)
                }
                self.wakePolling()
            } catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }

    /// A peer ACK cannot release a process-local stop whose own write is
    /// unresolved. Only the latest explicit own durable intent retires its fence.
    private func releaseManualStopLatchIfSafe(_ id: String) {
        guard manualStopFences[id] == nil, manualSaveFailures[id] == nil,
              let record = stateRecords[id], !record.phase.terminal, !record.mailboxClosed,
              (record.manualPause ?? DirectManualPauseState()).manualByteEligible else { return }
        manualStopLatches.remove(id)
    }

    /// Stay on the same transfer's serial lease while resolving an ambiguous
    /// write. Adopt only a fully validated owned journal; never infer a commit
    /// from RAM or let an incoming peer snapshot overwrite a newer own revision.
    private func reconcileManualWriteFailure(_ attempted: DirectFileRecord) async throws {
        try requireCurrent()
        guard let durable = try await disk.journal.record(attempted.id),
              durable.offer == attempted.offer, durable.chatId == attempted.chatId, durable.outgoing == attempted.outgoing,
              durable.localDeviceId == device,
              (durable.outgoing ? durable.offer.senderUserId : durable.offer.recipientUserId) == owner else { throw Failure.invalid }
        try requireCurrent()
        guard let latest = stateRecords[attempted.id], latest.offer == attempted.offer,
              latest.chatId == attempted.chatId, latest.localDeviceId == device else { throw Failure.invalid }
        let saved = durable.manualPause ?? DirectManualPauseState()
        let memory = latest.manualPause ?? DirectManualPauseState()
        guard saved.localRevision != memory.localRevision || saved.localPaused == memory.localPaused else { throw Failure.invalid }
        if saved.localRevision > memory.localRevision {
            stateRecords[attempted.id] = durable
            publishRecord(durable)
        }
        // The process stop/failure fence deliberately remains until the user
        // explicitly retries Pause/Continue, even if this read found a commit.
    }

    private func applyManualObservation(_ control: DirectFileControl, id: String) async throws {
        guard var record = stateRecords[id], !record.phase.terminal, record.phase != .verifying,
              let snapshot = try control.validatedManualSnapshot() else { return }
        let result = try (record.manualPause ?? DirectManualPauseState()).applying(snapshot: snapshot, requestAck: control.requestAck == true)
        var state = result.state
        if result.needsResponse { state.pendingResponseTo = control.clientSignalId }
        if state.acknowledgedLocalRevision == state.localRevision { state.syncNeeded = false }
        record.manualPause = state
        let mustRetire = result.peerChanged || state.localPaused || state.peerPaused
        if mustRetire {
            record.senderRequest = nil; record.requestStartedAt = nil; record.requestAcknowledged = false
            record.phase = .paused
        }
        try await save(record)
        if mustRetire {
            controlPreparations[id]?.cancel(); controlPreparations[id] = nil
            let offset = await retirePeer(id)
            if var fresh = stateRecords[id], !fresh.phase.terminal {
                if let offset { fresh.durableOffset = max(fresh.durableOffset, offset) }
                try await save(fresh)
            }
        }
        releaseManualStopLatchIfSafe(id)
        try await drainManualState(id)
    }

    private func drainManualState(_ id: String) async throws {
        guard let record = stateRecords[id], !record.phase.terminal, !record.mailboxClosed,
              record.mailboxRegistered, record.peerDeviceId != nil,
              record.outgoing ? record.peerVerified : record.consentGiven,
              let state = record.manualPause else { return }
        let reply = state.pendingResponseTo != nil
        let newIntent = state.localRevision > state.publishedLocalRevision
        guard reply || newIntent || state.syncNeeded && !state.syncLimitReached && state.nextSyncAt <= now * 1000 else { return }
        guard (record.signalBudget ?? .unknown()).mayFreezeOptional(pendingCount: record.outgoingSignals.count) else { return }
        try await queueControl(record, type: .pauseState,
            attempt: record.attemptId ?? UUID().uuidString.lowercased(), manualRequestAck: !reply)
    }

    private func wakePolling() {
        pollTask?.cancel(); pollTask = nil; pollLease = nil
        start()
    }

    private func queueControl(_ source: DirectFileRecord, type: DirectFileControl.Kind, attempt: String,
                              sdp: String? = nil, offset: Int64? = nil, prefix: String? = nil, reason: String? = nil,
                              completionRecovery: Bool = false, manualRequestAck: Bool? = nil, readyAllowed: (() -> Bool)? = nil) async throws {
        guard var record = stateRecords[source.id], let peerDevice = record.peerDeviceId,
              !record.outgoing || record.peerVerified else { throw Failure.invalid }
        let readyKind = [DirectFileControl.Kind.accept, .resume, .offer, .answer].contains(type)
        let actionEpoch = manualActionLeases[source.id]
        let readiness = { [self] in
            guard readyAllowed?() != false else { return false }
            if !readyKind { return true }
            guard self.foreground, self.canStream(), self.manualActionLeases[source.id] == actionEpoch,
                  let fresh = self.stateRecords[source.id], !fresh.phase.terminal, !fresh.mailboxClosed,
                  !self.terminalRequests.contains(source.id),
                  fresh.attemptId == attempt else { return false }
            return type == .accept || type == .resume
                ? self.receiverIdentityEligible(source.id) : self.manualByteEligible(source.id)
        }
        guard readiness() else { throw CancellationError() }
        let recipient = record.outgoing ? record.offer.recipientUserId : record.offer.senderUserId
        let id = UUID().uuidString.lowercased()
        var control = DirectFileControl(transferId: record.id, chatId: record.chatId,
            offerClientMessageId: record.offer.offerClientMessageId, offerNonce: record.offer.offerNonce, clientSignalId: id,
            senderUserId: owner, senderDeviceId: device, recipientUserId: recipient,
            recipientDeviceId: peerDevice, attemptId: attempt, type: type, sdp: sdp,
            offset: offset, prefixSHA256: prefix, reason: reason)
        if [.accept, .resume, .offer, .answer, .pauseState].contains(type) {
            let snapshot = (record.manualPause ?? DirectManualPauseState()).snapshot()
            control.pauseRevision = snapshot.pauseRevision; control.paused = snapshot.paused
            control.ackPauseRevision = snapshot.ackPauseRevision
            if type == .pauseState { control.requestAck = manualRequestAck ?? false }
        }
        guard control.validShape, record.outgoingSignals.count < 8 else { throw Failure.invalid }
        let terminalPriority = [.cancel, .reject, .complete, .completeAck].contains(type) || completionRecovery
        if !terminalPriority, record.outgoingSignals.count >= 6 {
            if type == .pauseState || type == .pause && reason == "reconnect_required" { return }
            throw Failure.busy
        }
        if type == .complete, record.completionControlQueued { try await flushSignals(record.id); return }
        if type == .completeAck, record.completionAckQueued { try await flushSignals(record.id); return }
        let optional = type == .pauseState || type == .pause && reason == "reconnect_required" && !completionRecovery
        var budget = record.signalBudget ?? .unknown()
        if optional, !budget.mayFreezeOptional(pendingCount: record.outgoingSignals.count) { return }
        budget = try budget.recording(identity: DirectSignalIdentity(senderDeviceId: device, clientSignalId: id))
        record.signalBudget = budget
        try await save(record) // Ambiguous encryption/POST failures never mint credits.
        guard readiness() else { throw CancellationError() }
        let plaintext = try JSONEncoder().encode(control)
        guard plaintext.count <= 48 * 1024 else { throw Failure.invalid }
        let encrypted = try await signal.encryptExact(plaintext: plaintext, userId: recipient, deviceId: peerDevice)
        try requireCurrent()
        guard let latest = stateRecords[source.id], latest.offer == record.offer, latest.peerDeviceId == peerDevice else { throw Failure.invalid }
        guard readiness() else { throw CancellationError() }
        record = latest
        record.outgoingSignals.append(DirectFileFrozenSignal(clientSignalId: id, recipientDeviceId: peerDevice,
            messageType: encrypted.messageType, ciphertext: encrypted.ciphertext))
        if type == .pauseState, var state = record.manualPause {
            if state.pendingResponseTo == source.manualPause?.pendingResponseTo { state.pendingResponseTo = nil }
            let frozenRevision = control.pauseRevision ?? 0
            let newIntent = frozenRevision > state.publishedLocalRevision
            state.publishedLocalRevision = max(state.publishedLocalRevision, frozenRevision)
            if manualRequestAck == true {
                if newIntent {
                    // Real intent gets one publication even if the previous
                    // repair cycle is exhausted. Cooldown governs repairs.
                    if DirectManualTiming.canResetCycle(nowMillis: now * 1000, startedAtMillis: state.syncCycleStartedAt) {
                        state.syncProbeCount = 0; state.syncCycleStartedAt = now * 1000
                        state.nextSyncAt = now * 1000 + 2000
                    }
                } else {
                    state.syncProbeCount = min(6, state.syncProbeCount + 1)
                    let next = min(5, state.syncProbeCount)
                    state.nextSyncAt = now * 1000 + (try DirectManualTiming.probeDelayMillis(index: next))
                }
            }
            record.manualPause = state
        }
        if type == .complete { record.completionControlQueued = true }
        if type == .completeAck { record.completionAckQueued = true }
        if completionRecovery {
            record.completionRecoveryNextAt = now + min(1800, 90 << min(5, record.completionRecoveryCount))
            record.completionRecoveryCount += 1
        }
        struct RetireUnsentReady: Error {}
        var currentPostAttempted = false
        do {
            try await save(record)
            guard readiness() else { throw RetireUnsentReady() }
            let background = type == .pause && reason == "background" && backgroundTask != .invalid
                ? apiBackgroundLease.map { (lease: $0, attempt: attempt, signal: id) } : nil
            try await flushSignals(record.id, background: background, beforePost: { signal in
                if signal.clientSignalId == id {
                    guard readiness() else { throw RetireUnsentReady() }
                    currentPostAttempted = true
                }
            })
        } catch {
            if readyKind, !currentPostAttempted,
               error is RetireUnsentReady || error is CancellationError {
                // Cancel-safe cleanup stays inside this transfer's serial
                // scope. The ciphertext was never attempted by this producer;
                // retire only its body, keeping its unique budget debit.
                let cleanup = Task { @MainActor in
                    try self.requireCurrent()
                    guard var durable = try await self.disk.journal.record(source.id),
                          durable.offer == source.offer, durable.chatId == source.chatId,
                          durable.localDeviceId == self.device, durable.outgoing == source.outgoing else { throw Failure.invalid }
                    if durable.outgoingSignals.contains(where: { $0.clientSignalId == id }) {
                        durable.outgoingSignals.removeAll { $0.clientSignalId == id }
                        try await self.save(durable)
                    }
                }
                try await cleanup.value
                throw CancellationError()
            }
            throw error
        }
    }

    private func requestCompletionReplay(_ id: String) async throws {
        guard let record = stateRecords[id], record.outgoing, record.phase == .completed,
              record.peerVerified, !record.completionReceived, !record.mailboxClosed,
              record.completionRecoveryCount < 6, (record.completionRecoveryNextAt ?? 0) <= now,
              let attempt = record.attemptId else { return }
        // At most six fresh logical hints with exponential backoff. Every
        // individual ciphertext is frozen/retryable and can never reopen a
        // peer, reverse completion or change the verified file.
        try await queueControl(record, type: .pause, attempt: attempt,
            reason: "reconnect_required", completionRecovery: true)
    }

    private func flushSignals(_ id: String, background: (lease: UUID, attempt: String, signal: String)? = nil, beforePost: ((DirectFileFrozenSignal) throws -> Void)? = nil) async throws {
        guard let record = stateRecords[id] else { return }
        for signal in record.outgoingSignals {
            // Background authority is only for this exact newly frozen notice.
            // Earlier opaque predecessors remain unchanged for foreground replay.
            if let background, signal.clientSignalId != background.signal { return }
            try requireCurrent()
            let context = apiContext(id, priority: record.phase.terminal ? .control : .queued,
                terminal: record.phase.terminal, background: background.map { ($0.lease, $0.attempt) })
            try await api.send(id, signal: signal, context: context, beforeAttempt: {
                try self.requireCurrent()
                try beforePost?(signal)
            })
            try requireCurrent()
            guard var fresh = stateRecords[id] else { return }
            fresh.outgoingSignals.removeAll { $0.clientSignalId == signal.clientSignalId }
            try await save(fresh)
        }
    }

    private func sendTerminalIfPossible(_ id: String, type: DirectFileControl.Kind) async throws {
        guard var record = stateRecords[id], !record.mailboxClosed, record.mailboxRegistered, record.peerDeviceId != nil else { return }
        guard record.errorCode != "terminal_sent" else { return }
        if record.outgoing, !record.peerVerified {
            // No nonce-bearing packet may be echoed to a server-only claimed
            // device. No file has flowed before this proof; close as generic
            // unavailable while retaining the user's local sticky tombstone.
            try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
            record.mailboxClosed = true; record.errorCode = "terminal_sent"
            try await save(record); return
        }
        try await queueControl(record, type: type, attempt: record.attemptId ?? UUID().uuidString.lowercased(),
            reason: type == .reject ? "user_declined" : "user_cancelled")
        record = stateRecords[id] ?? record; record.errorCode = "terminal_sent"; try await save(record)
        try await api.close(id, context: apiContext(id, priority: .control, terminal: true))
        if var closed = stateRecords[id] { closed.mailboxClosed = true; try await save(closed) }
    }

    private var allActive: [Active] { [outboundActive, inboundActive].compactMap { $0 } }
    private func ownedActive(_ id: String) -> Active? {
        if outboundActive?.id == id { return outboundActive }
        if inboundActive?.id == id { return inboundActive }
        return nil
    }
    private func manualByteEligible(_ id: String) -> Bool {
        guard loaded, !admissionBlocked, manualStopFences[id] == nil, manualSaveFailures[id] == nil,
              let record = stateRecords[id], !manualStopLatches.contains(id),
              record.continuedProcessing?.requiresContinue != true else { return false }
        return (record.manualPause ?? DirectManualPauseState()).manualByteEligible
    }
    private func pauseActive(_ id: String, reason: String, notify: Bool) async {
        guard let owned = ownedActive(id), let original = stateRecords[id], !original.phase.terminal else { return }
        let hadGrant = owned.execution.hasGrant || owned.execution.wasInterrupted
        let checkpoint = await retirePeer(id)
        guard var record = stateRecords[id], record.attemptId == original.attemptId, !record.phase.terminal else { return }
        if let checkpoint { record.durableOffset = max(record.durableOffset, checkpoint) }
        record.phase = .paused; record.errorCode = reason; record.updatedAt = now
        if hadGrant {
            record.continuedProcessing?.requiresContinue = true
            if !hardRecoveryFailure(record) { record.errorCode = "background_interrupted" }
        }
        try? await save(record)
        if notify, current { try? await queueControl(record, type: .pause, attempt: owned.attempt, reason: reason) }
    }

    @discardableResult private func stopTransportImmediately(_ id: String, preservingReceiverLease: UUID? = nil, finishContinued: Bool = true) -> Task<Int64?, Never>? {
        if let request = receiverRequestTasks[id], request.lease != preservingReceiverLease { request.task.cancel() }
        if let pending = stopping[id] { return pending.task }
        let roles = [false, true].filter { roleReservations[$0] == id }
        let owned = ownedActive(id)
        guard owned != nil || !roles.isEmpty || controlPreparations[id] != nil else { return nil }
        if let owned {
            owned.execution.revoke()
            if outboundActive === owned { outboundActive = nil }
            if inboundActive === owned { inboundActive = nil }
            owned.peer.close()
        }
        // Keep this role's reservation until its actual worker exits. A new
        // same-role prefix cannot mistake a cancelling bulk lane for corruption.
        let lease = UUID()
        let task = Task { @MainActor [weak self] () -> Int64? in
            guard let self else { return nil }
            let offset: Int64?
            if let stream = owned?.stream { offset = await stream.stop() }
            else { await self.disk.suspend(id); offset = nil }
            if let owned, finishContinued {
                self.continuedProcessing.finish(owned.member, success: self.stateRecords[id]?.phase == .completed)
            }
            if self.stopping[id]?.lease == lease {
                for role in roles where self.roleReservations[role] == id {
                    if !role && self.receiverRequestLeases[id] != nil { continue }
                    self.roleReservations[role] = nil
                }
                self.stopping[id] = nil
            }
            return offset
        }
        stopping[id] = (lease, task)
        return task
    }
    @discardableResult private func retirePeer(_ id: String, preservingReceiverLease: UUID? = nil) async -> Int64? {
        await stopTransportImmediately(id, preservingReceiverLease: preservingReceiverLease)?.value
    }

    private func slotAvailable(_ id: String) -> Bool {
        guard let record = stateRecords[id], stopping[id] == nil else { return false }
        let roleActive = record.outgoing ? outboundActive : inboundActive
        return (roleActive == nil || roleActive?.id == id) &&
            (roleReservations[record.outgoing] == nil || roleReservations[record.outgoing] == id)
    }

    func rowState(_ id: String) -> DirectFileRowState {
        if let existing = rowStates[id] { return existing }
        let created = DirectFileRowState(); rowStates[id] = created
        if let snapshot = progress[id] { created.update(snapshot) }
        if let record = stateRecords[id] { created.update(presentation(record)) }
        return created
    }

    private func presentation(_ record: DirectFileRecord) -> DirectFilePresentation {
        let manual = record.manualPause ?? DirectManualPauseState()
        let active = ownedActive(record.id) != nil
        let canAct = current && !record.mailboxClosed && !record.phase.terminal
        var actions = Set<DirectFileAction>()
        var status = [.paused, .failed].contains(record.phase)
            ? DirectFileFailureText.status(record.errorCode, outgoing: record.outgoing) : record.phase.label
        var displayPhase = record.phase
        if canAct {
            if !record.outgoing && !record.consentGiven && record.phase == .offered {
                actions = [.accept, .reject]
            } else {
                actions.insert(.cancel)
                if record.outgoing || record.consentGiven,
                   record.phase != .verifying, !verifyingTransfers.contains(record.id) {
                    actions.insert(manual.localPaused ? .resume : .pause)
                }
                if record.phase == .paused, !manual.localPaused, !manual.peerPaused,
                   manual.manualByteEligible, !manualStopLatches.contains(record.id) {
                    if record.errorCode == "prefix_mismatch", !record.outgoing { actions.insert(.restart) }
                    else if record.outgoing ? canRetrySender(record) :
                        !["integrity_failed", "integrity_mismatch", "verification_failed", "source_missing", "source_changed",
                          "claimed_elsewhere", "transfer_unavailable"].contains(record.errorCode ?? "") { actions.insert(.retry) }
                }
            }
        }
        if record.phase == .completed { actions = [.open, .share] }
        if !record.phase.terminal && record.phase != .verifying {
            if manual.localPaused && manual.peerPaused { status = "Paused by you and the other participant" }
            else if manual.localPaused { status = "Paused by you" }
            else if manual.peerPaused { status = "Paused by the other participant" }
            else if manual.acknowledgedLocalRevision != manual.localRevision || manualStopLatches.contains(record.id) {
                status = "Confirming continuation"
            } else if record.queue?.remoteCapacityBlocked == true || record.errorCode == "queued" {
                status = record.outgoing ? "Waiting for a transfer slot on this device" : "Waiting for the other device's transfer slot"
            }
            if manual.syncNeeded && (!(record.signalBudget ?? .unknown()).known || manual.syncLimitReached ||
                (record.signalBudget?.debits ?? 0) >= DirectSignalBudget.optionalLimit) {
                status = "Pause synchronization needs attention. Keep both devices connected or cancel this transfer."
                if (record.signalBudget ?? .unknown()).known { actions.insert(.retrySync) }
            }
        }
        if !record.phase.terminal && record.phase != .verifying &&
            (manual.localPaused || manual.peerPaused || !manual.manualByteEligible || manualStopLatches.contains(record.id)) {
            displayPhase = .paused
        }
        if canAct, let desired = manualPendingIntents[record.id], !record.phase.terminal,
           record.phase != .verifying, !verifyingTransfers.contains(record.id) {
            displayPhase = .paused
            actions.remove(.pause); actions.remove(.resume); actions.remove(.retrySync)
            actions.remove(.retryPauseSave); actions.remove(.retryContinueSave)
            if desired {
                status = manual.peerPaused ? "Saving pause here. The other participant is also paused." : "Stopping on this device. Saving pause..."
            } else {
                status = manual.peerPaused ? "Confirming continuation. The other participant is still paused." : "Confirming continuation"
            }
        }
        if canAct, manualPendingIntents[record.id] == nil,
           let failedPause = manualSaveFailures[record.id], !record.phase.terminal,
           record.phase != .verifying, !verifyingTransfers.contains(record.id) {
            displayPhase = .paused
            actions.remove(.pause); actions.remove(.resume); actions.remove(.retrySync)
            if failedPause {
                status = "Stopped on this device. Could not confirm that the pause was saved. Retry before closing UNYRI."
                actions.insert(.retryPauseSave); actions.insert(.resume)
            } else {
                status = "Could not confirm continuation. The transfer remains stopped."
                actions.insert(.retryContinueSave)
            }
        }
        if record.continuedProcessing?.requiresContinue == true, !record.phase.terminal {
            displayPhase = .paused
            if !hardRecoveryFailure(record) { status = "Background transfer stopped. Tap Continue when ready." }
            actions.remove(.pause); actions.remove(.retry); actions.insert(.resume)
        }
        if admissionBlocked, !record.phase.terminal {
            displayPhase = .paused
            status = "The transfer queue on this device could not be loaded. Existing completed files are retained."
            actions.formIntersection([.cancel, .reject])
        }
        let position: Int?
        if !active, record.queue != nil, !record.phase.terminal, record.outgoing || record.consentGiven {
            position = stateRecords.values.filter {
                $0.id != record.id && $0.outgoing == record.outgoing && !$0.phase.terminal &&
                ($0.queue?.ordinal ?? Int64.max) < (record.queue?.ordinal ?? Int64.max)
            }.count + 1
        } else { position = nil }
        var backgroundStatus: String?
        if let context = ownedActive(record.id), !record.phase.terminal {
            if context.execution.hasGrant {
                backgroundStatus = "Background allowed. iOS may pause this transfer."
            } else {
                backgroundStatus = context.backgroundRequestPending ? "Requesting background execution..." :
                    context.backgroundRequestDenied ? "Background unavailable. Keep UNYRI open." :
                    "Keep UNYRI open to continue this transfer."
                if #available(iOS 26.0, *), current, foreground,
                   UIApplication.shared.applicationState == .active, record.phase == .transferring,
                   manualByteEligible(record.id), !context.execution.wasInterrupted, !context.backgroundRequestPending {
                    actions.insert(.allowBackground)
                }
            }
        }
        return DirectFilePresentation(id: record.id, chatId: record.chatId, filename: record.offer.filename,
            mimeType: record.offer.mimeType, size: record.offer.size, outgoing: record.outgoing,
            phase: displayPhase, status: status, ordinal: record.queue?.ordinal ?? Int64.max,
            queuePosition: position, actions: actions, backgroundStatus: backgroundStatus)
    }
    private func refreshPresentations() {
        let ordered = stateRecords.values.sorted {
            let lhs = $0.queue?.ordinal ?? Int64.max, rhs = $1.queue?.ordinal ?? Int64.max
            return lhs == rhs ? $0.id < $1.id : lhs < rhs
        }
        for record in ordered { rowState(record.id).update(presentation(record)) }
        listState.update(ids: ordered.map(\.id), interrupted: interruptedPreparations)
    }
    func perform(_ action: DirectFileAction, id: String) {
        guard let record = stateRecords[id], presentation(record).actions.contains(action) else { return }
        switch action {
        case .accept: accept(id)
        case .reject: reject(id)
        case .pause: manualPause(id)
        case .resume: manualContinue(id)
        case .allowBackground: allowBackground(id)
        case .cancel: cancel(id)
        case .retry: if record.outgoing { retrySender(id) } else { resume(id) }
        case .retrySync: retryManualSynchronization(id)
        case .retryPauseSave: manualPause(id)
        case .retryContinueSave: manualContinue(id)
        case .restart: restartFromZero(id)
        case .open, .share: break // Validated async file actions retain their own lease.
        }
    }
    func discardInterruptedPreparation(_ id: String) {
        guard interruptedPreparations.contains(where: { $0.id == id }) else { return }
        Task { @MainActor in
            do {
                try await self.disk.discardPreparedSource(id: id)
                try self.requireCurrent()
                self.interruptedPreparations.removeAll { $0.id == id }; self.refreshPresentations()
            } catch { if self.current { self.errorMessage = self.userMessage(error) } }
        }
    }

    private func publishProgress(_ id: String, snapshot: DirectFileProgress, force: Bool = false) {
        // The receiver stream emits these exact authoritative snapshots at
        // <=4Hz. Do not resample/count down independently on the UI actor.
        progress[id] = snapshot
        rowState(id).update(snapshot)
    }

    func fileURL(_ id: String) async -> URL? {
        guard current, let record = stateRecords[id], record.phase == .completed else { return nil }
        return try? await disk.completedURL(record)
    }

    func shareURL(_ id: String) async -> URL? {
        guard current, let record = stateRecords[id], record.phase == .completed else { return nil }
        let url = try? await disk.completedShareURL(record)
        guard current, let latest = stateRecords[id], latest.phase == .completed,
              latest.offer == record.offer else { return nil }
        return url
    }

    private func backgroundExecutionAllowed(_ id: String) -> Bool {
        guard current, let owned = ownedActive(id), owned.execution.hasGrant,
              let record = stateRecords[id], record.attemptId == owned.attempt,
              record.outgoing == owned.outgoing, record.localDeviceId == device,
              record.continuedProcessing?.attempt == owned.attempt,
              record.continuedProcessing?.requiresContinue == false else { return false }
        return true
    }

    private func allowBackground(_ id: String) {
        guard #available(iOS 26.0, *), current, foreground,
              UIApplication.shared.applicationState == .active,
              let context = ownedActive(id), context.execution.allowed,
              !context.execution.hasGrant, !context.execution.wasInterrupted, !context.backgroundRequestPending,
              let record = stateRecords[id], record.phase == .transferring,
              record.attemptId == context.attempt, record.outgoing == context.outgoing,
              manualByteEligible(id) else { return }
        // This button renews only the existing exact live membership. It never
        // queues an intent for another attempt or changes manual holds.
        context.backgroundUserIntentDeadline = ProcessInfo.processInfo.systemUptime + 60
        requestContinuedProcessing(context)
    }

    private func noteBackgroundUserIntent(_ id: String) {
        guard current, foreground, UIApplication.shared.applicationState == .active else { return }
        let now = ProcessInfo.processInfo.systemUptime
        backgroundUserIntents = backgroundUserIntents.filter { $0.value >= now }
        backgroundUserIntents[id] = now + 60
    }

    private func requestContinuedProcessing(_ context: Active) {
        guard current, foreground, ownedActive(context.id) === context,
              let record = stateRecords[context.id], !record.phase.terminal,
              manualByteEligible(context.id), let deadline = context.backgroundUserIntentDeadline,
              deadline >= ProcessInfo.processInfo.systemUptime else { return }
        context.backgroundUserIntentDeadline = nil // a denied request is not automatically retried
        let sceneEpoch = foregroundEpoch
        context.backgroundRequestPending = true; context.backgroundRequestDenied = false
        let submitted = continuedProcessing.request(context.member, permit: context.execution,
            total: record.offer.size, offset: progress[record.id]?.offset ?? record.durableOffset,
            authorize: { [weak self, weak context] identifier in
                guard let self, let context else { return false }
                defer { context.backgroundRequestPending = false; self.refreshPresentations() }
                do {
                    let accepted = try await self.serialized(for: context.id) {
                        guard deadline >= ProcessInfo.processInfo.systemUptime, self.foregroundEpoch == sceneEpoch,
                              self.current, self.foreground, UIApplication.shared.applicationState == .active,
                              self.ownedActive(context.id) === context, context.execution.allowed,
                              self.manualByteEligible(context.id), var fresh = self.stateRecords[context.id],
                              !fresh.phase.terminal, fresh.attemptId == context.attempt,
                              fresh.outgoing == context.outgoing else { return false }
                        fresh.continuedProcessing = .init(task: identifier, attempt: context.attempt, requiresContinue: false)
                        try await self.save(fresh) // no grant reliance before this durable fence
                        guard deadline >= ProcessInfo.processInfo.systemUptime, self.foregroundEpoch == sceneEpoch,
                              self.current, self.foreground, UIApplication.shared.applicationState == .active,
                              self.ownedActive(context.id) === context, context.execution.allowed, self.manualByteEligible(context.id) else { return false }
                        return true
                    }
                    if !accepted, self.stateRecords[context.id]?.continuedProcessing?.task == identifier {
                        await self.backgroundTransferInterrupted(context)
                    }
                    context.backgroundRequestDenied = !accepted
                    return accepted
                } catch {
                    context.backgroundRequestDenied = true
                    context.execution.revoke()
                    await self.backgroundTransferInterrupted(context)
                    return false
                }
            }, interrupted: { [weak self, weak context] in
                guard let self, let context else { return }
                await self.backgroundTransferInterrupted(context)
            })
        if !submitted { context.backgroundRequestPending = false; context.backgroundRequestDenied = true }
        refreshPresentations()
    }

    private func backgroundTransferInterrupted(_ context: Active) async {
        context.execution.revoke()
        guard current else { return }
        // Stop this exact membership before the first await; a newer same-ID
        // native lease is never affected by an old expiration callback.
        let checkpoint = ownedActive(context.id) === context ? stopTransportImmediately(context.id, finishContinued: false) : nil
        try? await serialized(for: context.id) {
            guard var record = self.stateRecords[context.id], !record.phase.terminal,
                  record.attemptId == context.attempt,
                  self.ownedActive(context.id) == nil || self.ownedActive(context.id) === context else { return }
            let hold = record.manualPause ?? DirectManualPauseState()
            if !context.execution.wasInterrupted, record.continuedProcessing?.requiresContinue != true && (hold.localPaused || hold.peerPaused) {
                if let offset = await checkpoint?.value { record.durableOffset = max(record.durableOffset, offset) }
                record.continuedProcessing = nil
                try await self.save(record); return
            }
            self.manualStopLatches.insert(context.id)
            if let offset = await checkpoint?.value { record.durableOffset = max(record.durableOffset, offset) }
            record = self.stateRecords[context.id] ?? record
            guard !record.phase.terminal, record.attemptId == context.attempt else { return }
            record.continuedProcessing = .init(task: record.continuedProcessing?.task ?? context.lease.uuidString.lowercased(),
                attempt: context.attempt, requiresContinue: true)
            if !self.hardRecoveryFailure(record) {
                record.phase = .paused; record.errorCode = "background_interrupted"
            }
            try await self.save(record)
        }
        self.refreshPresentations()
    }

    func suspendForBackground() {
        foregroundEpoch &+= 1
        foreground = false; pollTask?.cancel(); pollTask = nil; pollLease = nil
        backgroundUserIntents = [:]
        for context in allActive { context.backgroundUserIntentDeadline = nil; context.execution.setForeground(false) }
        api.cancelRequests(ownerPrefix: "\(owner):\(generation):")
        cancelQueuedJobs()
        preparationOperations.values.forEach { $0.cancel() }
        preparationTasks.values.forEach { $0.cancel() }
        controlPreparations.values.forEach { $0.cancel() }; controlPreparations = [:]
        let owned = allActive.filter { !backgroundExecutionAllowed($0.id) }
        guard !owned.isEmpty else { return }
        var checkpoints: [String: Task<Int64?, Never>] = [:]
        for context in owned { checkpoints[context.id] = stopTransportImmediately(context.id) }
        if backgroundTask == .invalid {
            apiBackgroundLease = UUID()
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Finish direct file checkpoint") { [weak self] in
                Task { @MainActor in self?.endBackgroundTask() }
            }
        }
        Task { @MainActor in
            defer { self.endBackgroundTask() }
            for context in owned {
                try? await self.serialized(for: context.id) {
                    guard var record = self.stateRecords[context.id], !record.phase.terminal else { return }
                    if let task = checkpoints[context.id], let offset = await task.value {
                        record = self.stateRecords[context.id] ?? record
                        record.durableOffset = max(record.durableOffset, offset)
                    }
                    guard !record.phase.terminal else { return }
                    record.phase = .paused; record.errorCode = "background"
                    record.senderRequest = nil; record.requestStartedAt = nil
                    try await self.save(record)
                    try? await self.queueControl(record, type: .pause, attempt: context.attempt, reason: "background")
                }
            }
        }
    }

    private func endBackgroundTask() {
        if let lease = apiBackgroundLease {
            api.cancelRequests(ownerPrefix: "\(owner):\(generation):bg:\(lease):")
        }
        apiBackgroundLease = nil
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid
    }

    func shutdown() {
        stopped = true; foreground = false; backgroundUserIntents = [:]
        pollTask?.cancel(); pollTask = nil; pollLease = nil
        cadenceDue = [:]
        api.cancelRequests(ownerPrefix: "\(owner):\(generation):")
        cancelQueuedJobs()
        preparationOperations.values.forEach { $0.cancel() }
        preparationTasks.values.forEach { $0.cancel() }
        controlPreparations.values.forEach { $0.cancel() }; controlPreparations = [:]
        let owned = allActive
        outboundActive = nil; inboundActive = nil; roleReservations = [:]
        for context in owned {
            context.execution.revoke(); context.peer.close()
            shutdownDrains.append(Task {
                _ = await context.stream?.stop()
                self.continuedProcessing.finish(context.member, success: false)
            })
        }
        endBackgroundTask(); cancellables.removeAll()
    }

    func drain() async {
        for job in Array(pollJobs.values) { await job.task.value }
        for job in Array(queuedWorkJobs.values) { await job.task.value }
        for job in Array(checkpointJobs.values) { await job.task.value }
        for task in preparationOperations.values { _ = await task.value }
        for task in preparationTasks.values { _ = try? await task.value }
        for task in Array(serialTails.values) { await task.value }
        for stop in Array(stopping.values) { _ = await stop.task.value }
        for task in shutdownDrains { await task.value }
        continuedProcessing.finishAll()
        await disk.retire()
    }

    private static func digestBytes(_ value: String) -> Data {
        let chars = Array(value.utf8)
        guard chars.count == 64 else { return Data() }
        var result = Data()
        for index in stride(from: 0, to: 64, by: 2) {
            guard let byte = UInt8(String(bytes: chars[index...index + 1], encoding: .utf8) ?? "", radix: 16) else { return Data() }
            result.append(byte)
        }
        return result
    }

    private static func makeNonce() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw Failure.invalid }
        return Data(bytes).base64EncodedString()
    }

    private func userMessage(_ error: Error) -> String {
        if case DirectFileDisk.Failure.admissionUnavailable = error { return "The transfer queue on this device could not be loaded. Existing completed files are retained." }
        if case Failure.unsupported = error { return "Update the UNYRI server to use direct file transfers." }
        if case Failure.busy = error { return "The transfer is waiting for an available slot." }
        if case DirectFileAllocationLedger.Failure.full = error { return "Up to eight unfinished transfers per direction are allowed. Finish or cancel one first." }
        if case DirectFileAllocationLedger.Failure.space = error { return "Not enough free space for the original file." }
        if case DirectFileAllocationLedger.Failure.exhausted = error { return "The transfer queue cannot be extended on this device. Cancel an unfinished transfer and try again." }
        if case Failure.callBusy = error { return "You already have a call in progress. Return to it or end it first." }
        if case Failure.claimedElsewhere = error { return "This transfer was accepted on another device." }
        if case Failure.unavailable = error { return "This transfer is no longer available." }
        if case DirectFileDisk.Failure.noSpace = error { return "Not enough free space for the original file." }
        if case Failure.integrity = error { return "The saved file differs. Start a new direct transfer." }
        if case APIError.http(404) = error { return "Direct transfer is unavailable. Update the server or retry after the sender reconnects." }
        return "Could not continue the direct transfer. Keep UNYRI open on both devices and try again."
    }
}
