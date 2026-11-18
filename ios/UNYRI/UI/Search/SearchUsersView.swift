import SwiftUI
import Combine
import UIKit

private struct InlineSearchPerson: Identifiable {
    let id: String
    let title: String
    let user: PublicUser?
    let chat: ChatSummary?
}

private struct InlineSearchHit: Identifiable {
    let message: DecryptedChatMessage
    let chat: ChatSummary
    let categories: Set<SearchCategory>
    var id: String { message.clientMessageId }
}

private enum InlineSearchFocusRequest: Equatable {
    case focus(UUID)
    case dismiss(UUID)
}

/// Root content of the existing chat list; only opening a conversation pushes.
/// Plain repository references keep ping/presence/message publications out of rows.
@MainActor
struct InlineChatSearchView: View, Equatable {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.chats === rhs.chats && lhs.messages === rhs.messages && lhs.users === rhs.users &&
            lhs.connected == rhs.connected && lhs.active == rhs.active
    }
    let chats: ChatsRepository
    let messages: MessagesRepository
    let users: UsersRepository
    let connected: Bool
    let active: Bool
    let onClose: () -> Void
    let onOpenChat: (ChatSummary, String?) -> Void
    @State private var focusRequest = InlineSearchFocusRequest.focus(UUID())
    @State private var query = ""
    @State private var committedQuery = ""
    @State private var category = SearchCategory.chats
    @State private var localPeople: [InlineSearchPerson] = []
    @State private var hits: [InlineSearchHit] = []
    @State private var tabs: [SearchCategory] = []
    @State private var recentPeople: [PublicUser] = []
    @State private var recentQueries: [String] = []
    @State private var directory: [PublicUser] = []
    @State private var globalPeople: [PublicUser] = []
    @State private var directoryQuery = ""
    @State private var nextOffset: Int64?
    @State private var directoryLoading = false
    @State private var directoryError: String?
    @State private var localLoading = false
    @State private var localError = false
    @State private var openingError = false
    @State private var opening: Task<Void, Never>?
    @State private var retry = 0
    @State private var pageRequest = 0
    @State private var handledPage = 0
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 80
    private var term: String { SearchPresentation.query(query) }
    private var sections: [SearchSection] { SearchPresentation.sections(localCount: localPeople.count, messageCount: hits.count, query: term) }
    private var requestID: String { "\(term)|\(connected)|\(retry)|\(active)" }

    var body: some View {
        VStack(spacing: 0) {
            header
            if !term.isEmpty && !tabs.isEmpty { filters }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if term.isEmpty { recents }
                    else { results }
                }.padding(.horizontal, 16).padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.immediately)
            // The UIKit field is not SwiftUI-focused, so scrolling alone did not resign it (Mac walkthrough, 14 Nov).
            .simultaneousGesture(DragGesture(minimumDistance: 12).onChanged { value in
                if abs(value.translation.height) > abs(value.translation.width) { focusRequest = .dismiss(UUID()) }
            })
            .accessibilityIdentifier("search-results-" + (category == .chats ? "all" : category.rawValue))
        }
        .unyriScreenBackground()
        .performanceScreen(.search)
        .task {
            recentPeople = chats.recentSearchPeople; recentQueries = chats.recentSearchQueries

        }
        .task(id: requestID) { await search() }
        .task(id: requestID + "|page:\(pageRequest)") { await moreDirectory() }
        .onChange(of: active) { if !$0 { focusRequest = .dismiss(UUID()) } }
        .onDisappear { opening?.cancel(); opening = nil }
        .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { value in
            if value.startLocation.x < 24 && value.translation.width > 90 && abs(value.translation.height) < 60 { close() }
        })
    }

    private var header: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(UnyriColors.secondaryText).accessibilityHidden(true)
                InlineSearchTextInput(text: $query, focusRequest: active ? focusRequest : nil,
                    ink: UIColor(UnyriColors.primaryText), placeholderInk: UIColor(UnyriColors.secondaryText),
                    tint: UIColor(colorStyle.decorative(UnyriColors.accent)),
                    onSubmit: { recordQuery(); focusRequest = .dismiss(UUID()) })
                    .fixedSize(horizontal: false, vertical: true) // intrinsic height; unbounded it took ~380 pt
                    .frame(maxWidth: .infinity, minHeight: 44)
                if !query.isEmpty {
                    Button { query = ""; focusRequest = .focus(UUID()) } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(UnyriColors.secondaryText).frame(width: 44, height: 44)
                    }.accessibilityLabel("Clear search").accessibilityIdentifier("search-clear-query")
                }
            }
            .padding(.leading, 14).padding(.trailing, query.isEmpty ? 14 : 0).frame(minHeight: 44)
            .background { searchGlass }
            .overlay(Capsule().stroke(UnyriColors.outlineSecondary, lineWidth: 0.5))
            Button("Cancel", action: close).frame(minHeight: 44).accessibilityIdentifier("search-close")
        }
        .buttonStyle(.plain).foregroundStyle(UnyriColors.primaryText)
        .padding(.horizontal, 12).padding(.vertical, 4)
        .accessibilityElement(children: .contain) // keep the field's own "search-query"
        .accessibilityIdentifier("search-inline-header")
    }

    @ViewBuilder private var searchGlass: some View {
        if #available(iOS 26.0, *) { Capsule().fill(.clear).glassEffect(.regular, in: Capsule()) }
        else { Capsule().fill(.ultraThinMaterial) }
    }

    private var filters: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(tabs, id: \.self) { tab in
                    Button { category = tab; focusRequest = .dismiss(UUID()) } label: {
                        Text(UnyriL10n.string(tab.title)).font(.subheadline.weight(.semibold))
                            .foregroundStyle(category == tab ? colorStyle.decorative(UnyriColors.accent) : UnyriColors.secondaryText)
                            .padding(.vertical, 12)
                    }.buttonStyle(.plain)
                        .accessibilityIdentifier("search-scope-" + tab.rawValue)
                        .accessibilityAddTraits(category == tab ? [.isSelected] : [])
                }
            }.padding(.horizontal, 16)
        }.accessibilityIdentifier("search-scope-controls")
    }

    @ViewBuilder private var recents: some View {
        if !recentPeople.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(recentPeople) { user in
                        Button { openPerson(user) } label: {
                            VStack(spacing: 5) {
                                UserAvatar(url: users.cachedAvatarURL(id: user.id, avatarUrl: user.avatarUrl, avatarVersion: user.avatarVersion), name: user.visibleName, size: 52)
                                Text(user.visibleName).font(.caption).lineLimit(1).frame(width: 72)
                            }.foregroundStyle(UnyriColors.primaryText)
                        }.buttonStyle(.plain)
                    }
                }.padding(.vertical, 12)
            }.accessibilityIdentifier("search-recent-people")
        }
        HStack {
            section("Recent")
            Spacer()
            if !recentPeople.isEmpty || !recentQueries.isEmpty {
                Button("Clear") {
                    chats.recentSearchQueries = []; chats.recentSearchPeople = []
                    recentQueries = []; recentPeople = []
                }.font(.caption).frame(minHeight: 44).accessibilityIdentifier("search-clear-history")
            }
        }
        ForEach(recentQueries, id: \.self) { value in
            Button { query = value; focusRequest = .focus(UUID()) } label: {
                Label(value, systemImage: "clock").lineLimit(1).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            }.buttonStyle(.plain).foregroundStyle(UnyriColors.primaryText)
        }
        ForEach(recentPeople) { user in
            Button { openPerson(user) } label: { InlineSearchPersonRow(user: user, title: user.visibleName, users: users, height: rowHeight) }
                .buttonStyle(.plain)
        }
        if recentQueries.isEmpty && recentPeople.isEmpty { notice("Search saved chats and messages offline.") }
        if openingError { notice("Could not open the conversation. Your search results are still here. Try tapping the person again.") }
    }

    @ViewBuilder private var results: some View {
        if !connected { notice("No connection") }
        if localLoading { ProgressView().frame(maxWidth: .infinity, minHeight: 36).accessibilityIdentifier("search-loading") }
        if localError {
            notice("Could not load saved messages. Try searching again.", warning: true)
            Button("Retry") { retry += 1 }.frame(minHeight: 44)
        }
        if openingError { notice("Could not open the conversation. Your search results are still here. Try tapping the person again.") }
        if category == .chats {
            if sections.contains(.people) {
                section("Chats and contacts").accessibilityIdentifier("search-section-chats")
                ForEach(localPeople) { person in
                    Button {
                        if let chat = person.chat { navigate(chat) }
                        else if let user = person.user { openPerson(user) }
                    } label: { InlineSearchPersonRow(user: person.user, title: person.title, users: users, height: rowHeight) }
                        .buttonStyle(.plain)
                }
            }
            if sections.contains(.global) {
                section("Global Search").accessibilityIdentifier("search-section-global")
                notice("Search by name or @username. This query is sent to your server.")
                if directoryLoading { ProgressView().frame(maxWidth: .infinity, minHeight: 36) }
                ForEach(globalPeople) { user in
                    Button { openPerson(user) } label: { InlineSearchPersonRow(user: user, title: user.visibleName, users: users, height: rowHeight) }
                        .buttonStyle(.plain)
                }
                if let directoryError {
                    notice(directoryError, warning: true)
                    Button("Retry") { retry += 1 }.frame(minHeight: 44).accessibilityIdentifier("search-directory-retry")
                }
                if nextOffset != nil {
                    Button("Load more") { pageRequest += 1 }.frame(minHeight: 44).disabled(directoryLoading)
                        .accessibilityIdentifier("search-directory-more")
                }
            }
            if sections.contains(.messages) {
                section("Messages").accessibilityIdentifier("search-section-messages")
                ForEach(hits) { hit in messageRow(hit) }
            }
            if !localLoading && !directoryLoading && !localError && localPeople.isEmpty && hits.isEmpty && globalPeople.isEmpty { notice("No results.") }
        } else if category == .media {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 3)], spacing: 3) {
                ForEach(hits.filter { $0.categories.contains(.media) }) { hit in
                    Button { navigate(hit.chat, message: hit.id) } label: {
                        InlineSearchMediaTile(message: hit.message, repository: messages)
                            .frame(height: 112).clipped()
                    }.buttonStyle(.plain).accessibilityLabel(chatTitle(hit.chat))
                        .accessibilityIdentifier("search-media-" + hit.id)
                }
            }.accessibilityIdentifier("search-media-grid")
        } else {
            ForEach(hits.filter { $0.categories.contains(category) }) { hit in messageRow(hit) }
        }
    }

    private func messageRow(_ hit: InlineSearchHit) -> some View {
        Button { navigate(hit.chat, message: hit.id) } label: {
            HStack(spacing: 12) {
                Image(systemName: "text.bubble").foregroundStyle(colorStyle.decorative(UnyriColors.accent)).frame(width: 32)
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(chatTitle(hit.chat)).lineLimit(1)
                        Spacer(minLength: 8)
                        Text(RelativeTime.listLabel(for: hit.message.createdAt)).foregroundStyle(UnyriColors.mutedText)
                    }.font(.caption)
                    Text(preview(hit.message)).font(.body).lineLimit(2)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.foregroundStyle(UnyriColors.primaryText).frame(height: rowHeight, alignment: .center).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("search-message-" + hit.id)
    }

    private func section(_ key: String) -> some View {
        Text(UnyriL10n.string(key)).font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.secondaryText)
            .padding(.top, 14).padding(.bottom, 6)
    }
    private func notice(_ key: String, warning: Bool = false) -> some View {
        Text(UnyriL10n.string(key)).font(.footnote).foregroundStyle(warning ? UnyriColors.danger : UnyriColors.secondaryText)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10)
    }
    private func chatTitle(_ chat: ChatSummary) -> String {
        chat.otherUser?.visibleName ?? UnyriL10n.string(chat.chatType == "saved" ? "Saved Messages" : "Chats")
    }
    private func preview(_ message: DecryptedChatMessage) -> AttributedString {
        let source = message.content.text?.isEmpty == false ? message.displayText : (message.content.attachment?.filename ?? message.displayText)
        var text = AttributedString(SearchPresentation.snippet(source, query: committedQuery))
        if !committedQuery.isEmpty, let match = text.range(of: committedQuery, options: [.caseInsensitive, .diacriticInsensitive]) {
            text[match].inlinePresentationIntent = .stronglyEmphasized
            text[match].foregroundColor = colorStyle.decorative(UnyriColors.accent)
        }
        return text
    }
    private func close() { focusRequest = .dismiss(UUID()); opening?.cancel(); onClose() }
    private func recordQuery() {
        guard !term.isEmpty else { return }
        chats.recentSearchQueries = Array(([term] + chats.recentSearchQueries.filter { $0 != term }).prefix(8))
        recentQueries = chats.recentSearchQueries
    }
    private func navigate(_ chat: ChatSummary, message: String? = nil) {
        recordQuery(); focusRequest = .dismiss(UUID())
        if let peer = chat.otherUser { chats.rememberSearchPerson(peer); recentPeople = chats.recentSearchPeople }
        onOpenChat(chat, message)
    }
    private func openPerson(_ user: PublicUser) {
        guard opening == nil else { return }
        openingError = false; focusRequest = .dismiss(UUID())
        opening = Task {
            defer { opening = nil }
            let local = await chats.loadCachedChatsAsync()
            guard !Task.isCancelled else { return }
            if let chat = local.first(where: { $0.otherUser?.id == user.id }) { navigate(chat); return }
            do {
                let chat = try await chats.createOrOpenDirectChat(otherUserId: user.id)
                guard !Task.isCancelled else { return }
                navigate(chat)
            } catch { if !Task.isCancelled { openingError = true } }
        }
    }

    private func search() async {
        let key = requestID
        let value = term
        guard active else { return }
        if value.isEmpty {
            localPeople = []; hits = []; tabs = []; directory = []; globalPeople = []; committedQuery = ""; directoryQuery = ""
            localLoading = false; directoryLoading = false; localError = false; directoryError = nil; category = .chats
            return
        }
        do { try await SearchPresentation.debounce() } catch { return }
        guard !Task.isCancelled, key == requestID else { return }
        localLoading = true; localError = false
        async let remote: Void = searchDirectory(value, key: key)
        let local = await chats.loadCachedChatsAsync()
        if connected { await users.contacts.ensureLoaded() }
        let contacts = users.contacts.entries.map(\.user)
        guard !Task.isCancelled, key == requestID else { return }
        var people = local.map { InlineSearchPerson(id: $0.otherUser?.id ?? $0.chatId, title: chatTitle($0), user: $0.otherUser, chat: $0) }
        var known = Set(people.map(\.id))
        people += contacts.filter { known.insert($0.id).inserted }.map { InlineSearchPerson(id: $0.id, title: $0.visibleName, user: $0, chat: nil) }
        let candidates = people.map { SearchCandidate(id: $0.id, title: $0.title, username: $0.user?.username ?? "") }
        let ranked = await Task.detached(priority: .userInitiated) { SearchPresentation.ranked(candidates, query: value) }.value
        var found: [DecryptedChatMessage] = []
        do { found = try await messages.searchCachedMessages(query: value) }
        catch { if !Task.isCancelled, key == requestID { localError = true } }
        guard !Task.isCancelled, key == requestID else { return }
        let byID = Dictionary(local.map { ($0.chatId, $0) }, uniquingKeysWith: { _, last in last })
        let messageSnapshot = found
        let prepared = await Task.detached(priority: .userInitiated) {
            messageSnapshot.compactMap { message -> InlineSearchHit? in
                guard let chat = byID[message.chatId] else { return nil }
                let isDirectFile: Bool = message.content.directFile != nil || DirectFilePreviewText.isFallback(message.content.text)
                let mediaType: String? = isDirectFile ? "file" : message.content.attachment?.mediaType
                let mimeType: String? = isDirectFile ? nil : message.content.attachment?.mimeType
                let categories: Set<SearchCategory> = SearchPresentation.categories(
                    mediaType: mediaType, mimeType: mimeType, text: message.displayText)
                return InlineSearchHit(message: message, chat: chat, categories: categories)
            }
        }.value
        guard !Task.isCancelled, key == requestID else { return }
        let peopleByID = Dictionary(people.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        localPeople = ranked.compactMap { peopleByID[$0] }; hits = prepared; committedQuery = value
        refreshTabs(); localLoading = false
        await remote
    }

    private func refreshTabs() {
        let localIDs = Set(localPeople.compactMap { $0.user?.id })
        globalPeople = directory.filter { !localIDs.contains($0.id) }
        tabs = SearchPresentation.tabs(categories: hits.map(\.categories), hasPeople: !localPeople.isEmpty || !globalPeople.isEmpty)
        if !tabs.contains(category) { category = .chats }
    }
    private func searchDirectory(_ value: String, key: String) async {
        guard active, !Task.isCancelled, key == requestID else { return }
        directoryLoading = false; directoryError = nil; nextOffset = nil
        let cached = value.count >= 3 ? await users.cachedDirectory(query: value) : []
        guard !Task.isCancelled, key == requestID else { return }
        directoryQuery = value; directory = cached
        refreshTabs()
        guard SearchPresentation.canSearchDirectory(value, connected: connected) else { return }
        directoryLoading = true
        defer { if key == requestID { directoryLoading = false } }
        do {
            let page = try await users.directory(query: value, limit: 30, offset: 0)
            guard !Task.isCancelled, key == requestID else { return }
            directory = page.items; nextOffset = page.nextOffset.flatMap { $0 > 0 ? $0 : nil }; refreshTabs()
        } catch {
            guard !Task.isCancelled, key == requestID else { return }
            directoryFailure(error)
        }
    }
    private func directoryFailure(_ error: Error) {
        directoryError = "Could not load global search. Try again."
        if case APIError.http(404) = error { directoryError = "Server directory is unavailable. Update your server to use Contacts." }
        if case APIError.server(let code, _) = error, ["not_found", "route_not_found"].contains(code) {
            directoryError = "Server directory is unavailable. Update your server to use Contacts."
        }
        if case APIError.http(let status) = error, [401, 403].contains(status) { directory = []; nextOffset = nil }
        if case APIError.server(let code, _) = error,
           ["unauthorized", "forbidden", "session_revoked", "invalid_access_token", "account_restricted"].contains(code) {
            directory = []; nextOffset = nil
        }
        refreshTabs()
    }
    private func moreDirectory() async {
        let key = requestID
        guard active, pageRequest > handledPage, !directoryLoading, connected, directoryQuery == term, let offset = nextOffset else { return }
        handledPage = pageRequest
        directoryLoading = true; directoryError = nil
        defer { if key == requestID { directoryLoading = false } }
        do {
            let page = try await users.directory(query: directoryQuery, limit: 30, offset: offset)
            guard !Task.isCancelled, key == requestID else { return }
            var known = Set(directory.map(\.id))
            directory += page.items.filter { known.insert($0.id).inserted }
            nextOffset = page.nextOffset.flatMap { $0 > offset ? $0 : nil }; refreshTabs()
        } catch { if !Task.isCancelled, key == requestID { directoryError = "Could not load global search. Try again." } }
    }
}

/// The header is inserted while NavigationStack removes its toolbar. Request focus from
/// the native field's window lifecycle, rather than assuming a SwiftUI focus write worked.
/// A consumed request stays consumed after interactive scroll dismissal and result updates.
private struct InlineSearchTextInput: UIViewRepresentable {
    @Binding var text: String
    let focusRequest: InlineSearchFocusRequest?
    let ink: UIColor
    let placeholderInk: UIColor
    let tint: UIColor
    let onSubmit: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WindowTextField {
        let field = WindowTextField(frame: .zero)
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.edited(_:)), for: .editingChanged)
        field.accessibilityIdentifier = "search-query"
        field.borderStyle = .none
        field.backgroundColor = .clear
        field.clearButtonMode = .never // The existing accessible SwiftUI clear button owns this.
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.returnKeyType = .search
        field.adjustsFontForContentSizeCategory = true
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        updateUIView(field, context: context)
        return field
    }

    func updateUIView(_ field: WindowTextField, context: Context) {
        context.coordinator.parent = self
        // Keep the native selection and marked text intact during typing/composition.
        if field.text != text && field.markedTextRange == nil { field.text = text }
        field.font = .preferredFont(forTextStyle: .body)
        field.textColor = ink
        field.tintColor = tint
        let placeholder = UnyriL10n.string("Search")
        field.attributedPlaceholder = NSAttributedString(string: placeholder, attributes: [.foregroundColor: placeholderInk])
        field.accessibilityLabel = placeholder
        field.requestFocus(focusRequest)
    }

    static func dismantleUIView(_ field: WindowTextField, coordinator: Coordinator) {
        field.delegate = nil
        field.requestFocus(nil)
        field.resignFirstResponder()
    }

    @MainActor final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: InlineSearchTextInput
        init(_ parent: InlineSearchTextInput) { self.parent = parent }

        @objc func edited(_ field: UITextField) {
            var value = field.text ?? ""
            if field.markedTextRange == nil && value.count > 256 {
                value = String(value.prefix(256)); field.text = value
            }
            if parent.text != value { parent.text = value }
        }

        func textFieldShouldReturn(_ field: UITextField) -> Bool {
            parent.onSubmit()
            field.resignFirstResponder()
            return false
        }
    }

    final class WindowTextField: UITextField {
        private var requested: InlineSearchFocusRequest?
        private var consumed: UUID?
        private var focusScheduled = false

        override init(frame: CGRect) {
            super.init(frame: frame)
            NotificationCenter.default.addObserver(self, selector: #selector(windowBecameKey(_:)),
                name: UIWindow.didBecomeKeyNotification, object: nil)
        }
        required init?(coder: NSCoder) { nil }
        deinit { NotificationCenter.default.removeObserver(self) }

        func requestFocus(_ request: InlineSearchFocusRequest?) {
            guard request != requested else { return }
            requested = request
            scheduleFocus()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            scheduleFocus()
        }

        @objc private func windowBecameKey(_ note: Notification) {
            guard let keyWindow = note.object as? UIWindow, keyWindow === window else { return }
            scheduleFocus()
        }

        private func scheduleFocus() {
            guard !focusScheduled else { return }
            if let requested, case .focus(let token) = requested {
                guard token != consumed, window?.isKeyWindow == true else { return }
            } else if !isFirstResponder { return }
            // Leave the representable update/window attachment before UIKit calls delegates.
            // This is one run-loop handoff, with no timer, arbitrary delay or retry loop.
            focusScheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.focusScheduled = false
                guard let requested = self.requested, case .focus(let request) = requested else {
                    self.resignFirstResponder(); return
                }
                guard request != self.consumed, self.window?.isKeyWindow == true else { return }
                if self.isFirstResponder || self.becomeFirstResponder() { self.consumed = request }
            }
        }
    }
}

private struct InlineSearchPersonRow: View {
    let user: PublicUser?
    let title: String
    let users: UsersRepository
    let height: CGFloat
    var body: some View {
        HStack(spacing: 12) {
            UserAvatar(url: user.flatMap { users.cachedAvatarURL(id: $0.id, avatarUrl: $0.avatarUrl, avatarVersion: $0.avatarVersion) }, name: title, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.body.weight(.medium)).lineLimit(1)
                Text(user.map { $0.publicIdentityLabel } ?? UnyriL10n.string("Saved Messages"))
                    .font(.caption).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
            }
            Spacer(minLength: 0)
        }.foregroundStyle(UnyriColors.primaryText).frame(height: height).contentShape(Rectangle())
    }
}

private struct InlineSearchMediaTile: View {
    let message: DecryptedChatMessage
    let repository: MessagesRepository
    var body: some View {
        if let attachment = message.content.attachment {
            UnyriAttachmentImage(request: AttachmentImageRequest(id: attachment.attachmentId,
                url: repository.cachedLocalPath(attachmentId: attachment.attachmentId).map { URL(fileURLWithPath: $0) },
                mime: attachment.mimeType, thumbnail: attachment.thumbnail, pixelEdge: 384), repository: repository) { image in
                ZStack {
                    Color(.systemGray5)
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(systemName: attachment.mimeType.hasPrefix("video/") ? "video" : "photo").foregroundStyle(.secondary) }
                }
            }
        }
    }
}

/// "Add to contacts" / "Remove from contacts" on a peer profile. Observes only
/// the contacts store, so a contact change re-renders this row alone. Removal
/// is reversible, so it is neither red nor confirmed. It asks about this one
/// person and never loads the whole address book.
struct UnyriContactToggleRow: View {
    @ObservedObject var store: ContactsStore
    let user: PublicUser
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var failed = false

    // Explicit: the private @State would make the synthesized init file-private.
    init(store: ContactsStore, user: PublicUser) {
        _store = ObservedObject(wrappedValue: store)
        self.user = user
    }

    private var isContact: Bool { store.ids.contains(user.id) }
    // Unknown after a failed answer reads as "Add": the add is idempotent.
    private var unknown: Bool { !isContact && !store.nonMembers.contains(user.id) }
    private var busy: Bool { store.changing.contains(user.id) || (unknown && store.checking.contains(user.id)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: toggle) {
                HStack(spacing: 14) {
                    icon.font(.system(size: 18)).frame(width: 26)
                    Text(UnyriL10n.string(isContact ? "Remove from contacts" : "Add to contacts")).font(.body)
                    Spacer(minLength: 0)
                    if busy { ProgressView().controlSize(.small) }
                }
                .foregroundStyle(UnyriColors.primaryText)
                .padding(.horizontal, 16).frame(minHeight: 50).contentShape(Rectangle())
                .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isContact)
            }
            .buttonStyle(.plain)
            .disabled(busy)
            .accessibilityIdentifier("peer-profile-contact")
            if failed {
                Text(UnyriL10n.string("Could not update contacts. Check your connection and try again."))
                    .font(.footnote).foregroundStyle(UnyriColors.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.bottom, 10)
                    .transition(.opacity)
            }
        }
        .background(UnyriColors.chatPanel, in: RoundedRectangle(cornerRadius: 14))
        // On appear, and again after a change that named nobody.
        .task(id: store.revision) { await store.check(user) }
    }

    @ViewBuilder private var icon: some View {
        let image = Image(systemName: isContact ? "person.crop.circle.badge.minus" : "person.crop.circle.badge.plus")
        if #available(iOS 17, *) {
            image.contentTransition(reduceMotion ? .identity : .symbolEffect(.replace))
        } else {
            image
        }
    }

    private func toggle() {
        let adding = !isContact
        if failed { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { failed = false } }
        Task {
            if await store.set(user, contact: adding) {
                UnyriHaptics.selection()
                UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(adding ? "Contact added" : "Contact removed"))
            } else if !Task.isCancelled {
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { failed = true }
            }
        }
    }
}
