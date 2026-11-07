import SwiftUI
import CryptoKit

@MainActor
private final class HistoryKeySettingsModel: ObservableObject {
    @Published var state: HistoryBackupState?
    @Published var keySaved = false
    @Published var automatic = false
    @Published var wifiOnly = true
    @Published var replacingKey = false
    @Published var busy = false
    @Published var error: String?
    @Published var progress: String?
    private let access: HistoryBackupAccess
    @Published var draftText = ""
    @Published var confirmation = ""
    @Published var entered = ""
    private var draft: Data?
    private var draftRevision: Int64?
    private let owner: String
    private let fence: () throws -> Void
    private let remote: HistoryBackupAPI
    private var coordinator: HistoryBackupCoordinator?
    private var native: HistoryNativeOwner?
    private var job: Task<Void, Never>?
    private var expiry: Task<Void, Never>?
    private var copiedChangeCount: Int?

    init(auth: AuthRepository, access: HistoryBackupAccess) throws {
        self.access = access
        guard let owner = auth.currentUser?.id else { throw APIError.missingSession }
        let generation = auth.sessionGeneration
        let base = AppConfig.apiBaseURL
        let fence: () throws -> Void = {
            try Task.checkCancellation()
            guard auth.sessionGeneration == generation, auth.currentUser?.id == owner,
                  AppConfig.apiBaseURL == base else { throw APIError.missingSession }
        }
        self.owner = owner; self.fence = fence
        self.remote = HistoryBackupAPI(baseURL: base, accessToken: {
            try await MainActor.run { try fence() }
            let token = try await auth.currentAccessToken()
            try await MainActor.run { try fence() }
            return token
        }, assertCurrent: { try await MainActor.run { try fence() } })
    }
    func refresh() { run {} }
    func close() { job?.cancel(); expiry?.cancel(); clearDraft(); entered = "" }
    private func keys(_ state: HistoryBackupState) throws -> HistoryRecoveryKeys {
        try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: owner)
    }
    private func load() async throws {
        try fence()
        if coordinator == nil {
            let first = try await remote.status(); try fence()
            try HistoryArchive.validateId(first.serverId)
            let local = access.owner(server: first.serverId, remote: remote)
            native = local
            coordinator = HistoryBackupCoordinator(server: first.serverId, owner: owner, remote: remote,
                local: local, cipher: .platformCrypto)
        }
        guard let coordinator else { throw APIError.missingSession }
        let latest = try await coordinator.status(); try fence()
        keySaved = try keys(latest).load() != nil
        state = latest
        let preferences = HistoryAutoPreferences(server: latest.serverId, owner: owner)
        preferences.uploadsAllowed = latest.desiredEnabled
        UserDefaults.standard.set(latest.serverId, forKey: "history-auto-server:" + AppConfig.apiBaseURL.absoluteString + ":" + owner)
        automatic = preferences.enabled; wifiOnly = preferences.wifiOnly
        HistoryAutomaticBackup.shared.schedule(enabled: preferences.enabled && latest.desiredEnabled,
            earliest: HistoryAutoPolicy.next(lastSuccess: preferences.lastSuccess, now: Date().timeIntervalSince1970))
    }
    private func run(_ operation: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        job = Task { [self] in
            var acquired = false
            defer {
                busy = false; progress = nil; job = nil
                if acquired { access.lease.end() }
            }
            do {
                // Opening settings must remain possible during a long automatic
                // copy, so the user can inspect or disable the schedule.
                await HistoryAutomaticBackup.shared.stopCurrent()
                try fence(); try access.lease.begin(); acquired = true
                try await operation(); try await load()
            }
            catch is CancellationError { self.error = "Operation cancelled. Your previous confirmed backup is kept. You can retry safely." }
            catch { self.error = HistoryOperationErrorPresentation.message(for: error) }
            if acquired { await native?.cleanTemporaryFiles() }
        }
    }
    func createDraft() {
        guard !busy, !keySaved, let state, state.active == nil, state.staged == nil else { return }
        do {
            try fence(); clearDraft()
            let key = HistoryBackupCrypto.generateRecoveryKey()
            draftText = try HistoryBackupCrypto.formatRecoveryKey(key); draft = key; draftRevision = state.revision
            expiry = Task { [weak self] in
                do { try await Task.sleep(nanoseconds: 600_000_000_000) } catch { return }
                self?.clearDraft(); self?.error = "The unsaved key draft expired. Prepare a new key."
            }
        } catch { self.error = "The operation could not be completed. Check your connection and recovery key, then refresh. Existing keys are never replaced automatically." }
    }
    func clearDraft() {
        expiry?.cancel(); expiry = nil; draft = nil; draftRevision = nil; draftText = ""; confirmation = ""
        if let count = copiedChangeCount, UIPasteboard.general.changeCount == count { UIPasteboard.general.items = [] }
        copiedChangeCount = nil
    }
    var canConfirm: Bool { !draftText.isEmpty && confirmation.uppercased() == String(draftText.suffix(8)) }
    func confirmDraft() {
        guard canConfirm, let key = draft, let revision = draftRevision, let coordinator else { return }
        run { [self] in
            let latest = try await coordinator.status(); try fence()
            guard latest.revision == revision, latest.active == nil, latest.staged == nil else { throw HistoryBackupCoordinator.Failure.changed }
            try keys(latest).saveConfirmed(key)
            clearDraft()
        }
    }
    func setAutomatic(_ enabled: Bool) {
        guard let state, !busy else { return }
        do {
            try fence()
            if enabled { guard state.desiredEnabled, try keys(state).load() != nil else { throw APIError.missingSession } }
            let preferences = HistoryAutoPreferences(server: state.serverId, owner: owner)
            preferences.enabled = enabled; automatic = enabled
            HistoryAutomaticBackup.shared.schedule(enabled: enabled, earliest: HistoryAutoPolicy.next(lastSuccess: preferences.lastSuccess, now: Date().timeIntervalSince1970))
            if enabled { HistoryAutomaticBackup.shared.foreground() }
        } catch { self.error = "Save a verified recovery key and allow uploads before enabling automatic backup." }
    }
    func setWifiOnly(_ enabled: Bool) {
        guard let state else { return }
        guard (try? fence()) != nil else { return }
        HistoryAutomaticBackup.shared.cancelCurrent()
        HistoryAutoPreferences(server: state.serverId, owner: owner).wifiOnly = enabled; wifiOnly = enabled
    }
    func replaceEnteredKey() {
        guard let coordinator, let state else { return }
        run { [self] in
            let store = try keys(state)
            let expected = try store.load()
            let candidate = try HistoryBackupCrypto.parseRecoveryKey(entered)
            let verified = try await coordinator.verifyRecoveryKey(candidate)
            guard verified.backupId == state.active?.backupId else { throw HistoryBackupCoordinator.Failure.changed }
            try fence(); try store.replaceVerified(candidate, expected: expected)
            entered = ""; replacingKey = false
        }
    }
    func saveEnteredKey() {
        guard let coordinator, let state else { return }
        let key: Data
        do { key = try HistoryBackupCrypto.parseRecoveryKey(entered) }
        catch { self.error = "Use the complete key beginning with COCO-H1-, including all eight groups."; return }
        run { [self] in
            _ = try await coordinator.verifyRecoveryKey(key); try fence()
            try keys(state).saveConfirmed(key); entered = ""
        }
    }
    func cancel() { job?.cancel() }
    func createBackup() {
        guard let coordinator, let state, state.desiredEnabled else { return }
        run { [self] in
            guard let key = try keys(state).load() else { throw APIError.missingSession }
            progress = "Synchronizing history and verifying encrypted files…"
            _ = try await coordinator.backup(recoveryKey: key)
            try fence()
            HistoryAutoPreferences(server: state.serverId, owner: owner).lastSuccess = Date().timeIntervalSince1970
        }
    }
    func restoreBackup() {
        guard let coordinator, let state else { return }
        run { [self] in
            guard let key = try keys(state).load() else { throw APIError.missingSession }
            progress = "Downloading and verifying history and files…"
            _ = try await coordinator.restore(recoveryKey: key)
        }
    }
    func pause(_ revision: Int64) {
        guard let coordinator else { return }
        if let state { HistoryAutoPreferences(server: state.serverId, owner: owner).uploadsAllowed = false }
        HistoryAutomaticBackup.shared.schedule(enabled: false)
        Task { [weak self] in
            await HistoryAutomaticBackup.shared.stopCurrent()
            guard let self, (try? self.fence()) != nil else { return }
            self.run { _ = try await coordinator.setEnabled(expectedRevision: revision, enabled: false) }
        }
    }
    func enable(_ revision: Int64) {
        guard let coordinator else { return }
        run { _ = try await coordinator.setEnabled(expectedRevision: revision, enabled: true) }
    }
    func delete(_ revision: Int64) {
        guard let coordinator else { return }
        run { _ = try await coordinator.deleteConfirmed(expectedRevision: revision) }
    }
    func copyDraft() {
        guard !draftText.isEmpty else { return }
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: draftText]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
        copiedChangeCount = UIPasteboard.general.changeCount
    }
}

struct HistoryKeySettingsView: View {
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.historyBackupAccess) private var backupAccess
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: HistoryKeySettingsModel?
    @State private var failed = false
    var body: some View {
        Group {
            if let model { HistoryKeySettingsContent(model: model) }
            else if failed {
                UnyriSettingsPage("History and backups") {
                    UnyriHistorySettingsSection {
                        Text("History tools are unavailable. Go back, then open this screen again.")
                            .foregroundColor(UnyriColors.danger).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            else { ProgressView() }
        }
        .unyriScreenBackground().navigationTitle("History and backups").navigationBarTitleDisplayMode(.inline)
        .overlay { if scenePhase != .active { Color(uiColor: .systemBackground).ignoresSafeArea() } }
        .task {
            do {
                guard let backupAccess else { throw APIError.missingSession }
                if model == nil { model = try HistoryKeySettingsModel(auth: auth, access: backupAccess) }; model?.refresh()
            }
            catch { failed = true }
        }
        .onDisappear { model?.close(); model = nil }
    }
}

private struct HistoryKeySettingsContent: View {
    @ObservedObject var model: HistoryKeySettingsModel
    @State private var deleteRevision: Int64?
    @State private var confirmRestore = false
    @State private var confirmReplacement = false
    @State private var showTransfer = false
    @State private var receiveTransfer = true
    @State private var showTransferPreparation = false
    var body: some View {
        UnyriSettingsPage("History and backups") {
            UnyriHistorySettingsSection("Encrypted history") {
                Text("Create an encrypted copy of your messages and files. Restore it with your history recovery key. Keep this screen open for manual copying, or enable automatic daily backup.").font(.footnote)
                if model.busy {
                    ProgressView()
                    if let progress = model.progress { Text(LocalizedStringKey(progress)).font(.footnote) }
                    Button("Cancel operation", role: .cancel) { model.cancel() }.accessibilityIdentifier("history-cancel-operation")
                }
                if let error = model.error { Text(LocalizedStringKey(error)).foregroundStyle(UnyriColors.danger) }
            }
            if let state = model.state {
                let step = HistorySetupStep.next(keySaved: model.keySaved, hasDraft: !model.draftText.isEmpty,
                    hasBackup: state.active != nil, hasUnfinishedUpload: state.staged != nil, uploadsAllowed: state.desiredEnabled)
                UnyriHistorySettingsSection {
                    Text(LocalizedStringKey(step.title)).font(.headline).accessibilityAddTraits(.isHeader)
                    Text(LocalizedStringKey(step.detail)).font(.footnote).fixedSize(horizontal: false, vertical: true)
                    if step == .prepareKey {
                        Button("Prepare recovery key") { model.createDraft() }.disabled(model.busy).accessibilityIdentifier("history-prepare-key")
                    } else if step == .allowUploads {
                        Button("Allow future backup uploads") { model.enable(state.revision) }.disabled(model.busy).accessibilityIdentifier("history-setup-enable-uploads")
                    } else if step == .createBackup {
                        Button("Create encrypted backup") { model.createBackup() }.disabled(model.busy).accessibilityIdentifier("history-create-backup")
                    }
                }.accessibilityIdentifier("history-setup-next-step")
                if !model.draftText.isEmpty {
                    UnyriHistorySettingsSection("Save recovery key") {
                        Text("Save the entire key in a password manager or write it down. Then enter its last 8 characters to confirm. This draft expires after 10 minutes.").font(.footnote)
                        Text(model.draftText).font(.system(.body, design: .monospaced)).privacySensitive()
                            .fixedSize(horizontal: false, vertical: true).padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
                        Button("Copy recovery key") { model.copyDraft() }.disabled(model.busy).accessibilityIdentifier("history-copy-key")
                        SecureField("Last 8 characters", text: $model.confirmation).textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .unyriFieldStyle().accessibilityIdentifier("history-key-confirmation")
                            .onChange(of: model.confirmation) { value in if value.count > 8 { model.confirmation = String(value.prefix(8)) } }
                        Button("I saved the key") { model.confirmDraft() }.disabled(model.busy || !model.canConfirm).accessibilityIdentifier("history-confirm-key")
                        Button("Cancel", role: .cancel) { model.clearDraft() }.disabled(model.busy)
                    }
                }
                if !model.keySaved, state.active != nil {
                    UnyriHistorySettingsSection("Recovery key") {
                        Text("Enter the recovery key saved when the backup was created. UNYRI will verify it without importing or changing your messages.").font(.footnote)
                        SecureField("History recovery key", text: $model.entered).textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .unyriFieldStyle().accessibilityIdentifier("history-enter-key")
                            .onChange(of: model.entered) { value in if value.count > 128 { model.entered = String(value.prefix(128)) } }
                        Button("Verify and save key") { model.saveEnteredKey() }.disabled(model.busy || model.entered.isEmpty).accessibilityIdentifier("history-verify-key")
                    }
                }
                UnyriHistorySettingsSection("Backup status") {
                    Text(LocalizedStringKey(state.active == nil ? "No confirmed backup on this server" : "An encrypted backup is stored on this server"))
                    if let timestamp = state.active?.verifiedAt, let date = ISO8601Cocoa.parse(timestamp) {
                        VStack(alignment: .leading) { Text("Last confirmed copy"); Text(date, style: .date); Text(date, style: .time) }.font(.footnote)
                    }
                    if state.active == nil, state.staged != nil { Text("An unfinished upload exists. It is not a usable backup. Delete it before preparing a new key.").font(.footnote) }
                    Text(LocalizedStringKey(model.keySaved ? "Recovery key saved securely on this phone" : "No recovery key saved on this phone"))
                    Text("This key is only for history. It is not your account password or a six-digit sign-in code. Keep it outside UNYRI: without it, an encrypted backup cannot be opened. The server never receives this key.").font(.footnote)
                }
                if (model.keySaved && step != .createBackup) || state.active != nil {
                UnyriHistorySettingsSection("Backup and restore") {
                    if model.keySaved {
                        Button("Create encrypted backup") { model.createBackup() }.disabled(model.busy || !state.desiredEnabled).accessibilityIdentifier("history-create-backup")
                    }
                    if state.active != nil {
                        Button("Restore history and files") { confirmRestore = true }.disabled(model.busy || !model.keySaved).accessibilityIdentifier("history-restore-backup")
                        if !model.keySaved { Text("Enter and verify your saved key above before restoring.").font(.footnote) }
                    }
                }
                }
                UnyriHistorySettingsSection("Move to another phone") {
                    DisclosureGroup("How to transfer history") {
                    Text("1. On the old phone, choose Transfer from this phone.\n2. On the new phone, choose Receive on this phone to show a QR code.\n3. Scan that code with the OLD phone, compare the codes on both phones and confirm.").font(.footnote)
                    Text("Both phones need internet and the same account on the same server. Messages stay on the old phone.").font(.footnote)
                    }
                    Button("Transfer from this phone") {
                        if model.keySaved && state.desiredEnabled { receiveTransfer = false; showTransfer = true }
                        else { showTransferPreparation = true }
                    }.disabled(model.busy).accessibilityIdentifier("history-transfer-source")
                    Button("Receive on this phone") { receiveTransfer = true; showTransfer = true }.disabled(model.busy).accessibilityIdentifier("history-transfer-receive")
                    if showTransferPreparation && (!model.keySaved || !state.desiredEnabled) {
                        Text("First, prepare this phone").font(.headline)
                        if !model.keySaved {
                            if state.active == nil && state.staged == nil {
                                Text("Save the recovery key outside UNYRI and confirm its last 8 characters. Then continue the transfer.").font(.footnote)
                            } else if state.active == nil {
                                Text("An earlier backup upload was interrupted. Resolve the unfinished upload below before preparing a new key. Your messages on this phone are not deleted.").font(.footnote)
                            } else {
                                Text("A backup already exists. Below, enter its recovery key and tap Verify and save key. Do not create a replacement key. Then return here to transfer.").font(.footnote)
                            }
                        } else if !state.desiredEnabled {
                            Text("Allow an encrypted copy to be uploaded for this transfer. Daily automatic backup is a separate setting.").font(.footnote)
                            Button("Allow future backup uploads") { model.enable(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
                        }
                    }
                }
                UnyriHistorySettingsSection("Automatic backup") {
                    Toggle("Automatic daily backup", isOn: Binding(get: { model.automatic }, set: { model.setAutomatic($0) }))
                        .disabled(model.busy || (!model.automatic && (!model.keySaved || !state.desiredEnabled)))
                        .unyriDecorativeTint(Color(hex: 0x7BC9EC)).frame(minHeight: 44).accessibilityIdentifier("history-automatic-backup")
                    if !model.keySaved || !state.desiredEnabled {
                        Text("Save your recovery key and allow uploads before enabling automatic backup.").font(.footnote)
                    }
                    Toggle("Wi-Fi only", isOn: Binding(get: { model.wifiOnly }, set: { model.setWifiOnly($0) })).disabled(model.busy)
                        .unyriDecorativeTint(Color(hex: 0x7BC9EC)).frame(minHeight: 44).accessibilityIdentifier("history-wifi-only")
                    Text("iOS chooses when background copies can run. UNYRI also checks for an overdue daily copy when you open the app. Keep your recovery key saved.").font(.footnote)
                    if model.keySaved, state.active != nil {
                        Button("Replace saved recovery key") { model.replacingKey = true }.disabled(model.busy).accessibilityIdentifier("history-replace-key")
                    }
                    if model.replacingKey {
                        SecureField("History recovery key", text: $model.entered).textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .unyriFieldStyle().accessibilityIdentifier("history-replacement-key")
                            .onChange(of: model.entered) { value in if value.count > 128 { model.entered = String(value.prefix(128)) } }
                        Button("Verify replacement key") { confirmReplacement = true }.disabled(model.busy || model.entered.isEmpty)
                        Button("Cancel", role: .cancel) { model.replacingKey = false; model.entered = "" }.disabled(model.busy)
                    }
                }
                UnyriHistorySettingsSection("Server copy") {
                    if state.desiredEnabled {
                        Button("Disable future backup uploads") { model.pause(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
                    } else {
                        Text("Backup uploads disabled. Existing copies are kept.").font(.footnote)
                        Button("Allow future backup uploads") { model.enable(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
                    }
                    if state.active != nil || state.staged != nil {
                        Button("Delete server backup", role: .destructive) { deleteRevision = state.revision }.disabled(model.busy).accessibilityIdentifier("history-delete-backup")
                    }
                }
            }
            Button("Refresh") { model.refresh() }.disabled(model.busy || !model.draftText.isEmpty)
                .buttonStyle(UnyriHistoryActionStyle()).accessibilityIdentifier("history-refresh")
        }
        .sheet(isPresented: $showTransfer, onDismiss: { model.refresh() }) { NavigationStack { HistoryTransferView(initialReceive: receiveTransfer) } }
        .alert("Replace saved recovery key", isPresented: $confirmReplacement) {
            Button("Cancel", role: .cancel) {}
            Button("Verify and replace") { model.replaceEnteredKey() }
        } message: { Text("The new key must open the current server backup. Only the key saved on this phone will change; the server backup is not rotated. A failed verification keeps the previous key.") }
        .alert("Restore history and files", isPresented: $confirmRestore) {
            Button("Cancel", role: .cancel) {}
            Button("Restore history and files") { model.restoreBackup() }
        } message: { Text("Verified messages and files will be merged with this phone. Newer changes and deletions are preserved.") }
        .alert("Delete server backup", isPresented: Binding(get: { deleteRevision != nil }, set: { if !$0 { deleteRevision = nil } })) {
            Button("Cancel", role: .cancel) { deleteRevision = nil }
            Button("Delete server backup", role: .destructive) {
                if let revision = deleteRevision { deleteRevision = nil; model.delete(revision) }
            }
        } message: { Text("The server copy will be permanently deleted and future uploads disabled. Messages on your phones and the recovery key will remain. This cannot be undone.") }
    }
}

/// Shared history presentation only. Model leases, key operations and transfer
/// callbacks remain outside these wrappers; feedback runs on actual presses.
struct UnyriHistorySettingsSection<Content: View>: View {
    let title: String?
    let content: Content
    init(_ title: String? = nil, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        UnyriSettingsGroup(title) {
            VStack(alignment: .leading, spacing: 12) { content }
                .font(.subheadline).foregroundColor(UnyriColors.primaryText)
                .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                .buttonStyle(UnyriHistoryActionStyle())
        }
    }
}

struct UnyriHistoryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    @Environment(\.unyriAppColorStyle) private var colorStyle
    let accent: Color
    init(accent: Color = Color(hex: 0x7BC9EC)) { self.accent = accent }
    func makeBody(configuration: Configuration) -> some View {
        let ink = configuration.role == .destructive ? UnyriColors.danger : colorStyle.decorative(accent)
        return UnyriQuietButtonStyle().makeBody(configuration: configuration)
            .font(.subheadline.weight(.semibold)).foregroundColor(ink)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 12)
            .background(ink.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.42)
    }
}
