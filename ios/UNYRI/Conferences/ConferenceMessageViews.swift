import Foundation
import SwiftUI
import UIKit

/// A normal encrypted message, with deliberately harmless generic text. None
/// of the render/copy/preview paths below performs admission or network lookup.
struct UnyriConferenceInvitationCard: View {
    let metadata: ConferenceInvitationMetadata
    let message: DecryptedChatMessage
    let outgoing: Bool
    let selecting: Bool
    let select: () -> Void
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriMessagePreview) private var inMenuPreview
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var joinError: String?
    @State private var attemptedJoin = false
    private var valid: Bool {
        guard let server = URL(string: metadata.server) else { return false }
        return (try? metadata.validated(currentServer: server)) != nil
    }
    private var serverMatches: Bool { (try? ConferenceInvite.origin(AppConfig.apiBaseURL).absoluteString) == metadata.server }
    private var expired: Bool { metadata.expiresAt <= Int64(Date().timeIntervalSince1970) }
    private var active: Bool {
        guard controller.isBusy, serverMatches, let link = controller.activeInvite,
              let current = try? ConferenceInvite.parse(link.absoluteString, currentServer: controller.api.server),
              let offered = try? metadata.validated(currentServer: controller.api.server) else { return false }
        return current == offered
    }
    private var attention: String? {
        if expired { return "This invitation may have expired. Joining checks the server." }
        if !serverMatches { return "Choose the invitation's server before joining." }
        return nil
    }
    var body: some View {
        if !valid {
            Text(LocalizedStringKey("Conference invitation"))
        } else if outgoing {
            invitationHeader(subtitle: outgoingStatus)
                .frame(maxWidth: 184, alignment: .leading)
                .accessibilityIdentifier("conference-outgoing-invitation")
        } else {
            VStack(alignment: .leading, spacing: 6) {
                if dynamicTypeSize.isAccessibilitySize {
                    invitationHeader(subtitle: active ? "You are in this conference" : "Room invitation")
                    HStack { Spacer(minLength: 0); joinButton }
                } else {
                    HStack(alignment: .center, spacing: 10) {
                        invitationHeader(subtitle: active ? "You are in this conference" : "Room invitation")
                            .layoutPriority(1)
                        joinButton.fixedSize(horizontal: true, vertical: false)
                    }
                }
                if let notice = joinError ?? attention {
                    Text(LocalizedStringKey(notice)).font(.caption)
                        .foregroundStyle(joinError != nil ? UnyriColors.danger : expired ? UnyriColors.warning : UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }.frame(maxWidth: 224, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                .onChange(of: controller.error) { value in if attemptedJoin { joinError = value } }
        }
    }
    private func invitationHeader(subtitle: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: UnyriSymbols.rooms).font(.system(size: 18, weight: .regular))
                .foregroundStyle(UnyriColors.primaryText).frame(width: 32, height: 32)
                .background(UnyriColors.primaryText.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(metadata.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(LocalizedStringKey(subtitle)).font(.caption).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
            }.fixedSize(horizontal: false, vertical: true)
        }
    }
    private var outgoingStatus: String {
        switch message.status {
        case .pending: return "Waiting to send"
        case .failed: return "Could not send"
        default: return expired ? "Invitation expired" : "Room invitation"
        }
    }
    private var joinButton: some View {
        Button {
            if selecting { select(); return }
            guard !inMenuPreview else { return }
            UnyriHaptics.selection()
            guard !controller.isBusy || active else { joinError = ConferenceInvite.Failure.busy.localizedDescription; return }
            attemptedJoin = true; joinError = nil; controller.accept(metadata)
            if let error = controller.error { joinError = error }
        } label: {
            HStack(spacing: 4) {
                Text("Join")
                if active { Image(systemName: "arrow.up.right").accessibilityHidden(true) }
            }.font(.subheadline.weight(.semibold)).frame(minWidth: 54, minHeight: 44)
        }.buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 12))
            .tint(colorStyle.role(.categoryBlue))
            .foregroundStyle(UnyriColors.onAccent)
            .disabled(!serverMatches || !controller.api.isCurrentSession)
            .accessibilityIdentifier("conference-invite-join")
    }
}

/// Recipient selection sends through the same durable per-account message
/// outbox. The capability is never a server-side invitation/notification body.
struct ConferenceInvitationShareSheet: View {
    let metadata: ConferenceInvitationMetadata
    @EnvironmentObject private var chats: ChatsRepository
    @EnvironmentObject private var messages: MessagesRepository
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var users: UsersRepository
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var destinations: [ChatSummary] = []
    @State private var search = ""
    @State private var sent = Set<String>()
    @State private var selected = Set<String>()
    @State private var logicalIDs: [String: String] = [:]
    @State private var copied = false
    @State private var loadID: UUID?
    @State private var loadedScope: String?
    @State private var failed = false
    @State private var sending = false
    @State private var sendFailed = false
    private var loading: Bool { loadID != nil }
    private var expired: Bool { metadata.expiresAt <= Int64(Date().timeIntervalSince1970) }
    private var normalizedSearch: String {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.hasPrefix("@") ? String(query.dropFirst()) : query
    }
    private var visible: [ChatSummary] {
        guard loadedScope != nil, loadedScope == users.blockedAccountScope else { return [] }
        let query = normalizedSearch
        return destinations.filter {
            query.isEmpty || ($0.otherUser?.displayName ?? "").localizedCaseInsensitiveContains(query) ||
                ($0.otherUser?.username ?? "").localizedCaseInsensitiveContains(query)
        }
    }
    private var emptyMessage: String? {
        guard !loading, !failed, visible.isEmpty else { return nil }
        return normalizedSearch.isEmpty ? "No chats available for invitation" : "No results."
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        Image(systemName: "lock.shield.fill").foregroundStyle(UnyriColors.secondaryText)
                        Text(metadata.title).font(.headline).lineLimit(2)
                    }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                        .background(colorStyle.material(hex: 0x171F2D), in: RoundedRectangle(cornerRadius: 18))
                    Text("Choose chats. Recipients decide whether to join; camera and microphone stay off on acceptance.")
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    TextField("Search", text: $search).textFieldStyle(.roundedBorder).autocorrectionDisabled()
                    if loading { ProgressView().frame(maxWidth: .infinity).padding() }
                    if failed {
                        Text("Could not load chats. Try again.").font(.footnote).foregroundStyle(UnyriColors.danger)
                        Button("Retry") { Task { await load() } }.frame(minHeight: 44)
                    }
                    if let emptyMessage { Text(LocalizedStringKey(emptyMessage)).font(.footnote).foregroundStyle(UnyriColors.secondaryText) }
                    LazyVStack(spacing: 5) {
                        ForEach(visible) { chat in
                            ConferenceInvitationRecipientRow(chat: chat, sent: sent.contains(chat.chatId),
                                selected: selected.contains(chat.chatId), sending: sending) {
                                guard loadedScope == users.blockedAccountScope, !sent.contains(chat.chatId), !sending else { return }
                                UnyriHaptics.selection()
                                if selected.contains(chat.chatId) { selected.remove(chat.chatId) }
                                else { selected.insert(chat.chatId) }
                            }
                        }
                    }
                    Divider().padding(.vertical, 5)
                    HStack(spacing: 12) {
                        Button { copy(metadata.invite) } label: { Label(copied ? "Copied" : "Copy invitation", systemImage: copied ? "checkmark" : "link") }
                            .frame(minHeight: 44)
                        ShareLink(item: metadata.invite, preview: SharePreview(metadata.title)) { Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44) }
                            .accessibilityLabel("Share invitation")
                    }.font(.subheadline)
                    Text("Anyone with this invitation can join and decrypt this room. Share only with people you trust.")
                        .font(.caption).foregroundStyle(UnyriColors.secondaryText)
                }.padding(16)
            }.background(UnyriColors.background)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    VStack(spacing: 8) {
                        if sendFailed {
                            Text("Some invitations could not be queued. Retry the selected chats.")
                                .font(.footnote).foregroundStyle(UnyriColors.danger)
                        }
                        sendButton
                    }.padding(12).background(UnyriColors.background)
                }
                .navigationTitle("Invite participants").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                .task(id: users.blockedAccountScope) { await load() }
                .refreshable { await load() }
                .onChange(of: auth.sessionGeneration) { _ in clearDestinations(); dismiss() }
        }
    }
    private var canSend: Bool {
        !selected.isEmpty && !sending && loadedScope != nil && loadedScope == users.blockedAccountScope
    }
    private var sendButton: some View {
        Button { Task { await sendSelected() } } label: {
            HStack(spacing: 8) {
                if sending { ProgressView() } else { Image(systemName: "paperplane.fill") }
                Text(UnyriL10n.string("Send") + " (\(selected.count))")
            }.frame(maxWidth: .infinity, minHeight: 48)
        }.buttonStyle(.borderedProminent).tint(colorStyle.role(.categoryBlue))
            .foregroundStyle(canSend ? UnyriColors.onAccent : UnyriColors.secondaryText)
            .disabled(!canSend)
            .accessibilityIdentifier("conference-send-selected")
    }
    private func copy(_ value: String) {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
        UnyriHaptics.selection(); copied = true
    }
    @MainActor private func clearDestinations() {
        destinations = []; selected = []; sent = []; logicalIDs = [:]
        loadID = nil; loadedScope = nil; failed = false; sendFailed = false
    }
    @MainActor private func load() async {
        guard let scope = users.blockedAccountScope else { clearDestinations(); return }
        if loadedScope != scope { clearDestinations(); loadedScope = scope }
        guard loadID == nil else { return }
        let ticket = UUID(); loadID = ticket; failed = false
        defer { if loadID == ticket { loadID = nil } }
        do {
            var result: [ChatSummary] = []; var offset: Int64 = 0
            repeat {
                let page = try await chats.listChats(limit: 100, offset: offset)
                guard scope == users.blockedAccountScope, loadID == ticket, !Task.isCancelled else { return }
                result.append(contentsOf: page.items.filter { $0.chatType == "direct" && $0.isBlocked != true && $0.otherUser?.id != auth.currentUser?.id })
                guard let next = page.nextOffset, next > offset else { break }; offset = next
            } while result.count < 1000
            destinations = result
            selected.formIntersection(Set(result.map(\.chatId)))
        } catch {
            if scope == users.blockedAccountScope, loadID == ticket, !Task.isCancelled { failed = true }
        }
    }
    @MainActor private func sendSelected() async {
        guard !sending, !selected.isEmpty, let scope = users.blockedAccountScope, loadedScope == scope else { return }
        sending = true; sendFailed = false
        defer { sending = false }
        let generation = auth.sessionGeneration
        let targets = destinations.filter { selected.contains($0.chatId) && !sent.contains($0.chatId) }
        guard !targets.isEmpty else { return }
        for (index, chat) in targets.enumerated() {
            guard generation == auth.sessionGeneration, scope == users.blockedAccountScope, !Task.isCancelled else { return }
            let id = logicalIDs[chat.chatId] ?? UUID().uuidString.lowercased()
            logicalIDs[chat.chatId] = id
            let invitation = ConferenceInvitationMetadata(id: id, title: metadata.title, server: metadata.server,
                roomID: metadata.roomID, invite: metadata.invite, expiresAt: metadata.expiresAt)
            if messages.sendConferenceInvite(chat: chat, metadata: invitation) {
                sent.insert(chat.chatId); selected.remove(chat.chatId)
            } else { sendFailed = true }
            // Enqueue is local/durable, not a network wait. Yield bounded
            // batches so inviting a full room cannot monopolize the main actor.
            if index % 5 == 4 { await Task.yield() }
        }
        if !sendFailed { UnyriHaptics.selection(); dismiss() }
    }
}

/// Metadata-only task identity: a changed account, server, or photo version
/// invalidates the old row result. The repository owns all signing and caching.
private struct ConferenceInvitationAvatarRequest: Hashable {
    let scope: String?
    let userID: String
    let avatarURL: String?
    let avatarVersion: Int64
    let retry: UInt64
    var hasAvatar: Bool { !(avatarURL ?? "").isEmpty }
}

private struct ConferenceInvitationRecipientRow: View {
    let chat: ChatSummary
    let sent: Bool
    let selected: Bool
    let sending: Bool
    let choose: () -> Void
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var avatarURL: URL?
    @State private var resolvedRequest: ConferenceInvitationAvatarRequest?
    @State private var loadingRequest: ConferenceInvitationAvatarRequest?
    @State private var failedRequest: ConferenceInvitationAvatarRequest?
    @State private var retry: UInt64 = 0

    private var title: String { chat.otherUser?.displayName ?? chat.otherUser?.username ?? "Chat" }
    private var avatarRequest: ConferenceInvitationAvatarRequest {
        // Observing auth also invalidates the row immediately when its epoch ends.
        _ = auth.sessionGeneration
        return ConferenceInvitationAvatarRequest(scope: users.blockedAccountScope,
            userID: chat.otherUser?.id ?? "", avatarURL: chat.otherUser?.avatarUrl,
            avatarVersion: chat.otherUser?.avatarVersion ?? 0, retry: retry)
    }
    private var displayedAvatar: URL? {
        guard avatarRequest.scope != nil else { return nil }
        if resolvedRequest == avatarRequest { return avatarURL }
        return users.cachedAvatarURL(id: avatarRequest.userID, avatarUrl: avatarRequest.avatarURL,
            avatarVersion: avatarRequest.avatarVersion)
    }
    var body: some View {
        HStack(spacing: 0) {
            Button(action: choose) {
                HStack(spacing: 12) {
                    UserAvatar(url: displayedAvatar, name: title, size: 30)
                        .overlay {
                            if loadingRequest == avatarRequest && displayedAvatar == nil {
                                ProgressView().controlSize(.mini).accessibilityHidden(true)
                            }
                        }
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title).font(.subheadline).foregroundStyle(UnyriColors.primaryText)
                        if sent { Text("Queued for sending").font(.caption).foregroundStyle(UnyriColors.secondaryText) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: sent || selected ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(colorStyle.role(.categoryBlue))
                }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(sent || sending)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("conference-invite-recipient-" + chat.chatId)
            // Separate sibling control: retrying a failed photo never selects a chat.
            // No-photo accounts have normal initials and never offer a retry.
            if failedRequest == avatarRequest {
                Button { retry &+= 1 } label: {
                    Image(systemName: "arrow.clockwise").frame(width: 44, height: 44)
                }.buttonStyle(.plain).foregroundStyle(UnyriColors.secondaryText)
                    .accessibilityLabel("Retry").accessibilityHint("Photo")
                    .accessibilityIdentifier("conference-invite-avatar-retry-" + chat.chatId)
            }
        }.padding(.horizontal, 12).padding(.vertical, 6).frame(minHeight: 56)
            .background(colorStyle.material(hex: 0x171F2D), in: RoundedRectangle(cornerRadius: 14))
            .task(id: avatarRequest) { await loadAvatar(for: avatarRequest) }
    }
    @MainActor private func loadAvatar(for request: ConferenceInvitationAvatarRequest) async {
        guard request == avatarRequest, request.scope != nil else { return }
        avatarURL = users.cachedAvatarURL(id: request.userID, avatarUrl: request.avatarURL, avatarVersion: request.avatarVersion)
        resolvedRequest = request; failedRequest = nil
        guard request.hasAvatar else { loadingRequest = nil; return }
        loadingRequest = request
        defer { if loadingRequest == request { loadingRequest = nil } }
        let result = await users.avatarURL(id: request.userID, avatarUrl: request.avatarURL, avatarVersion: request.avatarVersion)
        guard !Task.isCancelled, request == avatarRequest, request.scope == users.blockedAccountScope else { return }
        if let result { avatarURL = result }
        else if avatarURL == nil { failedRequest = request }
    }
}
