import SwiftUI
import Combine
import LocalAuthentication
import UIKit

@MainActor
final class UserPrivacyRepository: ObservableObject {
    @Published private(set) var value: UserPrivacy?
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let auth: AuthRepository
    private let generation: UInt64
    private let owner: String
    private let server: URL
    private var ticket: UInt64 = 0
    private var refreshPending = false
    private func finish(_ request: UInt64) {
        guard request == ticket else { return }
        busy = false
        if refreshPending { refreshPending = false; Task { [weak self] in await self?.refresh() } }
    }
    init(auth: AuthRepository) {
        self.auth = auth; generation = auth.sessionGeneration
        owner = auth.currentUser?.id ?? ""; server = auth.authenticatedServerURL
    }
    private var current: Bool { generation == auth.sessionGeneration && owner == auth.currentUser?.id && server == auth.authenticatedServerURL }
    func refresh() async {
        guard current else { return }
        guard !busy else { refreshPending = true; return }
        busy = true; ticket &+= 1; let request = ticket
        defer { finish(request) }
        do {
            let token = try await auth.currentAccessToken()
            guard current else { return }
            let next = try await APIClient(baseURL: server, preventRedirects: true, ephemeral: true)
                .get("/users/me/privacy", accessToken: token, as: UserPrivacy.self)
            guard current, request == ticket, !Task.isCancelled else { return }
            value = next; error = nil
        } catch { if current, request == ticket { self.error = UnyriL10n.string("Could not refresh privacy. Try again.") } }
    }
    func save(_ proposed: UserPrivacy) async {
        guard current, !busy, let old = value else { return }
        var next = proposed.normalized(owner: owner); next.revision = old.revision
        guard next.alwaysAllow.count <= 256, next.neverAllow.count <= 256 else {
            error = UnyriL10n.string("Choose up to 256 accounts in each list."); return
        }
        guard next != old else { return }
        busy = true; ticket &+= 1; let request = ticket
        defer { finish(request) }
        do {
            if UnyriAppLock.shared.enabled { guard await UnyriAppLock.shared.authenticate(), current else { return } }
            let token = try await auth.currentAccessToken()
            guard current else { return }
            let saved = try await APIClient(baseURL: server, preventRedirects: true, ephemeral: true)
                .put("/users/me/privacy", accessToken: token, body: UpdateUserPrivacy(next), as: UserPrivacy.self)
            guard current, request == ticket, !Task.isCancelled else { return }
            value = saved; error = nil
        } catch {
            guard current, request == ticket else { return }
            let stale: Bool
            switch error { case APIError.http(409): stale = true
            case APIError.server(let code, _): stale = code == "conflict" || code.contains("revision") || code == "stale_privacy"
            default: stale = false }
            self.error = UnyriL10n.string(stale ? "Privacy changed on another device. Refresh and try again." : "Could not save privacy. Try again.")
            // Discard the old revision so another tap cannot overwrite a concurrent change.
            if stale {
                busy = false
                let message = self.error
                await refresh()
                if current { self.error = message }
            }
        }
    }
}

@MainActor
final class UnyriDeviceSettings: ObservableObject {
    static let shared = UnyriDeviceSettings()
    @Published var downloads: AutomaticMediaSettings { didSet { persist(downloads, key: "automaticMedia.v1") } }
    @Published var cache: MediaCachePolicy { didSet { persist(cache, key: "mediaCachePolicy.v1"); NotificationCenter.default.post(name: .unyriCachePolicyChanged, object: nil) } }
    private init() {
        let defaults = UserDefaults.standard
        downloads = defaults.data(forKey: "automaticMedia.v1").flatMap { try? JSONDecoder().decode(AutomaticMediaSettings.self, from: $0) }
            ?? .defaults(wifiEnabled: defaults.bool(forKey: "mediaAutoWifi"), cellularEnabled: defaults.bool(forKey: "mediaAutoMobile"))
        cache = defaults.data(forKey: "mediaCachePolicy.v1").flatMap { try? JSONDecoder().decode(MediaCachePolicy.self, from: $0) } ?? MediaCachePolicy()
    }
    private func persist<T: Encodable>(_ value: T, key: String) { if let data = try? JSONEncoder().encode(value) { UserDefaults.standard.set(data, forKey: key) } }
    func allows(mediaType: String, mime: String, bytes: Int64) -> Bool {
        downloads.allows(kind: .classify(mediaType: mediaType, mime: mime), wifi: UnyriMediaNetwork.shared.wifi,
                         connected: UnyriMediaNetwork.shared.connected, bytes: bytes)
    }
}
extension Notification.Name { static let unyriCachePolicyChanged = Notification.Name("COCOCachePolicyChanged") }

/// A single native authentication owner. The overlay changes presentation only;
/// it never stops CallKit, media engines, rooms, sync or the authenticated session.
@MainActor
final class UnyriAppLock: ObservableObject {
    static let shared = UnyriAppLock()
    @Published private(set) var enabled: Bool
    @Published private(set) var timeout: Int
    @Published private(set) var locked = true
    @Published private(set) var authenticating = false
    @Published private(set) var error: String?
    @Published private(set) var mediaControls = AnyView(EmptyView())
    private var policy = AppLockPolicy()
    private var owner: String?
    private weak var auth: AuthRepository?
    private var ownerIsCurrent: Bool {
        guard let auth, let user = auth.currentUser else { return false }
        return owner == auth.authenticatedServerURL.absoluteString + "|" + user.id + "|" + String(auth.sessionGeneration)
    }
    private var generation: UInt64 = 0
    private var context: LAContext?
    private var window: UIWindow?
    private var inactive = false
    private var previousShield: Bool?
    let presentationChanges = PassthroughSubject<Void, Never>()
    private init() {
        enabled = UserDefaults.standard.bool(forKey: "appLock.enabled")
        let stored = UserDefaults.standard.integer(forKey: "appLock.timeout")
        timeout = [0, 60, 300].contains(stored) ? stored : 0
        policy.enabled = enabled; policy.timeout = TimeInterval(timeout)
    }
    func requiresShield(owner: String?) -> Bool { enabled && owner != nil && (self.owner != owner || inactive || locked) }
    func attach(calls: CallController, rooms: ConferenceController, owner: String, auth: AuthRepository) {
        bind(owner: owner, auth: auth)
        mediaControls = AnyView(UnyriLockedCallControls(calls: calls, rooms: rooms))
    }
    func bind(owner: String?, auth: AuthRepository) {
        self.auth = auth
        guard owner != self.owner else { return }
        generation &+= 1; context?.invalidate(); context = nil; authenticating = false
        self.owner = owner; mediaControls = AnyView(EmptyView()); policy.invalidate(); locked = true; error = nil; render()
    }
    func phase(_ phase: ScenePhase) {
        inactive = phase != .active
        if phase == .background { generation &+= 1; context?.invalidate(); context = nil; authenticating = false; policy.background(at: ProcessInfo.processInfo.systemUptime) }
        if phase == .active { policy.activate(at: ProcessInfo.processInfo.systemUptime); locked = policy.locked }
        render()
    }
    func authenticate() async -> Bool {
        guard ownerIsCurrent, !authenticating else { return false }
        let request = generation; let identity = owner
        let context = LAContext(); self.context = context; authenticating = true; error = nil
        context.localizedCancelTitle = UnyriL10n.string("Cancel")
        defer { if request == generation { authenticating = false; self.context = nil } }
        var failure: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &failure) else {
            error = UnyriL10n.string("Set an iPhone passcode to use app lock."); return false
        }
        do {
            let accepted = try await context.evaluatePolicy(.deviceOwnerAuthentication,
                localizedReason: UnyriL10n.string("Unlock UNYRI with Face ID, Touch ID or your iPhone passcode."))
            return accepted && request == generation && identity == owner && ownerIsCurrent && !Task.isCancelled
        } catch {
            if request == generation { self.error = UnyriL10n.string("Authentication cancelled. Try again.") }
            return false
        }
    }
    func unlock() async {
        let request = generation; let identity = owner
        guard await authenticate(), request == generation, identity == owner, ownerIsCurrent else { return }
        policy.unlock(); locked = false; render()
    }
    func configure(enabled: Bool, timeout: Int) async -> Bool {
        let request = generation; let identity = owner
        guard [0, 60, 300].contains(timeout), await authenticate(), request == generation, identity == owner, ownerIsCurrent else { return false }
        self.enabled = enabled; self.timeout = timeout
        UserDefaults.standard.set(enabled, forKey: "appLock.enabled"); UserDefaults.standard.set(timeout, forKey: "appLock.timeout")
        policy.enabled = enabled; policy.timeout = TimeInterval(timeout); policy.unlock(); locked = false; render(); return true
    }
    private func render() {
        let visible = enabled && owner != nil && (inactive || locked)
        if previousShield != visible { previousShield = visible; presentationChanges.send(()) }
        guard visible else { window?.isHidden = true; return }
        if window == nil, let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            let shield = UIWindow(windowScene: scene)
            shield.windowLevel = .alert + 1
            shield.rootViewController = UIHostingController(rootView: UnyriLockScreen(lock: self))
            shield.rootViewController?.view.accessibilityViewIsModal = true
            shield.backgroundColor = .black; window = shield
        }
        // A separate window covers sheets, Quick Look and task-switcher snapshots.
        window?.isHidden = false
    }
}
private struct UnyriLockScreen: View {
    @ObservedObject var lock: UnyriAppLock
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "lock.shield.fill").font(.system(size: 46)).foregroundStyle(Color.white)
                Text("UNYRI is locked").font(.title2.weight(.semibold)).foregroundStyle(.white)
                lock.mediaControls
                Button("Unlock") { Task { await lock.unlock() } }.buttonStyle(.borderedProminent).disabled(lock.authenticating)
                if lock.authenticating { ProgressView().tint(.white) }
                if let error = lock.error { Text(error).font(.footnote).foregroundStyle(.white).multilineTextAlignment(.center) }
            }.padding(32)
        }.preferredColorScheme(.dark).environment(\.locale, Locale(identifier: UnyriL10n.language))
    }
}

struct UnyriAppLockProtection: ViewModifier {
    @EnvironmentObject private var auth: AuthRepository
    @ObservedObject private var lock = UnyriAppLock.shared
    private var identity: String? { auth.currentUser.map { auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration) } }
    func body(content: Content) -> some View {
        let shield = lock.requiresShield(owner: identity)
        content.opacity(shield ? 0 : 1).allowsHitTesting(!shield).accessibilityHidden(shield)
            .overlay { if shield { Color.black.ignoresSafeArea() } }
    }
}

private struct UnyriLockedCallControls: View {
    @ObservedObject var calls: CallController
    @ObservedObject var rooms: ConferenceController
    private var ongoingCall: Bool {
        switch calls.state { case .outgoingRinging, .incomingRinging, .connecting, .connected, .reconnecting: return true
        case .idle, .ended, .failed: return false }
    }
    var body: some View {
        VStack(spacing: 16) {
            if case .incomingRinging = calls.state {
                Text("Incoming call").foregroundStyle(.white)
                HStack(spacing: 24) {
                    Button("Decline", role: .destructive) { Task { await calls.rejectIncoming() } }.buttonStyle(.borderedProminent)
                    Button("Answer") { Task { await calls.acceptIncoming() } }.buttonStyle(.borderedProminent).tint(.green)
                }
            } else if ongoingCall {
                Text("Call in progress").foregroundStyle(.white)
                Button("End call", role: .destructive) { Task { await calls.hangup() } }.buttonStyle(.borderedProminent)
            }
            if rooms.phase != .idle {
                Text("Room in progress").foregroundStyle(.white)
                Button("Leave room", role: .destructive) { rooms.leave() }.buttonStyle(.borderedProminent).disabled(rooms.phase == .leaving)
            }
        }
    }
}
