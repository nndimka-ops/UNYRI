import SwiftUI
import QuickLook
import UniformTypeIdentifiers

struct DirectFileSendSelection: Identifiable {
    let id = UUID()
    let documents: [AttachmentFileCopy.DocumentSource]
    var urls: [URL] { documents.map(\.url) }
}

struct DirectFileSendSheet: View {
    let selection: DirectFileSendSelection
    let chat: ChatSummary
    @ObservedObject var transfers: DirectFileTransfers
    let close: () -> Void
    @State private var sent = 0
    @State private var sending = false
    @State private var operation: Task<Void, Never>?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("Original files, without compression", systemImage: "doc.on.doc")
                    Text("The recipient accepts the file first. Keep UNYRI open on both devices. The file is transferred directly and is not uploaded to the server.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    ForEach(Array(selection.urls.enumerated()), id: \.offset) { index, url in
                        HStack(spacing: 12) {
                            Image(systemName: index < sent ? "checkmark.circle.fill" : "doc")
                                .frame(width: 28).foregroundStyle(index < sent ? UnyriColors.accentSoft : UnyriColors.secondaryText)
                            Text(DirectFileOffer.safeFilename(url.lastPathComponent)).lineLimit(2)
                        }.frame(minHeight: 44)
                    }
                }
                if let error = transfers.errorMessage {
                    Section { Text(UnyriL10n.string(error)).font(.footnote).foregroundStyle(UnyriColors.danger) }
                }
            }
            .navigationTitle("Send directly").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { operation?.cancel(); transfers.cancelPreparing(); close() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    sending = true
                    operation = Task { @MainActor in
                        let documents = selection.documents
                        defer { sending = false; withExtendedLifetime(documents) {} }
                        for document in documents.dropFirst(sent) {
                            let url = document.url
                            let queued = await transfers.offer(source: url, chat: chat)
                            guard queued, !Task.isCancelled else { return }
                            sent += 1
                        }
                        close()
                    }
                } label: {
                    HStack {
                        if sending { ProgressView().controlSize(.small) }
                        Text(sending ? "Preparing original" : "Send directly")
                    }.frame(maxWidth: .infinity, minHeight: 48)
                }.unyriProminentAction()
                    .disabled(sending).padding()
            }
        }
        .interactiveDismissDisabled(sending)
        .onDisappear { operation?.cancel() }
    }
}

struct UnyriDirectFileCard: View {
    let offer: DirectFileOffer
    let chatId: String
    let outgoing: Bool
    let selecting: Bool
    let select: (() -> Void)?
    var text: String? = nil
    var historyOnly = false
    @EnvironmentObject private var messages: MessagesRepository
    var body: some View {
        Group {
            if let transfers = messages.directFiles {
                DirectFileStatusCard(offer: offer, chatId: chatId, outgoing: outgoing, selecting: selecting,
                    select: select, text: text, historyOnly: historyOnly, transfers: transfers)
            } else {
                Label(offer.filename, systemImage: "doc").font(.subheadline).lineLimit(2)
            }
        }.frame(maxWidth: 264, alignment: .leading)
    }
}

private struct DirectFileStatusCard: View {
    let offer: DirectFileOffer
    let chatId: String
    let outgoing: Bool
    let selecting: Bool
    let select: (() -> Void)?
    let text: String?
    let historyOnly: Bool
    let transfers: DirectFileTransfers
    @ObservedObject private var row: DirectFileRowState
    init(offer: DirectFileOffer, chatId: String, outgoing: Bool, selecting: Bool, select: (() -> Void)?,
         text: String?, historyOnly: Bool, transfers: DirectFileTransfers) {
        self.offer = offer; self.chatId = chatId; self.outgoing = outgoing; self.selecting = selecting
        self.select = select; self.text = text; self.historyOnly = historyOnly; self.transfers = transfers
        row = transfers.rowState(offer.transferId)
    }
    @State private var preview: URL?
    @State private var export: DirectFileExport?
    @State private var confirmRestart = false
    private enum CompletedFileAction { case open, share }
    @State private var failedFileAction: CompletedFileAction?
    @Environment(\.dynamicTypeSize) private var typeSize
    private var record: DirectFileRecord? {
        guard let value = transfers.records[offer.transferId],
              value.offer == offer, value.chatId == chatId else { return nil }
        return value
    }
    private var live: DirectFilePresentation? { record == nil ? nil : row.value }
    private var phase: DirectFilePhase { live?.phase ?? record?.phase ?? (outgoing ? .awaitingAcceptance : .offered) }
    private var snapshot: DirectFileProgress {
        guard let record else { return DirectFileProgress(offset: 0) }
        return row.progress.offset > 0 ? row.progress : DirectFileProgress(offset: record.durableOffset)
    }
    private var received: Int64 { min(offer.size, max(0, snapshot.offset)) }
    private var locale: Locale { Locale(identifier: UnyriL10n.language) }
    private var byteUnits: [String] { ["B", "KB", "MB", "GB"].map { UnyriL10n.string($0) } }
    private var rateUnits: [String] { ["B/s", "KB/s", "MB/s", "GB/s"].map { UnyriL10n.string($0) } }
    private var pauseReason: String { live?.status ?? DirectFileFailureText.status(record?.errorCode, outgoing: outgoing) }
    private var hasContextActions: Bool {
        guard let live else { return false }
        return live.backgroundStatus != nil || live.actions.contains(.allowBackground) || live.actions.contains(.retrySync) || live.actions.contains(.retryPauseSave) || live.actions.contains(.retryContinueSave)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if typeSize.isAccessibilitySize {
                fileIdentity
                HStack(spacing: 0) { Spacer(minLength: 0); headerActions }
            } else {
                HStack(alignment: .top, spacing: 6) {
                    fileIdentity
                    headerActions.fixedSize(horizontal: true, vertical: false)
                }
            }
            if phase == .transferring || phase == .verifying { progressFooter }
            else { statusFooter }
            if let live, hasContextActions {
                DirectFileHoldActions(value: live, transfers: transfers, showStatus: false, placement: .context)
            }
            if let caption = DirectFileOffer.caption(in: text) { Text(caption).font(.subheadline) }
        }
        .tint(UnyriColors.accentSoft)
        .allowsHitTesting(!selecting && record != nil)
        .contentShape(Rectangle())
        .onTapGesture { if selecting { select?() } }
        .quickLookPreview($preview)
        .sheet(item: $export) { item in DirectFileShareSheet(file: item) }
        .confirmationDialog("Start the transfer again?", isPresented: $confirmRestart, titleVisibility: .visible) {
            Button("Start again", role: .destructive) { transfers.restartFromZero(offer.transferId) }
        } message: { Text("The saved partial file does not match. Only this unfinished copy will be replaced.") }
    }
    private var fileIdentity: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: offer.mimeType.hasPrefix("video/") ? "video" : "doc")
                .font(.system(size: 22, weight: .medium)).frame(width: 28, height: 38)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(offer.filename).font(.subheadline.weight(.semibold)).lineLimit(2)
                    .accessibilityLabel(Text(offer.filename))
                Text(DirectFileDisplay.bytes(UInt64(offer.size), locale: locale, units: byteUnits))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private var headerActions: some View {
        HStack(spacing: 0) {
            if let live, live.actions.contains(.pause) || live.actions.contains(.resume) {
                DirectFileHoldActions(value: live, transfers: transfers, showStatus: false, placement: .header)
            }
            if live?.actions.contains(.cancel) == true {
                icon("xmark", label: "Cancel transfer") { transfers.perform(.cancel, id: offer.transferId) }
            } else if phase == .completed, failedFileAction == nil {
                Button { requestFile(.open) } label: {
                    Text("Open").font(.caption.weight(.medium)).frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain)
                icon("square.and.arrow.up", label: "Share") { requestFile(.share) }
            }
        }
    }
    @ViewBuilder private var statusFooter: some View {
        if record == nil {
            Text(UnyriL10n.string(historyOnly ? "File not copied with history. Ask the sender to send it again." : "Waiting for transfer information…"))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        } else if phase == .offered, !outgoing {
            HStack(spacing: 8) {
                Button { transfers.perform(.reject, id: offer.transferId) } label: {
                    Text("Decline").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.bordered)
                Button { transfers.perform(.accept, id: offer.transferId) } label: {
                    Text("Accept").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }.unyriProminentAction()
            }
        } else if phase == .completed {
            if let failedFileAction {
                HStack(spacing: 6) {
                    Text(UnyriL10n.string("Could not open this file. Check that it is available on this device, then try again."))
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    icon("arrow.clockwise", label: "Retry") { requestFile(failedFileAction) }
                }
            }
        } else if phase == .paused || phase == .failed {
            HStack(spacing: 6) {
                Text(UnyriL10n.string(live.map { $0.status == phase.label ? pauseReason : $0.status } ?? pauseReason))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if outgoing, live?.actions.contains(.retry) == true {
                    icon("arrow.clockwise", label: "Retry") { transfers.perform(.retry, id: offer.transferId) }
                } else if !outgoing, live?.actions.contains(.retry) == true || live?.actions.contains(.restart) == true {
                    icon("arrow.clockwise", label: record?.errorCode == "prefix_mismatch" ? "Start again" : "Retry") {
                        if record?.errorCode == "prefix_mismatch" { confirmRestart = true }
                        else { transfers.perform(.retry, id: offer.transferId) }
                    }
                }
            }
        } else {
            Text(LocalizedStringKey(live?.status ?? phase.label)).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
    private var progressFooter: some View {
        VStack(alignment: .leading, spacing: 4) {
            ProgressView(value: Double(received), total: Double(offer.size)).tint(UnyriColors.accentSoft)
                .accessibilityLabel("Direct file transfer")
                .accessibilityValue(Text("\(DirectFileDisplay.percent(offset: received, total: offer.size))%"))
            if typeSize.isAccessibilitySize {
                Text("\(DirectFileDisplay.percent(offset: received, total: offer.size))% · \(DirectFileDisplay.bytes(UInt64(received), locale: locale, units: byteUnits))")
                if snapshot.speedBytesPerSecond > 0 {
                    Text(DirectFileDisplay.bytes(snapshot.speedBytesPerSecond, locale: locale, units: rateUnits))
                    if let eta = DirectFileDisplay.eta(snapshot.remainingSeconds(total: offer.size), overOneDay: UnyriL10n.string(">24h")) { Text(eta) }
                } else if received == offer.size { Text("Verifying file") }
            } else {
                HStack(spacing: 6) {
                    Text("\(DirectFileDisplay.percent(offset: received, total: offer.size))% · \(DirectFileDisplay.bytes(UInt64(received), locale: locale, units: byteUnits))")
                    Spacer(minLength: 0)
                    if snapshot.speedBytesPerSecond > 0 {
                        Text(DirectFileDisplay.bytes(snapshot.speedBytesPerSecond, locale: locale, units: rateUnits))
                        if let eta = DirectFileDisplay.eta(snapshot.remainingSeconds(total: offer.size), overOneDay: UnyriL10n.string(">24h")) { Text(eta) }
                    } else if received == offer.size { Text("Verifying file") }
                }.lineLimit(1).minimumScaleFactor(0.8)
            }
        }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
    }
    private func icon(_ image: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: image).font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(label)))
    }
    private func requestFile(_ action: CompletedFileAction) {
        failedFileAction = nil
        Task {
            let file: URL?
            switch action {
            case .open: file = await transfers.fileURL(offer.transferId)
            case .share: file = await transfers.shareURL(offer.transferId)
            }
            guard let url = file else {
                failedFileAction = action
                return
            }
            switch action {
            case .open: preview = url
            case .share: export = DirectFileExport(url: url, filename: offer.filename)
            }
        }
    }
}

enum DirectFileHoldPlacement: Equatable { case full, header, context }

struct DirectFileHoldActions: View {
    let value: DirectFilePresentation
    let transfers: DirectFileTransfers
    var showStatus = true
    var placement: DirectFileHoldPlacement = .full
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if placement != .header {
                if let status = value.backgroundStatus {
                    Text(UnyriL10n.string(status)).font(.caption).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if value.actions.contains(.allowBackground) {
                    Button { transfers.perform(.allowBackground, id: value.id) } label: {
                        Label("Allow background", systemImage: "arrow.up.forward.app")
                            .font(.caption.weight(.medium)).frame(minHeight: 44)
                    }.buttonStyle(.plain).accessibilityIdentifier("transfer-allow-background")
                }
            }
            if placement == .context {
                HStack(spacing: 8) { repairActions }
            } else if value.actions.contains(.pause) || value.actions.contains(.resume) || value.actions.contains(.retrySync) || value.actions.contains(.retryPauseSave) || value.actions.contains(.retryContinueSave) {
                HStack(spacing: placement == .header ? 0 : 8) {
                    if showStatus, value.status != value.phase.label {
                        Text(UnyriL10n.string(value.status)).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                    }
                    if showStatus { Spacer(minLength: 0) }
                    if value.actions.contains(.resume) {
                        action("play.fill", label: "Continue transfer", kind: .resume)
                    } else if value.actions.contains(.pause) {
                        action("pause.fill", label: "Pause transfer", kind: .pause)
                    }
                    if placement == .full { repairActions }
                }
            }
        }
    }
    @ViewBuilder private var repairActions: some View {
        if value.actions.contains(.retryPauseSave) {
            action("arrow.clockwise", label: "Retry saving pause", kind: .retryPauseSave)
        } else if value.actions.contains(.retryContinueSave) {
            action("arrow.clockwise", label: "Retry continuation", kind: .retryContinueSave)
        } else if value.actions.contains(.retrySync) {
            action("arrow.clockwise", label: "Retry synchronization", kind: .retrySync)
        }
    }
    private func action(_ image: String, label: String, kind: DirectFileAction) -> some View {
        Button { transfers.perform(kind, id: value.id) } label: {
            Image(systemName: image).font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(label)))
    }
}

struct DirectFileExport: Identifiable {
    let id = UUID()
    let url: URL
    let filename: String
}
struct DirectFileShareSheet: UIViewControllerRepresentable {
    let file: DirectFileExport
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let provider = NSItemProvider(contentsOf: file.url) ?? NSItemProvider()
        provider.suggestedName = file.filename
        return UIActivityViewController(activityItems: [provider], applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
