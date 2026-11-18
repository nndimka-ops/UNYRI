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
    @State private var showingContacts = false
    @State private var searchSession = UUID()
    @State private var searchScroll = ChatSearchScrollMemory()
    @State private var openedMessageId: String?
    private func onSearch(contacts: Bool = false) {
        openingGate.cancel(); openingTask?.cancel(); openingTask = nil
        guard !batchBusy else { return }
        selecting = false; selection.removeAll(); batchDeleteIDs.reset()
        if contacts { isSearching = false; showingContacts = true }
        else {
            if !isSearching { searchScroll.capture() }
            searchSession = UUID(); isSearching = true
        }
    }
    /// A scanned profile QR (unyri://user/<username>): open or start that chat.
    private func openUserLink(_ name: String) async {
        guard name.lowercased() != auth.currentUser?.username.lowercased() else { return }
        do {
            let found = try await usersRepository.search(query: name)
            guard let user = found.items.first(where: { $0.username.lowercased() == name.lowercased() }) else {
                userLinkError = true; return
            }
            let chat = try await chatsRepository.createOrOpenDirectChat(otherUserId: user.id)
            open(chat)
        } catch { userLinkError = true }
    }

    private func handleSearchRequest(_ request: SearchNavigationRequest?) {
        guard let request, request.id != handledSearchRequest else { return }
        handledSearchRequest = request.id
        // Never defer a root request until after a detail or modal disappears.
        guard !isShowingChat, contactMenu == nil else { return }
        if request.destination == .chats {
            openingGate.cancel(); openingTask?.cancel(); openingTask = nil
            isSearching = false; showingContacts = false
        } else { onSearch(contacts: request.destination == .contacts) }
    }
    @EnvironmentObject private var callsHistory: CallsRepository
    @EnvironmentObject private var callController: CallController
    @EnvironmentObject private var realtime: RealtimeClient
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @State private var presenceOwner = "chat-list:" + UUID().uuidString
    @State private var visiblePresenceChats = Set<String>()
    @State private var listIsVisible = false
    private struct PresenceRequest: Equatable {
        let account: String
        let active: Bool
        let ids: [String]
    }
    private var presenceRequest: PresenceRequest {
        let active = listIsVisible && scenePhase == .active && !isShowingChat && !isSearching && !showingContacts
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
    // Row -> chat motion (zoom on iOS 18+, a soft settle or fade otherwise).
    @Namespace private var chatZoom
    @State private var openStyle: ChatOpenStyle = .instant
    /// Set for one zoom push only: the chat header is rebuilt after selection or in-chat
    /// search, and its avatar must not land a second time then.
    @State private var arrivalToken: UUID?
    // Live list motion: a non-observed clock (writes never re-render) and the one
    // row that plays an effect for the latest event.
    @State private var motionClock = ChatListMotionClock()
    @State private var rowSignal: ChatRowSignal?
    // Swipe actions: at most one open tray; rows leaving after a swipe archive.
    @State private var openSwipeID: String?
    @State private var leavingIDs = Set<String>()
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
            connected: realtime.state == .connected, visible: !isShowingChat && !isSearching && !showingContacts,
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
        // This 100 ms window exists only to coalesce a burst of triggers that
        // often arrive together (scene activation, socket connection, management
        // revision): `.task(id: refreshContext)` cancels the previous invocation,
        // so only the last trigger of the burst pays for the refresh. The first
        // paint must not pay it — when `chats` is still empty there are no cached
        // rows to protect and a duplicate refresh is already suppressed by the
        // `isLoading` guard inside `load(reset:)`.
        if !chats.isEmpty {
            do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
        }
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
                // A pin moves the row to or from the pinned group: one calm move, no lift.
                withAnimation(reduceMotion ? nil : ChatListMotion.move) { pinned = result }
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
    @State private var userLinkError = false
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
        let leaving: Set<String>
    }
    @State private var listPresentationCache = ChatPresentationCache<ListPresentationRevision, [ChatSummary]>()
    /// The render's cache key for a given `chats` value; a hydration seeds the cache under it.
    private func listPresentationKey(_ rows: [ChatSummary]) -> ListPresentationRevision {
        ListPresentationRevision(chats: rows, pinned: pinned, unreadOnly: unreadOnly,
            showingArchive: showingArchive, archived: chatsRepository.archivedChatIDs, unread: unreadOnly ? chatsRepository.unreadCounts : [:],
            calls: CallIndexRevision(owner: menuOwner, calls: callsHistory.historyRevision), leaving: leavingIDs)
    }
    private var visibleChats: [ChatSummary] {
        listPresentationCache.value(for: listPresentationKey(chats)) {
            Self.ordered(chats, pinned: pinned, archived: chatsRepository.archivedChatIDs, showingArchive: showingArchive,
                unreadOnly: unreadOnly, unread: chatsRepository.unreadCounts, calls: latestCalls, leaving: leavingIDs).rows
        }
    }

    /// The list's filter and order: pinned first, then the latest message or call.
    /// Shared by the rendered list and the live-motion decision so both see one order;
    /// `activity` is each chat's sort date, so callers never parse the timestamps again.
    private static func ordered(_ chats: [ChatSummary], pinned: Set<String>, archived: Set<String>, showingArchive: Bool,
                                unreadOnly: Bool, unread: [String: Int], calls: [String: CallDTO],
                                leaving: Set<String>) -> (rows: [ChatSummary], activity: [String: Date]) {
        func activity(_ chat: ChatSummary) -> Date {
            ChatCallActivity.latestDate(message: chat.lastMessageAt.flatMap(Self.messageDate), call: calls[chat.chatId])
        }
        let dates = Dictionary(chats.map { ($0.chatId, activity($0)) }, uniquingKeysWith: { first, _ in first })
        let rows = chats.filter { !leaving.contains($0.chatId) && ChatListArchivePolicy.includes(chatID: $0.chatId, type: $0.chatType,
            archived: archived, showingArchive: showingArchive) &&
            (!unreadOnly || (unread[$0.chatId] ?? 0) > 0) }
            .sorted {
                if pinned.contains($0.chatId) != pinned.contains($1.chatId) { return pinned.contains($0.chatId) }
                let left = dates[$0.chatId] ?? .distantPast, right = dates[$1.chatId] ?? .distantPast
                return left == right ? $0.chatId > $1.chatId : left > right
            }
        return (rows, dates)
    }

    // Server timestamps (chrono, microseconds) usually miss both formatters and take the
    // slow cleanup path; a busy list re-sorts every 150 ms, so each string parses once.
    private static var messageDates: [String: Date] = [:]
    private static func messageDate(_ value: String) -> Date? {
        if let cached = messageDates[value] { return cached }
        guard let date = ISO8601Cocoa.parse(value) else { return nil }
        if messageDates.count >= 2048 { messageDates.removeAll(keepingCapacity: true) }
        messageDates[value] = date
        return date
    }

    /// Live list motion may play only while the list is the thing the user is looking at.
    private var motionLive: Bool {
        listIsVisible && scenePhase == .active && !isShowingChat && !isSearching && !showingContacts && contactMenu == nil && !selecting && openSwipeID == nil
    }
    private var motionFilterKey: String { "\(unreadOnly)|\(showingArchive)|\(pinned.sorted())" }
    private var swipeEnabled: Bool { !selecting && !batchBusy && !isSearching && !showingContacts && contactMenu == nil && !isShowingChat }

    /// A cache hydration: decides whether the change is one live event worth a move,
    /// lift or sweep, or a catch-up that just lands. Other `chats` writers never animate.
    private func applyHydration(_ cached: [ChatSummary]) {
        let now = CACurrentMediaTime()
        let calls = latestCalls
        let unread = chatsRepository.unreadCounts
        let filterKey = motionFilterKey
        let order = Self.ordered(cached, pinned: pinned, archived: chatsRepository.archivedChatIDs, showingArchive: showingArchive,
                                 unreadOnly: unreadOnly, unread: unread, calls: calls, leaving: leavingIDs)
        // The render after `chats = cached` reads this order from the cache instead of sorting again.
        _ = listPresentationCache.value(for: listPresentationKey(cached)) { order.rows }
        let newRows = order.rows.map {
            ChatListLiftPolicy.Row(id: $0.chatId, activity: order.activity[$0.chatId] ?? .distantPast,
                unread: unread[$0.chatId] ?? 0, muted: $0.isMuted == true)
        }
        let clock = motionClock
        let old = clock.lastFilterKey == filterKey ? clock.lastRows : []
        let decision = ChatListLiftPolicy.decide(old: old, new: newRows, live: clock.isLive(now: now),
                                                 sweepAllowed: { clock.allowSweep($0, now: now) })
        clock.lastRows = newRows
        clock.lastFilterKey = filterKey
        let signal = ChatRowSignal.from(decision)
        if decision.animateOrder && !reduceMotion {
            withAnimation(ChatListMotion.move) {
                chats = cached
                if let signal { rowSignal = signal }
            }
        } else {
            chats = cached
            if let signal { rowSignal = signal }
        }
        didHydrateCache = true
    }

    private func swipeActions(_ chat: ChatSummary) -> [ChatSwipeAction] {
        let isPinned = pinned.contains(chat.chatId)
        let muted = chat.isMuted == true
        var actions = [
            ChatSwipeAction(kind: .pin, title: isPinned ? "Menu unpin chat" : "Menu pin chat",
                icon: isPinned ? "pin.slash" : "pin", result: isPinned ? "pin.slash.fill" : "pin.fill", tint: ChatControlPalette.amber),
            ChatSwipeAction(kind: .mute, title: muted ? "Menu sound on" : "Menu sound off",
                icon: muted ? "bell" : "bell.slash", result: muted ? "bell.fill" : "bell.slash.fill", tint: ChatControlPalette.mint),
        ]
        if chat.chatType == "direct" {
            let archived = chatsRepository.archivedChatIDs.contains(chat.chatId)
            actions.append(ChatSwipeAction(kind: .archive, title: archived ? "Swipe restore" : "Archive chat",
                icon: archived ? "tray.and.arrow.up" : "archivebox",
                result: archived ? "tray.and.arrow.up.fill" : "archivebox.fill", tint: ChatControlPalette.blue))
        }
        return actions
    }

    /// One path for a swipe commit, a tray tap and the VoiceOver actions.
    private func commitRowAction(_ kind: ChatSwipeAction.Kind, _ snapshot: ChatSummary) {
        guard auth.currentUser != nil, !batchBusy else { return }
        let chat = chats.first(where: { $0.chatId == snapshot.chatId }) ?? snapshot
        switch kind {
        case .pin:
            guard !pinBusy else { return }
            setPinned([chat.chatId], enabled: !pinned.contains(chat.chatId))
        case .mute:
            guard !preferenceBusy else { return }
            changeNotifications(chat)
        case .archive:
            guard !preferenceBusy, chat.chatType == "direct" else { return }
            let id = chat.chatId
            // Optimistic: the row leaves now and comes back if the change fails.
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.26)) { _ = leavingIDs.insert(id) }
            changeArchive(chat) { accepted in
                if accepted { leavingIDs.remove(id) }
                else { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { _ = leavingIDs.remove(id) } }
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
            .allowsHitTesting(!isSearching && !showingContacts)
            .accessibilityHidden(isSearching)
            .ignoresSafeArea(.keyboard, edges: .bottom)
            if isSearching {
                InlineChatSearchView(chats: chatsRepository, messages: messagesRepository, users: usersRepository,
                    connected: network.connected && realtime.state == .connected, active: listIsVisible && scenePhase == .active && !isShowingChat,
                    onClose: { isSearching = false }, onOpenChat: { chat, messageId in
                    open(chat, messageId: messageId)
                }).equatable().id(searchSession)
            }
            }
                .unyriScreenBackground()
                .onAppear { listIsVisible = true; chatsRepository.isChatListVisible = true; openReminder(); withAnimation(reduceMotion ? nil : .easeOut(duration: 0.7)) { titleAppeared = true }
                    motionClock.stamp(live: motionLive) }
                .onChange(of: motionLive) { live in motionClock.stamp(live: live) }
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
                    if !isSearching, let callIsland {
                        ToolbarItem(placement: .principal) { CallIslandTitle(presentation: callIsland) }
                    } else if !isSearching && showingArchive {
                        ToolbarItem(placement: .principal) { Text("Archive").font(.headline) }
                    } else if !isSearching {
                        ToolbarItem(placement: .principal) {
                            UnyriConnectionHeader(realtime: realtime, routeAvailable: network.connected,
                                loading: (isLoading || !didHydrateCache) && chats.isEmpty, error: errorMessage != nil, onRetry: { Task { await realtime.retryConnectionSync(); await load(reset: true) } },
                                visible: !isShowingChat && contactMenu == nil, contentReady: !chats.isEmpty, blocked: network.blocked)
                                .modifier(UnyriWindowCentered())
                        }
                    }
                    if !isSearching && showingArchive {
                        UnyriBackToolbarItem(action: { leaveArchive() }, label: "Back to chats", identifier: "archive-back")
                    } else if !isSearching {
                        ToolbarItem(placement: .navigationBarLeading) { UnyriMenuButton() }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) { if !isSearching && !showingContacts { compactHeaderActions } }
                }
                .navigationBarTitleDisplayMode(.inline)
                .alert("User not found", isPresented: $userLinkError) { Button("OK", role: .cancel) { } }
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
                // Only a zoom open animates the push and its pop; every other path stays an instant cut.
                .navigationDestination(isPresented: $showingContacts) {
                    ContactsRoute(users: usersRepository, chats: chatsRepository, messages: messagesRepository,
                                  realtime: realtime, calls: callController,
                                  displayName: auth.currentUser?.displayName ?? "UNYRI", username: auth.currentUser?.username ?? "")
                        .equatable()
                }
                .navigationDestination(isPresented: $isShowingChat.transaction(openStyle == .zoom ? Transaction() : chatNavigationTransaction)) {
                    if let openedChat {
                        ChatRoute(chat: openedChat, safety: openedSafetyAction, messageId: openedMessageId)
                            .equatable()
                            .id(openedChat.chatId)
                            .modifier(ChatOpenDestination(style: openStyle, sourceID: openedChat.chatId, namespace: chatZoom))
                            .environment(\.unyriChatArrival, openStyle == .zoom && arrivalToken != nil)
                    }
                }
                .onChange(of: isShowingChat) { shown in
                    chatsRepository.navigationChatId = shown ? openedChat?.chatId : nil
                    if !shown { chatsRepository.notificationHeaderHeight = 44 }
                    openSwipeID = nil
                    let instantClose = motionClock.instantClose
                    motionClock.instantClose = false
                    if !shown {
                        // Hold list hydration while the chat shrinks back into its row.
                        let now = CACurrentMediaTime()
                        motionClock.returnedAt = now
                        // Only a user close of a zoom open animates its pop; opens wait for it to end.
                        motionClock.popEndsAt = openStyle == .zoom && !instantClose ? now + ChatListMotion.returnHold : -.infinity
                        if openStyle == .settle || openStyle == .fade, let id = openedChat?.chatId {
                            // Without a zoom, a short glow shows which row the user came back to.
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                                rowSignal = ChatRowSignal(chatId: id, kind: .returnGlow)
                            }
                        }
                    }
                    // A route to another chat closed the current one first; open the target
                    // once the pop has been applied.
                    if !shown, chatsRepository.openChatRequest != nil {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { routeOpenRequest() }
                    }
                }
                .onChange(of: chatsRepository.chatsTabSelected) { visible in
                    guard visible, chatsRepository.openChatRequest != nil else { return }
                    // After the tab switch has been rendered, never in the same update.
                    DispatchQueue.main.async { routeOpenRequest() }
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
                    // @Published emits before the value is stored: route on the next turn.
                    guard chatId != nil else { return }
                    DispatchQueue.main.async { routeOpenRequest() }
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
                    // Just back from a chat: the row must stay put while the chat shrinks into it.
                    let hold = motionClock.returnHold(now: CACurrentMediaTime())
                    if hold > 0 {
                        do { try await Task.sleep(nanoseconds: UInt64(hold * 1_000_000_000)) } catch { return }
                    }
                    let cached = await chatsRepository.loadCachedChatsAsync()
                    guard !Task.isCancelled else { return }
                    applyHydration(cached)
                }
        }
        .onChange(of: savedRequest) { request in handleSavedRequest(request) }
        .onChange(of: showingArchive) { _ in
            openSwipeID = nil; leavingIDs.removeAll()
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
        .unyriBlocksRootDrawer(isSearching || isShowingChat || contactMenu != nil || selecting || deleteTarget != nil || batchDeleteRequest != nil || showError || openSwipeID != nil)
        .toolbar(isSearching && !isShowingChat ? .hidden : .visible, for: .navigationBar)
        .accessibilityHidden(contactMenu != nil)
        .overlay { contactMenuOverlay }
        .onChange(of: menuOwner) { _ in
            searchScroll.clear()
            showingContacts = false; isSearching = false
            visiblePresenceChats.removeAll()
            realtime.unsubscribePresence(owner: presenceOwner)
            contactMenu = nil; deleteTarget = nil; deleteOwner = nil
            deletingChat = false; preferenceBusy = false; pinBusy = false
            batchBusy = false; batchDeleteRequest = nil; batchDeleteIDs.reset(); selection.removeAll(); selecting = false
            // A new account/server/session must not keep showing the previous
            // owner's rows, and its cold list is "loading", not "empty".
            chats = []; nextOffset = nil; didHydrateCache = false; errorMessage = nil
            // The next owner's first hydration is a cold load, never a live event.
            motionClock.reset(); rowSignal = nil; openSwipeID = nil; leavingIDs.removeAll()
            UnyriSavedRouteQA.record(11, 3)
            openingGate.cancel(); openingTask?.cancel(); openingTask = nil
        }
        .onChange(of: isShowingChat) { if $0 { contactMenu = nil } }
            .onChange(of: isSearching) {
                if $0 { contactMenu = nil; openSwipeID = nil }
                else { searchScroll.restore() }
            }
            .onChange(of: selecting) { _ in openSwipeID = nil }
            .onChange(of: searchRequest) { request in handleSearchRequest(request) }
            .onReceive(NotificationCenter.default.publisher(for: .unyriOpenUserLink)) { note in
                guard let name = note.object as? String else { return }
                Task { await openUserLink(name) }
            }
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
        guard !selecting, !batchBusy, !isShowingChat, !isSearching && !showingContacts, !openingGate.isPending, auth.currentUser != nil else { return }
        openSwipeID = nil
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
        let title = chat.otherUser?.visibleName ?? chat.otherUser?.username ?? UnyriL10n.string("Chat")
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
        // Same glass as the chat menu and the attach sheet (no navy gradient).
        .modifier(UnyriAttachGlass(shape: RoundedRectangle(cornerRadius: 24, style: .continuous)))
        .shadow(color: .black.opacity(0.35), radius: 24, y: 8)
        .accessibilityIdentifier("chat-actions-menu")
    }

    private var contactMenuDivider: some View {
        Rectangle().fill(UnyriColors.primaryText.opacity(0.08)).frame(height: 0.5).padding(.vertical, 4)
    }

    private func contactMenuRow(_ title: String, icon: String, tint: Color, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(role: destructive ? .destructive : nil, action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 19, weight: .regular))
                    .foregroundStyle(destructive ? UnyriColors.danger : UnyriColors.primaryText).frame(width: 28)
                    .accessibilityHidden(true)
                Text(LocalizedStringKey(title)).font(.subheadline)
                    .foregroundStyle(destructive ? UnyriColors.danger : UnyriColors.primaryText).multilineTextAlignment(.leading)
                Spacer(minLength: 4)
            }.padding(.horizontal, 8).padding(.vertical, 10)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func beginSelection() {
        guard !batchBusy, !isSearching && !showingContacts, !isShowingChat, !openingGate.isPending else { return }
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
                        let isPinned = pinned.contains(chat.chatId)
                        ChatSwipeRow(actions: swipeActions(chat), isOpen: openSwipeID == chat.chatId, enabled: swipeEnabled,
                                     onBegin: { openSwipeID = chat.chatId },
                                     onClose: { if openSwipeID == chat.chatId { openSwipeID = nil } },
                                     onCommit: { commitRowAction($0, chat) }) {
                        HStack(spacing: 4) {
                            if selecting {
                                Image(systemName: selection.contains(chat.chatId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(colorStyle.decorative(UnyriColors.accent)).font(.title2)
                            }
                            ChatRow(chat: chat, latestCall: calls[chat.chatId], compact: isPinned).equatable()
                            if isPinned {
                                Image(systemName: "pin.fill").font(.body.weight(.semibold)).foregroundColor(colorStyle.decorative(Color(hex: 0xF1C36C)))
                                    .accessibilityLabel("Pinned chat")
                            }
                        }.padding(.trailing, 10)
                            .background(isPinned ? colorStyle.surface(Color(hex: 0x141D2C)).opacity(0.65) : Color.clear, in: RoundedRectangle(cornerRadius: 18))
                            .modifier(ChatZoomSource(id: chat.chatId, namespace: chatZoom))
                            .contentShape(Rectangle())
                            .accessibilityIdentifier("chat-list-row-" + chat.chatId)
                            .accessibilityAddTraits(selecting && selection.contains(chat.chatId) ? .isSelected : [])
                            .gesture(LongPressGesture(minimumDuration: 0.45)
                                .onEnded { _ in
                                    guard openSwipeID == nil else { return }
                                    if selecting { toggleSelection(chat.chatId) } else { presentContactMenu(chat) }
                                }
                                .exclusively(before: TapGesture().onEnded {
                                    // A tap on a row while a tray is open only closes the tray.
                                    if openSwipeID != nil { openSwipeID = nil; return }
                                    if selecting { toggleSelection(chat.chatId) } else { open(chat, viaRow: true) }
                                }))
                            .accessibilityAction(named: Text("Chat options")) { presentContactMenu(chat) }
                            .accessibilityActions {
                                // The swipe is off under VoiceOver; the same actions live here.
                                Button { commitRowAction(.pin, chat) } label: { Text(LocalizedStringKey(isPinned ? "Menu unpin chat" : "Menu pin chat")) }
                                Button { commitRowAction(.mute, chat) } label: { Text(LocalizedStringKey(chat.isMuted == true ? "Menu sound on" : "Menu sound off")) }
                                if chat.chatType == "direct" {
                                    Button { commitRowAction(.archive, chat) } label: {
                                        Text(LocalizedStringKey(chatsRepository.archivedChatIDs.contains(chat.chatId) ? "Restore to chats" : "Archive chat"))
                                    }
                                }
                            }
                        }
                            .modifier(ChatRowMotionHost(signal: rowSignal?.chatId == chat.chatId ? rowSignal : nil))
                            .padding(.horizontal, isPinned ? 2 : 0)
                            .padding(.vertical, isPinned ? 4 : 0)
                            .transition(.asymmetric(insertion: .opacity, removal: .move(edge: .leading).combined(with: .opacity)))
                            .onAppear {
                                visiblePresenceChats.insert(chat.chatId)
                                if chat.id == rows.last?.id, nextOffset != nil, !isLoading {
                                    Task { await load(reset: false) }
                                }
                            }
                            .onDisappear {
                                visiblePresenceChats.remove(chat.chatId)
                                if openSwipeID == chat.chatId { openSwipeID = nil }
                            }
                        if chat.id != rows.last?.id && !isPinned {
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
                .background(ChatSearchScrollAnchor(memory: searchScroll).frame(width: 0, height: 0))
            }
            .modifier(ChatListScrollClosesTray { if openSwipeID != nil { openSwipeID = nil } })
            // No position strip at the right edge of the chat list (owner, 18 Nov: like Telegram).
            .scrollIndicators(.hidden)
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


    /// Plain icons: the navigation bar draws the glass (one capsule on iOS 26, like the menu
    /// button on the left). Glass of their own drew a second lens inside that capsule.
    private var compactHeaderActions: some View {
        HStack(spacing: 6) {
            Button { UnyriHaptics.press(); onSearch() } label: {
                Image("UnyriSidebarSearch").renderingMode(.template).resizable().scaledToFit()
                    .frame(width: 21, height: 21).frame(width: 44, height: 44)
                    .foregroundStyle(UnyriColors.primaryText).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Search").accessibilityIdentifier("chats-search")
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
                errorMessage = UnyriErrorText.message(error) ?? errorMessage
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

    /// `finished` reports whether the change was accepted (a swipe archive uses it to
    /// keep or roll back its optimistic removal). It is called exactly once.
    private func changeArchive(_ chat: ChatSummary, finished: ((Bool) -> Void)? = nil) {
        guard !preferenceBusy, chat.chatType == "direct" else { finished?(false); return }
        let owner = menuOwner
        let repository = chatsRepository
        let archived = !repository.archivedChatIDs.contains(chat.chatId)
        preferenceBusy = true
        Task { @MainActor in
            defer { if owner == menuOwner { preferenceBusy = false } }
            guard owner == menuOwner else { finished?(false); return }
            do {
                _ = try await repository.setArchived([chat.chatId], archived: archived)
                guard owner == menuOwner else { finished?(false); return }
                finished?(true)
                selection.formIntersection(Set(visibleChats.map(\.chatId)))
            } catch is CancellationError { finished?(false) }
              catch {
                finished?(false)
                guard owner == menuOwner else { return }; errorMessage = UnyriErrorText.message(error) ?? errorMessage; showError = true
            }
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
              catch { guard owner == menuOwner else { return }; errorMessage = UnyriErrorText.message(error) ?? errorMessage; showError = true }
        }
    }

    /// Opens the chat a notification tap, banner, transfer or call screen asked for,
    /// through this list's own navigation. The request stays pending until Chats is
    /// the visible tab; another open chat is closed first; the same chat keeps itself
    /// (ChatScreen jumps to the message from the same route).
    /// Chats is the selected root tab. Chat routes (notification tap, banner, transfers,
    /// call screen) wait for it: a push inside a hidden tab left a navigation transition
    /// that never finished, and the whole app ignored taps until it was killed
    /// (device 0.4.5, 10 Nov: push tap from the lock screen while Settings was open).
    private var tabVisible: Bool { chatsRepository.chatsTabSelected }

    private func routeOpenRequest() {
        guard let chatId = chatsRepository.openChatRequest, tabVisible, !openingGate.isPending else { return }
        if isShowingChat {
            if openedChat?.chatId == chatId { chatsRepository.openChatRequest = nil }
            else { setChatPresented(false) }   // onChange(isShowingChat) routes again after the pop
            return
        }
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

    /// `viaRow` is true only for a tap on a list row: that open may animate from the
    /// row. Routes, search, Saved, notifications and links keep the instant push.
    private func open(_ chat: ChatSummary, safetyAction: String? = nil, messageId: String? = nil, viaRow: Bool = false) {
        guard !openingGate.isPending, !isShowingChat else { return }
        if let peer = chat.otherUser { chatsRepository.rememberSearchPerson(peer) }
        // A zoom pop is still running (isShowingChat flips at its start): a push now could
        // leave the stack without a visible chat. Open once the pop has settled.
        let pop = motionClock.popRemaining(now: CACurrentMediaTime())
        if pop > 0 {
            guard let ticket = openingGate.begin() else { return }
            openingTask?.cancel()
            openingTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(pop * 1_000_000_000))
                guard openingGate.finish(ticket) else { return }
                openingTask = nil
                guard !Task.isCancelled else { return }
                // A route that arrived during the wait was turned away by the gate; it is the later intent.
                if chatsRepository.openChatRequest != nil { routeOpenRequest() }
                else { open(chat, safetyAction: safetyAction, messageId: messageId, viaRow: viaRow) }
            }
            return
        }
        UnyriSavedRouteQA.record(12, chat.chatType == "saved" ? 1 : 0)
        let iOS18: Bool
        if #available(iOS 18.0, *) { iOS18 = true } else { iOS18 = false }
        openStyle = ChatOpenTransitionPolicy.style(viaRow: viaRow, iOS18: iOS18, reduceMotion: reduceMotion,
                                                   lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled)
        if openStyle == .zoom {
            let token = UUID()
            arrivalToken = token
            // The header reads it while the push runs; after that it must read false.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { if arrivalToken == token { arrivalToken = nil } }
        } else {
            arrivalToken = nil
        }
        openedMessageId = messageId
        openedSafetyAction = safetyAction
        openedChat = chat
        // A message target needs the full window; otherwise show the newest rows at once.
        messagesRepository.preloadChat(chat.chatId, firstWindow: messageId == nil)
        setChatPresented(true)
    }

    private var chatNavigationTransaction: Transaction {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        return transaction
    }

    private func setChatPresented(_ presented: Bool) {
        // The zoom needs the system's animated push; a route-driven close stays instant.
        motionClock.instantClose = !presented
        withTransaction(presented && openStyle == .zoom ? Transaction() : chatNavigationTransaction) {
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
            } catch let error as APIError where AuthRepository.isExpiredBearer(error) {
                // The rejected bearer was just marked expired: this retry refreshes the
                // session first instead of showing "Authentication required".
                try await Task.sleep(nanoseconds: 150_000_000)
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

/// Equatable so parent passes (a swipe drag, a row signal, a list move) skip this body;
/// its own environment objects still re-render it on presence, typing and unread changes.
private struct ChatRow: View, Equatable {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var usersRepository: UsersRepository
    @EnvironmentObject private var chatsRepository: ChatsRepository
    let chat: ChatSummary
    var latestCall: CallDTO? = nil
    var compact = false
    @EnvironmentObject private var auth: AuthRepository

    static func == (lhs: ChatRow, rhs: ChatRow) -> Bool {
        lhs.chat == rhs.chat && lhs.latestCall == rhs.latestCall && lhs.compact == rhs.compact
    }

    @State private var avatarURL: URL?
    @State private var expiredActivity: Date?
    private var peerOnline: Bool {
        guard chat.isBlocked != true, let peer = chat.otherUser else { return false }
        return realtime.presenceObservation(for: peer.id)?.item.online == true
    }
    private var presenceState: UnyriPresenceState {
        guard chat.isBlocked != true, let peer = chat.otherUser else { return .unknown }
        return UnyriPresenceState(realtime.presenceObservation(for: peer.id))
    }
    /// The peer's live activity (typing, recording, uploading) while it is fresh.
    private var activityKind: String? {
        guard let item = realtime.activity[chat.chatId], expiredActivity != item.at,
              Date().timeIntervalSince(item.at) < 8, item.kind != "idle" else { return nil }
        return item.kind
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
        let avatarSize: CGFloat = compact ? 48 : 52
        let activity = activityKind
        let presence = presenceState
        HStack(spacing: 12) {
            UserAvatar(url: avatarURL ?? usersRepository.cachedAvatarURL(id: chat.otherUser?.id ?? "", avatarUrl: chat.otherUser?.avatarUrl, avatarVersion: chat.otherUser?.avatarVersion ?? 0), name: title, size: avatarSize)
                .background {
                    // A breathing ring while the peer types; it exists only for the activity.
                    if activity != nil {
                        ChatTypingRing(color: UIColor(UnyriColors.primaryText))
                            .frame(width: avatarSize + 6, height: avatarSize + 6)
                            .transition(.opacity)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .animation(.easeOut(duration: 0.2), value: activity != nil)
                .overlay(alignment: .bottomTrailing) {
                    UnyriPresenceDot(state: presence, diameter: 10, color: ChatControlPalette.mint, cutout: UnyriColors.background)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("online")
                        .accessibilityIdentifier(presence == .online ? "chat-list-online-" + chat.chatId : "")
                        .accessibilityHidden(presence != .online)
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
                ZStack(alignment: .leading) {
                    if let kind = activity {
                        HStack(spacing: 5) {
                            Text(verbatim: UnyriL10n.string(kind == "typing" ? "List typing" : kind == "recording" ? "List recording voice" : "List sending file"))
                                .font(.footnote).foregroundColor(colorStyle.decorative(UnyriColors.accentSoft)).lineLimit(1)
                            ChatTypingWave(color: UIColor(colorStyle.decorative(UnyriColors.accentSoft)))
                                .frame(width: 18, height: 10)
                                .accessibilityHidden(true)
                        }
                        .transition(.opacity)
                    } else {
                        previewLine.transition(.opacity)
                    }
                }
                .animation(.easeOut(duration: 0.18), value: activity != nil)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 7) {
                if let timestamp = timestampText {
                    Text(timestamp)
                        .font(UnyriTypography.timestamp).lineLimit(1).fixedSize(horizontal: true, vertical: false)
                        .foregroundColor(UnyriColors.mutedText)
                }
                let unread = chatsRepository.unreadCounts[chat.chatId] ?? 0
                // The slot always exists, so the timestamp never jumps when the badge comes or goes.
                ZStack {
                    if unread > 0 {
                        Text(unread > 99 ? "99+" : "\(unread)")
                            .font(.caption.weight(.semibold)).foregroundColor(colorStyle.onColorfulButton)
                            .contentTransition(.numericText())
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Capsule().fill(colorStyle.decorative(UnyriColors.accent)))
                            .accessibilityLabel("\(unread) unread messages")
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .frame(minHeight: 20)
                // Only a live change rolls the digits; a freshly created row renders as is.
                .animation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.72), value: unread)
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
            Text(UnyriMessageTextPresentation.display(DirectFilePreviewText.display(text: text) ?? text, inline: true)).font(.footnote).foregroundColor(UnyriColors.secondaryText).lineLimit(1)
        case .some(.video):
            mediaPreview("Video", icon: "video", tint: ChatControlPalette.blue)
        case .some(.photo):
            mediaPreview("Photo", icon: "photo", tint: ChatControlPalette.mint)
        case .some(.voice):
            mediaPreview("Voice message", icon: "waveform", tint: UnyriColors.accentSoft)
        case .some(.file(let filename)):
            HStack(spacing: 5) { Image(systemName: "doc").foregroundStyle(colorStyle.decorative(ChatControlPalette.amber)); Text(UnyriMessageTextPresentation.display(DirectFilePreviewText.file(filename), inline: true)).foregroundStyle(UnyriColors.secondaryText).lineLimit(1) }.font(.footnote)
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
        return chat.otherUser?.visibleName ?? chat.otherUser?.username ?? "Chat"
    }

    private var avatarCacheKey: String {
        "\(chat.otherUser?.id ?? chat.chatId):\(chat.otherUser?.avatarVersion ?? 0)"
    }

    private var timestampText: String? {
        let activity = ChatCallActivity.latestDate(message: chat.lastMessageAt.flatMap(ISO8601Cocoa.parse), call: latestCall)
        return activity == .distantPast ? RelativeTime.label(forRFC3339: chat.createdAt) : RelativeTime.label(for: activity)
    }
}

// MARK: - Chat list motion policies
// Pure and Foundation-only: a contract runner can slice this section and test it.

enum ChatOpenStyle: Equatable { case instant, fade, settle, zoom }

enum ChatOpenTransitionPolicy {
    /// Kill switch, off until device QA on iOS 18+. The zoom brings the system's own
    /// swipe-down and pinch dismissal, which UnyriChatBackSwipe cannot gate: it closes a
    /// chat during selection or message tools, and may take the inverted timeline's drags
    /// at the newest messages. While off, row opens use `.settle`.
    static let zoomEnabled = false
    static func style(viaRow: Bool, iOS18: Bool, reduceMotion: Bool, lowPower: Bool,
                      zoom: Bool = ChatOpenTransitionPolicy.zoomEnabled) -> ChatOpenStyle {
        guard viaRow else { return .instant }   // routes, search, Saved, notifications: unchanged
        if reduceMotion || lowPower { return .fade }
        return iOS18 && zoom ? .zoom : .settle
    }
}

/// Decides whether a list change is one live event (a row moves up, lifts, gets a light
/// sweep) or a catch-up that should just land without motion.
enum ChatListLiftPolicy {
    struct Row: Equatable { let id: String; let activity: Date; let unread: Int; let muted: Bool }
    struct Decision: Equatable { var animateOrder = false; var lifted: String? = nil; var swept: String? = nil }
    static let maxMoves = 3

    static func decide(old: [Row], new: [Row], live: Bool, sweepAllowed: (String) -> Bool) -> Decision {
        guard live, !old.isEmpty, !new.isEmpty, abs(new.count - old.count) <= maxMoves else { return Decision() }
        var oldIndex: [String: Int] = [:]
        var oldRows: [String: Row] = [:]
        for (index, row) in old.enumerated() where oldIndex[row.id] == nil {
            oldIndex[row.id] = index
            oldRows[row.id] = row
        }
        // Bumped: newer activity than before, or a new chat that lands near the top.
        var bumped: [(row: Row, index: Int)] = []
        for (index, row) in new.enumerated() {
            if let previous = oldRows[row.id] {
                if row.activity > previous.activity { bumped.append((row, index)) }
            } else if index < maxMoves {
                bumped.append((row, index))
            }
        }
        let moved = bumped.filter { item in oldIndex[item.row.id].map { item.index < $0 } ?? true }
        // More than a few rows moving at once is a catch-up: rows jump without animation.
        guard moved.count <= maxMoves else { return Decision() }
        var decision = Decision()
        decision.animateOrder = !moved.isEmpty
        decision.lifted = moved.count == 1 ? moved[0].row.id : nil
        // Incoming only: the unread count grew, and the chat is not muted.
        let fresh = bumped.filter { item in !item.row.muted && item.row.unread > (oldRows[item.row.id]?.unread ?? 0) }
        if fresh.count == 1, sweepAllowed(fresh[0].row.id) { decision.swept = fresh[0].row.id }
        return decision
    }
}

/// Geometry of the swipe tray: open width, rubber band past it, the armed full swipe
/// (with hysteresis so it never chatters) and what a release does.
enum ChatSwipePolicy {
    static let slot: CGFloat = 68, padding: CGFloat = 8, commitExtra: CGFloat = 88, hysteresis: CGFloat = 24,
               elastic: CGFloat = 0.55, magnet: CGFloat = 14, flick: CGFloat = 350

    static func openWidth(count: Int) -> CGFloat { CGFloat(count) * slot + padding }

    static func displayed(raw: CGFloat, open: CGFloat, armed: Bool) -> CGFloat {
        guard raw > 0 else { return 0 }
        guard raw > open else { return raw }
        return open + (raw - open) * elastic + (armed ? magnet : 0)
    }

    static func armed(raw: CGFloat, open: CGFloat, wasArmed: Bool) -> Bool {
        raw >= open + commitExtra || (wasArmed && raw >= open + commitExtra - hysteresis)
    }

    enum Release: Equatable { case close, open, commit }

    /// `velocity` is positive toward opening (leftward in a left-to-right layout).
    static func release(raw: CGFloat, velocity: CGFloat, open: CGFloat, armed: Bool) -> Release {
        if armed { return .commit }
        if velocity > flick && raw > 12 { return .open }
        if velocity < -flick { return .close }
        return raw >= open * 0.5 ? .open : .close
    }
}
// MARK: - End chat list motion policies

private enum ChatListMotion {
    static let move = Animation.spring(response: 0.42, dampingFraction: 0.86)
    static let liftScale: CGFloat = 1.02
    static let liftOut = 0.16
    static let liftHold = 0.20
    static let sweepDelay = 0.22
    static let sweepDuration = 0.9
    static let sweepLifetime = 1.25
    static let sweepCooldown = 2.5
    static let liveDelay = 1.0
    static let returnHold = 0.55
    static let rowRadius: CGFloat = 18
}

/// Timing state for live list motion. A plain object, so its writes never re-render.
@MainActor
final class ChatListMotionClock {
    var liveSince: CFTimeInterval = .infinity
    var returnedAt: CFTimeInterval = -.infinity
    /// While a zoom pop still shrinks the chat into its row, a push would land mid-pop.
    var popEndsAt: CFTimeInterval = -.infinity
    /// Set by a route-driven close, which is an instant cut with no pop to wait for.
    var instantClose = false
    var lastRows: [ChatListLiftPolicy.Row] = []
    var lastFilterKey = ""
    private var sweptAt: [String: CFTimeInterval] = [:]

    func stamp(live: Bool) { liveSince = live ? CACurrentMediaTime() : .infinity }

    /// The list has been on screen for a second and is not just back from a chat.
    func isLive(now: CFTimeInterval) -> Bool {
        now - liveSince >= ChatListMotion.liveDelay && now - returnedAt >= ChatListMotion.returnHold
    }

    func returnHold(now: CFTimeInterval) -> TimeInterval { max(0, ChatListMotion.returnHold - (now - returnedAt)) }

    func popRemaining(now: CFTimeInterval) -> TimeInterval { max(0, popEndsAt - now) }

    /// One sweep per chat per cooldown; records the sweep when it allows one.
    func allowSweep(_ id: String, now: CFTimeInterval) -> Bool {
        if let last = sweptAt[id], now - last < ChatListMotion.sweepCooldown { return false }
        sweptAt[id] = now
        if sweptAt.count > 64 { sweptAt = sweptAt.filter { now - $0.value < ChatListMotion.sweepCooldown } }
        return true
    }

    func reset() {
        liveSince = .infinity; returnedAt = -.infinity; popEndsAt = -.infinity; instantClose = false
        lastRows = []; lastFilterKey = ""; sweptAt = [:]
    }
}

/// The one row that plays an effect for the latest list event.
private struct ChatRowSignal: Equatable {
    let id = UUID()
    let chatId: String
    let kind: Kind
    enum Kind: Equatable { case lift(sweep: Bool), sweep, returnGlow }

    static func from(_ decision: ChatListLiftPolicy.Decision) -> ChatRowSignal? {
        if let lifted = decision.lifted { return ChatRowSignal(chatId: lifted, kind: .lift(sweep: decision.swept == lifted)) }
        if let swept = decision.swept { return ChatRowSignal(chatId: swept, kind: .sweep) }
        return nil
    }
}

/// Plays a row's one-shot effects. It reacts to a new signal id only, so a row created
/// again on scroll-back never replays an old event.
private struct ChatRowMotionHost: ViewModifier {
    let signal: ChatRowSignal?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lifted = false
    @State private var sweep: UUID?
    @State private var glowing = false

    func body(content: Content) -> some View {
        content
            .overlay {
                if glowing || sweep != nil {
                    ZStack {
                        if glowing {
                            RoundedRectangle(cornerRadius: ChatListMotion.rowRadius, style: .continuous)
                                .fill(UnyriColors.glassHighlight).transition(.opacity)
                        }
                        if let sweep { ChatRowLightSweep().id(sweep) }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: ChatListMotion.rowRadius, style: .continuous))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            // No shadow: it would force an offscreen pass for the whole row.
            .scaleEffect(lifted ? ChatListMotion.liftScale : 1)
            .zIndex(lifted ? 1 : 0)
            .onChange(of: signal?.id) { _ in play() }
    }

    private func play() {
        guard let signal else { return }
        let calm = reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled
        switch signal.kind {
        case .lift(let sweeps):
            if !calm { lift() }
            if sweeps { light(calm: calm) }
        case .sweep:
            light(calm: calm)
        case .returnGlow:
            glow()
        }
    }

    private func lift() {
        withAnimation(.easeOut(duration: ChatListMotion.liftOut)) { lifted = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + ChatListMotion.liftHold) {
            withAnimation(.spring(response: 0.36, dampingFraction: 0.78)) { lifted = false }
        }
    }

    /// The light sweep, or under Low Power and Reduce Motion the cheaper glow.
    private func light(calm: Bool) {
        guard !calm else { glow(); return }
        let id = UUID()
        sweep = id
        DispatchQueue.main.asyncAfter(deadline: .now() + ChatListMotion.sweepLifetime) {
            if sweep == id { sweep = nil }
        }
    }

    private func glow() {
        withAnimation(.easeOut(duration: 0.15)) { glowing = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            withAnimation(.easeIn(duration: 0.45)) { glowing = false }
        }
    }
}

/// One soft diagonal band of light across the row, on the render server. It plays once
/// when it enters the window; the host removes the view after its lifetime.
private struct ChatRowLightSweep: UIViewRepresentable {
    func makeUIView(context: Context) -> SweepView { SweepView() }
    func updateUIView(_ view: SweepView, context: Context) {}

    final class SweepView: UIView {
        override class var layerClass: AnyClass { CAGradientLayer.self }
        private var gradient: CAGradientLayer { layer as! CAGradientLayer }
        private var played = false
        private static let start: [NSNumber] = [-0.44, -0.22, 0]
        private static let end: [NSNumber] = [1.0, 1.22, 1.44]

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            let clear = UIColor.white.withAlphaComponent(0).cgColor
            gradient.colors = [clear, UIColor.white.withAlphaComponent(0.09).cgColor, clear]
            gradient.locations = Self.start
            gradient.startPoint = CGPoint(x: 0, y: 0.3)
            gradient.endPoint = CGPoint(x: 1, y: 0.7)
        }
        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, !played else { return }
            played = true
            let sweep = CABasicAnimation(keyPath: "locations")
            sweep.fromValue = Self.start
            sweep.toValue = Self.end
            sweep.duration = ChatListMotion.sweepDuration
            sweep.beginTime = gradient.convertTime(CACurrentMediaTime(), from: nil) + ChatListMotion.sweepDelay
            sweep.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            sweep.fillMode = .backwards
            sweep.repeatCount = 1
            // The model ends past the trailing edge, so nothing snaps back afterwards.
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            gradient.locations = Self.end
            CATransaction.commit()
            gradient.add(sweep, forKey: "sweep")
        }
    }
}

/// Three dots rising in a wave while the peer types. The animation runs on the render
/// server and exists only with the view, so it stops completely when typing ends.
private struct ChatTypingWave: UIViewRepresentable {
    let color: UIColor
    func makeUIView(context: Context) -> WaveView { WaveView() }
    // Rows re-render on every presence snapshot: this must stay a cheap no-op then.
    func updateUIView(_ view: WaveView, context: Context) { view.update(color: color) }

    final class WaveView: UIView {
        private let dots: [CALayer] = (0..<3).map { _ in CALayer() }
        private var color: CGColor?
        private static let staticOpacity: [Float] = [0.45, 0.7, 1.0]

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            for dot in dots {
                dot.cornerRadius = 2
                layer.addSublayer(dot)
            }
        }
        required init?(coder: NSCoder) { nil }

        func update(color next: UIColor) {
            let cg = next.cgColor
            if color.map({ !CFEqual($0, cg) }) ?? true {
                color = cg
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                dots.forEach { $0.backgroundColor = cg }
                CATransaction.commit()
            }
            startIfNeeded()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            let size: CGFloat = 4, gap: CGFloat = 3
            let width = size * 3 + gap * 2
            let x0 = (bounds.width - width) / 2, y = (bounds.height - size) / 2
            for (index, dot) in dots.enumerated() {
                dot.frame = CGRect(x: x0 + CGFloat(index) * (size + gap), y: y, width: size, height: size)
            }
            CATransaction.commit()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            // Core Animation drops animations while the list is out of the window (a pushed chat).
            startIfNeeded()
        }

        private func startIfNeeded() {
            guard window != nil else { return }
            let calm = UIAccessibility.isReduceMotionEnabled || ProcessInfo.processInfo.isLowPowerModeEnabled
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            defer { CATransaction.commit() }
            if calm {
                for (index, dot) in dots.enumerated() {
                    dot.removeAnimation(forKey: "wave")
                    dot.opacity = Self.staticOpacity[index]
                }
                return
            }
            for (index, dot) in dots.enumerated() where dot.animation(forKey: "wave") == nil {
                dot.opacity = 0.35
                let ease = CAMediaTimingFunction(name: .easeInEaseOut)
                let rise = CAKeyframeAnimation(keyPath: "transform.translation.y")
                rise.values = [0, -2.5, 0, 0]
                rise.keyTimes = [0, 0.25, 0.5, 1]
                rise.timingFunctions = [ease, ease, ease]
                let fade = CAKeyframeAnimation(keyPath: "opacity")
                fade.values = [0.35, 1, 0.35, 0.35]
                fade.keyTimes = [0, 0.25, 0.5, 1]
                fade.timingFunctions = [ease, ease, ease]
                let group = CAAnimationGroup()
                group.animations = [rise, fade]
                group.duration = 1.2
                group.repeatCount = .infinity
                group.beginTime = dot.convertTime(CACurrentMediaTime(), from: nil) + Double(index) * 0.15
                group.fillMode = .backwards
                dot.add(group, forKey: "wave")
            }
        }
    }
}

/// A thin ring breathing around the avatar while the peer types (render server only).
private struct ChatTypingRing: UIViewRepresentable {
    let color: UIColor
    func makeUIView(context: Context) -> RingView { RingView() }
    func updateUIView(_ view: RingView, context: Context) { view.update(color: color) }

    final class RingView: UIView {
        private let ring = CAShapeLayer()
        private var color: CGColor?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            ring.fillColor = UIColor.clear.cgColor
            ring.lineWidth = 1.5
            ring.opacity = 0.22
            layer.addSublayer(ring)
        }
        required init?(coder: NSCoder) { nil }

        func update(color next: UIColor) {
            let cg = next.cgColor
            if color.map({ !CFEqual($0, cg) }) ?? true {
                color = cg
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                ring.strokeColor = cg
                CATransaction.commit()
            }
            startIfNeeded()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            ring.frame = bounds
            ring.path = UIBezierPath(ovalIn: bounds.insetBy(dx: 0.75, dy: 0.75)).cgPath
            CATransaction.commit()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            startIfNeeded()
        }

        private func startIfNeeded() {
            guard window != nil else { return }
            let calm = UIAccessibility.isReduceMotionEnabled || ProcessInfo.processInfo.isLowPowerModeEnabled
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            defer { CATransaction.commit() }
            if calm {
                ring.removeAnimation(forKey: "breath")
                ring.opacity = 0.22
                return
            }
            guard ring.animation(forKey: "breath") == nil else { return }
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.10
            fade.toValue = 0.30
            let scale = CABasicAnimation(keyPath: "transform.scale")
            scale.fromValue = 0.97
            scale.toValue = 1.03
            let group = CAAnimationGroup()
            group.animations = [fade, scale]
            group.duration = 1.8
            group.autoreverses = true
            group.repeatCount = .infinity
            group.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            ring.add(group, forKey: "breath")
        }
    }
}

// MARK: - Swipe actions

struct ChatSwipeAction: Equatable, Identifiable {
    enum Kind: Hashable { case pin, mute, archive }
    let kind: Kind
    let title: String
    let icon: String
    let result: String
    let tint: Color
    var id: Kind { kind }
}

/// A leftward swipe reveals a glass tray (pin, mute, archive). A full swipe arms the
/// primary action with one haptic tick and commits on release. All drag state lives
/// here, so a drag re-renders this row only (ChatRow itself is skipped via Equatable).
private struct ChatSwipeRow<Content: View>: View {
    let actions: [ChatSwipeAction]
    let isOpen: Bool
    let enabled: Bool
    let onBegin: () -> Void
    let onClose: () -> Void
    let onCommit: (ChatSwipeAction.Kind) -> Void
    let content: Content
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @State private var raw: CGFloat = 0
    @State private var base: CGFloat = 0
    @State private var velocity: CGFloat = 0
    @State private var dragging = false
    @State private var armed = false
    @State private var morphed: ChatSwipeAction.Kind?
    @State private var pulsing: ChatSwipeAction.Kind?
    @State private var calm = false
    // Created once per row (StateObject's initializer is lazy); it never publishes.
    @StateObject private var sampler = ChatSwipeSampler()
    // DragGesture.onEnded never runs for a cancelled drag (the scroll view took over).
    @GestureState private var tracking = false

    init(actions: [ChatSwipeAction], isOpen: Bool, enabled: Bool, onBegin: @escaping () -> Void,
         onClose: @escaping () -> Void, onCommit: @escaping (ChatSwipeAction.Kind) -> Void,
         @ViewBuilder content: () -> Content) {
        self.actions = actions
        self.isOpen = isOpen
        self.enabled = enabled
        self.onBegin = onBegin
        self.onClose = onClose
        self.onCommit = onCommit
        self.content = content()
    }

    private var isRTL: Bool { layoutDirection == .rightToLeft }
    private var openWidth: CGFloat { ChatSwipePolicy.openWidth(count: actions.count) }
    private var calmNow: Bool { reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled }

    var body: some View {
        let d = ChatSwipePolicy.displayed(raw: raw, open: openWidth, armed: armed)
        let active = enabled && !actions.isEmpty
        // The tray is a background, so it never changes the row's height or layout.
        content
            .offset(x: isRTL ? d : -d)
            .background(alignment: .trailing) {
                if !actions.isEmpty && (d > 0.5 || isOpen) { tray(d).accessibilityHidden(true) }
            }
            .modifier(ChatRowPanHost(enabled: active, isRTL: isRTL, isOpen: isOpen || raw > 0,
                changed: { tx, vx in drag(tx: tx, vx: vx) },
                ended: { cancelled, vx in release(cancelled: cancelled, vx: vx) }))
            .simultaneousGesture(ChatRowPanHost.usesUIKit || !active ? nil : fallbackDrag)
            .onChange(of: tracking) { isTracking in
                guard !isTracking else { return }
                // Let a normal onEnded run first; only a cancelled drag is still marked here.
                DispatchQueue.main.async {
                    if dragging { release(cancelled: true, vx: 0) }
                    sampler.reset()
                }
            }
            .onChange(of: isOpen) { open in
                // Another row opened, a scroll began or a tap closed the tray. An active
                // drag keeps tracking and re-claims the tray if it ends open.
                guard !open, !dragging, morphed == nil, raw != 0 else { return }
                armed = false
                snap(to: 0)
            }
            .onChange(of: enabled) { on in
                guard !on, raw != 0 || dragging else { return }
                dragging = false; armed = false
                snap(to: 0)
                onClose()
            }
            .onDisappear {
                guard raw != 0 || dragging || armed else { return }
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) { raw = 0; armed = false; dragging = false; velocity = 0; morphed = nil }
            }
    }

    // MARK: Gesture

    private var fallbackDrag: some Gesture {
        DragGesture(minimumDistance: 16, coordinateSpace: .local)
            .updating($tracking) { _, state, _ in state = true }
            .onChanged { value in
                let tx = value.translation.width
                if !dragging {
                    guard !sampler.rejected else { return }
                    let lead = isRTL ? tx : -tx
                    // Decided once per drag: a vertical start stays a scroll.
                    guard abs(tx) > abs(value.translation.height) * 1.35, isOpen || raw > 0 || lead > 0,
                          !UIAccessibility.isVoiceOverRunning else { sampler.rejected = true; return }
                }
                drag(tx: tx, vx: sampler.velocity(x: tx, at: value.time))
            }
            .onEnded { _ in
                if dragging { release(cancelled: false, vx: sampler.current) }
                sampler.reset()
            }
    }

    private func drag(tx: CGFloat, vx: CGFloat) {
        guard morphed == nil else { return }
        if !dragging {
            dragging = true
            base = isOpen || raw > 0 ? openWidth : 0
            calm = calmNow
            onBegin()
        }
        let next = base + (isRTL ? tx : -tx)
        let nextArmed = ChatSwipePolicy.armed(raw: next, open: openWidth, wasArmed: armed)
        // Per-frame values follow the finger; they must never pick up an animation.
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            raw = next
            velocity = isRTL ? vx : -vx
        }
        guard nextArmed != armed else { return }
        withAnimation(calm ? .easeOut(duration: 0.18) : .spring(response: 0.22, dampingFraction: 0.68)) { armed = nextArmed }
        if nextArmed, let primary = actions.first {
            UnyriHaptics.selection()
            pulse(primary.kind)
        }
    }

    private func release(cancelled: Bool, vx: CGFloat) {
        guard dragging else { return }
        dragging = false
        let outcome: ChatSwipePolicy.Release = cancelled ? .close
            : ChatSwipePolicy.release(raw: raw, velocity: isRTL ? vx : -vx, open: openWidth, armed: armed)
        velocity = 0
        switch outcome {
        case .commit:
            if let primary = actions.first { commit(primary.kind) }
        case .open:
            if armed { armed = false }
            snap(to: openWidth)
            onBegin()
        case .close:
            armed = false
            snap(to: 0)
            onClose()
        }
    }

    private func snap(to target: CGFloat) {
        let animation: Animation = calmNow ? .easeOut(duration: 0.18)
            : target > 0 ? .spring(response: 0.32, dampingFraction: 0.74) : .spring(response: 0.3, dampingFraction: 0.9)
        withAnimation(animation) { raw = target }
    }

    /// The icon morphs into its result, then the tray closes and the action runs.
    private func commit(_ kind: ChatSwipeAction.Kind) {
        guard morphed == nil else { return }
        withAnimation(calm ? .easeOut(duration: 0.18) : .spring(response: 0.28, dampingFraction: 0.66)) { morphed = kind }
        if !armed { pulse(kind) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.30) {
            withAnimation(calm ? .easeOut(duration: 0.18) : .spring(response: 0.38, dampingFraction: 0.86)) {
                raw = 0
                armed = false
            }
            onClose()
            onCommit(kind)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { morphed = nil }
        }
    }

    private func tap(_ kind: ChatSwipeAction.Kind) {
        guard morphed == nil, !dragging else { return }
        calm = calmNow
        commit(kind)
    }

    private func pulse(_ kind: ChatSwipeAction.Kind) {
        guard !calm else { return }
        withAnimation(.spring(response: 0.16, dampingFraction: 0.6)) { pulsing = kind }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.14) {
            withAnimation(.spring(response: 0.28, dampingFraction: 0.7)) { if pulsing == kind { pulsing = nil } }
        }
    }

    // MARK: Tray

    @ViewBuilder private func tray(_ d: CGFloat) -> some View {
        let solid = reduceTransparency || contrast == .increased || ProcessInfo.processInfo.isLowPowerModeEnabled
        let stretch = dragging && !calm
        let speed = abs(velocity)
        ChatSwipeTrayGlass(solid: solid) {
            ZStack(alignment: .trailing) {
                ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                    slot(action, index: index, d: d, solid: solid)
                }
            }
        }
        .frame(width: max(1, d), alignment: .trailing)
        // The liquid edge stretches with the finger's speed while dragging.
        .scaleEffect(x: stretch ? 1 + min(0.2, speed / 2500) : 1, y: stretch ? 1 - min(0.08, speed / 6000) : 1,
                     anchor: isRTL ? .leading : .trailing)
    }

    private func slot(_ action: ChatSwipeAction, index: Int, d: CGFloat, solid: Bool) -> some View {
        let open = openWidth
        let n = CGFloat(max(1, actions.count))
        let progress = min(1, d / open)
        let primary = index == 0
        let widened = armed && primary
        let hidden = armed && !primary
        let diameter = 44 * max(0.35, progress)
        let width = widened ? max(diameter, d - 12) : diameter
        // Slot centres measured from the trailing edge; early in the drag they overlap
        // and the glass merges them into one drop, then they split as the tray widens.
        let center = widened ? d / 2 : d * (CGFloat(index) + 0.5) / n
        let box = max(width, ChatSwipePolicy.slot - 4)
        let symbol = widened || morphed == action.kind ? action.result : action.icon
        let labelOpacity = min(1, max(0, (progress - 0.6) / 0.4))
        return Button { tap(action.kind) } label: {
            VStack(spacing: 4) {
                // The glass sits on the icon's own frame, so the icon is drawn on it. The
                // scale stays on the icon: the capsule never shrinks with the pulse.
                ZStack {
                    Image(systemName: symbol)
                        .font(.system(size: 19, weight: .regular))
                        .foregroundStyle(colorStyle.decorative(action.tint))
                        .modifier(ChatSwipeSymbolMorph(symbol: symbol, calm: calm))
                        .scaleEffect(max(0.35, progress) * (pulsing == action.kind ? 1.18 : 1))
                }
                .frame(width: width, height: diameter)
                .modifier(ChatSwipeCapsuleGlass(solid: solid))
                .frame(height: 44)
                Text(LocalizedStringKey(action.title))
                    .font(.caption2).foregroundStyle(UnyriColors.secondaryText)
                    .lineLimit(1).minimumScaleFactor(0.8)
                    .opacity(labelOpacity)
            }
            .frame(width: box)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Not `.disabled`: that would dim the tray while dragging and during the morph.
        .allowsHitTesting(morphed == nil && !dragging && !hidden)
        .opacity(hidden ? 0 : 1)
        .scaleEffect(hidden ? 0.6 : 1)
        .offset(x: (isRTL ? 1 : -1) * (center - box / 2))
    }
}

/// iOS 26: one glass pass for the tray, so nearby capsules merge like liquid.
/// Members apply their glass to their content (ChatSwipeCapsuleGlass): inside this
/// container a glass on an empty shape behind the icon covers the icon.
private struct ChatSwipeTrayGlass<Content: View>: View {
    let solid: Bool
    @ViewBuilder let content: () -> Content
    var body: some View {
        if #available(iOS 26.0, *), !solid {
            GlassEffectContainer(spacing: 16) { content() }
        } else {
            content()
        }
    }
}

/// Liquid Glass on iOS 26, material before, a solid fill under Reduce Transparency,
/// Increase Contrast or Low Power. On iOS 26 the slot's content is the glass's
/// content, as the API intends, so the icon stays above the tray's shared glass.
private struct ChatSwipeCapsuleGlass: ViewModifier {
    let solid: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if solid {
            content.background(Capsule().fill(UnyriColors.glassPrimary))
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: Capsule())
        } else {
            content.background(Capsule().fill(.ultraThinMaterial)
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5)))
        }
    }
}

/// Outline -> filled icon: a symbol replace on iOS 17+, a scale swap on iOS 16, a
/// crossfade in calm mode.
private struct ChatSwipeSymbolMorph: ViewModifier {
    let symbol: String
    let calm: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if calm {
            content.id(symbol).transition(.opacity)
        } else if #available(iOS 17.0, *) {
            content.contentTransition(.symbolEffect(.replace))
        } else {
            content.id(symbol).transition(.scale(scale: 0.6).combined(with: .opacity))
        }
    }
}

/// Fallback drag bookkeeping (iOS 16-17). Nothing is @Published: per-frame writes never re-render.
private final class ChatSwipeSampler: ObservableObject {
    var rejected = false
    private(set) var current: CGFloat = 0
    private var lastX: CGFloat = 0
    private var lastTime: Date?

    func velocity(x: CGFloat, at time: Date) -> CGFloat {
        defer { lastX = x; lastTime = time }
        guard let lastTime else { return current }
        let dt = time.timeIntervalSince(lastTime)
        guard dt > 0.001 else { return current }
        current = current * 0.4 + (x - lastX) / CGFloat(dt) * 0.6
        return current
    }

    func reset() { rejected = false; current = 0; lastX = 0; lastTime = nil }
}

/// iOS 18+: the row swipe is a UIKit pan, like swipe-to-reply. It is always attached
/// (toggling it would swap the row's identity); `enabled` is checked when it begins.
private struct ChatRowPanHost: ViewModifier {
    static var usesUIKit: Bool { if #available(iOS 18.0, *) { return true } else { return false } }
    let enabled: Bool
    let isRTL: Bool
    let isOpen: Bool
    let changed: (CGFloat, CGFloat) -> Void
    let ended: (Bool, CGFloat) -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.gesture(ChatRowPan(isRTL: isRTL, isOpen: isOpen, enabled: enabled, changed: changed, ended: ended))
        } else {
            content
        }
    }
}

@available(iOS 18.0, *)
private struct ChatRowPan: UIGestureRecognizerRepresentable {
    let isRTL: Bool
    let isOpen: Bool
    let enabled: Bool
    /// (translation.x, velocity.x) in the row's own coordinates.
    let changed: (CGFloat, CGFloat) -> Void
    /// (cancelled, velocity.x).
    let ended: (Bool, CGFloat) -> Void
    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }
    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        pan.cancelsTouchesInView = false
        pan.delaysTouchesBegan = false
        pan.maximumNumberOfTouches = 1
        return pan
    }
    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        context.coordinator.isRTL = isRTL
        context.coordinator.isOpen = isOpen
        context.coordinator.enabled = enabled
    }
    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let velocity = recognizer.velocity(in: recognizer.view).x
        switch recognizer.state {
        case .began:
            // Telegram-like direction lock (owner, 18 Nov): once the row owns a horizontal
            // swipe, the list stops scrolling under the finger for the rest of this drag.
            context.coordinator.stopListScroll()
            changed(recognizer.translation(in: recognizer.view).x, velocity)
        case .changed: changed(recognizer.translation(in: recognizer.view).x, velocity)
        case .ended: ended(false, velocity)
        case .cancelled, .failed: ended(true, velocity)
        default: break
        }
    }
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var isRTL = false
        var isOpen = false
        var enabled = true
        /// The list's own pan, seen while both recognizers track the same touch.
        weak var listPan: UIGestureRecognizer?
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard enabled, !UIAccessibility.isVoiceOverRunning, let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
            let velocity = pan.velocity(in: pan.view)
            let toward = isRTL ? velocity.x : -velocity.x
            // Mostly horizontal; toward opening when closed, either way when open.
            return abs(velocity.x) > abs(velocity.y) * 1.35 && (isOpen || toward > 0)
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            if other is UIPanGestureRecognizer, other.view is UIScrollView { listPan = other }
            return true
        }
        /// Toggling isEnabled cancels the scroll view's current drag only; the next touch scrolls normally.
        func stopListScroll() {
            guard let pan = listPan, pan.isEnabled else { return }
            pan.isEnabled = false
            pan.isEnabled = true
        }
    }
}

/// iOS 18+: a scroll the user starts closes an open tray.
private struct ChatListScrollClosesTray: ViewModifier {
    let close: () -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .interacting { close() }
            }
        } else {
            content
        }
    }
}

// MARK: - Row -> chat transition

/// iOS 18+: the row is the zoom transition's source; the system shrinks the chat back
/// into the same row on the way out.
private struct ChatZoomSource: ViewModifier {
    let id: String
    let namespace: Namespace.ID
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *), ChatOpenTransitionPolicy.zoomEnabled {
            content.matchedTransitionSource(id: id, in: namespace) { source in
                source.background(UnyriColors.background)
                    .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusChatRow, style: .continuous))
            }
        } else {
            content
        }
    }
}

private struct ChatOpenDestination: ViewModifier {
    let style: ChatOpenStyle
    let sourceID: String
    let namespace: Namespace.ID
    @ViewBuilder func body(content: Content) -> some View {
        switch style {
        case .zoom:
            if #available(iOS 18.0, *) {
                content.navigationTransition(.zoom(sourceID: sourceID, in: namespace))
            } else {
                content.modifier(ChatOpenSettle(scale: true))
            }
        case .settle:
            content.modifier(ChatOpenSettle(scale: true))
        case .fade:
            content.modifier(ChatOpenSettle(scale: false))
        case .instant:
            content
        }
    }
}

/// The non-zoom open: the chat settles in from 96.5 % (or only fades in calm mode) over
/// the instant push. One-shot on the first appearance.
private struct ChatOpenSettle: ViewModifier {
    let scale: Bool
    @State private var shown = false
    @ViewBuilder func body(content: Content) -> some View {
        if scale {
            content
                .scaleEffect(shown ? 1 : 0.965, anchor: .center)
                .animation(.spring(response: 0.34, dampingFraction: 0.9), value: shown)
                .opacity(shown ? 1 : 0)
                .onAppear { reveal() }
        } else {
            content
                .opacity(shown ? 1 : 0)
                .onAppear { reveal() }
        }
    }
    private func reveal() {
        guard !shown else { return }
        withAnimation(.easeOut(duration: scale ? 0.2 : 0.18)) { shown = true }
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

/// The open chat as a navigation destination. The list re-renders on every realtime,
/// chats and messages publish; without this boundary each of those passes rebuilt the
/// chat screen (its init and its body), e.g. while a peer typed in another chat.
private struct ChatRoute: View, Equatable {
    let chat: ChatSummary
    let safety: String?
    let messageId: String?
    static func == (lhs: ChatRoute, rhs: ChatRoute) -> Bool {
        lhs.chat.chatId == rhs.chat.chatId && lhs.safety == rhs.safety && lhs.messageId == rhs.messageId
    }
    var body: some View {
        ChatScreen(chat: chat, initialSafetyAction: safety, initialMessageId: messageId)
    }
}


/// The chat push belongs to Contacts' destination, so popping it reveals the same
/// Contacts State (query, rows, scroll position). It does not replace a sibling root route.
private struct ContactsRoute: View, Equatable {
    let users: UsersRepository
    let chats: ChatsRepository
    let messages: MessagesRepository
    let realtime: RealtimeClient
    let calls: CallController
    let displayName: String
    let username: String
    @State private var openedChat: ChatSummary?
    @State private var showingChat = false
    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.users === rhs.users && lhs.chats === rhs.chats && lhs.messages === rhs.messages &&
            lhs.realtime === rhs.realtime && lhs.calls === rhs.calls &&
            lhs.displayName == rhs.displayName && lhs.username == rhs.username
    }
    var body: some View {
        ContactsView(users: users, chats: chats, realtime: realtime, calls: calls,
                     displayName: displayName, username: username, onOpenChat: { chat in
            guard !showingChat else { return }
            openedChat = chat
            messages.preloadChat(chat.chatId, firstWindow: true)
            showingChat = true
        }).equatable()
            .navigationDestination(isPresented: $showingChat) {
                if let openedChat { ChatRoute(chat: openedChat, safety: nil, messageId: nil).equatable() }
            }
            .onChange(of: showingChat) { shown in
                chats.navigationChatId = shown ? openedChat?.chatId : nil
                if !shown { chats.notificationHeaderHeight = 44 }
            }
    }
}

/// The navigation bar centres a principal item between the leading and trailing items. With
/// the two-button capsule on the right and one button on the left, "В сети" sat about 25 pt
/// left of the screen's centre (owner, 14 Nov). This shifts it to the window's centre. The
/// frame is measured before the offset (so the shift never feeds back), only when layout
/// changes: no per-frame work.
struct UnyriWindowCentered: ViewModifier {
    @State private var shift: CGFloat = 0
    func body(content: Content) -> some View {
        // 14 Nov (owner: "в сети с пингом оно смещается"): the reader sits outside the offset, so it
        // measures the unshifted layout frame. Inside, it saw its own shift and toggled the
        // label back and forth on every ping update.
        content
            .offset(x: shift)
            .background(GeometryReader { proxy in
                Color.clear
                    .onAppear { update(proxy.frame(in: .global)) }
                    .onChange(of: proxy.frame(in: .global)) { update($0) }
            })
    }
    private func update(_ frame: CGRect) {
        let window = UIApplication.shared.connectedScenes.lazy
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        guard let width = window?.bounds.width, width > 0, frame.width > 0 else { return }
        // Never push the item under the bar's buttons: at most 40 pt either way.
        let wanted = max(-40, min(40, (width / 2 - frame.midX).rounded()))
        if abs(wanted - shift) >= 1 { shift = wanted }
    }
}



/// Native offset memory; only entry/cancel/keyboard completion touch it.
/// No observed scrolling state, display link or SwiftUI work during scrolling.
@MainActor private final class ChatSearchScrollMemory {
    weak var scroll: UIScrollView?
    private var offset: CGPoint?
    private var restoring = false
    private var keyboardVisible = false
    private var observers: [NSObjectProtocol] = []
    init() {
        observers.append(NotificationCenter.default.addObserver(forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.keyboardVisible = true }
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.keyboardVisible = false
                if self.restoring { self.apply(); self.restoring = false }
            }
        })
    }
    deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    func capture() {
        restoring = false; offset = scroll?.contentOffset
        if let scroll, let offset { scroll.setContentOffset(offset, animated: false) }
    }
    func clear() { offset = nil; restoring = false }
    func restore() {
        guard offset != nil else { return }
        restoring = true
        DispatchQueue.main.async { [weak self] in
            guard let self, self.restoring else { return }
            self.apply()
            if !self.keyboardVisible { self.restoring = false }
        }
    }
    private func apply() {
        guard let scroll, let offset else { return }
        scroll.layoutIfNeeded()
        scroll.setContentOffset(offset, animated: false)
    }
}

private struct ChatSearchScrollAnchor: UIViewRepresentable {
    let memory: ChatSearchScrollMemory
    func makeUIView(context: Context) -> Anchor { Anchor(memory: memory) }
    func updateUIView(_ view: Anchor, context: Context) {}
    final class Anchor: UIView {
        let memory: ChatSearchScrollMemory
        init(memory: ChatSearchScrollMemory) { self.memory = memory; super.init(frame: .zero); isUserInteractionEnabled = false }
        required init?(coder: NSCoder) { nil }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            var parent = superview
            while let candidate = parent {
                if let scroll = candidate as? UIScrollView { memory.scroll = scroll; break }
                parent = candidate.superview
            }
        }
    }
}
