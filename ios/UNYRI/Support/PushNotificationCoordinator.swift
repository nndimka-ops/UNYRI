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
    private var registry: PKPushRegistry?
    private var lastSession: UnyriPushSession?
    private var permissionTask: Task<Void, Never>?
    private var receives: [UUID: Task<Void, Never>] = [:]
    private var deadlines: [UUID: Task<Void, Never>] = [:]
    private var receiveSessions: [UUID: UnyriPushSession] = [:]
    private var receiveTickets: [UUID: UnyriPushCallBook.Ticket] = [:]
    private var pendingTap: (payload: UnyriPushPayload, session: UnyriPushSession)?
    var onIncomingCall: ((UnyriPushPayload, UnyriPushCallBook.Ticket) async -> Bool)?
    var onMessageTap: ((UnyriPushPayload, UnyriPushSession) async -> Void)?

    func bind(auth: AuthRepository) {
        if let previous = self.auth, previous !== auth { retireCurrentSession() }
        self.auth = auth
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
                })
            value.changed = { [weak self, weak value] in
                guard let self, let value, self.registration === value else { return }
                self.connection = value.isRegistered(.apns) ? "ready" : value.issue == nil ? "waiting" : "unavailable"
            }
            registration = value
        } catch { connection = "unavailable" }
        auth.willChangeSession = { [weak self] in self?.retireCurrentSession() }
        sessionChanged()
    }

    func start(_ application: UIApplication) {
        _ = UnyriCallKitProvider.shared // Ready before a cold PushKit callback.
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self; registry.desiredPushTypes = [.voIP]
        self.registry = registry
        application.registerForRemoteNotifications()
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
        registration?.updateSession()
        if next != nil {
            registry?.desiredPushTypes = [.voIP]
            UIApplication.shared.registerForRemoteNotifications()
            Task { await refreshAuthorization(); await requestFirstPermissionIfAppropriate() }
        }
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

    func receivedAPNs(_ data: Data) { registration?.receivedToken(data, provider: .apns) }
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
        // Revoke admission synchronously, before auth cleanup or any await.
        registration?.retire(scope: old.scope)
        UnyriCallKitProvider.shared.retire(old.scope)
        lastSession = nil
        registry?.desiredPushTypes = []
        UIApplication.shared.unregisterForRemoteNotifications()
        connection = "waiting"
    }

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }
        registration?.receivedToken(pushCredentials.token, provider: .voip)
    }
    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        registration?.invalidated(.voip)
    }
    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType, completion: @escaping () -> Void) {
        guard type == .voIP,
              let hint = UnyriPushPayload.reportableVoIP(payload.dictionaryPayload) else {
            completion(); return // Malformed input never manufactures a call UUID.
        }
        let origin = auth?.authenticatedServerURL.absoluteString ?? AppConfig.storageOrigin
        let fresh = UnyriPushPayload.parse(payload.dictionaryPayload, provider: .voip, now: Self.now) != nil
        let owned = fresh ? registration?.binding(for: hint, origin: origin) : nil
        let unverified = UnyriPushBinding(context: UnyriPushContext(scope: UnyriPushScope(origin: origin,
            user: hint.user, device: hint.device), provider: .voip), id: hint.registrationID,
            revision: hint.registrationRevision, active: false)
        let provider = UnyriCallKitProvider.shared
        // The report request is synchronous on the registry's explicit main
        // queue. Only after it is initiated may bootstrap/network work begin.
        guard let ticket = provider.reportPush(hint, binding: owned ?? unverified, completion: completion) else { return }
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
              registration?.binding(for: hint, origin: current.scope.origin) != nil else { return false }
        return true
    }
    func matchesReceived(_ hint: UnyriPushPayload, ticket: UnyriPushCallBook.Ticket) -> Bool {
        guard let expected = receiveSessions[ticket.nonce], receiveTickets[ticket.nonce] == ticket,
              currentSession() == expected, expected.scope.user == hint.user, expected.scope.device == hint.device else { return false }
        return !Task.isCancelled
    }
    func presentation(_ values: [AnyHashable: Any]) -> UNNotificationPresentationOptions {
        guard let hint = UnyriPushPayload.parse(values, provider: .apns, now: Self.now), matches(hint) else { return [] }
        // Existing foreground ingestion owns message banners, grouping and
        // sound. Security/admin notifications remain generic and silent.
        return hint.event == "new_message" ? [] : [.banner]
    }
    func tapped(_ values: [AnyHashable: Any]) {
        guard let hint = UnyriPushPayload.parse(values, provider: .apns, now: Self.now), hint.event == "new_message",
              let auth, let binding = registration?.binding(for: hint, origin: auth.authenticatedServerURL.absoluteString),
              onMessageTap != nil else { return }
        pendingTap = (hint, UnyriPushSession(scope: binding.context.scope, generation: auth.sessionGeneration))
        deliverPendingTap()
    }
    private func deliverPendingTap() {
        guard UIApplication.shared.applicationState == .active, let value = pendingTap, let onMessageTap else { return }
        pendingTap = nil
        Task { @MainActor in await onMessageTap(value.payload, value.session) }
    }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
