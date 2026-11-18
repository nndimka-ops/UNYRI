import SwiftUI
import UniformTypeIdentifiers

private struct SignupRecoveryDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    let text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = "" }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// The nonsecret pending flag survives process death. The key/password never
/// enter defaults, logs, screen caches, or the account registry.
@MainActor
struct SignupRecoveryView: View {
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var scenePhase
    @State private var identity: OwnerIdentity?
    @State private var code = ""
    @State private var password = ""
    @State private var confirmed = false
    @State private var busy = false
    @State private var error: String?
    @State private var exporting = false
    @State private var copiedChangeCount: Int?
    @State private var secretEpoch: UInt64 = 0
    @State private var captured = UIScreen.main.isCaptured

    var body: some View {
        NavigationStack {
            UnyriSettingsPage("Save your recovery key") {
                UnyriSettingsGroup("Your public number") {
                    VStack(alignment: .leading, spacing: 12) {
                        if let loadedIdentity = identity {
                            Text(PublicIdentityPolicy.formattedNumber(loadedIdentity.unyriNumber))
                                .font(.largeTitle.monospacedDigit().bold()).textSelection(.enabled)
                                .accessibilityIdentifier("login2-signup-number")
                            Button("Copy number") { UIPasteboard.general.string = loadedIdentity.unyriNumber }
                        }
                        Text("Your number never changes. Your login stays private. You can choose an optional @username later.")
                            .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    }.padding(16)
                }
                UnyriSettingsGroup("Recovery key") {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Save this key somewhere safe before continuing. Anyone with your private login and this key can reset your password. Keep them separate.")
                            .font(.subheadline)
                        Text("This server's account recovery key resets your password and signs out all phones. Encrypted history still needs its separate history recovery key.")
                            .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        if !code.isEmpty {
                            Text(scenePhase == .active && !captured ? code : "••••••••")
                                .font(.system(.body, design: .monospaced)).textSelection(.enabled).privacySensitive()
                                .accessibilityIdentifier("login2-signup-recovery")
                            if captured { Text("Stop screen sharing or recording to view your recovery key.").font(.footnote) }
                            Button("Copy recovery key") {
                                guard scenePhase == .active, !captured else { return }
                                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: code]],
                                    options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
                                copiedChangeCount = UIPasteboard.general.changeCount
                            }.accessibilityIdentifier("login2-recovery-copy")
                            Button("Save to Files") { if scenePhase == .active && !captured { exporting = true } }
                                .accessibilityIdentifier("login2-recovery-files")
                            Toggle("I saved my recovery key", isOn: $confirmed)
                                .accessibilityIdentifier("login2-recovery-saved")
                            Button("Continue") {
                                clearSecrets(); auth.confirmSignupRecovery()
                            }.unyriProminentAction().disabled(!confirmed || identity == nil || captured || scenePhase != .active)
                                .accessibilityIdentifier("login2-recovery-confirm")
                        } else if !busy {
                            SecureField("Current password", text: $password).textContentType(.password).unyriFieldStyle()
                            Button("Create recovery key") { Task { @MainActor in await prepare() } }.disabled(password.isEmpty)
                            Text("If a response was lost, creating a key replaces the previous key. Save the new one.")
                                .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        }
                        if busy { ProgressView() }
                        if let errorMessage = error { Text(errorMessage).foregroundStyle(UnyriColors.danger).font(.footnote) }
                    }.padding(16)
                }
                Button("Sign out") { clearSecrets(); Task { @MainActor in await auth.logout() } }
            }
        }
        .task { await prepare() }
        .fileExporter(isPresented: $exporting, document: SignupRecoveryDocument(text: code), contentType: .plainText,
                      defaultFilename: "UNYRI-recovery-key") { result in
            Task { @MainActor in
                if case .failure = result { error = UnyriL10n.string("Could not save the file. Try again or copy the key.") }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            let next = UIScreen.main.isCaptured
            if captured != next { captured = next }
        }
        .onChange(of: scenePhase) { next in
            if next == .background { secretEpoch &+= 1; clearSecrets(); auth.discardSignupPassword() }
        }
        // Not discardSignupPassword(): the gate is re-created when the session generation changes,
        // and the new instance must still create the key automatically (lead, 18 Nov walk). The
        // repository drops the password on confirm, consumption, sign-out, account change and background.
        .onDisappear { secretEpoch &+= 1; clearSecrets() }
    }
    private func clearSecrets() {
        code = ""; password = ""; confirmed = false
        if let ownedClipboardRevision = copiedChangeCount, UIPasteboard.general.changeCount == ownedClipboardRevision { UIPasteboard.general.items = [] }
        copiedChangeCount = nil
    }
    private func prepare() async {
        guard !busy else { return }
        busy = true; error = nil
        let operation = secretEpoch
        let generation = auth.sessionGeneration
        let server = auth.authenticatedServerURL
        defer { busy = false; password = "" }
        do {
            let token = try await auth.currentAccessToken()
            guard generation == auth.sessionGeneration else { return }
            let loadedIdentity: OwnerIdentity
            do {
                loadedIdentity = try await UsersAPI(client: APIClient(baseURL: server, preventRedirects: true, ephemeral: true)).identity(accessToken: token)
            } catch APIError.http(let status) where status == 404 || status == 405 {
                guard generation == auth.sessionGeneration, operation == secretEpoch else { return }
                clearSecrets(); auth.confirmSignupRecovery(); return
            } catch let failure as APIStatusError where failure.status == 404 || failure.status == 405 {
                guard generation == auth.sessionGeneration, operation == secretEpoch else { return }
                clearSecrets(); auth.confirmSignupRecovery(); return
            }
            guard generation == auth.sessionGeneration, operation == secretEpoch, scenePhase == .active, !Task.isCancelled else { return }
            identity = loadedIdentity
            // Without a retained or typed password the screen simply asks for it; that is not an error.
            guard !password.isEmpty || auth.hasSignupPassword else { return }
            let issued = try await auth.issueSignupRecovery(password: password)
            guard generation == auth.sessionGeneration, operation == secretEpoch, scenePhase == .active, !Task.isCancelled else { return }
            code = issued
        } catch is CancellationError { }
        catch {
            guard generation == auth.sessionGeneration, operation == secretEpoch, !Task.isCancelled else { return }
            #if targetEnvironment(simulator)
            // Simulator walkthrough diagnostics only: the failure kind, never the password or key.
            NSLog("UNYRI_SIGNUP_RECOVERY failed: %@", String(describing: error))
            #endif
            self.error = UnyriL10n.string("Could not prepare recovery. Enter your password and retry. Your account is already registered.")
        }
    }
}
