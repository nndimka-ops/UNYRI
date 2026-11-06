import SwiftUI
import CryptoKit
import CoreImage.CIFilterBuiltins
import AVFoundation
import LocalAuthentication

/// Shared history-tool errors. A rejected transcript, missing key, or local
/// file failure must not be presented as a network/account mismatch.
enum HistoryOperationErrorPresentation {
    static func message(for error: Error) -> String {
        switch error {
        case HistoryTransferFailure.wrongServer:
            return "This code belongs to another server. Select the same server on both phones, then show a new code."
        case HistoryTransferFailure.wrongAccount:
            return "The phones are signed in to different accounts. Check the account shown on each phone, then show a new code."
        case HistoryTransferFailure.expired:
            return "This code expired. Show a new code on the new phone, then scan it with the old phone."
        case HistoryTransferFailure.recoveryKeyMissing:
            return "This phone needs its history recovery key first. Go back to History and backups and finish preparing this phone. Your messages stay here."
        case HistoryTransferFailure.deviceApprovalRequired:
            return "Unlock this phone to approve the transfer. The new phone has not received your history yet."
        case HistoryTransferFailure.changed, HistoryBackupCoordinator.Failure.changed, HistoryNativeOwner.Failure.changed:
            return "The prepared history or transfer request changed. Prepare the history again, then show a new code on the new phone."
        case HistoryTransferFailure.invalid, HistoryTransferFailure.comparisonRequired:
            return "This is not a valid UNYRI history transfer code. Show a new code from Receive on this phone."
        case HistoryBackupCoordinator.Failure.busy:
            return "Another history operation is running. Wait for it to finish."
        case HistoryBackupCoordinator.Failure.disabled:
            return "Backup uploads are off. On the old phone, allow encrypted backup uploads, then prepare the history again."
        case HistoryBackupCoordinator.Failure.noBackup:
            return "No confirmed backup is available yet. Prepare the history on the old phone first."
        case HistoryBackupCoordinator.Failure.unsupportedServer:
            return "This server does not support history backups yet. Ask its owner to update the server."
        case HistoryNativeOwner.Failure.incompleteSource:
            return "History is incomplete on this phone. Finish synchronization or restore the existing backup before creating a new copy."
        case HistoryNativeOwner.Failure.unavailableMedia:
            return "A file is unavailable. Keep your old phone and try again. The previous confirmed backup was not replaced."
        case HistoryNativeOwner.Failure.insufficientSpace:
            return "Not enough free space to verify and restore the files. Free storage and retry."
        case is AttachmentIntegrityError, HistoryMediaSpool.Failure.integrity:
            return "A file failed verification. No unverified history was imported. Try downloading the copy again."
        case CryptoKitError.authenticationFailure:
            return "This key cannot open the backup. Check that you entered the history recovery key for this account and server."
        case let authentication as NSError where authentication.domain == LAError.errorDomain:
            switch authentication.code {
            case LAError.Code.passcodeNotSet.rawValue:
                return "Set an iPhone passcode in iOS Settings under Face ID or Touch ID and Passcode, then return and allow the transfer. Your history stays on this phone."
            case LAError.Code.userCancel.rawValue, LAError.Code.appCancel.rawValue,
                 LAError.Code.systemCancel.rawValue, LAError.Code.userFallback.rawValue:
                return "Device confirmation was cancelled. Tap Allow transfer to try again. If the QR expired, show a new one on the new phone."
            default:
                return "Device confirmation did not finish. Unlock this iPhone, then tap Allow transfer again. Your history has not been sent by this confirmation."
            }
        case APIError.missingSession, APIError.http(401), APIError.http(403):
            return "This sign-in can no longer continue the transfer. Sign in again on this phone, then create a new request."
        case APIError.http(404), APIError.http(410):
            return "The transfer request is no longer available. Show a new code on the new phone and scan it again."
        case APIError.http(409):
            return "The prepared history or transfer request changed. Prepare the history again, then show a new code on the new phone."
        case APIError.http(429):
            return "Too many attempts. Wait a moment, then continue the transfer."
        case APIError.invalidURL:
            return "Check the server address on this phone. Both phones must use the same server."
        case APIError.network, is URLError:
            return "The server could not be reached. Keep both phones connected and try again. Your confirmed backup is kept."
        default:
            return "History could not be prepared or verified. Your messages and confirmed backup are kept. Go back, refresh History and backups, then retry."
        }
    }
}

@MainActor final class HistoryTransferModel: ObservableObject {
    @Published var qr: String?
    @Published var comparison: String?
    @Published var needsSourceApproval = false
    @Published var readyToAccept = false
    @Published var busy = false
    @Published var error: String?
    @Published var completed = false
    @Published var adopted = false
    @Published var replacing = false
    @Published var scanning = false
    @Published var preparedAt: Date?
    @Published var retryPublish = false
    @Published var preparingSource = false
    @Published var cameraDenied = false
    private struct Publication: Encodable {
        let expectedRevision: Int64; let backupId: String; let senderPublicKey: String; let ciphertext: String
    }
    private var pendingPublication: Publication?
    private var pendingComparison: String?
    private var preparedBackup: HistoryBackupInfo?
    private let context: HistoryOperationContext
    private var challenge: HistoryTransferChallenge?
    private var recipient: Curve25519.KeyAgreement.PrivateKey?
    private var candidate: Data?
    private var expected: Data?
    private var record: HistoryTransferRecord?
    private var job: Task<Void, Never>?
    private var poll: Task<Void, Never>?
    private var expiry: Task<Void, Never>?
    private var closed = false
    private var approvalAuthentication: LAContext?
    init(auth: AuthRepository, access: HistoryBackupAccess) throws { context = try .init(auth: auth, access: access) }
    private func work(_ action: @escaping () async throws -> Void) {
        guard !busy, !closed else { return }
        do { try context.access.lease.begin() } catch { self.error = "Another history operation is running. Wait for it to finish."; return }
        busy = true; error = nil
        job = Task { [self] in
            defer { context.access.lease.end(); busy = false; job = nil }
            do { try context.fence(); try await action(); try context.fence() }
            catch is CancellationError { }
            catch { if !closed { self.error = HistoryOperationErrorPresentation.message(for: error) } }
            await context.cleanup()
        }
    }
    func receive() {
        work { [self] in
            let state = try await context.open()
            expected = try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner).load()
            replacing = expected != nil
            let secret = Curve25519.KeyAgreement.PrivateKey()
            struct Create: Encodable { let recipientPublicKey: String }
            let value: HistoryTransferRecord = try await context.remote.request("/history-transfer", body: context.remote.encode(Create(recipientPublicKey: secret.publicKey.rawRepresentation.base64EncodedString())))
            try context.fence()
            try value.challenge.validate(server: state.serverId, owner: context.owner)
            guard value.status == "waiting", value.recipientPublicKey == secret.publicKey.rawRepresentation.base64EncodedString() else { throw HistoryTransferFailure.invalid }
            recipient = secret; challenge = value.challenge; record = value
            qr = try value.challenge.encoded()
            expire(challenge: value.challenge)
            startPolling()
        }
    }
    private func expire(challenge: HistoryTransferChallenge) {
        expiry?.cancel()
        expiry = Task { [weak self] in
            let wait = max(0, challenge.expires - Int64(Date().timeIntervalSince1970 * 1000))
            do { try await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000) } catch { return }
            guard let self else { return }
            self.approvalAuthentication?.invalidate(); self.approvalAuthentication = nil
            self.poll?.cancel(); self.job?.cancel(); self.recipient = nil; self.candidate = nil
            self.expected = nil; self.qr = nil; self.comparison = nil; self.readyToAccept = false
            self.needsSourceApproval = false; self.retryPublish = false
            self.pendingPublication = nil; self.pendingComparison = nil
            self.error = "History transfer expired or changed. Close it and create a new request."
        }
    }
    private func startPolling() {
        poll?.cancel()
        poll = Task { [weak self] in
            var failures = 0
            var wait: UInt64 = 3_000_000_000
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: wait)
                    guard let self, !self.closed, let challenge = self.challenge, let recipient = self.recipient else { return }
                    try self.context.fence()
                    let value: HistoryTransferRecord = try await self.context.remote.request("/history-transfer/" + challenge.transfer)
                    try value.pin(challenge)
                    self.error = nil; failures = 0; wait = 3_000_000_000
                    if value.status == "ready" {
                        let opened = try HistoryTransferCrypto.open(value, challenge: challenge, recipient: recipient)
                        self.candidate = opened.key; self.comparison = opened.comparison; self.record = value
                        self.readyToAccept = true; self.qr = nil; return
                    }
                } catch is CancellationError { return }
                catch {
                    guard let self, !self.closed, let challenge = self.challenge else { return }
                    var status: Int?
                    var networkFailure = error is URLError
                    if let api = error as? APIError {
                        switch api {
                        case .http(let code): status = code
                        case .network: networkFailure = true
                        default: break
                        }
                    }
                    if let delay = HistoryTransferRetryPolicy.delay(status: status, networkFailure: networkFailure, attempt: failures,
                        now: Int64(Date().timeIntervalSince1970 * 1000), expires: challenge.expires) {
                        failures += 1; wait = delay
                        self.error = "Connection interrupted. Waiting to continue history transfer."
                        continue
                    }
                    self.error = "History transfer expired or changed. Close it and create a new request."
                    self.recipient = nil; self.candidate = nil; self.qr = nil; return
                }
            }
        }
    }
    func prepareSource() {
        guard !busy, !closed else { return }
        guard context.access.canUseCamera() else { error = "End the call before scanning a history transfer code."; return }
        preparedBackup = nil; preparedAt = nil
        work { [self] in
            preparingSource = true
            defer { preparingSource = false }
            let state = try await context.open()
            guard state.desiredEnabled else { throw HistoryBackupCoordinator.Failure.disabled }
            guard let coordinator = context.coordinator else { throw HistoryTransferFailure.invalid }
            guard let key = try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner).load() else { throw HistoryTransferFailure.recoveryKeyMissing }
            let backup = try await coordinator.backup(recoveryKey: key)
            try context.fence()
            guard let date = ISO8601Cocoa.parse(backup.createdAt) else { throw HistoryTransferFailure.invalid }
            preparedBackup = backup; preparedAt = date
            HistoryAutoPreferences(server: state.serverId, owner: context.owner).lastSuccess = Date().timeIntervalSince1970
        }
    }
    func scan() {
        guard !busy, !closed else { return }
        guard preparedBackup != nil else { error = "Prepare your history on this phone first, then scan the QR on the new phone."; return }
        guard context.access.canUseCamera() else { error = "End the call before scanning a history transfer code."; return }
        Task {
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard !closed else { return }
            guard allowed else { cameraDenied = true; error = "Camera access is required to scan the code. Allow it in iOS Settings."; return }
            guard context.access.canUseCamera() else { error = "End the call before scanning a history transfer code."; return }
            cameraDenied = false; error = nil; scanning = true
        }
    }
    func scanned(_ text: String) {
        scanning = false
        work { [self] in
            let state = try await context.open()
            let value = try HistoryTransferChallenge.decode(text, server: state.serverId, owner: context.owner)
            let remote: HistoryTransferRecord = try await context.remote.request("/history-transfer/" + value.transfer)
            try remote.pin(value)
            guard remote.status == "waiting" else { throw HistoryTransferFailure.changed }
            challenge = value; needsSourceApproval = true; expire(challenge: value)
        }
    }
    func approveSource() {
        guard needsSourceApproval, let challenge else { return }
        work { [self] in
            let authentication = LAContext()
            approvalAuthentication = authentication
            defer { authentication.invalidate(); approvalAuthentication = nil }
            guard try await authentication.evaluatePolicy(.deviceOwnerAuthentication,
                localizedReason: UnyriL10n.string("Allow history transfer to your other phone.")) else { throw HistoryTransferFailure.deviceApprovalRequired }
            try context.fence()
            let state = try await context.open()
            try challenge.validate(server: state.serverId, owner: context.owner)
            let pinned: HistoryTransferRecord = try await context.remote.request("/history-transfer/" + challenge.transfer)
            try pinned.pin(challenge)
            guard pinned.status == "waiting", let coordinator = context.coordinator,
                  let key = try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner).load() else { throw HistoryTransferFailure.invalid }
            guard let backup = preparedBackup else { throw HistoryTransferFailure.changed }
            let verified = try await coordinator.verifyRecoveryKey(key)
            guard verified.backupId == backup.backupId else { throw HistoryTransferFailure.changed }
            let latest = try await context.remote.status()
            guard latest.active?.backupId == backup.backupId, latest.desiredEnabled else { throw HistoryTransferFailure.changed }
            try challenge.validate(server: latest.serverId, owner: context.owner)
            let sealed = try HistoryTransferCrypto.seal(key, challenge: challenge, backup: backup.backupId)
            pendingPublication = Publication(expectedRevision: latest.revision, backupId: backup.backupId,
                senderPublicKey: sealed.sender, ciphertext: sealed.ciphertext)
            pendingComparison = sealed.comparison; retryPublish = true
            needsSourceApproval = false
            try await publishPending()
        }
    }
    func retryPublication() { work { [self] in try await publishPending() } }
    private func publishPending() async throws {
        guard let challenge, let pending = pendingPublication, let expectedComparison = pendingComparison else { throw HistoryTransferFailure.changed }
        try challenge.validate(server: challenge.server, owner: context.owner)
        let result: HistoryTransferRecord = try await context.remote.request("/history-transfer/" + challenge.transfer + "/publish", body: context.remote.encode(pending))
        try result.pin(challenge)
        guard result.status == "ready", result.senderPublicKey == pending.senderPublicKey,
              result.ciphertext == pending.ciphertext, result.backupId == pending.backupId else { throw HistoryTransferFailure.changed }
        comparison = expectedComparison; record = result
        retryPublish = false; pendingPublication = nil; pendingComparison = nil
    }
    func acceptMatchingCodes() {
        guard readyToAccept, let candidate, let record, let backup = record.backupId, let challenge else { return }
        work { [self] in
            try record.pin(challenge)
            let latest: HistoryTransferRecord = try await context.remote.request("/history-transfer/" + challenge.transfer)
            try latest.pin(challenge)
            guard latest.ciphertext == record.ciphertext, latest.senderPublicKey == record.senderPublicKey,
                  latest.backupId == backup, let coordinator = context.coordinator else { throw HistoryTransferFailure.changed }
            let verified = try await coordinator.verifyRecoveryKey(candidate)
            guard verified.backupId == backup else { throw HistoryTransferFailure.changed }
            try context.fence(); try record.pin(challenge)
            try HistoryRecoveryKeys.deviceStore(server: challenge.server, owner: context.owner).replaceVerified(candidate, expected: expected)
            expected = candidate
            adopted = true; readyToAccept = false; recipient = nil; self.candidate = nil
            expiry?.cancel(); expiry = nil; comparison = nil
            // The verified key is durable before acknowledging. Failed restore
            // can retry from ordinary settings with the newly adopted key.
            struct Ack: Encodable { let backupId: String }
            let acknowledged: HistoryTransferRecord = try await context.remote.request("/history-transfer/" + challenge.transfer + "/ack", body: context.remote.encode(Ack(backupId: backup)))
            guard acknowledged.challenge == challenge, acknowledged.status == "consumed", acknowledged.backupId == backup else { throw HistoryTransferFailure.changed }
            expiry?.cancel(); expiry = nil
            readyToAccept = false; recipient = nil; self.candidate = nil
            _ = try await coordinator.restore(recoveryKey: candidate)
            completed = true; comparison = nil
        }
    }
    func restoreAdopted() {
        guard adopted else { return }
        work { [self] in
            let state = try await context.open()
            guard let coordinator = context.coordinator,
                  let key = try HistoryRecoveryKeys.deviceStore(server: state.serverId, owner: context.owner).load() else { throw HistoryTransferFailure.invalid }
            _ = try await coordinator.restore(recoveryKey: key)
            completed = true
        }
    }
    func close() {
        approvalAuthentication?.invalidate(); approvalAuthentication = nil
        closed = true; job?.cancel(); poll?.cancel(); expiry?.cancel()
        recipient = nil; candidate = nil; expected = nil; qr = nil; comparison = nil; preparedBackup = nil; preparedAt = nil
        pendingPublication = nil; pendingComparison = nil; retryPublish = false
        if let challenge, !completed {
            let remote = context.remote
            Task { let _: HistoryTransferRecord? = try? await remote.request("/history-transfer/" + challenge.transfer + "/cancel", body: Data("{}".utf8)) }
        }
    }
}

struct HistoryTransferView: View {
    var initialReceive: Bool? = nil
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.historyBackupAccess) private var access
    @Environment(\.dismiss) private var dismiss
    @State private var model: HistoryTransferModel?
    @State private var failed = false
    @State private var confirmAccept = false
    var body: some View {
        Group {
            if let model { content(model) }
            else if failed {
                UnyriSettingsPage("Transfer history by QR") {
                    UnyriHistorySettingsSection {
                        Text("Transfer is unavailable. Go back, then open this screen again.")
                            .foregroundColor(UnyriColors.danger).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            else { ProgressView() }
        }
        .unyriScreenBackground().navigationTitle("Transfer history by QR")
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.frame(minHeight: 44).accessibilityIdentifier("history-transfer-done") } }
        .task { do { if model == nil { guard let access else { throw APIError.missingSession }; model = try HistoryTransferModel(auth: auth, access: access) } } catch { failed = true } }
        .onDisappear { model?.close() }
    }
    private func content(_ model: HistoryTransferModel) -> some View { HistoryTransferContent(model: model, initialReceive: initialReceive) }
}
private struct HistoryTransferContent: View {
    @Environment(\.openURL) private var openURL
    @EnvironmentObject private var auth: AuthRepository
    @ObservedObject var model: HistoryTransferModel
    var initialReceive: Bool?
    @State private var confirmAccept = false
    @State private var receiving = false
    @State private var started = false
    private var phoneRoleTitle: String? {
        if receiving || initialReceive == true { return "New phone · receives history" }
        if initialReceive == false || model.preparingSource || model.preparedAt != nil ||
            model.needsSourceApproval || model.retryPublish || model.comparison != nil {
            return "Old phone · sends history"
        }
        return nil
    }
    private var progressTitle: String {
        if model.preparingSource { return "Preparing an encrypted copy of messages and files…" }
        if model.adopted { return "Restoring messages and files on this phone…" }
        if receiving && !model.readyToAccept { return "Creating a one-time QR on this phone…" }
        if receiving { return "Verifying the transfer before restoring history…" }
        return "Verifying and approving transfer to your new phone…"
    }
    var body: some View {
        UnyriSettingsPage("Transfer history by QR") {
            UnyriHistorySettingsSection {
                if let role = phoneRoleTitle {
                    Label(LocalizedStringKey(role), systemImage: "iphone")
                        .font(.headline).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("history-transfer-phone-role")
                }
                if let username = auth.currentUser?.username {
                    LabeledContent("Account on this phone", value: "@" + username).font(.footnote)
                }
                if let host = AppConfig.apiBaseURL.host {
                    LabeledContent("Server on this phone", value: host).font(.footnote)
                }
                if !model.completed && !model.adopted && model.comparison == nil {
                    UnyriSettingsNote("QR direction: the NEW phone shows it; the OLD phone scans it. This QR is not your recovery code.")
                        .accessibilityIdentifier("history-transfer-direction")
                }
                if let error = model.error { Text(LocalizedStringKey(error)).foregroundStyle(UnyriColors.danger).fixedSize(horizontal: false, vertical: true) }
                if model.cameraDenied {
                    Button("Open app settings") { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } }.accessibilityIdentifier("history-transfer-camera-settings")
                }
                if model.busy {
                    ProgressView(LocalizedStringKey(progressTitle))
                    Text("Keep this screen open. Do not create another request while this step is running.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if model.completed {
                    phaseTitle(receiving ? "History received" : "Transfer complete")
                    Text(LocalizedStringKey(receiving ? "Your messages and files are now on this phone. You can close this screen." : "Your history is now on the new phone. The copy on this phone is unchanged."))
                }
                else if model.adopted {
                    phaseTitle("Receiving your history")
                    Text("Keep this screen open while messages and files arrive. If interrupted, continue here — you do not need to scan again.")
                    if !model.busy { Button("Continue transfer") { model.restoreAdopted() }.accessibilityIdentifier("history-transfer-continue") }
                } else if let text = model.qr, let image = qrImage(text) {
                    phaseTitle("2. Show this QR to your old phone")
                    Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 260).padding().background(.white).accessibilityLabel(Text("Show this code to your old phone"))
                        .frame(maxWidth: .infinity, alignment: .center).accessibilityIdentifier("history-transfer-qr")
                    Label("Waiting for your old phone to scan and allow transfer", systemImage: "hourglass")
                        .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("history-transfer-waiting-source")
                    Text("Leave this QR on the NEW phone. On the OLD phone, choose Scan the new phone's code and point its camera at this square. No recovery key needs to be typed here.")
                } else if let comparison = model.comparison {
                    phaseTitle("3. Compare the security codes")
                    Text(comparison).font(.system(.title2, design: .monospaced)).textSelection(.disabled)
                        .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(Color.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityIdentifier("history-transfer-comparison")
                    Text("Compare all four groups on both phones. If anything differs, close this transfer.")
                    if model.readyToAccept {
                        Button("The codes match") { confirmAccept = true }.disabled(model.busy).accessibilityIdentifier("history-transfer-confirm-codes")
                    } else {
                        Label("Waiting for confirmation on your new phone", systemImage: "hourglass")
                            .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                        Text("On the NEW phone, compare all four groups and tap The codes match. Keep this old phone's history until you have checked the result on the new phone.")
                    }
                } else if model.retryPublish {
                    Text("Could not send confirmation. Check your connection and try again.")
                    Button("Try again") { model.retryPublication() }.disabled(model.busy).accessibilityIdentifier("history-transfer-retry")
                } else if model.needsSourceApproval {
                    phaseTitle("Send history to your new phone?")
                    Text("Allow only the request you just scanned from your own NEW phone. Confirm with Face ID, Touch ID or this iPhone's passcode; then compare the codes on both screens.")
                    Button("Allow transfer") { model.approveSource() }.disabled(model.busy).accessibilityIdentifier("history-transfer-approve")
                } else if model.preparingSource {
                    phaseTitle("1. Preparing your history")
                    Text("We are preparing a confirmed encrypted copy for this transfer. Messages and files stay on the old phone. Keep this screen open; do not type a recovery code here.")
                } else {
                    if model.preparedAt != nil {
                        phaseTitle("2. Scan with this old phone")
                        Text("History is ready on this OLD phone. On the NEW phone choose Receive history and Show QR on this phone. Then scan the new phone's square using this old phone.")
                        Button("Scan the new phone's code") { model.scan() }.disabled(model.busy).accessibilityIdentifier("history-transfer-scan")
                    } else if receiving {
                        phaseTitle("1. Prepare your old phone")
                        Text("On the OLD phone that already has your messages, open History and backups and choose Send history. Wait until it is ready. Then show the QR here, on this NEW phone. Both phones must use the account and server shown above.")
                        Button("Show QR on this phone") { model.receive() }.disabled(model.busy).accessibilityIdentifier("history-transfer-show-qr")
                    } else {
                        phaseTitle("Which phone is this?")
                        Text("OLD phone: your messages are already here. NEW phone: you want to receive those messages here. Select the role of the phone in your hand.")
                        UnyriSettingsNote("Before sending, prepare the old phone in History and backups and keep its recovery key saved outside UNYRI.")
                        Button("This is the OLD phone · send history") { model.prepareSource() }.disabled(model.busy).accessibilityIdentifier("history-transfer-start-source")
                        Button("This is the NEW phone · receive history") { receiving = true }.buttonStyle(UnyriHistoryActionStyle()).disabled(model.busy).accessibilityIdentifier("history-transfer-start-receive")
                    }
                }
            }
        }
        .task {
            guard !started else { return }
            started = true
            if let initialReceive {
                receiving = initialReceive
                if !initialReceive { model.prepareSource() }
            }
        }
        .sheet(isPresented: $model.scanning) {
            VStack(spacing: 16) {
                Text("This is the OLD phone's camera. Point it at the QR displayed on your NEW phone; it will be read automatically.")
                    .font(.headline).fixedSize(horizontal: false, vertical: true).padding()
                HistoryQRScanner { model.scanned($0) } failure: { model.scanning = false; model.error = "The camera could not scan this code. Close other camera apps and try again." }
                Button("Cancel") { model.scanning = false }.buttonStyle(UnyriHistoryActionStyle()).padding(.horizontal, 16).padding(.bottom, 16).accessibilityIdentifier("history-transfer-scan-cancel")
            }.unyriScreenBackground()
        }
        .alert("Are the codes identical on both phones?", isPresented: $confirmAccept) {
            Button("Cancel", role: .cancel) {}
            Button("Yes, transfer my history") { model.acceptMatchingCodes() }
        } message: {
            Text("Compare every letter and number on both screens. Confirm only if everything matches. This lets your new phone receive your private messages and files.")
        }
    }
    private func phaseTitle(_ title: String) -> some View {
        Text(LocalizedStringKey(title)).font(.title3.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
            .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("history-transfer-phase")
    }
    private func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 8, y: 8)), from: output.extent.applying(CGAffineTransform(scaleX: 8, y: 8))) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct HistoryQRScanner: UIViewControllerRepresentable {
    let scanned: (String) -> Void
    let failure: () -> Void
    func makeUIViewController(context: Context) -> Camera { Camera(scanned: scanned, failure: failure) }
    func updateUIViewController(_ uiViewController: Camera, context: Context) {}
    static func dismantleUIViewController(_ controller: Camera, coordinator: ()) { controller.stop() }
    final class Camera: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        let queue = DispatchQueue(label: "unyri.history-qr-camera")
        let scanned: (String) -> Void
        let failure: () -> Void
        var preview: AVCaptureVideoPreviewLayer?
        var delivered = false
        init(scanned: @escaping (String) -> Void, failure: @escaping () -> Void) { self.scanned = scanned; self.failure = failure; super.init(nibName: nil, bundle: nil) }
        required init?(coder: NSCoder) { fatalError("Not used") }
        override func viewDidLoad() {
            super.viewDidLoad()
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { failure(); return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { failure(); return }
            session.addOutput(output); output.setMetadataObjectsDelegate(self, queue: .main); output.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspectFill
            preview = layer; view.layer.addSublayer(layer)
            queue.async { [session] in session.startRunning() }
        }
        override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
        func stop() { queue.async { [session] in session.stopRunning() } }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !delivered, let text = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue, text.utf8.count <= 1024 else { return }
            delivered = true; stop(); scanned(text)
        }
    }
}
