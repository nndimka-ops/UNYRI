import SwiftUI
import Combine
import UserNotifications
import CallKit
import CoreFoundation

@main
@MainActor
struct UNYRIApp: App {
    @UIApplicationDelegateAdaptor(ChatReminderDelegate.self) private var reminderDelegate
    @AppStorage(UnyriL10n.key) private var language = "system"
    @Environment(\.scenePhase) private var appPhase
    @StateObject private var auth: AuthRepository
    @StateObject private var serverOriginGate: UnyriServerOriginController
    // @State, not @StateObject: the scene keeps the instance without observing
    // it. Only the small gate views observe it, so a gate change re-renders
    // them and not the whole scene.
    @State private var clientConfig: UnyriClientConfigGate

    init() {
        PeerImagePolicy.install()
        // The account list selects the active storage slot before any session
        // read (single-account installs have no list: primary slot, as before).
        let accounts = UnyriAccountManager.shared
        let marker: () throws -> Bool = { try UnyriServerWorkspaceEvidence.installationExists(support: Self.supportDirectory) }
        try? accounts.resolveActiveSlot(installationMarker: marker, launch: true)
        let store = SessionStore.active()
        let originGate = UnyriServerOriginController(store: { SessionStore.active() },
            resolveAccount: { try accounts.resolveActiveSlot(installationMarker: marker) })
        // The placeholder is never used while proof is blocked. Every
        // Auth.bootstrap entry point repeats the guard before any side effect.
        let selectedOrigin = (try? originGate.prepare()) ?? URL(string: "https://localhost")!
        let client = APIClient(baseURL: selectedOrigin)
        let account = AuthRepository(api: AuthAPI(client: client), store: store, device: DeviceInfo(store: store),
            clearLocalData: { try await LocalAccountCachePurger.erase() },
            hasLocalAccountWorkspace: { userId in
                // A just-added account signs in before its services create the folder.
                if accounts.activeSlotAwaitsSignIn { return true }
                return try UnyriServerWorkspaceEvidence.hasWorkspace(support: Self.supportDirectory,
                    origin: AppConfig.storageOrigin, user: userId)
            }, hasLocalInstallation: {
                let marker = Self.installationDirectory.appendingPathComponent("continuity-v1")
                do {
                    guard try Data(contentsOf: marker) == Data("COCO installation v1".utf8) else {
                        throw CocoaError(.fileReadCorruptFile)
                    }
                    return true
                } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
                    return false
                }
            }, recordLocalInstallation: {
                var directory = Self.installationDirectory
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var attributes = URLResourceValues()
                attributes.isExcludedFromBackup = true
                try directory.setResourceValues(attributes)
                try Data("COCO installation v1".utf8).write(to: directory.appendingPathComponent("continuity-v1"), options: .atomic)
            }, prepareServerForBootstrap: { try originGate.prepare() },
            installationProofs: InstallationProofCoordinator(store: KeychainInstallationProofStore()) { origin in
                try await AuthAPI(client: APIClient(baseURL: origin, preventRedirects: true)).createInstallationCredential()
            })
        _auth = StateObject(wrappedValue: account)
        _serverOriginGate = StateObject(wrappedValue: originGate)
        // The cached decision for this server applies before the first frame.
        _clientConfig = State(initialValue: UnyriClientConfigGate(origin: selectedOrigin))
        accounts.bind(auth: account)
        let push = UnyriPushNotificationCoordinator.shared
        push.bind(auth: account)
        push.onIncomingCall = { [weak account] hint, ticket in
            guard let account else { return false }
            await account.bootstrap()
            guard push.matchesReceived(hint, ticket: ticket), let session = push.currentSession() else { return false }
            let services = AccountServices.shared(auth: account)
            guard services.sessionGeneration == session.generation, services.initializationError == nil,
                  let controller = services.callController, let call = hint.callID else { return false }
            let provider = UnyriCallKitProvider.shared
            let handled = await controller.receiveIncomingPush(call,
                admitted: { push.matchesReceived(hint, ticket: ticket) && provider.admitted(ticket, session: session) },
                takeReject: { push.matchesReceived(hint, ticket: ticket) && provider.takeReject(ticket, session: session) },
                terminal: { provider.terminal(ticket, reason: $0) })
            guard handled, push.matchesReceived(hint, ticket: ticket) else { return false }
            // A genuine ringing CallKit call owns background signaling, using
            // the same connection and recovery policy as an ordinary call.
            services.realtime?.start()
            return true
        }
        push.onForegroundMessage = { [weak account] hint, expected in
            guard let account else { return }
            await account.bootstrap()
            guard push.currentSession() == expected, !Task.isCancelled,
                  UIApplication.shared.applicationState == .active,
                  let chat = hint.chatID, let message = hint.messageID else { return }
            let services = AccountServices.shared(auth: account)
            services.foregroundBanner?.isForeground = true
            _ = ForegroundMessageBannerController.takesAlert(UnyriForegroundPushHint(
                owner: expected.scope.user.uuidString.lowercased(), generation: expected.generation,
                chatId: chat.uuidString.lowercased(), messageId: message.uuidString.lowercased()))
            await services.syncManager?.runCatchUpSync()
        }
        push.onMessageTap = { [weak account] hint, expected in
            guard let account else { return }
            await account.bootstrap()
            guard push.currentSession() == expected, !Task.isCancelled,
                  UIApplication.shared.applicationState == .active else { return }
            let services = AccountServices.shared(auth: account)
            await services.syncManager?.runCatchUpSync()
            guard push.currentSession() == expected, !Task.isCancelled,
                  let chat = hint.chatID, let message = hint.messageID else { return }
            UserDefaults.standard.set(["owner": expected.scope.user.uuidString.lowercased(),
                "chat": chat.uuidString.lowercased(), "message": message.uuidString.lowercased()], forKey: "chatReminderRoute")
            NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil)
        }
        HistoryAutomaticBackup.shared.register {
            await account.bootstrap()
            guard account.currentUser != nil else { return false }
            let services = AccountServices.shared(auth: account)
            guard let access = services.historyBackupAccess else { return false }
            return await HistoryAutomaticBackup.shared.run(auth: account, access: access)
        }
        UnyriBackgroundPush.restore = { [weak account] in
            guard let account else { return }
            await account.bootstrap()
            // Signed in only: logged out, locked Keychain or a running account switch create nothing.
            guard account.currentUser != nil else { return }
            _ = AccountServices.shared(auth: account)
        }
    }

    // Kept outside the account-cache root so ordinary logout cannot erase it.
    private static var installationDirectory: URL {
        supportDirectory.appendingPathComponent("COCOInstallation", isDirectory: true)
    }

    private static var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
    }

    private static func signedIn(_ state: AuthRepository.State) -> Bool {
        if case .loggedIn = state { return true }
        return false
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if let issue = serverOriginGate.issue {
                    UnyriServerOriginRecoveryView(issue: issue) { choice in
                        do { _ = try serverOriginGate.prepare(choice: choice) } catch { return }
                        Task { await auth.bootstrap() }
                    }
                } else {
                    // Inside the app lock below, so the lock also covers the gate.
                    UnyriClientGateRoot(gate: clientConfig, signedIn: Self.signedIn(auth.state)) { ContentView() }
                }
            }
                .unyriChatAppearanceRoot()
                .unyriAppColorThemeRoot()
                .unyriSidebarAppearanceRoot()
                .performanceDiagnosticsSession()
                .automaticDiagnosticsSession()
                .shakeToReportSession()
                .modifier(UnyriAppLockProtection())
                .environment(\.openURL, OpenURLAction { url in
                    ["http", "https"].contains(url.scheme?.lowercased() ?? "") ? .systemAction : .discarded
                })
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
                    UnyriAppLock.shared.protectedDataUnavailable()
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataWillBecomeUnavailableNotification)) { _ in
                    UnyriAppLock.shared.protectedDataUnavailable()
                }
                .alert("Security warning", isPresented: Binding(get: { auth.localSecurityWarning != nil }, set: { if !$0 { auth.localSecurityWarning = nil } })) {
                    Button("OK") { auth.localSecurityWarning = nil }
                } message: { Text(verbatim: auth.localSecurityWarning ?? "") }
                .environmentObject(auth)
                .modifier(UnyriAccountActivityReporter(auth: auth))
                .unyriLocale(language)
                .preferredColorScheme(.dark)
                .task { UnyriL10n.syncExtensionLanguage(); await auth.bootstrap() }
                .task(id: appPhase) {
                    guard appPhase == .active else { return }
                    await UnyriPushNotificationCoordinator.shared.foreground()
                    while !Task.isCancelled {
                        AutomaticDiagnosticReports.shared.retry()
                        await auth.retryPendingLogouts()
                        await auth.ensureInstallationBinding()
                        UnyriPushNotificationCoordinator.shared.retry()
                        // Throttled to once every few hours (also when the app stays open for days).
                        UnyriAccountManager.shared.renewInactiveSessionsIfDue()
                        do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
                    }
                }
                // Launch, foreground, account switch and server change (selectServer
                // bumps the generation). One request at most per 30 min per server;
                // no timer and no loop.
                .task(id: "\(appPhase == .active)|\(auth.sessionGeneration)") {
                    guard appPhase == .active else { return }
                    await clientConfig.refresh(origin: auth.authenticatedServerURL, force: false)
                }
                .task(id: "\(auth.sessionGeneration)|\(auth.currentUser?.id ?? "")") {
                    UnyriPushNotificationCoordinator.shared.sessionChanged()
                    UnyriAppLock.shared.bind(owner: auth.currentUser.map { auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration) }, auth: auth)
                    UnyriAppLock.shared.phase(appPhase)
                }
                .onChange(of: appPhase) { phase in
                    UnyriAppLock.shared.phase(phase)
                    // A background restore may happen while the protected inbox
                    // is locked. Retry publication on foreground, without polling.
                    if phase == .active { auth.syncShareInboxAccount() }
                }
                .onChange(of: language) { _ in UnyriL10n.syncExtensionLanguage() }
                .onOpenURL { url in
                    if IncomingIdentityLinks.shared.receiveServerInvite(url.absoluteString) {
                        return
                    } else if ["unyri", "coco"].contains(url.scheme?.lowercased() ?? ""), ["conference", "c"].contains(url.host ?? "") {
                        NotificationCenter.default.post(name: ConferenceController.invitationNotification, object: url)
                    } else if url.scheme?.lowercased() == "unyri", ["u", "user"].contains(url.host?.lowercased() ?? "") {
                        IncomingIdentityLinks.shared.receive(url.absoluteString)
                    } else { UnyriIncomingFile.shared.receive(url) }
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { IncomingIdentityLinks.shared.receiveServerInvite(url.absoluteString) }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                    Task {
                        await auth.bootstrap()
                        auth.syncShareInboxAccount()
                    }
                }
        }
    }
}

/// One installation-owned preflight. Failure never clears a session or probes
/// any server; a readable, uniquely proven previous origin is selected locally.
@MainActor
private final class UnyriServerOriginController: ObservableObject {
    @Published private(set) var issue: UnyriServerOriginContinuity.Failure?
    private let store: () -> SessionStore
    private let resolveAccount: () throws -> Void
    init(store: @escaping () -> SessionStore, resolveAccount: @escaping () throws -> Void) {
        self.store = store; self.resolveAccount = resolveAccount
    }
    func prepare(choice: String? = nil) throws -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        // The active account's slot (and so its server key) is known first.
        do { try resolveAccount() } catch {
            let failure = UnyriServerOriginContinuity.Failure(.sessionUnreadable)
            issue = failure; throw failure
        }
        let session = store()
        do {
            let url = try AppConfig.prepareServerOrigin(choice: choice,
                identity: { try session.checkedServerContinuityUserID() },
                installation: { try UnyriServerWorkspaceEvidence.installationExists(support: support) },
                hasWorkspaces: { try UnyriServerWorkspaceEvidence.hasAnyWorkspace(support: support) },
                workspace: { origin, user in try UnyriServerWorkspaceEvidence.hasWorkspace(support: support, origin: origin, user: user) })
            issue = nil
            return url
        } catch let failure as UnyriServerOriginContinuity.Failure { issue = failure; throw failure }
    }
}

private struct UnyriServerOriginRecoveryView: View {
    let issue: UnyriServerOriginContinuity.Failure
    let resume: (String?) -> Void
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "lock.shield").font(.system(size: 32)).foregroundStyle(UnyriColors.accent)
                Text("Confirm your previous server").font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                Text(issue.reason == .sessionUnreadable
                    ? "Unlock this iPhone, then retry. UNYRI has not changed your server or removed your history."
                    : "UNYRI could not safely identify the server for your saved account. Your session and history have not been removed. Choose a proven previous server below, or retry when storage is available.")
                    .multilineTextAlignment(.center).foregroundStyle(UnyriColors.secondaryText)
                ForEach(issue.choices, id: \.absoluteString) { origin in
                    Button(origin.absoluteString) { resume(origin.absoluteString) }.unyriProminentAction()
                }
                Button("Retry") { resume(nil) }.unyriProminentAction()
            }.padding(24).frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
        }.unyriScreenBackground()
    }
}

// MARK: - Client version gate (GET /client/config)

/// Server-driven minimum and recommended app version, per server origin.
/// Fetched on launch, foreground, account switch and server change, at most
/// once per 30 min per origin; never on a timer. A cached decision applies
/// before the first frame. An unreachable server keeps the last decision, so
/// a blocked client stays blocked offline and a client with no cache is never
/// blocked.
@MainActor
private final class UnyriClientConfigGate: ObservableObject {
    struct Requirement: Equatable { let current, minimum, host: String; let updateURL: URL? }
    struct Recommendation: Equatable { let version, originKey: String; let updateURL: URL? }
    enum Outcome { case applied, throttled, unreachable }

    @Published private(set) var required: Requirement?
    @Published private(set) var recommended: Recommendation?

    /// iOS entry of one origin's policy; an empty entry means "no gate".
    private struct Entry: Codable {
        let fetchedAt: Date
        let minimum: String?
        let recommended: String?
        let updateURL: String?
    }

    private static let cacheKey = "unyri.clientConfig.v1"
    private static let dismissedKey = "unyri.update.dismissedRecommended.v1"
    private static let throttle: TimeInterval = 30 * 60
    private static let maximumEntries = 8

    private var origin: URL
    // In memory only, so a cold launch always fetches once.
    private var lastFetch: [String: Date] = [:]
    private var inFlight: [String: Task<Outcome, Never>] = [:]
    /// A requirement that appeared during a call; shown on the next activation.
    private var pending: Requirement?

    init(origin: URL) {
        self.origin = origin
        if let entry = Self.cachedEntries()[Self.key(origin)] { apply(entry, origin: origin, coldLaunch: true) }
    }

    static func key(_ origin: URL) -> String {
        let scheme = origin.scheme?.lowercased() ?? "https"
        let host = origin.host?.lowercased() ?? ""
        return origin.port.map { "\(scheme)://\(host):\($0)" } ?? "\(scheme)://\(host)"
    }

    @discardableResult
    func refresh(origin: URL, force: Bool) async -> Outcome {
        let key = Self.key(origin)
        if key != Self.key(self.origin) {
            // Another server: its own cached decision, never the previous one's.
            self.origin = origin
            pending = nil
            if let entry = Self.cachedEntries()[key] { apply(entry, origin: origin, coldLaunch: false) }
            else { clearDecision() }
        }
        showPendingIfIdle()
        if let running = inFlight[key] { return await running.value }
        if !force, let last = lastFetch[key], Date().timeIntervalSince(last) < Self.throttle { return .throttled }
        let task = Task { await self.fetch(origin: origin, key: key) }
        inFlight[key] = task
        let outcome = await task.value
        inFlight[key] = nil
        return outcome
    }

    func recheck() async -> Outcome { await refresh(origin: origin, force: true) }

    /// Once per version per origin; also called after "Update" is tapped.
    func dismissRecommendation() {
        guard let current = recommended else { return }
        var dismissed = Self.dismissedVersions()
        dismissed[current.originKey] = current.version
        if dismissed.count > Self.maximumEntries {
            let known = Set(Self.cachedEntries().keys)
            dismissed = dismissed.filter { known.contains($0.key) || $0.key == current.originKey }
        }
        UserDefaults.standard.set(dismissed, forKey: Self.dismissedKey)
        recommended = nil
    }

    private func fetch(origin: URL, key: String) async -> Outcome {
        // A fresh ephemeral session per fetch: nothing cached, no cookies, no redirects.
        let api = AuthAPI(client: APIClient(baseURL: origin, preventRedirects: true, ephemeral: true))
        let entry: Entry
        do {
            let ios = try await api.clientConfig().platforms.ios
            entry = Entry(fetchedAt: Date(), minimum: ios?.minimumVersion, recommended: ios?.recommendedVersion, updateURL: ios?.updateUrl)
        } catch APIError.http(let status) where status == 404 || status == 405 {
            entry = Entry(fetchedAt: Date(), minimum: nil, recommended: nil, updateURL: nil) // an older server: no gate
        } catch APIError.server(let code, _) where code == "not_found" || code == "route_not_found" {
            entry = Entry(fetchedAt: Date(), minimum: nil, recommended: nil, updateURL: nil)
        } catch {
            return .unreachable // keep the last decision
        }
        lastFetch[key] = Date()
        Self.store(entry, key: key)
        guard key == Self.key(self.origin) else { return .applied }
        apply(entry, origin: origin, coldLaunch: false)
        return .applied
    }

    private func apply(_ entry: Entry, origin: URL, coldLaunch: Bool) {
        let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let key = Self.key(origin)
        let updateURL = AppVersion.trustedUpdateURL(entry.updateURL)
        if let minimum = entry.minimum, AppVersion.compare(current, minimum) == .orderedAscending {
            let requirement = Requirement(current: current, minimum: minimum, host: origin.host?.lowercased() ?? key, updateURL: updateURL)
            // Never cut into a running call: a new requirement waits for the
            // next activation. At cold launch it applies at once.
            if required == nil && !coldLaunch && Self.callInProgress() {
                pending = requirement
                return
            }
            pending = nil
            if required != requirement { required = requirement }
            if recommended != nil { recommended = nil }
            return
        }
        pending = nil
        if required != nil { required = nil }
        let recommendation: Recommendation?
        if let version = entry.recommended, AppVersion.compare(current, version) == .orderedAscending,
           Self.dismissedVersions()[key] != version {
            recommendation = Recommendation(version: version, originKey: key, updateURL: updateURL)
        } else {
            recommendation = nil
        }
        if recommended != recommendation { recommended = recommendation }
    }

    private func showPendingIfIdle() {
        guard let requirement = pending, !Self.callInProgress() else { return }
        pending = nil
        if required != requirement { required = requirement }
        if recommended != nil { recommended = nil }
    }

    private func clearDecision() {
        if required != nil { required = nil }
        if recommended != nil { recommended = nil }
    }

    /// One read, no observer kept.
    private static func callInProgress() -> Bool {
        CXCallObserver().calls.contains { !$0.hasEnded }
    }

    private static func cachedEntries() -> [String: Entry] {
        let raw = UserDefaults.standard.dictionary(forKey: cacheKey) as? [String: Data] ?? [:]
        let decoder = JSONDecoder()
        return raw.compactMapValues { try? decoder.decode(Entry.self, from: $0) }
    }

    private static func store(_ entry: Entry, key: String) {
        guard let data = try? JSONEncoder().encode(entry) else { return }
        // Undecodable leftovers are dropped; the oldest go first past 8 origins.
        var entries = cachedEntries()
        entries[key] = entry
        var raw: [String: Data] = [:]
        for (origin, value) in entries.sorted(by: { $0.value.fetchedAt > $1.value.fetchedAt }).prefix(maximumEntries) {
            if origin == key { raw[origin] = data }
            else if let encoded = try? JSONEncoder().encode(value) { raw[origin] = encoded }
        }
        raw[key] = data
        UserDefaults.standard.set(raw, forKey: cacheKey)
    }

    private static func dismissedVersions() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: dismissedKey) as? [String: String] ?? [:]
    }
}

/// While required, blocks only a signed-in account's content: sign-in, the
/// server choice and the other accounts stay reachable, so one server's
/// minimum never locks the whole app. Signed out, a notice sits above the
/// sign-in screen. The recommendation banner is on top. Observes only the gate.
private struct UnyriClientGateRoot<Content: View>: View {
    @ObservedObject var gate: UnyriClientConfigGate
    let signedIn: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        Group {
            if signedIn {
                if let requirement = gate.required {
                    UnyriUpdateRequiredView(requirement: requirement, recheck: { await gate.recheck() })
                } else {
                    content()
                }
            } else {
                // The notice comes and goes inside the inset, so the sign-in
                // form (and what was typed into it) keeps its identity.
                content().safeAreaInset(edge: .top, spacing: 0) {
                    if let requirement = gate.required { UnyriUpdateRequiredNotice(requirement: requirement) }
                }
            }
        }
        .overlay(alignment: .top) { UnyriUpdateBannerHost(gate: gate) }
    }
}

/// Glass card surface; a solid fill under Low Power Mode.
private struct UnyriUpdateSurface<S: Shape>: ViewModifier {
    let shape: S
    let lowPower: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if lowPower {
            content
                .background(UnyriColors.glassFloating, in: shape)
                .overlay(shape.stroke(UnyriColors.outlineSecondary, lineWidth: 0.5))
        } else {
            content.modifier(UnyriAttachGlass(shape: shape))
        }
    }
}

/// Full-screen and blocking: this version is below the server's minimum.
/// Local data is untouched; only the app is asked to update.
private struct UnyriUpdateRequiredView: View {
    let requirement: UnyriClientConfigGate.Requirement
    let recheck: () async -> UnyriClientConfigGate.Outcome
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var checking = false
    @State private var note: String?
    @State private var appeared = false
    // Read once: the screen is static, it need not follow the setting live.
    private let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    private var simple: Bool { reduceMotion || lowPower }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                card
                    .frame(maxWidth: 420)
                    .padding(.horizontal, 20)
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
        .unyriScreenBackground()
    }

    private var card: some View {
        VStack(spacing: 16) {
            Circle().fill(UnyriColors.glassHighlight).frame(width: 64, height: 64)
                .overlay(Image(systemName: "arrow.down.app").font(.system(size: 28, weight: .medium)).foregroundStyle(UnyriColors.primaryText))
                .accessibilityHidden(true)
            Text(UnyriL10n.string("Update the app"))
                .font(.title2.weight(.semibold)).foregroundStyle(UnyriColors.primaryText)
                .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
            Text(String(format: UnyriL10n.string("The server %@ no longer supports this version of UNYRI. Install the update to continue. Data on this device has not been removed."), requirement.host))
                .font(.subheadline).foregroundStyle(UnyriColors.secondaryText)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Text(String(format: UnyriL10n.string("Installed %@ · required %@"), requirement.current, requirement.minimum))
                .font(.footnote.monospacedDigit()).foregroundStyle(UnyriColors.veryMutedText)
                .multilineTextAlignment(.center)
            if let url = requirement.updateURL {
                // The button style gives the press haptic.
                Button(UnyriL10n.string("Update")) { openURL(url) }
                    .buttonStyle(UnyriPrimaryButtonStyle())
                    .accessibilityIdentifier("update-required-open")
            } else {
                Text(UnyriL10n.string("Update UNYRI from the App Store or TestFlight."))
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
            }
            Button(action: check) {
                HStack(spacing: 8) {
                    if checking { ProgressView().controlSize(.small).tint(UnyriColors.primaryText) }
                    Text(UnyriL10n.string("Check again")).font(.body.weight(.medium))
                }
                .foregroundStyle(UnyriColors.primaryText)
                .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }
            .buttonStyle(UnyriQuietButtonStyle())
            .disabled(checking)
            .accessibilityIdentifier("update-required-check")
            if let note {
                Text(note).font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            UnyriUpdateAccountEscape()
        }
        .padding(24)
        .modifier(UnyriUpdateSurface(shape: RoundedRectangle(cornerRadius: 28, style: .continuous), lowPower: lowPower))
        .accessibilityElement(children: .contain)
        .opacity(appeared ? 1 : 0)
        .scaleEffect(appeared || simple ? 1 : 0.98)
        .onAppear {
            // One-shot entrance; opacity only with Reduce Motion or Low Power.
            if !appeared { withAnimation(.easeOut(duration: simple ? 0.2 : 0.28)) { appeared = true } }
            UIAccessibility.post(notification: .screenChanged, argument: nil)
        }
    }

    private func check() {
        guard !checking else { return }
        checking = true
        if note != nil { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { note = nil } }
        Task {
            let outcome = await recheck()
            checking = false
            // Still on screen means still required: a lifted gate removes this view.
            let text = outcome == .unreachable
                ? UnyriL10n.string("Could not reach the server. Check your network connection and server address.")
                : UnyriL10n.string("This version is still not supported.")
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { note = text }
        }
    }
}

/// The way out of a blocked server: the other accounts on this iPhone, and
/// "Add account" for another server. The blocked account stays signed in
/// and its data stays here.
private struct UnyriUpdateAccountEscape: View {
    @ObservedObject private var accounts = UnyriAccountManager.shared

    var body: some View {
        let others = accounts.otherAccounts
        if !others.isEmpty || accounts.canAddAccount {
            VStack(alignment: .leading, spacing: 6) {
                Rectangle().fill(UnyriColors.outlineSecondary).frame(height: 0.5)
                    .padding(.bottom, 6).accessibilityHidden(true)
                Text(UnyriL10n.string("Another account or server"))
                    .font(.footnote.weight(.semibold)).foregroundStyle(UnyriColors.secondaryText)
                    .accessibilityAddTraits(.isHeader)
                ForEach(others, id: \.slot) { record in
                    let publicIdentityLabel = PeerIdentityPolicy.label(publicUsername: record.publicUsername,
                                                                      unyriNumber: record.unyriNumber)
                    Button { Task { await accounts.switchTo(record) } } label: {
                        HStack(spacing: 10) {
                            Text(String(record.displayName.prefix(1)).uppercased())
                                .font(.system(size: 13, weight: .semibold)).foregroundStyle(UnyriColors.primaryText)
                                .frame(width: 30, height: 30).background(Circle().fill(UnyriColors.glassHighlight))
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(record.displayName).font(.subheadline.weight(.semibold))
                                    .foregroundStyle(UnyriColors.primaryText).lineLimit(1)
                                if accounts.needsSignIn.contains(record.slot) {
                                    Text(UnyriL10n.string("Sign in again")).font(.caption).foregroundStyle(UnyriColors.danger).lineLimit(1)
                                } else if !publicIdentityLabel.isEmpty {
                                    Text(publicIdentityLabel).font(.caption).foregroundStyle(UnyriColors.secondaryText)
                                        .lineLimit(1).truncationMode(.middle)
                                }
                            }
                            Spacer(minLength: 4)
                            if accounts.busy { ProgressView().controlSize(.small) }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(UnyriQuietButtonStyle())
                    .disabled(accounts.busy)
                    .accessibilityHint(Text(UnyriL10n.string("Switch account")))
                    .accessibilityIdentifier("update-required-account-\(record.slot)")
                }
                if accounts.canAddAccount {
                    Button { Task { await accounts.beginAddAccount() } } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "plus").font(.system(size: 14, weight: .semibold))
                                .frame(width: 30, height: 30).background(Circle().fill(UnyriColors.glassHighlight))
                                .accessibilityHidden(true)
                            Text(UnyriL10n.string("Add account")).font(.subheadline.weight(.medium))
                            Spacer(minLength: 0)
                        }
                        .foregroundStyle(UnyriColors.primaryText)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(UnyriQuietButtonStyle())
                    .disabled(accounts.busy)
                    .accessibilityIdentifier("update-required-add-account")
                }
                if let error = accounts.lastError {
                    Text(verbatim: error).font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // An error left by the drawer belongs to the drawer.
            .onAppear { accounts.lastError = nil }
        }
    }
}

/// Signed out on a server that needs a newer app: sign-in and the server
/// choice stay usable, with this note above them. Not dismissible, no timer.
private struct UnyriUpdateRequiredNotice: View {
    let requirement: UnyriClientConfigGate.Requirement
    @Environment(\.openURL) private var openURL
    private let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    private var text: String {
        String(format: UnyriL10n.string("The server %@ needs a newer version of UNYRI. Update the app or choose another server."), requirement.host)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "arrow.down.app").font(.system(size: 17, weight: .semibold))
                .foregroundStyle(UnyriColors.primaryText).accessibilityHidden(true)
            Text(text).font(.footnote).foregroundStyle(UnyriColors.primaryText)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let url = requirement.updateURL {
                Button(UnyriL10n.string("Update")) { openURL(url) }
                    .font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.accentBright)
                    .frame(minHeight: 44)
                    .buttonStyle(UnyriQuietButtonStyle())
                    .accessibilityIdentifier("update-required-notice-open")
            }
        }
        .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 6)
        .frame(minHeight: 52)
        .modifier(UnyriUpdateSurface(shape: RoundedRectangle(cornerRadius: 18, style: .continuous), lowPower: lowPower))
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 4)
        .frame(maxWidth: 560)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("update-required-notice")
        .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
    }
}

/// Owns the banner's entrance and exit; Reduce Motion fades only.
private struct UnyriUpdateBannerHost: View {
    @ObservedObject var gate: UnyriClientConfigGate
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let visible = gate.required == nil ? gate.recommended : nil
        ZStack(alignment: .top) {
            if let recommendation = visible {
                UnyriUpdateBanner(recommendation: recommendation, open: { url in
                    openURL(url)
                    gate.dismissRecommendation()
                }, dismiss: { gate.dismissRecommendation() })
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity)
        .animation(reduceMotion ? .easeOut(duration: 0.2)
            : (visible != nil ? .spring(response: 0.32, dampingFraction: 0.9) : .easeIn(duration: 0.2)), value: visible)
    }
}

/// Dismissible, once per version per server. No auto-hide timer.
private struct UnyriUpdateBanner: View {
    let recommendation: UnyriClientConfigGate.Recommendation
    let open: (URL) -> Void
    let dismiss: () -> Void
    private let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle").font(.system(size: 17, weight: .semibold))
                .foregroundStyle(UnyriColors.primaryText).accessibilityHidden(true)
            Text(UnyriL10n.string("A new version of UNYRI is available"))
                .font(.subheadline.weight(.medium)).foregroundStyle(UnyriColors.primaryText)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if let url = recommendation.updateURL {
                Button(UnyriL10n.string("Update")) { open(url) }
                    .font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.accentBright)
                    .frame(minHeight: 44)
                    .buttonStyle(UnyriQuietButtonStyle())
            }
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(UnyriColors.secondaryText)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(UnyriQuietButtonStyle())
            .accessibilityLabel(UnyriL10n.string("Later"))
        }
        .padding(.leading, 14).padding(.trailing, 2)
        .frame(minHeight: 52)
        .modifier(UnyriUpdateSurface(shape: RoundedRectangle(cornerRadius: 18, style: .continuous), lowPower: lowPower))
        .shadow(color: .black.opacity(0.3), radius: 16, y: 6)
        .padding(.horizontal, 12).padding(.top, 6)
        .frame(maxWidth: 560)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("update-recommended-banner")
        .onAppear {
            UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string("A new version of UNYRI is available"))
        }
    }
}

/// Created only after login; discarded together with all cached UI state on logout.
@MainActor
private final class AccountServices: ObservableObject {
    private static var instance: AccountServices?
    private static var authIdentity: ObjectIdentifier?
    static func shared(auth: AuthRepository) -> AccountServices {
        if let existing = instance, existing.initializationError == nil, authIdentity == ObjectIdentifier(auth), existing.sessionGeneration == auth.sessionGeneration { return existing }
        instance?.shutdown()
        let value = AccountServices(auth: auth)
        instance = value; authIdentity = ObjectIdentifier(auth)
        // Also when iOS launches the app in the background for a message push:
        // installing this only on foreground missed exactly that case.
        value.installBackgroundPushHandler()
        return value
    }
    let sessionGeneration: UInt64
    private(set) var signalManager: SignalSessionManager?
    let privacy: UserPrivacyRepository
    let accountAutoDelete: AccountAutoDeleteRepository
    let users: UsersRepository
    let chats: ChatsRepository
    let messages: MessagesRepository?
    let realtime: RealtimeClient?
    let syncManager: SyncManager?
    let outbox: MessageOutbox?
    private(set) var directFiles: DirectFileTransfers?
    let attachmentManager: AttachmentManager?
    let storage: StorageService?
    let voicePlayer: VoicePlayerController
    let voiceRecorder: VoiceRecorder
    let callController: CallController?
    let conferenceController: ConferenceController?
    private(set) var callsHistory: CallsRepository?
    let callKit: CallKitCoordinator?
    let foregroundBanner: ForegroundMessageBannerController?
    let initializationError: String?
    private(set) var historyBackupAccess: HistoryBackupAccess?

    private var cancellables = [AnyCancellable]()
    private var recoveryTask: Task<Void, Never>?
    private var foregroundTask: Task<Void, Never>?
    private var foregroundTicket = UUID()
    private var historyAutomaticBinding: UUID?
    private weak var auth: AuthRepository?

    private var shareSnapshotWriter: ShareTargetSnapshotWriter?
    private var shareJobProcessor: ShareJobProcessor?
    private var shareJobObserver: UUID?
    @Published var shareJobNotice: String?

    init(auth: AuthRepository) {
        self.auth = auth
        sessionGeneration = auth.sessionGeneration
        // Bound to this account's slot for the services' whole lifetime.
        let session = SessionStore.active()
        let generation = auth.sessionGeneration
        let client = APIClient(baseURL: AppConfig.apiBaseURL, onAuthenticatedRequestError: { [weak auth] error, bearer in
            // Let the rejected request unwind before cache erasure waits for writers.
            Task { await auth?.handleAuthenticatedRequestError(error, generation: generation, requestToken: bearer) }
        })
        privacy = UserPrivacyRepository(auth: auth)
        accountAutoDelete = AccountAutoDeleteRepository(auth: auth)
        users = UsersRepository(api: UsersAPI(client: client), auth: auth)
        chats = ChatsRepository(api: ChatsAPI(client: client), auth: auth)
        voicePlayer = VoicePlayerController()
        let recordingController = VoiceRecorder()
        voiceRecorder = recordingController
        voicePlayer.playbackAllowed = { [weak recordingController] in
            guard let recordingController else { return false }
            // callsBusy also covers a call still being created or a room joining, before
            // callInProgress is published: playback must never replace that configuration.
            return !recordingController.callInProgress && !recordingController.hasRecordingSession
                && !NonCallCaptureOwnership.callsBusy()
        }
        do {
            guard let userId = auth.currentUser?.id else { throw APIError.missingSession }
            guard let pushOwner = UUID(uuidString: userId), let pushDevice = auth.currentDeviceId else { throw APIError.missingSession }
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            let server = Data(AppConfig.storageOrigin.utf8).base64EncodedString()
                .replacingOccurrences(of: "/", with: "_")
            let directory = base.appendingPathComponent("COCO", isDirectory: true)
                .appendingPathComponent(server, isDirectory: true)
                .appendingPathComponent(userId, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var privateDirectory = directory
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try privateDirectory.setResourceValues(resourceValues)
            // Voice transcripts are kept per account (same owner key as its erasure).
            VoiceTranscriber.bind(owner: AppConfig.storageOrigin + "|" + userId)
            let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let attachmentStore = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
            let chatStore = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3"))
            try HistoryAttachmentRestorer.resume(messages: store, attachments: attachmentStore,
                applyDeletedChat: { try chatStore.deleteChat($0) }, removeCachedFile: { path in
                    let cache = directory.appendingPathComponent("attachments",isDirectory:true).standardizedFileURL.resolvingSymlinksInPath().path + "/"
                    let file = URL(fileURLWithPath:path).standardizedFileURL.resolvingSymlinksInPath()
                    guard file.path.hasPrefix(cache), FileManager.default.fileExists(atPath:file.path),
                          try file.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile == true else { return }
                    try FileManager.default.removeItem(at:file)
                }, removeTranscript: { try VoiceTranscriber.forgetForDeletion([$0]) }) {
                guard auth.sessionGeneration == generation, auth.currentUser?.id == userId else { throw APIError.missingSession }
            }
            chats.store = chatStore
            chats.messageStore = store
            let attachmentsCache = directory.appendingPathComponent("attachments", isDirectory: true)
            try FileManager.default.createDirectory(at: attachmentsCache, withIntermediateDirectories: true)
            let container = try UnyriSignalStoreContainer.forCurrentAccount(sessionStore: session, localSecurity: auth.localSecurity)
            let keys = KeysRepository(api: KeysAPI(client: client), auth: auth)
            let messagesAPI = MessagesAPI(client: client)
            let attachmentsAPI = AttachmentsAPI(client: client)
            let keyManager = SignalKeyManager(container: container, keysRepository: keys)
            let sessionManager = SignalSessionManager(container: container, keysRepository: keys)
            self.signalManager = sessionManager
            sessionManager.onSecurityWarning = { [weak auth] in
                auth?.localSecurityWarning = UnyriL10n.string("A removed device is listed again. It remains blocked on this iPhone.")
            }
            auth.revokeOwnDeviceLocally = { [weak sessionManager] id in
                guard let sessionManager else { throw CancellationError() }
                try await sessionManager.revokeOwnDevice(id)
            }
            let attachmentManager = AttachmentManager(
                store: attachmentStore,
                api: attachmentsAPI,
                auth: auth,
                cacheDirectory: attachmentsCache
            )
            self.attachmentManager = attachmentManager
            storage = StorageService(store: attachmentStore, accountDirectory: directory, draftReferenced: { try store.workspaceReferences($0) }, current: { generation == auth.sessionGeneration && auth.currentUser?.id == userId })
            NotificationCenter.default.publisher(for: Notification.Name("COCOLocalBlockChanged"))
                .sink { [weak users] _ in users?.accountBlocksChanged() }.store(in: &cancellables)
            let ingestor = MessageIngestor(store: store, attachmentStore: attachmentStore,
                keyManager: keyManager,
                sessionManager: sessionManager, auth: auth, messagesAPI: messagesAPI,
                sessionStore: session)
            let securityChats = chats
            ingestor.authorizeIncoming = { [weak securityChats] dto in
                guard let securityChats else { throw CancellationError() }
                try await securityChats.authorizeIncoming(dto)
            }
            chats.onPeerReceipt = { [weak ingestor] chat, delivered, read in
                ingestor?.advanceOwnMessageStatuses(chatId: chat, deliveredSequence: delivered, readSequence: read)
            }
            sessionManager.onTrustAccepted = { [weak ingestor] userId, _ in
                Task { await ingestor?.retryPendingDecrypts(senderId: userId) }
            }
            let outbox = MessageOutbox(store: store, ingestor: ingestor,
                sessionManager: sessionManager, auth: auth, messagesAPI: messagesAPI,
                sessionStore: session, chatsRepository: chats, attachmentManager: attachmentManager)
            self.outbox = outbox
            outbox.onAuthTerminal = {
                Task { await auth.handleAuthenticatedError(.server(code: "session_revoked", message: "Session ended"), generation: generation) }
            }
            let liveChats = chats
            let liveUsers = users
            liveUsers.onAccountBlockChange = { [weak liveChats] userID, blocked in
                liveChats?.applyAccountBlock(userID: userID, blocked: blocked)
            }
            liveChats.onAccountBlockChange = { [weak liveUsers] userID, blocked in
                liveUsers?.accountBlockAcknowledged(userID: userID, blocked: blocked)
            }
            ingestor.changes.merge(with: outbox.changes)
                .sink { chatIds in
                    Task { await liveChats.messagesChanged(in: chatIds) }
                }.store(in: &cancellables)

            let messagesRepository = MessagesRepository(api: messagesAPI, auth: auth,
                store: store, ingestor: ingestor, outbox: outbox,
                attachmentManager: attachmentManager)
            messages = messagesRepository
            let directFiles = DirectFileTransfers(auth: auth, signal: sessionManager, outbox: outbox,
                store: store, client: client, accountDirectory: directory)
            self.directFiles = directFiles
            messagesRepository.directFiles = directFiles
            ingestor.onDirectFileOffer = { [weak directFiles] chat, offer in
                directFiles?.receivedOffer(chatId: chat, offer: offer)
            }
            messagesRepository.onReadStateChange = { [weak liveChats] in
                Task { await liveChats?.refreshUnreadCounts() }
            }

            let realtime = RealtimeClient(wsURL: AppConfig.wsURL, auth: auth)
            realtime.directTransferSignals.sink { [weak directFiles] id in
                directFiles?.peerSignalArrived(id)
            }.store(in: &cancellables)
            let privacyRepository = privacy
            let profileRepository = users
            realtime.profileEvents.sink { [weak profileRepository] hint in
                profileRepository?.refreshOwnProfile(hint)
            }.store(in: &cancellables)
            realtime.privacyEvents.sink { [weak privacyRepository] _ in Task { await privacyRepository?.refresh() } }.store(in: &cancellables)
            let accountAutoDeleteRepository = accountAutoDelete
            realtime.accountAutoDeleteEvents.sink { [weak accountAutoDeleteRepository] _ in Task { await accountAutoDeleteRepository?.refresh() } }.store(in: &cancellables)
            realtime.liveDrafts.configure(signal: sessionManager, send: { [weak realtime] value in
                guard realtime?.state == .connected else { return }
                realtime?.sendRaw(value)
            })
            outbox.onMediaActivity = { [weak realtime] chat, kind in
                if UserDefaults.standard.bool(forKey: "chatActivityEnabled") {
                    realtime?.sendActivity(chatId: chat, kind: kind)
                }
            }
            self.realtime = realtime
            let syncManager = SyncManager(api: SyncAPI(client: client), ingestor: ingestor,
                auth: auth, sessionStore: session)
            self.syncManager = syncManager
            syncManager.onPhaseChange = { [weak realtime] phase in realtime?.syncPhase = phase }
            realtime.onRetrySync = { [weak syncManager] in await syncManager?.runCatchUpSync() }
            let callsRepository = CallsRepository(api: CallsAPI(client: client), auth: auth,
                historyFile: directory.appendingPathComponent("calls.json"), ownerId: UUID(uuidString: userId),
                deletionServerOrigin: AppConfig.storageOrigin)
            let historyLease = HistoryAccountLease()
            historyBackupAccess = HistoryBackupAccess(lease: historyLease) { server, remote in
                HistoryNativeOwner(server: server, owner: userId, directory: directory, remote: remote, lease: historyLease,
                    sourceReady: {
                        guard generation == auth.sessionGeneration else { throw CancellationError() }
                        try await callsRepository.prepareBackupHistory()
                        do { try await syncManager.prepareHistoryBackup() }
                        catch is SyncManager.HistoryPreparationFailure { throw HistoryNativeOwner.Failure.incompleteSource }
                        var offset: Int64 = 0
                        for _ in 0..<100 {
                            let page = try await liveChats.listChats(limit: 100, offset: offset)
                            liveChats.persistChats(page.items)
                            guard let next = page.nextOffset else { return }
                            guard next > offset else { throw HistoryNativeOwner.Failure.incompleteSource }
                            offset = next
                        }
                        throw HistoryNativeOwner.Failure.incompleteSource
                    }, current: {
                        guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    }, updated: { [weak callsRepository, weak messagesRepository] touched in
                        messagesRepository?.refreshImportedHistory(in: touched)
                        _ = await liveChats.loadCachedChatsAsync()
                        await callsRepository?.loadCachedHistory()
                        await liveChats.messagesChanged(in: touched)
                    }, exportCalls: { [weak callsRepository] archive in
                        guard let callsRepository else { throw CancellationError() }
                        return try await callsRepository.exportBackupCalls(archive)
                    }, restoreCalls: { [weak callsRepository] archive in
                        guard let callsRepository else { throw CancellationError() }
                        try await callsRepository.restoreBackupCalls(archive)
                    })
            }
            UnyriAppLock.shared.presentationChanges.sink { [weak messagesRepository] _ in
                Task { await messagesRepository?.presentationVisibilityChanged() }
            }.store(in: &cancellables)
            storage?.onCleared = { [weak messagesRepository] in messagesRepository?.invalidateLocalMediaCache() }
            syncManager.onDeletedChat = { [weak liveChats, weak messagesRepository, weak realtime] chatId in
                guard let liveChats, let messagesRepository, let realtime else { throw CancellationError() }
                guard generation == auth.sessionGeneration else { throw CancellationError() }
                try store.markChatDeleted(chatId)
                try liveChats.removeDeletedChat(chatId)
                realtime.liveDrafts.close(chat: chatId)
                try await messagesRepository.purgeDeletedChat(chatId)
                // This chat's own pending sends were cancelled by the purge above.
                ConversationImages.clearDecodedCache(includingPendingSends: false)
            }
            // Finished deletions are skipped by the catch-up sync (the server lists them all
            // every time); one that stopped half-way (files left) runs again.
            syncManager.isDeletionApplied = { chatId in
                ((try? store.isChatDeleted(chatId)) ?? false) && attachmentManager.isChatPurged(chatId)
            }

            liveChats.onPersonalClear = { [weak messagesRepository] chat, through in
                guard let messagesRepository else { throw CancellationError() }
                return try await messagesRepository.clearPersonalChat(chat, through: through)
            }
            liveChats.clearServerOrigin = AppConfig.storageOrigin
            liveChats.currentClearServer = { AppConfig.storageOrigin }
            liveChats.clearSyncSequence = {
                guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { return 0 }
                return session.lastSyncedSequence() ?? 0
            }
            liveChats.onLocalBoundedClear = { [weak messagesRepository] chat, cancelled in
                messagesRepository?.boundedChatWasCleared(chat,cancelled:cancelled)
            }
            syncManager.beforeSync = {
                // Publish this device's existing/new Signal keys at login and
                // reconnect, even while the user stays on the chat list. A
                // person must be encryptable before opening their first chat.
                try await ingestor.ensureBootstrap()
                do { try await liveChats.refreshPersonalClears() }
                catch APIError.http(404) { /* Older server. */ }
            }
            liveChats.onDeleteConversation = syncManager.onDeletedChat

            // Call subsystem: REST lifecycle + signaling over the SAME socket +
            // WebRTC media, all behind one authoritative controller.
            callsRepository.currentDeletionServer = { AppConfig.storageOrigin }
            callsRepository.currentDeletionOwner = { auth.currentUser.flatMap { UUID(uuidString: $0.id) } }
            callsRepository.messageStore = store
            callsRepository.beforeHistoryRefresh = {
                do { try await liveChats.refreshPersonalClears() }
                catch APIError.http(404) { /* Legacy server. */ }
                catch APIError.server(let code, _) where code == "not_found" { }
            }
            liveChats.onCallClear = { [weak callsRepository] chat, before in callsRepository?.applyCallClear(chat: chat, before: before) }
            let deleteChat = syncManager.onDeletedChat
            syncManager.onDeletedChat = { [weak callsRepository] chat in
                try await deleteChat?(chat)
                callsRepository?.chatDeleted(chat)
            }
            liveChats.onDeleteConversation = syncManager.onDeletedChat
            callsRepository.projectHistory = { [weak callsRepository] rows, since in
                guard let callsRepository else { throw CancellationError() }
                let historyRevision = callsRepository.historyRevision
                var resolved: [CallDTO] = []
                var changed = false
                let chatRevision = liveChats.cacheRevision
                for var call in rows {
                    guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    guard CallActivityPolicy.eligible(call, owner: UUID(uuidString: userId), since: since) else { continue }
                    let peer = (call.callerUserId == UUID(uuidString: userId) ? call.calleeUserId : call.callerUserId).uuidString.lowercased()
                    var visible = false
                    if call.chatVisible != false {
                        do {
                            if let chat = try await liveChats.resolveCallChat(call, peer: peer) {
                                call.chatId = UUID(uuidString: chat.chatId); visible = true
                            }
                        } catch APIError.network {
                            guard generation == auth.sessionGeneration else { throw CancellationError() }
                            // A transport failure does not revoke an already validated canonical cache entry.
                            if let id = call.chatId?.uuidString.lowercased(),
                               let cached = liveChats.preferenceCache[id], !liveChats.isDeleted(id),
                               UUID(uuidString: cached.otherUser?.id ?? "") == UUID(uuidString: peer),
                               let created = ISO8601Cocoa.parse(cached.createdAt), created <= call.createdAt { visible = true }
                        } catch {
                            guard generation == auth.sessionGeneration else { throw CancellationError() }
                        }
                    }
                    guard generation == auth.sessionGeneration, auth.currentUser?.id == userId else { throw CancellationError() }
                    guard callsRepository.historyRevision == historyRevision else { throw CancellationError() }
                    let rowChanged = try store.projectCallActivity(CallActivityProjection(callId: call.id.uuidString.lowercased(),
                        chatId: call.chatId?.uuidString.lowercased(), peerId: peer, createdAt: call.createdAt,
                        mediaKind: call.mediaKind.rawValue, outcome: call.isEnded ? "ended:\(call.endReason ?? "")" : call.state,
                        visible: visible, unread: call.unread), owner: userId)
                    changed = changed || rowChanged
                    resolved.append(call)
                }
                return (resolved, changed || liveChats.cacheRevision != chatRevision)
            }
            callsRepository.activityChanged = { [weak liveChats] in
                guard generation == auth.sessionGeneration else { return }
                await liveChats?.messagesChanged(in: [])
            }
            realtime.callHistoryEvents.sink { [weak callsRepository] in callsRepository?.reconcile() }.store(in: &cancellables)
            realtime.callEvents.sink { [weak callsRepository] event in
                switch event {
                case .ringing(let id, _, _), .ended(let id, _), .accepted(let id), .answeredElsewhere(let id):
                    callsRepository?.reconcile(callId: UUID(uuidString: id))
                default: break
                }
            }.store(in: &cancellables)
            realtime.$state.removeDuplicates().sink { [weak callsRepository] state in
                if state == .connected {
                    callsRepository?.reconcile()
                    Task { await liveChats.replayPreferences(); _ = try? await liveChats.archives() }
                    Task { await liveChats.replayPendingChatClears() }
                }
            }.store(in: &cancellables)
            syncManager.onHistoryClear = { cutoff, sequence in
                guard generation == auth.sessionGeneration else { throw CancellationError() }
                let touched = try store.clearServerHistory(throughSequence: sequence)
                ingestor.changes.send(touched)
                try await callsRepository.clearServerHistory(beforeMs: cutoff)
            }
            callsHistory = callsRepository
            let callSignaling = SocketCallSignaling(realtime: realtime)
            let callEngine = WebRTCEngineImpl()
            let callController = CallController(
                api: callsRepository,
                signaling: callSignaling,
                engine: callEngine,
                generation: auth.sessionGeneration
            )
            self.callController = callController
            let conferenceController = ConferenceController(auth: auth, signalManager: sessionManager)
            self.conferenceController = conferenceController
            // B-055: a confirmed account switch ends the call and leaves the room first.
            UnyriAccountManager.shared.endActiveMedia = { [weak callController, weak conferenceController] in
                // B-059: the peer must hear the hangup from this account before the switch.
                if let callController, callController.state != .idle { await callController.hangupDelivered() }
                if let conferenceController, conferenceController.phase != .idle { conferenceController.leave() }
            }
            callController.externalMediaBusy = { ConferenceMediaOwnership.isBusy }
            historyBackupAccess?.canUseCamera = { [weak callController] in
                callController?.state == .idle && !ConferenceMediaOwnership.isBusy
            }
            let callVoiceRecorder = voiceRecorder
            let callVoicePlayer = voicePlayer
            callController.$state.sink { state in
                switch state {
                case .outgoingRinging, .incomingRinging, .connecting, .connected, .reconnecting:
                    callVoiceRecorder.callInProgress = true
                    callVoicePlayer.stop()
                default:
                    callVoiceRecorder.callInProgress = ConferenceMediaOwnership.isBusy
                    if UIApplication.shared.applicationState == .background { realtime.stop() }
                }
            }.store(in: &cancellables)
            let callerChats = chats
            callController.resolveCallerName = { userId in
                return try? await callerChats.directPeerName(userId)
            }
            UnyriPushNotificationCoordinator.shared.knownIncomingPeer = { [weak callController, weak auth] id in
                guard let auth, auth.sessionGeneration == generation,
                      let call = callController?.state.callInfo, UUID(uuidString: call.callId) == id else { return nil }
                return call.remoteUserId
            }
            callController.incomingPeerAllowed = { [weak auth] peer in
                guard let security = auth?.localSecurity else { return false }
                do { return try security.blockDecision(peer) != true } catch { return false }
            }
            NotificationCenter.default.publisher(for: Notification.Name("COCOLocalBlockChanged"))
                .sink { [weak callController] _ in callController?.localBlockChanged() }.store(in: &cancellables)
            let callerUsers = users
            callController.isContact = { [weak callerUsers] userId in
                callerUsers?.isKnownPeerContact(userId) ?? false
            }
            directFiles.isContact = { [weak callerUsers] userId in
                callerUsers?.isKnownPeerContact(userId) ?? false
            }
            // Native system-call UI mirror of the same controller (no second
            // state machine). Shares the early process provider with PushKit.
            let pushSession = UnyriPushSession(scope: UnyriPushScope(origin: auth.authenticatedServerURL.absoluteString,
                user: pushOwner, device: pushDevice), generation: auth.sessionGeneration)
            callController.incomingCallAllowed = { UnyriCallKitProvider.shared.allowsIncoming($0, session: pushSession) }
            let callKit = CallKitCoordinator(controller: callController, generation: auth.sessionGeneration, pushSession: pushSession)
            self.callKit = callKit
            directFiles.canStream = { [weak callController, weak callKit] in
                callController?.state == .idle && callKit?.hasPendingMediaStart == false && !ConferenceMediaOwnership.isBusy
            }
            callController.$state.sink { [weak directFiles] _ in directFiles?.mediaOwnershipChanged() }.store(in: &cancellables)
            conferenceController.$phase.sink { [weak directFiles] _ in directFiles?.mediaOwnershipChanged() }.store(in: &cancellables)
            NonCallCaptureOwnership.currentGeneration = { [weak auth] in
                guard let auth, auth.currentUser != nil else { return nil }
                return auth.sessionGeneration
            }
            NonCallCaptureOwnership.callsBusy = { [weak callController, weak callKit] in
                ConferenceMediaOwnership.isBusy || callController?.isPrivateMediaBusy != false || callKit?.hasPendingMediaStart != false
            }
            callController.externalCaptureBusy = { NonCallCaptureOwnership.isBusy }
            callController.interruptExternalCapture = { NonCallCaptureOwnership.interrupt() }
            callController.awaitExternalCaptureRelease = { deadline in
                try await NonCallCaptureOwnership.waitForRelease(until: deadline)
            }
            conferenceController.canUseMedia = { [weak callController, weak callKit, weak callVoiceRecorder] in
                callController?.canAcquireConferenceMedia == true && callKit?.hasPendingMediaStart == false
                    && callVoiceRecorder?.hasRecordingSession == false
            }
            conferenceController.onMediaOwnershipChanged = { [weak callVoiceRecorder, weak callVoicePlayer, weak callController] active in
                callVoiceRecorder?.callInProgress = active || callController?.isPrivateMediaBusy == true
                if active { callVoicePlayer?.stop() }
            }

            // Runtime Correction D: foreground message banner (presentation-only).
            // Driven by WS new-message events, resolved from the just-persisted
            // LOCAL row (never the wire ciphertext). Replace-latest, auto-dismissed.
            let bannerController = ForegroundMessageBannerController(
                realtimeEvents: realtime.events,
                ingestorChanges: ingestor.changes,
                loadMessage: { try store.message(byClientMessageId: $0) },
                ownUserId: userId,
                activeChatId: { messagesRepository.activeChatId },
                activeCall: {
                    switch callController.state {
                    case .outgoingRinging, .incomingRinging, .connecting, .connected, .reconnecting:
                        return true
                    default:
                        return false
                    }
                },
                resolveSenderName: { chatId in
                    callerChats.preferenceCache[chatId]?.otherUser?.visibleName
                },
                notificationServer: AppConfig.apiBaseURL.absoluteString,
                notificationGeneration: generation,
                loadServerMessage: { try store.message(byServerId: $0) }
            )
            bannerController.isSilenced = { chatId in
                callerChats.preferenceCache[chatId]?.suppressNotifications == true
            }
            bannerController.notificationsEnabled = {
                UnyriNotificationPreferencesStore.shared.value.allowsMessages &&
                    !(UnyriAppLock.shared.enabled && UnyriAppLock.shared.locked)
            }
            bannerController.resolveAvatar = { chatID in
                guard let peer = callerChats.preferenceCache[chatID]?.otherUser else { return nil }
                return profileRepository.cachedAvatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion)
            }
            bannerController.resolvePresentation = { [weak auth] chatID in
                guard auth?.sessionGeneration == generation, auth?.currentUser?.id == userId else { return nil }
                var chat = callerChats.preferenceCache[chatID]
                if chat == nil { chat = try? await callerChats.getChat(chatId: chatID) }
                guard !Task.isCancelled, auth?.sessionGeneration == generation, let chat else { return nil }
                if chat.suppressNotifications { return (nil, nil, true) }
                guard let peer = chat.otherUser else { return (nil, nil, false) }
                var avatar = profileRepository.cachedAvatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion)
                if avatar == nil {
                    avatar = await profileRepository.avatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion)
                }
                guard !Task.isCancelled, auth?.sessionGeneration == generation else { return nil }
                return (peer.visibleName, avatar, false)
            }
            bannerController.privacyBlocked = { UnyriAppLock.shared.enabled && UnyriAppLock.shared.locked }
            bannerController.onInactivePresented = { preferences in
                if preferences.allowsInAppVibration { UINotificationFeedbackGenerator().notificationOccurred(.success) }
                if preferences.allowsInAppSound {
                    UnyriForegroundMessageSound.presentInactive(stillCurrent: { !(UnyriAppLock.shared.enabled && UnyriAppLock.shared.locked) })
                }
            }
            bannerController.inAppPreferences = { UnyriNotificationPreferencesStore.shared.value }
            bannerController.messagePreviewEnabled = { UnyriNotificationPreferencesStore.shared.value.previews }
            UnyriForegroundMessageSound.bind(owner: userId, generation: generation, canPlay: { [weak auth, weak callController, weak callKit] in
                auth?.sessionGeneration == generation && auth?.currentUser?.id == userId &&
                    callController?.isPrivateMediaBusy == false && callKit?.hasPendingMediaStart == false &&
                    !ConferenceMediaOwnership.isBusy && !(UnyriAppLock.shared.enabled && UnyriAppLock.shared.locked)
            })
            bannerController.onPresented = { [weak auth, weak bannerController] banner, messageId in
                guard let auth, auth.sessionGeneration == generation, auth.currentUser?.id == userId,
                      bannerController?.isForeground == true, banner.shouldAlert else { return }
                if UnyriNotificationPreferencesStore.shared.value.allowsInAppVibration {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                }
                UnyriForegroundMessageSound.present(owner: userId, generation: generation, chatId: banner.chatId, messageId: messageId,
                    stillCurrent: { [weak auth, weak bannerController] in
                        auth?.sessionGeneration == generation && auth?.currentUser?.id == userId &&
                            bannerController?.isForeground == true
                    })
            }
            UnyriAppLock.shared.presentationChanges.sink { [weak bannerController] _ in
                if UnyriAppLock.shared.enabled && UnyriAppLock.shared.locked { bannerController?.preferencesChanged() }
            }.store(in: &cancellables)
            NotificationCenter.default.publisher(for: UnyriNotificationPreferencesStorage.changed)
                .receive(on: RunLoop.main)
                .sink { [weak bannerController] _ in
                    bannerController?.preferencesChanged()
                    UnyriLocalNotificationPreferences.reconcile()
                    Task { await UnyriPushNotificationCoordinator.shared.refreshAuthorization() }
                }.store(in: &cancellables)
            UnyriLocalNotificationPreferences.attach(owner: userId, isCurrent: { [weak auth] in
                auth?.sessionGeneration == generation && auth?.currentUser?.id == userId
            })
            foregroundBanner = bannerController

            // WS reconnect -> global catch-up sync + pending-message flush
            // (Android parity: the socket fast path only delivers while
            // connected; /sync and forceFlush cover the gaps).
            realtime.$state
                .removeDuplicates()
                .sink { [weak profileRepository, weak directFiles, weak callerUsers] state in
                    if state == .connected {
                        callerUsers?.refreshContactMembership()
                        directFiles?.networkDidRecover()
                        profileRepository?.refreshOwnProfile()
                        Task {
                            await syncManager.runCatchUpSync()
                            await outbox.forceFlush()
                            await messagesRepository.flushReadReceipts()
                        }
                    }
                }
                .store(in: &cancellables)

            // Realtime event dispatch: incoming messages funnel through the
            // shared ingestor (single decrypt/dedup path); receipts advance our
            // own bubbles; a server-side session revocation logs out.
            realtime.events.sink { event in
                switch event {
                case .messageNew(let dto), .messageSent(let dto):
                    Task { _ = await ingestor.ingest([dto], advanceCursor: false) }
                case .receiptUpdated(let chatId, let userId, let delivered, let read):
                    if userId == auth.currentUser?.id {
                        messagesRepository.applyAccountReadReceipt(chatId: chatId, sequence: read)
                        return
                    }
                    ingestor.advanceOwnMessageStatuses(
                        chatId: chatId,
                        deliveredSequence: delivered,
                        readSequence: read
                    )
                case .sessionRevoked(let reason):
                    let code = ["account_deleted", "account_suspended"].contains(reason) ? reason : "session_revoked"
                    Task { await auth.handleAuthenticatedError(.server(code: code, message: "Session ended"), generation: generation) }
                case .chatManagementChanged:
                    liveChats.accountBlocksChangedRemotely()
                    liveUsers.accountBlocksChanged()
                    liveChats.managementRevision &+= 1
                    Task {
                        await liveChats.replayPreferences()
                        _ = try? await liveChats.archives()
                        await liveChats.replayPendingChatClears()
                        try? await liveChats.refreshPersonalClears(force: true)
                    }
                case .userDeleted(let userId):
                    liveUsers.peerDeleted(userId)
                    liveChats.peerDeleted(userId)
                case .chatDeleted(let chatId):
                    Task { try? await syncManager.onDeletedChat?(chatId) }
                }
            }.store(in: &cancellables)

            initializationError = nil
            shareSnapshotWriter = ShareTargetSnapshotWriter(auth: auth, chats: chats, users: users)
            shareJobProcessor = ShareJobProcessor.native(auth: auth, chats: chats, outbox: outbox, messages: messagesRepository, store: store, directory: directory,
                notice: { [weak self] key in self?.shareJobNotice = UnyriL10n.string(key) })
            shareJobObserver = UnyriShareJobObserver.install { [weak self] in self?.shareJobProcessor?.request() }
            UnyriShareLifecycle.retire = { [weak self] in self?.retireShareWork() }
            outbox.changes.sink { [weak self] _ in self?.shareJobProcessor?.request() }.store(in: &cancellables)
            chats.$cacheRevision.removeDuplicates().dropFirst().sink { [weak self] _ in self?.shareJobProcessor?.request() }.store(in: &cancellables)
            shareJobProcessor?.request()
            historyAutomaticBinding = HistoryAutomaticBackup.shared.attach { [weak self, weak auth] in
                guard let self, let auth, let access = self.historyBackupAccess,
                      auth.sessionGeneration == self.sessionGeneration else { return false }
                return await HistoryAutomaticBackup.shared.run(auth: auth, access: access, canRun: self.callController?.state == .idle)
            }
        } catch {
            messages = nil
            realtime = nil
            syncManager = nil
            outbox = nil
            attachmentManager = nil
            storage = nil
            callController = nil
            conferenceController = nil
            callKit = nil
            foregroundBanner = nil
            initializationError = error.localizedDescription
        }
        let accountChats = chats
        UnyriAccountManager.shared.currentUnread = { [weak accountChats] in accountChats?.unreadTotal ?? 0 }
        let accountUsers = users
        accountUsers.startContactMembershipSync()
        let attachments = attachmentManager
        let accountMessages = messages
        let accountCalls = callsHistory
        let recorder = voiceRecorder
        let player = voicePlayer
        auth.prepareForCacheErasure = { [weak self] in
            self?.shutdown()
            await attachments?.cancelAndDrainDraftReferences()
            await self?.directFiles?.drain()
            await self?.historyBackupAccess?.drain()
            ConversationImages.clearDecodedCache()
            UserAvatar.clearDecodedCache()
            recorder.cancel()
            player.stop()
            await accountMessages?.finishSessionDraftWrites()
            await accountCalls?.finishSessionCacheWrites()
            await accountUsers.finishSessionCacheWrites()
            await attachments?.finishSessionCacheWrites()
        }
    }

    /// Only one account-owned foreground preflight can publish work. A scene
    /// transition revokes its ticket before an old privacy/disk await returns.
    func installBackgroundPushHandler() {
        let generation = sessionGeneration
        UnyriBackgroundPush.handler = { [weak self] in
            guard let self, self.auth?.sessionGeneration == generation, self.auth?.currentUser != nil else { return }
            await self.syncManager?.runCatchUpSync()
            // A silent push also gives unsent messages another try.
            await self.outbox?.forceFlush()
        }
    }

    func startRealtime() {
        shareJobProcessor?.request()
        installBackgroundPushHandler()
        guard auth?.sessionGeneration == sessionGeneration, auth?.currentUser != nil,
              UIApplication.shared.applicationState == .active,
              foregroundTask == nil, recoveryTask == nil else { return }
        let ticket = UUID()
        foregroundTicket = ticket
        foregroundTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.foregroundTicket == ticket { self.foregroundTask = nil } }
            guard self.foregroundCurrent(ticket) else { return }
            await self.privacy.refresh()
            guard self.foregroundCurrent(ticket) else { return }
            await self.storage?.enforcePolicy()
            guard self.foregroundCurrent(ticket) else { return }
            await self.startForegroundRealtime(ticket: ticket)
        }
    }

    /// Revokes foreground jobs only. Call/room/checkpoint background leases
    /// retain their existing owners and are not controlled by this ticket.
    func cancelForegroundWork() {
        foregroundTicket = UUID()
        foregroundTask?.cancel()
        foregroundTask = nil
        recoveryTask?.cancel()
        recoveryTask = nil
    }

    private func foregroundCurrent(_ ticket: UUID) -> Bool {
        !Task.isCancelled && foregroundTicket == ticket &&
            auth?.sessionGeneration == sessionGeneration && auth?.currentUser != nil &&
            UIApplication.shared.applicationState == .active
    }

    private func startForegroundRealtime(ticket: UUID) async {
        guard foregroundCurrent(ticket) else { return }
        directFiles?.start()
        users.refreshOwnProfile()
        realtime?.setPresenceActive(true)
        if recoveryTask == nil {
            recoveryTask = Task { [weak self] in
                var pass = 0
                while !Task.isCancelled {
                    do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                    guard let self, self.foregroundCurrent(ticket) else { return }
                    // While the socket is connected it already delivers messages, receipts and
                    // call events; the REST safety net then runs once a minute instead of every
                    // 15 s (device report: >1 request/s while idle).
                    pass &+= 1
                    if self.realtime?.state == .connected && pass % 4 != 0 { continue }
                    self.realtime?.start()
                    self.callController?.refreshActiveCall()
                    self.callsHistory?.reconcile()
                    async let preferenceReplay: Void = { @MainActor in
                        guard self.foregroundCurrent(ticket) else { return }
                        await self.chats.replayPreferences()
                    }()
                    await self.chats.replayPendingChatClears()
                    guard self.foregroundCurrent(ticket) else { return }
                    await self.syncManager?.runCatchUpSync()
                    guard self.foregroundCurrent(ticket) else { return }
                    await self.chats.recoverVisiblePeerReceipts { self.messages?.activeChatId }
                    await preferenceReplay
                }
            }
        }
        realtime?.start()
        callController?.refreshActiveCall()
        callsHistory?.reconcile()
        async let preferenceReplay: Void = { @MainActor in
            guard self.foregroundCurrent(ticket) else { return }
            await self.chats.replayPreferences()
        }()
        await chats.replayPendingChatClears()
        guard foregroundCurrent(ticket) else { return }
        await syncManager?.runCatchUpSync()
        guard foregroundCurrent(ticket) else { return }
        await chats.recoverVisiblePeerReceipts { self.messages?.activeChatId }
        guard foregroundCurrent(ticket) else { return }
        HistoryAutomaticBackup.shared.foreground()
        // Flush any PENDING rows surviving a restart/login.
        await outbox?.forceFlush()
        guard foregroundCurrent(ticket) else { return }
        await messages?.flushReadReceipts()
        await preferenceReplay
    }

    func stopRealtime() {
        cancelForegroundWork()
        directFiles?.suspendForBackground()
        realtime?.setPresenceActive(false)
        // An active CallKit audio session can keep the app running in the
        // background. Keep signaling alive so remote hangup reaches CallKit.
        if !CallKitPolicy.keepsSignalingInBackground(callController?.state ?? .idle) { realtime?.stop() }
        // Recording never continues in the background. Voice playback does (screen
        // lock, other apps; the app has the audio background mode, like Telegram):
        // it is stopped on sign-out/account switch in shutdown().
        voiceRecorder.interrupt()
    }

    func shutdown() {
        retireShareWork()
        UnyriBackgroundPush.handler = nil
        cancelForegroundWork()
        directFiles?.shutdown()
        users.stopOwnProfileSync()
        storage?.retire()
        NonCallCaptureOwnership.interrupt(generation: sessionGeneration)
        if Self.instance === self { Self.instance = nil; Self.authIdentity = nil }
        HistoryAutomaticBackup.shared.detach(historyAutomaticBinding)
        historyBackupAccess?.stop()
        signalManager?.shutdown(); signalManager = nil
        outbox?.stopScheduling()
        foregroundBanner?.isForeground = false
        callKit?.shutdown()
        conferenceController?.shutdown()
        callController?.shutdown()
        callsHistory?.stopReconciliation()
        voicePlayer.stop()
        stopRealtime()
    }

    private func retireShareWork() {
        shareSnapshotWriter?.stop(); shareJobProcessor?.stop()
        UnyriShareJobObserver.remove(shareJobObserver); shareJobObserver = nil
    }

    func shareProtectedDataAvailable() {
        shareSnapshotWriter?.changed(); shareJobProcessor?.request()
    }
}

/// A payload-free nudge. The processor coalesces bursts for 300 ms and retains
/// one rescan if a notification arrives during an existing pass.
@MainActor private enum UnyriShareJobObserver {
    private static var installed = false
    private static var binding: UUID?
    private static var handler: (() -> Void)?
    static func install(_ handler: @escaping () -> Void) -> UUID {
        let id = UUID(); binding = id; self.handler = handler
        if !installed {
            installed = true
            // The callback has process lifetime, no raw pointer to a retired
            // account's services. Its handler is replaced/revoked on MainActor.
            CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, { _, _, _, _, _ in
                Task { @MainActor in UnyriShareJobObserver.handler?() }
            }, ShareSignal.name as CFString, nil, .deliverImmediately)
        }
        return id
    }
    static func remove(_ id: UUID?) { if id == binding { handler = nil; binding = nil } }
}

@MainActor
struct AccountContentView: View {
    @ObservedObject var auth: AuthRepository
    @ObservedObject private var incomingFile = UnyriIncomingFile.shared
    @StateObject private var services: AccountServices
    @State private var showHistoryRestore = false
    @Environment(\.scenePhase) private var scenePhase

    init(auth: AuthRepository) {
        self.auth = auth
        _services = StateObject(wrappedValue: AccountServices.shared(auth: auth))
    }

    private func checkSharedInbox(offerLegacy: Bool = true) {
        guard auth.sessionGeneration == services.sessionGeneration, let scope = auth.shareInboxScope else { return }
        let auth = self.auth
        incomingFile.checkSharedInbox(scope: scope, offerLegacy: offerLegacy, current: { [weak auth] in
            auth?.shareInboxScope == scope
        })
    }

    var body: some View {
        Group {
            if let messages = services.messages, let attachments = services.attachmentManager, let storage = services.storage, let callController = services.callController, let conferenceController = services.conferenceController, let callKit = services.callKit, let realtime = services.realtime, let bannerController = services.foregroundBanner, let callsHistory = services.callsHistory {
                LoggedInView()
                    .environment(\.historyBackupAccess, services.historyBackupAccess)
                    .environment(\.signalVerificationManager, services.signalManager)
                    .environmentObject(services.privacy)
                    .environmentObject(services.accountAutoDelete)
                    .environmentObject(services.users)
                    .environmentObject(services.chats)
                    .environment(\.chatChatsRepository, services.chats)
                    .environmentObject(callsHistory)
                    .environmentObject(messages)
                    .environmentObject(attachments)
                    .environmentObject(storage)
                    .environmentObject(services.voicePlayer)
                    .environmentObject(services.voiceRecorder)
                    .environment(\.videoNoteVoicePlayer, services.voicePlayer)
                    .environment(\.videoNoteVoiceRecorder, services.voiceRecorder)
                    .environmentObject(callController)
                    .environmentObject(conferenceController)
                    .environmentObject(callKit)
                    .environmentObject(realtime)
                    .environment(\.chatRealtimeClient, realtime)
                    .environmentObject(bannerController)
            } else {
                VStack(spacing: 16) {
                    Text("Could not open local messages")
                    Text(services.initializationError ?? "Please restart UNYRI.").font(.footnote)
                    Button("Sign out") { Task { await auth.logout() } }
                }.padding()
            }
        }
        .task {
            if let calls = services.callController, let rooms = services.conferenceController, let user = auth.currentUser {
                let callKit = services.callKit
                UnyriAppLock.shared.attach(calls: calls, rooms: rooms,
                    owner: auth.authenticatedServerURL.absoluteString + "|" + user.id + "|" + String(auth.sessionGeneration), auth: auth,
                    answer: { [weak callKit] in callKit?.answerIncoming() })
                UnyriAppLock.shared.phase(scenePhase)
            }
            services.foregroundBanner?.isForeground = scenePhase == .active
            if services.initializationError == nil {
                UnyriAccountManager.shared.activated(auth: auth, profile: services.users.cachedMe())
            }
            if UserDefaults.standard.string(forKey: "openHistoryBackupForAccount") == auth.currentUser?.id {
                UserDefaults.standard.removeObject(forKey: "openHistoryBackupForAccount")
                showHistoryRestore = true
            }
            checkSharedInbox()
            services.startRealtime()
        }
        .task {
            while !Task.isCancelled {
                await services.storage?.enforcePolicy()
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
            }
        }
        .sheet(isPresented: Binding(get: { auth.sessionGeneration == services.sessionGeneration && incomingFile.hasPendingImport && scenePhase == .active && UIApplication.shared.isProtectedDataAvailable }, set: { if !$0 && auth.sessionGeneration == services.sessionGeneration && scenePhase == .active && UIApplication.shared.isProtectedDataAvailable { incomingFile.close() } }), onDismiss: {
            checkSharedInbox(offerLegacy: false)
        }) {
            if let messages = services.messages { UnyriImportFileSheet(inbox: incomingFile, messages: messages, chats: services.chats, users: services.users, accountKey: auth.shareAccountKey).id(incomingFile.url?.path) }
        }
        .sheet(isPresented: $showHistoryRestore) {
            NavigationStack {
                HistoryKeySettingsView()
                    .environmentObject(auth)
                    .environment(\.historyBackupAccess, services.historyBackupAccess)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showHistoryRestore = false } } }
            }
        }
        .onReceive(auth.$state) { _ in
            if auth.sessionGeneration != services.sessionGeneration { incomingFile.close(discardBatch: false); services.shutdown() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
            checkSharedInbox()
            services.shareProtectedDataAvailable()
        }
        .safeAreaInset(edge: .top) {
            if let notice = services.shareJobNotice {
                HStack {
                    Text(notice).font(.footnote)
                    Button(UnyriL10n.string("Close")) { services.shareJobNotice = nil }.frame(minWidth: 44, minHeight: 44)
                }.padding(.horizontal, 16).background(.ultraThinMaterial).accessibilityElement(children: .contain)
            }
        }
        .onDisappear {
            // A full-screen call can hide this view without ending its account.
            if auth.sessionGeneration != services.sessionGeneration {
                services.shutdown()
            }
        }
        .onChange(of: scenePhase) { phase in
            services.foregroundBanner?.isForeground = phase == .active
            if phase == .active {
                UnyriLocalNotificationPreferences.reconcile()
                checkSharedInbox()
                services.startRealtime()
            } else if phase == .background {
                services.stopRealtime()
            } else {
                services.cancelForegroundWork()
            }
        }
    }
}

/// Set by the signed-in account services; runs a catch-up sync for a background push.
@MainActor enum UnyriBackgroundPush {
    static var handler: (() async -> Void)?
    /// Set at launch: restores the session and its services (which install `handler`).
    static var restore: (() async -> Void)?
}

/// Local reminder routing survives a cold launch; payload contains IDs only.
final class ChatReminderDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var privacyCovers: [UIView] = []
    private var privacyObservers: [NSObjectProtocol] = []
    /// Synchronous UIKit cover before iOS captures the task-switcher snapshot.
    /// Covers every scene/window, including sheets and full-screen media/calls.
    @MainActor private func concealWindows() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows where !window.isHidden && !privacyCovers.contains(where: { $0.superview === window }) {
                let cover = UIView(frame: window.bounds)
                cover.backgroundColor = .black
                cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                cover.isUserInteractionEnabled = false
                cover.accessibilityElementsHidden = true
                window.addSubview(cover)
                privacyCovers.append(cover)
            }
        }
    }
    @MainActor private func revealWindows() {
        let active = privacyCovers.filter { $0.window?.windowScene?.activationState == .foregroundActive }
        for cover in active { cover.removeFromSuperview() }
        privacyCovers.removeAll { active.contains($0) }
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        // UIKit can ask without a window during presentation invalidation.
        // Resolve the foreground key scene instead of silently locking that
        // query to portrait while its full call already owns a rotation lease.
        let scene = window?.windowScene ?? application.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive && $0.windows.contains(where: \.isKeyWindow) })
        return UnyriMediaOrientation.mask(for: scene)
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UnyriDiag.appLaunch(background: application.applicationState == .background, durationMs: UnyriProcessClock.sinceStartMs())
        privacyObservers = [
            NotificationCenter.default.addObserver(forName: UIScene.willDeactivateNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.concealWindows() }
            },
            NotificationCenter.default.addObserver(forName: UIScene.didActivateNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.revealWindows() }
            },
            NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.concealWindows() }
            },
            NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.revealWindows() }
            }
        ]
        UNUserNotificationCenter.current().delegate = self
        UnyriPushNotificationCoordinator.shared.start(application)
        // A video note interrupted together with the previous process.
        VideoNoteRecorder.removeStaleTakes()
        return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        UnyriDiag.push(.apnsRegistered)
        UnyriPushNotificationCoordinator.shared.receivedAPNs(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        UnyriDiag.push(.apnsFailed, reason: abs((error as NSError).code))
        UnyriPushNotificationCoordinator.shared.failedAPNs()
    }
    /// A message alert also wakes a suspended app (content-available): it syncs, which
    /// stores the message and acknowledges delivery, so the sender sees two ticks while
    /// this phone is locked, like Telegram/WhatsApp.
    /// A locked phone is fine: it needs only AfterFirstUnlock Keychain items and default-class
    /// files, so it never waits for isProtectedDataAvailable. Before the first unlock bootstrap
    /// leaves the session unavailable (never signed out) and the wake ends with no data.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        if userInfo["event"] as? String == "session_check" {
            var completed = false
            let finish: @MainActor () -> Void = {
                guard !completed else { return }
                completed = true
                completionHandler(.newData)
            }
            let checking = Task { @MainActor in
                if UnyriBackgroundPush.handler == nil { await UnyriBackgroundPush.restore?() }
                await UnyriPushNotificationCoordinator.shared.checkSavedSessions()
                finish()
            }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                finish()
                checking.cancel()
            }
            return
        }
        guard userInfo["event"] as? String == "new_message" else { completionHandler(.noData); return }
        if UnyriPushNotificationCoordinator.shared.absorbInactiveWake(userInfo) { completionHandler(.noData); return }
        var finished = false
        let finish: @MainActor (UIBackgroundFetchResult) -> Void = { result in
            guard !finished else { return }
            finished = true
            completionHandler(result)
        }
        let wakeStarted = ProcessInfo.processInfo.systemUptime
        Task { @MainActor in
            // When iOS launched the app for this push, no view has restored the session yet.
            if UnyriBackgroundPush.handler == nil { await UnyriBackgroundPush.restore?() }
            guard let handler = UnyriBackgroundPush.handler else { finish(.noData); return }
            await handler()
            UnyriDiag.push(.messageWake, durationMs: (ProcessInfo.processInfo.systemUptime - wakeStarted) * 1000)
            finish(.newData)
        }
        // iOS allows ~30 s; never hold the wake longer than that.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            finish(.newData)
        }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let values = response.notification.request.content.userInfo
        if response.notification.request.trigger is UNPushNotificationTrigger {
            if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                Task { @MainActor in UnyriPushNotificationCoordinator.shared.tapped(values) }
            }
            completionHandler(); return
        }
        let kind = values["kind"] as? String ?? ""
        if ["chat_reminder", "foreground_message_sound", "account_message"].contains(kind) {
            guard let owner = values["owner"] as? String, UUID(uuidString: owner) != nil,
                  let chat = values["chat"] as? String, UUID(uuidString: chat) != nil,
                  let message = values["message"] as? String, UUID(uuidString: message) != nil else {
                completionHandler(); return
            }
            if kind != "account_message", NotificationRecipientPolicy.matches(owner: owner, current: SessionStore.active().currentUser()?.id) {
                let route = ["owner": owner, "chat": chat, "message": message]
                UserDefaults.standard.set(route, forKey: "chatReminderRoute")
                DispatchQueue.main.async { NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil) }
            } else if kind != "foreground_message_sound" {
                // The other signed-in account: switch to it, then open the chat.
                Task { @MainActor in UnyriAccountManager.shared.openFromNotification(owner: owner, chat: chat, message: message) }
            }
        }
        completionHandler()
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let values = notification.request.content.userInfo
        if values["event"] as? String == "incoming_call" || values["type"] as? String == "call.incoming" {
            completionHandler([]); return
        }
        if notification.request.trigger is UNPushNotificationTrigger {
            Task { @MainActor in completionHandler(UnyriPushNotificationCoordinator.shared.presentation(values)) }
            return
        }
        let preferences = UnyriNotificationPreferencesStorage.load()
        if values["kind"] as? String == "foreground_message_sound" {
            Task { @MainActor in
                let current = UnyriForegroundMessageSound.permitsPresentation(owner: values["owner"] as? String,
                    generation: (values["session_generation"] as? NSNumber)?.uint64Value)
                completionHandler(current && UnyriNotificationPreferencesStore.shared.value.allowsInAppSound ? [.sound] : [])
            }
            return
        }
        if values["kind"] as? String == "chat_reminder" &&
            !NotificationRecipientPolicy.matches(owner: values["owner"] as? String, current: SessionStore.active().currentUser()?.id) {
            // The other signed-in account's reminder is shown (tapping it switches accounts).
            let owner = values["owner"] as? String ?? ""
            Task { @MainActor in
                completionHandler(UnyriAccountManager.shared.account(owner: owner) != nil ? [.banner, .list] : [])
            }
            return
        }
        if values["kind"] as? String == "chat_reminder" {
            guard preferences.allowsReminders else { completionHandler([]); return }
            completionHandler(preferences.allowsReminderSound ? [.banner, .sound] : [.banner]); return
        }
        if values["kind"] as? String == "account_message" { completionHandler([.badge]); return }
        // Foreground message presentation belongs to the in-app banner policy.
        // Reminders and security/call notifications keep their existing behavior.
        if values["event"] as? String == "new_message" || values["type"] as? String == "message.new" {
            completionHandler([.badge])
        } else { completionHandler([.banner, .sound]) }
    }
}


/// Milliseconds since this process started (launch timing for diagnostics).
enum UnyriProcessClock {
    static func sinceStartMs() -> Double? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return nil }
        let start = info.kp_proc.p_un.__p_starttime
        let started = Double(start.tv_sec) + Double(start.tv_usec) / 1_000_000
        let value = (Date().timeIntervalSince1970 - started) * 1000
        return value.isFinite && value >= 0 && value < 600_000 ? value : nil
    }
}
