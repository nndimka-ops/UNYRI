import SwiftUI
import QuickLook

struct DirectFileManagerView: View {
    let transfers: DirectFileTransfers
    let close: () -> Void
    let openChat: (String) -> Void
    @ObservedObject private var list: DirectFileListState
    @EnvironmentObject private var chats: ChatsRepository
    @State private var filter = Filter.all
    @State private var cachedChats: [String: ChatSummary] = [:]
    enum Filter: String, CaseIterable { case all = "All", sending = "Sending", receiving = "Receiving" }
    init(transfers: DirectFileTransfers, close: @escaping () -> Void, openChat: @escaping (String) -> Void) {
        self.transfers = transfers; self.close = close; self.openChat = openChat
        list = transfers.listState
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Transfers on this device").font(.footnote).foregroundStyle(.secondary)
                    Picker("Transfer direction", selection: $filter) {
                        ForEach(Filter.allCases, id: \.self) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                    }.pickerStyle(.segmented)
                }
                if !list.interruptedPreparations.isEmpty, filter != .receiving {
                    Section("Needs attention") {
                        ForEach(list.interruptedPreparations, id: \.id) { ticket in
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    if let name = ticket.displayFilename { Text(name).font(.subheadline).lineLimit(2) }
                                    Text("Preparation was interrupted. Choose the original file again.")
                                        .font(.caption).foregroundStyle(.secondary)
                                    Button {
                                        if let chat = ticket.chatId, cachedChats[chat] != nil { openChat(chat) }
                                    } label: {
                                        Label("Open conversation", systemImage: "bubble.left")
                                            .font(.caption).frame(minHeight: 44)
                                    }.buttonStyle(.plain)
                                        .disabled(ticket.chatId.flatMap { cachedChats[$0] } == nil)
                                }
                                Spacer(minLength: 8)
                                Button { transfers.discardInterruptedPreparation(ticket.id) } label: {
                                    Image(systemName: "xmark").frame(width: 44, height: 44)
                                }.buttonStyle(.plain).accessibilityLabel("Cancel transfer")
                            }
                        }
                    }
                }
                ForEach(list.ids, id: \.self) { id in
                    DirectFileManagerRow(row: transfers.rowState(id), transfers: transfers,
                        filter: filter, cachedChat: cachedChats[transfers.rowState(id).value?.chatId ?? ""],
                        openChat: openChat)
                }
                if list.ids.isEmpty, list.interruptedPreparations.isEmpty {
                    Section { Text("No transfers on this device yet").foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Transfers").navigationBarTitleDisplayMode(.inline)
            .toolbar { UnyriBackToolbarItem(action: close, identifier: "transfers-back") }
            .unyriScreenBackground()
            .task { await refreshCachedChats() }
            .onChange(of: chats.cacheRevision) { _ in Task { await refreshCachedChats() } }
        }
    }
    @MainActor private func refreshCachedChats() async {
        let values = await chats.loadCachedChatsAsync()
        guard !Task.isCancelled else { return }
        cachedChats = Dictionary(values.map { ($0.chatId, $0) }, uniquingKeysWith: { _, last in last })
    }
}

private struct DirectFileManagerRow: View {
    @ObservedObject var row: DirectFileRowState
    let transfers: DirectFileTransfers
    let filter: DirectFileManagerView.Filter
    let cachedChat: ChatSummary?
    let openChat: (String) -> Void
    @EnvironmentObject private var users: UsersRepository
    @State private var preview: URL?
    @State private var export: DirectFileExport?
    @State private var fileUnavailable = false
    @State private var retryShare = false
    @State private var confirmRestart = false
    private var locale: Locale { Locale(identifier: UnyriL10n.language) }
    private var units: [String] { ["B", "KB", "MB", "GB"].map { UnyriL10n.string($0) } }
    private var rateUnits: [String] { ["B/s", "KB/s", "MB/s", "GB/s"].map { UnyriL10n.string($0) } }
    var body: some View {
        if let value = row.value, filter == .all || value.outgoing == (filter == .sending) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    UserAvatar(url: cachedAvatar, name: cachedChat?.otherUser?.displayName ?? value.filename, size: 36)
                    Button { openChat(value.chatId) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cachedChat?.otherUser?.displayName ?? UnyriL10n.string("Conversation"))
                                .font(.subheadline.weight(.semibold)).lineLimit(1)
                            Label(LocalizedStringKey(value.outgoing ? "Sending" : "Receiving"),
                                  systemImage: value.outgoing ? "arrow.up" : "arrow.down")
                                .font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading).frame(minHeight: 44)
                    }.buttonStyle(.plain).disabled(cachedChat == nil)
                    if value.actions.contains(.cancel) {
                        button("xmark", label: "Cancel transfer") { transfers.perform(.cancel, id: value.id) }
                    }
                }
                Text(value.filename).font(.subheadline).lineLimit(2)
                Text(DirectFileDisplay.bytes(UInt64(value.size), locale: locale, units: units))
                    .font(.caption).foregroundStyle(.secondary)
                DirectFileHoldActions(value: value, transfers: transfers)
                if value.phase == .transferring || value.phase == .verifying {
                    let offset = max(0, min(value.size, row.progress.offset))
                    ProgressView(value: Double(offset), total: Double(value.size))
                    ViewThatFits(in: .horizontal) {
                        HStack { progressText(value, offset: offset); Spacer(); rateText(value) }
                        VStack(alignment: .leading) { progressText(value, offset: offset); rateText(value) }
                    }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                } else if !value.actions.contains(.pause) && !value.actions.contains(.resume) || value.status == value.phase.label {
                    Text(UnyriL10n.string(value.status)).font(.caption).foregroundStyle(.secondary)
                }
                if let position = value.queuePosition, position > 1 {
                    Text(String(format: UnyriL10n.string("Queue position: %d"), position))
                        .font(.caption).foregroundStyle(.secondary)
                }
                HStack(spacing: 12) {
                    if value.actions.contains(.accept) {
                        Button("Accept") { transfers.perform(.accept, id: value.id) }.unyriProminentAction()
                        Button("Decline") { transfers.perform(.reject, id: value.id) }.buttonStyle(.bordered)
                    }
                    if value.actions.contains(.open) {
                        Button("Open") { requestFile(value, share: false) }.buttonStyle(.bordered)
                        button("square.and.arrow.up", label: "Share") { requestFile(value, share: true) }
                    }
                    if value.actions.contains(.retry) {
                        button("arrow.clockwise", label: "Retry") { transfers.perform(.retry, id: value.id) }
                    }
                    if value.actions.contains(.restart) {
                        button("arrow.clockwise", label: "Start again") { confirmRestart = true }
                    }
                }
                if fileUnavailable {
                    HStack {
                        Text("Could not open this file. Check that it is available on this device, then try again.")
                            .font(.caption).foregroundStyle(.secondary)
                        button("arrow.clockwise", label: "Retry") { requestFile(value, share: retryShare) }
                    }
                }
            }
            .padding(.vertical, 6)
            .unyriQuickLookPreview($preview, title: value.filename)
            .sheet(item: $export) { DirectFileShareSheet(file: $0) }
            .confirmationDialog("Start the transfer again?", isPresented: $confirmRestart, titleVisibility: .visible) {
                Button("Start again", role: .destructive) { transfers.perform(.restart, id: value.id) }
            } message: { Text("The saved partial file does not match. Only this unfinished copy will be replaced.") }
        }
    }
    private var cachedAvatar: URL? {
        guard let peer = cachedChat?.otherUser,
              let url = users.cachedAvatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion ?? 0),
              url.isFileURL else { return nil }
        return url
    }
    private func progressText(_ value: DirectFilePresentation, offset: Int64) -> some View {
        Text("\(DirectFileDisplay.percent(offset: offset, total: value.size))% · \(DirectFileDisplay.bytes(UInt64(offset), locale: locale, units: units))")
    }
    @ViewBuilder private func rateText(_ value: DirectFilePresentation) -> some View {
        if row.progress.speedBytesPerSecond > 0 {
            Text(DirectFileDisplay.bytes(row.progress.speedBytesPerSecond, locale: locale, units: rateUnits))
            if let eta = DirectFileDisplay.eta(row.progress.remainingSeconds(total: value.size), overOneDay: UnyriL10n.string(">24h")) { Text(eta) }
        } else if row.progress.offset == value.size { Text("Verifying file") }
    }
    private func button(_ image: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: image).frame(width: 44, height: 44) }
            .buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(label)))
    }
    private func requestFile(_ value: DirectFilePresentation, share: Bool) {
        fileUnavailable = false; retryShare = share
        Task { @MainActor in
            let url = share ? await transfers.shareURL(value.id) : await transfers.fileURL(value.id)
            guard row.value?.id == value.id else { return }
            guard let url else { fileUnavailable = true; return }
            if share { export = DirectFileExport(url: url, filename: value.filename) } else { preview = url }
        }
    }
}
