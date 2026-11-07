import SwiftUI

@MainActor
struct ChatsListView: View {
    @Binding var showingArchive: Bool
    init(showingArchive: Binding<Bool> = .constant(false)) { _showingArchive = showingArchive }
    @State private var contactMenu: ContactMenuSnapshot?
    @Environment(\.unyriAppColorStyle) private var colorStyle
    private struct ContactMenuSnapshot {
        let chat: ChatSummary
        let owner: String
    }
    private enum ContactMenuAction { case open, pin, mute, archive, copy, report, block, delete }
    private var menuOwner: String {
        "\(AppConfig.storageOrigin)|\(auth.currentUser?.id ?? "")|\(auth.sessionGeneration)"
    }
    @Environment(\.callIsland) private var callIsland
    @State private var isSearching = false
    @Environment(\.unyriSearchRequest) private var searchRequest
    @State private var handledSearchRequest: UUID?
    @State private var searchPeople = false
    @State private var searchSession = UUID()
    @State private var openedMessageId: String?
    private func onSearch(people: Bool = false) {
        openingGate.cancel(); openingTask?.cancel(); openingTask = nil
        guard !batchBusy else { return }
        selecting = false; selection.removeAll(); batchDeleteIDs.reset()
        searchPeople = people; searchSession = UUID(); isSearching = true
    }
    private func handleSearchRequest(_ request: SearchNavigationRequest?) {
        guard let request, request.id != handledSearchRequest else { return }
        handledSearchRequest = request.id
        // Never defer a root request until after a detail or modal disappears.
        guard !isShowingChat, contactMenu == nil else { return }
        if request.destination == .chats {
            openingGate.cancel(); openingTask?.cancel(); openingTask = nil
            isSearching = false
        } else { onSearch(people: request.destination == .people) }
    }
    @EnvironmentObject private var callsHistory: CallsRepository
    @EnvironmentObject private var callController: CallController
    @EnvironmentObject private var realtime: RealtimeClient
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @State private var presenceOwner = "chat-list:" + UUID().uuidString
    @State private var visiblePresenceChats = Set<String>()
    @State private var listIsVisible = false
    private struct PresenceRequest: Equatable {
        let account: String
        let active: Bool
        let ids: [String]
    }
    private var presenceRequest: PresenceRequest {
        let active = listIsVisible && scenePhase == .active && !isShowingChat && !isSearching
        let ids = active ? visibleChats.compactMap { chat -> String? in
            guard visiblePresenceChats.contains(chat.chatId), chat.isBlocked != true else { return nil }
            return chat.otherUser?.id
        }.sorted() : []
        return PresenceRequest(account: menuOwner, active: active, ids: ids)
    }

    @Environment(\.unyriSavedRequest) private var savedRequest
    @State private var handledSavedRequest: UUID?
    @State private var rejectedSavedRequest: UUID?
    @State private var deleteRequestId = UUID().uuidString
    @State private var deleteTarget: ChatSummary?
    @State private var deleteOwner: String?
    @State private var deletingChat = false
    @State private var chats: [ChatSummary] = []
    /// The persistent cache has answered once. Until then an empty `chats` means
    /// "still hydrating", never "no conversations".
    @State private var didHydrateCache = false
    @State private var nextOffset: Int64?
    @State private var isLoading = false
    @State private var isLoadingMore = false
    @State private var errorMessage: String?
    @State private var openedChat: ChatSummary?
    @State private var openedSafetyAction: String?
    @State private var preferenceBusy = false
    @State private var isShowingChat = false
    @State private var openingGate = ChatOpeningGate()
    @State private var openingTask: Task<Void, Never>?
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var batchBusy = false
    private enum BatchAction { case pin(Bool), mute(Bool), archive(Bool), delete(Bool) }
    private struct BatchDeleteRequest {
        let owner: String
        let requestsByScope: [Bool: [String: String]]
    }
    @State private var batchDeleteIDs = ChatListDeleteRequestIDs()
    @State private var batchDeleteRequest: BatchDeleteRequest?
    private var selectionChats: [ChatSummary] { chats.filter { selection.contains($0.chatId) && $0.chatType != "saved" } }
    private var selectedAllPinned: Bool { !selection.isEmpty && selection.isSubset(of: pinned) }
    private var selectedAllMuted: Bool { !selectionChats.isEmpty && selectionChats.allSatisfy { $0.isMuted == true } }
    @State private var pinned = Set<String>()
    private var pinOwner: String { "chatPins|" + AppConfig.storageOrigin + "|" + (auth.currentUser?.id ?? "") }
    @State private var pinBusy = false
    private struct RefreshContext: Equatable {
        let owner: String
        let active: Bool
        let connected: Bool
        let visible: Bool
        let management: UInt64
    }
    private var refreshContext: RefreshContext {
        RefreshContext(owner: menuOwner, active: scenePhase == .active,
            connected: realtime.state == .connected, visible: !isShowingChat && !isSearching,
            management: chatsRepository.managementRevision)
    }
    private struct CacheContext: Equatable {
        let owner: String
        let active: Bool
        let revision: UInt64
        let cutoff: Int64
    }
    private func refreshVisibleList() async {
        guard refreshContext.active, refreshContext.visible else { return }
        // Scene activation, socket connection and management changes often
        // arrive together. Cancellation coalesces them into one refresh.
        do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
        await refreshPins()
        guard !Task.isCancelled else { return }
        await load(reset: true)
    }
    private func refreshPins() async {
        let owner = pinOwner
        // Clears and pins are independent server snapshots: both must settle
        // before the list fetch, but not one after the other. The clear pass
        // still completes before `load(reset:)` runs (privacy fence).
        async let clears: Void = { try? await chatsRepository.refreshPersonalClears() }()
        let fetchedPins = try? await chatsRepository.pins()
        _ = await clears
        guard owner == pinOwner, !Task.isCancelled else { return }
        if var result = fetchedPins {
            guard owner == pinOwner, !Task.isCancelled else { return }
            if !UserDefaults.standard.bool(forKey: pinOwner + "|synced") {
                if !pinned.isEmpty && chats.isEmpty { return }
                let migrate = pinned.intersection(Set(chats.map(\.chatId))).subtracting(result)
                if !migrate.isEmpty {
                    guard let merged = try? await chatsRepository.setPins(migrate, pinned: true) else { return }
                    guard owner == pinOwner, !Task.isCancelled else { return }
                    result = merged
                }
                UserDefaults.standard.set(true, forKey: pinOwner + "|synced")
            }
            pinned = result
            UserDefaults.standard.set(Array(result).sorted(), forKey: pinOwner)
        }
    }
    private func setPinned(_ ids: Set<String>, enabled: Bool) {
        guard auth.currentUser != nil, !pinBusy else { return }
        let owner = pinOwner
        let requestOwner = menuOwner
        pinBusy = true
        Task { @MainActor in
            defer { if requestOwner == menuOwner { pinBusy = false } }
            guard requestOwner == menuOwner else { return }
            do {
                let result = try await chatsRepository.setPins(ids, pinned: enabled)
                guard owner == pinOwner, requestOwner == menuOwner else { return }
                pinned = result
                UserDefaults.standard.set(Array(result).sorted(), forKey: owner)
            } catch {
                guard owner == pinOwner, requestOwner == menuOwner else { return }
                errorMessage = UnyriL10n.string("Could not update"); showError = true
            }
        }
    }
    private func toggleSelection(_ id: String) {
        guard !batchBusy else { return }
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
        UnyriHaptics.selection()
    }
    @State private var unreadOnly = false
    @State private var showError = false
    @State private var titleAppeared = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var network = UnyriMediaNetwork.shared
    private struct CallIndexRevision: Equatable {
        let owner: String
        let calls: UInt64
    }
    @State private var callIndexCache = ChatPresentationCache<CallIndexRevision, [String: CallDTO]>()
    private var latestCalls: [String: CallDTO] {
        callIndexCache.value(for: CallIndexRevision(owner: menuOwner, calls: callsHistory.historyRevision)) {
            ChatCallActivity.latestByChat(callsHistory.chatHistory, owner: auth.currentUser.flatMap { UUID(uuidString: $0.id) })
        }
    }
    private struct ListPresentationRevision: Equatable {
        let chats: [ChatSummary]
        let pinned: Set<String>
        let unreadOnly: Bool
        let showingArchive: Bool
        let archived: Set<String>
        let unread: [String: Int]
        let calls: CallIndexRevision
    }
    @State private var listPresentationCache = ChatPresentationCache<ListPresentationRevision, [ChatSummary]>()
    private var visibleChats: [ChatSummary] {
        let key = ListPresentationRevision(chats: chats, pinned: pinned, unreadOnly: unreadOnly,
            showingArchive: showingArchive, archived: chatsRepository.archivedChatIDs, unread: unreadOnly ? chatsRepository.unreadCounts : [:],
            calls: CallIndexRevision(owner: menuOwner, calls: callsHistory.historyRevision))
        return listPresentationCache.value(for: key) {
        let calls = latestCalls
        func activity(_ chat: ChatSummary) -> Date {
            ChatCallActivity.latestDate(message: chat.lastMessageAt.flatMap(ISO8601Cocoa.parse),
                call: calls[chat.chatId])
        }
        let dates = Dictionary(uniqueKeysWithValues: chats.map { ($0.chatId, activity($0)) })
        return chats.filter { ChatListArchivePolicy.includes(chatID: $0.chatId, type: $0.chatType,
            archived: chatsRepository.archivedChatIDs, showingArchive: showingArchive) &&
            (!unreadOnly || (chatsRepository.unreadCounts[$0.chatId] ?? 0) > 0) }
            .sorted {
                if pinned.contains($0.chatId) != pinned.contains($1.chatId) { return pinned.contains($0.chatId) }
                let left = dates[$0.chatId] ?? .distantPast, right = dates[$1.chatId] ?? .distantPast
                return left == right ? $0.chatId > $1.chatId : left > right
            }
        }
    }

    private func openReminder() {
        guard let route = UserDefaults.standard.dictionary(forKey: "chatReminderRoute"), NotificationRecipientPolicy.matches(owner: route["owner"] as? String, current: auth.currentUser?.id),
              let chat = route["chat"] as? String else { return }
        chatsRepository.openChatRequest = chat
    }
    var body: some View {
        NavigationStack {
            ZStack {
            VStack(spacing: 8) {
                if selecting { selectionHeader }
                content
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { if selecting { selectionActions } }
            .opacity(isSearching ? 0 : 1)
            .allowsHitTesting(!isSearching)
            .accessibilityHidden(isSearching)
            if isSearching {
                SearchUsersView(people: searchPeople, onClose: { isSearching = false }, onOpenChat: { chat, messageId in
                    open(chat, messageId: messageId)
                }).id(searchSession)
            }
            }
                .unyriScreenBackground()
                .onAppear { listIsVisible = true; chatsRepository.isChatListVisible = true; openReminder(); withAnimation(reduceMotion ? nil : .easeOut(duration: 0.7)) { titleAppeared = true } }
                .task(id: presenceRequest) {
                    let request = presenceRequest
                    // Rows appear one by one while the list hydrates and scrolls.
                    // Debouncing the id set collapses that burst into one socket
                    // subscription instead of resending the whole id list per row.
                    do { try await Task.sleep(nanoseconds: 150_000_000) } catch { return }
                    guard !Task.isCancelled else { return }
                    realtime.subscribePresence(owner: presenceOwner, userIDs: request.ids, priority: 20)
                }
                .task(id: pinOwner) {
                    pinned = chatsRepository.cachedPins()
                    selection.removeAll(); selecting = false; batchDeleteIDs.reset()
                }
                .onChange(of: chats.map(\.chatId)) { ids in selection.formIntersection(Set(ids)) }
                .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOChatReminder"))) { _ in openReminder() }
                .onDisappear { listIsVisible = false; chatsRepository.isChatListVisible = false; realtime.unsubscribePresence(owner: presenceOwner) }
                .confirmationDialog(Text(LocalizedStringKey(batchDeleteRequest == nil ? "Delete conversation?" : "Delete selected chats?")), isPresented: Binding(get: { deleteTarget != nil || batchDeleteRequest != nil }, set: { if !$0 { deleteTarget = nil; batchDeleteRequest = nil } }), titleVisibility: .visible) {
                    if let request = batchDeleteRequest {
                        Button("Delete only for me", role: .destructive) { runSelectionAction(.delete(false), deleteRequest: request) }
                        Button("Delete for both", role: .destructive) { runSelectionAction(.delete(true), deleteRequest: request) }
                    } else if let target = deleteTarget {
                        Button("Delete only for me", role: .destructive) { deleteMenuChat(target, forEveryone: false) }
                        Button("Delete for both", role: .destructive) { deleteMenuChat(target, forEveryone: true) }
                    }
                    Button("Cancel", role: .cancel) { deleteTarget = nil; batchDeleteRequest = nil }
                } message: { Text("Choose who loses the selected conversations and their call history. For both cannot be undone. Downloaded files and screenshots are not removed. Offline changes sync after reconnection.") }
                .navigationTitle("")
                .performanceScreen(.chats)
                .toolbar {
                    if let callIsland {
                        ToolbarItem(placement: .principal) { CallIslandTitle(presentation: callIsland) }
                    } else if showingArchive {
                        ToolbarItem(placement: .principal) { Text("Archive").font(.headline) }
                    } else {
                        ToolbarItem(placement: .principal) {
                            UnyriConnectionHeader(realtime: realtime, routeAvailable: network.connected,
                                loading: (isLoading || !didHydrateCache) && chats.isEmpty, error: errorMessage != nil, onRetry: { Task { await realtime.retryConnectionSync(); await load(reset: true) } },
                                visible: !isShowingChat && contactMenu == nil, contentReady: !chats.isEmpty)
                        }
                    }
                    if showingArchive {
                        UnyriBackToolbarItem(action: { leaveArchive() }, label: "Back to chats", identifier: "archive-back")
                    } else {
                        ToolbarItem(placement: .navigationBarLeading) { UnyriMenuButton() }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) { if !isSearching { compactHeaderActions } }
                }
                .navigationBarTitleDisplayMode(.inline)
                .alert("Could not update", isPresented: $showError) {
                    Button("Retry") { Task { await chatsRepository.replayPendingChatClears(); await load(reset: true) } }
                    Button("Cancel", role: .cancel) { }
                } message: { Text(errorMessage ?? "") }
                .onChange(of: chatsRepository.archiveReplayError) { message in
                    guard let message else { return }
                    errorMessage = message; showError = true
                }
                .onChange(of: chatsRepository.clearReplayError) { message in
                    guard let message else { return }
                    errorMessage = message; showError = true
                }
                .navigationDestination(isPresented: $isShowingChat.transaction(chatNavigationTransaction)) {
                    if let openedChat {
                        ChatScreen(chat: openedChat, initialSafetyAction: openedSafetyAction, initialMessageId: openedMessageId)
                            .id(openedChat.chatId)
                    }
                }
                .onChange(of: isShowingChat) { shown in
                    chatsRepository.navigationChatId = shown ? openedChat?.chatId : nil
                    if !shown { chatsRepository.notificationHeaderHeight = 44 }
                }
                .onChange(of: openedChat?.chatId) { id in
                    if isShowingChat { chatsRepository.navigationChatId = id }
                }
                .task {
                    await callsHistory.loadCachedHistory()
                }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    _ = try? await callsHistory.refreshHistory()
                }
                .onChange(of: callController.state) { state in
                    if case .ended = state {
                        // The automatic transition to idle must not cancel this final refresh.
                        Task { _ = try? await callsHistory.refreshHistory() }
                    }
                }
                .task(id: refreshContext) {
                    await refreshVisibleList()
                }
                .onReceive(chatsRepository.$lastListSuccess) { success in
                    if success != nil { errorMessage = nil }
                }
                .onReceive(chatsRepository.$openChatRequest) { chatId in
                    // Foreground banner tap (Runtime Correction D): open the
                    // corresponding chat through this list's own navigation.
                    guard let chatId else { return }
                    guard !openingGate.isPending, !isShowingChat else { return }
                    chatsRepository.openChatRequest = nil
                    if let chat = chats.first(where: { $0.chatId == chatId }) {
                        open(chat)
                    } else {
                        openingTask?.cancel()
                        guard let ticket = openingGate.begin() else { return }
                        openingTask = Task { @MainActor in
                            let summary = try? await chatsRepository.getChat(chatId: chatId)
                            // Release this lookup before passing through the normal
                            // navigation guard. Ignore late responses after cancellation.
                            guard openingGate.finish(ticket) else { return }
                            openingTask = nil
                            guard !Task.isCancelled, let summary else { return }
                            open(summary)
                        }
                    }
                }
                .task(id: CacheContext(owner: menuOwner, active: refreshContext.active,
                    revision: chatsRepository.cacheRevision,
                    cutoff: auth.historyBeforeSequence ?? 0)) {
                    guard refreshContext.active, refreshContext.visible else { return }
                    // A list that is already on screen coalesces revision bursts
                    // (message arrivals, clear/archive replays) into one cache
                    // hydration instead of one per revision. The first hydration
                    // is never delayed: it is what puts cached rows on screen.
                    if !chats.isEmpty {
                        do { try await Task.sleep(nanoseconds: 150_000_000) } catch { return }
                    }
                    let cached = await chatsRepository.loadCachedChatsAsync()
                    guard !Task.isCancelled else { return }
                    chats = cached
                    didHydrateCache = true
                }
        }
        .onChange(of: savedRequest) { request in handleSavedRequest(request) }
        .onChange(of: showingArchive) { _ in
            guard !batchBusy else { return }
            contactMenu = nil; batchDeleteRequest = nil; batchDeleteIDs.reset()
            selection.removeAll(); selecting = false; unreadOnly = false
            isSearching = false
        }
        .onChange(of: chatsRepository.archivedChatIDs) { _ in
            guard !batchBusy else { return }
            selection.formIntersection(Set(visibleChats.map(\.chatId)))
        }
        .onAppear { UnyriSavedRouteQA.record(2); handleSavedRequest(savedRequest); handleSearchRequest(searchRequest) }
        .toolbar(.hidden, for: .tabBar)
        .preference(key: UnyriRootDockHiddenKey.self, value: isShowingChat || contactMenu != nil)
        .unyriBlocksRootDrawer(isShowingChat || contactMenu != nil || selecting || deleteTarget != nil || batchDeleteRequest != nil || showError)
        .toolbar(.visible, for: .navigationBar)
        .accessibilityHidden(contactMenu != nil)
        .overlay { contactMenuOverlay }
        .onChange(of: menuOwner) { _ in
            visiblePresenceChats.removeAll()
            realtime.unsubscribePresence(owner: presenceOwner)
            contactMenu = nil; deleteTarget = nil; deleteOwner = nil
            deletingChat = false; preferenceBusy = false; pinBusy = false
            batchBusy = false; batchDeleteRequest = nil; batchDeleteIDs.reset(); selection.removeAll(); selecting = false
            // A new account/server/session must not keep showing the previous
            // owner's rows, and its cold list is "loading", not "empty".
            chats = []; nextOffset = nil; didHydrateCache = false; errorMessage = nil
            UnyriSavedRouteQA.record(11, 3)
            openingGate.cancel(); openingTask?.cancel(); openingTask = nil
        }
        .onChange(of: isShowingChat) { if $0 { contactMenu = nil } }
            .onChange(of: isSearching) { if $0 { contactMenu = nil } }
            .onChange(of: searchRequest) { request in handleSearchRequest(request) }
        .onDisappear {
            UnyriSavedRouteQA.record(3, openingGate.isPending ? 1 : 0)
            contactMenu = nil
            // The lookup belongs to the ChatsList/NavigationStack, not its
            // replaceable root content. Only leaving this owner cancels it.
            UnyriSavedRouteQA.record(11, 2)
            openingGate.cancel(); openingTask?.cancel(); openingTask = nil
        }
    }

    private func presentContactMenu(_ chat: ChatSummary) {
        guard !selecting, !batchBusy, !isShowingChat, !isSearching, !openingGate.isPending, auth.currentUser != nil else { return }
        contactMenu = ContactMenuSnapshot(chat: chat, owner: menuOwner)
        UnyriHaptics.longPress()
    }

    private func deleteMenuChat(_ target: ChatSummary, forEveryone: Bool) {
        guard deleteOwner == menuOwner, !deletingChat else { return }
        let owner = menuOwner
        let requestId = deleteRequestId
        let repository = chatsRepository
        deletingChat = true
        UnyriHaptics.selection()
        Task { @MainActor in
            defer { if owner == menuOwner { deletingChat = false } }
            guard owner == menuOwner, !Task.isCancelled else { return }
            do {
                try await repository.deleteForBoth(target.chatId, forEveryone: forEveryone, requestId: requestId)
                guard owner == menuOwner, !Task.isCancelled else { return }
                let cached = await repository.loadCachedChatsAsync()
                guard owner == menuOwner, !Task.isCancelled else { return }
                chats = cached
            } catch is CancellationError { }
              catch {
                guard owner == menuOwner else { return }
                errorMessage = UnyriL10n.string("Could not update"); showError = true
            }
        }
    }

    private func closeContactMenu() {
        guard contactMenu != nil else { return }
        contactMenu = nil
        UnyriHaptics.selection()
    }

    private func performMenuAction(_ action: ContactMenuAction, snapshot: ContactMenuSnapshot) {
        guard snapshot.owner == menuOwner, contactMenu?.owner == snapshot.owner,
              contactMenu?.chat.chatId == snapshot.chat.chatId,
              let chat = chats.first(where: { $0.chatId == snapshot.chat.chatId }) else {
            contactMenu = nil
            return
        }
        switch action {
        case .pin: guard !pinBusy else { return }
        case .mute, .archive: guard !preferenceBusy else { return }
        case .delete: guard chat.chatType == "direct", !deletingChat else { return }
        case .copy: guard let username = chat.otherUser?.username, !username.isEmpty else { return }
        case .open, .report, .block: guard !openingGate.isPending, !isShowingChat else { return }
        }
        // Plain menu buttons deliver feedback here only after accepting the
        // action. Dismiss before navigation or presentation of confirmation.
        contactMenu = nil
        UnyriHaptics.selection()
        switch action {
        case .open: open(chat)
        case .pin: setPinned([chat.chatId], enabled: !pinned.contains(chat.chatId))
        case .mute: changeNotifications(chat)
        case .archive: changeArchive(chat)
        case .copy: if let username = chat.otherUser?.username { UIPasteboard.general.string = "@" + username }
        case .report: open(chat, safetyAction: "report")
        case .block: open(chat, safetyAction: "block")
        case .delete: deleteRequestId = UUID().uuidString; deleteOwner = snapshot.owner; deleteTarget = chat
        }
    }

    @ViewBuilder private var contactMenuOverlay: some View {
        if let snapshot = contactMenu, snapshot.owner == menuOwner {
            let chat = chats.first(where: { $0.chatId == snapshot.chat.chatId }) ?? snapshot.chat
            GeometryReader { geometry in
                ZStack(alignment: .bottom) {
                    UnyriChatMenuBackdrop { closeContactMenu() }
                    ViewThatFits(in: .vertical) {
                        contactMenuCard(chat, snapshot: snapshot).fixedSize(horizontal: false, vertical: true)
                        ScrollView { contactMenuCard(chat, snapshot: snapshot) }
                            .frame(height: max(120, geometry.size.height - 32))
                    }
                    .frame(maxWidth: 440, maxHeight: max(120, geometry.size.height - 32), alignment: .bottom)
                    .padding(.horizontal, 12)
                    .padding(.top, 12).padding(.bottom, 20)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { closeContactMenu() }
        }
    }

    private func contactMenuCard(_ chat: ChatSummary, snapshot: ContactMenuSnapshot) -> some View {
        let title = chat.otherUser?.displayName ?? chat.otherUser?.username ?? UnyriL10n.string("Chat")
        return VStack(spacing: 8) {
            Capsule().fill(UnyriColors.secondaryText.opacity(0.5)).frame(width: 34, height: 4).accessibilityHidden(true)
            HStack(spacing: 12) {
                UserAvatar(url: usersRepository.cachedAvatarURL(id: chat.otherUser?.id ?? "", avatarUrl: chat.otherUser?.avatarUrl,
                           avatarVersion: chat.otherUser?.avatarVersion ?? 0), name: title, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline).foregroundStyle(UnyriColors.primaryText)
                    Text("Chat actions").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                }
                Spacer(minLength: 0)
                Button { closeContactMenu() } label: {
                    Image(systemName: "xmark").font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(UnyriColors.secondaryText).frame(width: 44, height: 44)
                        .contentShape(Circle())
                }.buttonStyle(.plain).accessibilityLabel("Close")
            }.padding(.bottom, 4)
            VStack(spacing: 0) {
                contactMenuRow("Open chat", icon: "bubble.left.and.bubble.right", tint: ChatControlPalette.blue) { performMenuAction(.open, snapshot: snapshot) }
                contactMenuRow(pinned.contains(chat.chatId) ? "Menu unpin chat" : "Menu pin chat", icon: pinned.contains(chat.chatId) ? "pin.slash" : "pin", tint: ChatControlPalette.amber) { performMenuAction(.pin, snapshot: snapshot) }.disabled(pinBusy)
                contactMenuRow(chat.isMuted == true ? "Menu sound on" : "Menu sound off", icon: chat.isMuted == true ? "bell" : "bell.slash", tint: ChatControlPalette.mint) { performMenuAction(.mute, snapshot: snapshot) }.disabled(preferenceBusy)
                if chat.chatType == "direct" {
                    contactMenuRow(chatsRepository.archivedChatIDs.contains(chat.chatId) ? "Restore to chats" : "Archive chat",
                        icon: chatsRepository.archivedChatIDs.contains(chat.chatId) ? "tray.and.arrow.up" : "archivebox",
                        tint: ChatControlPalette.blue) { performMenuAction(.archive, snapshot: snapshot) }
                        .disabled(preferenceBusy)
                }
                contactMenuDivider
                if let username = chat.otherUser?.username, !username.isEmpty {
                    contactMenuRow("Menu copy handle", icon: "doc.on.doc", tint: ChatControlPalette.blue) { performMenuAction(.copy, snapshot: snapshot) }
                }
                contactMenuRow("Menu report contact", icon: "flag", tint: ChatControlPalette.amber) { performMenuAction(.report, snapshot: snapshot) }
                contactMenuDivider
                contactMenuRow(chat.isBlocked == true ? "Menu unblock contact" : "Menu block contact", icon: chat.isBlocked == true ? "person.crop.circle.badge.checkmark" : "hand.raised", tint: chat.isBlocked == true ? ChatControlPalette.mint : UnyriColors.danger, destructive: chat.isBlocked != true) { performMenuAction(.block, snapshot: snapshot) }
                if chat.chatType == "direct" {
                    contactMenuRow("Menu delete chat", icon: "trash", tint: UnyriColors.danger, destructive: true) { performMenuAction(.delete, snapshot: snapshot) }.disabled(deletingChat)
                }
            }
        }
        .padding(14)
        .background(colorStyle.surface(LinearGradient(colors: [Color(hex: 0x1C2739), Color(hex: 0x111C2B)], startPoint: .topLeading, endPoint: .bottomTrailing)), in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(colorStyle.material(hex: 0x43516A).opacity(0.65), lineWidth: 0.75))
        .shadow(color: .black.opacity(0.25), radius: 12, y: 5)
        .accessibilityIdentifier("chat-actions-menu")
    }

    private var contactMenuDivider: some View {
        Rectangle().fill(colorStyle.material(hex: 0x31405A).opacity(0.65)).frame(height: 0.5).padding(.vertical, 4)
    }

    private func contactMenuRow(_ title: String, icon: String, tint: Color, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(role: destructive ? .destructive : nil, action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium))
                    .foregroundStyle(destructive ? tint : colorStyle.decorative(tint)).frame(width: 28)
                    .accessibilityHidden(true)
                Text(LocalizedStringKey(title)).font(.subheadline)
                    .foregroundStyle(destructive ? tint : UnyriColors.primaryText).multilineTextAlignment(.leading)
                Spacer(minLength: 4)
            }.padding(.horizontal, 8).padding(.vertical, 10)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func beginSelection() {
        guard !batchBusy, !isSearching, !isShowingChat, !openingGate.isPending else { return }
        contactMenu = nil; selection.removeAll(); batchDeleteIDs.reset(); selecting = true
        UnyriHaptics.selection()
    }

    private var selectionHeader: some View {
        let shown = Set(visibleChats.map(\.chatId))
        let allShownSelected = !shown.isEmpty && shown.isSubset(of: selection)
        return HStack(spacing: 8) {
            Button {
                guard !batchBusy else { return }
                selection.removeAll(); selecting = false; batchDeleteIDs.reset(); UnyriHaptics.selection()
            } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44)
            }.buttonStyle(.plain).accessibilityLabel("Cancel").disabled(batchBusy)
            Text("Selected chats").font(.subheadline)
            Text(verbatim: String(selection.count)).font(.headline).monospacedDigit()
                .accessibilityIdentifier("chats-selection-count")
            Spacer(minLength: 0)
            if batchBusy { ProgressView().controlSize(.small).accessibilityLabel("Updating chats") }
            Button {
                guard !batchBusy else { return }
                if allShownSelected { selection.subtract(shown) } else { selection.formUnion(shown) }
                UnyriHaptics.selection()
            } label: {
                Image(systemName: allShownSelected ? "minus.circle" : "checkmark.circle").font(.system(size: 21)).frame(width: 44, height: 44)
            }.buttonStyle(.plain).accessibilityLabel(LocalizedStringKey(allShownSelected ? "Deselect shown chats" : "Select all shown chats"))
                .accessibilityIdentifier("chats-select-all").disabled(batchBusy || shown.isEmpty)
        }.foregroundStyle(UnyriColors.primaryText).padding(.horizontal, 12)
    }

    private var selectionActions: some View {
        HStack(spacing: 8) {
            selectionButton(selectedAllPinned ? "Unpin chats" : "Pin chats", icon: selectedAllPinned ? "pin.slash" : "pin", identifier: "chats-selection-pin") {
                runSelectionAction(.pin(!selectedAllPinned))
            }
            selectionButton(selectedAllMuted ? "Unmute chats" : "Mute chats", icon: selectedAllMuted ? "bell" : "bell.slash", identifier: "chats-selection-mute") {
                runSelectionAction(.mute(!selectedAllMuted))
            }
            selectionButton(showingArchive ? "Restore to chats" : "Archive chats", icon: showingArchive ? "tray.and.arrow.up" : "archivebox", identifier: "chats-selection-archive") {
                runSelectionAction(.archive(!showingArchive))
            }.disabled(selectionChats.contains { $0.chatType != "direct" })
            selectionButton("Delete chats", icon: "trash", identifier: "chats-selection-delete", destructive: true) {
                guard !batchBusy, !selectionChats.isEmpty, selectionChats.allSatisfy({ $0.chatType == "direct" }) else { return }
                let ids = Set(selectionChats.map(\.chatId))
                batchDeleteRequest = BatchDeleteRequest(owner: menuOwner, requestsByScope: [
                    false: batchDeleteIDs.requests(for: ids, forEveryone: false),
                    true: batchDeleteIDs.requests(for: ids, forEveryone: true)
                ])
                UnyriHaptics.selection()
            }.disabled(selectionChats.contains { $0.chatType != "direct" })
        }.padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 8)
            .background { Rectangle().fill(colorStyle.surface(ChatControlPalette.composer)).opacity(0.98) }
            .overlay(alignment: .top) { Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5) }
    }

    private func selectionButton(_ title: String, icon: String, identifier: String, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium)).accessibilityHidden(true)
                Text(LocalizedStringKey(title)).font(.caption).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, minHeight: 48).padding(.vertical, 4).contentShape(Rectangle())
        }.buttonStyle(.plain).foregroundStyle(destructive ? UnyriColors.danger : UnyriColors.primaryText)
            .disabled(selection.isEmpty || batchBusy || pinBusy || preferenceBusy || deletingChat)
            .accessibilityIdentifier(identifier)
    }

    private func runSelectionAction(_ action: BatchAction, deleteRequest: BatchDeleteRequest? = nil) {
        guard !batchBusy, !pinBusy, !preferenceBusy, !deletingChat, auth.currentUser != nil else { return }
        let owner = menuOwner
        let targets = selectionChats
        let deleteRequests: [String: String]?
        if case .delete(let everyone) = action { deleteRequests = deleteRequest?.requestsByScope[everyone] }
        else { deleteRequests = nil }
        let ids = deleteRequests.map { $0.keys.sorted() } ?? targets.map(\.chatId).sorted()
        guard !ids.isEmpty, deleteRequest == nil || deleteRequest?.owner == owner else { return }
        if case .delete = action {
            guard deleteRequests != nil, ids.allSatisfy({ id in targets.contains { $0.chatId == id && $0.chatType == "direct" } }) else { return }
        }
        batchDeleteRequest = nil; batchBusy = true
        let repository = chatsRepository
        let requestPinOwner = pinOwner
        UnyriHaptics.selection()
        // Leaving the screen does not discard an accepted durable user action.
        // The captured repository and owner guard prevent a later account/server
        // from receiving this task's mutations or UI results.
        Task { @MainActor in
            defer { if owner == menuOwner { batchBusy = false } }
            let result = await ChatListBatchRunner.run(ids: ids, isCurrent: { owner == menuOwner }) { id in
                switch action {
                case .pin(let enabled):
                    let result = try await repository.setPins([id], pinned: enabled)
                    guard owner == menuOwner else { throw CancellationError() }
                    pinned = result
                    UserDefaults.standard.set(Array(result).sorted(), forKey: requestPinOwner)
                case .mute(let enabled):
                    _ = try await repository.updatePreference(chatId: id, action: enabled ? "mute" : "unmute")
                case .archive(let archived):
                    _ = try await repository.setArchived([id], archived: archived)
                case .delete(let everyone):
                    guard let requestID = deleteRequests?[id] else { throw CancellationError() }
                    try await repository.deleteForBoth(id, forEveryone: everyone, requestId: requestID)
                }
            }
            guard owner == menuOwner else { return }
            selection.subtract(result.completed)
            batchDeleteIDs.complete(result.completed)
            let cached = await repository.loadCachedChatsAsync()
            guard owner == menuOwner else { return }
            chats = cached
            selection.formIntersection(Set(visibleChats.map(\.chatId)))
            if selection.isEmpty { selecting = false; batchDeleteIDs.reset() }
            if !result.failed.isEmpty {
                errorMessage = UnyriL10n.string("Some chats could not be updated. They remain selected. Try again.")
                showError = true
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if chats.isEmpty && !didHydrateCache && errorMessage == nil {
            // The persistent cache has not answered yet: this is a cold list,
            // not an empty account. Never flash "No chats yet." while hydrating.
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("Loading chats")
        } else if showingArchive && !isLoading && !chats.contains(where: { $0.chatType != "saved" && chatsRepository.archivedChatIDs.contains($0.chatId) }) {
            VStack(spacing: 12) {
                Image(systemName: "archivebox").font(.system(size: 36, weight: .light)).foregroundStyle(UnyriColors.secondaryText)
                Text("No archived chats").font(.headline).foregroundStyle(UnyriColors.primaryText)
                Text("Archived chats stay here when new messages arrive. Restore them manually whenever you want.")
                    .font(.subheadline).foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
                Button("Back to chats") { leaveArchive() }.buttonStyle(.plain).frame(minHeight: 44)
            }.padding(28).frame(maxWidth: .infinity, maxHeight: .infinity).accessibilityIdentifier("archive-empty")
        } else if let errorMessage, chats.allSatisfy({ $0.chatType == "saved" }) {
            errorView(errorMessage)
        } else if chats.allSatisfy({ $0.chatType == "saved" }) && !isLoading {
            emptyView()
        } else {
            let calls = latestCalls
            let rows = visibleChats
            ScrollView {
                LazyVStack(spacing: 0) {
                    if unreadOnly && rows.isEmpty {
                        Text(verbatim: UnyriL10n.string("No unread messages")).foregroundColor(UnyriColors.secondaryText).padding(32)
                    }
                    ForEach(rows) { chat in
                        HStack(spacing: 4) {
                            if selecting {
                                Image(systemName: selection.contains(chat.chatId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(colorStyle.decorative(UnyriColors.accent)).font(.title2)
                            }
                            ChatRow(chat: chat, latestCall: calls[chat.chatId], compact: pinned.contains(chat.chatId))
                            if pinned.contains(chat.chatId) {
                                Image(systemName: "pin.fill").font(.body.weight(.semibold)).foregroundColor(colorStyle.decorative(Color(hex: 0xF1C36C)))
                                    .accessibilityLabel("Pinned chat")
                            }
                        }.padding(.trailing, 10)
                            .background(pinned.contains(chat.chatId) ? colorStyle.surface(Color(hex: 0x141D2C)).opacity(0.65) : Color.clear, in: RoundedRectangle(cornerRadius: 18))
                            .padding(.horizontal, pinned.contains(chat.chatId) ? 2 : 0)
                            .padding(.vertical, pinned.contains(chat.chatId) ? 4 : 0)
                            .contentShape(Rectangle())
                            .accessibilityIdentifier("chat-list-row-" + chat.chatId)
                            .accessibilityAddTraits(selecting && selection.contains(chat.chatId) ? .isSelected : [])
                            .gesture(LongPressGesture(minimumDuration: 0.45)
                                .onEnded { _ in if selecting { toggleSelection(chat.chatId) } else { presentContactMenu(chat) } }
                                .exclusively(before: TapGesture().onEnded {
                                    if selecting { toggleSelection(chat.chatId) } else { open(chat) }
                                }))
                            .accessibilityAction(named: Text("Chat options")) { presentContactMenu(chat) }
                            .onAppear {
                                visiblePresenceChats.insert(chat.chatId)
                                if chat.id == rows.last?.id, nextOffset != nil, !isLoading {
                                    Task { await load(reset: false) }
                                }
                            }
                            .onDisappear { visiblePresenceChats.remove(chat.chatId) }
                        if chat.id != rows.last?.id && !pinned.contains(chat.chatId) {
                            Rectangle().fill(colorStyle.decorative(Color(hex: 0x2A3548)).opacity(colorStyle.isMonochrome ? 0.10 : 0.55)).frame(height: 0.5)
                                .padding(.leading, selecting ? 106 : 76).padding(.trailing, 12)
                        }
                    }
                    if unreadOnly, nextOffset != nil {
                        Button("Load older chats") { Task { await load(reset: false) } }.disabled(isLoading)
                    }
                    if isLoading && (isLoadingMore || chats.isEmpty) {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                    }
                }
                .padding(.vertical, 4)
                .padding(.horizontal, 12)
            }
            .refreshable { await load(reset: true) }
        }
    }

    private var unreadChatCount: Int {
        // Match Android: count known non-Saved chat summaries with unread
        // messages, independently of the currently selected All/Unread filter.
        chats.reduce(0) { count, chat in
            count + (chat.chatType != "saved" && chatsRepository.archivedChatIDs.contains(chat.chatId) == showingArchive && (chatsRepository.unreadCounts[chat.chatId] ?? 0) > 0 ? 1 : 0)
        }
    }


    private var compactHeaderActions: some View {
        HStack(spacing: 0) {
            Menu {
                Button {
                    guard !selecting else { return }
                    UnyriHaptics.selection(); unreadOnly = false
                } label: { Label("All", systemImage: unreadOnly ? "bubble.left.and.bubble.right" : "checkmark") }
                    .buttonStyle(.plain).disabled(selecting)
                Button {
                    guard !selecting else { return }
                    UnyriHaptics.selection(); unreadOnly = true
                } label: { Label("Unread", systemImage: unreadOnly ? "checkmark" : "line.3.horizontal.decrease") }
                    .buttonStyle(.plain).disabled(selecting)
                Divider()
                Button { beginSelection() } label: { Label("Select chats", systemImage: "checkmark.circle") }
                    .disabled(selecting || visibleChats.isEmpty || batchBusy)
                    .accessibilityIdentifier("chats-filter-select")
            } label: {
                Image(systemName: "slider.horizontal.3").font(.system(size: 21, weight: .regular)).foregroundStyle(Color(hex: 0xF2F3F7))
                    .frame(width: 21, height: 21)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(UnyriQuietButtonStyle())
            .accessibilityLabel("Chat filters")
            .accessibilityIdentifier("chats-filter")
            .accessibilityValue(UnyriL10n.string(unreadOnly ? "Unread" : "All") + ", " + String(unreadChatCount))
        }
        .frame(height: 44)
    }

    private func headerActionIcon(_ symbol: String, tint: Color) -> some View {
        Image(systemName: symbol).font(.system(size: 21, weight: .semibold)).foregroundStyle(tint)
            .frame(width: 44, height: 44).contentShape(Rectangle())
    }


    private func handleSavedRequest(_ request: UUID?) {
        UnyriSavedRouteQA.record(4, request == nil ? 0 : 1)
        guard let request, request != handledSavedRequest, request != rejectedSavedRequest else { return }
        // A request observed while another chat is presented must never become
        // an unexpected navigation when that chat is later dismissed.
        guard !isShowingChat, auth.currentUser != nil else {
            rejectedSavedRequest = request
            return
        }
        // This explicit latest user intent supersedes an uncommitted lookup.
        // Old completion handlers can release only their own obsolete ticket.
        openingTask?.cancel()
        openingTask = nil
        openingGate.cancel()
        if openSavedMessages() { handledSavedRequest = request; UnyriSavedRouteQA.record(5, 1) }
        else { UnyriSavedRouteQA.record(5, 0) }
    }

    @discardableResult
    private func openSavedMessages() -> Bool {
        guard !isShowingChat, auth.currentUser != nil, let ticket = openingGate.begin() else { return false }
        let owner = menuOwner
        openingTask = Task { @MainActor in
            UnyriSavedRouteQA.record(6, Task.isCancelled ? 1 : 0)
            guard !Task.isCancelled, owner == menuOwner else { UnyriSavedRouteQA.record(11, 4); _ = openingGate.finish(ticket); return }
            do {
                let chat = try await chatsRepository.getOrCreateSavedChat()
                guard openingGate.finish(ticket), !Task.isCancelled, owner == menuOwner else { UnyriSavedRouteQA.record(11, 5); return }
                openingTask = nil
                open(chat)
            } catch {
                guard openingGate.finish(ticket), !Task.isCancelled, owner == menuOwner else { UnyriSavedRouteQA.record(11, 6); return }
                openingTask = nil
                UnyriSavedRouteQA.record(13)
                errorMessage = error.localizedDescription
                showError = true
            }
        }
        return true
    }

    private func emptyView() -> some View {
        VStack(spacing: 12) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.largeTitle).foregroundColor(UnyriColors.accentSoft)
                .accessibilityHidden(true)
            Text(verbatim: UnyriL10n.string("No chats yet."))
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text(verbatim: UnyriL10n.string("Find someone in Search to start a chat."))
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 12) {
            UnyriNotice(message: message)
            Button("Retry") {
                Task { await load(reset: true) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }

    private func leaveArchive() {
        guard !batchBusy else { return }
        UnyriHaptics.selection(); showingArchive = false
        selection.removeAll(); selecting = false; batchDeleteRequest = nil; batchDeleteIDs.reset()
        contactMenu = nil; unreadOnly = false
    }

    private func changeArchive(_ chat: ChatSummary) {
        guard !preferenceBusy, chat.chatType == "direct" else { return }
        let owner = menuOwner
        let repository = chatsRepository
        let archived = !repository.archivedChatIDs.contains(chat.chatId)
        preferenceBusy = true
        Task { @MainActor in
            defer { if owner == menuOwner { preferenceBusy = false } }
            guard owner == menuOwner else { return }
            do {
                _ = try await repository.setArchived([chat.chatId], archived: archived)
                guard owner == menuOwner else { return }
                selection.formIntersection(Set(visibleChats.map(\.chatId)))
            } catch is CancellationError { }
              catch { guard owner == menuOwner else { return }; errorMessage = error.localizedDescription; showError = true }
        }
    }

    private func changeNotifications(_ chat: ChatSummary) {
        guard !preferenceBusy else { return }
        let owner = menuOwner
        preferenceBusy = true
        Task { @MainActor in
            defer { if owner == menuOwner { preferenceBusy = false } }
            guard owner == menuOwner else { return }
            do {
                _ = try await chatsRepository.updatePreference(chatId: chat.chatId, action: chat.isMuted == true ? "unmute" : "mute")
                guard owner == menuOwner else { return }
                let cached = await chatsRepository.loadCachedChatsAsync()
                guard owner == menuOwner, !Task.isCancelled else { return }
                chats = cached
            } catch is CancellationError { }
              catch { guard owner == menuOwner else { return }; errorMessage = error.localizedDescription; showError = true }
        }
    }

    private func open(_ chat: ChatSummary, safetyAction: String? = nil, messageId: String? = nil) {
        guard !openingGate.isPending, !isShowingChat else { return }
        UnyriSavedRouteQA.record(12, chat.chatType == "saved" ? 1 : 0)
        openedMessageId = messageId
        openedSafetyAction = safetyAction
        openedChat = chat
        setChatPresented(true)
    }

    private var chatNavigationTransaction: Transaction {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        return transaction
    }

    private func setChatPresented(_ presented: Bool) {
        withTransaction(chatNavigationTransaction) {
            isShowingChat = presented
        }
    }

    private func load(reset: Bool) async {
        guard !isLoading else { return }
        isLoadingMore = !reset
        isLoading = true
        defer { isLoading = false; isLoadingMore = false }
        if reset { errorMessage = nil }
        let startedAt = Date()
        let offset = reset ? Int64(0) : (nextOffset ?? 0)
        // Archive membership is an input of the list projection, so a full
        // refresh must have it settled before the page is merged (otherwise a
        // stale snapshot briefly lets archived chats into "All chats"). It is
        // independent of the page request, though, so it overlaps that request
        // instead of adding a serial round-trip in front of it.
        let archiveRefresh: Task<Void, Never>? = reset ? Task { _ = try? await chatsRepository.archives() } : nil
        defer { archiveRefresh?.cancel() }
        do {
            let response: ChatListResponse
            do {
                response = try await chatsRepository.listChats(offset: offset)
            } catch APIError.network(_) {
                // A refresh can overlap socket/network recovery. Retry once,
                // but only a successful HTTP response clears the error.
                guard reset, !Task.isCancelled, scenePhase == .active, realtime.state == .connected else { throw APIError.network("refresh unavailable") }
                try await Task.sleep(nanoseconds: 750_000_000)
                response = try await chatsRepository.listChats(offset: offset)
            }
            // Apply the concurrent archive refresh before the merge so the
            // cached projection never publishes under stale membership.
            await archiveRefresh?.value
            // Merge the page into the persistent cache, then re-read it as
            // the single source of truth. Never replaces: older cached pages
            // stay visible across refreshes and restarts.
            try Task.checkCancellation()
            chatsRepository.persistChats(response.items, refreshUnread: false)
            nextOffset = response.nextOffset
            errorMessage = nil
        } catch is CancellationError {
            return
        } catch let error as URLError where error.code == .cancelled {
            return
        } catch {
            guard !Task.isCancelled else { return }
            if let success = chatsRepository.lastListSuccess, success > startedAt { return }
            errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }
}

private struct ChatRow: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var usersRepository: UsersRepository
    @EnvironmentObject private var chatsRepository: ChatsRepository
    let chat: ChatSummary
    var latestCall: CallDTO? = nil
    var compact = false
    @EnvironmentObject private var auth: AuthRepository

    @State private var avatarURL: URL?
    @State private var expiredActivity: Date?
    private var peerOnline: Bool {
        guard chat.isBlocked != true, let peer = chat.otherUser else { return false }
        return realtime.presenceObservation(for: peer.id)?.item.online == true
    }

    private func expireActivity() async {
        guard let item = realtime.activity[chat.chatId], item.kind != "idle" else { return }
        let remaining = max(0, 8 - Date().timeIntervalSince(item.at))
        do { try await Task.sleep(nanoseconds: UInt64(min(8, remaining) * 1_000_000_000)) }
        catch { return }
        guard !Task.isCancelled, realtime.activity[chat.chatId]?.at == item.at else { return }
        expiredActivity = item.at
    }

    var body: some View {
        HStack(spacing: 12) {
            UserAvatar(url: avatarURL ?? usersRepository.cachedAvatarURL(id: chat.otherUser?.id ?? "", avatarUrl: chat.otherUser?.avatarUrl, avatarVersion: chat.otherUser?.avatarVersion ?? 0), name: title, size: compact ? 48 : 52)
                .overlay(alignment: .bottomTrailing) {
                    if peerOnline {
                        Circle().fill(ChatControlPalette.mint).frame(width: 10, height: 10)
                            .overlay(Circle().stroke(UnyriColors.background, lineWidth: 2))
                            .accessibilityLabel("online")
                            .accessibilityIdentifier("chat-list-online-" + chat.chatId)
                    }
                }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                Text(title)
                    .font(.body)
                    .fontWeight(.semibold)
                    .foregroundColor(UnyriColors.primaryText)
                    .lineLimit(1)
                if chat.isMuted == true { Image(systemName: "bell.slash").font(.caption).foregroundStyle(UnyriColors.secondaryText).accessibilityLabel("Mute message notifications") }
                if peerOnline {
                    Text(verbatim: UnyriL10n.string("online")).font(.caption)
                        .foregroundStyle(ChatControlPalette.mint).lineLimit(1).fixedSize(horizontal: true, vertical: false)
                }
                }
                    if let item = realtime.activity[chat.chatId], expiredActivity != item.at,
                       Date().timeIntervalSince(item.at) < 8, item.kind != "idle" {
                        Text(UnyriL10n.string(item.kind == "typing" ? "Typing…" : item.kind == "recording" ? "Recording a voice message…" : "Sending attachment…"))
                            .font(.footnote).foregroundColor(colorStyle.decorative(UnyriColors.accentSoft)).lineLimit(1)
                    } else { previewLine }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 7) {
                if let timestamp = timestampText {
                    Text(timestamp)
                        .font(UnyriTypography.timestamp).lineLimit(1).fixedSize(horizontal: true, vertical: false)
                        .foregroundColor(UnyriColors.mutedText)
                }
                let unread = chatsRepository.unreadCounts[chat.chatId] ?? 0
                if unread > 0 {
                    Text(unread > 99 ? "99+" : "\(unread)")
                        .font(.caption.weight(.semibold)).foregroundColor(colorStyle.onColorfulButton)
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(Capsule().fill(colorStyle.decorative(UnyriColors.accent)))
                        .accessibilityLabel("\(unread) unread messages")
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, compact ? 8 : 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .task(id: realtime.activity[chat.chatId]?.at) { await expireActivity() }
        .task(id: avatarCacheKey) {
            avatarURL = await usersRepository.avatarURL(
                id: chat.otherUser?.id ?? chat.chatId,
                avatarUrl: chat.otherUser?.avatarUrl,
                avatarVersion: chat.otherUser?.avatarVersion ?? 0
            )
        }
    }

    /// Second line: the LOCAL last-message preview when available, otherwise
    /// the peer's @username (identity fallback). Never raw JSON / keys / UUIDs.
    @ViewBuilder
    private var previewLine: some View {
        if let latestCall, latestCall.createdAt > (chat.lastMessageAt.flatMap(ISO8601Cocoa.parse) ?? .distantPast) {
            let owner = auth.currentUser.flatMap { UUID(uuidString: $0.id) }
            Label(UnyriL10n.string(latestCall.historyLabel(owner: owner)) + " · " + UnyriL10n.string(latestCall.mediaKind == .video ? "Video call" : "Audio call"), systemImage: latestCall.mediaKind == .video ? "video" : "phone")
                .font(.footnote).foregroundColor(latestCall.isMissedIncoming(owner: owner) ? UnyriColors.danger : UnyriColors.secondaryText).lineLimit(1)
        } else { messagePreviewLine }
    }

    @ViewBuilder private var messagePreviewLine: some View {
        switch chat.lastPreview {
        case .some(.text(let text)):
            Text(UnyriMessageTextPresentation.display(text, inline: true)).font(.footnote).foregroundColor(UnyriColors.secondaryText).lineLimit(1)
        case .some(.video):
            mediaPreview("Video", icon: "video", tint: ChatControlPalette.blue)
        case .some(.photo):
            mediaPreview("Photo", icon: "photo", tint: ChatControlPalette.mint)
        case .some(.voice):
            mediaPreview("Voice message", icon: "waveform", tint: UnyriColors.accentSoft)
        case .some(.file(let filename)):
            HStack(spacing: 5) { Image(systemName: "doc").foregroundStyle(colorStyle.decorative(ChatControlPalette.amber)); Text(UnyriMessageTextPresentation.display(filename ?? UnyriL10n.string("File"), inline: true)).foregroundStyle(UnyriColors.secondaryText).lineLimit(1) }.font(.footnote)
        case .some(.deleted):
            Text(verbatim: UnyriL10n.string("Message deleted")).font(.footnote).foregroundColor(UnyriColors.secondaryText)
        case .none:
            Text(verbatim: UnyriL10n.string(chat.lastMessageAt == nil ? "No messages yet" : "Message unavailable on this device"))
                .font(.footnote).foregroundColor(UnyriColors.secondaryText).lineLimit(1)
        }
    }

    private func mediaPreview(_ title: String, icon: String, tint: Color) -> some View {
        HStack(spacing: 5) { Image(systemName: icon).foregroundStyle(colorStyle.decorative(tint)); Text(verbatim: UnyriL10n.string(title)).foregroundStyle(UnyriColors.secondaryText).lineLimit(1) }.font(.footnote)
    }

    private var title: String {
        if chat.chatType == "saved" { return UnyriL10n.string("Saved Messages", comment: "") }
        return chat.otherUser?.displayName ?? chat.otherUser?.username ?? "Chat"
    }

    private var avatarCacheKey: String {
        "\(chat.otherUser?.id ?? chat.chatId):\(chat.otherUser?.avatarVersion ?? 0)"
    }

    private var timestampText: String? {
        let activity = ChatCallActivity.latestDate(message: chat.lastMessageAt.flatMap(ISO8601Cocoa.parse), call: latestCall)
        return activity == .distantPast ? RelativeTime.label(forRFC3339: chat.createdAt) : RelativeTime.label(for: activity)
    }
}

// MARK: - Sequential durable chat-list actions
// Success means the existing repository accepted its durable local operation,
// not that an offline server has already acknowledged it.
struct ChatListBatchResult {
    let completed: Set<String>
    let failed: Set<String>
}

@MainActor
enum ChatListBatchRunner {
    static func run(ids: [String], isCurrent: () -> Bool,
                    operation: (String) async throws -> Void) async -> ChatListBatchResult {
        let requested = Set(ids)
        var completed = Set<String>()
        var visited = Set<String>()
        for id in ids where !visited.contains(id) {
            guard isCurrent(), !Task.isCancelled else { break }
            visited.insert(id)
            do {
                try await operation(id)
                guard isCurrent(), !Task.isCancelled else { break }
                completed.insert(id)
            } catch is CancellationError {
                if !isCurrent() || Task.isCancelled { break }
            } catch { continue }
        }
        return ChatListBatchResult(completed: completed, failed: requested.subtracting(completed))
    }
}

/// Keep the same bounded-clear identity when retrying an ambiguous local failure.
/// Different deletion scopes must never reuse one request ID.
struct ChatListDeleteRequestIDs {
    private var values: [Bool: [String: String]] = [:]

    mutating func requests(for chatIDs: Set<String>, forEveryone: Bool,
                           makeID: () -> String = { UUID().uuidString }) -> [String: String] {
        var scope = values[forEveryone] ?? [:]
        for id in chatIDs.sorted() where scope[id] == nil { scope[id] = makeID() }
        values[forEveryone] = scope
        return scope.filter { chatIDs.contains($0.key) }
    }

    mutating func complete(_ chatIDs: Set<String>) {
        for scope in [false, true] {
            for id in chatIDs { values[scope]?.removeValue(forKey: id) }
        }
    }

    mutating func reset() { values.removeAll() }
}

/// Archive membership only partitions existing visible local summaries. It
/// never provides a reason to discover or hydrate a server history entry.
enum ChatListArchivePolicy {
    static func includes(chatID: String, type: String, archived: Set<String>, showingArchive: Bool) -> Bool {
        guard type != "saved" else { return false }
        let isArchived = type == "direct" && archived.contains(chatID)
        return isArchived == showingArchive
    }
}
