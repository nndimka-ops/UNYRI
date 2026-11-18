import Foundation
import Combine
import UIKit
import UserNotifications
import PushKit

/// Platform entry points and account binding. No message decryption, call
/// reducer, transport keepalive, or permanently running background worker.
@MainActor
final class UnyriPushNotificationCoordinator: NSObject, ObservableObject, @preconcurrency PKPushRegistryDelegate {
    static let shared = UnyriPushNotificationCoordinator()
    @Published private(set) var connection = "waiting"
    private weak var auth: AuthRepository?
    private var registration: UnyriPushRegistration?
    /// The last build failed (protected data unavailable): the next successful one is late.
    private var registrationFailed = false
    private var protectedDataObserver: NSObjectProtocol?
    private var registry: PKPushRegistry?
    private var lastSession: UnyriPushSession?
    private var permissionTask: Task<Void, Never>?
    private var receives: [UUID: Task<Void, Never>] = [:]
    private var deadlines: [UUID: Task<Void, Never>] = [:]
    private var receiveSessions: [UUID: UnyriPushSession] = [:]
    private var receiveTickets: [UUID: UnyriPushCallBook.Ticket] = [:]
    /// A tapped alert of the current account, routed once its session is restored.
    private var pendingTap: UnyriPushPayload?
    var onIncomingCall: ((UnyriPushPayload, UnyriPushCallBook.Ticket) async -> Bool)?
    var onMessageTap: ((UnyriPushPayload, UnyriPushSession) async -> Void)?
    var onForegroundMessage: ((UnyriPushPayload, UnyriPushSession) async -> Void)?

    func bind(auth: AuthRepository) {
        if let previous = self.auth, previous !== auth { retireCurrentSession() }
        self.auth = auth
        registration = nil
        ensureRegistration()
        auth.willChangeSession = { [weak self] in self?.retireCurrentSession() }
        sessionChanged()
    }

    /// The registration reads its ledger from a file that stays unreadable until the first
    /// unlock after a reboot. A process iOS starts in the background before that (a message
    /// wake, a VoIP push) builds it on first use or when protected data becomes available,
    /// instead of living without one: no call could be matched and no token uploaded.
    @discardableResult
    private func ensureRegistration() -> UnyriPushRegistration? {
        if let registration { return registration }
        guard let auth else { return nil }
        let rebuilding = registrationFailed
        do {
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            let ledger = try UnyriPushIntentLedger(file: support.appendingPathComponent("COCOInstallation/push-intents-v1.json"))
            guard let aps = Bundle.main.object(forInfoDictionaryKey: "UNYRI_APS_ENVIRONMENT") as? String,
                  ["development", "production"].contains(aps) else { throw UnyriPushAPIError.invalid }
            let environment = aps == "development" ? "sandbox" : "production"
            guard let topic = Bundle.main.bundleIdentifier, !topic.isEmpty else { throw UnyriPushAPIError.invalid }
            let value = UnyriPushRegistration(ledger: ledger, environment: environment, topic: topic,
                current: { [weak self] in self?.currentSession() }, access: { [weak auth] in
                    guard let auth else { throw APIError.missingSession }
                    return try await auth.currentAccessToken()
                }, register: { put, bearer, session in
                    guard let origin = URL(string: session.scope.origin) else { throw UnyriPushAPIError.invalid }
                    return try await UnyriPushAPI(origin: origin).register(put, access: bearer)
                }, remove: { binding, bearer, session in
                    guard let origin = URL(string: session.scope.origin) else { throw UnyriPushAPIError.invalid }
                    try await UnyriPushAPI(origin: origin).retire(binding, access: bearer)
                }, inactiveAccess: { scope in
                    // Only a signed-in account that is not active, with its own slot and server.
                    try await UnyriAccountManager.shared.inactiveAccessToken(for: scope)
                })
            value.changed = { [weak self, weak value] in
                guard let self, let value, self.registration === value else { return }
                self.connection = value.isRegistered(.apns) ? "ready" : value.issue == nil ? "waiting" : "unavailable"
            }
            registration = value
            registrationFailed = false
            // Tokens that arrived while there was no registration were dropped: ask the OS
            // again (PushKit's own current VoIP token; APNs calls back on registration).
            if rebuilding {
                if let token = registry?.pushToken(for: .voIP) { value.receivedToken(token, provider: .voip) }
                if currentSession() != nil { UIApplication.shared.registerForRemoteNotifications() }
            }
            return value
        } catch {
            registrationFailed = true
            connection = "unavailable"
            return nil
        }
    }

    func start(_ application: UIApplication) {
        _ = UnyriCallKitProvider.shared // Ready before a cold PushKit callback.
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self; registry.desiredPushTypes = [.voIP]
        self.registry = registry
        application.registerForRemoteNotifications()
        if protectedDataObserver == nil {
            protectedDataObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, self.registration == nil else { return }
                    self.sessionChanged()
                }
            }
        }
    }

    func currentSession() -> UnyriPushSession? {
        guard let auth, let user = auth.currentUser, let owner = UUID(uuidString: user.id), let device = auth.currentDeviceId else { return nil }
        let scope = UnyriPushScope(origin: auth.authenticatedServerURL.absoluteString, user: owner, device: device)
        guard scope.valid else { return nil }
        return UnyriPushSession(scope: scope, generation: auth.sessionGeneration)
    }

    func sessionChanged() {
        let next = currentSession()
        if let old = lastSession, old != next { UnyriCallKitProvider.shared.retire(old.scope) }
        lastSession = next
        ensureRegistration()?.updateSession()
        if next != nil {
            registry?.desiredPushTypes = [.voIP]
            UIApplication.shared.registerForRemoteNotifications()
            // Also resumes the alert registration, which waits for these settings.
            Task { await refreshAuthorization(); await requestFirstPermissionIfAppropriate() }
        }
        deliverPendingTap() // A cold launch restores the session after the tap.
    }

    func foreground() async {
        sessionChanged()
        await refreshAuthorization()
        if currentSession() != nil { UIApplication.shared.registerForRemoteNotifications() }
        await requestFirstPermissionIfAppropriate()
        deliverPendingTap()
    }

    func retry() { registration?.retry() }
    func refreshRegistration() {
        guard currentSession() != nil else { return }
        // A real user request asks the OS for current credentials. Never
        // synthesize a token callback from an old cached value.
        registry?.desiredPushTypes = []
        registry?.desiredPushTypes = [.voIP]
        UIApplication.shared.registerForRemoteNotifications()
    }
    func refreshAuthorization() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let preferences = UnyriNotificationPreferencesStore.shared.value
        let allowed = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
        registration?.updatePreferences(UnyriPushPreferences(messageAlertEnabled: preferences.allowsMessages,
            messageSoundEnabled: preferences.allowsMessageSound, permissionAllowed: allowed))
    }

    func requestAuthorization() async {
        if let task = permissionTask { await task.value; return }
        let task = Task { @MainActor [weak self] in
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            guard let self else { return }
            await self.refreshAuthorization()
            UIApplication.shared.registerForRemoteNotifications()
        }
        permissionTask = task; await task.value; permissionTask = nil
    }
    private func requestFirstPermissionIfAppropriate() async {
        guard currentSession() != nil, auth?.needsHistoryChoice == false,
              UIApplication.shared.applicationState == .active, !UnyriCallKitProvider.shared.hasReports else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .notDetermined, currentSession() != nil,
              UIApplication.shared.applicationState == .active, !UnyriCallKitProvider.shared.hasReports else { return }
        await requestAuthorization()
    }

    func receivedAPNs(_ data: Data) { ensureRegistration()?.receivedToken(data, provider: .apns) }
    func failedAPNs() { connection = "unavailable" }

    private func retireCurrentSession() {
        // A missing/restoring session must also revoke an already-received
        // delivery when bootstrap explicitly retires that stored session.
        for (nonce, ticket) in receiveTickets {
            UnyriCallKitProvider.shared.end(ticket, reason: .failed)
            receives[nonce]?.cancel()
        }
        receiveSessions.removeAll()
        pendingTap = nil
        guard let old = lastSession ?? currentSession() else { return }
        // An account switch keeps the leaving account signed in: its alert
        // registration stays (prepareAccountSwitch already withdrew VoIP).
        let switching = auth?.switchingAccount == true
        // Revoke admission synchronously, before auth cleanup or any await.
        if switching { registration?.suspend(scope: old.scope) } else { registration?.retire(scope: old.scope) }
        UnyriCallKitProvider.shared.retire(old.scope)
        lastSession = nil
        // Device-wide credentials stay while another account on this device uses them.
        if !switching && !UnyriAccountManager.shared.hasOtherSignedInAccount {
            registry?.desiredPushTypes = []
            UIApplication.shared.unregisterForRemoteNotifications()
        }
        connection = "waiting"
    }

    /// Before an account switch, while the leaving account is still current.
    /// Bounded: a slow server never holds the switch for long.
    func prepareAccountSwitch() async {
        guard let registration, let scope = currentSession()?.scope else { return }
        // Not cancelled at the deadline: the VoIP DELETE still goes out with the
        // leaving account's own credentials (or stays a pending cleanup that
        // `retry` sends with its session), and a late alert upload keeps its slot.
        let work = Task { @MainActor in
            await registration.retireVoIP(for: scope)
            await registration.settleAlerts()
        }
        let gate = UnyriSwitchGate()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gate.continuation = continuation
            Task { @MainActor in await work.value; gate.open() }
            Task { @MainActor in try? await Task.sleep(nanoseconds: 3_000_000_000); gate.open() }
        }
    }

    func restoreAfterAbortedSwitch() {
        guard currentSession() != nil else { return }
        registration?.restoreVoIP()
    }

    /// A removed inactive account: its pushes stop matching immediately (the
    /// server drops its tokens when the queued logout revokes the session).
    func retireInactive(_ scope: UnyriPushScope) {
        registration?.retire(scope: scope)
        UnyriCallKitProvider.shared.retire(scope)
    }

    /// The signed-in but inactive account that owns this genuine registration.
    func inactiveAccount(for hint: UnyriPushPayload) -> UnyriAccountRecord? {
        UnyriAccountManager.shared.otherAccounts.first { record in
            guard let scope = record.pushScope, scope.user == hint.user, scope.device == hint.device else { return false }
            return ensureRegistration()?.binding(for: hint, origin: scope.origin) != nil
        }
    }

    /// Background wake for an inactive account's message: nothing of the active
    /// account is synced; only the inactive account's unread hint grows.
    func absorbInactiveWake(_ values: [AnyHashable: Any]) -> Bool {
        guard let hint = UnyriPushPayload.parse(values, provider: .apns, now: Self.now), hint.event == "new_message",
              let record = inactiveAccount(for: hint), let message = hint.messageID else { return false }
        UnyriAccountManager.shared.noteInactiveMessage(slot: record.slot, message: message.uuidString.lowercased())
        return true
    }

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }
        ensureRegistration()?.receivedToken(pushCredentials.token, provider: .voip)
    }
    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        registration?.invalidated(.voip)
    }
    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType, completion: @escaping () -> Void) {
        UnyriDiag.push(.voipReceived)
        guard type == .voIP,
              let hint = UnyriPushPayload.reportableVoIP(payload.dictionaryPayload) else {
            UnyriDiag.push(.voipUnmatched, reason: 1)
            completion(); return // Malformed input never manufactures a call UUID.
        }
        let origin = auth?.authenticatedServerURL.absoluteString ?? AppConfig.storageOrigin
        let fresh = UnyriPushPayload.parse(payload.dictionaryPayload, provider: .voip, now: Self.now) != nil
        let owned = fresh ? ensureRegistration()?.binding(for: hint, origin: origin) : nil
        let unverified = UnyriPushBinding(context: UnyriPushContext(scope: UnyriPushScope(origin: origin,
            user: hint.user, device: hint.device), provider: .voip), id: hint.registrationID,
            revision: hint.registrationRevision, active: false)
        let provider = UnyriCallKitProvider.shared
        // The report request is synchronous on the registry's explicit main
        // queue. Only after it is initiated may bootstrap/network work begin.
        guard let ticket = provider.reportPush(hint, binding: owned ?? unverified, completion: completion) else { return }
        // reason: 0 matched, 2 stale push, 3 not this session's registration, 4 too many at once.
        UnyriDiag.push(owned != nil ? .voipReported : .voipUnmatched, reason: owned != nil ? 0 : fresh ? 3 : 2)
        guard owned != nil, receives.count < 8, let onIncomingCall else {
            provider.end(ticket, reason: .failed); provider.recoveryFinished(ticket); return
        }
        guard let generation = auth?.sessionGeneration, let owned else {
            provider.end(ticket, reason: .failed); provider.recoveryFinished(ticket); return
        }
        receiveSessions[ticket.nonce] = UnyriPushSession(scope: owned.context.scope, generation: generation)
        receiveTickets[ticket.nonce] = ticket
        let timeout = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
            guard let self, self.receives[ticket.nonce] != nil else { return }
            provider.end(ticket, reason: .failed)
            self.receives[ticket.nonce]?.cancel()
        }
        deadlines[ticket.nonce] = timeout
        receives[ticket.nonce] = Task { @MainActor [weak self] in
            defer {
                self?.deadlines.removeValue(forKey: ticket.nonce)?.cancel()
                self?.receives[ticket.nonce] = nil
                self?.receiveSessions[ticket.nonce] = nil
                self?.receiveTickets[ticket.nonce] = nil
                provider.recoveryFinished(ticket)
            }
            let handled = await onIncomingCall(hint, ticket)
            if !handled || Task.isCancelled { provider.end(ticket, reason: .failed) }
        }
    }

    func matches(_ hint: UnyriPushPayload) -> Bool {
        guard let current = currentSession(), current.scope.user == hint.user, current.scope.device == hint.device,
              ensureRegistration()?.binding(for: hint, origin: current.scope.origin) != nil else { return false }
        return true
    }
    func matchesReceived(_ hint: UnyriPushPayload, ticket: UnyriPushCallBook.Ticket) -> Bool {
        guard let expected = receiveSessions[ticket.nonce], receiveTickets[ticket.nonce] == ticket,
              currentSession() == expected, expected.scope.user == hint.user, expected.scope.device == hint.device else { return false }
        return !Task.isCancelled
    }
    func presentation(_ values: [AnyHashable: Any]) -> UNNotificationPresentationOptions {
        guard let hint = UnyriPushPayload.parse(values, provider: .apns, now: Self.now) else { return [] }
        guard matches(hint) else {
            // Another signed-in account: generic in-app banner, routed to its owner.
            if hint.event == "new_message", let record = inactiveAccount(for: hint),
               let chat = hint.chatID, let message = hint.messageID {
                UnyriAccountManager.shared.presentInactiveMessage(record, chat: chat.uuidString.lowercased(),
                    message: message.uuidString.lowercased())
                return [.badge]
            }
            return []
        }
        // Calls already have their in-app/CallKit UI. Other security notices stay generic.
        guard hint.event == "new_message" else { return [.banner] }
        guard let session = currentSession(), let chat = hint.chatID, let message = hint.messageID else { return [.badge] }
        _ = ForegroundMessageBannerController.takesAlert(UnyriForegroundPushHint(
            owner: session.scope.user.uuidString.lowercased(), generation: session.generation,
            chatId: chat.uuidString.lowercased(), messageId: message.uuidString.lowercased()))
        if let onForegroundMessage { Task { await onForegroundMessage(hint, session) } }
        return [.badge]
    }

    /// Taps have their own admission (`UnyriPushPayload.tapRoute`): no freshness
    /// or registration revision check, so an alert older than its 24 h expiry or
    /// one sent before a preference change, token rotation or 409 rebase still
    /// opens its chat. It routes only to the signed-in account and device it was
    /// sent to; presentation, background wake and VoIP keep the strict checks.
    func tapped(_ values: [AnyHashable: Any]) {
        guard let hint = UnyriPushPayload.tapRoute(values), let chat = hint.chatID, let message = hint.messageID else { return }
        if let record = UnyriAccountManager.shared.otherAccounts.first(where: { record in
            record.pushScope.map { hint.addressed(to: $0) } ?? false
        }) {
            UnyriAccountManager.shared.openFromNotification(owner: record.userId, chat: chat.uuidString.lowercased(),
                message: message.uuidString.lowercased())
            return
        }
        guard onMessageTap != nil else { return }
        pendingTap = hint
        deliverPendingTap()
    }
    private func deliverPendingTap() {
        guard let hint = pendingTap, UIApplication.shared.applicationState == .active, let onMessageTap,
              let session = currentSession() else { return } // No session yet: kept until it is restored.
        pendingTap = nil
        guard hint.addressed(to: session.scope) else { return }
        Task { @MainActor in await onMessageTap(hint, session) }
    }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Resumes `prepareAccountSwitch` once: work finished or the deadline passed.
@MainActor
private final class UnyriSwitchGate {
    var continuation: CheckedContinuation<Void, Never>?
    func open() { continuation?.resume(); continuation = nil }
}
