import SwiftUI
import Combine
import UIKit

struct CallIslandPresentation {
    let name: String
    let isVideo: Bool
    let connectedAt: Date?
    var status: String? = nil
    /// Signal bars beside the timer of a connected call.
    var quality: CallQualityMonitor? = nil
    /// The bar reports its frame here, for the minimize and expand morphs.
    var morph: CallMorphAnchors? = nil
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
            connectedAt: presentation.connectedAt, status: presentation.status, quality: presentation.quality,
            morph: presentation.morph, onReturn: presentation.onReturn)
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        VStack(spacing: 0) {
            if let presentation {
                ActiveCallBanner(name: presentation.name, isVideo: presentation.isVideo,
                    connectedAt: presentation.connectedAt, status: presentation.status, quality: presentation.quality,
                    morph: presentation.morph, onReturn: presentation.onReturn)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            content
        }
        // The banner slides in and the screen eases down with it, instead of the
        // whole app jumping 56 pt the moment a call is minimized.
        .animation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.86), value: presentation != nil)
    }
}

/// Authenticated tabs and the shared call/message presentation.
/// The app navigates with its drawer; the TabView's own bar is never shown.
private struct UnyriSystemTabBarHider: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) { controller.hide() }

    final class Controller: UIViewController {
        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            hide()
        }
        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            hide()
        }
        func hide() {
            var ancestor = parent
            while let current = ancestor, !(current is UITabBarController) { ancestor = current.parent }
            guard let tabs = (ancestor as? UITabBarController) ?? tabBarController else { return }
            if #available(iOS 18.0, *) {
                if !tabs.isTabBarHidden { tabs.setTabBarHidden(true, animated: false) }
            } else if !tabs.tabBar.isHidden {
                tabs.tabBar.isHidden = true
            }
        }
    }
}

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

/// Callbacks the root hands to each tab, made once and reused. A closure written in
/// the root's body is a new value on every body run, and SwiftUI cannot compare
/// closures, so every hidden tab (Settings, Calls, Rooms) re-rendered whenever the root
/// did: each time a chat opened or closed (the list's menu button reports itself),
/// part of the stall when going back to the chat list.
private final class UnyriRootCallbacks {
    var menuVisibility: [RootDestination: (Bool) -> Void] = [:]
    var drawerScopes: [RootDestination: UnyriDrawerBlockerScope] = [:]
    /// The environment's open-menu action: one stable closure, its target swapped each
    /// pass. An inline closure was a new environment value on every root pass, so every
    /// view below that reads the environment (each tab's menu button, every UIKit-backed
    /// view, the open chat's timeline) was updated on each publish the root observes.
    private var openMenuTarget: () -> Void = {}
    private lazy var openMenuAction: () -> Void = { [weak self] in self?.openMenuTarget() }
    func openMenu(_ target: @escaping () -> Void) -> () -> Void {
        openMenuTarget = target
        return openMenuAction
    }
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
    @State private var minimizedWhileStarting = false
    @State private var callScreenVisible = false
    /// What the call cover is up for, taken from `callActive` in the same
    /// update (not from the cover's onDisappear after its slide), so an ended
    /// call keeps only its own cover.
    @State private var callCoverClaim: CallCoverPolicy.Claim = .none
    /// The minimize/expand morph between the call cover and the call bar. A
    /// reference: frames written into it never re-render the root.
    @State private var callMorph = CallMorphAnchors()
    /// The call cover's rotation lease. A reference like `callMorph`: the root never observes
    /// it, so a screen turn re-renders only the call screen (which subscribes itself).
    @State private var callRotation = CallScreenRotation()
    /// The mini call window's corner, size and tuck for the current call. A
    /// reference: its writes never re-render the root.
    @State private var callMiniWindowMemory = CallMiniWindowMemory()
    /// Where the mini call window takes touches; the drawer's pan never
    /// starts there. A reference: its writes never re-render the root.
    @State private var callMiniWindowTouches = CallMiniWindowTouchAreas()
    /// The call whose mini window × hid; back in the call it shows again.
    @State private var callWindowHidden: String?
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var pictureInPicture = CallPictureInPicture()
    @AppStorage("showCallsTab") private var showCallsTab = true
    @AppStorage("showSavedMessages") private var showSavedMessages = true
    @State private var menuVisible = false
    /// Finger offset of an interactive drawer pan. A reference, not @State: only the
    /// drawer layer observes it, so a slow drag no longer re-renders the whole root
    /// (tabs, chat list) on every touch move (device feedback: the drawer "froze").
    @State private var drawerDrag = UnyriDrawerDrag()
    /// Changes only when a pan starts/ends, never per frame.
    @State private var drawerPeeking = false
    @State private var showingProfileQR = false
    @State private var chatsMenuAvailable = false
    @State private var callsMenuAvailable = false
    @State private var settingsMenuAvailable = false
    @State private var roomsMenuAvailable = false
    @State private var drawerBlockers: [RootDestination: Set<UUID>] = [:]
    @State private var rootCallbacks = UnyriRootCallbacks()
    @State private var savedRequest: UUID?
    @State private var showingArchive = false
    @State private var showingTransfers = false
    @State private var transfersOwnerGeneration: UInt64?
    @State private var pendingTransferChat: String?
    @State private var sidebarProfile: UserProfile?
    @State private var sidebarAvatarURL: URL?
    @State private var sidebarAvatarOwner: String?
    @State private var searchRequest: SearchNavigationRequest?
    @State private var settingsRequest: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriSidebarStyle) private var sidebarStyle
    @Environment(\.unyriSidebarAppearanceSettings) private var sidebarAppearance
    private var drawerChoice: DrawerChoice { DrawerChoice(storedValue: sidebarAppearance.drawerChoice) }
    @EnvironmentObject private var auth: AuthRepository
    @State private var dockHidden = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Body text scale (1 at the default size); drawer A widens with larger text.
    @ScaledMetric(relativeTo: .body) private var drawerTextScale: CGFloat = 1
    @State private var selectedTab = RootDestination.chats
    @EnvironmentObject private var callController: CallController
    @EnvironmentObject private var conference: ConferenceController
    @EnvironmentObject private var callKit: CallKitCoordinator
    @EnvironmentObject private var chatsRepository: ChatsRepository
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @EnvironmentObject private var usersRepository: UsersRepository
    @EnvironmentObject private var bannerController: ForegroundMessageBannerController

    private func setMenu(_ requested: Bool, feedback: Bool = true) {
        let visible = requested && drawerPanContext.allowsOpening
        if visible { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        if feedback && visible != menuVisible { UnyriHaptics.selection() }
        withAnimation(reduceMotion ? nil : .easeOut(duration: Double(sidebarStyle.behaviorDuration) / 1000)) {
            drawerDrag.value = 0
            menuVisible = visible
        }
        drawerPeeking = false
    }

    private var drawerPanContext: UnyriDrawerPanPolicy.Context {
        UnyriDrawerPanPolicy.Context(generation: auth.sessionGeneration, root: String(describing: selectedTab),
            active: scenePhase == .active && !showingTransfers && !callScreenVisible && !callActive.wrappedValue && !conferenceForeground,
            menuVisible: menuVisible, canOpen: !dockHidden && rootMenuAvailable && !rootDrawerBlocked)
    }

    private var conferenceForeground: Bool { conference.showLobby || (conference.isBusy && !conference.minimized) }
    private var rootDrawerBlocked: Bool { !(drawerBlockers[selectedTab] ?? []).isEmpty }
    private func drawerScope(_ root: RootDestination) -> UnyriDrawerBlockerScope {
        if let scope = rootCallbacks.drawerScopes[root] { return scope }
        let scope = UnyriDrawerBlockerScope(update: { token, blocked in
            if blocked { drawerBlockers[root, default: []].insert(token) }
            else { drawerBlockers[root]?.remove(token) }
        })
        rootCallbacks.drawerScopes[root] = scope
        return scope
    }
    /// The same closure on every pass (its target is this pass's `setMenu`).
    private var openMenuEnvironment: () -> Void { rootCallbacks.openMenu { setMenu(true) } }
    private func menuVisibility(_ root: RootDestination) -> (Bool) -> Void {
        if let callback = rootCallbacks.menuVisibility[root] { return callback }
        let callback: (Bool) -> Void
        switch root {
        case .chats: callback = { chatsMenuAvailable = $0 }
        case .calls: callback = { callsMenuAvailable = $0 }
        case .rooms: callback = { roomsMenuAvailable = $0 }
        case .settings: callback = { settingsMenuAvailable = $0 }
        default: callback = { _ in }
        }
        rootCallbacks.menuVisibility[root] = callback
        return callback
    }

    private func finishMenuPan(_ session: UnyriDrawerPanPolicy.Session, translation: Double, velocity: Double, width: Double) {
        // UIKit callbacks can outlive the root, call or account which admitted
        // the drag. A late release may never reopen a successor screen's menu.
        let target = session.finish(current: drawerPanContext, width: width, translation: translation, velocity: velocity)
        setMenu(target ?? menuVisible)
    }

    /// The repository is per account and outlives this view (sign-out, sign-in).
    private func syncChatsTabSelection() { chatsRepository.chatsTabSelected = selectedTab == .chats }

    /// A notification tap always lands in Chats with the menu closed; the list then opens
    /// the chat once it is the visible tab (ChatsListView.routeOpenRequest).
    private func landOnChatsForRoute() {
        guard UserDefaults.standard.dictionary(forKey: "chatReminderRoute") != nil else { return }
        setMenu(false)
        showingArchive = false
        if selectedTab != .chats { selectedTab = .chats }
    }

    /// QA only (simulator, UNYRI_QA_PUSH_TAP_PEER): every return to the foreground takes
    /// the route a notification tap takes, for that peer's chat.
    private func qaNotificationRoute() {
        #if targetEnvironment(simulator)
        guard let peer = ProcessInfo.processInfo.environment["UNYRI_QA_PUSH_TAP_PEER"], let owner = auth.currentUser?.id else { return }
        Task { @MainActor in
            let chats = await chatsRepository.loadCachedChatsAsync()
            guard let target = chats.first(where: { $0.otherUser?.displayName == peer }) else { return }
            UserDefaults.standard.set(["owner": owner, "chat": target.chatId, "message": ""], forKey: "chatReminderRoute")
            NotificationCenter.default.post(name: Notification.Name("COCOChatReminder"), object: nil)
        }
        #endif
    }

    private func openSettings(_ route: String) {
        selectedTab = .settings
        settingsRequest = route
    }

    private func performSidebarItem(_ id: String) {
        setMenu(false, feedback: false)
        switch id {
        case "chats": showingArchive = false; selectedTab = .chats; searchRequest = SearchNavigationRequest(.chats)
        case "search": showingArchive = false; selectedTab = .chats; searchRequest = SearchNavigationRequest(.search)
        case "contacts": showingArchive = false; selectedTab = .chats; searchRequest = SearchNavigationRequest(.contacts)
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

    private func performSidebarSettings(_ route: String) {
        UnyriHaptics.selection(); setMenu(false); openSettings(route)
    }

    private var navigationDrawer: some View {
        UnyriDrawerDragReader(drag: drawerDrag) { menuDrag in
        GeometryReader { geometry in
            let width = CGFloat(drawerChoice.width(screenWidth: Double(geometry.size.width), accessibility: dynamicTypeSize.isAccessibilitySize, textScale: Double(drawerTextScale)))
            let extent = width + 8
            let offset = menuVisible ? max(-extent, min(0, menuDrag)) : min(extent, max(0, menuDrag)) - extent
            let progress = max(0, min(1, 1 + offset / max(1, extent)))
            let open = menuVisible || menuDrag > 0
            ZStack(alignment: .leading) {
                if open {
                    Color.black.opacity(Double(sidebarStyle.behaviorScrim / 100) * progress)
                        .ignoresSafeArea().onTapGesture { setMenu(false) }
                        .accessibilityLabel("Close menu").accessibilityIdentifier("drawer-backdrop")
                        .accessibilityAddTraits(.isButton).transition(.opacity)
                }
                // Construct only while open or during an admitted interactive pan.
                if open {
                    let profile = (usersRepository.ownProfile ?? sidebarProfile).flatMap { cached in
                        cached.id.lowercased() == auth.currentUser?.id.lowercased() ? cached : nil
                    }
                    UnyriSidebarBadgeReader(transfers: messagesRepository.directFiles) { badges in
                    UnyriDrawerView(choice: drawerChoice, screenWidth: geometry.size.width, availableHeight: max(0, geometry.size.height - 24),
                        displayName: profile?.displayName ?? auth.currentUser?.displayName ?? "UNYRI",
                        username: usersRepository.ownIdentity?.publicUsername,
                        avatarURL: profile.flatMap { value in
                            guard value.avatarUrl?.isEmpty == false else { return nil }
                            return usersRepository.cachedAvatarURL(id: value.id, avatarUrl: value.avatarUrl, avatarVersion: value.avatarVersion)
                                ?? (sidebarAvatarOwner == auth.currentUser?.id ? sidebarAvatarURL : nil)
                        },
                        selectedID: selectedTab == .chats && showingArchive ? "archive" : String(describing: selectedTab), unreadCount: chatsRepository.unreadTotal,
                        showCalls: showCallsTab, showSaved: showSavedMessages,
                        onEdit: { performSidebarSettings("edit") }, onItem: performSidebarItem,
                        badges: badges, onQR: { UnyriHaptics.selection(); setMenu(false); showingProfileQR = true },
                        showsAccounts: true, publicNumber: usersRepository.ownIdentity?.unyriNumber)
                    }
                        .padding(.leading, 8).padding(.bottom, 24)
                        .offset(x: offset)
                        // The modal trait belongs to this one container, not
                        // each descendant (which can exclude sibling rows).
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("main-drawer")
                        .accessibilityAddTraits(.isModal)
                        .accessibilityAction(.escape) { setMenu(false) }
                        .task { _ = try? await usersRepository.getIdentity() }
                        .task(id: "\(auth.sessionGeneration):\(profile?.avatarVersion ?? -1):\(profile?.avatarUrl ?? "")") {
                            await loadSidebarAvatar()
                        }
                        .allowsHitTesting(open)
                        .accessibilityHidden(!open)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .transaction { if reduceMotion { $0.disablesAnimations = true } }
        }
        }
        // Attached here, not in body: LoggedInView.body is at the type checker's limit.
        // The drawer overlay is always mounted.
        .modifier(UnyriNotificationRouteLanding(scenePhase: scenePhase, land: landOnChatsForRoute,
            syncTab: syncChatsTabSelection, qaRoute: qaNotificationRoute))
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
        sidebarProfile = profile
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
                    // Hidden from the first frame: `.toolbar(.hidden)` lands a few frames
                    // later, so a cold launch flashed the system tab bar and shifted the list.
                    .background(UnyriSystemTabBarHider().frame(width: 0, height: 0).accessibilityHidden(true))
                    .environment(\.unyriMenuVisibility, menuVisibility(.chats))
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.chats))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                    .tag(RootDestination.chats)
                    .badge(chatsRepository.unreadTotal)
                if showCallsTab {
                    CallsView(visible: selectedTab == .calls)
                        .environment(\.unyriMenuVisibility, menuVisibility(.calls))
                        .environment(\.unyriDrawerBlockerScope, drawerScope(.calls))
                        .toolbar(.hidden, for: .tabBar)
                        .tabItem { Label("Calls", systemImage: "phone") }
                        .tag(RootDestination.calls)
                }
                ConferenceDirectoryView()
                    .environment(\.unyriMenuVisibility, menuVisibility(.rooms))
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.rooms))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Rooms", systemImage: "person.3") }
                    .tag(RootDestination.rooms)
                ProfileView(quickAction: $settingsRequest)
                    .environment(\.unyriMenuVisibility, menuVisibility(.settings))
                    .environment(\.unyriDrawerBlockerScope, drawerScope(.settings))
                    .toolbar(.hidden, for: .tabBar)
                    .tabItem { Label("Settings", systemImage: "gearshape") }
                    .tag(RootDestination.settings)
            }
            .accessibilityHidden(menuVisible || drawerPeeking)
            // Like Telegram (owner, 18 Nov, B-027): the drawer slides over a still chat list with
            // its scrim; the content no longer follows the panel (the 18 % parallax is removed).
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
        .background(CallMorphWindowAnchor(morph: callMorph).frame(width: 0, height: 0))
        .unyriCallDisplayAwake(state: callController.state, mediaState: callController.audioControls)
        .modifier(CallReturnPresentation(presentation: pictureInPicture.isSystemPresented ? nil : islandPresentation))
        .modifier(ConferencePresentation())
        .modifier(UnyriCrashPresentation(canPrompt: rootMenuAvailable && !menuVisible && !conference.isBusy &&
            !conference.showLobby && conference.error == nil && callController.state == .idle))
        .background(UnyriDrawerPanAnchor(panelPercentage: Double(sidebarStyle.geometryWidth),
            choice: drawerChoice,
            swipeOpen: sidebarStyle.behaviorSwipeOpen, swipeClose: sidebarStyle.behaviorSwipeClose,
            callWindow: callMiniWindowTouches, context: { drawerPanContext },
            changed: { session, translation in
                guard session.isCurrent(drawerPanContext) else { return }
                drawerDrag.value = CGFloat(session.offset(translation))
                if !drawerPeeking && drawerDrag.value != 0 { drawerPeeking = true }
            }, ended: finishMenuPan,
            cancelled: { setMenu(menuVisible) }).allowsHitTesting(false).accessibilityHidden(true))
        .unyriScreenBackground()
        .environment(\.unyriOpenMenu, openMenuEnvironment)
        .environment(\.unyriSavedRequest, savedRequest)
        .environment(\.unyriSearchRequest, searchRequest)
        .overlay { navigationDrawer }
        .overlay(alignment: .top) {
            ForegroundMessageBannerHost(controller: bannerController) { banner in
                setMenu(false)
                selectedTab = .chats
                if let owner = banner.ownerID {
                    UnyriAccountManager.shared.openFromNotification(owner: owner, chat: banner.chatId, message: banner.messageID ?? "")
                } else { chatsRepository.openChatRequest = banner.chatId }
            }.padding(.top, 4)
        }

        .task(id: auth.sessionGeneration) {
            // Resolve the persisted profile outside the drawer layout/drag path.
            let cached = usersRepository.cachedMe()
            sidebarProfile = cached?.id.lowercased() == auth.currentUser?.id.lowercased() ? cached : nil
        }
        .sheet(isPresented: $showingProfileQR) {
            UnyriProfileQRSheet(users: usersRepository, displayName: usersRepository.ownProfile?.displayName ?? auth.currentUser?.displayName ?? "UNYRI")
        }
        .onChange(of: dynamicTypeSize) { _ in setMenu(false) }
        // Keep an open panel open when its renderer changes; discard only the old pan offset.
        .onChange(of: drawerChoice) { _ in setMenu(menuVisible, feedback: false) }
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
            sidebarProfile = nil; sidebarAvatarURL = nil; sidebarAvatarOwner = nil; showingArchive = false; setMenu(false)
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
        // No start from the background: AVKit starts the window itself as the
        // app leaves (automatic inline start), and a failed extra request
        // used to stop the frames of the window it had started.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            pictureInPicture.returnToApplication()
        }
        .overlay {
            // Fades in once the collapse morph has handed the call to the bar,
            // and out when × hides it. One window per call: a new call starts
            // from the default corner and size.
            Group {
                if let c = inlineCallVideo {
                    CallMiniWindow(call: c, quality: callController.quality, memory: callMiniWindowMemory,
                                   touches: callMiniWindowTouches, onReturn: { returnToCall(from: $0) },
                                   onHide: { callWindowHidden = c.call.callId },
                                   toggleMute: { callController.toggleMute() })
                        .id(c.call.callId)
                        .transition(.opacity)
                }
            }
            // Opacity only, so Reduce Motion keeps it.
            .animation(.easeOut(duration: 0.22), value: inlineCallVideo != nil)
        }
        .onDisappear { setMenu(false); if callController.state == .idle || callController.state.isTerminal { pictureInPicture.stop() } }
        .onChange(of: selectedTab) { _ in
            setMenu(false)
            bannerController.dismiss()
            bannerController.navigationChatId = selectedTab == .chats ? chatsRepository.navigationChatId : nil
            chatsRepository.chatsTabSelected = selectedTab == .chats
        }
        .onChange(of: chatsRepository.navigationChatId) { id in
            bannerController.navigationChatId = selectedTab == .chats ? id : nil
        }
        .onAppear { bannerController.navigationChatId = selectedTab == .chats ? chatsRepository.navigationChatId : nil }
        .onChange(of: chatsRepository.isChatListVisible) { _ in bannerController.dismiss() }
        .onChange(of: callController.state.callInfo?.callId) { _ in
            if callController.state.callInfo != nil { dismissKeyboard() }
            // Collapsed while the call was being created: stay collapsed once it rings.
            if minimizedWhileStarting, callController.state.callInfo != nil {
                minimizedWhileStarting = false
                return
            }
            callMinimized = false
        }
        .onChange(of: callKit.startingCall) { starting in
            if starting != nil {
                callMinimized = false
                minimizedWhileStarting = false
            } else if callController.state == .idle {
                // Cancelled or failed before a call existed.
                callMinimized = false
                minimizedWhileStarting = false
            }
        }
        .onChange(of: callActive.wrappedValue) { expanded in
            if expanded { setMenu(false); dismissKeyboard() }
        }
        .onAppear { callCoverClaim = nextCallCoverClaim }
        .onChange(of: nextCallCoverClaim) { callCoverClaim = $0 }
        .task(id: callController.state.isTerminal) {
            guard callController.state.isTerminal else { return }
            // Keep the ended screen briefly so its reason ("Declined", "No answer",
            // "Cancelled") is actually seen, like FaceTime/Telegram; `callActive`
            // keeps the cover up until then.
            do { try await Task.sleep(nanoseconds: 1_500_000_000) }
            catch { return }
            if callScreenVisible { callRotation.beforeLeaving { callController.acknowledgeEnded() } }
            else { callController.acknowledgeEnded() }
        }
        .alert("Call error", isPresented: Binding(
            get: { callController.state == .idle && callKit.actionError != nil },
            set: { if !$0 { callKit.actionError = nil } }
        )) {
            Button("OK") { callKit.actionError = nil }
        } message: { Text(callKit.actionError ?? "") }
        .fullScreenCover(isPresented: callActive) { callScreenCover }
    }

    /// The call cover as one small view: CallScreen compares equal across
    /// this root's renders (its callbacks sit behind a stable reference) and
    /// observes only the call objects, not MessagesRepository.
    private var callScreenCover: CallScreenHost {
        let messages = messagesRepository
        let pip = pictureInPicture
        return CallScreenHost(pictureInPicture: pip, controller: callController, callKit: callKit,
            bannerController: bannerController, morph: callMorph, rotation: callRotation,
            onMinimize: {
                minimizedWhileStarting = callController.state == .idle && callKit.startingCall != nil
                callMinimized = true
            },
            onOpenChat: { chatId in
                selectedTab = .chats
                chatsRepository.openChatRequest = chatId
                callMinimized = true
            },
            setExpanded: { messages.isCallExpanded = $0 },
            onVisibilityChange: { visible in
                callScreenVisible = visible
                if visible {
                    pip.returnToApplication()
                    // Back in the call: the next minimize shows the window again.
                    callWindowHidden = nil
                }
            })
    }

    private var nextCallCoverClaim: CallCoverPolicy.Claim {
        CallCoverPolicy.claim(callController.state, presented: callActive.wrappedValue)
    }

    private var callActive: Binding<Bool> {
        Binding(
            get: {
                // Use the incoming cover only without a system host; the ended
                // screen stays on the cover raised for that call (its reason
                // is seen) but never raises one.
                CallCoverPolicy.presented(callController.state, starting: callKit.startingCall != nil,
                                          minimized: callMinimized, claim: callCoverClaim,
                                          systemHostsIncoming: CallKitCoordinator.systemCallUI)
            },
            set: { if !$0 { callMinimized = true } }
        )
    }

    private var islandPresentation: CallIslandPresentation? {
        guard callMinimized, let banner = activeCallBanner else { return nil }
        return CallIslandPresentation(name: banner.name, isVideo: banner.isVideo,
            connectedAt: callController.connectedAt, status: banner.status, quality: callController.quality,
            morph: callMorph, onReturn: { returnToCall(from: nil) })
    }

    /// Back to the full-screen call: a dark surface grows out of `origin` (the
    /// mini video window) or the bar, then the cover appears without its slide.
    /// PiP restore and CallKit keep the plain presentation.
    private func returnToCall(from origin: CGRect?) {
        callMorph.expand(from: origin, style: .current(reduceMotion: reduceMotion)) { callMinimized = false }
    }

    /// The call's mini video window is on screen (minimized in the app, no
    /// system PiP, not hidden with ×), with the controls it shows.
    private var inlineCallVideo: ConnectedState? {
        guard let c = callController.videoPresentation, callWindowHidden != c.call.callId,
              CallVideoWindowPolicy.showsInline(videoCall: c.videoMode == .video, minimized: callMinimized,
                  fullScreenVisible: callScreenVisible, appActive: scenePhase == .active,
                  systemPipVisible: pictureInPicture.isSystemPresented) else { return nil }
        return c
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    /// The peer identity + media kind for the minimized banner, only while a
    /// outgoing/connecting calls also need a way back before the peer answers.
    private var activeCallBanner: (name: String, isVideo: Bool, status: String?)? {
        if callController.state == .idle, let starting = callKit.startingCall {
            return (starting.name, starting.isVideo, UnyriL10n.string("Calling…"))
        }
        switch callController.state {
        case .outgoingRinging(let call):
            let key: String
            switch callController.ringIndicator?.phase {
            case .ringing: key = "Ringing…"
            case .notOnline: key = "Not online yet"
            default: key = "Calling…"
            }
            return (call.visibleRemoteName, call.mediaKind == .video, UnyriL10n.string(key))
        case .connecting(let call):
            return (call.visibleRemoteName, call.mediaKind == .video, UnyriL10n.string("Connecting…"))
        case .connected(let c):
            return (c.call.visibleRemoteName, c.videoMode == .video, nil)
        case .reconnecting(let call):
            // With a timer running the bars say it (after the same ~1.2 s as
            // the call screen); a blip no longer flashes "Reconnecting…".
            return (call.visibleRemoteName,
                    callController.audioControls.map { $0.videoMode == .video } ?? (call.mediaKind == .video),
                    callController.connectedAt == nil ? UnyriL10n.string("Reconnecting…") : nil)
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
        // Global search is root content; Contacts is a standard push from Chats.
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
    static func panelWidth(rootWidth: Double, percentage: Double, accessibility: Bool = false, choice: DrawerChoice = .defaultValue, textScale: Double = 1) -> Double {
        // Gesture travel includes the floating panel's 8pt leading gap.
        choice.width(screenWidth: rootWidth, accessibility: accessibility, textScale: textScale) + 8
    }
    static func start(context: Context, x: Double, rootWidth: Double,
                      panelPercentage: Double = Double(UnyriSavedSidebarStyle.geometryWidth),
                      swipeOpen: Bool = true, swipeClose: Bool = true, accessibility: Bool = false, choice: DrawerChoice = .defaultValue,
                      textScale: Double = 1) -> Session? {
        guard context.active, x.isFinite, rootWidth.isFinite, rootWidth > 1,
              (0...rootWidth).contains(x) else { return nil }
        let width = panelWidth(rootWidth: rootWidth, percentage: panelPercentage, accessibility: accessibility, choice: choice, textScale: textScale)
        if context.menuVisible { return swipeClose ? Session(context: context, width: width, mode: .closing) : nil }
        guard swipeOpen, context.allowsOpening, x <= 22 else { return nil }
        return Session(context: context, width: width, mode: .opening)
    }
}

/// Noninteractive anchor only: the owned recognizer lives on its hosting
/// window. Vertical drags fail in shouldBegin before stealing a list scroll.
private struct UnyriDrawerPanAnchor: UIViewRepresentable {
    let panelPercentage: Double
    let choice: DrawerChoice
    let swipeOpen: Bool
    let swipeClose: Bool
    let callWindow: CallMiniWindowTouchAreas
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
        view.choice = choice
        view.callWindow = callWindow
        view.cancelObsoletePan()
    }
    static func dismantleUIView(_ view: UnyriDrawerPanView, coordinator: ()) { view.detach() }
}

private final class UnyriDrawerPanView: UIView, UIGestureRecognizerDelegate {
    var panelPercentage = Double(UnyriSavedSidebarStyle.geometryWidth)
    var choice = DrawerChoice.defaultValue
    var swipeOpen = true
    var swipeClose = true
    var callWindow: CallMiniWindowTouchAreas?
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
    private var drawerWidth: Double { UnyriDrawerPanPolicy.panelWidth(rootWidth: Double(bounds.width), percentage: panelPercentage, accessibility: traitCollection.preferredContentSizeCategory.isAccessibilityCategory, choice: choice, textScale: drawerTextScale) }
    /// Same value as SwiftUI's @ScaledMetric(relativeTo: .body) for this trait collection.
    private var drawerTextScale: Double { Double(UIFontMetrics(forTextStyle: .body).scaledValue(for: 1, compatibleWith: traitCollection)) }
    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard recognizer === pan, pan.state == .possible, let window = gestureWindow,
              let current, let touched = touch.view else { return false }
        let point = touch.location(in: self)
        guard bounds.contains(point) else { return false }
        // The mini call window and its tab keep their own drags: a tab tucked
        // at the leading edge sits inside the 22 pt opening strip.
        if callWindow?.contains(touch.location(in: window)) == true { return false }
        // Presented sheets/calls/keyboard windows never belong to the root.
        var responder: UIResponder? = self
        while let value = responder, !(value is UIViewController) { responder = value.next }
        guard let owner = responder as? UIViewController,
              touched.isDescendant(of: owner.view), touched.window === window else { return false }
        candidate = UnyriDrawerPanPolicy.start(context: current(), x: Double(point.x), rootWidth: Double(bounds.width),
            panelPercentage: panelPercentage, swipeOpen: swipeOpen, swipeClose: swipeClose,
            accessibility: traitCollection.preferredContentSizeCategory.isAccessibilityCategory, choice: choice, textScale: drawerTextScale)
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
        let simultaneous = recognizer === pan && other is UIPanGestureRecognizer && other.view is UIScrollView
        if simultaneous { underlyingScrollPan = other }
        return simultaneous
    }
    /// The list pan tracking the same touch; stopped once the drawer owns the drag.
    private weak var underlyingScrollPan: UIGestureRecognizer?
    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        switch recognizer.state {
        case .began:
            guard let candidate, let current, candidate.isCurrent(current()) else { cancelPan(); return }
            active = candidate; self.candidate = nil
            // Telegram-like (owner, 18 Nov): a drawer drag must not also scroll the chat list.
            // Toggling isEnabled cancels only the list's current drag.
            if let scroll = underlyingScrollPan, scroll.isEnabled { scroll.isEnabled = false; scroll.isEnabled = true }
            underlyingScrollPan = nil
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
    var quality: CallQualityMonitor? = nil
    var morph: CallMorphAnchors? = nil
    let onReturn: () -> Void

    var body: some View {
        // Not `live`: its breathing halo looped for as long as the call was
        // minimized, and the timer already says the call is live.
        ConversationReturnBanner(title: name, systemImage: isVideo ? "video.fill" : "phone.fill", onReturn: onReturn, live: false, subtitle: {
            HStack(spacing: 6) {
                if let status { Text(status) }
                else if let connectedAt {
                    // Observes only the quality monitor: the root never re-renders for it.
                    // It also says "Reconnecting…", after the same delay as the call screen.
                    if let quality {
                        CallBannerStatus(monitor: quality, connectedAt: connectedAt)
                    } else {
                        TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                            Text(formatCallDuration(context.date.timeIntervalSince(connectedAt))).monospacedDigit()
                        }
                    }
                } else { Text("In call") }
                Text("·"); Text("Tap to return")
            }
        }, accessory: { EmptyView() })
        .background {
            // The morphs' target. Written into a reference, so nothing
            // re-renders; the banner pads its capsule by 10 × 4 pt.
            if let morph {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { morph.bannerFrame = proxy.frame(in: .global).insetBy(dx: 10, dy: 4) }
                        .onChange(of: proxy.frame(in: .global)) { morph.bannerFrame = $0.insetBy(dx: 10, dy: 4) }
                }
            }
        }
        .accessibilityLabel(UnyriL10n.string("Return to call") + ". " + name)
        .accessibilityIdentifier("call-return")
    }

}

private extension CallUiState {
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
            } catch { self.error = UnyriErrorText.message(error) ?? self.error }
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
        let name = CallPeerTitlePolicy.title(displayName:identity.map { DisplayNameSanitizer.display($0.displayName) },username:identity?.username,unknown:UnyriL10n.string("Unknown user"))
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
                    } catch { if !Task.isCancelled { self.error = UnyriErrorText.message(error) ?? self.error } }
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
                                    self.error = UnyriErrorText.message(error) ?? self.error
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
                                    } catch { self.error = UnyriErrorText.message(error) ?? self.error }
                                }
                            }, visible: visible && !showChat, blocked: network.blocked)
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
                    } catch { if Task.isCancelled { return }; self.error = UnyriErrorText.message(error) ?? self.error }
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

/// Finite transitions are scoped to this small host, never the whole root view.
@MainActor
struct ForegroundMessageBannerHost: View {
    @ObservedObject var controller: ForegroundMessageBannerController
    let onTap: (ForegroundMessageBannerController.Banner) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        VStack(spacing: 0) {
            if let banner = controller.banner {
                ForegroundMessageBannerView(banner: banner, onTap: {
                    guard controller.banner?.generation == banner.generation else { return }
                    controller.dismiss()
                    onTap(banner)
                }, onDismiss: {
                    guard controller.banner?.generation == banner.generation else { return }
                    controller.dismiss()
                })
                    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
            }
        }.animation(.easeOut(duration: 0.18), value: controller.banner != nil)
    }
}

/// Compact Glass Flow banner (avatar + name + one-line preview). Tap opens the
/// conversation; accessibility announces sender + preview.
struct ForegroundMessageBannerView: View {
    let banner: ForegroundMessageBannerController.Banner
    let onTap: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                UserAvatar(url: banner.avatarURL, name: title, size: 32)
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
            }
            .padding(.trailing, 48)
            .frame(minHeight: 44)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("\(title). \(previewText)")
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: UnyriShapes.radiusCard, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusCard, style: .continuous)
            .stroke(UnyriColors.outlineSecondary, lineWidth: 1).allowsHitTesting(false))
        .overlay(alignment: .trailing) {
            Button { onDismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(UnyriColors.secondaryText)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Dismiss notification").accessibilityIdentifier("inapp-notification-dismiss")
            .padding(.trailing, 14)
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .frame(maxWidth: 420)
        .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
        .simultaneousGesture(DragGesture(minimumDistance: 12).onEnded { value in
            if value.translation.height < -24 { onDismiss() }
        })
        .accessibilityIdentifier("inapp-notification-banner")
        .buttonStyle(.plain)
    }
    private var title: String {
        banner.senderName ?? UnyriL10n.string("Chats")
    }

    private var previewText: String {
        switch banner.preview {
        case .text(let text): return DirectFilePreviewText.display(text: text) ?? text
        case .video: return UnyriL10n.string("Video")
        case .photo: return UnyriL10n.string("Photo")
        case .voice: return UnyriL10n.string("Voice message")
        case .file(let filename): return DirectFilePreviewText.file(filename)
        case .deleted: return UnyriL10n.string("Message deleted")
        }
    }
}


/// Interactive drawer offset, observed only by the drawer layer.
final class UnyriDrawerDrag: ObservableObject {
    @Published var value: CGFloat = 0
}

private struct UnyriDrawerDragReader<Content: View>: View {
    @ObservedObject var drag: UnyriDrawerDrag
    @ViewBuilder let content: (CGFloat) -> Content
    var body: some View { content(drag.value) }
}


/// Sidebar counters, observed in this small reader only (calls history and live
/// transfers must not re-render the root host).
private struct UnyriSidebarBadgeReader<Content: View>: View {
    @EnvironmentObject private var calls: CallsRepository
    @EnvironmentObject private var chats: ChatsRepository
    @EnvironmentObject private var auth: AuthRepository
    let transfers: DirectFileTransfers?
    @ViewBuilder let content: ([String: String]) -> Content
    var body: some View {
        if let transfers {
            UnyriSidebarTransfersCount(transfers: transfers) { active in content(badges(active: active)) }
        } else { content(badges(active: 0)) }
    }
    private func badges(active: Int) -> [String: String] {
        var result: [String: String] = [:]
        let archived = chats.archivedChatIDs.reduce(0) { $0 + (chats.unreadCounts[$1] ?? 0) }
        result["archive"] = DrawerCPolicy.count(archived)
        let owner = auth.currentUser.flatMap { UUID(uuidString: $0.id) }
        let missed = calls.history.filter { $0.unread && $0.isMissedIncoming(owner: owner) }.count
        result["calls"] = DrawerCPolicy.count(missed)
        result["transfers"] = DrawerCPolicy.count(active, capped: false)
        return result
    }
}

private struct UnyriSidebarTransfersCount<Content: View>: View {
    @ObservedObject var transfers: DirectFileTransfers
    @ViewBuilder let content: (Int) -> Content
    var body: some View { content(transfers.records.values.filter { !$0.phase.terminal }.count) }
}


/// Canonical number + configured server origin; opening requires discovery.
@MainActor
struct UnyriProfileQRSheet: View {
    let displayName: String
    private let users: UsersRepository?
    @State private var identity: OwnerIdentity?
    @State private var failure: String?
    @State private var qrImage: UIImage?
    @Environment(\.dismiss) private var dismiss
    init(users: UsersRepository, displayName: String) {
        self.users = users; self.displayName = displayName
        _identity = State(initialValue: users.ownIdentity)
    }
    init(identity: OwnerIdentity, displayName: String = "") {
        users = nil; self.displayName = displayName; _identity = State(initialValue: identity)
    }
    var body: some View {
        NavigationStack {
            ScrollView {
            VStack(spacing: 18) {
                if let image = qrImage {
                    Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                        .frame(width: 220, height: 220).padding(16)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                        .accessibilityLabel(Text("QR code"))
                        .accessibilityIdentifier("profile-qr-image")
                }
                VStack(spacing: 4) {
                    Text(displayName).font(.title3.weight(.semibold))
                    if let identity {
                        Text(PublicIdentityPolicy.formattedNumber(identity.unyriNumber)).font(.title2.monospacedDigit())
                            .accessibilityIdentifier("profile-qr-number")
                        if let username = identity.publicUsername { Text("@" + username).font(.subheadline).foregroundStyle(.secondary) }
                    }
                }
                Text("Scan to open your public profile on this server. Your privacy settings still apply. This number link never changes.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, 32)
                if let identity {
                    Text(identity.publicOrigin).font(.caption).foregroundStyle(.secondary)
                    Button("Copy number") { UIPasteboard.general.string = identity.unyriNumber }
                    if let link = identity.link {
                        ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") }
                            .buttonStyle(.bordered).accessibilityIdentifier("profile-qr-share")
                    }
                }
                if let failure { Text(failure).font(.footnote) }
            }
            .padding(.top, 24).padding(.bottom, 32)
            .frame(maxWidth: .infinity)
            }
            .navigationTitle(Text("My QR code")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.large])
        .task(id: identity?.link) {
            if let link = identity?.link { qrImage = Self.qr(link) }
            else { qrImage = nil }
        }
        .task {
            guard let users else { return }
            do { identity = try await users.getIdentity() }
            catch is CancellationError { }
            catch { identity = nil; failure = IdentityErrorText.message(error) }
        }
    }
    static func qr(_ text: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

extension Notification.Name {
    /// unyri://user/<username> opened (scanned profile QR).
    static let unyriOpenUserLink = Notification.Name("UNYRIOpenUserLink")
}



/// Notification-tap landing for the root view (kept out of LoggedInView.body, whose
/// modifier chain is at the type checker's limit).
private struct UnyriNotificationRouteLanding: ViewModifier {
    let scenePhase: ScenePhase
    let land: () -> Void
    let syncTab: () -> Void
    let qaRoute: () -> Void
    @State private var wasInBackground = false
    func body(content: Content) -> some View {
        content
            .onAppear { syncTab() }
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOChatReminder"))) { _ in land() }
            .onChange(of: scenePhase) { phase in
                if phase == .background { wasInBackground = true }
                if phase == .active, wasInBackground { wasInBackground = false; qaRoute() }
            }
    }
}
