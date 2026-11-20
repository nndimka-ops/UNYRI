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
        let preferences = HistoryAutoPreferences(server: latest.serverId, owner: owner, scope: AccountSecretScope.current(owner: owner))
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
            let preferences = HistoryAutoPreferences(server: state.serverId, owner: owner, scope: AccountSecretScope.current(owner: owner))
            preferences.enabled = enabled; automatic = enabled
            HistoryAutomaticBackup.shared.schedule(enabled: enabled, earliest: HistoryAutoPolicy.next(lastSuccess: preferences.lastSuccess, now: Date().timeIntervalSince1970))
            if enabled { HistoryAutomaticBackup.shared.foreground() }
        } catch { self.error = "Save a verified recovery key and allow uploads before enabling automatic backup." }
    }
    func setWifiOnly(_ enabled: Bool) {
        guard let state else { return }
        guard (try? fence()) != nil else { return }
        HistoryAutomaticBackup.shared.cancelCurrent()
        HistoryAutoPreferences(server: state.serverId, owner: owner, scope: AccountSecretScope.current(owner: owner)).wifiOnly = enabled; wifiOnly = enabled
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
            HistoryAutoPreferences(server: state.serverId, owner: owner, scope: AccountSecretScope.current(owner: owner)).lastSuccess = Date().timeIntervalSince1970
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
        if let state { HistoryAutoPreferences(server: state.serverId, owner: owner, scope: AccountSecretScope.current(owner: owner)).uploadsAllowed = false }
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

/// Presentation only. Every action below calls the same model method, under the
/// same conditions, as before; the layout groups them by what the user wants:
/// status and the one next action, the key, schedule, restore, moving phones,
/// and the destructive server actions last.
private struct HistoryKeySettingsContent: View {
    @ObservedObject var model: HistoryKeySettingsModel
    @Environment(\.locale) private var locale
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var deleteRevision: Int64?
    @State private var confirmRestore = false
    @State private var confirmReplacement = false
    @State private var showTransfer = false
    @State private var receiveTransfer = true
    @State private var showTransferPreparation = false

    var body: some View {
        UnyriSettingsPage("History and backups") {
            statusCard
            if let state = model.state {
                recoveryKeySection(state)
                automaticSection(state)
                if state.active != nil { restoreSection }
                transferSection(state)
                howItWorksSection
                storageSection(state, step: setupStep(state))
            }
            refreshButton
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

    private func setupStep(_ state: HistoryBackupState) -> HistorySetupStep {
        HistorySetupStep.next(keySaved: model.keySaved, hasDraft: !model.draftText.isEmpty,
            hasBackup: state.active != nil, hasUnfinishedUpload: state.staged != nil, uploadsAllowed: state.desiredEnabled)
    }

    // MARK: Status card

    private struct Status {
        let icon: String
        let tint: Color
        let title: Text
        let detail: Text?
    }

    private var statusCard: some View {
        UnyriHistorySettingsSection {
            if let state = model.state {
                let step = setupStep(state)
                VStack(alignment: .leading, spacing: 14) {
                    statusHeader(status(state, step: step))
                    if !model.busy { primaryAction(state, step: step) }
                }.accessibilityIdentifier("history-setup-next-step")
            } else {
                statusHeader(status(nil, step: nil))
            }
            if model.busy {
                Button("Cancel operation", role: .cancel) { model.cancel() }.accessibilityIdentifier("history-cancel-operation")
            }
            if let error = model.error { UnyriHistoryErrorLine(error) }
        }
    }

    private func statusHeader(_ status: Status) -> some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(status.tint.opacity(0.12))
                if model.busy { ProgressView() }
                else { Image(systemName: status.icon).font(.system(size: 19, weight: .semibold)).foregroundColor(status.tint) }
            }
            .frame(width: 44, height: 44).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                status.title.font(.headline).foregroundColor(UnyriColors.primaryText)
                if let detail = status.detail {
                    detail.font(.footnote).foregroundColor(UnyriColors.secondaryText)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
        }
    }

    private func status(_ state: HistoryBackupState?, step: HistorySetupStep?) -> Status {
        let neutral = colorStyle.decorative(Color(hex: 0x7BC9EC))
        if model.busy {
            if let progress = model.progress {
                // The model sets one of two progress lines; restore has its own title.
                let restoring = progress == "Downloading and verifying history and files…"
                return Status(icon: "arrow.triangle.2.circlepath", tint: neutral,
                              title: restoring ? Text("Restoring history") : Text("Backup in progress"),
                              detail: Text(LocalizedStringKey(progress)))
            }
            return Status(icon: "arrow.triangle.2.circlepath", tint: neutral,
                          title: state == nil ? Text("Checking…") : Text("Please wait…"), detail: nil)
        }
        guard let state, let step else {
            return Status(icon: "exclamationmark.icloud", tint: UnyriColors.warning, title: Text("Backup status unavailable"), detail: nil)
        }
        let title: Text
        if state.active != nil {
            if let timestamp = state.active?.verifiedAt, let date = ISO8601Cocoa.parse(timestamp) {
                title = Text("Last backup: \(backupStamp(date))")
            } else {
                title = Text("An encrypted backup is stored on this server")
            }
        } else {
            title = Text("No backup yet")
        }
        let guidance = Text(LocalizedStringKey(step.title))
        switch step {
        case .ready:
            return Status(icon: "checkmark.icloud", tint: UnyriColors.success, title: title,
                          detail: model.automatic ? Text("Daily backup is on") : Text("Daily backup is off"))
        case .allowUploads where state.active != nil:
            return Status(icon: "icloud.slash", tint: UnyriColors.warning, title: title,
                          detail: Text("Backups are off. Existing backups are kept."))
        case .enterExistingKey, .unfinishedUpload:
            return Status(icon: "exclamationmark.icloud", tint: UnyriColors.warning, title: title, detail: guidance)
        default:
            return Status(icon: "icloud", tint: neutral, title: title, detail: guidance)
        }
    }

    /// "today, 14:20", "yesterday, 09:05", "10 November at 14:20" (with the year
    /// when it differs), in the app's language.
    private func backupStamp(_ date: Date) -> String {
        let calendar = Calendar.current
        let time = date.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(locale))
        if calendar.isDateInToday(date) { return UnyriL10n.string("Today").lowercased() + ", " + time }
        if calendar.isDateInYesterday(date) { return UnyriL10n.string("Yesterday").lowercased() + ", " + time }
        let base = Date.FormatStyle.dateTime.day().month(.wide).hour().minute()
        let style = calendar.isDate(date, equalTo: Date(), toGranularity: .year) ? base : base.year()
        return date.formatted(style.locale(locale))
    }

    /// The single next action for the current setup step. Key confirmation and
    /// entry happen in the Recovery key section right below the card.
    @ViewBuilder private func primaryAction(_ state: HistoryBackupState, step: HistorySetupStep) -> some View {
        switch step {
        case .prepareKey:
            Button("Create recovery key") { model.createDraft() }.disabled(model.busy)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-prepare-key")
        case .allowUploads:
            Button("Turn on backups") { model.enable(state.revision) }.disabled(model.busy)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-setup-enable-uploads")
        case .createBackup:
            Button("Back up now") { model.createBackup() }.disabled(model.busy)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-create-backup")
        case .ready:
            Button("Back up now") { model.createBackup() }.disabled(model.busy || !state.desiredEnabled)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-create-backup")
        case .unfinishedUpload:
            Text("It cannot be used to restore. Tap Refresh, or delete it at the bottom of this screen.")
                .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
        case .confirmKey, .enterExistingKey:
            EmptyView()
        }
    }

    // MARK: Recovery key

    private func recoveryKeySection(_ state: HistoryBackupState) -> some View {
        HistorySettingsBlock("Recovery key", footer: "Only this key can open your backup. It is not your account password. Keep it outside UNYRI.") {
            HStack(spacing: 12) {
                HistoryRowIcon(symbol: "key.fill")
                (model.keySaved ? Text("Saved on this phone") : Text("Not saved on this phone"))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                Image(systemName: model.keySaved ? "checkmark.circle.fill" : "circle.dashed")
                    .foregroundColor(model.keySaved ? UnyriColors.success : UnyriColors.veryMutedText)
                    .accessibilityHidden(true)
            }
            .accessibilityElement(children: .combine)
            if !model.draftText.isEmpty { draftKeyEditor }
            if !model.keySaved, state.active != nil { existingKeyEditor }
            if model.keySaved, state.active != nil, !model.replacingKey {
                Button("Replace saved recovery key") { model.replacingKey = true }.disabled(model.busy).accessibilityIdentifier("history-replace-key")
            }
            if model.replacingKey { replacementKeyEditor }
        }
    }

    private var draftKeyEditor: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Save the entire key in a password manager or write it down. Then enter its last 8 characters to confirm. This draft expires after 10 minutes.")
                .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
            Text(model.draftText).font(.system(.body, design: .monospaced)).privacySensitive()
                .fixedSize(horizontal: false, vertical: true).padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
            Button("Copy recovery key") { model.copyDraft() }.disabled(model.busy).accessibilityIdentifier("history-copy-key")
            SecureField("Last 8 characters", text: $model.confirmation).textInputAutocapitalization(.characters).autocorrectionDisabled()
                .unyriFieldStyle().accessibilityIdentifier("history-key-confirmation")
                .onChange(of: model.confirmation) { value in if value.count > 8 { model.confirmation = String(value.prefix(8)) } }
            Button("I saved the key") { model.confirmDraft() }.disabled(model.busy || !model.canConfirm)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-confirm-key")
            Button("Cancel", role: .cancel) { model.clearDraft() }.disabled(model.busy)
        }
    }

    private var existingKeyEditor: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Enter the recovery key saved when the backup was created. UNYRI will verify it without importing or changing your messages.")
                .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
            SecureField("History recovery key", text: $model.entered).textInputAutocapitalization(.characters).autocorrectionDisabled()
                .unyriFieldStyle().accessibilityIdentifier("history-enter-key")
                .onChange(of: model.entered) { value in if value.count > 128 { model.entered = String(value.prefix(128)) } }
            Button("Verify and save key") { model.saveEnteredKey() }.disabled(model.busy || model.entered.isEmpty)
                .buttonStyle(UnyriHistoryPrimaryActionStyle()).accessibilityIdentifier("history-verify-key")
        }
    }

    private var replacementKeyEditor: some View {
        VStack(alignment: .leading, spacing: 12) {
            SecureField("History recovery key", text: $model.entered).textInputAutocapitalization(.characters).autocorrectionDisabled()
                .unyriFieldStyle().accessibilityIdentifier("history-replacement-key")
                .onChange(of: model.entered) { value in if value.count > 128 { model.entered = String(value.prefix(128)) } }
            Button("Verify replacement key") { confirmReplacement = true }.disabled(model.busy || model.entered.isEmpty)
            Button("Cancel", role: .cancel) { model.replacingKey = false; model.entered = "" }.disabled(model.busy)
        }
    }

    // MARK: Automatic backup

    private func automaticSection(_ state: HistoryBackupState) -> some View {
        let available = model.keySaved && state.desiredEnabled
        return HistorySettingsBlock("Automatic backup", footer: available
            ? "iOS picks the exact time. UNYRI also checks for a missed backup when you open the app."
            : "Available after you save a recovery key and turn on backups.") {
            Toggle("Daily backup", isOn: Binding(get: { model.automatic }, set: { model.setAutomatic($0) }))
                .disabled(model.busy || (!model.automatic && (!model.keySaved || !state.desiredEnabled)))
                .unyriDecorativeTint(Color(hex: 0x7BC9EC)).frame(minHeight: 44).accessibilityIdentifier("history-automatic-backup")
            Toggle("Wi-Fi only", isOn: Binding(get: { model.wifiOnly }, set: { model.setWifiOnly($0) })).disabled(model.busy)
                .unyriDecorativeTint(Color(hex: 0x7BC9EC)).frame(minHeight: 44).accessibilityIdentifier("history-wifi-only")
        }
    }

    // MARK: Restore

    private var restoreSection: some View {
        HistorySettingsBlock("Restore from backup", footer: model.keySaved ? nil : "Enter and verify your saved key above before restoring.") {
            Button("Restore history and files") { confirmRestore = true }.disabled(model.busy || !model.keySaved).accessibilityIdentifier("history-restore-backup")
        }
    }

    // MARK: Move to another phone

    private func transferSection(_ state: HistoryBackupState) -> some View {
        HistorySettingsBlock("Move to another phone", footer: "Both phones need internet and the same account on the same server. Messages stay on the old phone.") {
            VStack(alignment: .leading, spacing: 10) {
                HistoryNumberedStep(number: 1, text: "On the old phone, tap Send from this phone.")
                HistoryNumberedStep(number: 2, text: "On the new phone, tap Receive on this phone to show a QR code.")
                HistoryNumberedStep(number: 3, text: "Scan it with the old phone, check that the codes match and confirm.")
            }
            Button {
                if model.keySaved && state.desiredEnabled { receiveTransfer = false; showTransfer = true }
                else { showTransferPreparation = true }
            } label: { Label("Send from this phone", systemImage: "qrcode.viewfinder") }
                .disabled(model.busy).accessibilityIdentifier("history-transfer-source")
            Button { receiveTransfer = true; showTransfer = true } label: { Label("Receive on this phone", systemImage: "qrcode") }
                .disabled(model.busy).accessibilityIdentifier("history-transfer-receive")
            if showTransferPreparation && (!model.keySaved || !state.desiredEnabled) {
                transferPreparation(state)
            }
        }
    }

    /// Shown after Send when this phone cannot send yet: names the one thing to do.
    private func transferPreparation(_ state: HistoryBackupState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("First, prepare this phone", systemImage: "exclamationmark.circle")
                .font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.warning)
            if !model.keySaved {
                if state.active == nil && state.staged == nil {
                    preparationHint("Create and confirm your recovery key at the top of this screen. Then tap Send from this phone again.")
                } else if state.active == nil {
                    preparationHint("An earlier upload was interrupted. Delete it at the bottom of this screen, then create a recovery key.")
                } else {
                    preparationHint("This account already has a backup. Enter its recovery key above instead of creating a new one. Then tap Send from this phone again.")
                }
            } else if !state.desiredEnabled {
                preparationHint("Sending needs backups turned on. Daily backup is a separate setting.")
                Button("Turn on backups") { model.enable(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(UnyriColors.warning.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }

    private func preparationHint(_ text: LocalizedStringKey) -> some View {
        Text(text).font(.footnote).foregroundColor(UnyriColors.primaryText).fixedSize(horizontal: false, vertical: true)
    }

    // MARK: How it works

    private var howItWorksSection: some View {
        UnyriHistorySettingsSection {
            DisclosureGroup("How it works") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Create an encrypted copy of your messages and files. Restore it with your history recovery key. Keep this screen open for manual copying, or enable automatic daily backup.")
                    Text("This key is only for history. It is not your account password or a six-digit sign-in code. Keep it outside UNYRI: without it, an encrypted backup cannot be opened. The server never receives this key.")
                    Text("iOS chooses when background copies can run. UNYRI also checks for an overdue daily copy when you open the app. Keep your recovery key saved.")
                    Text("Verified messages and files will be merged with this phone. Newer changes and deletions are preserved.")
                }
                .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 8)
            }
            .tint(UnyriColors.primaryText)
        }
    }

    // MARK: Turn off and delete (last on the screen)

    @ViewBuilder private func storageSection(_ state: HistoryBackupState, step: HistorySetupStep) -> some View {
        let hasServerData = state.active != nil || state.staged != nil
        // In the allow-uploads step the status card already offers Turn on backups.
        let offerEnable = !state.desiredEnabled && step != .allowUploads && hasServerData
        if state.desiredEnabled || offerEnable || hasServerData {
            HistorySettingsBlock(nil, footer: state.desiredEnabled ? "Turning off stops new backups. Your last backup is kept." : nil) {
                if state.desiredEnabled {
                    Button("Turn off backups") { model.pause(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
                } else if offerEnable {
                    Text("Backups are off. Existing backups are kept.").font(.footnote).foregroundColor(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Turn on backups") { model.enable(state.revision) }.disabled(model.busy || !model.draftText.isEmpty)
                }
                if hasServerData {
                    Button("Delete server backup", role: .destructive) { deleteRevision = state.revision }.disabled(model.busy).accessibilityIdentifier("history-delete-backup")
                }
            }
        }
    }

    /// The last row on the screen (the native screen audit scrolls to it).
    private var refreshButton: some View {
        Button("Refresh") { model.refresh() }.disabled(model.busy || !model.draftText.isEmpty)
            .buttonStyle(UnyriHistoryActionStyle()).accessibilityIdentifier("history-refresh")
    }
}

/// A history card with an optional footnote under it, like a grouped-list footer.
private struct HistorySettingsBlock<Content: View>: View {
    let title: String?
    let footer: String?
    let content: Content
    init(_ title: String?, footer: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title; self.footer = footer; self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            UnyriHistorySettingsSection(title) { content }
            if let footer { UnyriSettingsNote(footer) }
        }
    }
}

private struct HistoryRowIcon: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    let symbol: String
    var body: some View {
        let tint = colorStyle.decorative(Color(hex: 0x7BC9EC))
        Image(systemName: symbol).font(.system(size: 15, weight: .semibold)).foregroundColor(tint)
            .frame(width: 32, height: 32)
            .background(RoundedRectangle(cornerRadius: 10).fill(tint.opacity(0.12)))
            .accessibilityHidden(true)
    }
}

private struct HistoryNumberedStep: View {
    let number: Int
    let text: LocalizedStringKey
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(verbatim: String(number)).font(.caption.weight(.bold)).monospacedDigit()
                .foregroundColor(UnyriColors.onAccent)
                .frame(width: 20, height: 20)
                .background(Circle().fill(UnyriColors.accent))
            Text(text).font(.footnote).foregroundColor(UnyriColors.primaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Error text for the history screens: a plain Text (UI tests match its label)
/// with a leading icon so the state does not rely on colour alone.
struct UnyriHistoryErrorLine: View {
    let message: String
    init(_ message: String) { self.message = message }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill").foregroundColor(UnyriColors.danger).accessibilityHidden(true)
            Text(verbatim: UnyriL10n.string(message)).foregroundStyle(UnyriColors.danger).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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

/// The one filled action for the current history state (same shape and press
/// feedback as UnyriHistoryActionStyle, filled with the shared accent).
struct UnyriHistoryPrimaryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        UnyriQuietButtonStyle().makeBody(configuration: configuration)
            .font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.onAccent)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 12)
            .background(UnyriColors.accent, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.42)
    }
}
