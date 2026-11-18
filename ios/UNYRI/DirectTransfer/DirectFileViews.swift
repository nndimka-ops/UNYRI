import SwiftUI
import QuickLook
import UniformTypeIdentifiers
import UIKit

struct DirectFileSendSelection: Identifiable {
    let id = UUID()
    let documents: [AttachmentFileCopy.DocumentSource]
    let intent: MediaSendIntent
    init(documents: [AttachmentFileCopy.DocumentSource], intent: MediaSendIntent = MediaSendIntent()) {
        self.documents = documents; self.intent = intent
    }
    var urls: [URL] { documents.map(\.url) }
}

struct DirectFileSendSheet: View {
    let selection: DirectFileSendSelection
    let chat: ChatSummary
    @ObservedObject var transfers: DirectFileTransfers
    let close: () -> Void
    @State private var sent = 0
    @State private var sourceIssue: String?
    @State private var sending = false
    @State private var operation: Task<Void, Never>?
    private var destinationIssue: String? { transfers.destinationIssue(chat) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("Direct transfer", systemImage: "arrow.up.arrow.down")
                    Text("The recipient accepts first. Offline devices wait. Keep UNYRI open; iOS may pause in the background. The file itself is not stored on the server. Encrypted transfer requests and previews are.")
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
                if let error = destinationIssue ?? sourceIssue ?? transfers.errorMessage {
                    Section { Text(UnyriL10n.string(error)).font(.footnote).foregroundStyle(UnyriColors.danger) }
                }
            }
            .navigationTitle("Send directly").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { operation?.cancel(); transfers.cancelPreparing(); close() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    sending = true; sourceIssue = nil
                    operation = Task { @MainActor in
                        let documents = selection.documents
                        defer { sending = false; withExtendedLifetime(documents) {} }
                        for document in documents.dropFirst(sent) {
                            let url = document.url
                            let reader = AttachmentFileCopy.DocumentRead()
                            let metadata = Task.detached(priority: .utility) { try reader.size(of: url) }
                            let size: Int64? = try? await withTaskCancellationHandler(operation: { try await metadata.value },
                                onCancel: { reader.cancel(); metadata.cancel() })
                            guard !Task.isCancelled else { return }
                            if let issue = DirectFileDestinationPolicy.directSourceIssue(size: size) {
                                sourceIssue = issue; return // Keep this and later selections for correction/retry.
                            }
                            let queued = await transfers.offer(source: url, chat: chat, caption: selection.intent.caption(at: sent),
                                albumId: selection.intent.albumId, albumItemIndex: sent)
                            guard queued, !Task.isCancelled else { return }
                            sent += 1
                        }
                        close()
                    }
                } label: {
                    HStack {
                        if sending { ProgressView().controlSize(.small) }
                        Text(sending ? "Preparing file" : "Send directly")
                    }.frame(maxWidth: .infinity, minHeight: 48)
                }.unyriProminentAction()
                    .disabled(sending || destinationIssue != nil).padding()
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
    var inAlbum = false
    /// Time + ticks shown at the end of the size/status line (no separate row).
    var metadata: AnyView? = nil
    @EnvironmentObject private var messages: MessagesRepository
    var body: some View {
        Group {
            if let transfers = messages.directFiles {
                DirectFileStatusCard(offer: offer, chatId: chatId, outgoing: outgoing, selecting: selecting,
                    select: select, text: text, historyOnly: historyOnly, transfers: transfers, metadata: metadata)
            } else {
                Label(offer.filename, systemImage: "doc").font(.subheadline).lineLimit(2)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
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
    let metadata: AnyView?
    @ObservedObject private var row: DirectFileRowState
    private let mediaPreview: DirectFileMediaPreview?
    init(offer: DirectFileOffer, chatId: String, outgoing: Bool, selecting: Bool, select: (() -> Void)?,
         text: String?, historyOnly: Bool, transfers: DirectFileTransfers, metadata: AnyView? = nil) {
        self.offer = offer; self.chatId = chatId; self.outgoing = outgoing; self.selecting = selecting
        self.select = select; self.text = text; self.historyOnly = historyOnly; self.transfers = transfers
        self.metadata = metadata
        row = transfers.rowState(offer.transferId)
        let safe = offer.safeMediaPreview
        mediaPreview = safe
    }
    @State private var preview: URL?
    @State private var export: DirectFileExport?
    @State private var confirmRestart = false
    @State private var confirmCancel = false
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
            else {
                if received > 0, received < offer.size, !phase.terminal {
                    VStack(alignment: .leading, spacing: 5) {
                        DirectFileProgressBar(fraction: Double(received) / Double(max(1, offer.size)), dimmed: true)
                        Text(amountText).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                statusFooter
            }
            if let position = live?.queuePosition, position > 1, !phase.terminal {
                Text(String(format: UnyriL10n.string("Queue position: %d"), position))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            if let live, hasContextActions {
                DirectFileHoldActions(value: live, transfers: transfers, showStatus: false, placement: .context)
            }
            if !outgoing, offer.mayBeHarmful, phase == .offered || phase == .completed {
                Label("Files of this type can be harmful. Open it only if you trust the sender.", systemImage: "exclamationmark.shield")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            if let caption = DirectFileOffer.caption(in: text) { Text(caption).font(.subheadline) }
        }

        .tint(UnyriColors.accentSoft)
        .allowsHitTesting(!selecting && record != nil)
        .contentShape(Rectangle())
        .onTapGesture { if selecting { select?() } }
        .unyriQuickLookPreview($preview, title: offer.filename)
        .sheet(item: $export) { item in DirectFileShareSheet(file: item) }
        .confirmationDialog("Start the transfer again?", isPresented: $confirmRestart, titleVisibility: .visible) {
            Button("Start again", role: .destructive) { transfers.restartFromZero(offer.transferId) }
        } message: { Text("The saved partial file does not match. Only this unfinished copy will be replaced.") }
        .confirmationDialog("Cancel this transfer?", isPresented: $confirmCancel, titleVisibility: .visible) {
            Button("Cancel transfer", role: .destructive) { transfers.perform(.cancel, id: offer.transferId) }
        } message: { Text("This unfinished transfer will be stopped and its partial data will be removed.") }
    }
    /// Short terminal/progress states read inline after the size ("114 KB · Cancelled"),
    /// like a Telegram file row, instead of a separate footer line.
    private var inlineStatus: String? {
        guard record != nil, !(phase == .offered && !outgoing), phase != .completed,
              phase != .paused, phase != .failed, phase != .transferring, phase != .verifying else { return nil }
        return UnyriL10n.string(live?.status ?? phase.label)
    }
    /// Compact Telegram-like file row: a small thumbnail (or file icon), name, size · status.
    private var fileIdentity: some View {
        HStack(alignment: .center, spacing: 10) {
            thumbnail
            VStack(alignment: .leading, spacing: 2) {
                Text(offer.filename).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                    .accessibilityLabel(Text(offer.filename))
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text([DirectFileDisplay.bytes(UInt64(offer.size), locale: locale, units: byteUnits), inlineStatus].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    if let metadata { Spacer(minLength: 6); metadata.fixedSize() }
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private var thumbnail: some View {
        ZStack {
            DirectFileAttachmentThumbnail(offer: offer, transfers: transfers)
            if mediaPreview?.kind == .video {
                Image(systemName: "play.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 26, height: 26).background(.black.opacity(0.55), in: Circle())
            }
        }
        .frame(width: 46, height: 46).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { if phase == .completed { requestFile(.open) } }
        .accessibilityHidden(true)
    }
    private var headerActions: some View {
        HStack(spacing: 0) {
            if let live, live.actions.contains(.pause) || live.actions.contains(.resume) {
                DirectFileHoldActions(value: live, transfers: transfers, showStatus: false, placement: .header)
            }
            if live?.actions.contains(.cancel) == true {
                icon("xmark", label: "Cancel transfer") { confirmCancel = true }
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
            let expired = !historyOnly && offer.expiresAt <= Int64(Date().timeIntervalSince1970)
            Text(UnyriL10n.string(historyOnly ? "File not copied with history. Ask the sender to send it again." : expired ? DirectFilePhase.expired.label : "Waiting for transfer information…"))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        } else if phase == .offered, !outgoing {
            Text("Wants to send you this file directly, phone to phone.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
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
        } else if inlineStatus == nil {
            Text(LocalizedStringKey(live?.status ?? phase.label)).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
    /// "12.4 of 29.8 MB", from the receiver-confirmed bytes both sides share.
    private var amountText: String {
        String(format: UnyriL10n.string("%@ of %@"),
               DirectFileDisplay.bytes(UInt64(received), locale: locale, units: byteUnits),
               DirectFileDisplay.bytes(UInt64(offer.size), locale: locale, units: byteUnits))
    }
    /// "3.1 MB/s · 00:06" while bytes flow; nothing while waiting (never an invented speed).
    private var rateText: String? {
        guard snapshot.speedBytesPerSecond > 0 else { return nil }
        let speed = DirectFileDisplay.bytes(snapshot.speedBytesPerSecond, locale: locale, units: rateUnits)
        let eta = DirectFileDisplay.eta(snapshot.remainingSeconds(total: offer.size), overOneDay: UnyriL10n.string(">24h"))
        return [speed, eta].compactMap { $0 }.joined(separator: " · ")
    }
    private var progressFooter: some View {
        VStack(alignment: .leading, spacing: 5) {
            DirectFileProgressBar(fraction: Double(received) / Double(max(1, offer.size)))
                .accessibilityElement()
                .accessibilityLabel(DirectFilePreviewText.file(offer.filename))
                .accessibilityValue(Text("\(DirectFileDisplay.percent(offset: received, total: offer.size))%"))
            if typeSize.isAccessibilitySize {
                Text(amountText)
                if let rateText { Text(rateText) } else if received == offer.size { Text("Verifying file") }
            } else {
                HStack(spacing: 6) {
                    Text(amountText)
                    Spacer(minLength: 0)
                    if let rateText { Text(rateText) } else if received == offer.size { Text("Verifying file") }
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

/// A thin monochrome bar. Progress arrives at most four times a second; the fill
/// glides between samples (only its width animates, no continuous animation).
struct DirectFileProgressBar: View {
    let fraction: Double
    var dimmed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let value = min(1, max(0, fraction.isFinite ? fraction : 0))
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.12))
                Capsule().fill(Color.primary.opacity(dimmed ? 0.45 : 0.85))
                    .frame(width: value > 0 ? max(5, geometry.size.width * value) : 0)
            }
        }
        .frame(height: 5)
        .animation(reduceMotion ? nil : .linear(duration: 0.25), value: value)
    }
}

extension DirectFileOffer {
    /// Types that can install, run or phish on another device when shared on.
    /// Nothing is ever opened automatically; this only adds a warning.
    var mayBeHarmful: Bool {
        let kinds: Set<String> = ["apk", "ipa", "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "sh", "command",
            "app", "dmg", "pkg", "jar", "js", "mobileconfig", "shortcut", "html", "htm", "xhtml", "svg",
            "docm", "xlsm", "pptm", "lnk", "reg", "iso"]
        return kinds.contains((filename as NSString).pathExtension.lowercased())
    }
}

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

struct UnyriDirectFileAlbumHeader: View {
    let offers: [DirectFileOffer]
    let outgoing: Bool
    var chatId: String? = nil
    var albumId: String? = nil
    @Binding var isExpanded: Bool
    @EnvironmentObject private var messages: MessagesRepository
    var body: some View {
        if let transfers = messages.directFiles, offers.count > 1 {
            DirectFileAlbumSummaryBar(offers: offers, outgoing: outgoing, chatId: chatId, albumId: albumId, isExpanded: $isExpanded, transfers: transfers)
        }
    }
}

private struct DirectFileAlbumSummaryBar: View {
    @Environment(\.unyriAppColorStyle) private var directColors
    let offers: [DirectFileOffer]
    let outgoing: Bool
    let chatId: String?
    let albumId: String?
    @Binding var isExpanded: Bool
    @ObservedObject var transfers: DirectFileTransfers
    @State private var confirmCancelBatch = false
    private var locale: Locale { Locale(identifier: UnyriL10n.language) }
    private var byteUnits: [String] { ["B", "KB", "MB", "GB"].map { UnyriL10n.string($0) } }
    private var rateUnits: [String] { ["B/s", "KB/s", "MB/s", "GB/s"].map { UnyriL10n.string($0) } }
    private var presentations: [DirectFilePresentation] {
        offers.compactMap { transfers.rowState($0.transferId).value }
    }
    private var pendingSlotCount: Int {
        outgoing ? transfers.pendingBatchSlotCount(chatId: chatId, albumId: albumId) : 0
    }
    private var totalItemCount: Int { offers.count + pendingSlotCount }
    private var totalBytes: Int64 { offers.reduce(0) { $0 + max(0, $1.size) } }
    private var completedCount: Int {
        offers.filter { transfers.records[$0.transferId]?.phase == .completed }.count
    }
    private var transferredBytes: Int64 {
        offers.reduce(0) { sum, offer in
            let id = offer.transferId
            if transfers.records[id]?.phase == .completed { return sum + offer.size }
            let progressOffset = transfers.progress[id]?.offset ?? 0
            let durableOffset = transfers.records[id]?.durableOffset ?? 0
            return sum + min(offer.size, max(0, max(progressOffset, durableOffset)))
        }
    }
    private var aggregateSpeed: UInt64 {
        offers.reduce(0) { sum, offer in
            let id = offer.transferId
            guard let phase = transfers.records[id]?.phase, phase == .transferring else { return sum }
            return sum + (transfers.progress[id]?.speedBytesPerSecond ?? 0)
        }
    }
    private var remainingSeconds: Int64? {
        guard aggregateSpeed > 0, totalBytes > transferredBytes else { return nil }
        let rem = UInt64(totalBytes - transferredBytes)
        return Int64(rem / aggregateSpeed + (rem % aggregateSpeed == 0 ? 0 : 1))
    }
    private var acceptIDs: [String] {
        presentations.filter { $0.actions.contains(.accept) }.map(\.id)
    }
    private var rejectIDs: [String] {
        presentations.filter { $0.actions.contains(.reject) }.map(\.id)
    }
    private var pauseIDs: [String] {
        presentations.filter { $0.actions.contains(.pause) }.map(\.id)
    }
    private var resumeIDs: [String] {
        presentations.filter { $0.actions.contains(.resume) }.map(\.id)
    }
    private var cancelIDs: [String] {
        presentations.filter { $0.actions.contains(.cancel) }.map(\.id)
    }
    private var queuedCount: Int {
        presentations.filter { ($0.queuePosition ?? 0) > 1 && !$0.phase.terminal }.count
    }
    private var statusSummary: String {
        if completedCount == totalItemCount && totalItemCount > 0 {
            return UnyriL10n.string(outgoing ? "Sent" : "Saved on this device")
        }
        if !acceptIDs.isEmpty && !outgoing && transferredBytes == 0 {
            return UnyriL10n.string("Ready to accept")
        }
        var parts = ["\(completedCount)/\(totalItemCount)"]
        parts.append("\(DirectFileDisplay.bytes(UInt64(transferredBytes), locale: locale, units: byteUnits)) / \(DirectFileDisplay.bytes(UInt64(totalBytes), locale: locale, units: byteUnits))")
        if pendingSlotCount > 0 {
            parts.append("+\(pendingSlotCount) " + UnyriL10n.string("in queue"))
        }
        return parts.joined(separator: " · ")
    }
    var body: some View {
        let visualOffers = offers.filter { $0.safeMediaPreview != nil }
        VStack(alignment: .leading, spacing: 8) {
            if !visualOffers.isEmpty && !isExpanded {
                HStack(spacing: 6) {
                    ForEach(Array(visualOffers.prefix(4).enumerated()), id: \.element.transferId) { idx, offer in
                        ZStack {
                            DirectFileAttachmentThumbnail(offer: offer, transfers: transfers)
                                .frame(width: 48, height: 48).clipped()
                            if offer.safeMediaPreview?.kind == .video {
                                Circle().fill(.black.opacity(0.55)).frame(width: 20, height: 20)
                                    .overlay { Image(systemName: "play.fill").font(.system(size: 8, weight: .bold)).foregroundStyle(.white) }
                            }
                            if idx == 3 && visualOffers.count > 4 {
                                Color.black.opacity(0.55)
                                Text("+\(visualOffers.count - 3)")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                            }
                        }
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    Spacer(minLength: 0)
                }
            }
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    Circle().fill(UnyriColors.accentSoft.opacity(0.18)).frame(width: 36, height: 36)
                    Image(systemName: completedCount == totalItemCount ? "checkmark.circle.fill" : (outgoing ? "arrow.up.doc.on.clipboard" : "arrow.down.doc"))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(completedCount == totalItemCount ? directColors.decorative(Color.green) : UnyriColors.accentSoft)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(UnyriL10n.string("Files") + ": \(totalItemCount) · " + DirectFileDisplay.bytes(UInt64(totalBytes), locale: locale, units: byteUnits))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                    Text(statusSummary)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                }
                Spacer(minLength: 4)
            }
            if (transferredBytes > 0 || !pauseIDs.isEmpty || !resumeIDs.isEmpty), completedCount < totalItemCount {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: Double(transferredBytes), total: Double(max(1, totalBytes)))
                        .tint(pauseIDs.isEmpty && !resumeIDs.isEmpty ? directColors.decorative(Color.orange) : UnyriColors.accentSoft)
                    HStack(spacing: 6) {
                        Text("\(DirectFileDisplay.percent(offset: transferredBytes, total: totalBytes))%")
                        if queuedCount + pendingSlotCount > 0 {
                            Text("· \(queuedCount + pendingSlotCount) " + UnyriL10n.string("in queue"))
                        }
                        Spacer(minLength: 0)
                        if aggregateSpeed > 0 {
                            Text(DirectFileDisplay.bytes(aggregateSpeed, locale: locale, units: rateUnits))
                            if let eta = DirectFileDisplay.eta(remainingSeconds, overOneDay: UnyriL10n.string(">24h")) {
                                Text(eta)
                            }
                        }
                    }.font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            if !isExpanded {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(offers.prefix(3), id: \.transferId) { offer in
                        let phase = transfers.records[offer.transferId]?.phase
                        HStack(spacing: 6) {
                            Image(systemName: phase == .completed ? "checkmark.circle.fill" : (phase == .transferring ? "arrow.up.arrow.down.circle.fill" : "doc.fill"))
                                .font(.system(size: 11))
                                .foregroundStyle(phase == .completed ? directColors.decorative(Color.green) : .secondary)
                            Text(offer.filename)
                                .font(.caption)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer(minLength: 4)
                            Text(DirectFileDisplay.bytes(UInt64(max(0, offer.size)), locale: locale, units: byteUnits))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    if offers.count > 3 {
                        Text("+\(offers.count - 3) " + UnyriL10n.string("more"))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                .padding(.horizontal, 8)
                .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            let hasActions = (!acceptIDs.isEmpty && !outgoing) || pauseIDs.count > 1 || resumeIDs.count > 1 || cancelIDs.count > 1
            if hasActions {
                HStack(spacing: 8) {
                    if !acceptIDs.isEmpty, !outgoing {
                        Button {
                            transfers.performBatch(.accept, ids: acceptIDs)
                        } label: {
                            Text(UnyriL10n.string("Accept all")).font(.caption.weight(.semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                        }.unyriProminentAction()
                        if !rejectIDs.isEmpty {
                            Button {
                                transfers.performBatch(.reject, ids: rejectIDs)
                            } label: {
                                Text(UnyriL10n.string("Decline")).font(.caption)
                                    .lineLimit(1)
                                    .padding(.horizontal, 8).padding(.vertical, 5)
                            }.buttonStyle(.bordered)
                        }
                    } else {
                        if pauseIDs.count > 1 {
                            Button {
                                transfers.performBatch(.pause, ids: pauseIDs)
                            } label: {
                                Label(UnyriL10n.string("Pause all"), systemImage: "pause.fill")
                                    .font(.caption.weight(.medium))
                                    .lineLimit(1)
                                    .padding(.horizontal, 8).padding(.vertical, 5)
                            }.buttonStyle(.bordered)
                        } else if resumeIDs.count > 1 {
                            Button {
                                transfers.performBatch(.resume, ids: resumeIDs)
                            } label: {
                                Label(UnyriL10n.string("Continue all"), systemImage: "play.fill")
                                    .font(.caption.weight(.medium))
                                    .lineLimit(1)
                                    .padding(.horizontal, 8).padding(.vertical, 5)
                            }.buttonStyle(.bordered)
                        }
                        if cancelIDs.count > 1 {
                            Button(role: .destructive) {
                                confirmCancelBatch = true
                            } label: {
                                Text(UnyriL10n.string("Cancel"))
                                    .font(.caption)
                                    .lineLimit(1)
                                    .padding(.horizontal, 8).padding(.vertical, 5)
                            }.buttonStyle(.bordered)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                    Text(isExpanded ? UnyriL10n.string("Hide files") : "\(UnyriL10n.string("Show files")) (\(offers.count))")
                        .font(.caption.weight(.semibold))
                }
                .foregroundStyle(UnyriColors.accentSoft)
                .padding(.vertical, 3)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("direct-album-toggle-files")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("direct-album-summary")

        .confirmationDialog(
            UnyriL10n.string("Cancel transfer?"),
            isPresented: $confirmCancelBatch,
            titleVisibility: .visible
        ) {
            Button(UnyriL10n.string("Cancel transfer"), role: .destructive) {
                transfers.performBatch(.cancel, ids: cancelIDs)
            }
            Button(UnyriL10n.string("Keep transfer"), role: .cancel) {}
        }
    }
}

/// The offer is a loading preview; completed local bytes always outrank it.
private struct DirectFileAttachmentThumbnail: View {
    let offer: DirectFileOffer
    let transfers: DirectFileTransfers
    @ObservedObject private var row: DirectFileRowState
    @State private var localURL: URL?
    @State private var visible = false
    @Environment(\.scenePhase) private var scene
    init(offer: DirectFileOffer, transfers: DirectFileTransfers) {
        self.offer = offer; self.transfers = transfers; row = transfers.rowState(offer.transferId)
    }
    var body: some View {
        UnyriAttachmentImage(request: AttachmentImageRequest(id: "direct:" + offer.transferId,
            url: localURL, mime: offer.mimeType, thumbnail: offer.safeMediaPreview?.thumbnail,
            pixelEdge: 256, repositoryAttachment: false)) { image in
            ZStack {
                Color.secondary.opacity(0.16)
                if let image { Image(uiImage: image).resizable().scaledToFill() }
                else { Image(systemName: offer.mimeType.hasPrefix("video/") ? "video" : offer.mimeType.hasPrefix("image/") ? "photo" : "doc").foregroundStyle(.secondary) }
            }.clipped()
        }
        .modifier(UnyriMediaVisibility { visible = $0 })
        .task(id: "\(row.value?.phase.rawValue ?? "")|\(visible)|\(scene)") {
            guard visible, scene == .active, row.value?.phase == .completed else { localURL = nil; return }
            let url = await transfers.fileURL(offer.transferId)
            if !Task.isCancelled { localURL = url }
        }
    }
}
