import SwiftUI

@MainActor
final class IncomingIdentityLinks: ObservableObject {
    static let shared = IncomingIdentityLinks()
    @Published var target: PublicIdentityPolicy.Link?
    @Published var serverInvite: SelfHostingPolicy.Invite?
    @Published var presentationID = UUID()
    @Published var presented = false
    @discardableResult
    func receiveServerInvite(_ raw: String) -> Bool {
        guard let parsed = SelfHostingPolicy.parseInvite(raw, allowLocalHTTP: SelfHostingLinks.allowLocalHTTP) else { return false }
        target = nil; serverInvite = parsed; presentationID = UUID(); presented = true
        return true
    }
    func receive(_ raw: String) {
        if receiveServerInvite(raw) { return }
        serverInvite = nil; target = PublicIdentityPolicy.parse(raw); presentationID = UUID(); presented = true
    }
    func clear() { serverInvite = nil; target = nil }
}

@MainActor
struct IdentityLinkPresentation: ViewModifier {
    @ObservedObject private var links = IncomingIdentityLinks.shared
    func body(content: Content) -> some View {
        content.sheet(isPresented: $links.presented, onDismiss: { links.clear() }) {
            Group {
                if let invite = links.serverInvite { JoinServerView(invite: invite) }
                else { IdentityLinkView(target: links.target) }
            }.id(links.presentationID)
        }
    }
}

/// Arrival only parses. Each connection/account/contact action needs a tap.
/// This sheet uses a new no-redirect client bound to the authenticated origin.
@MainActor
struct IdentityLinkView: View {
    let target: PublicIdentityPolicy.Link?
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var accounts = UnyriAccountManager.shared
    @State private var approved = false
    @State private var profile: DiscoveredProfile?
    @State private var busy = false
    @State private var message: String?
    @State private var login = ""
    @State private var password = ""
    @State private var added = false

    private var sameOrigin: Bool {
        guard let target, auth.currentUser != nil else { return false }
        return PublicIdentityPolicy.origin(auth.authenticatedServerURL.absoluteString) == target.origin
    }
    private var known: Bool {
        guard let target else { return false }
        return PublicIdentityPolicy.origin(auth.authenticatedServerURL.absoluteString) == target.origin ||
            accounts.records.contains { PublicIdentityPolicy.origin($0.origin) == target.origin } ||
            UserDefaults.standard.stringArray(forKey: "login2.approved-origins")?.contains(target.origin) == true
    }
    private var requiresApproval: Bool {
        guard let target else { return false }
        let http = target.origin.hasPrefix("http://")
        let consent = URL(string: target.origin).map(AppConfig.hasIdentityHTTPConsent) ?? false
        return !approved && (!known || (http && !consent))
    }
    var body: some View {
        NavigationStack {
            UnyriSettingsPage("Public profile") {
                if let target {
                    UnyriSettingsGroup("Server") {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(target.origin).font(.body.monospaced()).textSelection(.enabled)
                                .accessibilityIdentifier("login2-link-origin")
                            if target.origin.hasPrefix("http://") {
                                Text("HTTP is unencrypted. Your password and connection metadata can be intercepted. Installation protection requires HTTPS.")
                                    .font(.footnote).foregroundStyle(UnyriColors.danger)
                            }
                            if requiresApproval {
                                Text("This is an unknown server. Confirm its address before connecting. Your other accounts' credentials will not be sent to it.")
                                Button("Trust this server and continue") {
                                    approved = true
                                    var origins = UserDefaults.standard.stringArray(forKey: "login2.approved-origins") ?? []
                                    if !origins.contains(target.origin) { origins.append(target.origin) }
                                    UserDefaults.standard.set(Array(origins.suffix(100)), forKey: "login2.approved-origins")
                                }.accessibilityIdentifier("login2-link-trust")
                            }
                        }.padding(16)
                    }
                    if !requiresApproval {
                        if sameOrigin {
                            if auth.needsRegistrationRecovery {
                                Text("Finish saving your recovery key before opening this profile.")
                            } else {
                                profileContent(target)
                            }
                        } else {
                            UnyriSettingsGroup("Choose an account on this server") {
                                VStack(alignment: .leading, spacing: 12) {
                                    ForEach(accounts.records.filter { PublicIdentityPolicy.origin($0.origin) == target.origin }) { record in
                                        Button(record.displayName) { run { await accounts.switchTo(record) } }
                                    }
                                    Text("Sign in with an account on this server. Accounts on different servers are separate.")
                                        .font(.footnote)
                                    TextField("Private login", text: $login).textInputAutocapitalization(.never).autocorrectionDisabled().unyriFieldStyle()
                                    SecureField("Password", text: $password).textContentType(.password).unyriFieldStyle()
                                    Button("Sign in on this server") { run {
                                        if auth.currentUser != nil {
                                            guard accounts.canAddAccount else { throw APIError.missingSession }
                                            await accounts.beginAddAccount()
                                        }
                                        guard auth.currentUser == nil else { throw APIError.missingSession }
                                        try auth.selectServer(target.origin)
                                        try await auth.login(username: login, password: password)
                                        password = ""
                                    } }.disabled(login.isEmpty || password.isEmpty)
                                }.padding(16)
                            }
                        }
                    }
                } else {
                    Text("Invalid profile link. Use a number or @username link with an explicit server.")
                }
                if busy { ProgressView() }
                if let message { Text(message).font(.footnote).foregroundStyle(UnyriColors.secondaryText) }
            }.disabled(busy)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onChange(of: auth.sessionGeneration) { _ in profile = nil; added = false; message = nil; password = "" }
        .onChange(of: scenePhase) { phase in if phase != .active { profile = nil; added = false; password = "" } }
        .onDisappear { password = "" }
        .modifier(UnyriAppLockProtection())
    }
    @ViewBuilder private func profileContent(_ target: PublicIdentityPolicy.Link) -> some View {
        if let profile {
            UnyriSettingsGroup {
                VStack(alignment: .leading, spacing: 12) {
                    Text(profile.contact.visibleName).font(.title2.bold())
                    Text(PublicIdentityPolicy.formattedNumber(profile.unyriNumber)).monospacedDigit()
                    if let username = profile.publicUsername { Text("@" + username) }
                    if let bio = profile.bio { Text(bio).font(.subheadline) }
                    if profile.id != auth.currentUser?.id {
                        Button(added ? "Contact added" : "Add contact") { run {
                            // Resolve the UUID again immediately before the explicit mutation.
                            let api = try currentAPI(target)
                            let generation = auth.sessionGeneration
                            let token = try await auth.currentAccessToken()
                            guard sameOrigin, generation == auth.sessionGeneration, scenePhase == .active else { throw CancellationError() }
                            _ = try await api.publicProfile(accessToken: token, userID: profile.id)
                            guard sameOrigin, generation == auth.sessionGeneration, scenePhase == .active else { throw CancellationError() }
                            try await api.setContact(accessToken: token, userID: profile.id, contact: true)
                            guard sameOrigin, generation == auth.sessionGeneration else { throw CancellationError() }
                            added = true
                            NotificationCenter.default.post(name: .login2ContactChanged, object: UUID(),
                                userInfo: ["owner": auth.currentUser?.id ?? ""])
                        } }.disabled(added).accessibilityIdentifier("login2-link-add")
                    }
                }.padding(16)
            }
        }
        Button("Open profile") { run {
            let api = try currentAPI(target), generation = auth.sessionGeneration
            let token = try await auth.currentAccessToken()
            guard sameOrigin, generation == auth.sessionGeneration, scenePhase == .active else { throw CancellationError() }
            let result = try await api.lookup(accessToken: token, target: target.target)
            guard sameOrigin, generation == auth.sessionGeneration, scenePhase == .active else { throw CancellationError() }
            profile = result; added = false
        } }.accessibilityIdentifier("login2-link-resolve")
    }
    private func currentAPI(_ target: PublicIdentityPolicy.Link) throws -> UsersAPI {
        guard sameOrigin, !requiresApproval, scenePhase == .active, let url = URL(string: target.origin) else { throw APIError.missingSession }
        let generation = auth.sessionGeneration
        return UsersAPI(client: APIClient(baseURL: url, preventRedirects: true, ephemeral: true,
            onAuthenticatedRequestError: { error, token in
                await auth.handleAuthenticatedRequestError(error, generation: generation, requestToken: token)
            }))
    }
    private func run(_ operation: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; message = nil
        Task { @MainActor in
            defer { busy = false }
            do { try await operation() }
            catch is CancellationError { }
            catch { message = IdentityErrorText.message(error) }
        }
    }
}

extension Notification.Name { static let login2ContactChanged = Notification.Name("UNYRILogin2ContactChanged") }
