import SwiftUI

enum SelfHostingLinks {
    static var allowLocalHTTP: Bool {
        #if DEBUG || UNYRI_ALPHA_LAN
        return true
        #else
        return false
        #endif
    }
}

/// Reuses the existing camera scanner; scanning never changes the selected origin.
@MainActor
struct JoinServerScannerButton: View {
    @EnvironmentObject private var auth: AuthRepository
    @State private var scanning = false
    @State private var failed = false
    @State private var generation: UInt64 = 0
    var body: some View {
        VStack {
            Button("Scan server invitation") { generation = auth.sessionGeneration; scanning = true }
            if failed { Text("Invalid server invitation. Ask for a new link or QR code.").font(.footnote) }
        }
        .sheet(isPresented: $scanning, onDismiss: offerScanned) {
            NavigationStack {
                HistoryQRScanner { raw in
                    guard generation == auth.sessionGeneration else { scanning = false; return }
                    scanned = raw; scanning = false
                } failure: { failed = true; scanning = false }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { scanning = false } } }
            }
        }
    }
    @State private var scanned: String?
    private func offerScanned() {
        guard let raw = scanned else { return }
        scanned = nil
        guard generation == auth.sessionGeneration else { return }
        failed = !IncomingIdentityLinks.shared.receiveServerInvite(raw)
    }
}

@MainActor
struct JoinServerView: View {
    let invite: SelfHostingPolicy.Invite
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var approved = false
    @State private var address = ""
    @State private var busy = false
    @State private var message: String?
    @State private var generation: UInt64 = 0
    @State private var confirmTask: Task<Void, Never>?
    var body: some View {
        NavigationStack {
            if approved {
                RegisterView(serverAddress: $address, onSignIn: { dismiss() }, initialInvite: invite)
            } else {
                UnyriSettingsPage("Join a server") {
                    Text(invite.origin).font(.body.monospaced()).textSelection(.enabled)
                        .accessibilityIdentifier("sh3-join-origin")
                    Text("Confirm this server address before continuing. Accounts on different servers are separate.")
                    if invite.origin.hasPrefix("http://") { Text("HTTP is unencrypted. Your password and connection metadata can be intercepted. Installation protection requires HTTPS.") }
                    if auth.currentUser != nil { Text("A new account will be added. You can return to your existing account in Settings.") }
                    Button("Use this server") { confirm() }.disabled(busy)
                        .accessibilityIdentifier("sh3-join-confirm")
                    if busy { ProgressView() }
                    if let message { Text(message).font(.footnote) }
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
            }
        }
        .task { generation = auth.sessionGeneration }
        .interactiveDismissDisabled(busy)
        .onDisappear { confirmTask?.cancel(); confirmTask = nil }
        .onChange(of: auth.currentUser?.id) { next in if approved, next != nil { dismiss() } }
        .onChange(of: scenePhase) { phase in if phase == .background, !busy { dismiss() } }
        .modifier(UnyriAppLockProtection())
    }
    private func confirm() {
        guard !busy, generation == auth.sessionGeneration, scenePhase == .active else { dismiss(); return }
        busy = true
        confirmTask = Task { @MainActor in
            defer { busy = false }
            do {
                if auth.currentUser != nil {
                    guard UnyriAccountManager.shared.canAddAccount else { throw APIError.missingSession }
                    await UnyriAccountManager.shared.beginAddAccount()
                }
                guard !Task.isCancelled, auth.currentUser == nil, scenePhase == .active else { throw CancellationError() }
                if invite.origin.hasPrefix("http://") {
                    var origins = UserDefaults.standard.stringArray(forKey: "login2.approved-origins") ?? []
                    if !origins.contains(invite.origin) { origins.append(invite.origin) }
                    UserDefaults.standard.set(Array(origins.suffix(100)), forKey: "login2.approved-origins")
                }
                try auth.selectServer(invite.origin)
                address = invite.origin; approved = true
            } catch is CancellationError { dismiss() }
            catch { message = UnyriL10n.string("Could not switch servers. Try again from Settings.") }
        }
    }
}

/// The input never contains dashes. The preview alone groups the code.
@MainActor
struct OwnerSetupView: View {
    let onClaimed: (UserProfile) -> Void
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var users: UsersRepository
    @Environment(\.scenePhase) private var scenePhase
    @State private var code = ""
    @State private var busy = false
    @State private var claimed = false
    @State private var unavailable = false
    @State private var scanning = false
    @State private var captured = UIScreen.main.isCaptured
    @State private var message: String?
    @State private var generation: UInt64 = 0
    @State private var scanGeneration: UInt64 = 0
    @State private var operationTask: Task<Void, Never>?
    var body: some View {
        UnyriSettingsPage("Become the owner") {
            Text(auth.authenticatedServerURL.absoluteString).font(.footnote.monospaced())
            UnyriSettingsNote("Enter the 16-character owner code from your server installer. It works only once. Then set up administrator sign-in protection.")
            if !claimed, !unavailable {
                if scenePhase == .active, !captured {
                    OwnerCodeInput(value: $code).frame(height: 48).unyriFieldStyle().privacySensitive()
                        .accessibilityIdentifier("sh3-owner-code")
                    Text(SelfHostingPolicy.ownerDisplay(code)).font(.body.monospaced()).privacySensitive()
                        .accessibilityIdentifier("sh3-owner-code-groups")
                    Button("Scan owner code") { scanGeneration = auth.sessionGeneration; scanning = true }
                        .disabled(busy).accessibilityIdentifier("sh3-owner-scan")
                } else { Text("Stop screen sharing or recording to enter the owner code.") }
                Button("Become the owner") { submit() }
                    .unyriProminentAction().disabled(busy || captured || scenePhase != .active || SelfHostingPolicy.ownerCode(code) == nil)
            }
            if claimed { Button("Continue to administration") { submit() }.disabled(busy) }
            if busy { ProgressView() }
            if let message { Text(message).font(.footnote).foregroundStyle(UnyriColors.secondaryText) }
        }
        .task { generation = auth.sessionGeneration }
        .sheet(isPresented: $scanning) {
            NavigationStack {
                HistoryQRScanner { raw in
                    scanning = false
                    guard scanGeneration == auth.sessionGeneration, scenePhase == .active, !captured else { return }
                    guard let normalized = SelfHostingPolicy.ownerCode(raw) else {
                        message = UnyriL10n.string(SelfHostingPolicy.refusalKey("setup_invalid")!); return
                    }
                    code = normalized
                } failure: { scanning = false; message = UnyriL10n.string("The camera could not scan this code. Close other camera apps and try again.") }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { scanning = false } } }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captured = UIScreen.main.isCaptured
            if captured { code = ""; scanning = false }
        }
        .onChange(of: scenePhase) { phase in if phase != .active { code = ""; scanning = false } }
        .onChange(of: auth.sessionGeneration) { _ in code = ""; scanning = false; unavailable = true }
        .onDisappear { operationTask?.cancel(); operationTask = nil; code = ""; scanning = false }
    }
    private func submit() {
        guard !busy, generation == auth.sessionGeneration, scenePhase == .active else { return }
        let submitted = code; code = ""; busy = true; message = nil
        operationTask = Task { @MainActor in
            defer { busy = false; operationTask = nil }
            do {
                if !claimed { try await auth.claimOwner(submitted); claimed = true }
                let refreshed = try await users.getMe()
                guard generation == auth.sessionGeneration, !Task.isCancelled, scenePhase == .active else { return }
                guard refreshed.adminRole == "owner" else { throw APIError.decoding("Owner role refresh") }
                onClaimed(refreshed)
            } catch is CancellationError { }
            catch {
                guard generation == auth.sessionGeneration else { return }
                let key: String
                if case APIError.server(let refusal, _) = error, let mapped = SelfHostingPolicy.refusalKey(refusal) {
                    key = mapped
                    if refusal == "setup_not_available" { unavailable = true; await auth.refreshOwnerSetupAvailability() }
                } else { key = "Could not complete owner setup. Try again. If ownership was accepted, continue to administration." }
                message = UnyriL10n.string(key)
            }
        }
    }
}

/// UIKit preserves the plain-text selection during normalization, including paste
/// and middle edits. SwiftUI never writes grouped text back into the editor.
@MainActor
private struct OwnerCodeInput: UIViewRepresentable {
    @Binding var value: String
    func makeCoordinator() -> Coordinator { Coordinator(value: $value) }
    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        field.placeholder = UnyriL10n.string("Owner code")
        field.autocapitalizationType = .allCharacters; field.autocorrectionType = .no
        field.keyboardType = .asciiCapable; field.font = .monospacedSystemFont(ofSize: 18, weight: .regular)
        field.textColor = .label; field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        return field
    }
    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.value = $value
        if field.text != value { field.text = value }
    }
    @MainActor final class Coordinator: NSObject, UITextFieldDelegate {
        var value: Binding<String>
        init(value: Binding<String>) { self.value = value }
        @objc func changed(_ field: UITextField) { value.wrappedValue = field.text ?? "" }
        func textField(_ field: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
            let old = field.text ?? ""
            guard let swiftRange = Range(range, in: old) else { return false }
            let proposed = old.replacingCharacters(in: swiftRange, with: string)
            let prefix = String(old[..<swiftRange.lowerBound]) + string
            let normalized = SelfHostingPolicy.ownerInput(proposed)
            // Ordinary typing stays entirely in UIKit, including its cursor/undo.
            if proposed == normalized { return true }
            let cursor = min(SelfHostingPolicy.ownerInput(prefix).utf16.count, normalized.utf16.count)
            field.text = normalized
            if let position = field.position(from: field.beginningOfDocument, offset: cursor) {
                field.selectedTextRange = field.textRange(from: position, to: position)
            }
            value.wrappedValue = normalized
            return false
        }
    }
}
