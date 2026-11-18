import SwiftUI
import Combine

private struct ContactsRenderedItem: Identifiable, Equatable, Sendable {
    let id: String
    let user: PublicUser?
    let title: String?
}

/// Dependencies are plain references. Only address-book snapshots and the connection
/// Bool enter State; ping, messages, typing and other people's presence do not.
@MainActor
struct ContactsView: View, Equatable {
    nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.users === rhs.users && lhs.chats === rhs.chats && lhs.realtime === rhs.realtime && lhs.calls === rhs.calls &&
            lhs.displayName == rhs.displayName && lhs.username == rhs.username
    }
    let users: UsersRepository
    let chats: ChatsRepository
    let realtime: RealtimeClient
    let calls: CallController
    let displayName: String
    let username: String
    let onOpenChat: (ChatSummary) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var searchFocused: Bool
    @State private var query = ""
    @State private var revision: UInt64 = 0
    /// Last ContactsStore.revision applied; a replayed value is not a change.
    @State private var storeRevision: UInt64?
    @State private var entries: [ContactEntry] = []
    @State private var contactIDs = Set<String>()
    @State private var phase = ContactsStore.Phase.idle
    @State private var connected = false
    @State private var listItems: [ContactsRenderedItem] = []
    @State private var onlineIDs = Set<String>()
    @State private var classificationRevision: UInt64 = 0
    @State private var projectionRevision = 0
    @State private var lastPreparation: PreparationKey?
    @State private var renderedOnlineIDs = Set<String>()
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .footnote) private var feedbackLineHeight: CGFloat = 20
    @State private var serverResults: [PublicUser] = []
    @State private var renderedServer: [PublicUser] = []
    @State private var serverRevision = 0
    @State private var lastBlockedRevision: UInt64?
    @State private var lastRemoteKey: RemoteKey?
    @State private var retry = 0
    @State private var loading = false
    @State private var searched = false
    @State private var serverError: String?
    @State private var nextOffset: Int64?
    @State private var failedPageOffset: Int64?
    @State private var epoch = UUID()
    @State private var mutationFailed = false
    @State private var opening = false
    @State private var openingFailed = false
    @State private var openingEpoch = UUID()
    @State private var openingTask: Task<Void, Never>?
    @State private var cachedChats: [ChatSummary] = []
    @State private var showingQR = false
    @State private var showingAdd = false
    @State private var publicIdentity: OwnerIdentity?
    @State private var lastContactChange: UUID?
    @State private var visible = false
    @StateObject private var hub: ContactsPresenceHub

    init(users: UsersRepository, chats: ChatsRepository, realtime: RealtimeClient, calls: CallController,
         displayName: String, username: String, onOpenChat: @escaping (ChatSummary) -> Void) {
        self.users = users; self.chats = chats; self.realtime = realtime; self.calls = calls
        self.displayName = displayName; self.username = username; self.onOpenChat = onOpenChat
        _hub = StateObject(wrappedValue: ContactsPresenceHub(realtime: realtime))
    }

    private struct PreparationKey: Hashable, Sendable {
        let query: String
        let revision: UInt64
        let classification: UInt64
    }
    private struct ServerPreparationKey: Hashable, Sendable {
        let revision: UInt64
        let serverRevision: Int
        let query: String
    }
    private struct RemoteKey: Hashable, Sendable {
        let query: String
        let connected: Bool
        let retry: Int
    }
    private var preparationKey: PreparationKey { .init(query: query, revision: revision, classification: classificationRevision) }
    private var remoteKey: RemoteKey { .init(query: ContactsPresentation.query(query), connected: connected, retry: retry) }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                actions
                if !connected { note("No connection", symbol: "wifi.slash") }
                if mutationFailed { note("Could not update contacts. Check your connection and try again.", symbol: "exclamationmark.bubble") }
                if openingFailed { note("Could not open the conversation. Your search results are still here. Try tapping the person again.", symbol: "exclamationmark.bubble") }
                if entries.isEmpty && phase == .loading { ProgressView().frame(maxWidth: .infinity, minHeight: 76) }
                if phase == .failed && connected {
                    note("Could not load contacts.", symbol: "exclamationmark.bubble")
                    Button { Task { @MainActor in await users.contacts.reload() } } label: {
                        Text("Retry").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }.accessibilityIdentifier("contacts-retry")
                }
                if entries.isEmpty && phase == .loaded {
                    note("Your contacts will appear here. Add someone by public number or @username.", symbol: "person.crop.circle.badge.plus")
                } else if listItems.isEmpty && !query.isEmpty {
                    note("No results.", symbol: "magnifyingglass")
                }
                // One flat ForEach keeps a person's SwiftUI identity when it moves sections.
                ForEach(listItems) { item in
                    if let user = item.user { person(user, contact: true) }
                    else if let title = item.title {
                        sectionHeader(title == "online" ? UnyriL10n.string("Online") : title)
                            .accessibilityIdentifier("contacts-section-" + title)
                    }
                }
                if ContactsPresentation.query(query).count >= 3 { serverSection }
                else if !query.isEmpty {
                    note("People you have chats with. Type 3 or more characters to search everyone on your server.", symbol: "magnifyingglass")
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .background(ContactsProjectionBridge(revision: projectionRevision, hub: hub).frame(width: 0, height: 0))
        .onChange(of: projectionRevision) { revision in
            if let anchor = hub.anchorNeedingMaterialization(revision: revision) {
                proxy.scrollTo(ContactsPresentation.personID(anchor.id), anchor: .top)
            }
            hub.requestAnchorRestoration()
        }
        .scrollDismissesKeyboard(.interactively)
        .accessibilityIdentifier("contacts-list")
        .safeAreaInset(edge: .top, spacing: 0) { searchField.padding(.horizontal, 16).padding(.vertical, 8) }
        .navigationTitle("Contacts").navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            UnyriBackToolbarItem(action: { dismiss() }, label: "Back to chats", identifier: "contacts-back")
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { UnyriHaptics.press(); showingAdd = true } label: {
                    Image(systemName: "person.crop.circle.badge.plus").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Add contact by number or @username")
                .accessibilityIdentifier("contacts-add-identity")
            }
            ToolbarItem(placement: .principal) {
                ContactsConnectionTitle(realtime: realtime, routeAvailable: connected)
            }
        }
        .unyriScreenBackground()
        .performanceScreen(.search)
        .unyriBlocksRootDrawer()
        // Every handler below must be idempotent. SwiftUI may cancel and resubscribe
        // onReceive on any body update, and @Published / CurrentValueSubject replay
        // their current value at once. An unconditional bump there is a synchronous
        // update loop: 18 Nov, iOS 26.5 simulator, 100 % CPU on opening Contacts.
        .onReceive(users.contacts.$entries.removeDuplicates()) { values in
            guard values != entries else { return }
            entries = values; revision &+= 1
            hub.setContacts(Set(values.map(\.id)))
        }
        .onReceive(users.contacts.$ids.removeDuplicates()) { ids in
            guard ids != contactIDs else { return }
            contactIDs = ids; serverRevision += 1
        }
        .onReceive(users.contacts.$revision.removeDuplicates()) { value in
            guard value != storeRevision else { return }
            storeRevision = value; revision &+= 1
        }
        .onReceive(users.contacts.$phase.removeDuplicates()) { value in if phase != value { phase = value } }
        .onReceive(users.$blockedAccountsRevision.removeDuplicates()) { value in
            guard lastBlockedRevision != value else { return }
            if lastBlockedRevision != nil {
                epoch = UUID(); serverResults = []; renderedServer = []; serverRevision += 1
                lastRemoteKey = nil; retry += 1
            }
            lastBlockedRevision = value
        }
        // REST directory availability is independent of the WebSocket transport.
        .onReceive(UnyriMediaNetwork.shared.$connected.removeDuplicates()) { value in if connected != value { connected = value } }
        .onReceive(hub.classificationChanges.removeDuplicates()) { ids in
            if onlineIDs != ids { onlineIDs = ids; classificationRevision &+= 1 }
        }
        .task(id: preparationKey) { await prepare() }
        .task(id: ServerPreparationKey(revision: revision, serverRevision: serverRevision, query: query)) { await prepareServer() }
        .task(id: remoteKey) { await searchServer() }
        .task { _ = try? await users.getIdentity() }
        .task {
            cachedChats = await chats.loadCachedChatsAsync()
            hub.blocked = Set(cachedChats.filter { $0.isBlocked == true }.compactMap { $0.otherUser?.id })
            await users.contacts.ensureLoaded()
        }
        .onAppear { visible = true; users.contacts.listAppeared(); hub.setEnabled(scenePhase == .active && !showingQR && !showingAdd) }
        .onDisappear {
            visible = false; users.contacts.listDisappeared(); hub.setEnabled(false)
            cancelOpening()
        }
        .onChange(of: query) { _ in cancelOpening() }
        .onChange(of: scenePhase) { hub.setEnabled(visible && $0 == .active && !showingQR && !showingAdd) }
        .onChange(of: showingQR) { hub.setEnabled(visible && scenePhase == .active && !$0 && !showingAdd) }
        .onChange(of: showingAdd) { hub.setEnabled(visible && scenePhase == .active && !$0 && !showingQR) }
        .onReceive(users.$ownIdentity.removeDuplicates()) { value in if publicIdentity != value { publicIdentity = value } }
        .onReceive(NotificationCenter.default.publisher(for: .login2ContactChanged)) { notification in
            guard let change = notification.object as? UUID, change != lastContactChange,
                  notification.userInfo?["owner"] as? String == users.blockedAccountOwnerID else { return }
            lastContactChange = change
            users.contacts.markStale()
            Task { @MainActor in await users.contacts.ensureLoaded() }
        }
        .sheet(isPresented: $showingQR) { UnyriProfileQRSheet(users: users, displayName: displayName) }
        .sheet(isPresented: $showingAdd) { ContactsAddByIdentity(users: users) }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(UnyriColors.secondaryText).accessibilityHidden(true)
            TextField("Search contacts", text: $query).focused($searchFocused)
                .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.search)
                .accessibilityIdentifier("contacts-query")
                .onSubmit { searchFocused = false }
                .onChange(of: query) { if $0.count > 256 { query = String($0.prefix(256)) } }
            if !query.isEmpty {
                Button { query = ""; searchFocused = true } label: {
                    Image(systemName: "xmark").frame(width: 44, height: 44)
                }.buttonStyle(.plain).accessibilityLabel("Clear search").accessibilityIdentifier("contacts-clear-query")
            }
        }
        .padding(.leading, 16).padding(.trailing, query.isEmpty ? 16 : 0).frame(minHeight: 48)
        .foregroundStyle(UnyriColors.primaryText).modifier(UnyriDrawerGlass())
        // Contain, then name the group: an identifier on a plain container replaces its
        // children's (the field was found only as "contacts-search", never "contacts-query").
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("contacts-search")
    }

    private var actions: some View {
        VStack(spacing: 0) {
            Button { UnyriHaptics.press(); showingQR = true } label: {
                actionLabel("My QR code", symbol: "qrcode")
            }.accessibilityIdentifier("contacts-qr")
            if let link = publicIdentity?.link {
                ShareLink(item: link) { actionLabel("Invite to UNYRI", symbol: "square.and.arrow.up") }
                    .accessibilityIdentifier("contacts-invite")
            }
        }.buttonStyle(.plain).padding(.bottom, 12)
    }
    private func actionLabel(_ title: LocalizedStringKey, symbol: String) -> some View {
        Label(title, systemImage: symbol).font(.body)
            .foregroundStyle(UnyriColors.primaryText).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .contentShape(Rectangle())
    }
    private func note(_ key: String, symbol: String) -> some View {
        Label(UnyriL10n.string(key), systemImage: symbol).font(.footnote).foregroundStyle(UnyriColors.secondaryText)
            .fixedSize(horizontal: false, vertical: true).padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
    private func sectionHeader(_ title: String) -> some View {
        Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.secondaryText)
            .padding(.top, 14).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
    }

    private func person(_ user: PublicUser, contact: Bool) -> some View {
        // Owner, 14 Nov: a person found on the server is added with a button on the row
        // ("в контактах нету кнопки напротив человека"); no separate add row and sheet.
        HStack(spacing: 4) {
            Button { openPerson(user) } label: {
                ContactsPersonRow(user: user, query: query, users: users, presence: hub.cell(for: user)).equatable()
            }
            .buttonStyle(.plain).disabled(opening)
            if !contact {
                Button { UnyriHaptics.press(); changeContact(user, adding: true) } label: {
                    Image(systemName: "person.crop.circle.badge.plus").font(.system(size: 20))
                        .foregroundStyle(UnyriColors.primaryText).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).disabled(opening)
                .accessibilityLabel(Text(UnyriL10n.string("Add to contacts")))
                .accessibilityIdentifier("contacts-add-" + user.id)
            }
        }
        .modifier(ContactsRowVisibility(id: user.id, hub: hub))
        .accessibilityIdentifier("contacts-person-" + user.id)
        .contextMenu {
            Button { openPerson(user) } label: { Label("Write message", systemImage: "message") }
            Button { call(user, video: false) } label: { Label("Call", systemImage: "phone") }
            Button { call(user, video: true) } label: { Label("Video call", systemImage: "video") }
            Button { changeContact(user, adding: !contact) } label: {
                Label(UnyriL10n.string(contact ? "Remove from contacts" : "Add to contacts"),
                      systemImage: contact ? "person.crop.circle.badge.minus" : "person.crop.circle.badge.plus")
            }
        }
        .transition(reduceMotion ? .identity : .opacity.combined(with: .offset(y: 4)))
    }

    @ViewBuilder private var serverSection: some View {
        sectionHeader(UnyriL10n.string("Find on server"))
            .accessibilityIdentifier("contacts-server")
        note("Search by name or @username. This query is sent to your server.", symbol: "network")
        // One reserved feedback line: loading/error/empty changes cannot move the rows.
        HStack {
            if loading { ProgressView().controlSize(.small).accessibilityLabel("Searching") }
            else if let serverError {
                Text(serverError).font(.footnote).lineLimit(dynamicTypeSize.isAccessibilitySize ? 4 : 2)
                Button { Task { @MainActor in await retryServer() } } label: {
                    Text("Retry").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }.disabled(loading || !connected).accessibilityIdentifier("contacts-server-retry")
            } else if !connected { Text("Connect to search for people on this server.").font(.footnote) }
            else if searched && renderedServer.isEmpty { Text("No users found.").font(.footnote) }
            Spacer(minLength: 0)
        }.foregroundStyle(UnyriColors.secondaryText).frame(height: max(56, feedbackLineHeight * (dynamicTypeSize.isAccessibilitySize ? 4 : 2) + 8))
        ForEach(renderedServer) { user in person(user, contact: false) }
        if nextOffset != nil && connected && serverError == nil {
            Button { Task { @MainActor in await loadServer(reset: false) } } label: {
                Text("Load more").frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }.disabled(loading).accessibilityIdentifier("contacts-server-more")
        }
    }

    private func prepare() async {
        let key = preparationKey
        let people = entries.map(\.user)
        let knownOnline = onlineIDs
        let term = query
        let prepared = await Task.detached(priority: .userInitiated) {
            let values = people.map { ContactListPerson(id: $0.id, name: $0.visibleName, username: $0.username,
                                                       online: knownOnline.contains($0.id)) }
            let byID = Dictionary(people.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            return ContactsPresentation.sections(values, query: term).flatMap { section -> [ContactsRenderedItem] in
                [ContactsRenderedItem(id: ContactsPresentation.sectionID(section.id), user: nil, title: section.id)] +
                    section.people.compactMap { item in
                        byID[item.id].map { ContactsRenderedItem(id: ContactsPresentation.personID(item.id), user: $0, title: nil) }
                    }
            }
        }.value
        guard !Task.isCancelled, key == preparationKey else { return }
        let classificationOnly = lastPreparation.map { $0.query == key.query && $0.revision == key.revision } ?? false
        if prepared != listItems {
            let nextRevision = projectionRevision &+ 1
            if classificationOnly {
                hub.captureScrollAnchor(revision: nextRevision, excluding: renderedOnlineIDs.symmetricDifference(knownOnline))
            } else { hub.clearScrollAnchor() }
            var transaction = Transaction(animation: reduceMotion || classificationOnly ? nil : .easeOut(duration: 0.2))
            transaction.disablesAnimations = reduceMotion || classificationOnly
            withTransaction(transaction) {
                listItems = prepared
                projectionRevision = nextRevision
            }
        }
        lastPreparation = key
        renderedOnlineIDs = knownOnline
    }

    private func prepareServer() async {
        let version = serverRevision
        let bookRevision = revision
        let term = query
        let server = serverResults
        let contacts = contactIDs.union(entries.map(\.id))
        let ids = server.map(\.id)
        let filtered = await Task.detached(priority: .userInitiated) {
            ContactsPresentation.serverIDs(ids, excluding: contacts)
        }.value
        guard !Task.isCancelled, version == serverRevision, bookRevision == revision, term == query else { return }
        let byID = Dictionary(server.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let prepared = filtered.compactMap { byID[$0] }
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
            if renderedServer != prepared { renderedServer = prepared }
        }
    }

    private func searchServer() async {
        let key = remoteKey
        let sameQuery = lastRemoteKey.map { $0.query == key.query && $0.retry == key.retry } ?? false
        if sameQuery && (searched || serverError != nil || !key.connected) {
            if !key.connected { epoch = UUID(); loading = false }
            lastRemoteKey = key
            return
        }
        lastRemoteKey = key
        epoch = UUID(); loading = false; searched = false; serverError = nil; nextOffset = nil; failedPageOffset = nil
        serverResults = []; serverRevision += 1
        guard key.query.count >= 3 else { return }
        let saved = await users.cachedDirectory(query: key.query)
        guard !Task.isCancelled, key == remoteKey else { return }
        serverResults = saved; serverRevision += 1
        guard key.connected else { return }
        do { try await Task.sleep(nanoseconds: 300_000_000) } catch { return }
        guard !Task.isCancelled else { return }
        await loadServer(reset: true)
    }

    private func retryServer() async {
        guard !loading else { return }
        await loadServer(reset: ContactsDirectoryPolicy.retryResets(failedOffset: failedPageOffset))
    }

    /// Same directory request, paging, privacy fencing and failure wording as People.
    private func loadServer(reset: Bool) async {
        guard connected, remoteKey.query.count >= 3, !loading || reset else { return }
        let key = remoteKey
        let ticket = epoch
        let offset = reset ? Int64(0) : (failedPageOffset ?? nextOffset ?? 0)
        loading = true; serverError = nil
        defer { if ticket == epoch && key == remoteKey { loading = false } }
        do {
            let response = try await users.directory(query: key.query, limit: 30, offset: offset)
            guard !Task.isCancelled, ticket == epoch, key == remoteKey else { return }
            var seen = Set<String>()
            serverResults = ((reset ? [] : serverResults) + response.items).filter { seen.insert($0.id).inserted }
            nextOffset = response.nextOffset.flatMap { $0 > offset ? $0 : nil }
            searched = true; failedPageOffset = nil; serverRevision += 1
        } catch {
            guard !Task.isCancelled, ticket == epoch, key == remoteKey else { return }
            failedPageOffset = offset
            if case APIError.http(404) = error {
                serverResults = []; nextOffset = nil; failedPageOffset = 0
                serverError = UnyriL10n.string("Server directory is unavailable. Update your server to use Contacts.")
            } else if case APIError.server(let code, _) = error, ["not_found", "route_not_found"].contains(code) {
                serverResults = []; nextOffset = nil; failedPageOffset = 0
                serverError = UnyriL10n.string("Server directory is unavailable. Update your server to use Contacts.")
            } else {
                serverError = (error as? APIError)?.errorDescription ?? error.localizedDescription
                if case APIError.http(let status) = error, [401, 403].contains(status) { serverResults = []; nextOffset = nil; failedPageOffset = 0 }
                if case APIError.server(let code, _) = error,
                   ["unauthorized", "forbidden", "session_revoked", "invalid_access_token", "account_restricted"].contains(code) {
                    serverResults = []; nextOffset = nil; failedPageOffset = 0
                }
            }
            serverRevision += 1
        }
    }

    private func openPerson(_ user: PublicUser) {
        guard !opening else { return }
        UnyriHaptics.press(); searchFocused = false; openingFailed = false
        if let existing = cachedChats.first(where: { $0.otherUser?.id == user.id }) { onOpenChat(existing); return }
        opening = true
        let ticket = UUID(); openingEpoch = ticket
        openingTask = Task { @MainActor in
            defer { if ticket == openingEpoch { opening = false } }
            do {
                let chat = try await chats.createOrOpenDirectChat(otherUserId: user.id)
                guard !Task.isCancelled, ticket == openingEpoch else { return }
                onOpenChat(chat)
            } catch { if !Task.isCancelled, ticket == openingEpoch { openingFailed = true } }
        }
    }
    private func cancelOpening() {
        openingEpoch = UUID(); openingTask?.cancel(); openingTask = nil; opening = false; openingFailed = false
    }
    private func call(_ user: PublicUser, video: Bool) {
        searchFocused = false; UnyriHaptics.press()
        Task { @MainActor in await calls.startCall(remoteUserId: user.id, remoteDisplayName: user.displayName, mediaKind: video ? .video : .audio) }
    }
    private func changeContact(_ user: PublicUser, adding: Bool) {
        mutationFailed = false
        Task { @MainActor in
            if await users.contacts.set(user, contact: adding) {
                UnyriHaptics.selection()
                UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string(adding ? "Contact added" : "Contact removed"))
            } else if !Task.isCancelled { mutationFailed = true }
        }
    }
}

@MainActor
private struct ContactsPersonRow: View, Equatable {
    nonisolated static func == (lhs: Self, rhs: Self) -> Bool { lhs.user == rhs.user && lhs.query == rhs.query }
    let user: PublicUser
    let query: String
    let users: UsersRepository
    @ObservedObject var presence: ContactPresenceCell
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .caption) private var statusLineHeight: CGFloat = 18
    @State private var avatarURL: URL?
    var body: some View {
        let display = presence.presentation
        let accessibility = dynamicTypeSize.isAccessibilitySize
        HStack(alignment: accessibility ? .top : .center, spacing: 12) {
            UserAvatar(url: avatarURL ?? users.cachedAvatarURL(id: user.id, avatarUrl: user.avatarUrl, avatarVersion: user.avatarVersion),
                       name: user.visibleName, size: 44)
                .overlay(alignment: .bottomTrailing) {
                    UnyriPresenceDot(state: !display.isLive ? .unknown : display.online ? .online : .offline, diameter: 9,
                                     color: UnyriColors.online, cutout: UnyriColors.background)
                        .accessibilityHidden(true)
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(highlight(user.visibleName)).font(.body.weight(.medium)).foregroundStyle(UnyriColors.primaryText)
                    .lineLimit(accessibility ? nil : 1).fixedSize(horizontal: false, vertical: true)
                Text(highlight(user.publicIdentityLabel.isEmpty ? " " : user.publicIdentityLabel)).font(.caption).foregroundStyle(UnyriColors.secondaryText)
                    .lineLimit(accessibility ? nil : 1).fixedSize(horizontal: false, vertical: true)
                // Names/handles are known before rendering. Only status changes later:
                // reserve all six accessibility lines, even for an unknown/short value.
                Text(display.label.isEmpty ? " " : display.label).font(.caption)
                    .foregroundStyle(display.online ? UnyriColors.online : UnyriColors.secondaryText)
                    .lineLimit(ContactsRowPolicy.statusLines(accessibility: accessibility))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: CGFloat(ContactsRowPolicy.statusHeight(lineHeight: Double(statusLineHeight),
                                                                         accessibility: accessibility)), alignment: .topLeading)
            }
            Spacer(minLength: 0)
        }.padding(.vertical, 10).frame(minHeight: 76).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            .accessibilityElement(children: .combine)
            .task(id: user.lastSeenAt) { presence.seed(lastSeen: user.lastSeenAt) }
            .task(id: "\(user.id)|\(user.avatarVersion)|\(user.avatarUrl ?? "")") {
                avatarURL = await users.avatarURL(id: user.id, avatarUrl: user.avatarUrl, avatarVersion: user.avatarVersion)
            }
    }
    private func highlight(_ source: String) -> AttributedString {
        var value = AttributedString(source)
        let term = ContactsPresentation.query(query)
        if !term.isEmpty, let match = value.range(of: term, options: [.caseInsensitive, .diacriticInsensitive]) {
            value[match].foregroundColor = UnyriColors.accent
            value[match].inlinePresentationIntent = .stronglyEmphasized
        }
        return value
    }
}

/// A narrow connection island. Only connectivity and the opted-in ping update it.
@MainActor
private struct ContactsConnectionTitle: View {
    let realtime: RealtimeClient
    let routeAvailable: Bool
    @State private var socketConnected = false
    private var connected: Bool { routeAvailable && socketConnected }
    @AppStorage("cocoConnectionPing") private var showPing = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var ping: Int?
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(connected ? UnyriColors.online : UnyriColors.secondaryText).frame(width: 5, height: 5)
            Text(UnyriL10n.string(connected ? "Connection online" : routeAvailable ? "Connecting…" : "No connection")).font(.system(size: 13))
            if showPing && connected {
                Text((ping.map(String.init) ?? "—") + " " + UnyriL10n.string("ms"))
                    .font(.system(size: 10).monospacedDigit()).foregroundStyle(UnyriColors.secondaryText)
            }
        }
        .accessibilityElement(children: .combine)
        .onReceive(realtime.$state.map { $0 == .connected }.removeDuplicates()) { value in
            if socketConnected != value { socketConnected = value }
        }
        .task(id: "\(connected)|\(showPing)|\(scenePhase == .active)") {
            ping = nil
            guard connected, showPing, scenePhase == .active else { return }
            while !Task.isCancelled {
                ping = await realtime.measureDisplayPing()
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            }
        }
    }
}

@MainActor
private struct ContactsAddByIdentity: View {
    let users: UsersRepository
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var busy = false
    @State private var failure: String?
    @State private var profile: DiscoveredProfile?
    @State private var link: PublicIdentityPolicy.Link?
    @State private var openedLink: PublicIdentityPolicy.Link?
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        NavigationStack {
            UnyriSettingsPage("Add contact") {
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 14) {
                        TextField("Public number, @username or profile link", text: $username)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().unyriFieldStyle()
                            .accessibilityIdentifier("contacts-add-username")
                            .onChange(of: username) {
                                if $0.utf8.count > 4096 { username = String($0.prefix(4096)) }
                                profile = nil; failure = nil; link = nil
                            }
                        Text("Exact match only. Hidden, blocked and missing profiles all appear unavailable. A link does not bypass privacy.")
                            .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        Button("Find profile") { Task { @MainActor in await find() } }.disabled(busy || username.isEmpty)
                            .accessibilityIdentifier("login2-contact-find")
                    }.padding(16)
                }
                if let profile {
                    UnyriSettingsGroup {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(profile.contact.visibleName).font(.title3.bold())
                            Text(PublicIdentityPolicy.formattedNumber(profile.unyriNumber)).monospacedDigit()
                                .accessibilityIdentifier("login2-contact-number")
                            if let username = profile.publicUsername { Text("@" + username) }
                            if let bio = profile.bio { Text(bio).font(.subheadline) }
                            if profile.id != users.blockedAccountOwnerID {
                                Button("Add contact") { Task { @MainActor in await add(profile) } }.disabled(busy)
                                    .accessibilityIdentifier("contacts-add-confirm")
                            }
                        }.padding(16)
                    }
                }
                if let link {
                    Text(link.origin).font(.caption.monospaced())
                    Button("Open server profile") { openedLink = link }
                }
                if let failure { Text(failure).font(.footnote).foregroundStyle(UnyriColors.secondaryText) }
                if busy { ProgressView() }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .disabled(busy)
        }
        .sheet(item: $openedLink) { IdentityLinkView(target: $0) }
        .onChange(of: scenePhase) { if $0 != .active { profile = nil } }
    }
    private func find() async {
        busy = true; failure = nil; defer { busy = false }
        profile = nil; link = nil
        let input = username
        if input.lowercased().hasPrefix("unyri:") {
            if let parsed = PublicIdentityPolicy.parse(input) { link = parsed }
            else { failure = UnyriL10n.string("Invalid profile link. Use a number or @username link with an explicit server.") }
            return
        }
        guard let target = PublicIdentityPolicy.input(input) else {
            failure = UnyriL10n.string("Enter a public number or a valid @username."); return
        }
        do {
            let result = try await users.lookup(target)
            guard !Task.isCancelled, input == username, scenePhase == .active else { return }
            profile = result
        } catch is CancellationError { }
        catch { failure = IdentityErrorText.message(error) }
    }
    private func add(_ profile: DiscoveredProfile) async {
        busy = true; failure = nil; defer { busy = false }
        do {
            // Recheck by immutable UUID, never by a handle that may have changed owners.
            let freshProfile = try await users.publicProfile(profile.id)
            guard scenePhase == .active, !Task.isCancelled else { return }
            if await users.contacts.set(freshProfile.contact, contact: true) {
                UnyriHaptics.selection(); await users.contacts.ensureLoaded(); dismiss()
            }
            else { failure = UnyriL10n.string("Could not update contacts. Check your connection and try again.") }
        } catch is CancellationError { }
        catch { self.profile = nil; failure = IdentityErrorText.message(error) }
    }
}
