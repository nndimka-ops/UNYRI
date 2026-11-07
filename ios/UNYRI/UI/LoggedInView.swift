import SwiftUI
import Combine
import UIKit

struct CallIslandPresentation {
    let name: String
    let isVideo: Bool
    let connectedAt: Date?
    var status: String? = nil
    let onReturn: () -> Void
}
private struct CallIslandKey: EnvironmentKey {
    static let defaultValue: CallIslandPresentation? = nil
}
extension EnvironmentValues {
    var callIsland: CallIslandPresentation? {
        get { self[CallIslandKey.self] }
        set { self[CallIslandKey.self] = newValue }
    }
}
struct CallIslandTitle: View {
    let presentation: CallIslandPresentation
    var body: some View {
        ActiveCallBanner(name: presentation.name, isVideo: presentation.isVideo,
            connectedAt: presentation.connectedAt, status: presentation.status, onReturn: presentation.onReturn)
    }
}
struct CallIslandToolbar: ViewModifier {
    @Environment(\.callIsland) private var island
    func body(content: Content) -> some View {
        content.toolbar {
            if let island {
                ToolbarItem(placement: .principal) { CallIslandTitle(presentation: island) }
            }
        }
    }
}

/// Reserve a real row above nested TabView/NavigationStack destinations.
/// An outer safeAreaInset can leave their navigation bars at the old origin.
struct CallReturnPresentation: ViewModifier {
    let presentation: CallIslandPresentation?
    func body(content: Content) -> some View {
        VStack(spacing: 0) {
            if let presentation {
                ActiveCallBanner(name: presentation.name, isVideo: presentation.isVideo,
                    connectedAt: presentation.connectedAt, status: presentation.status, onReturn: presentation.onReturn)
            }
            content
        }
    }
}

/// Authenticated tabs and the shared call/message presentation.
struct UnyriRootDockHiddenKey: PreferenceKey {
    static var defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

private struct UnyriOpenMenuKey: EnvironmentKey { static let defaultValue: () -> Void = {} }
private struct UnyriSavedRequestKey: EnvironmentKey { static let defaultValue: UUID? = nil }
private struct UnyriSearchRequestKey: EnvironmentKey { static let defaultValue: SearchNavigationRequest? = nil }
private struct UnyriMenuVisibilityKey: EnvironmentKey { static let defaultValue: (Bool) -> Void = { _ in } }
private struct UnyriDrawerBlockerScope {
    var update: (UUID, Bool) -> Void = { _, _ in }
}
private struct UnyriDrawerBlockerKey: EnvironmentKey { static let defaultValue = UnyriDrawerBlockerScope() }
extension EnvironmentValues {
    fileprivate var unyriDrawerBlockerScope: UnyriDrawerBlockerScope {
        get { self[UnyriDrawerBlockerKey.self] }
        set { self[UnyriDrawerBlockerKey.self] = newValue }
    }
    var unyriMenuVisibility: (Bool) -> Void { get { self[UnyriMenuVisibilityKey.self] } set { self[UnyriMenuVisibilityKey.self] = newValue } }
    var unyriOpenMenu: () -> Void { get { self[UnyriOpenMenuKey.self] } set { self[UnyriOpenMenuKey.self] = newValue } }
    var unyriSavedRequest: UUID? { get { self[UnyriSavedRequestKey.self] } set { self[UnyriSavedRequestKey.self] = newValue } }
    var unyriSearchRequest: SearchNavigationRequest? { get { self[UnyriSearchRequestKey.self] } set { self[UnyriSearchRequestKey.self] = newValue } }
}

/// A child reports only its own lifetime. Disposing an old child cannot clear
/// a successor's modal/form blocker, nor can an offscreen tab block another root.
private struct UnyriRootDrawerBlock: ViewModifier {
    let blocked: Bool
    @State private var token = UUID()
    @Environment(\.unyriDrawerBlockerScope) private var scope
    func body(content: Content) -> some View {
        content.onAppear { scope.update(token, blocked) }
            .onChange(of: blocked) { scope.update(token, $0) }
            .onDisappear { scope.update(token, false) }
    }
}
extension View {
    func unyriBlocksRootDrawer(_ blocked: Bool = true) -> some View { modifier(UnyriRootDrawerBlock(blocked: blocked)) }
}
struct UnyriMenuButton: View {
    @Environment(\.unyriOpenMenu) private var open
    @Environment(\.unyriMenuVisibility) private var visibility
    var body: some View {
        Button(action: open) { Image(systemName: "line.3.horizontal").font(.system(size: 22, weight: .medium))
            .frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).foregroundStyle(UnyriColors.primaryText).accessibilityLabel("Open menu")
            .accessibilityIdentifier("main-open-menu")
            .onAppear { visibility(true) }.onDisappear { visibility(false) }
    }
}

struct LoggedInView: View {
    @State private var callMinimized = false
    @State private var callScreenVisible = false
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var pictureInPicture = CallPictureInPicture()
    @AppStorage("showCallsTab") private var showCallsTab = true
    @AppStorage("showSavedMessages") private var showSavedMessages = true
    @State private var menuVisible = false
    @State private var menuDrag: CGFloat = 0
    @State private var chatsMenuAvailable = false
    @State private var callsMenuAvailable = false
    @State private var settingsMenuAvailable = false
    @State private var roomsMenuAvailable = false
    @State private var drawerBlockers: [RootDestination: Set<UUID>] = [:]
    @State private var savedRequest: UUID?
    @State private var showingArchive = false
    @State private var showingTransfers = false
    @State private var transfersOwnerGeneration: UInt64?
    @State private var pendingTransferChat: String?
    @State private var sidebarAvatarURL: URL?
    @State private var sidebarAvatarOwner: String?
    @State private var searchRequest: SearchNavigationRequest?
    @State private var settingsRequest: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriSidebarStyle) private var sidebarStyle
    @EnvironmentObject private var auth: AuthRepository
    @State private var dockHidden = false
    @State private var selectedTab = RootDestination.chats
    @EnvironmentObject private var callController: CallController
    @EnvironmentObject private var conference: ConferenceController
    @EnvironmentObject private var callKit: CallKitCoordinator
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @EnvironmentObject private var bannerController: ForegroundMessageBannerController

    private func setMenu(_ requested: Bool) {
        let visible = requested && drawerPanContext.allowsOpening
        if visible { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        if visible && !menuVisible { UnyriHaptics.selection() }
        withAnimation(reduceMotion ? nil : .easeOut(duration: Double(sidebarStyle.behaviorDuration) / 1000)) {
            menuDrag = 0
            menuVisible = visible
        }
    }

    private var drawerPanContext: UnyriDrawerPanPolicy.Context {
        UnyriDrawerPanPolicy.Context(generation: auth.sessionGeneration, root: String(describing: selectedTab),
            active: scenePhase == .active && !showingTransfers && !callScreenVisible && !callActive.wrappedValue && !conferenceForeground,
            menuVisible: menuVisible, canOpen: !dockHidden && rootMenuAvailable && !rootDrawerBlocked)
    }

    private var conferenceForeground: Bool { conference.showLobby || (conference.isBusy && !conference.minimized) }
    private var rootDrawerBlocked: Bool { !(drawerBlockers[selectedTab] ?? []).isEmpty }
    private func drawerScope(_ root: RootDestination) -> UnyriDrawerBlockerScope {
        UnyriDrawerBlockerScope(update: { token, blocked in
            if blocked { drawerBlockers[root, default: []].insert(token) }
            else { drawerBlockers[root]?.remove(token) }
        })
    }

    private func finishMenuPan(_ session: UnyriDrawerPanPolicy.Session, translation: Double, velocity: Double, width: Double) {
        // UIKit callbacks can outlive the root, call or account which admitted
        // the drag. A late release may never reopen a successor screen's menu.
        let target = session.finish(current: drawerPanContext, width: width, translation: translation, velocity: velocity)
        setMenu(target ?? menuVisible)
    }

    private func openSettings(_ route: String) {
        selectedTab = .settings
        settingsRequest = route
    }

    private func performSidebarItem(_ id: String) {
        UnyriHaptics.selection()
        setMenu(false)
        switch id {
        case "chats": showingArchive = false; selectedTab = .chats; searchRequest = SearchNavigationRequest(.chats)
        case "archive": selectedTab = .chats; showingArchive = true
        case "transfers":
            transfersOwnerGeneration = auth.sessionGeneration
            pendingTransferChat = nil; showingTransfers = true
        case "calls": selectedTab = .calls
        case "rooms":
            selectedTab = .rooms
        case "saved": UnyriSavedRouteQA.record(1); showingArchive = false; selectedTab = .chats; savedRequest = UUID()
        case "settings": selectedTab = .settings
        case "server": openSettings("server")
        case "devices": openSettings("devices")
        case "help": openSettings("help")
        case "report": openSettings("feedback")
        default: break
        }
    }

    private func performSidebarSearch(_ destination: SearchEntryPoint) {
        UnyriHaptics.selection(); setMenu(false); showingArchive = false; selectedTab = .chats
        searchRequest = SearchNavigationRequest(destination)
    }

    private func performSidebarSettings(_ route: String) {
        UnyriHaptics.selection(); setMenu(false); openSettings(route)
    }

    private var navigationDrawer: some View {
        GeometryReader { geometry in
            let width = sidebarStyle.metrics(screenWidth: geometry.size.width).panelWidth
            let offset = menuVisible ? max(-width, min(0, menuDrag)) : min(width, max(0, menuDrag)) - width
            let progress = max(0, min(1, 1 + offset / max(1, width)))
            ZStack(alignment: .leading) {
                if menuVisible || menuDrag > 0 {
                    Color.black.opacity(Double(sidebarStyle.behaviorScrim / 100) * progress)
                        .ignoresSafeArea().onTapGesture { setMenu(false) }
                        .accessibilityLabel("Close menu").accessibilityIdentifier("drawer-backdrop")
                        .accessibilityAddTraits(.isButton).transition(.opacity)
                    let profile = (usersRepository.ownProfile ?? usersRepository.cachedMe()).flatMap { cached in
                        cached.id.lowercased() == auth.currentUser?.id.lowercased() ? cached : nil
                    }
                    UnyriSavedSidebarView(screenWidth: geometry.size.width, availableHeight: geometry.size.height,
                        displayName: profile?.displayName ?? auth.currentUser?.displayName ?? "UNYRI",
                        username: profile?.username ?? auth.currentUser?.username,
                        avatarURL: profile.flatMap { value in
                            guard value.avatarUrl?.isEmpty == false else { return nil }
                            return usersRepository.cachedAvatarURL(id: value.id, avatarUrl: value.avatarUrl, avatarVersion: value.avatarVersion)
                                ?? (sidebarAvatarOwner == auth.currentUser?.id ? sidebarAvatarURL : nil)
                        },
                        selectedID: selectedTab == .chats && showingArchive ? "archive" : String(describing: selectedTab), unreadCount: chatsRepository.unreadTotal,
                        showCalls: showCallsTab, showSaved: showSavedMessages,
                        onClose: { setMenu(false) }, onEdit: { performSidebarSettings("edit") },
                        onSearch: { performSidebarSearch(.search) }, onPeople: { performSidebarSearch(.people) },
                        onLogout: { performSidebarSettings("logout") }, onItem: performSidebarItem)
                        .offset(x: offset)
                        // The modal trait belongs to this one container, not
                        // each descendant (which can exclude sibling rows).
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("main-drawer")
                        .accessibilityAddTraits(.isModal)
                        .accessibilityAction(.escape) { setMenu(false) }
                        .transition(.move(edge: .leading))
                        .task(id: "\(auth.sessionGeneration):\(profile?.avatarVersion ?? -1):\(profile?.avatarUrl ?? "")") {
                            await loadSidebarAvatar()
                        }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .transaction { if reduceMotion { $0.disablesAnimations = true } }
        }
    }

    @MainActor private func loadSidebarAvatar() async {
        let generation = auth.sessionGeneration
        guard let owner = auth.currentUser?.id else { return }
        // A cold drawer must resolve its own image; visiting Profile is not a prerequisite.
        let profile: UserProfile?
        if let cached = usersRepository.cachedMe() { profile = cached }
        else { profile = try? await usersRepository.getMe() }
        guard !Task.isCancelled, generation == auth.sessionGeneration, auth.currentUser?.id == owner,
              let profile, profile.id.lowercased() == owner.lowercased() else { return }
        let url = await usersRepository.avatarURL(id: profile.id, avatarUrl: profile.avatarUrl, avatarVersion: profile.avatarVersion)
        guard !Task.isCancelled, generation == auth.sessionGeneration, auth.currentUser?.id == owner else { return }
        let currentProfile = usersRepository.ownProfile ?? usersRepository.cachedMe()
        guard currentProfile?.avatarVersion == profile.avatarVersion, currentProfile?.avatarUrl == profile.avatarUrl else { return }
        sidebarAvatarOwner = owner
        sidebarAvatarURL = url
    }

    private var rootMenuAvailable: Bool {
        switch selectedTab {
        case .chats: return chatsMenuAvailable
        case .calls: return callsMenuAvailable
        case .settings: return settingsMenuAvailable
        case .rooms: return roomsMenuAvailable
        default: return false
        }
    }

    var body: some View {
        ZStack {
            TabView(selection: $selectedTab) {
                ChatsListView(showingArchive: $showingArchive)
                    .environment(\.unyriMenuVisibility, { chatsMenuAvailable = $0 })
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.chats))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                    .tag(RootDestination.chats)
                    .badge(chatsRepository.unreadTotal)
                if showCallsTab {
                    CallsView(visible: selectedTab == .calls)
                        .environment(\.unyriMenuVisibility, { callsMenuAvailable = $0 })
                        .environment(\.unyriDrawerBlockerScope, drawerScope(.calls))
                        .toolbar(.hidden, for: .tabBar)
                        .tabItem { Label("Calls", systemImage: "phone") }
                        .tag(RootDestination.calls)
                }
                ConferenceDirectoryView()
                    .environment(\.unyriMenuVisibility, { roomsMenuAvailable = $0 })
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.rooms))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Rooms", systemImage: "person.3") }
                    .tag(RootDestination.rooms)
                ProfileView(quickAction: $settingsRequest)
                    .environment(\.unyriMenuVisibility, { settingsMenuAvailable = $0 })
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.settings))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Settings", systemImage: "gearshape") }
                    .tag(RootDestination.settings)
            }
            .accessibilityHidden(menuVisible || menuDrag > 0)
            .tint(colorStyle.controlTint(UnyriColors.accent))
            .toolbar(.hidden, for: .tabBar)
            .onPreferenceChange(UnyriRootDockHiddenKey.self) { dockHidden = $0 }
        }
        .onChange(of: showCallsTab) { newValue in
            // If Calls is hidden while selected, fall back to Chats — never
            // leave an invalid tab selection.
            selectedTab = RootNavigation.fallbackSelected(selectedTab, showCalls: newValue, showSearch: false)
        }
        .background(CallPipSource(pip: pictureInPicture))
        .unyriCallDisplayAwake(state: callController.state, mediaState: callController.audioControls)
        .overlay(alignment: .top) {
            if !(selectedTab == .chats && chatsRepository.isChatListVisible), let banner = bannerController.banner, banner.chatId != bannerController.navigationChatId {
                ForegroundMessageBannerView(banner: banner) {
                    selectedTab = .chats
                    chatsRepository.openChatRequest = banner.chatId
                    bannerController.dismiss()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                .padding(.top, CGFloat(bannerController.navigationChatId == nil ? 44 : chatsRepository.notificationHeaderHeight))
            }
        }
        .animation(.easeOut(duration: 0.16), value: bannerController.banner != nil)
        .modifier(CallReturnPresentation(presentation: pictureInPicture.isSystemPresented ? nil : islandPresentation))
        .modifier(ConferencePresentation())
        .modifier(UnyriCrashPresentation(canPrompt: rootMenuAvailable && !menuVisible && !conference.isBusy &&
            !conference.showLobby && conference.error == nil && callController.state == .idle))
        .background(UnyriDrawerPanAnchor(panelPercentage: Double(sidebarStyle.geometryWidth),
            swipeOpen: sidebarStyle.behaviorSwipeOpen, swipeClose: sidebarStyle.behaviorSwipeClose, context: { drawerPanContext },
            changed: { session, translation in
                guard session.isCurrent(drawerPanContext) else { return }
                menuDrag = CGFloat(session.offset(translation))
            }, ended: finishMenuPan,
            cancelled: { setMenu(menuVisible) }).allowsHitTesting(false).accessibilityHidden(true))
        .unyriScreenBackground()
        .environment(\.unyriOpenMenu, { setMenu(true) })
        .environment(\.unyriSavedRequest, savedRequest)
        .environment(\.unyriSearchRequest, searchRequest)
        .overlay { navigationDrawer }
        .onChange(of: sidebarStyle.geometryWidth) { _ in setMenu(false) }
        .onChange(of: sidebarStyle.behaviorSwipeOpen) { _ in setMenu(false) }
        .onChange(of: sidebarStyle.behaviorSwipeClose) { _ in setMenu(false) }
        .onChange(of: dockHidden) { hidden in if hidden { setMenu(false) } }
        .onChange(of: rootMenuAvailable) { available in if !available { setMenu(false) } }
        .onChange(of: rootDrawerBlocked) { blocked in if blocked { setMenu(false) } }
        .onChange(of: conferenceForeground) { visible in if visible { setMenu(false) } }
        .onChange(of: scenePhase) { phase in if phase != .active { setMenu(false) } }
        .onChange(of: callScreenVisible) { visible in if visible { setMenu(false) } }
        .onChange(of: auth.sessionGeneration) { _ in
            sidebarAvatarURL = nil; sidebarAvatarOwner = nil; showingArchive = false; setMenu(false)
            pendingTransferChat = nil; transfersOwnerGeneration = nil; showingTransfers = false
        }
        .onChange(of: conference.requestedOwnedRooms) { requested in if requested { setMenu(false); selectedTab = .rooms } }
        .sheet(isPresented: $showingTransfers, onDismiss: {
            let target = pendingTransferChat; pendingTransferChat = nil
            guard transfersOwnerGeneration == auth.sessionGeneration, let target else { return }
            showingArchive = false; selectedTab = .chats; chatsRepository.openChatRequest = target
        }) {
            if let transfers = messagesRepository.directFiles,
               transfersOwnerGeneration == auth.sessionGeneration {
                DirectFileManagerView(transfers: transfers, close: { showingTransfers = false }, openChat: { chat in
                    guard transfersOwnerGeneration == auth.sessionGeneration else { return }
                    pendingTransferChat = chat; showingTransfers = false
                })
                .presentationDetents([.large])
            } else {
                NavigationStack {
                    Text("Transfers are still loading. Try again shortly.")
                        .foregroundStyle(.secondary).padding()
                        .navigationTitle("Transfers")
                        .toolbar { UnyriBackToolbarItem(action: { showingTransfers = false }, identifier: "transfers-back") }
                        .unyriScreenBackground()
                }
            }
        }
        .environment(\.callIsland, nil as CallIslandPresentation?)
        .alert("Another conversation is active", isPresented: Binding(get: { callController.startBlockedMessage != nil },
            set: { if !$0 { callController.startBlockedMessage = nil } })) {
                Button("OK") { callController.startBlockedMessage = nil }
            } message: { Text(LocalizedStringKey(callController.startBlockedMessage ?? "")) }
        .onReceive(callController.$state) { pictureInPicture.update($0, owner: callController, chats: chatsRepository, users: usersRepository) }
        .onChange(of: pictureInPicture.restoreRequest) { _ in callMinimized = false }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            pictureInPicture.startIfPossible()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            pictureInPicture.returnToApplication()
        }
        .overlay {
            if let c = callController.videoPresentation,
               CallVideoWindowPolicy.showsInline(videoCall: c.videoMode == .video, minimized: callMinimized,
                   fullScreenVisible: callScreenVisible, appActive: scenePhase == .active,
                   systemPipVisible: pictureInPicture.isSystemPresented) {
                FloatingCallVideo(width: 120, height: 168, bottomClearance: 100, content: {
                    ZStack {
                        RemoteVideoView()
                        if c.remoteVideoState != .on {
                            UnyriColors.callBackground
                            CallPeerAvatar(userId: c.call.remoteUserId, name: c.call.remoteDisplayName, size: 64)
                        }
                    }
                }, onTap: { callMinimized = false })
                .accessibilityLabel("Return to call with \(c.call.remoteDisplayName)")
                .accessibilityHint("Tap to open the call. Drag to move")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { callMinimized = false }
            }
        }
        .onDisappear { setMenu(false); if callController.state == .idle || callController.state.isTerminal { pictureInPicture.stop() } }
        .onChange(of: selectedTab) { _ in
            setMenu(false)
            bannerController.dismiss()
            bannerController.navigationChatId = selectedTab == .chats ? chatsRepository.navigationChatId : nil
        }
        .onChange(of: chatsRepository.navigationChatId) { id in
            bannerController.navigationChatId = selectedTab == .chats ? id : nil
        }
        .onAppear { bannerController.navigationChatId = selectedTab == .chats ? chatsRepository.navigationChatId : nil }
        .onChange(of: chatsRepository.isChatListVisible) { _ in bannerController.dismiss() }
        .onChange(of: callController.state.callInfo?.callId) { _ in
            if callController.state.callInfo != nil { dismissKeyboard() }
            callMinimized = false
        }
        .onChange(of: callActive.wrappedValue) { expanded in
            if expanded { setMenu(false); dismissKeyboard() }
        }
        .task(id: callController.state.isTerminal) {
            guard callController.state.isTerminal else { return }
            do { if case .failed = callController.state { try await Task.sleep(nanoseconds: 1_500_000_000) } }
            catch { return }
            callController.acknowledgeEnded()
        }
        .alert("Call error", isPresented: Binding(
            get: { callController.state == .idle && callKit.actionError != nil },
            set: { if !$0 { callKit.actionError = nil } }
        )) {
            Button("OK") { callKit.actionError = nil }
        } message: { Text(callKit.actionError ?? "") }
        .fullScreenCover(isPresented: callActive) {
            CallScreen(onMinimize: { callMinimized = true }, onOpenChat: { chatId in
                selectedTab = .chats
                chatsRepository.openChatRequest = chatId
                callMinimized = true
            })
                .environmentObject(pictureInPicture)
                .environmentObject(callController)
                .environmentObject(callKit)
                .environmentObject(bannerController)
                .onAppear { callScreenVisible = true; pictureInPicture.returnToApplication() }
                .onDisappear { callScreenVisible = false }
        }
    }

    private var callActive: Binding<Bool> {
        Binding(
            get: {
                // CallKit already presents the incoming-call banner; do not cover
                // the conversation with a second incoming screen.
                if case .incomingRinging = callController.state { return false }
                return callController.state != .idle && !callController.state.isNormallyEnded && !callMinimized
            },
            set: { if !$0 { callMinimized = true } }
        )
    }

    private var islandPresentation: CallIslandPresentation? {
        guard callMinimized, let banner = activeCallBanner else { return nil }
        return CallIslandPresentation(name: banner.name, isVideo: banner.isVideo,
            connectedAt: callController.connectedAt, status: banner.status, onReturn: { callMinimized = false })
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    /// The peer identity + media kind for the minimized banner, only while a
    /// outgoing/connecting calls also need a way back before the peer answers.
    private var activeCallBanner: (name: String, isVideo: Bool, status: String?)? {
        switch callController.state {
        case .outgoingRinging(let call):
            return (call.remoteDisplayName, call.mediaKind == .video, UnyriL10n.string("Calling…"))
        case .connecting(let call):
            return (call.remoteDisplayName, call.mediaKind == .video, UnyriL10n.string("Connecting…"))
        case .connected(let c):
            return (c.call.remoteDisplayName, c.videoMode == .video, nil)
        case .reconnecting(let call):
            return (call.remoteDisplayName,
                    callController.audioControls.map { $0.videoMode == .video } ?? (call.mediaKind == .video),
                    UnyriL10n.string("Reconnecting…"))
        default:
            return nil
        }
    }
}

/// Pure admission/release policy shared with the native window pan. Keeping
/// the first owner and mode prevents cancelled/obsolete drags reopening menus.
enum UnyriDrawerPanPolicy {
    struct Context: Equatable, Sendable {
        let generation: UInt64
        let root: String
        let active: Bool
        let menuVisible: Bool
        let canOpen: Bool
        var allowsOpening: Bool { active && canOpen && UnyriDrawerPanPolicy.isRoot(root) }
    }
    enum Mode { case opening, closing }
    static func isRoot(_ name: String) -> Bool {
        // Search and People are root content inside the chats destination.
        // Saved Messages is currently a conversation, not a fifth root tab.
        switch name { case "chats", "calls", "rooms", "settings": return true; default: return false }
    }
    struct DeferredCancellation: Sendable {
        let context: Context
        let sequence: UInt64
        func shouldNotify(current: Context, sequence latest: UInt64, idle: Bool, attached: Bool) -> Bool {
            context == current && current.active && sequence == latest && idle && attached
        }
    }
    struct Session {
        let context: Context
        let width: Double
        let mode: Mode

        func isCurrent(_ current: Context) -> Bool { context == current && current.active }
        func accepts(horizontal: Double, vertical: Double) -> Bool {
            guard horizontal.isFinite, vertical.isFinite,
                  abs(horizontal) > abs(vertical) * 1.25 else { return false }
            return mode == .opening ? horizontal > 0 : horizontal < 0
        }
        func offset(_ translation: Double) -> Double {
            guard translation.isFinite else { return 0 }
            return mode == .opening ? min(width, max(0, translation)) : max(-width, min(0, translation))
        }
        func finish(current: Context, width currentWidth: Double, translation: Double, velocity: Double) -> Bool? {
            guard isCurrent(current), currentWidth.isFinite, abs(currentWidth - width) <= 1,
                  translation.isFinite, velocity.isFinite else { return nil }
            let distance = mode == .opening ? translation : -translation
            let speed = mode == .opening ? velocity : -velocity
            let commit = distance > width * 0.25 || (distance > 0 && distance + speed * 0.18 > width * 0.5)
            return mode == .opening ? commit : !commit
        }
    }
    static func panelWidth(rootWidth: Double, percentage: Double) -> Double {
        let bounded = percentage.isFinite ? min(80, max(34, percentage)) : Double(UnyriSavedSidebarStyle.geometryWidth)
        return rootWidth * bounded / 100
    }
    static func start(context: Context, x: Double, rootWidth: Double,
                      panelPercentage: Double = Double(UnyriSavedSidebarStyle.geometryWidth),
                      swipeOpen: Bool = true, swipeClose: Bool = true) -> Session? {
        guard context.active, x.isFinite, rootWidth.isFinite, rootWidth > 1,
              (0...rootWidth).contains(x) else { return nil }
        let width = panelWidth(rootWidth: rootWidth, percentage: panelPercentage)
        if context.menuVisible { return swipeClose ? Session(context: context, width: width, mode: .closing) : nil }
        guard swipeOpen, context.allowsOpening, x <= 22 else { return nil }
        return Session(context: context, width: width, mode: .opening)
    }
}

/// Noninteractive anchor only: the owned recognizer lives on its hosting
/// window. Vertical drags fail in shouldBegin before stealing a list scroll.
private struct UnyriDrawerPanAnchor: UIViewRepresentable {
    let panelPercentage: Double
    let swipeOpen: Bool
    let swipeClose: Bool
    let context: () -> UnyriDrawerPanPolicy.Context
    let changed: (UnyriDrawerPanPolicy.Session, Double) -> Void
    let ended: (UnyriDrawerPanPolicy.Session, Double, Double, Double) -> Void
    let cancelled: () -> Void

    func makeUIView(context: Context) -> UnyriDrawerPanView {
        let view = UnyriDrawerPanView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        configure(view)
        return view
    }
    func updateUIView(_ view: UnyriDrawerPanView, context: Context) { configure(view) }
    private func configure(_ view: UnyriDrawerPanView) {
        view.current = context; view.changed = changed; view.ended = ended; view.cancelled = cancelled
        view.panelPercentage = panelPercentage; view.swipeOpen = swipeOpen; view.swipeClose = swipeClose
        view.cancelObsoletePan()
    }
    static func dismantleUIView(_ view: UnyriDrawerPanView, coordinator: ()) { view.detach() }
}

private final class UnyriDrawerPanView: UIView, UIGestureRecognizerDelegate {
    var panelPercentage = Double(UnyriSavedSidebarStyle.geometryWidth)
    var swipeOpen = true
    var swipeClose = true
    var current: (() -> UnyriDrawerPanPolicy.Context)?
    var changed: ((UnyriDrawerPanPolicy.Session, Double) -> Void)?
    var ended: ((UnyriDrawerPanPolicy.Session, Double, Double, Double) -> Void)?
    var cancelled: (() -> Void)?
    private weak var gestureWindow: UIWindow?
    private var candidate: UnyriDrawerPanPolicy.Session?
    private var active: UnyriDrawerPanPolicy.Session?
    private var panSequence: UInt64 = 0
    private lazy var pan: UIPanGestureRecognizer = {
        let value = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        value.maximumNumberOfTouches = 1
        value.cancelsTouchesInView = false
        value.delaysTouchesBegan = false
        value.delaysTouchesEnded = false
        value.delegate = self
        return value
    }()

    override func didMoveToWindow() {
        super.didMoveToWindow()
        detach()
        if let window { window.addGestureRecognizer(pan); gestureWindow = window }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let session = active, abs(session.width - drawerWidth) > 1 else { return }
        cancelPan(notify: false)
        let ticket = UnyriDrawerPanPolicy.DeferredCancellation(context: session.context, sequence: panSequence)
        // Layout/updateUIView must not synchronously mutate SwiftUI state.
        // A new touch, owner, window or explicit menu action invalidates this.
        DispatchQueue.main.async { [weak self] in
            guard let self, let current = self.current,
                  ticket.shouldNotify(current: current(), sequence: self.panSequence,
                      idle: self.active == nil && self.candidate == nil, attached: self.gestureWindow != nil) else { return }
            self.cancelled?()
        }
    }
    func detach() {
        cancelPan(notify: false)
        gestureWindow?.removeGestureRecognizer(pan)
        gestureWindow = nil
    }
    func cancelObsoletePan() {
        guard let session = active ?? candidate, let current else { return }
        let enabled = session.mode == .opening ? swipeOpen : swipeClose
        guard !session.isCurrent(current()) || abs(session.width - drawerWidth) > 1 || !enabled else { return }
        // Root onChange closes context/width/setting departures; this callback only
        // tears down the obsolete recognizer state, never writes View state.
        cancelPan(notify: false)
    }
    private func cancelPan(notify: Bool = true) {
        let wasActive = active != nil
        panSequence &+= 1
        active = nil; candidate = nil
        pan.isEnabled = false; pan.isEnabled = true
        if wasActive && notify { cancelled?() }
    }
    private var drawerWidth: Double { UnyriDrawerPanPolicy.panelWidth(rootWidth: Double(bounds.width), percentage: panelPercentage) }
    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard recognizer === pan, pan.state == .possible, let window = gestureWindow,
              let current, let touched = touch.view else { return false }
        let point = touch.location(in: self)
        guard bounds.contains(point) else { return false }
        // Presented sheets/calls/keyboard windows never belong to the root.
        var responder: UIResponder? = self
        while let value = responder, !(value is UIViewController) { responder = value.next }
        guard let owner = responder as? UIViewController,
              touched.isDescendant(of: owner.view), touched.window === window else { return false }
        candidate = UnyriDrawerPanPolicy.start(context: current(), x: Double(point.x), rootWidth: Double(bounds.width),
            panelPercentage: panelPercentage, swipeOpen: swipeOpen, swipeClose: swipeClose)
        if candidate != nil { panSequence &+= 1 }
        return candidate != nil
    }
    override func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        guard recognizer === pan, let candidate, let current, candidate.isCurrent(current()),
              abs(candidate.width - drawerWidth) <= 1 else { return false }
        let velocity = pan.velocity(in: gestureWindow)
        let translation = pan.translation(in: gestureWindow)
        let vector = velocity == .zero ? translation : velocity
        return candidate.accepts(horizontal: Double(vector.x), vertical: Double(vector.y))
    }
    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        // A vertical ScrollView remains its own owner; our horizontal admission
        // rejects its gesture without an edge hit-test view or failure chain.
        recognizer === pan && other is UIPanGestureRecognizer && other.view is UIScrollView
    }
    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        switch recognizer.state {
        case .began:
            guard let candidate, let current, candidate.isCurrent(current()) else { cancelPan(); return }
            active = candidate; self.candidate = nil
            changed?(candidate, Double(recognizer.translation(in: gestureWindow).x))
        case .changed:
            guard let active, let current, active.isCurrent(current()) else { cancelPan(); return }
            changed?(active, Double(recognizer.translation(in: gestureWindow).x))
        case .ended:
            guard let session = active else { candidate = nil; return }
            panSequence &+= 1
            active = nil; candidate = nil
            ended?(session, Double(recognizer.translation(in: gestureWindow).x),
                Double(recognizer.velocity(in: gestureWindow).x), drawerWidth)
        case .cancelled, .failed:
            let wasActive = active != nil
            panSequence &+= 1
            active = nil; candidate = nil
            if wasActive { cancelled?() }
        default: break
        }
    }
}



/// Compact call control inside the existing navigation header.
private struct ActiveCallBanner: View {
    let name: String
    let isVideo: Bool
    let connectedAt: Date?
    var status: String? = nil
    let onReturn: () -> Void

    var body: some View {
        ConversationReturnBanner(title: name, systemImage: isVideo ? "video.fill" : "phone.fill", onReturn: onReturn, subtitle: {
            HStack(spacing: 6) {
                if let status { Text(status) }
                else if let connectedAt {
                    TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                        Text(formatCallDuration(context.date.timeIntervalSince(connectedAt))).monospacedDigit()
                    }
                } else { Text("In call") }
                Text("·"); Text("Tap to return")
            }
        }, accessory: { EmptyView() })
        .accessibilityLabel(UnyriL10n.string("Return to call") + ". " + name)
        .accessibilityIdentifier("call-return")
    }

}

private extension CallUiState {
    var isNormallyEnded: Bool { if case .ended = self { return true }; return false }
    var isTerminal: Bool {
        switch self {
        case .ended, .failed: return true
        default: return false
        }
    }
}

private struct CallsView: View {
    let visible: Bool
    @EnvironmentObject private var callKit: CallKitCoordinator
    @EnvironmentObject private var callController: CallController
    @EnvironmentObject private var conference: ConferenceController
    @EnvironmentObject private var calls: CallsRepository
    @EnvironmentObject private var chats: ChatsRepository
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var realtime: RealtimeClient
    @Environment(\.callIsland) private var island
    @ObservedObject private var network = UnyriMediaNetwork.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var nextCursor: UUID?
    @State private var loaded = false
    @State private var loadingOlder = false
    @State private var olderPageFailed = false
    @State private var error: String?
    @State private var summaries: [ChatSummary] = []
    @State private var openedChat: ChatSummary?
    @State private var showChat = false
    @State private var openingChat = false
    @State private var openingTask: Task<Void, Never>?
    @State private var selectedCalls: Set<UUID> = []
    @State private var confirmDeletion = false
    @State private var deletingCalls = false
    @State private var visibleCallFrames: [UUID: CGRect] = [:]
    @State private var callViewport: CGRect = .zero
    private func acknowledgeVisibleCalls() {
        guard scenePhase == .active, !showChat, !callViewport.isEmpty else { return }
        calls.acknowledgeViewed(Set(visibleCallFrames.filter { $0.value.intersects(callViewport) }.keys))
    }
    private func toggleCall(_ id: UUID) {
        if selectedCalls.contains(id) { selectedCalls.remove(id) } else { selectedCalls.insert(id) }
    }
    private func deleteSelectedCalls(everyone: Bool) {
        let ids = selectedCalls
        let generation = auth.sessionGeneration
        deletingCalls = true
        Task {
            defer { deletingCalls = false }
            do {
                for id in ids {
                    guard generation == auth.sessionGeneration else { return }
                    try await calls.deleteHistory(id: id, everyone: everyone)
                    selectedCalls.remove(id)
                }
            } catch { self.error = error.localizedDescription }
        }
    }
    private func callSummary(_ call: CallDTO) -> ChatSummary? {
        guard call.chatVisible != false, let chatId = call.chatId else { return nil }
        if let cached = chats.preferenceCache.values.first(where: { UUID(uuidString: $0.chatId) == chatId }) {
            return cached
        }
        return summaries.first(where: { UUID(uuidString: $0.chatId) == chatId })
    }
    private func callPeer(_ call: CallDTO, owner: UUID?) -> PublicUser? {
        if let profile = call.validatedPeer(owner:owner) {
            return PublicUser(id:profile.id.uuidString.lowercased(),username:profile.username,displayName:profile.displayName,
                bio:profile.bio,avatarUrl:profile.avatarUrl,profileVersion:profile.profileVersion,
                avatarVersion:profile.avatarVersion,hasAvatar:profile.hasAvatar)
        }
        guard let owner, call.callerUserId == owner || call.calleeUserId == owner else { return nil }
        let peerId = call.callerUserId == owner ? call.calleeUserId : call.callerUserId
        // Profile fallback only; navigation still requires the canonical call chat.
        if let cached = chats.preferenceCache.values.first(where: { UUID(uuidString:$0.otherUser?.id ?? "") == peerId })?.otherUser { return cached }
        return summaries.first(where: { UUID(uuidString:$0.otherUser?.id ?? "") == peerId })?.otherUser
    }

    @ViewBuilder
    private func callRow(_ call: CallDTO) -> some View {
        let owner = auth.currentUser.flatMap { UUID(uuidString: $0.id) }
        let peer = call.callerUserId == owner ? call.calleeUserId : call.callerUserId
        // Use the session's in-memory profile before the asynchronous disk refresh.
        let summary: ChatSummary? = callSummary(call)
        let identity = callPeer(call,owner:owner)
        let name = CallPeerTitlePolicy.title(displayName:identity?.displayName,username:identity?.username,unknown:UnyriL10n.string("Unknown user"))
        HStack(spacing: 4) {
        Button {
            if !selectedCalls.isEmpty { toggleCall(call.id); return }
            guard !openingChat, !showChat else { return }
            if let summary {
                openedChat = summary
                showChat = true
            } else {
                openingChat = true
                openingTask = Task {
                    defer { openingChat = false }
                    do {
                        let chat = try await chats.createOrOpenDirectChat(otherUserId: peer.uuidString.lowercased())
                        guard !Task.isCancelled else { return }
                        chats.persistChats([chat]); openedChat = chat; showChat = true
                    } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
                }
            }
        } label: { CallHistoryRow(call: call, owner: owner, name: name, listRow: true, peer: identity) }
        .buttonStyle(.plain)
        .disabled(deletingCalls)
        .contextMenu {
            Button("Select") { toggleCall(call.id) }
            Button("Delete", role: .destructive) { selectedCalls = [call.id]; confirmDeletion = true }
        }
        if !selectedCalls.isEmpty {
            Button { toggleCall(call.id) } label: {
                Image(systemName: selectedCalls.contains(call.id) ? "checkmark.circle.fill" : "circle")
                    .frame(width: 44, height: 44)
            }.disabled(deletingCalls).accessibilityLabel("Select")
        } else {
        Button {
            callKit.startOutgoing(remoteUserId: peer.uuidString.lowercased(), displayName:name, isVideo: call.mediaKind == .video)
        } label: {
            Image(systemName: call.mediaKind == .video ? "video" : "phone")
                .foregroundColor(UnyriColors.accentSoft).frame(width: 44, height: 44)
        }.buttonStyle(.plain).accessibilityLabel("Call again")
        .disabled(callController.state != .idle)
        }
        }
        .background(GeometryReader { geometry in
            Color.clear.preference(key: CallRowsFrames.self, value: [call.id: geometry.frame(in: .global)])
        })
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 4) {
                    if let error { Text(error).font(.footnote).foregroundColor(UnyriColors.danger) }
                    if calls.history.isEmpty { Text(LocalizedStringKey(loaded ? "No recent calls" : "Loading call history…")).padding(.vertical, 32) }
                    ForEach(calls.history) { call in
                        callRow(call)
                        Divider().padding(.leading, 56)
                    }
                    if let cursor = nextCursor {
                        if olderPageFailed {
                            Button("Retry") { olderPageFailed = false }
                        } else {
                        ProgressView().task(id: cursor) {
                            guard !loadingOlder else { return }
                            loadingOlder = true
                                defer { loadingOlder = false }
                                do {
                                    let next = try await calls.refreshHistory(before: cursor)
                                    guard !Task.isCancelled else { return }
                                    nextCursor = next
                                } catch {
                                    guard !Task.isCancelled else { return }
                                    self.error = error.localizedDescription
                                    olderPageFailed = true
                                }
                        }
                        }
                    }
                }.padding(16)
            }
            .background(GeometryReader { geometry in
                Color.clear.preference(key: CallViewportFrame.self, value: geometry.frame(in: .global))
            })
            .onPreferenceChange(CallRowsFrames.self) { visibleCallFrames = $0; acknowledgeVisibleCalls() }
            .onPreferenceChange(CallViewportFrame.self) { callViewport = $0; acknowledgeVisibleCalls() }
            .onChange(of: calls.history) { _ in acknowledgeVisibleCalls() }
            .onChange(of: scenePhase) { _ in acknowledgeVisibleCalls() }
            .onChange(of: showChat) { _ in acknowledgeVisibleCalls() }
            .unyriScreenBackground().navigationTitle(selectedCalls.isEmpty ? UnyriL10n.string("Calls") : String(format: UnyriL10n.string("Selected: %d"), selectedCalls.count))
            .task(id: chats.cacheRevision) {
                let updated = await chats.loadCachedChatsAsync()
                guard !Task.isCancelled else { return }
                summaries = updated
            }
            .toolbar {
                if selectedCalls.isEmpty { ToolbarItem(placement: .navigationBarLeading) { UnyriMenuButton() } }
                if selectedCalls.isEmpty && island == nil {
                    ToolbarItem(placement: .principal) {
                        UnyriConnectionHeader(realtime: realtime, routeAvailable: network.connected,
                            loading: !loaded, error: error != nil, onRetry: {
                                Task {
                                    await realtime.retryConnectionSync()
                                    do {
                                        let cursor = try await calls.refreshHistory()
                                        if !loaded { nextCursor = cursor; loaded = true }
                                        error = nil
                                    } catch { self.error = error.localizedDescription }
                                }
                            }, visible: visible && !showChat)
                    }
                }
                if !selectedCalls.isEmpty {
                    ToolbarItem(placement: .navigationBarLeading) { Button("Done") { selectedCalls = [] }.disabled(deletingCalls) }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(role: .destructive) { confirmDeletion = true } label: { Label("Delete", systemImage: "trash") }.disabled(deletingCalls)
                    }
                }
            }
            .confirmationDialog("Delete selected calls?", isPresented: $confirmDeletion, titleVisibility: .visible) {
                Button("Delete for me", role: .destructive) { deleteSelectedCalls(everyone: false) }
                Button("Delete for everyone", role: .destructive) { deleteSelectedCalls(everyone: true) }
                Button("Cancel", role: .cancel) { }
            } message: { Text("This removes call records, not messages or contacts.") }
            .onChange(of: calls.history.map(\.id)) { ids in selectedCalls.formIntersection(Set(ids)) }
            .modifier(CallIslandToolbar())
            .navigationBarTitleDisplayMode(.inline)
            .onDisappear { openingTask?.cancel(); openingTask = nil }
            .navigationDestination(isPresented: $showChat) { if let openedChat { ChatScreen(chat: openedChat).id(openedChat.chatId) } }
            .task(id: "\(scenePhase)-\(showChat)-\(visible)") {
                guard scenePhase == .active, !showChat, visible else { return }
                await calls.loadCachedHistory()
                while !Task.isCancelled {
                    do {
                        let cursor = try await calls.refreshHistory()
                        if !loaded { nextCursor = cursor; loaded = true }
                        error = nil
                    } catch { if Task.isCancelled { return }; self.error = error.localizedDescription }
                    do { try await Task.sleep(nanoseconds: 10_000_000_000) } catch { return }
                }
            }
        }
        .preference(key: UnyriRootDockHiddenKey.self, value: showChat)
        .unyriBlocksRootDrawer(showChat || !selectedCalls.isEmpty || confirmDeletion)
    }
}

// MARK: - Foreground message banner (Runtime Correction D)
private struct CallRowsFrames: PreferenceKey {
    static let defaultValue: [UUID: CGRect] = [:]
    static func reduce(value: inout [UUID: CGRect], nextValue: () -> [UUID: CGRect]) { value.merge(nextValue(), uniquingKeysWith: { _, last in last }) }
}
private struct CallViewportFrame: PreferenceKey {
    static let defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = nextValue() }
}

/// Compact Glass Flow banner (avatar + name + one-line preview). Tap opens the
/// conversation; accessibility announces sender + preview.
struct ForegroundMessageBannerView: View {
    let banner: ForegroundMessageBannerController.Banner
    let onTap: () -> Void
    @EnvironmentObject private var bannerController: ForegroundMessageBannerController
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @State private var avatarURL: URL?

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onTap) {
              HStack(spacing: 12) {
                UserAvatar(url: avatarURL, name: title, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(UnyriColors.primaryText)
                        .lineLimit(1)
                    Text(previewText)
                        .font(.footnote)
                        .foregroundColor(UnyriColors.secondaryText)
                        .lineLimit(1).truncationMode(.tail)
                }
                Spacer(minLength: 8)
              }.contentShape(Rectangle())
            }
            .accessibilityLabel("\(title). \(previewText)")
            Button { bannerController.dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(UnyriColors.secondaryText)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Dismiss notification")
        }
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: UnyriShapes.radiusCard, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusCard, style: .continuous).stroke(UnyriColors.outlineSecondary, lineWidth: 1))
            .padding(.horizontal, 12)
            .padding(.top, 4)
        .frame(maxWidth: 420)
        .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
        .simultaneousGesture(DragGesture(minimumDistance: 12).onEnded { value in
            if value.translation.height < -24 { bannerController.dismiss() }
        })
        .buttonStyle(.plain)
        .task(id: banner.generation) {
            avatarURL = nil
            // A cached sender and local image remain available while offline.
            // The in-memory preference cache is hydrated from the same local
            // store at account start; using it avoids a synchronous SQLite
            // scan of every chat on the main actor for each banner.
            var chat = chatsRepository.preferenceCache[banner.chatId]
            if chat == nil { chat = try? await chatsRepository.getChat(chatId: banner.chatId) }
            guard !Task.isCancelled, let peer = chat?.otherUser else { return }
            avatarURL = usersRepository.cachedAvatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion ?? 0)
            let url = await usersRepository.avatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion ?? 0)
            guard !Task.isCancelled else { return }
            avatarURL = url
            // Refresh version/removal metadata without hiding the cached image on a network error.
            if let fresh = try? await chatsRepository.getChat(chatId: banner.chatId),
               let updated = fresh.otherUser,
               updated.id != peer.id || updated.avatarVersion != peer.avatarVersion || updated.avatarUrl != peer.avatarUrl {
                let refreshed = await usersRepository.avatarURL(id: updated.id, avatarUrl: updated.avatarUrl, avatarVersion: updated.avatarVersion ?? 0)
                guard !Task.isCancelled else { return }
                avatarURL = refreshed
            }
        }
    }

    private var title: String {
        banner.senderName ?? "Chat"
    }

    private var previewText: String {
        switch banner.preview {
        case .text(let text): return text
        case .video: return UnyriL10n.string("Video")
        case .photo: return UnyriL10n.string("Photo")
        case .voice: return "Voice message"
        case .file(let filename): return filename ?? "File"
        case .deleted: return "Message deleted"
        }
    }
}
