import Combine
import Foundation
import CryptoKit
import UIKit
enum ConnectionSyncPhase { case idle, running, failed }

/// Authenticated realtime transport over WebSocket. Mirrors Android's
/// `MessagingSocketClient`: reconnects with exponential backoff (1s, 2s, 4s,
/// ... capped at 30s) on any drop — never a tight infinite loop — and fetches
/// the CURRENT access token fresh on every (re)connect attempt via
/// `AuthRepository.currentAccessToken` (which transparently refreshes).
/// Postgres stays the source of truth: a missed event is always recoverable
/// via `/sync`/`GET /chats/{id}/messages`, so a dropped connection is degraded
/// realtime, not data loss.
@MainActor
final class RealtimeClient: ObservableObject {
    @Published var syncPhase: ConnectionSyncPhase = .idle
    var onRetrySync: (() async -> Void)?
    func retryConnectionSync() async {
        guard !stopped, generation == auth.sessionGeneration else { return }
        if state != .connected { handleDisconnect(); reconnectTask?.cancel(); connect() }
        await onRetrySync?()
    }
    private var applicationProbe: RealtimeApplicationProbe?
    private var applicationDeadlineTask: Task<Void, Never>?

    /// Shares one authenticated PING/PONG flight with the liveness watchdog.
    func measureDisplayPing() async -> Int? {
        guard let requested = beginApplicationProbe() else { return nil }
        for _ in 0..<50 {
            guard !Task.isCancelled, !stopped, state == .connected,
                  generation == auth.sessionGeneration, let current = applicationProbe,
                  current.id == requested.id,
                  current.owns(attempt: connectionAttempt, generation: auth.sessionGeneration) else { return nil }
            if current.completed { return current.measuredMilliseconds }
            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return nil }
        }
        return nil
    }
    private func receiveDisplayPong(_ data: Data) {
        guard !stopped, state == .connecting || state == .connected,
              generation == auth.sessionGeneration,
              var probe = applicationProbe, !probe.completed,
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              value["type"] as? String == "PONG",
              value["request_id"] == nil || value["request_id"] is NSNull || value["request_id"] is String,
              probe.receivePong(requestID: value["request_id"] as? String,
                uptime: ProcessInfo.processInfo.systemUptime, attempt: connectionAttempt,
                generation: auth.sessionGeneration) else { return }
        applicationProbe = probe
        applicationDeadlineTask?.cancel(); applicationDeadlineTask = nil
        if state == .connecting, let socket {
            attempt = 0
            state = .connected
            startPing(socket)
        }
        if let measured = probe.measuredMilliseconds {
            PerformanceRecorder.shared.record(.network, ["phase": 11, "duration_ms": Double(measured)])
        } else {
            PerformanceRecorder.shared.record(.network, ["phase": 12])
        }
    }

    /// Shared by periodic liveness and visible ping requests. Several screens
    /// can await the same pending probe without replacing each other's nonce.
    @discardableResult private func beginApplicationProbe(allowConnecting: Bool = false) -> RealtimeApplicationProbe? {
        guard !stopped, state == .connected || allowConnecting && state == .connecting,
              generation == auth.sessionGeneration,
              let socket else { return nil }
        let now = ProcessInfo.processInfo.systemUptime
        if let current = applicationProbe,
           current.owns(attempt: connectionAttempt, generation: auth.sessionGeneration),
           !current.completed || now - current.startedAt < 1 { return current }
        let probe = RealtimeApplicationProbe(id: UUID().uuidString, attempt: connectionAttempt,
            generation: generation, startedAt: now)
        applicationProbe = probe
        scheduleApplicationDeadline(socket: socket, probe: probe, delay: RealtimeApplicationProbe.timeout)
        PerformanceRecorder.shared.record(.network, ["phase": 10])
        sendRaw("{\"type\":\"PING\",\"request_id\":\"\(probe.id)\"}")
        return probe
    }

    private func scheduleApplicationDeadline(socket: URLSessionWebSocketTask,
                                             probe: RealtimeApplicationProbe, delay: TimeInterval) {
        applicationDeadlineTask?.cancel()
        applicationDeadlineTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(max(0.001, delay) * 1_000_000_000)) } catch { return }
            guard let self, !Task.isCancelled, !self.stopped, self.socket === socket,
                  self.state == .connected || self.state == .connecting,
                  self.generation == self.auth.sessionGeneration,
                  let current = self.applicationProbe, current.id == probe.id else { return }
            switch current.deadline(uptime: ProcessInfo.processInfo.systemUptime,
                                    attempt: self.connectionAttempt, generation: self.auth.sessionGeneration) {
            case .ignore: break
            case .wait(let remaining):
                self.scheduleApplicationDeadline(socket: socket, probe: current, delay: remaining)
            case .retryFresh:
                PerformanceRecorder.shared.record(.network, ["phase": 14])
                self.applicationProbe = nil
                self.beginApplicationProbe(allowConnecting: self.state == .connecting)
            case .disconnect:
                PerformanceRecorder.shared.record(.network, ["phase": 13])
                self.handleDisconnect()
            }
        }
    }

    private func resetApplicationProbe() {
        applicationDeadlineTask?.cancel(); applicationDeadlineTask = nil
        applicationProbe = nil
    }
    enum State: Equatable {
        case disconnected
        case connecting
        case connected
    }

    @Published private(set) var state: State = .disconnected {
        didSet {
            if state != oldValue {
                let phase: Double = state == .connected ? 2 : state == .connecting ? 1 : 0
                PerformanceRecorder.shared.record(.network, ["phase": phase])
            }
            if state != .connected {
                liveDrafts.transportDisconnected()
                clearPresence()
                presenceHeartbeat?.cancel(); presenceHeartbeat = nil
            } else if oldValue != .connected { beginPresenceHeartbeat() }
        }
    }
    @Published private(set) var presence: [String: PresenceObservation] = [:]
    private var presenceHistory = PresenceHistory()
    private var presenceActive = false
    private var presenceOwners: [String: (priority: Int, owner: String, ids: [String])] = [:]
    private var presenceHandoff = PresenceOwnerHandoff()
    private var presenceReleaseTask: Task<Void, Never>?
    private var presenceReleaseToken: UUID?
    private var presenceHeartbeat: Task<Void, Never>?
    private var presenceExpiry: Task<Void, Never>?
    private var presenceObservedAt: Date?
    private var presenceLifecycle = Set<AnyCancellable>()
    let events = PassthroughSubject<RealtimeEvent, Never>()
    /// Call signaling frames multiplexed over the SAME socket (never a second
    /// connection — Android's CallSignalingClientImpl pattern).
    let callEvents = PassthroughSubject<CallSignalingEvent, Never>()
    let callHistoryEvents = PassthroughSubject<Void, Never>()
    let privacyEvents = PassthroughSubject<Void, Never>()
    let accountAutoDeleteEvents = PassthroughSubject<Void, Never>()
    private func receivePrivacy(_ data: Data) {
        guard generation == auth.sessionGeneration,
              let frame = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = frame["type"] as? String else { return }
        if type == "privacy.changed" { privacyEvents.send(()) }
        else if type == "auto_delete.changed" { accountAutoDeleteEvents.send(()) }
    }
    let appearanceEvents = PassthroughSubject<Void, Never>()
    let profileEvents = PassthroughSubject<SelfProfileInvalidationHint, Never>()
    private func receiveProfileChange(_ data: Data) {
        guard generation == auth.sessionGeneration,
              let hint = SelfProfileInvalidationHint.parse(data),
              hint.userID == auth.currentUser?.id.lowercased() else { return }
        profileEvents.send(hint)
    }
    private func receiveAppearance(_ data: Data) {
        guard generation == auth.sessionGeneration,
              let frame = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              frame["type"] as? String == "appearance.changed" else { return }
        appearanceEvents.send(())
    }
    private func receiveCallHistory(_ data: Data) {
        guard let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["type"] as? String == "call.history_changed" else { return }
        callHistoryEvents.send(())
    }

    let liveDrafts = LiveDrafts()
    private let wsURL: URL
    private let auth: AuthRepository
    private let session: URLSession
    private var socket: URLSessionWebSocketTask?
    private var connectionAttempt = UUID()
    private var reconnectTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var attempt = 0
    private var stopped = true
    private let generation: UInt64

    init(wsURL: URL, auth: AuthRepository) {
        self.wsURL = wsURL
        self.auth = auth
        self.generation = auth.sessionGeneration
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        let delegate = RealtimeWebSocketOpenDelegate()
        self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        delegate.owner = self
        NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification).sink { [weak self] _ in
            Task { @MainActor in self?.setPresenceActive(false) }
        }.store(in: &presenceLifecycle)
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification).sink { [weak self] _ in
            Task { @MainActor in self?.setPresenceActive(true) }
        }.store(in: &presenceLifecycle)
    }

    // The account can stop/start this transport, so invalidate only when its
    // owner is released. Foundation retains a custom session's delegate until
    // invalidation; the bridge's weak owner does not release that session.
    deinit { session.invalidateAndCancel() }

    func setPresenceActive(_ active: Bool) {
        guard generation == auth.sessionGeneration else {
            presenceActive = false; presenceHeartbeat?.cancel(); presenceHeartbeat = nil; clearPresence(resetWatermark: true); return
        }
        if presenceActive == active {
            if active && presenceHeartbeat == nil { beginPresenceHeartbeat() }
            return
        }
        presenceActive = active
        presenceHeartbeat?.cancel(); presenceHeartbeat = nil
        if !active {
            if state == .connected { emitPresence(["type": "presence.heartbeat", "active": false]) }
            clearPresence()
        } else { beginPresenceHeartbeat() }
    }

    func subscribePresence(owner: String, userIDs: [String], priority: Int = 0) {
        guard generation == auth.sessionGeneration else { return }
        let previous = presenceHandoff.effectiveIDs
        // A late main-queue task must not keep an expired handoff observable.
        let currentRequested = Set(presenceSubscriptionIDs)
        presence = presence.filter { currentRequested.contains($0.key) }
        let ids = PresencePolicy.subscriptions([(priority, owner, userIDs)])
        if ids.isEmpty { presenceOwners[owner] = nil }
        else { presenceOwners[owner] = (priority, owner, ids) }
        let token = presenceHandoff.update(desired: PresencePolicy.subscriptions(Array(presenceOwners.values)),
            deferRemovals: presenceActive && state == .connected && !stopped,
            uptime: ProcessInfo.processInfo.systemUptime)
        if let token, let deadline = presenceHandoff.releaseDeadline {
            schedulePresenceRelease(token: token, deadline: deadline)
        } else {
            presenceReleaseTask?.cancel(); presenceReleaseTask = nil; presenceReleaseToken = nil
        }
        let requested = Set(presenceSubscriptionIDs)
        presence = presence.filter { requested.contains($0.key) }
        if presenceActive && state == .connected,
           previous != presenceSubscriptionIDs || (token == nil && !ids.isEmpty) { emitPresenceSubscription() }
    }

    private func schedulePresenceRelease(token: UUID, deadline: TimeInterval) {
        guard presenceReleaseToken != token else { return }
        presenceReleaseTask?.cancel(); presenceReleaseToken = token
        let epoch = generation
        let connection = connectionAttempt
        presenceReleaseTask = Task { @MainActor [weak self] in
            let remaining = max(0, deadline - ProcessInfo.processInfo.systemUptime)
            do { try await Task.sleep(nanoseconds: UInt64((remaining * 1_000_000_000).rounded(.up))) }
            catch { return }
            guard let self, !Task.isCancelled, self.generation == epoch,
                  epoch == self.auth.sessionGeneration, self.connectionAttempt == connection,
                  self.presenceActive, self.state == .connected, !self.stopped,
                  self.presenceReleaseToken == token, self.presenceHandoff.release(token) else { return }
            self.presenceReleaseTask = nil; self.presenceReleaseToken = nil
            let requested = Set(self.presenceSubscriptionIDs)
            self.presence = self.presence.filter { requested.contains($0.key) }
            self.emitPresenceSubscription()
        }
    }

    func unsubscribePresence(owner: String) { subscribePresence(owner: owner, userIDs: []) }
    private var presenceSubscriptionIDs: [String] {
        presenceHandoff.currentIDs(uptime: ProcessInfo.processInfo.systemUptime)
    }

    func presenceObservation(for userID: String) -> PresenceObservation? {
        guard presenceActive, state == .connected, generation == auth.sessionGeneration,
              let id = UUID(uuidString: userID)?.uuidString.lowercased(), presenceSubscriptionIDs.contains(id),
              let value = presence[id],
              PresencePolicy.fresh(value, uptime: ProcessInfo.processInfo.systemUptime) else { return nil }
        return value
    }

    func presenceLabel(for userID: String) -> String? {
        guard generation == auth.sessionGeneration else { return nil }
        let live = presenceObservation(for: userID)
        if live?.item.online == true { return UnyriL10n.string("online") }
        guard let date = live?.item.lastSeenAt.flatMap(PresencePolicy.timestamp) ?? presenceHistory.date(for: userID) else {
            return live == nil ? nil : UnyriL10n.string("Offline")
        }
        let label = PresencePolicy.lastSeenLabel(date, now: Date(), locale: Locale(identifier: UnyriL10n.language), calendar: .autoupdatingCurrent)
        return String(format: UnyriL10n.string(label.key), label.value)
    }

    private func emitPresence(_ value: [String: Any]) {
        guard !stopped, state == .connected, generation == auth.sessionGeneration,
              let data = try? JSONSerialization.data(withJSONObject: value), let text = String(data: data, encoding: .utf8) else { return }
        sendRaw(text)
    }

    private func emitPresenceSubscription() {
        emitPresence(["type": "presence.subscribe", "user_ids": presenceSubscriptionIDs])
    }

    private func beginPresenceHeartbeat() {
        guard presenceActive, !stopped, state == .connected, generation == auth.sessionGeneration else { return }
        presenceHeartbeat?.cancel()
        emitPresence(["type": "presence.heartbeat", "active": true])
        emitPresenceSubscription()
        presenceHeartbeat = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 2_000_000_000) } catch { return }
                guard let self, self.presenceActive, !self.stopped, self.state == .connected,
                      self.generation == self.auth.sessionGeneration else { return }
                self.emitPresence(["type": "presence.heartbeat", "active": true])
            }
        }
    }

    private func clearPresence(resetWatermark: Bool = false) {
        presenceReleaseTask?.cancel(); presenceReleaseTask = nil; presenceReleaseToken = nil
        if resetWatermark { presenceOwners.removeAll() }
        presenceHandoff.reset(to: PresencePolicy.subscriptions(Array(presenceOwners.values)))
        presenceExpiry?.cancel(); presenceExpiry = nil
        presence.removeAll()
        // A foreground transition must not accept a duplicate pre-background
        // observation as a new lease, including after transport reconnect.
        // Only a replaced account/server identity resets this watermark.
        if resetWatermark { presenceObservedAt = nil; presenceHistory.clear() }
    }

    private func receivePresence(_ data: Data) {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard presenceActive, generation == auth.sessionGeneration,
              let frame = try? decoder.decode(PresenceSnapshot.self, from: data),
              let update = PresencePolicy.snapshot(frame, requestedIDs: presenceSubscriptionIDs, receivedAt: Date(),
                    uptime: ProcessInfo.processInfo.systemUptime, newerThan: presenceObservedAt) else { return }
        presenceObservedAt = update.observedAt
        presenceHistory.apply(update.entries, requestedIDs: presenceSubscriptionIDs)
        // Full union snapshots: omitted blocked/unknown users become unknown.
        presence = update.entries
        presenceExpiry?.cancel()
        presenceExpiry = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(update.lifetime * 1_000_000_000)) } catch { return }
            guard let self else { return }
            self.presence = self.presence.filter { PresencePolicy.fresh($0.value, uptime: ProcessInfo.processInfo.systemUptime) }
        }
    }

    @Published private(set) var activity: [String: (kind: String, at: Date)] = [:]
    private var lastActivityAt = Date.distantPast
    func sendActivity(chatId: String, kind: String) {
        guard kind == "idle" || UserDefaults.standard.bool(forKey: "chatActivityEnabled") else { return }
        guard state == .connected, kind == "idle" || Date().timeIntervalSince(lastActivityAt) >= 2.5 else { return }
        lastActivityAt = Date()
        if let data = try? JSONSerialization.data(withJSONObject: ["type": "chat.activity", "chat_id": chatId, "activity": kind]), let text = String(data: data, encoding: .utf8) { sendRaw(text) }
    }
    private func receiveActivity(_ data: Data) {
        guard let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], value["type"] as? String == "chat.activity",
              let chat = value["chat_id"] as? String, let kind = value["activity"] as? String, ["typing", "recording", "uploading", "idle"].contains(kind) else { return }
        activity = activity.filter { Date().timeIntervalSince($0.value.at) < 8 }
        if activity.count < 128 { activity[chat] = (kind, Date()) }
    }
    func start() {
        stopped = false
        if state == .connecting || state == .connected { return }
        attempt = 0
        connect()
    }

    func stop() {
        let wasConnected = state == .connected
        // Background suspension keeps last-confirmed timestamps, but account
        // teardown must erase them even if an old owner is retained briefly.
        if generation != auth.sessionGeneration { clearPresence(resetWatermark: true) }
        presenceActive = false
        presenceHeartbeat?.cancel(); presenceHeartbeat = nil
        connectionAttempt = UUID()
        resetApplicationProbe()
        stopped = true
        liveDrafts.close()
        activity.removeAll()
        reconnectTask?.cancel()
        reconnectTask = nil
        pingTask?.cancel()
        pingTask = nil
        if wasConnected, let retiringSocket = socket {
            // Complete the best-effort inactive frame before retiring this
            // socket. The completion can never close a successor connection.
            retiringSocket.send(.string("{\"type\":\"presence.heartbeat\",\"active\":false}")) { _ in
                retiringSocket.cancel(with: .normalClosure, reason: nil)
            }
        } else { socket?.cancel(with: .normalClosure, reason: nil) }
        socket = nil
        state = .disconnected
    }

    /// Sends a pre-serialized JSON frame (call signaling will reuse the one
    /// real socket, same as Android). No-op when there is no live connection;
    /// the caller decides retry policy.
    func sendRaw(_ payload: String) {
        guard !stopped, let socket else { return }
        socket.send(.string(payload)) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in
                // A failed write is transport failure, not a successful call/draft send.
                // Late completions from a replaced connection must not tear down its successor.
                guard let self, !self.stopped, self.socket === socket else { return }
                self.handleDisconnect()
            }
        }
    }

    // MARK: - Connection

    private func connect() {
        guard !stopped else { return }
        resetApplicationProbe()
        reconnectTask?.cancel()
        reconnectTask = nil
        state = .connecting
        let ticket = UUID()
        connectionAttempt = ticket

        Task { @MainActor [self] in
            guard !stopped, connectionAttempt == ticket, generation == auth.sessionGeneration else { return }
            let token: String
            do {
                token = try await auth.currentAccessToken()
            } catch {
                guard !stopped, connectionAttempt == ticket else { return }
                state = .disconnected
                scheduleReconnect()
                return
            }
            guard !stopped, connectionAttempt == ticket, generation == auth.sessionGeneration else { return }

            // Drop any previous socket before opening a new one (defensive —
            // start() guards against double-connect, but reconnect races can
            // still leave a half-open task behind).
            socket?.cancel(with: .normalClosure, reason: nil)
            var request = URLRequest(url: wsURL)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let socket = session.webSocketTask(with: request)
            self.socket = socket
            socket.resume()

            receiveLoop(socket)
            // didOpen starts the authenticated JSON probe. A write completion
            // or an unrelated frame cannot declare the connection ready.
        }
    }

    /// URLSession's transport-open event is owned by this exact task. Readiness
    /// still requires its JSON PONG, using the existing legacy-compatible probe.
    fileprivate func socketOpened(_ socket: URLSessionWebSocketTask) {
        guard !stopped, self.socket === socket, state == .connecting,
              generation == auth.sessionGeneration else { return }
        beginApplicationProbe(allowConnecting: true)
    }

    private func scheduleReconnect() {
        guard !stopped else { return }
        let shift = min(attempt, Self.maxBackoffShift)
        let delayMs = min(Self.maxDelayMs, Self.baseDelayMs << shift)
        attempt += 1
        reconnectTask?.cancel()
        reconnectTask = Task { @MainActor [self] in
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            guard !Task.isCancelled else { return }
            connect()
        }
    }

    // MARK: - Receive loop

    private func receiveLoop(_ socket: URLSessionWebSocketTask) {
        socket.receive { [weak self] result in
            Task { @MainActor in
                guard let self, !self.stopped, self.socket === socket else { return }
                switch result {
                case .success(let message):
                    if self.state == .connected { self.attempt = 0 }
                    switch message {
                    case .string(let text):
                        self.receivePrivacy(Data(text.utf8))
                        self.receiveDisplayPong(Data(text.utf8))
                        self.receiveActivity(Data(text.utf8))
                        self.receivePresence(Data(text.utf8))
                        self.receiveCallHistory(Data(text.utf8))
                        if text.contains("appearance.changed") { self.receiveAppearance(Data(text.utf8)) }
                        if text.contains("profile.changed") { self.receiveProfileChange(Data(text.utf8)) }
                        self.liveDrafts.receive(Data(text.utf8))
                        if let event = RealtimeEventParser.parse(Data(text.utf8)) {
                            self.events.send(event)
                        }
                        if let callEvent = CallSignalingEventParser.parse(Data(text.utf8)) {
                            self.callEvents.send(callEvent)
                        }
                    case .data(let data):
                        self.receivePrivacy(data)
                        self.receiveDisplayPong(data)
                        self.receiveActivity(data)
                        self.receivePresence(data)
                        self.receiveCallHistory(data)
                        self.receiveAppearance(data)
                        self.receiveProfileChange(data)
                        self.liveDrafts.receive(data)
                        if let event = RealtimeEventParser.parse(data) {
                            self.events.send(event)
                        }
                        if let callEvent = CallSignalingEventParser.parse(data) {
                            self.callEvents.send(callEvent)
                        }
                    @unknown default:
                        break
                    }
                    self.receiveLoop(socket)
                case .failure:
                    self.handleDisconnect()
                }
            }
        }
    }

    private func startPing(_ socket: URLSessionWebSocketTask) {
        pingTask?.cancel()
        pingTask = Task { @MainActor [self] in
            while !Task.isCancelled && !stopped {
                try? await Task.sleep(nanoseconds: Self.pingIntervalNs)
                guard !Task.isCancelled, !stopped else { return }
                guard self.socket === socket, state == .connected,
                      generation == auth.sessionGeneration else { return }
                beginApplicationProbe()
            }
        }
    }

    private func handleDisconnect() {
        resetApplicationProbe()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        pingTask?.cancel()
        pingTask = nil
        state = .disconnected
        scheduleReconnect()
    }

    // MARK: - Constants

    private static let baseDelayMs = 1_000
    private static let maxDelayMs = 30_000
    private static let maxBackoffShift = 5
    private static let pingIntervalNs: UInt64 = 20_000_000_000
}


/// Keep Foundation's transport-open callback separate from application readiness.
/// The session retains this bridge; it never retains its RealtimeClient owner.
@MainActor
private final class RealtimeWebSocketOpenDelegate: NSObject, URLSessionWebSocketDelegate {
    weak var owner: RealtimeClient?

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                               didOpenWithProtocol _: String?) {
        Task { @MainActor [weak self] in
            self?.owner?.socketOpened(webSocketTask)
        }
    }
}

/// Ephemeral authenticated snapshots; plaintext and stream keys live only in RAM.
@MainActor
final class LiveDrafts: ObservableObject {
    @Published private(set) var peerText = ""
    private var signal: SignalSessionManager?
    private var send: ((String) -> Void)?
    private var chat: String?
    private var peer: String?
    private var generation = 0
    private var stream = ""
    private var key: SymmetricKey?
    private var envelopes: [[String: Any]] = []
    private var revision: UInt64 = 0
    private var busy = false
    private var receiving = false
    private var desiredText = ""
    private var sentAt = Date.distantPast
    private var receivedAt = Date.distantPast
    private var expiry: Task<Void, Never>?
    private var latestTimestamp: [String: Double] = [:]
    private var incoming: [String: (key: SymmetricKey, revision: UInt64)] = [:]
    func configure(signal: SignalSessionManager, send: @escaping (String) -> Void) { self.signal = signal; self.send = send }
    func open(chat: String, peer: String) {
        if self.chat == chat && self.peer == peer { return }
        close(); self.chat = chat; self.peer = peer
    }
    func close(chat expected: String? = nil) {
        if let expected, chat != expected { return }
        // Clear using the old stream before changing its context.
        if key != nil { emit("") }
        generation += 1; desiredText = ""; chat = nil; peer = nil; key = nil; envelopes = []; peerText = ""
        expiry?.cancel(); expiry = nil
    }
    func transportDisconnected() {
        generation += 1
        key = nil; envelopes = []; desiredText = ""; peerText = ""; sentAt = .distantPast
        expiry?.cancel(); expiry = nil
    }
    func update(_ text: String) {
        let enabled = UserDefaults.standard.bool(forKey: "liveDraftEnabled")
        desiredText = enabled ? String(text.prefix(2000)) : ""
        let snapshot = desiredText
        guard let chat, let peer, let signal, !busy, Date().timeIntervalSince(sentAt) >= 1 else { return }
        guard !snapshot.isEmpty || key != nil else { return }
        sentAt = Date(); busy = true
        let ticket = generation
        Task { @MainActor in
            defer { busy = false }
            do {
                if key == nil {
                    let fresh = SymmetricKey(size: .bits256)
                    let id = UUID().uuidString.lowercased()
                    let wrapped = try JSONSerialization.data(withJSONObject: ["purpose": "coco.live-draft.v1", "chat": chat, "stream": id,
                        "key": fresh.withUnsafeBytes { Data($0).base64EncodedString() }])
                    let recipients = try await signal.ensureSessions(for: peer)
                    guard ticket == generation, UserDefaults.standard.bool(forKey: "liveDraftEnabled") else { return }
                    let encrypted = try signal.encrypt(plaintext: wrapped, recipients: recipients)
                    key = fresh; stream = id; revision = 0
                    envelopes = encrypted.map { ["recipient_device_id": $0.recipientDeviceId, "message_type": $0.messageType, "ciphertext": $0.ciphertext] }
                }
                guard ticket == generation else { return }
                emit(UserDefaults.standard.bool(forKey: "liveDraftEnabled") ? desiredText : "")
            } catch { /* No retry queue, UI error or plaintext diagnostics. */ }
        }
    }
    private func emit(_ text: String) {
        guard let chat, let key else { return }
        do {
            revision += 1
            let aad = Data("\(chat)|\(stream)|\(revision)".utf8)
            let body = try JSONSerialization.data(withJSONObject: ["text": text, "at": Int64(Date().timeIntervalSince1970 * 1000)])
            guard body.count <= 12000 else { return }
            let sealed = try AES.GCM.seal(body, using: key, authenticating: aad)
            guard let combined = sealed.combined else { return }
            let wire = try JSONSerialization.data(withJSONObject: ["type": "chat.draft", "chat_id": chat, "stream": stream, "revision": revision,
                "payload": combined.base64EncodedString(), "envelopes": envelopes])
            if let value = String(data: wire, encoding: .utf8) { send?(value) }
        } catch { }
    }
    func receive(_ data: Data) {
        guard data.count <= 32768, !receiving, let chat, let peer, let signal,
              let frame = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              frame["type"] as? String == "chat.draft", frame["chat_id"] as? String == chat, frame["user_id"] as? String == peer,
              let device = frame["device_id"] as? String, let stream = frame["stream"] as? String,
              let revision = (frame["revision"] as? NSNumber)?.uint64Value,
              let payload = frame["payload"] as? String, let sealed = Data(base64Encoded: payload), sealed.count >= 28,
              let envelope = frame["envelope"] as? [String: Any] else { return }
        let cacheID = "\(peer)|\(device)|\(stream)"
        let ticket = generation
        receiving = true
        Task { @MainActor in
            defer { receiving = false }
            do {
                var entry = incoming[cacheID]
                if entry == nil {
                    guard let ciphertext = envelope["ciphertext"] as? String, let messageType = envelope["message_type"] as? Int else { return }
                    let deviceID = try await signal.resolveSignalDeviceId(senderId: peer, senderDeviceId: device)
                    guard ticket == generation else { return }
                    let plaintext = try signal.decrypt(ciphertextBase64: ciphertext, messageType: messageType, senderUserId: peer, senderSignalDeviceId: deviceID)
                    guard let wrapped = (try JSONSerialization.jsonObject(with: plaintext)) as? [String: Any],
                          wrapped["purpose"] as? String == "coco.live-draft.v1", wrapped["chat"] as? String == chat, wrapped["stream"] as? String == stream,
                          let base64 = wrapped["key"] as? String, let bytes = Data(base64Encoded: base64), bytes.count == 32 else { return }
                    entry = (SymmetricKey(data: bytes), 0)
                    if incoming.count >= 32, let first = incoming.keys.first { incoming.removeValue(forKey: first) }
                }
                guard let current = entry, revision > current.revision else { return }
                let box = try AES.GCM.SealedBox(combined: sealed)
                let plaintext = try AES.GCM.open(box, using: current.key, authenticating: Data("\(chat)|\(stream)|\(revision)".utf8))
                guard let body = (try JSONSerialization.jsonObject(with: plaintext)) as? [String: Any], let text = body["text"] as? String,
                      let timestamp = body["at"] as? NSNumber, abs(Date().timeIntervalSince1970 * 1000 - timestamp.doubleValue) <= 15000 else { return }
                incoming[cacheID] = (current.key, revision)
                guard ticket == generation else { return }
                let displayKey = "\(chat)|\(peer)"
                guard timestamp.doubleValue >= (latestTimestamp[displayKey] ?? 0) else { return }
                if latestTimestamp.count >= 32, let first = latestTimestamp.keys.first { latestTimestamp.removeValue(forKey: first) }
                latestTimestamp[displayKey] = timestamp.doubleValue
                peerText = String(text.prefix(2000)); receivedAt = Date()
                expiry?.cancel()
                expiry = Task { @MainActor [weak self] in
                    do { try await Task.sleep(nanoseconds: 8_000_000_000) } catch { return }
                    self?.peerText = ""
                }
            } catch { }
        }
    }
}
