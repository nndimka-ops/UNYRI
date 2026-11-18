import Foundation
import BackgroundTasks
import Network

/// One account-bound operation context used by background and QR flows. It
/// shares the same lease as manual settings, including account retirement.
@MainActor final class HistoryOperationContext {
    let owner: String
    let remote: HistoryBackupAPI
    let access: HistoryBackupAccess
    let fence: () throws -> Void
    private(set) var native: HistoryNativeOwner?
    private(set) var coordinator: HistoryBackupCoordinator?
    init(auth: AuthRepository, access: HistoryBackupAccess, allowsCellularAccess: Bool = true) throws {
        guard let owner = auth.currentUser?.id else { throw APIError.missingSession }
        let generation = auth.sessionGeneration, base = AppConfig.apiBaseURL
        let fence: () throws -> Void = {
            try Task.checkCancellation(); try access.lease.check()
            guard generation == auth.sessionGeneration, auth.currentUser?.id == owner, AppConfig.apiBaseURL == base else { throw APIError.missingSession }
        }
        self.owner = owner; self.access = access; self.fence = fence
        remote = HistoryBackupAPI(baseURL: base, accessToken: {
            try await MainActor.run { try fence() }
            let value = try await auth.currentAccessToken()
            try await MainActor.run { try fence() }; return value
        }, assertCurrent: { try await MainActor.run { try fence() } }, allowsCellularAccess: allowsCellularAccess)
    }
    func open() async throws -> HistoryBackupState {
        try fence()
        let state = try await remote.status(); try fence()
        try HistoryArchive.validateId(state.serverId)
        if native == nil {
            let local = access.owner(server: state.serverId, remote: remote); native = local
            coordinator = HistoryBackupCoordinator(server: state.serverId, owner: owner, remote: remote, local: local, cipher: .platformCrypto)
        }
        return state
    }
    func cleanup() async { await native?.cleanTemporaryFiles() }
}

@MainActor final class HistoryAutomaticBackup {
    static let shared = HistoryAutomaticBackup()
    static let identifier = (Bundle.main.bundleIdentifier ?? "com.messenger.unyri.X7G7M9PFJ3") + ".history-backup"
    private let monitor = NWPathMonitor()
    private var wifi = false
    private var available = false
    private var pathKnown = false
    private var registered = false
    private var scheduleGeneration = 0
    private var runner: (() async -> Bool)?
    private var binding: UUID?
    private var job: Task<Bool, Never>?
    private var bootstrap: (() async -> Bool)?
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.wifi = path.usesInterfaceType(.wifi); self?.available = path.status == .satisfied; self?.pathKnown = true }
        }
        monitor.start(queue: DispatchQueue(label: "unyri.history-network"))
    }
    func register(bootstrap: @escaping () async -> Bool) {
        self.bootstrap = bootstrap
        guard !registered else { return }; registered = true
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifier, using: nil) { task in
            guard let processing = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
            Task { @MainActor in
                let work = Task { @MainActor in await self.perform() }
                processing.expirationHandler = { work.cancel(); Task { @MainActor in self.job?.cancel() } }
                let success = await work.value
                processing.setTaskCompleted(success: success)
            }
        }
    }
    func attach(_ runner: @escaping () async -> Bool) -> UUID {
        let id = UUID(); binding = id; self.runner = runner; return id
    }
    func detach(_ id: UUID?) {
        guard id == binding else { return }; scheduleGeneration += 1; job?.cancel(); binding = nil; runner = nil
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
    }
    func schedule(enabled: Bool, earliest: Date = Date().addingTimeInterval(86400)) {
        scheduleGeneration += 1; let generation = scheduleGeneration
        guard enabled else {
            job?.cancel(); BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier); return
        }
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
          Task { @MainActor in
            guard generation == self.scheduleGeneration else { return }
            guard !requests.contains(where: { $0.identifier == Self.identifier && ($0.earliestBeginDate ?? .distantPast) <= earliest }) else { return }
            let request = BGProcessingTaskRequest(identifier: Self.identifier)
            request.requiresNetworkConnectivity = true; request.earliestBeginDate = earliest
            try? BGTaskScheduler.shared.submit(request)
          }
        }
    }
    func cancelCurrent() { job?.cancel() }
    func stopCurrent() async { let current = job; current?.cancel(); _ = await current?.value }
    func foreground() { Task { _ = await perform() } }
    private func perform() async -> Bool {
        guard job == nil, !Task.isCancelled else { return false }
        let task = Task { @MainActor in
            if let runner { return await runner() }
            return await bootstrap?() ?? false
        }
        job = task
        let deadline = Task {
            do { try await Task.sleep(nanoseconds: 900_000_000_000) } catch { return }
            task.cancel()
        }
        defer { deadline.cancel(); job = nil }
        return await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
    }
    func run(auth: AuthRepository, access: HistoryBackupAccess, canRun: Bool = true) async -> Bool {
        guard let account = auth.currentUser?.id else { return false }
        let generation = auth.sessionGeneration
        let scopeKey = "history-auto-server:" + AppConfig.apiBaseURL.absoluteString + ":" + account
        var preferences = UserDefaults.standard.string(forKey: scopeKey).map { HistoryAutoPreferences(server: $0, owner: account) }
        defer {
            if let preferences, generation == auth.sessionGeneration, auth.currentUser?.id == account,
               (try? access.lease.checkActive()) != nil {
                schedule(enabled: HistoryAutoPolicy.shouldSchedule(enabled: preferences.enabled, uploadsAllowed: preferences.uploadsAllowed, accountActive: true),
                    earliest: HistoryAutoPolicy.next(lastSuccess: preferences.lastSuccess, now: Date().timeIntervalSince1970))
            }
        }
        guard preferences?.enabled == true, preferences?.uploadsAllowed == true else {
            schedule(enabled: false); return true
        }
        guard canRun else { return true }
        do {
            var context = try HistoryOperationContext(auth: auth, access: access)
            try access.lease.begin(); defer { access.lease.end() }
            let state = try await context.open()
            UserDefaults.standard.set(state.serverId, forKey: scopeKey)
            let currentPreferences = HistoryAutoPreferences(server: state.serverId, owner: context.owner)
            preferences = currentPreferences
            currentPreferences.uploadsAllowed = state.desiredEnabled
            guard currentPreferences.enabled else { return true }
            let preferences = currentPreferences
            for _ in 0..<50 where !pathKnown { try await Task.sleep(nanoseconds: 100_000_000) }
            guard state.desiredEnabled, preferences.due, available, !preferences.wifiOnly || wifi else { return true }
            if preferences.wifiOnly {
                context = try HistoryOperationContext(auth: auth, access: access, allowsCellularAccess: false)
                let restricted = try await context.open()
                guard restricted.serverId == state.serverId, restricted.desiredEnabled else { return false }
            }
            guard let key = try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner).load(),
                  let coordinator = context.coordinator else { return false }
            let runtime = context
            let operation = Task { try await coordinator.backup(recoveryKey: key) }
            let watch = Task { @MainActor in
                while !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
                    guard preferences.enabled, preferences.uploadsAllowed, access.canUseCamera(),
                          self.available, !preferences.wifiOnly || self.wifi,
                          (try? runtime.fence()) != nil else {
                        operation.cancel(); runtime.remote.cancel(); return
                    }
                }
            }
            defer { watch.cancel() }
            do {
                _ = try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
                try context.fence()
                preferences.lastSuccess = Date().timeIntervalSince1970
                await context.cleanup(); return true
            } catch { await context.cleanup(); throw error }
        } catch { return false }
    }
}
