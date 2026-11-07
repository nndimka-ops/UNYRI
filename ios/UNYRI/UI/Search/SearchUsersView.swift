import SwiftUI

private enum SearchScope: String, CaseIterable {
    case all = "All", chats = "Chats", messages = "Messages", people = "People"
    var accessibilityKey: String {
        switch self {
        case .all: return "all"
        case .chats: return "chats"
        case .messages: return "messages"
        case .people: return "people"
        }
    }
    var searchesMessages: Bool { self == .all || self == .messages }
    func tint(_ style: UnyriAppColorStyle) -> Color {
        switch self {
        case .all: return style.decorative(Color(hex: 0xAB86F6))
        case .chats: return style.decorative(Color(hex: 0x69C6ED))
        case .messages: return style.decorative(Color(hex: 0xAB86F6))
        case .people: return style.decorative(Color(hex: 0x69C6ED))
        }
    }
    var symbol: String {
        switch self {
        case .all: return "square.grid.2x2"
        case .chats: return "bubble.left.and.bubble.right"
        case .messages: return "text.bubble"
        case .people: return "person.2"
        }
    }
}

/// Lives in ChatsListView's navigation stack. No sheet, second tab or nested stack.
@MainActor
struct SearchUsersView: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    let onClose: () -> Void
    let onOpenChat: (ChatSummary, String?) -> Void
    init(people: Bool = false, onClose: @escaping () -> Void, onOpenChat: @escaping (ChatSummary, String?) -> Void) {
        self.onClose = onClose
        self.onOpenChat = onOpenChat
        _scope = State(initialValue: people ? .people : .all)
    }
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var chats: ChatsRepository
    @EnvironmentObject private var messages: MessagesRepository
    @ObservedObject private var network = UnyriMediaNetwork.shared
    @FocusState private var searchFocused: Bool
    @State private var didFocus = false
    @State private var scope = SearchScope.all
    @State private var presenceOwner = "directory:" + UUID().uuidString
    @State private var visibleDirectoryIDs = Set<String>()
    @State private var cachedChats: [ChatSummary] = []
    @State private var messageResults: [DecryptedChatMessage] = []
    @State private var localError = false
    @State private var localLoading = false
    @State private var localRetry = 0
    @State private var query = ""
    @State private var lastRemoteSearchID: String?
    @State private var lastRemoteQuery: String?
    @State private var lastLocalSearchID: String?
    @State private var results: [PublicUser] = []
    @State private var searchEpoch = UUID()
    @State private var nextOffset: Int64?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var hasSearched = false
    @State private var showingCachedDirectory = false
    @State private var openingChat = false
    @State private var openingError = false
    @State private var openingEpoch = UUID()
    @State private var openingTask: Task<Void, Never>?
    private var normalizedQuery: String {
        let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.hasPrefix("@") ? String(value.dropFirst()) : value
    }
    private var remoteSearchID: String { "\(query)|\(scope.rawValue)|\(network.connected)" }
    private var localSearchID: String {
        // People only needs local chat/block metadata; typing and unrelated
        // message publications do not change that snapshot.
        if scope == .people { return "people|\(chats.cacheRevision)|\(localRetry)" }
        return "\(query)|\(scope.rawValue)|\(chats.cacheRevision)|\(messages.messagesRevision)|\(localRetry)"
    }
    private var chatsByID: [String: ChatSummary] {
        Dictionary(cachedChats.map { ($0.chatId, $0) }, uniquingKeysWith: { _, last in last })
    }

    var body: some View {
        VStack(spacing: 0) {
            searchField
            if scope != .people {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(SearchScope.allCases.filter { $0 != .people }, id: \.self) { option in
                        Button { UnyriHaptics.selection(); scope = option } label: {
                            HStack(spacing: 6) {
                                Image(systemName: option.symbol).font(.system(size: 13, weight: .semibold)).accessibilityHidden(true)
                                Text(UnyriL10n.string(option.rawValue)).font(.subheadline.weight(.semibold))
                            }
                            .padding(.horizontal, 12).frame(minHeight: 44)
                            .foregroundStyle(scope == option ? option.tint(colorStyle) : UnyriColors.secondaryText)
                            .background(option.tint(colorStyle).opacity(scope == option ? 0.14 : 0), in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(option.tint(colorStyle).opacity(scope == option ? 0.32 : 0), lineWidth: 0.75))
                            .contentShape(RoundedRectangle(cornerRadius: 12))
                        }.buttonStyle(.plain)
                         .accessibilityIdentifier("search-scope-" + option.accessibilityKey)
                         .accessibilityValue(Text(UnyriL10n.string(scope == option ? "Selected" : "Not selected")))
                         .accessibilityAddTraits(scope == option ? [.isSelected] : [])
                    }
                }.padding(4)
            }
            .fixedSize(horizontal: false, vertical: true)
            .background(colorStyle.material(hex: 0x10151D), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(colorStyle.material(hex: 0x27313F), lineWidth: 0.5))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("search-scope-controls")
            .padding(.horizontal, 12).padding(.bottom, 4)
            }
            if openingChat { ProgressView().frame(maxWidth: .infinity).padding(4) }
            if openingError {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.bubble").foregroundStyle(Color(hex: 0xF07891)).accessibilityHidden(true)
                    Text("Could not open the conversation. Your search results are still here. Try tapping the person again.")
                        .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                    Button("Close") { openingError = false }.buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(colorStyle.decorative(Color(hex: 0xAB86F6))).frame(minHeight: 44)
                }.padding(12).background(colorStyle.material(hex: 0x172334), in: RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 16).padding(.vertical, 4)
            }
            content
        }
        .unyriScreenBackground()
        .performanceScreen(.search)
        .task(id: remoteSearchID) { await runSearch() }
        .task(id: localSearchID) { await searchLocal() }
        .task { if !didFocus { didFocus = true; searchFocused = scope != .people } }
        .onChange(of: query) { _ in cancelOpening(); releaseDirectoryPresence() }
        .onChange(of: scope) { _ in cancelOpening(); releaseDirectoryPresence() }
        .onChange(of: network.connected) { _ in updateDirectoryPresence() }
        .onChange(of: cachedChats.filter { $0.isBlocked == true }.compactMap { $0.otherUser?.id }) { _ in updateDirectoryPresence() }
        .onDisappear { cancelOpening(); releaseDirectoryPresence() }
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            UnyriBackButton(action: { searchFocused = false; onClose() }, label: "Close search")
                .accessibilityIdentifier("search-close")
            HStack(spacing: 9) {
                Image(systemName: "magnifyingglass").font(.system(size: 18, weight: .semibold))
                    .foregroundColor(colorStyle.decorative(Color(hex: 0x69C6ED))).accessibilityHidden(true)
                TextField("Search", text: $query)
                    .font(.body).focused($searchFocused).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .foregroundColor(UnyriColors.primaryText).submitLabel(.search)
                    .accessibilityIdentifier("search-query")
                    .onSubmit { UnyriHaptics.press(); recordSearch(); searchFocused = false }
                    .onChange(of: query) { value in if value.count > 256 { query = String(value.prefix(256)) } }
                if !query.isEmpty {
                    Button { query = ""; searchFocused = true } label: {
                        Image(systemName: "xmark").font(.system(size: 13, weight: .semibold))
                            .foregroundColor(colorStyle.decorative(Color(hex: 0xAB86F6))).frame(width: 44, height: 44)
                    }.buttonStyle(UnyriQuietButtonStyle()).accessibilityLabel("Clear search").accessibilityIdentifier("search-clear-query")
                }
            }
            .padding(.leading, 13).padding(.trailing, query.isEmpty ? 13 : 0).frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 16).fill(colorStyle.material(hex: 0x141B25)))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(colorStyle.material(hex: 0x303B4B), lineWidth: 0.75))
        }.padding(.horizontal, 12).padding(.vertical, 8)
    }

    private var matchingChats: [ChatSummary] {
        guard !normalizedQuery.isEmpty else { return [] }
        return cachedChats.filter {
            chatTitle($0).localizedCaseInsensitiveContains(normalizedQuery) ||
                ($0.otherUser?.username ?? "").localizedCaseInsensitiveContains(normalizedQuery)
        }
    }
    private func chatTitle(_ chat: ChatSummary) -> String {
        chat.otherUser?.displayName ?? UnyriL10n.string(chat.chatType == "saved" ? "Saved Messages" : "Chats")
    }
    private func recordSearch() {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !term.isEmpty { chats.recentSearchQueries = Array(([term] + chats.recentSearchQueries.filter { $0 != term }).prefix(5)) }
    }
    private func navigate(_ chat: ChatSummary, messageId: String? = nil) {
        recordSearch(); searchFocused = false; onOpenChat(chat, messageId)
    }
    private func section(_ title: String) -> some View {
        let category = SearchScope(rawValue: title)
        let tint = category?.tint(colorStyle) ?? colorStyle.decorative(Color(hex: 0xAB86F6))
        return HStack(spacing: 8) {
            Image(systemName: category?.symbol ?? "clock.arrow.circlepath")
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(tint).accessibilityHidden(true)
            Text(UnyriL10n.string(title)).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.secondaryText)
        }.padding(.top, 16).padding(.bottom, 6)
    }

    private func resultDivider(inset: CGFloat = 66) -> some View {
        Rectangle().fill(colorStyle.material(hex: 0x293749).opacity(0.6)).frame(height: 0.5).padding(.leading, inset)
    }

    private func searchNotice(_ text: String, symbol: String = "magnifyingglass", tint: Color? = nil) -> some View {
        let tint = tint ?? colorStyle.decorative(Color(hex: 0x69C6ED))
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.system(size: 18, weight: .semibold)).foregroundStyle(tint)
                .frame(width: 32, height: 32).background(tint.opacity(0.09), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
            Text(UnyriL10n.string(text)).font(.subheadline).foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
        }.padding(12).background(colorStyle.material(hex: 0x111822).opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(colorStyle.material(hex: 0x27313F).opacity(0.65), lineWidth: 0.5))
            .padding(.vertical, 8)
    }

    private var content: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if normalizedQuery.isEmpty && scope != .people {
                    searchNotice(scope == .people ? "Search by name or @username. This query is sent to your server." : "Search saved chats and messages offline. Find people on this server by username.", symbol: scope.symbol, tint: scope.tint(colorStyle))
                    if !chats.recentSearchQueries.isEmpty {
                        HStack {
                            section("Recent searches"); Spacer()
                            Button("Clear search") { UnyriHaptics.selection(); chats.recentSearchQueries = [] }.buttonStyle(.plain).foregroundStyle(colorStyle.decorative(Color(hex: 0xAB86F6))).font(.caption.weight(.semibold)).frame(minHeight: 44).accessibilityIdentifier("search-clear-history")
                        }
                        ForEach(chats.recentSearchQueries, id: \.self) { term in
                            Button { UnyriHaptics.selection(); query = term } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "clock").foregroundStyle(colorStyle.decorative(Color(hex: 0xAB86F6))).frame(width: 32, height: 32)
                                        .background(colorStyle.decorative(Color(hex: 0xAB86F6)).opacity(0.08), in: RoundedRectangle(cornerRadius: 10)).accessibilityHidden(true)
                                    Text(term).font(.body).foregroundStyle(UnyriColors.primaryText).lineLimit(2)
                                    Spacer(minLength: 0)
                                    Image(systemName: "arrow.up.left").font(.caption).foregroundStyle(UnyriColors.mutedText)
                                }.frame(minHeight: 52).padding(.horizontal, 10).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            resultDivider(inset: 54)
                        }
                    }
                } else {
                    if scope == .all || scope == .chats {
                        section("Chats")
                        if matchingChats.isEmpty { searchNotice("No results.", symbol: SearchScope.chats.symbol, tint: SearchScope.chats.tint(colorStyle)) }
                        ForEach(matchingChats) { chat in
                            Button { UnyriHaptics.press(); navigate(chat) } label: {
                                if let user = chat.otherUser { SearchUserRow(user: user) }
                                else { Label(chatTitle(chat), systemImage: "bookmark.fill").font(.body).foregroundStyle(colorStyle.decorative(Color(hex: 0xF1C36C))).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading).padding(.horizontal, 10) }
                            }.buttonStyle(.plain).disabled(openingChat)
                            resultDivider()
                        }
                    }
                    if scope.searchesMessages { messageSection }
                    if scope == .all {
                        Button { UnyriHaptics.selection(); scope = .people } label: { Label("Find someone on this server", systemImage: "person.2").font(.subheadline.weight(.semibold)).foregroundStyle(SearchScope.people.tint(colorStyle)).frame(maxWidth: .infinity, minHeight: 48).padding(.horizontal, 12).background(SearchScope.people.tint(colorStyle).opacity(0.08), in: RoundedRectangle(cornerRadius: 14)) }.buttonStyle(.plain).padding(.top, 14)
                    }
                    if scope == .people { peopleSection }
                }
            }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24)
        }.scrollDismissesKeyboard(.interactively)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("search-results-" + scope.accessibilityKey)
    }

    @ViewBuilder private var messageSection: some View {
        section("Messages")
        if localLoading { ProgressView().tint(SearchScope.messages.tint(colorStyle)).frame(maxWidth: .infinity).padding(12) }
        else if localError {
            searchNotice("Could not load saved messages. Try searching again.", symbol: "exclamationmark.bubble", tint: Color(hex: 0xF07891))
            Button("Retry") { localRetry += 1 }.buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(SearchScope.messages.tint(colorStyle)).frame(minHeight: 44)
        } else if messageResults.isEmpty { searchNotice("No results.", symbol: SearchScope.messages.symbol, tint: SearchScope.messages.tint(colorStyle)) }
        let byID = chatsByID
        ForEach(messageResults) { message in
            if let chat = byID[message.chatId] {
                Button { UnyriHaptics.press(); navigate(chat, messageId: message.clientMessageId) } label: {
                    HStack(alignment: .top, spacing: 11) {
                        Image(systemName: "text.bubble").font(.system(size: 17, weight: .semibold)).foregroundStyle(SearchScope.messages.tint(colorStyle))
                            .frame(width: 34, height: 34).background(SearchScope.messages.tint(colorStyle).opacity(0.09), in: RoundedRectangle(cornerRadius: 11)).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 5) {
                            HStack {
                                Text(chatTitle(chat)).lineLimit(1).foregroundColor(SearchScope.messages.tint(colorStyle))
                                Spacer(minLength: 8)
                                Text(message.createdAt, style: .date).foregroundColor(UnyriColors.mutedText)
                            }.font(.caption)
                            Text(messagePreview(message)).font(.body)
                                .lineLimit(3).foregroundColor(UnyriColors.primaryText)
                        }
                    }.frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, 12)
                        .background(colorStyle.material(hex: 0x111822).opacity(0.8), in: RoundedRectangle(cornerRadius: 14)).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(openingChat)
                resultDivider(inset: 55)
            }
        }
    }

    private func messagePreview(_ message: DecryptedChatMessage) -> AttributedString {
        let source = message.content.text?.isEmpty == false ? message.displayText : (message.content.attachment?.filename ?? message.displayText)
        var preview = AttributedString(SearchPresentation.snippet(source, query: query))
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !term.isEmpty, let range = preview.range(of: term, options: [.caseInsensitive, .diacriticInsensitive]) {
            preview[range].font = .body.bold()
        }
        return preview
    }

    @ViewBuilder private var peopleSection: some View {
        section("People")
        if showingCachedDirectory {
            Text("Saved data. Connect to refresh.").font(.caption).foregroundStyle(UnyriColors.secondaryText).padding(.vertical, 4)
        }
        Text(UnyriL10n.string(normalizedQuery.isEmpty ? "People on this server. Search by name or @username." : "Search by name or @username. This query is sent to your server."))
            .font(.footnote).foregroundColor(UnyriColors.secondaryText).padding(.vertical, 8)
        if !network.connected {
            searchNotice("Connect to search for people on this server.", symbol: "wifi.slash", tint: SearchScope.people.tint(colorStyle))
        } else if let errorMessage {
            searchNotice(errorMessage, symbol: "exclamationmark.bubble", tint: Color(hex: 0xF07891))
            Button("Retry") { Task { if results.isEmpty { await runSearch() } else { await load(reset: false) } } }
                .buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(SearchScope.people.tint(colorStyle)).frame(minHeight: 44)
        } else if isLoading { ProgressView().tint(SearchScope.people.tint(colorStyle)).frame(maxWidth: .infinity).padding(12) }
        else if hasSearched && results.isEmpty { searchNotice("No users found.", symbol: "person.2", tint: SearchScope.people.tint(colorStyle)) }
        ForEach(results) { user in
            Button {
                UnyriHaptics.press()
                if let existing = cachedChats.first(where: { $0.otherUser?.id == user.id }) { navigate(existing) }
                else { openChat(user) }
            } label: { SearchUserRow(user: user, showsPresence: true, allowsPresence: network.connected && !cachedChats.contains { $0.isBlocked == true && $0.otherUser?.id == user.id }) }.buttonStyle(.plain).disabled(openingChat)
                .onAppear { visibleDirectoryIDs.insert(user.id); updateDirectoryPresence() }
                .onDisappear { visibleDirectoryIDs.remove(user.id); updateDirectoryPresence() }
            resultDivider()
        }
        if nextOffset != nil && !isLoading && network.connected && errorMessage == nil {
            Button("Load more") { Task { await load(reset: false) } }
                .buttonStyle(UnyriQuietButtonStyle()).foregroundStyle(SearchScope.people.tint(colorStyle)).frame(maxWidth: .infinity, minHeight: 44).padding(.top, 8)
        }
    }

    private func updateDirectoryPresence() {
        guard scope == .people, network.connected else { realtime.unsubscribePresence(owner: presenceOwner); return }
        let blocked = Set(cachedChats.filter { $0.isBlocked == true }.compactMap { $0.otherUser?.id })
        realtime.subscribePresence(owner: presenceOwner, userIDs: visibleDirectoryIDs.filter { !blocked.contains($0) }.sorted())
    }

    private func releaseDirectoryPresence() {
        visibleDirectoryIDs.removeAll()
        realtime.unsubscribePresence(owner: presenceOwner)
    }

    private func searchLocal() async {
        let snapshot = localSearchID
        if lastLocalSearchID == snapshot && !localError { return }
        messageResults = []; localError = false; localLoading = scope.searchesMessages && !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let local = await chats.loadCachedChatsAsync()
        guard !Task.isCancelled, localSearchID == snapshot else { return }
        cachedChats = local
        guard localLoading else { lastLocalSearchID = snapshot; return }
        do {
            try await Task.sleep(nanoseconds: 200_000_000)
            let found = try await messages.searchCachedMessages(query: query)
            guard !Task.isCancelled, localSearchID == snapshot else { return }
            let known = Set(local.map(\.chatId))
            messageResults = found.filter { known.contains($0.chatId) }
        } catch {
            guard !Task.isCancelled, localSearchID == snapshot else { return }
            localError = true
        }
        localLoading = false
        if !localError { lastLocalSearchID = snapshot }
    }

    private func load(reset: Bool) async {
        let trimmed = normalizedQuery
        let epoch = searchEpoch
        guard scope == .people, !isLoading || reset else { return }

        isLoading = true
        errorMessage = nil
        let offset = reset ? Int64(0) : (nextOffset ?? 0)
        do {
            let response = try await users.directory(query: trimmed, limit: 30, offset: offset)
            guard !Task.isCancelled, normalizedQuery == trimmed, epoch == searchEpoch else { return }
            // Offset pages may overlap while users are added or renamed.
            // SwiftUI ForEach requires a unique identity for every row.
            var seen = Set<String>()
            results = ((reset ? [] : results) + response.items).filter { seen.insert($0.id).inserted }
            nextOffset = response.nextOffset.flatMap { $0 > offset ? $0 : nil }
            hasSearched = true
            showingCachedDirectory = false
        } catch {
            guard !Task.isCancelled, normalizedQuery == trimmed, epoch == searchEpoch else { return }
            if case APIError.http(404) = error {
                results = []; nextOffset = nil; showingCachedDirectory = false; hasSearched = false
                errorMessage = UnyriL10n.string("Server directory is unavailable. Update your server to use People.")
            } else if case APIError.server(let code, _) = error, ["not_found", "route_not_found"].contains(code) {
                results = []; nextOffset = nil; showingCachedDirectory = false; hasSearched = false
                errorMessage = UnyriL10n.string("Server directory is unavailable. Update your server to use People.")
            } else {
                errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
                if case APIError.network = error { showingCachedDirectory = !results.isEmpty }
                else if case APIError.http(let status) = error, [401, 403].contains(status) {
                    results = []; nextOffset = nil; showingCachedDirectory = false; hasSearched = false
                } else if case APIError.server(let code, _) = error, ["unauthorized", "forbidden", "session_revoked", "invalid_access_token", "account_restricted"].contains(code) {
                    results = []; nextOffset = nil; showingCachedDirectory = false; hasSearched = false
                }
            }
        }
        isLoading = false
    }

    private func cancelOpening() {
        openingEpoch = UUID(); openingTask?.cancel(); openingTask = nil; openingChat = false; openingError = false
    }

    private func openChat(_ user: PublicUser) {
        guard !openingChat else { return }
        // A known conversation is already available locally, including offline.
        if let existing = cachedChats.first(where: { $0.otherUser?.id == user.id }) {
            navigate(existing)
            return
        }
        openingChat = true; openingError = false
        let epoch = UUID(); openingEpoch = epoch
        openingTask = Task {
            defer { if epoch == openingEpoch { openingChat = false } }
            do {
                let chat = try await chats.createOrOpenDirectChat(otherUserId: user.id)
                guard !Task.isCancelled, epoch == openingEpoch else { return }
                navigate(chat)
            } catch {
                guard !Task.isCancelled, epoch == openingEpoch else { return }
                openingError = true
            }
        }
    }

    private func runSearch() async {
        let requestID = remoteSearchID
        if lastRemoteSearchID == requestID && hasSearched && errorMessage == nil { return }
        if scope == .people && !network.connected && lastRemoteQuery == normalizedQuery {
            searchEpoch = UUID(); isLoading = false; errorMessage = nil
            if results.isEmpty {
                let saved = await users.cachedDirectory(query: normalizedQuery)
                guard !Task.isCancelled, remoteSearchID == requestID else { return }
                results = saved; hasSearched = !saved.isEmpty; nextOffset = nil
            }
            showingCachedDirectory = !results.isEmpty
            return
        }
        lastRemoteSearchID = requestID
        lastRemoteQuery = normalizedQuery
        searchEpoch = UUID()
        results = []; nextOffset = nil; errorMessage = nil; hasSearched = false; isLoading = false; showingCachedDirectory = false
        // Local decrypted text must not become a server-side people query implicitly.
        guard scope == .people else { return }
        let saved = await users.cachedDirectory(query: normalizedQuery)
        guard !Task.isCancelled, remoteSearchID == requestID else { return }
        if !saved.isEmpty { results = saved; showingCachedDirectory = true; hasSearched = true }
        guard network.connected else { return }
        isLoading = true
        if !normalizedQuery.isEmpty {
            do { try await Task.sleep(nanoseconds: 300_000_000) } catch { return }
        }
        guard !Task.isCancelled else { return }
        await load(reset: true)
    }
}

private struct SearchUserRow: View {
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var users: UsersRepository
    let user: PublicUser
    var showsPresence = false
    var allowsPresence = true

    @State private var avatarURL: URL?

    var body: some View {
        HStack(spacing: 12) {
            UserAvatar(url: avatarURL ?? users.cachedAvatarURL(id: user.id, avatarUrl: user.avatarUrl, avatarVersion: user.avatarVersion), name: user.displayName, size: 44)
                .overlay(alignment: .bottomTrailing) {
                    if showsPresence && allowsPresence, realtime.presenceObservation(for: user.id)?.item.online == true {
                        Circle().fill(Color(hex: 0x67D6B6)).frame(width: 9, height: 9)
                            .overlay(Circle().stroke(UnyriColors.background, lineWidth: 2)).accessibilityHidden(true)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(user.displayName)
                    .font(.body.weight(.medium))
                    .foregroundColor(UnyriColors.primaryText)
                    .lineLimit(1)
                Text("@\(user.username)")
                    .font(.caption)
                    .foregroundColor(UnyriColors.secondaryText)
                    .lineLimit(1)
                if showsPresence {
                    let status = (allowsPresence ? realtime.presenceLabel(for: user.id) : nil) ?? UnyriL10n.string("Status unknown")
                    Text(status).font(.caption).foregroundStyle(allowsPresence && realtime.presenceObservation(for: user.id)?.item.online == true ? Color(hex: 0x67D6B6) : UnyriColors.secondaryText)
                        .lineLimit(1).accessibilityLabel(Text(status))
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.medium))
                .foregroundColor(UnyriColors.mutedText)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading).contentShape(Rectangle())
        .task(id: user.avatarVersion) {
            avatarURL = await users.avatarURL(
                id: user.id,
                avatarUrl: user.avatarUrl,
                avatarVersion: user.avatarVersion
            )
        }
    }
}
