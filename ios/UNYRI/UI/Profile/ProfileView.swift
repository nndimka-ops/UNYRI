import SwiftUI
import SafariServices

// UIKit navigation items receive resolved text; observing the current locale
// updates the title preference without replacing navigation/account identity.
private func unyriSettingsNavigationTitle(_ key: String, locale: Locale) -> String {
    String(localized: String.LocalizationValue(stringLiteral: key), bundle: UnyriL10n.bundle, locale: locale)
}

// Shared settings presentation only: no per-row IO, blur or competing gestures.
struct UnyriSettingsPage<Content: View>: View {
    @Environment(\.locale) private var settingsLocale
    let title: String
    let content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) { content }
                .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 32)
        }
        .unyriScreenBackground().navigationTitle(unyriSettingsNavigationTitle(title, locale: settingsLocale)).navigationBarTitleDisplayMode(.inline)
        .buttonStyle(UnyriQuietButtonStyle())
        .unyriBlocksRootDrawer()
    }
}

struct UnyriSettingsGroup<Content: View>: View {
    let title: String?
    let content: Content
    init(_ title: String? = nil, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(LocalizedStringKey(title)).font(.caption.weight(.semibold)).foregroundColor(UnyriColors.secondaryText)
                    .padding(.horizontal, 4).accessibilityAddTraits(.isHeader)
            }
            VStack(spacing: 0) { content }
                .background(RoundedRectangle(cornerRadius: 18).fill(Color(hex: 0x1B1B1D)))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(hex: 0x343437), lineWidth: 1))
        }
    }
}

struct UnyriSettingsRow: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    let title: String
    let icon: String
    let tint: Color
    var subtitle: String? = nil
    var showsChevron = true
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 16, weight: .semibold)).foregroundColor(colorStyle.decorative(tint))
                .frame(width: 32, height: 32).background(RoundedRectangle(cornerRadius: 10).fill(colorStyle.decorative(tint).opacity(0.12)))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(LocalizedStringKey(title)).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                if let subtitle {
                    Text(LocalizedStringKey(subtitle)).font(.caption).foregroundColor(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }.fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if showsChevron { Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundColor(UnyriColors.veryMutedText).accessibilityHidden(true) }
        }.padding(.horizontal, 14).padding(.vertical, 12).frame(minHeight: 56).contentShape(Rectangle())
    }
}

struct UnyriSettingsDivider: View {
    var body: some View { Rectangle().fill(Color(hex: 0x343437)).frame(height: 0.5).padding(.leading, 58).accessibilityHidden(true) }
}

struct UnyriSettingsNote: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(LocalizedStringKey(text)).font(.footnote).foregroundColor(UnyriColors.secondaryText)
            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4)
    }
}

private struct AccountDevice: Codable, Identifiable {
    let id: String
    let displayName: String
    let platform: String
    let lastActiveAt: Date?
    let isCurrent: Bool
}

private struct AccountDeviceList: Decodable { let devices: [AccountDevice] }

private struct DevicesSettingsView: View {
    @EnvironmentObject private var auth: AuthRepository
    @State private var devices: [AccountDevice] = []
    @State private var showingCachedDevices = false
    @State private var error: String?
    @State private var busy = false
    @State private var revoke: AccountDevice?
    private let client = APIClient(baseURL: AppConfig.apiBaseURL)

    var body: some View {
        UnyriSettingsPage("Devices") {
            if let error { Text(error).font(.footnote).foregroundColor(UnyriColors.danger).fixedSize(horizontal: false, vertical: true) }
            if showingCachedDevices { UnyriSettingsNote("Saved data. Connect to refresh.") }
            UnyriSettingsGroup("Active sessions") {
                ForEach(devices) { device in
                    HStack(spacing: 12) {
                        Image(systemName: "iphone").font(.system(size: 18, weight: .medium))
                            .unyriDecorativeForeground(Color(hex: 0x7BC9EC)).frame(width: 32, height: 32)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x7BC9EC).opacity(0.12))).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(device.displayName).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                            if device.isCurrent { Text("This device").font(.caption).foregroundColor(UnyriColors.success) }
                            if let date = device.lastActiveAt { Text(date, style: .relative).font(.caption).foregroundColor(UnyriColors.secondaryText) }
                        }.fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 4)
                        if !device.isCurrent {
                            Button("Revoke", role: .destructive) { revoke = device }
                                .font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.danger)
                                .frame(minWidth: 44, minHeight: 44).padding(.horizontal, 6)
                                .background(RoundedRectangle(cornerRadius: 10).fill(UnyriColors.danger.opacity(0.08))).disabled(busy)
                        }
                    }.padding(14).frame(minHeight: 60)
                }
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            UnyriSettingsGroup {
                Button { Task { await load() } } label: {
                    UnyriSettingsRow(title: "Refresh", icon: "arrow.clockwise", tint: Color(hex: 0x7BC9EC), showsChevron: false)
                }.disabled(busy)
            }
        }
        .task { await load() }.refreshable { await load() }
        .alert("Sign out this device?", isPresented: Binding(get: { revoke != nil }, set: { if !$0 { revoke = nil } })) {
            Button("Cancel", role: .cancel) { revoke = nil }
            Button("Revoke", role: .destructive) {
                guard let device = revoke else { return }
                revoke = nil
                Task { await revokeDevice(device.id) }
            }
        } message: { Text(String(format: UnyriL10n.string("%@ will lose access to this account.", comment: ""), revoke?.displayName ?? UnyriL10n.string("The selected device", comment: ""))) }
    }

    private func load() async {
        guard !busy else { return }
        busy = true; defer { busy = false }
        let generation = auth.sessionGeneration
        if let cached = auth.cachedScreen("devices", as: [AccountDevice].self), devices.isEmpty { devices = cached; showingCachedDevices = true }
        do {
            let token = try await auth.currentAccessToken()
            guard generation == auth.sessionGeneration else { return }
            let response = try await client.get("/devices", accessToken: token, as: AccountDeviceList.self)
            guard generation == auth.sessionGeneration, !Task.isCancelled else { return }
            devices = response.devices.sorted { $0.isCurrent && !$1.isCurrent }; error = nil
            showingCachedDevices = false
            auth.saveScreen("devices", generation: generation, value: devices)
        } catch { self.error = error.localizedDescription }
    }

    private func revokeDevice(_ id: String) async {
        guard !busy else { return }
        busy = true
        let generation = auth.sessionGeneration
        do {
            let token = try await auth.currentAccessToken()
            guard generation == auth.sessionGeneration else { busy = false; return }
            try await client.deleteNoContent("/devices/\(id)", accessToken: token)
            guard generation == auth.sessionGeneration else { busy = false; return }
            devices.removeAll { $0.id == id }; error = nil
            auth.saveScreen("devices", generation: generation, value: devices)
        } catch { self.error = error.localizedDescription }
        busy = false
    }
}

private struct ConnectionSettingsView: View {
    @State private var status: String?
    @State private var checking = false
    private let server = AppConfig.apiBaseURL

    var body: some View {
        UnyriSettingsPage("Server connection") {
            UnyriSettingsGroup("Current server") {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "server.rack").font(.system(size: 18)).unyriDecorativeForeground(Color(hex: 0x7BC9EC)).accessibilityHidden(true)
                    Text(server.absoluteString).font(.subheadline).foregroundColor(UnyriColors.primaryText)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    Spacer(minLength: 0)
                }.padding(14)
            }
            UnyriSettingsGroup {
                Button { Task { await check() } } label: {
                    UnyriSettingsRow(title: checking ? "Checking…" : "Check connection", icon: "network", tint: Color(hex: 0x83D9B7), showsChevron: false)
                }.disabled(checking)
                if checking { ProgressView().padding(.bottom, 14) }
                if let status {
                    UnyriSettingsDivider()
                    Text(status).font(.footnote).foregroundColor(UnyriColors.primaryText)
                        .fixedSize(horizontal: false, vertical: true).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            UnyriSettingsNote("To use another server, sign out and choose the server on the sign-in screen.")
        }
    }

    private func check() async {
        checking = true; defer { checking = false }
        do {
            var request = URLRequest(url: server.appendingPathComponent("health"))
            request.timeoutInterval = 10
            let (_, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            status = (200..<300).contains(code) ? UnyriL10n.string("Server is reachable", comment: "") : String(format: UnyriL10n.string("Server responded with HTTP %d", comment: ""), code)
        } catch { status = UnyriL10n.string("Could not reach the server. Check Wi-Fi and the server address.", comment: "") }
    }
}

struct ProfileView: View {
    @Environment(\.locale) private var settingsLocale
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Binding var quickAction: String?
    init(quickAction: Binding<String?> = .constant(nil)) { _quickAction = quickAction }
    @State private var quickDestination: String?
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var auth: AuthRepository
    @EnvironmentObject private var realtime: RealtimeClient
    @EnvironmentObject private var sidebarStore: UnyriSidebarAppearanceStore
    @AppStorage("showSavedMessages") private var showSavedMessages = true
    @AppStorage("mediaAutoWifi") private var mediaWifi = false
    @AppStorage("mediaAutoMobile") private var mediaMobile = false
    @AppStorage("liveDraftEnabled") private var liveDraftEnabled = false
    @AppStorage("chatActivityEnabled") private var activityEnabled = false
    @AppStorage("shareDiagnostics") private var shareDiagnostics = false
    @AppStorage("showCallsTab") private var showCallsTab = true

    @State private var profile: UserProfile?
    @State private var showingCachedProfile = false
    @State private var avatarURL: URL?
    @State private var errorMessage: String?
    @State private var isLoggingOut = false
    @State private var isShowingEdit = false
    @State private var isShowingStorage = false
    @State private var profileLoadId = UUID()
    private struct LogoutScope: Identifiable {
        let id = UUID()
        let owner: String
        let server: String
        let generation: UInt64
    }
    @State private var logoutPrompt: LogoutScope?
    @State private var pendingLogoutBackup: LogoutScope?
    @State private var logoutBackupScope: LogoutScope?

    var body: some View {
        NavigationStack {
            mainSettingsContent
            .unyriScreenBackground().navigationTitle(unyriSettingsNavigationTitle("Settings", locale: settingsLocale))
            .performanceScreen(.settings)
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { UnyriMenuButton() } }
            .modifier(CallIslandToolbar())
            .navigationBarTitleDisplayMode(.large)
            .navigationDestination(isPresented: $isShowingEdit) {
                if let profile {
                    EditProfileView(initial: profile, onSaved: profileSaved)
                }
            }
            .navigationDestination(isPresented: $isShowingStorage) {
                StorageView()
            }
            .navigationDestination(isPresented: Binding(get: { quickDestination != nil }, set: { if !$0 { quickDestination = nil } })) {
                switch quickDestination {
                case "edit":
                    if let profile { EditProfileView(initial: profile, onSaved: profileSaved) }
                case "server": ConnectionSettingsView()
                case "devices": DevicesSettingsView()
                case "feedback": BugReportView()
                default: helpSettingsPage("Help & app")
                }
            }
            .onAppear { consumeQuickAction() }
            .onChange(of: quickAction) { _ in consumeQuickAction() }
            .onChange(of: profile) { _ in consumeQuickAction() }
            .navigationDestination(isPresented: Binding(get: { logoutBackupScope != nil }, set: { if !$0 { logoutBackupScope = nil } })) {
                HistoryKeySettingsView()
            }
            .sheet(item: $logoutPrompt, onDismiss: openLogoutBackupIfRequested) { scope in
                logoutConfirmation(scope)
            }
            .onChange(of: auth.currentUser?.id) { _ in discardLogoutPrompt() }
            .onChange(of: auth.sessionGeneration) { _ in discardLogoutPrompt() }
            .onChange(of: AppConfig.apiBaseURL.absoluteString) { _ in discardLogoutPrompt() }
            .task(id: realtime.state) {
                if profile == nil || (realtime.state == .connected && (showingCachedProfile || errorMessage != nil)) {
                    await load()
                }
            }
            .onReceive(users.$ownProfile.compactMap { $0 }.removeDuplicates()) { updated in
                guard updated.id.lowercased() == auth.currentUser?.id.lowercased(),
                      profile.map({ $0.profileVersion <= updated.profileVersion }) ?? true else { return }
                let avatarChanged = profile?.avatarVersion != updated.avatarVersion || profile?.avatarUrl != updated.avatarUrl
                profile = updated
                showingCachedProfile = false
                if avatarChanged { Task { await resolveAvatar(for: updated) } }
            }
            .refreshable { await load() }
            .onDisappear { profileLoadId = UUID() }
        }
        .unyriBlocksRootDrawer(isShowingEdit || isShowingStorage || quickDestination != nil ||
            logoutPrompt != nil || logoutBackupScope != nil)
    }

    private var mainSettingsContent: some View {
        ScrollView {
            VStack(spacing: 18) {
                if let profile {
                    accountCard(profile)
                    if showingCachedProfile { UnyriSettingsNote("Saved data. Connect to refresh.") }
                    if ["owner", "moderator"].contains(profile.adminRole ?? "") {
                        NavigationLink { AdminPanelView(role: profile.adminRole ?? "") } label: {
                            settingsLabel("Server administration", icon: "lock.shield")
                        }.background(settingsPanel)
                    }
                } else if let errorMessage { errorView(errorMessage) }
                else { ProgressView().frame(maxWidth: .infinity, minHeight: 80) }
                serverStatusRow
                historyCard
                UnyriSettingsGroup("Account & privacy") {
                    NavigationLink { accountSettings } label: { settingsLabel("Account", icon: "person.crop.circle") }
                        .accessibilityIdentifier("settings-account")
                    settingsDivider
                    NavigationLink { privacySettings } label: { settingsLabel("Privacy & Security", icon: "lock.shield") }
                        .accessibilityIdentifier("settings-privacy")
                }
                UnyriSettingsGroup("Personalize") {
                    NavigationLink { UnyriNotificationSettingsView() } label: { settingsLabel("Notifications", icon: "bell") }
                        .accessibilityIdentifier("settings-notifications")
                    settingsDivider
                    NavigationLink { appearanceSettings } label: { settingsLabel("Appearance", icon: "paintpalette") }
                        .accessibilityIdentifier("settings-appearance")
                    settingsDivider
                    NavigationLink { chatSettings } label: { settingsLabel("Chats", icon: "bubble.left.and.bubble.right") }
                        .accessibilityIdentifier("settings-chats")
                }
                UnyriSettingsGroup("App & connection") {
                    NavigationLink { dataSettings } label: { settingsLabel("Connection & storage", icon: "antenna.radiowaves.left.and.right") }
                        .accessibilityIdentifier("settings-data")
                    settingsDivider
                    NavigationLink { helpSettings } label: { settingsLabel("Help & app", icon: "questionmark.circle") }
                        .accessibilityIdentifier("settings-help")
                }
                logoutButton
                Text(AppVersion.display(
                    shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                    build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String))
                    .font(.caption).foregroundColor(UnyriColors.veryMutedText).textSelection(.enabled)
                    .accessibilityIdentifier("settings-build-version")
            }.padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 28)
        }.buttonStyle(UnyriQuietButtonStyle())
    }

    private var accountSettings: some View {
        UnyriSettingsPage("Account") {
            UnyriSettingsGroup("Profile") {
                Button { isShowingEdit = true } label: {
                    UnyriSettingsRow(title: "Edit profile", icon: "person.crop.circle", tint: Color(hex: 0xAFA4F7))
                }.disabled(profile == nil)
            }
            UnyriSettingsGroup("Server") {
                NavigationLink { ConnectionSettingsView() } label: {
                    UnyriSettingsRow(title: "Server connection", icon: "antenna.radiowaves.left.and.right", tint: Color(hex: 0x7BC9EC))
                }
                UnyriSettingsDivider()
                NavigationLink { ServerCardView() } label: {
                    UnyriSettingsRow(title: "Server card", icon: "server.rack", tint: Color(hex: 0x7BC9EC))
                }
            }
            UnyriSettingsGroup {
                NavigationLink { AccountRecoveryView(signedIn: true, server: AppConfig.apiBaseURL) } label: {
                    UnyriSettingsRow(title: "Account recovery", icon: "key", tint: Color(hex: 0xE6BD73))
                }
            }
            UnyriSettingsGroup("Account actions") {
                NavigationLink { DeleteAccountView() } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.crop.circle.badge.minus").font(.system(size: 17, weight: .semibold))
                            .frame(width: 32, height: 32).background(RoundedRectangle(cornerRadius: 10).fill(UnyriColors.danger.opacity(0.12)))
                            .accessibilityHidden(true)
                        Text("Delete account").font(.subheadline.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 4)
                        Image(systemName: "chevron.right").font(.caption).accessibilityHidden(true)
                    }.foregroundColor(UnyriColors.danger).padding(14).frame(minHeight: 56).contentShape(Rectangle())
                }
            }
        }
    }

    private var appearanceSettings: some View {
        UnyriSettingsPage("Appearance") {
            UnyriAppColorThemePicker()
            UnyriSettingsGroup {
                NavigationLink { UnyriChatAppearanceSettingsView() } label: {
                    UnyriSettingsRow(title: "Theme appearance", icon: "paintbrush.pointed", tint: Color(hex: 0x7BC9EC))
                }.accessibilityIdentifier("chat-appearance-open")
                UnyriSettingsDivider()
                NavigationLink { UnyriSidebarAppearanceSettingsView() } label: {
                    UnyriSettingsRow(title: "Sidebar appearance", icon: "sidebar.left", tint: Color(hex: 0xAFA4F7))
                }.accessibilityIdentifier("sidebar-appearance-open")
            }
            UnyriSettingsGroup("Language") { AppLanguagePicker().padding(14).frame(minHeight: 52) }
            UnyriSettingsGroup("Navigation") { navigationSection }
        }
    }

    private var chatSettings: some View {
        UnyriSettingsPage("Chats") {
            UnyriSettingsGroup {
                NavigationLink { UnyriQuickReactionSettingsView() } label: {
                    UnyriSettingsRow(title: "Quick reaction", icon: "face.smiling", tint: Color(hex: 0x83D9B7))
                }
            }
        }
    }

    private var privacySettings: some View {
        UnyriSettingsPage("Privacy & Security") {
            UnyriSettingsGroup {
                NavigationLink { UnyriAppLockSettingsView() } label: {
                    UnyriSettingsRow(title: "App lock", icon: "lock.iphone", tint: Color(hex: 0xAFA4F7))
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriPrivacySettingsView() } label: {
                    UnyriSettingsRow(title: "Online status & read receipts", icon: "eye", tint: Color(hex: 0x83D9B7))
                }
            }
            UnyriSettingsGroup("Devices") {
                NavigationLink { DevicesSettingsView() } label: {
                    UnyriSettingsRow(title: "Devices", icon: "iphone", tint: Color(hex: 0xE6BD73))
                }
            }
            UnyriSettingsGroup {
                NavigationLink { UnyriAccountAutoDeleteSettingsView() } label: {
                    UnyriSettingsRow(title: "Account auto-delete", icon: "timer", tint: UnyriColors.danger)
                }.accessibilityIdentifier("settings-account-auto-delete")
            }
            UnyriSettingsGroup {
                NavigationLink { BlockedAccountsView() } label: {
                    UnyriSettingsRow(title: "Blocked accounts", icon: "person.crop.circle.badge.xmark", tint: Color(hex: 0xEC8997))
                }.accessibilityIdentifier("settings-blocked-accounts")
            }
            if let issue = auth.installationProtectionIssue { UnyriSettingsNote(issue) }
            UnyriSettingsGroup("Live draft") {
                settingsToggle("Show my live draft", icon: "text.cursor", tint: Color(hex: 0xAFA4F7), value: $liveDraftEnabled)
            }
            UnyriSettingsNote("Your unsent text is visible to your conversation partner while you type. Encrypted, never saved to chat history. Off by default.")
            UnyriSettingsGroup("Activity status") {
                settingsToggle("Share typing and recording status", icon: "waveform", tint: Color(hex: 0x83D9B7), value: $activityEnabled)
            }
            UnyriSettingsNote("Optional activity metadata is visible to the service. Message text is never included.")
        }
    }

    private var dataSettings: some View {
        UnyriSettingsPage("Data & Storage") {
            UnyriSettingsGroup {
                NavigationLink { CallQualitySettingsView() } label: {
                    UnyriSettingsRow(title: "Call quality", icon: "phone", tint: Color(hex: 0x83D9B7))
                }
                UnyriSettingsDivider()
                NavigationLink { StorageView() } label: {
                    UnyriSettingsRow(title: "Storage Usage", icon: "internaldrive", tint: Color(hex: 0x7BC9EC))
                }
            }
            UnyriSettingsGroup {
                NavigationLink { UnyriAutomaticDownloadsView() } label: {
                    UnyriSettingsRow(title: "Automatic downloads", icon: "arrow.down.circle", tint: Color(hex: 0x7BC9EC))
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriCachePolicyView() } label: {
                    UnyriSettingsRow(title: "Cache limits & chats", icon: "internaldrive", tint: Color(hex: 0xAFA4F7))
                }
            }
        }
    }

    private var helpSettings: some View {
        helpSettingsPage("About")
    }

    private func helpSettingsPage(_ title: String) -> some View {
        UnyriSettingsPage(title) {
            UnyriSettingsGroup("Help & app") {
                NavigationLink { BugReportView() } label: {
                    UnyriSettingsRow(title: "Report a problem", icon: "exclamationmark.bubble", tint: Color(hex: 0xE995AD))
                }
                UnyriSettingsDivider()
                NavigationLink { PerformanceDiagnosticsView() } label: {
                    UnyriSettingsRow(title: "Performance diagnostics", icon: "waveform.path.ecg", tint: Color(hex: 0x7BC9EC))
                }
            }
            UnyriSettingsGroup {
                settingsToggle("Send diagnostics", icon: "chart.bar.xaxis", tint: Color(hex: 0xAFA4F7), value: $shareDiagnostics)
            }
            UnyriSettingsNote("Technical error codes only. No messages, files or keys.")
            UnyriSettingsGroup {
                NavigationLink { AlphaAboutView() } label: {
                    UnyriSettingsRow(title: "About UNYRI", icon: "info.circle", tint: Color(hex: 0x7BC9EC))
                }
                UnyriSettingsDivider()
                NavigationLink { AlphaTermsView() } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("terms"), icon: "doc.text", tint: UnyriColors.secondaryText)
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriLegalView(documentID: "privacy") } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("privacy"), icon: "hand.raised", tint: UnyriColors.secondaryText)
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriLegalView(documentID: "support") } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("support"), icon: "questionmark.circle", tint: UnyriColors.secondaryText)
                }
            }
            aboutSection
        }
    }

    private func settingsToggle(_ title: String, icon: String, tint: Color, value: Binding<Bool>) -> some View {
        Toggle(isOn: Binding(get: { value.wrappedValue }, set: { next in
            guard next != value.wrappedValue else { return }
            value.wrappedValue = next
            Task { @MainActor in UnyriHaptics.selection() }
        })) {
            UnyriSettingsRow(title: title, icon: icon, tint: tint, showsChevron: false)
        }.tint(colorStyle.controlTint(tint)).padding(.trailing, 14).accessibilityLabel(Text(LocalizedStringKey(title)))
    }

    // Solid graphite surfaces keep settings inexpensive to render.
    private var settingsPanel: some View {
        RoundedRectangle(cornerRadius: 18).fill(Color(hex: 0x1B1B1D))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(hex: 0x343437), lineWidth: 1))
    }

    private var settingsDivider: some View {
        UnyriSettingsDivider()
    }

    private var serverStatusRow: some View {
        NavigationLink { ConnectionSettingsView() } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Current server").font(.caption).foregroundColor(UnyriColors.secondaryText)
                    Text(AppConfig.apiBaseURL.host ?? AppConfig.apiBaseURL.absoluteString)
                        .font(.subheadline).foregroundColor(UnyriColors.primaryText).lineLimit(1)
                }
                Spacer(minLength: 8)
                Circle().fill(realtime.state == .connected ? UnyriColors.success : UnyriColors.warning).frame(width: 7, height: 7)
                Text(LocalizedStringKey(connectionLabel)).font(.caption).foregroundColor(UnyriColors.secondaryText)
                Image(systemName: "chevron.right").font(.caption).foregroundColor(UnyriColors.mutedText)
            }.padding(.horizontal, 16).padding(.vertical, 12).frame(minHeight: 48).contentShape(Rectangle())
        }.buttonStyle(UnyriQuietButtonStyle()).background(settingsPanel)
    }

    private var connectionLabel: String {
        switch realtime.state {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .disconnected: return "Disconnected"
        }
    }

    private var historyCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "iphone.and.arrow.forward").font(.title2).foregroundColor(Color(hex: 0xA092FF)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("History & transfer").font(.headline).foregroundColor(UnyriColors.primaryText)
                    Text("Move to another phone or restore a backup")
                        .font(.caption).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { historyTransferLink; historyBackupLink }
                VStack(spacing: 10) { historyTransferLink; historyBackupLink }
            }
        }.padding(16)
            .background(settingsPanel)
    }

    private var historyTransferLink: some View {
        NavigationLink { HistoryTransferView() } label: {
            historyActionLabel("Transfer", icon: "iphone.and.arrow.forward", tint: Color(hex: 0x80D7FA))
        }.buttonStyle(UnyriQuietButtonStyle())
    }

    private var historyBackupLink: some View {
        NavigationLink { HistoryKeySettingsView() } label: {
            historyActionLabel("Backup", icon: "lock.folder", tint: Color(hex: 0xEBC078))
        }.buttonStyle(UnyriQuietButtonStyle())
    }

    private func historyActionLabel(_ title: String, icon: String, tint: Color) -> some View {
        let tint = colorStyle.decorative(tint)
        return Label(LocalizedStringKey(title), systemImage: icon)
            .font(.subheadline.weight(.semibold)).foregroundColor(tint)
            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 12).background(RoundedRectangle(cornerRadius: 13).fill(tint.opacity(0.07)))
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(tint.opacity(0.3), lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 13))
    }

    // Tapping the compact identity card retains the existing edit route.
    private func accountCard(_ profile: UserProfile) -> some View {
        Button {
            isShowingEdit = true
        } label: {
            HStack(spacing: 14) {
                UserAvatar(url: avatarURL ?? users.cachedAvatarURL(id: profile.id, avatarUrl: profile.avatarUrl, avatarVersion: profile.avatarVersion), name: profile.displayName, size: 48)
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.displayName)
                        .font(UnyriTypography.sectionTitle)
                        .foregroundColor(UnyriColors.primaryText)
                        .lineLimit(1)
                    Text("@\(profile.username)")
                        .font(UnyriTypography.bodySecondary)
                        .foregroundColor(UnyriColors.secondaryText)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "pencil")
                    .font(.subheadline).unyriDecorativeForeground(Color(hex: 0xB6ADF1))
                    .frame(width: 32, height: 32).background(Circle().fill(Color(hex: 0x303034)))
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(settingsPanel)
        }
        .buttonStyle(UnyriQuietButtonStyle())
        .accessibilityLabel("Edit Profile")
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(LocalizedStringKey(title))
            .font(UnyriTypography.caption)
            .foregroundColor(UnyriColors.mutedText)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func settingsSummary(_ title: String) -> String {
        switch title {
        case "Account": return "Profile and account access"
        case "Appearance": return "Text, background and language"
        case "Chats": return "Quick reaction"
        case "Notifications": return "Messages, reminders and iPhone permission"
        case "Privacy & Security": return "Devices, activity and live draft"
        case "Connection & storage": return "Call quality, downloads and cache"
        case "Server administration": return "Server administration"
        default: return "Support, diagnostics and terms"
        }
    }

    private func settingsTint(_ title: String) -> Color {
        switch title {
        case "Account", "Server administration": return Color(hex: 0xA092FF)
        case "Appearance", "Connection & storage": return Color(hex: 0x80D7FA)
        case "Chats", "Notifications": return Color(hex: 0x7CE4BF)
        case "Privacy & Security": return Color(hex: 0xEBC078)
        default: return Color(hex: 0xF28BAB)
        }
    }

    private func settingsLabel(_ title: String, icon: String) -> some View {
        UnyriSettingsRow(title: title == "Privacy & Security" ? "Privacy" : title, icon: icon,
            tint: settingsTint(title), subtitle: settingsSummary(title))
    }

    /// Data & Storage row (Phase 9.1): a real destination, never a fake switch.
    private var storageRow: some View {
        Button {
            isShowingStorage = true
        } label: {
            HStack {
                Text("Storage Usage")
                    .font(UnyriTypography.body)
                    .foregroundColor(UnyriColors.primaryText)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(UnyriColors.mutedText)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: UnyriShapes.radiusCard).fill(UnyriColors.glassPrimary))
            .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusCard).stroke(UnyriColors.outlineSubtle, lineWidth: 1))
        }
        .buttonStyle(UnyriQuietButtonStyle())
        .accessibilityLabel("Storage Usage")
    }

    /// Navigation section (Phase 8 §34): a real persisted toggle — hiding the
    /// Calls tab never disables calling itself.
    private func sidebarVisibility(_ id: String) -> Binding<Bool> {
        Binding(get: {
            let legacyVisible = id == "calls" ? showCallsTab : showSavedMessages
            return legacyVisible && !sidebarStore.settings.hiddenItems.contains(id)
        }, set: { visible in
            let chosen = UnyriSidebarVisibility.setting(id, visible: visible, in: sidebarStore.settings)
            guard sidebarStore.apply(chosen) else { return }
            if id == "calls" { showCallsTab = visible }
            else { showSavedMessages = visible }
        })
    }

    private var navigationSection: some View {
        VStack(spacing: 0) {
            settingsToggle("Show Saved Messages in menu", icon: "bookmark", tint: Color(hex: 0xE6BD73), value: sidebarVisibility("saved"))
            UnyriSettingsDivider()
            settingsToggle("Show Calls in menu", icon: "phone", tint: Color(hex: 0x83D9B7), value: sidebarVisibility("calls"))
        }
    }

    /// About/version section (Phase 6 §20): actual installed bundle metadata.
    private var aboutSection: some View {
        HStack {
            Text("UNYRI")
                .font(UnyriTypography.body)
                .foregroundColor(UnyriColors.primaryText)
            Spacer()
            Text(AppVersion.display(
                shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            ))
            .font(UnyriTypography.caption)
            .foregroundColor(UnyriColors.mutedText)
        }
        .padding(16)
        .background(settingsPanel)
    }

    // Account actions must stay available even while /users/me is offline.
    private func consumeQuickAction() {
        guard let action = quickAction else { return }
        if action == "edit", profile == nil { return }
        quickAction = nil
        if action == "logout" {
            guard !isLoggingOut, let owner = auth.currentUser?.id else { return }
            pendingLogoutBackup = nil
            logoutPrompt = LogoutScope(owner: owner, server: AppConfig.apiBaseURL.absoluteString, generation: auth.sessionGeneration)
        } else if action == "edit" { isShowingEdit = true }
        else { quickDestination = action }
    }

    private var logoutButton: some View {
        Button {
            guard !isLoggingOut, let owner = auth.currentUser?.id else { return }
            pendingLogoutBackup = nil
            logoutPrompt = LogoutScope(owner: owner, server: AppConfig.apiBaseURL.absoluteString, generation: auth.sessionGeneration)
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "rectangle.portrait.and.arrow.right").font(.title3).frame(width: 38)
                Text("Sign out of account").font(.subheadline.weight(.semibold))
                Spacer()
                if isLoggingOut { ProgressView().tint(UnyriColors.danger) }
                else { Image(systemName: "chevron.right").font(.caption).foregroundColor(UnyriColors.mutedText) }
            }.foregroundColor(UnyriColors.danger).padding(16).frame(minHeight: 56).contentShape(Rectangle())
        }
        .buttonStyle(UnyriQuietButtonStyle()).accessibilityIdentifier("settings-logout")
        .background(settingsPanel)
        .disabled(isLoggingOut)
    }

    private func logoutScopeIsCurrent(_ scope: LogoutScope) -> Bool {
        scope.owner == auth.currentUser?.id && scope.server == AppConfig.apiBaseURL.absoluteString
            && scope.generation == auth.sessionGeneration
    }

    private func discardLogoutPrompt() {
        pendingLogoutBackup = nil
        logoutPrompt = nil
        logoutBackupScope = nil
    }

    private func openLogoutBackupIfRequested() {
        guard let scope = pendingLogoutBackup else { return }
        pendingLogoutBackup = nil
        guard logoutScopeIsCurrent(scope), !isLoggingOut else { return }
        logoutBackupScope = scope
    }

    private func confirmLogout(_ scope: LogoutScope) {
        guard !isLoggingOut, logoutScopeIsCurrent(scope) else { discardLogoutPrompt(); return }
        pendingLogoutBackup = nil
        logoutPrompt = nil
        isLoggingOut = true
        Task { @MainActor in
            guard logoutScopeIsCurrent(scope) else { isLoggingOut = false; return }
            await auth.logout()
            isLoggingOut = false
        }
    }

    private func logoutConfirmation(_ scope: LogoutScope) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 18) {
                    Image(systemName: "rectangle.portrait.and.arrow.right").font(.title).foregroundColor(UnyriColors.danger).accessibilityHidden(true)
                    Text("Sign out of account").font(.title2.bold()).foregroundColor(UnyriColors.primaryText)
                    Text("Signing out removes this account’s messages, calls, files, drafts and saved recovery key from this phone. Your account and encrypted backup stay on the server. Signing in again starts with an empty history until you choose Restore history.")
                    Text("Before signing out, finish an encrypted backup and save its history recovery key outside UNYRI. You will need both to restore your history. Or transfer it from another phone where you are still signed in and your history is available.")
                    Text("The account recovery code is separate from the history recovery key. A fresh sign-in may also create a new security identity; contacts may need to confirm a new security key before new messages can be decrypted.")
                        .font(.footnote).foregroundColor(UnyriColors.secondaryText)
                }.font(.body).foregroundColor(UnyriColors.primaryText)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(24)
            VStack(spacing: 10) {
                Button { pendingLogoutBackup = nil; logoutPrompt = nil } label: {
                    Text("Cancel").foregroundColor(UnyriColors.primaryText)
                        .modifier(LogoutActionSurface(tint: Color(hex: 0xA092FF), filled: true))
                }
                Button {
                    guard logoutScopeIsCurrent(scope), !isLoggingOut else { discardLogoutPrompt(); return }
                    pendingLogoutBackup = scope
                    logoutPrompt = nil
                } label: {
                    Text("History and backup").foregroundColor(Color(hex: 0x80D7FA))
                        .modifier(LogoutActionSurface(tint: Color(hex: 0x80D7FA)))
                }
                Button(role: .destructive) { confirmLogout(scope) } label: {
                    Text("Sign out of account").foregroundColor(UnyriColors.danger)
                        .modifier(LogoutActionSurface(tint: UnyriColors.danger))
                }
            }.buttonStyle(UnyriQuietButtonStyle()).padding(.horizontal, 24).padding(.bottom, 24)
            }
        }.background(Color(hex: 0x171F2D).ignoresSafeArea())
            .presentationDetents([.large]).presentationDragIndicator(.visible)
    }

    private struct LogoutActionSurface: ViewModifier {
        let tint: Color
        var filled = false
        func body(content: Content) -> some View {
            content.font(.body.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 48)
                .padding(.vertical, 4).padding(.horizontal, 12)
                .background(RoundedRectangle(cornerRadius: 14).fill(tint.opacity(filled ? 0.2 : 0.06)))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(tint.opacity(0.3), lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text(message)
                .font(.footnote)
                .foregroundColor(.red)
                .multilineTextAlignment(.center)
            Button("Retry") {
                Task { await load() }
            }
        }
        .padding(.top, 48)
    }

    private func profileSaved(_ updated: UserProfile) {
        guard !Task.isCancelled, updated.id.lowercased() == auth.currentUser?.id.lowercased() else { return }
        // The edit API returns its actual acknowledgement. A live update from
        // another device may already be newer; only the displayed metadata is
        // merged here, never the version attached to uploaded avatar bytes.
        let canonical = users.ownProfile.flatMap { current in
            current.id == updated.id && current.profileVersion >= updated.profileVersion &&
                current.avatarVersion >= updated.avatarVersion ? current : nil
        } ?? updated
        guard profile.map({ $0.id != canonical.id ||
            ($0.profileVersion <= canonical.profileVersion && $0.avatarVersion <= canonical.avatarVersion) }) ?? true else { return }
        profileLoadId = UUID()
        profile = canonical
        showingCachedProfile = false
        errorMessage = nil
        Task { await resolveAvatar(for: canonical) }
    }

    private func load() async {
        let ticket = UUID()
        profileLoadId = ticket
        let generation = auth.sessionGeneration
        errorMessage = nil
        if let cached = users.cachedMe() {
            profile = cached
            showingCachedProfile = true
            await resolveAvatar(for: cached)
        }
        guard ticket == profileLoadId, generation == auth.sessionGeneration, !Task.isCancelled else { return }
        do {
            let fetched = try await users.getMe()
            guard ticket == profileLoadId, generation == auth.sessionGeneration, !Task.isCancelled else { return }
            profile = fetched
            showingCachedProfile = false
            await resolveAvatar(for: fetched)
        } catch {
            guard ticket == profileLoadId, generation == auth.sessionGeneration, !Task.isCancelled else { return }
            errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func resolveAvatar(for profile: UserProfile) async {
        let ticket = profileLoadId
        let generation = auth.sessionGeneration
        let resolved = await users.avatarURL(
            id: profile.id,
            avatarUrl: profile.avatarUrl,
            avatarVersion: profile.avatarVersion
        )
        guard ticket == profileLoadId, generation == auth.sessionGeneration, !Task.isCancelled,
              self.profile?.id == profile.id, self.profile?.avatarVersion == profile.avatarVersion,
              self.profile?.avatarUrl == profile.avatarUrl else { return }
        avatarURL = resolved
    }
}

private struct DeleteAccountView: View {
    @EnvironmentObject private var auth: AuthRepository
    @State private var password = ""
    @State private var confirming = false
    @State private var deleting = false
    @State private var error: String?
    private let api = UsersAPI(client: APIClient(baseURL: AppConfig.apiBaseURL))
    var body: some View {
        UnyriSettingsPage("Delete account") {
            UnyriSettingsGroup {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Delete account", systemImage: "person.crop.circle.badge.minus")
                        .font(.headline).foregroundColor(UnyriColors.danger)
                    if let user = auth.currentUser { Text("@" + user.username).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText) }
                    Text("Deleting your account is permanent. Your profile, messages, files and direct chats will be removed from the server. Direct chats will also disappear from your contacts' UNYRI apps when they synchronize. Copies already saved outside UNYRI cannot be removed.")
                        .font(.subheadline).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
            UnyriSettingsGroup("Account password") {
                SecureField("Account password", text: $password)
                    .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(.horizontal, 14).frame(minHeight: 52)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(UnyriColors.danger).accessibilityAddTraits(.isStaticText) }
            if deleting { ProgressView("Deleting account…").frame(maxWidth: .infinity) }
        }
        .safeAreaInset(edge: .bottom) {
            Button("Delete account permanently", role: .destructive) { confirming = true }
                .font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.danger)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 14).fill(UnyriColors.danger.opacity(0.12)))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(UnyriColors.danger.opacity(0.3), lineWidth: 1))
                .buttonStyle(UnyriQuietButtonStyle())
                .disabled(deleting || password.isEmpty || password.utf8.count > 1024)
                .padding(.horizontal, 16).padding(.vertical, 12).background(Color(hex: 0x1B1B1D))
        }
        .disabled(deleting)
        .navigationBarBackButtonHidden(deleting).interactiveDismissDisabled(deleting)
        .alert("Delete account permanently?", isPresented: $confirming) {
            Button("Cancel", role: .cancel) {}
            Button("Delete account permanently", role: .destructive) { Task { await delete() } }
        } message: { Text("This action cannot be undone. Entering your password and confirming deletes this account and its direct chats for both participants.") }
        .onDisappear { password = "" }
    }
    private func delete() async {
        guard !deleting else { return }
        deleting = true; error = nil
        defer { deleting = false; password = "" }
        do { try await auth.deleteAccount(password: password) { token, password in try await api.deleteAccount(accessToken: token, password: password) } }
        catch is CancellationError {}
        catch { self.error = UnyriL10n.string("Could not confirm account deletion. Check your password and connection, then try again.") }
    }
}

/// Data & Storage screen (Phase 9.1): truthful LOCAL cache accounting + a real
/// "Clear Media Cache" action. The message/chat databases are shown only
/// informationally and can never be cleared from here.
private struct StorageView: View {
    @EnvironmentObject private var storage: StorageService
    @EnvironmentObject private var users: UsersRepository
    @State private var avatarBytes: Int64 = 0

    @State private var usage: StorageUsage?
    @State private var isClearing = false
    @State private var clearFailed = false
    @State private var showConfirm = false

    var body: some View {
        UnyriSettingsPage("Storage") {
            if let usage {
                card(title: "Media cache") {
                    row("Photos", usage.photosBytes)
                    row("Voice messages", usage.voiceBytes)
                    row("Files", usage.filesBytes)
                    row("Avatars", avatarBytes)
                    subTotal("Subtotal", usage.mediaBytes + avatarBytes)
                }
                card(title: "Temporary files") {
                    row("Temporary files", usage.tempBytes)
                    row("Media draft", usage.draftBytes)
                }
                Text(String(format: UnyriL10n.string("Total cache: %@", comment: ""), FileSizeFormatter.format(usage.totalCacheBytes + avatarBytes)))
                    .font(.headline).foregroundColor(UnyriColors.primaryText).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(String(format: UnyriL10n.string("Database: %@ (informational)", comment: ""), FileSizeFormatter.format(usage.databaseBytes)))
                    .font(.caption).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if clearFailed { Text("Some cached files could not be removed.").font(.footnote).foregroundColor(UnyriColors.danger) }
                UnyriSettingsNote("Messages and conversations will not be deleted. Media can be downloaded again when needed.")
                UnyriSettingsGroup {
                    Button { showConfirm = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "trash").font(.system(size: 17)).frame(width: 32).accessibilityHidden(true)
                            Text(LocalizedStringKey(isClearing ? "Clearing…" : "Clear Media Cache")).font(.subheadline.weight(.semibold))
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 4)
                        }.foregroundColor(UnyriColors.danger).padding(14).frame(minHeight: 56).contentShape(Rectangle())
                    }.disabled(isClearing)
                }
            } else {
                ProgressView("Calculating…").font(.footnote).frame(maxWidth: .infinity, minHeight: 80)
            }
        }
        .task {
            usage = await storage.usage()
            avatarBytes = await users.avatarCacheBytes()
        }
        .refreshable {
            usage = await storage.usage()
            avatarBytes = await users.avatarCacheBytes()
        }
        .alert("Clear cached media?", isPresented: $showConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                isClearing = true
                Task {
                    let result = await storage.clear()
                    let avatarsCleared = await users.clearAvatarCache()
                    UserAvatar.clearDecodedCache()
                    clearFailed = result.failedFiles > 0 || !avatarsCleared
                    usage = await storage.usage()
                    avatarBytes = await users.avatarCacheBytes()
                    isClearing = false
                }
            }
        } message: {
            Text("Messages and conversations will not be deleted. Media can be downloaded again when needed.")
        }
    }

    private func card(title: String, @ViewBuilder content: () -> some View) -> some View {
        UnyriSettingsGroup(title) { VStack(spacing: 10) { content() }.padding(14) }
    }

    private func row(_ label: String, _ bytes: Int64) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(LocalizedStringKey(label)).font(.subheadline).foregroundColor(UnyriColors.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Text(FileSizeFormatter.format(bytes)).font(.subheadline.weight(.medium)).foregroundColor(UnyriColors.primaryText)
                .fixedSize(horizontal: true, vertical: true)
        }.frame(minHeight: 28)
    }

    private func subTotal(_ label: String, _ bytes: Int64) -> some View {
        VStack(spacing: 10) {
            Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5)
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(LocalizedStringKey(label)).font(.caption.weight(.semibold)).foregroundColor(UnyriColors.secondaryText)
                Spacer(minLength: 4)
                Text(FileSizeFormatter.format(bytes)).font(.caption.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                    .fixedSize(horizontal: true, vertical: true)
            }
        }
    }

}
import Foundation

private final class AdminRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

private struct AdminReply: Decodable {
    var keyProtocol: Int?
    var authProtocol: Int?
    var authPolicy: String?
    var totpConfigured: Bool?
    var paperKeyConfigured: Bool?
    var serverId: String?
    var signingPublicKey: String?
    var challengeId: String?
    var userId: String?
    var authSession: String?
    var purpose: String?
    var publicKey: String?
    var nonce: String?
    var configured: Bool?
    var secret: String?
    var qrPng: String?
    var token: String?
    // ADMIN CONSOLE PACK 3 - one-time handoff for the server-hosted console.
    var handoff: String?
    var expiresIn: Int?
    var consolePath: String?
    var recoveryCodes: [String]?
    var items: [AdminReport]?
    var screenshots: [AdminScreenshot]?
    var name: String?
    var `operator`: String?
    var contact: String?
    var rules: String?
    var retention: String?
    var maxFileBytes: Int64?
    var userQuotaBytes: Int64?
    var storageBudgetBytes: Int64?
    var usedBytes: Int64?
    var reservedBytes: Int64?
    var warning: Bool?
    var planId: String?
    var bytes: Int64?
    var deleted: Int?
    var skipped: Int?
    var failed: Int?
}
private struct AdminReport: Decodable, Identifiable {
    var id: String
    var reason: String?
    var description: String?
    var status: String?
    var userId: String?
    var action: String?
    var installationBlocks: Int?
    var reviewNote: String?
    var decisionReason: String?
    var bytes: Int64?
    var createdAt: String?
    var platform: String?
}

import ImageIO
private struct AdminScreenshot: Decodable {
    var dataBase64: String
    func thumbnail() -> UIImage? {
        guard let data = Data(base64Encoded:dataBase64), let source = CGImageSourceCreateWithData(data as CFData,nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:2048,kCGImageSourceCreateThumbnailWithTransform:true] as CFDictionary) else { return nil }
        return UIImage(cgImage:image)
    }
}

private struct AdminPanelView: View {
    let role: String
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var scenePhase
    @State private var paperKey = ""
    @State private var paperSaved = false
    @State private var showPaper = false
    @State private var keyMismatch = false
    @State private var keyConfigured = false
    @State private var authProtocol = 1
    @State private var authPolicy = AdminFactorPolicy.both
    @State private var totpConfigured = false
    @State private var setupReady = false
    @State private var unsupportedPolicy = false
    @State private var serverId = ""
    @State private var rotating = false
    @State private var replacementKey = ""
    @State private var replacementSaved = false
    private var pinName: String { "admin-public-pin:\(server.absoluteString)|\(auth.currentUser?.id ?? "")" }
    @State private var password = ""
    @State private var code = ""
    @State private var recoveryMode = false
    @State private var token: String?
    @State private var secret = ""
    @State private var qr: UIImage?
    @State private var recovery: [String] = []
    @State private var reports: [AdminReport] = []
    @State private var section = "reports"
    @State private var search = ""
    @State private var screenshots: [UIImage] = []
    @State private var screenshotIndex: Int?
    @State private var reportFilter = ""
    @State private var noScreenshots = false
    @State private var target = ""
    @State private var targetLabel = ""
    @State private var showRestriction = false
    @State private var restrictionReason = ""
    @State private var includeInstallations = false
    @State private var temporary = true
    @State private var actionSaved = false
    @State private var configured: Bool?
    @State private var busy = false
    @State private var failed = false
    @State private var adminFailureMessage: String?
    @State private var adminDiagnostic: String?
    @State private var adminRequestStage = "LOCAL"
    @State private var panelGeneration = 0
    @State private var lastAction = Date()
    private let server = AppConfig.apiBaseURL
    @State private var backgroundAt: TimeInterval?
    @State private var backgroundExpiry: Task<Void, Never>?
    @State private var operationTask: Task<Void, Never>?
    @State private var formExpired = false
    @State private var regenerate = false
    @State private var copiedSecret = false
    @State private var manualPaper = false
    @State private var setupExpiresAt: TimeInterval?
    @State private var copiedChangeCount: Int?
    /// ADMIN CONSOLE PACK 3: the console URL, held only while the browser sheet is up.
    @State private var consoleURL: URL?

    @MainActor private func clearOwnClipboard() {
        if let copiedChangeCount, UIPasteboard.general.changeCount == copiedChangeCount {
            UIPasteboard.general.items = []
        }
        copiedChangeCount = nil
    }
    @MainActor private func copySetupSecret() {
        guard !secret.isEmpty else { return }
        UIPasteboard.general.setItems([["public.utf8-plain-text": secret]], options: [
            .localOnly: true, .expirationDate: Date().addingTimeInterval(60)
        ])
        copiedChangeCount = UIPasteboard.general.changeCount
        copiedSecret = true
    }
    @MainActor private func suspendPanel() {
        operationTask?.cancel(); operationTask = nil
        if token != nil { clear(); return }
        guard backgroundAt == nil else { return }
        panelGeneration += 1; busy = false; failed = false
        code = ""; showPaper = false
        backgroundAt = ProcessInfo.processInfo.systemUptime
        backgroundExpiry?.cancel()
        backgroundExpiry = Task { @MainActor in
            do { try await Task.sleep(for: .seconds(AdminSetupPolicy.resumeWindow)) }
            catch { return }
            clear(); formExpired = true
        }
    }
    @MainActor private func resumePanel() {
        if AdminSetupPolicy.expired(backgroundAt: backgroundAt, now: ProcessInfo.processInfo.systemUptime) {
            clear(); formExpired = true
        }
        backgroundExpiry?.cancel(); backgroundExpiry = nil; backgroundAt = nil
        if let deadline = setupExpiresAt, ProcessInfo.processInfo.systemUptime >= deadline {
            secret = ""; qr = nil; setupExpiresAt = nil; copiedSecret = false; clearOwnClipboard()
        }
        if configured == nil && !busy { perform { try await loadStatus() } }
    }

    @MainActor private func clear() {
        backgroundExpiry?.cancel(); backgroundExpiry = nil; backgroundAt = nil
        regenerate = false; manualPaper = false; copiedSecret = false; setupExpiresAt = nil
        clearOwnClipboard(); busy = false
        panelGeneration += 1
        paperKey = ""; paperSaved = false; showPaper = false; rotating = false; replacementKey = ""; replacementSaved = false; setupReady = false
        password = ""; code = ""; recoveryMode = false; token = nil; secret = ""; qr = nil; recovery = []; reports = []; screenshots = []; screenshotIndex = nil; showRestriction = false; target = ""; restrictionReason = ""
    }
    @MainActor private func request(_ path: String, body: [String:Any]? = nil) async throws -> AdminReply {
        switch path {
        case "status": adminRequestStage = "STATUS"
        case "setup": adminRequestStage = "SETUP"
        case "key-challenge": adminRequestStage = "CHALLENGE"
        case "unlock": adminRequestStage = "UNLOCK"
        default: adminRequestStage = "ACTION"
        }
        guard server.scheme == "https" else { throw APIError.invalidURL }
        let generation = auth.sessionGeneration
        let operation = panelGeneration
        let access = try await auth.currentAccessToken()
        guard generation == auth.sessionGeneration, operation == panelGeneration else { throw CancellationError() }
        let route = path.split(separator: "?", maxSplits: 1)
        var components = URLComponents(url: server.appendingPathComponent("admin").appendingPathComponent(String(route[0])), resolvingAgainstBaseURL: false)
        if route.count > 1 { components?.percentEncodedQuery = String(route[1]) }
        guard let url = components?.url else { throw APIError.invalidURL }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization")
        if let token {
            request.setValue(token, forHTTPHeaderField: "X-UNYRI-Admin")
            request.setValue(token, forHTTPHeaderField: "X-COCO-Admin")
        }
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject:body)
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25
        let session = URLSession(configuration: configuration, delegate: AdminRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard generation == auth.sessionGeneration, operation == panelGeneration, !Task.isCancelled else { throw CancellationError() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.http((response as? HTTPURLResponse)?.statusCode ?? 500) }
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let reply = try decoder.decode(AdminReply.self, from: data)
        lastAction = Date()
        return reply
    }
    @MainActor private func loadStatus() async throws {
        let data = try await request("status")
        guard data.keyProtocol == 1, let id = data.serverId else { throw APIError.invalidURL }
        unsupportedPolicy = (data.authProtocol ?? 1) >= 2 && AdminFactorPolicy(rawValue: data.authPolicy ?? "") == nil
        let policy = try AdminFactorPolicy.parse(protocolVersion: data.authProtocol, policy: data.authPolicy)
        let previous = try KeychainStore.checkedValue(forKey: pinName)
        let pending = try KeychainStore.checkedValue(forKey: pinName + "|pending")
        let observed = id + "|" + (data.signingPublicKey ?? "null")
        keyMismatch = !(policy.requiresPaperKey
            ? AdminSigningKey.acceptsPin(previous: previous,pending: pending,server: id,publicKey: data.signingPublicKey)
            : AdminFactorPolicy.acceptsServerPin(previous: previous, pending: pending, server: id))
        guard !keyMismatch else { throw APIError.invalidURL }
        if pending == observed {
            guard KeychainStore.set(observed,forKey: pinName) else { throw APIError.invalidURL }
            KeychainStore.remove(pinName + "|pending")
        }
        serverId = id; keyConfigured = data.signingPublicKey != nil; configured = data.configured
        authProtocol = data.authProtocol ?? 1
        totpConfigured = authProtocol >= 2 ? (data.totpConfigured ?? false) : (data.configured ?? false)
        authPolicy = data.configured == false && !keyConfigured && !totpConfigured && authProtocol >= 2 ? .authenticator : policy
    }
    @MainActor private func proof(_ purpose: String, proposed: String, old: String) async throws -> [String:Any] {
        let publicKey = try AdminSigningKey.publicKey(proposed)
        let data = try await request("key-challenge", body: ["purpose":purpose,"public_key":publicKey])
        guard data.serverId == serverId, data.userId == auth.currentUser?.id,
              data.purpose == purpose, data.publicKey == publicKey,
              let user = data.userId, let session = data.authSession, let id = data.challengeId,
              let nonce = data.nonce else { throw APIError.invalidURL }
        let payload = try AdminSigningKey.payload(server: serverId,user: user,session: session,id: id,
            purpose: purpose,publicKey: publicKey,nonce: nonce)
        var result: [String:Any] = ["challenge_id":id,"signature":try AdminSigningKey.sign(old,payload: payload)]
        if purpose == "rotate" { result["new_signature"] = try AdminSigningKey.sign(proposed,payload: payload) }
        return result
    }
    @MainActor private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy, scenePhase == .active else { return }
        busy = true; failed = false; adminFailureMessage = nil; adminDiagnostic = nil
        let operation = panelGeneration
        operationTask = Task { @MainActor in
            defer { if operation == panelGeneration { busy = false; operationTask = nil } }
            do { try await action() }
            catch is CancellationError { }
            catch { if operation == panelGeneration {
                failed = true; token = nil; reports = []
                adminDiagnostic = "ADMIN-\(adminRequestStage)-LOCAL"
                if case APIError.http(let status) = error {
                    adminDiagnostic = "ADMIN-\(adminRequestStage)-\(status)"
                    switch status {
                    case 401: adminFailureMessage = UnyriL10n.string("The server rejected sign-in. Check your account password and the current authenticator code. This does not mean the code alone is wrong.")
                    case 429: adminFailureMessage = UnyriL10n.string("Too many attempts. Wait 5 minutes before trying again.")
                    case 500...599: adminFailureMessage = UnyriL10n.string("The server could not complete the request. Contact the server owner.")
                    default: adminFailureMessage = nil
                    }
                } else if error is URLError {
                    adminDiagnostic = "ADMIN-\(adminRequestStage)-NETWORK"
                    adminFailureMessage = UnyriL10n.string("Could not connect securely to the server. Check the network and server certificate.")
                }
            } }
        }
    }
    @ViewBuilder private var signInForm: some View {
        Section {
            Text("Your account password is required with every sign-in method.")
            switch authPolicy {
            case .authenticator: Text("Authenticator")
            case .paperKey: Text("Paper key")
            case .both: Text("Authenticator + paper key")
            }
            if configured == false && authProtocol >= 2 && !keyConfigured && !totpConfigured && !setupReady {
                Picker("Sign-in protection", selection: $authPolicy) {
                    Text("Authenticator").tag(AdminFactorPolicy.authenticator)
                    Text("Paper key").tag(AdminFactorPolicy.paperKey)
                    Text("Authenticator + paper key").tag(AdminFactorPolicy.both)
                }.disabled(busy)
            }
            Text("To change an existing sign-in method, use Sign-in protection in the Windows Admin Console.").font(.footnote).foregroundStyle(.secondary)
            if formExpired {
                Text("The form was cleared after a long absence. Enter your saved paper key again. If authenticator setup was not finished, repeat step 1.").font(.footnote).foregroundStyle(.secondary)
            }
            SecureField("Account password", text: $password).textContentType(.password).frame(minHeight: 44).disabled(busy)
        }
        if configured == false { authenticatorSetup }
        if authPolicy.requiresPaperKey { paperKeySetup }
        Section {
            if authPolicy.requiresAuthenticator {
            Text("Open your authenticator and enter the current 6-digit UNYRI code below. This is not the long setup secret or your paper key. A saved recovery code is also accepted.")
            Text("If you already used this authenticator code to sign in, wait for the next code or use an unused recovery code.").font(.footnote).foregroundStyle(.secondary)
            if configured == true {
                Button(recoveryMode ? "Use authenticator code" : "Use a recovery code") { recoveryMode.toggle(); code = "" }.disabled(busy)
            }
            SecureField(recoveryMode ? "Saved recovery code" : "6-digit authenticator code", text: $code)
                .textContentType(.oneTimeCode).keyboardType(recoveryMode ? .asciiCapable : .numberPad)
                .onChange(of: code) { value in
                    let normalized = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(128))
                    if code != normalized { code = normalized }
                }
                .textInputAutocapitalization(.never).autocorrectionDisabled().disabled(busy)
            if !code.isEmpty && !AdminSetupPolicy.validCode(code, recovery: recoveryMode) {
                Text(recoveryMode ? "Enter a saved 43-character recovery code." : "Enter 6 digits from your authenticator. Add the long setup secret to the authenticator first; it is not a sign-in code.").font(.footnote)
            }
            }
                Button("Unlock administration") { perform {
                    var body: [String: Any] = ["password": password]
                    if authProtocol >= 2 { body["auth_policy"] = authPolicy.rawValue }
                    if authPolicy.requiresAuthenticator { body["code"] = code }
                    var publicKey: String?
                    if authPolicy.requiresPaperKey {
                        publicKey = try AdminSigningKey.publicKey(paperKey)
                        body["key_proof"] = try await proof("unlock", proposed: paperKey, old: paperKey)
                        guard KeychainStore.set(serverId + "|" + (publicKey ?? "null"),forKey: pinName + "|pending") else { throw APIError.invalidURL }
                    }
                    let reply = try await request("unlock", body: body)
                    if authPolicy.requiresPaperKey {
                        guard KeychainStore.set(serverId + "|" + (publicKey ?? "null"), forKey: pinName) else { throw APIError.invalidURL }
                        KeychainStore.remove(pinName + "|pending")
                    } else if try KeychainStore.checkedValue(forKey: pinName) == nil {
                        guard KeychainStore.set(serverId + "|null", forKey: pinName) else { throw APIError.invalidURL }
                    }
                    paperKey = ""; paperSaved = false; showPaper = false; keyConfigured = authPolicy.requiresPaperKey
                    token = reply.token; recovery = reply.recoveryCodes ?? []
                    password = ""; code = ""; secret = ""; qr = nil; configured = true; setupExpiresAt = nil; clearOwnClipboard()
                    if recovery.isEmpty { reports = try await request(section + (section == "users" ? "?query=" + (search.addingPercentEncoding(withAllowedCharacters:.urlQueryAllowed.subtracting(CharacterSet(charactersIn:"&=+#?"))) ?? "") : "")).items ?? [] }
                } }.unyriProminentAction()
                .disabled(busy || keyMismatch || unsupportedPolicy || password.isEmpty ||
                    (authPolicy.requiresAuthenticator && !AdminSetupPolicy.validCode(code, recovery: recoveryMode)) ||
                    (authPolicy.requiresPaperKey && (!AdminSigningKey.valid(paperKey) || (!keyConfigured && !paperSaved))) ||
                    (configured == false && !setupReady))
        } header: {
            if !keyConfigured || configured == false { Text("3 · Enter the code and sign in") }
            else { Text("Unlock administration") }
        } footer: {
            Text("You can switch to your authenticator and return within 3 minutes. The unfinished form stays only in memory. Closing this screen clears it.")
        }
    }
    @ViewBuilder private var authenticatorSetup: some View {
        Section("Prepare sign-in protection") {
            if authPolicy.requiresAuthenticator { Text("On this phone: copy the setup secret, open your authenticator and add an account using a setup key (time-based). On another device: scan the QR code. These are two ways to add the same account.") }
            if !setupReady {
                Button("Continue setup") { perform {
                    var body: [String: Any] = ["password": password]
                    if authProtocol >= 2 { body["auth_policy"] = authPolicy.rawValue }
                    let reply = try await request("setup", body: body)
                    secret = reply.secret ?? ""
                    qr = reply.qrPng.flatMap { Data(base64Encoded: $0) }.flatMap { UIImage(data: $0) }
                    setupExpiresAt = ProcessInfo.processInfo.systemUptime + 600
                    copiedSecret = false
                    setupReady = true
                } }.disabled(busy || password.isEmpty || keyMismatch)
            } else if authPolicy.requiresAuthenticator && !secret.isEmpty {
                Text("Authenticator setup secret — not the sign-in code").font(.caption).foregroundStyle(.secondary)
                Text(secret).font(.system(.callout, design: .monospaced)).textSelection(.enabled).privacySensitive()
                Button { copySetupSecret() } label: { Label("Copy setup secret", systemImage: "doc.on.doc") }
                    .unyriProminentAction().disabled(busy)
                if copiedSecret { Text("Copied. Paste it into your authenticator.").font(.footnote).foregroundStyle(.secondary) }
                if let qr {
                    HStack { Spacer(); Image(uiImage: qr).resizable().interpolation(.none).scaledToFit().frame(width: 180, height: 180).accessibilityLabel("Set up authenticator"); Spacer() }
                }
                Text("Authenticator setup lasts 10 minutes. If it expires, reconnect the authenticator; your written paper key can be reused.").font(.footnote).foregroundStyle(.secondary)
            }
            if setupReady && authPolicy.requiresAuthenticator && secret.isEmpty { Text("Use your existing authenticator. Its secret is not shown again.").font(.footnote) }
        }
    }
    @ViewBuilder private var paperKeySetup: some View {
        Section {
            if keyConfigured {
                Text("Keep this key privately. It is never sent to the server. Losing every copy prevents signing in.").font(.footnote)
            } else {
                Text("Generate this key once and write down all 4 groups of 8 characters. It becomes active after your first successful sign-in. Keep using that same key afterward.")
            }
            if configured == false && !setupReady {
                Text("Finish preparing sign-in protection first.").foregroundStyle(.secondary)
            } else if !keyConfigured && paperKey.isEmpty && !manualPaper {
                Button("Generate paper key") { perform {
                    paperKey = try AdminSigningKey.generate(); paperSaved = false; showPaper = true
                } }.disabled(busy || keyMismatch)
                Button("I already wrote down a key") { manualPaper = true }.disabled(busy)
            } else {
                AdminPaperKeyFields(value: $paperKey, visible: showPaper).disabled(busy).privacySensitive()
                Toggle("Show key", isOn: $showPaper).disabled(busy)
                if !keyConfigured {
                    Toggle("I wrote down the key and stored it safely", isOn: $paperSaved).disabled(busy || !AdminSigningKey.valid(paperKey))
                    Button("Generate a different key") { regenerate = true }.disabled(busy)
                }
                if !paperKey.isEmpty && !AdminSigningKey.valid(paperKey) {
                    Text("Check the key: a character is missing or incorrect.").font(.footnote)
                }
            }
        } header: {
            if keyConfigured { Text("Administrator paper key") }
            else { Text("2 · Save your paper key") }
        }
    }
    var body: some View {
        Form {
            if server.scheme != "https" { Label("Administration requires HTTPS. Configure TLS on this server first.", systemImage: "lock.shield").font(.footnote).foregroundColor(UnyriColors.danger) }
            if busy { ProgressView() }
            if unsupportedPolicy { Text("Unsupported administrator sign-in policy. Update UNYRI or use the Windows Admin Console.").foregroundStyle(UnyriColors.danger) }
            if keyMismatch { Label("The server identity or administrator key changed. Sign-in was stopped. Verify the server with its owner.", systemImage: "exclamationmark.shield").font(.footnote).foregroundColor(UnyriColors.danger) }
            else if failed { Text(adminFailureMessage ?? UnyriL10n.string("Administrative request failed. Check the connection, credentials and server configuration.")).font(.footnote).foregroundColor(UnyriColors.warning); if let adminDiagnostic { Text(adminDiagnostic).font(.caption.monospaced()).foregroundColor(UnyriColors.secondaryText) } }
            if token == nil { Button("Refresh") { perform { configured = nil; try await loadStatus() } }.disabled(busy) }
            if token == nil, configured != nil { signInForm }
            if token != nil {
                Text("To change an existing sign-in method, use Sign-in protection in the Windows Admin Console.").font(.footnote).foregroundStyle(.secondary)
                if authPolicy.requiresPaperKey { Button("Replace paper key") { rotating.toggle(); paperKey = ""; replacementKey = ""; replacementSaved = false }.disabled(busy) }
                if rotating && authPolicy.requiresPaperKey {
                    Text("Current paper key")
                    AdminPaperKeyFields(value: $paperKey, visible: false).disabled(busy)
                    Button("Generate paper key") { perform { replacementKey = try AdminSigningKey.generate(); replacementSaved = false } }.disabled(busy)
                    AdminPaperKeyFields(value: $replacementKey, visible: true).disabled(busy)
                    Toggle("I wrote down the key and stored it safely", isOn: $replacementSaved)
                    Button("Replace paper key") { perform {
                        let publicKey = try AdminSigningKey.publicKey(replacementKey)
                        let signed = try await proof("rotate", proposed: replacementKey, old: paperKey)
                        guard KeychainStore.set(serverId + "|" + publicKey,forKey: pinName + "|pending") else { throw APIError.invalidURL }
                        _ = try await request("key-rotate", body: signed)
                        guard KeychainStore.set(serverId + "|" + publicKey, forKey: pinName) else { throw APIError.invalidURL }
                    KeychainStore.remove(pinName + "|pending")
                        clear(); try await loadStatus()
                    } }.disabled(busy || !replacementSaved || !AdminSigningKey.valid(paperKey) || !AdminSigningKey.valid(replacementKey))
                }

                if !recovery.isEmpty {
                    Section("Save these recovery codes privately. Each code can be used once.") {
                        Text(recovery.joined(separator:"\n")).textSelection(.enabled)
                        Button("I saved the codes") { recovery = []; perform { reports = try await request(section + (section == "users" ? "?query=" + (search.addingPercentEncoding(withAllowedCharacters:.urlQueryAllowed.subtracting(CharacterSet(charactersIn:"&=+#?"))) ?? "") : "")).items ?? [] } }
                    }
                }
                if role == "owner" { NavigationLink("Server storage · Server card") { ServerManagementView(request: { path, body in try await request(path, body: body) }) } }
                // ADMIN CONSOLE PACK 3 - the administration product is served by
                // the UNYRI server. This screen keeps the secure unlock and hands
                // the browser a single-use URL; it never sees the console itself.
                Button("Open administration console") { perform {
                    let reply = try await request("console-handoff", body: [:])
                    guard let handoff = reply.handoff,
                          let url = AdminConsolePolicy.consoleUrl(base: server, handoff: handoff) else { throw APIError.invalidURL }
                    consoleURL = url
                } }.unyriProminentAction().disabled(busy)
                Button("Lock administration") { perform { _ = try await request("lock",body:[:]); clear() } }.disabled(busy)
                Button("Refresh") { perform { reports = try await request(section + (section == "users" ? "?query=" + (search.addingPercentEncoding(withAllowedCharacters:.urlQueryAllowed.subtracting(CharacterSet(charactersIn:"&=+#?"))) ?? "") : "")).items ?? [] } }.disabled(busy)
                Picker("Server administration", selection:$section) {
                    Text("User reports").tag("reports")
                    Text("Bug reports").tag("feedback")
                    Text("Server users").tag("users")
                    if role == "owner" { Text("Action history").tag("audit") }
                }.pickerStyle(.menu).frame(minHeight: 44).onChange(of:section) { _ in screenshots = []; screenshotIndex = nil; noScreenshots = false; perform { reports = try await request(section).items ?? [] } }.disabled(busy)
                if section == "users" { TextField("Search username", text:$search).textInputAutocapitalization(.never).autocorrectionDisabled() }
                if actionSaved { Text("Action saved") }
                if section == "feedback" || section == "reports" {
                    TextField("Search loaded reports", text: $reportFilter)
                }
                if noScreenshots { Text("No screenshots attached") }
                Section("Latest 100 entries") {
                    if reports.isEmpty { Text("No reports") }
                    ForEach(reports.filter { reportFilter.isEmpty || !["feedback","reports"].contains(section) || [$0.description ?? "",$0.reason ?? "",$0.id].contains(where: { $0.localizedCaseInsensitiveContains(reportFilter) }) }) { report in
                        VStack(alignment:.leading) {
                            Text(report.reason ?? report.action ?? "").font(.headline)
                            if section == "feedback" { Text([report.platform,report.createdAt].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                            if let description = report.description { Text(description) }
                            Text(LocalizedStringKey(section == "reports" ? reportStatusLabel(report.status) : (report.status ?? ""))).font(.caption)
                            if section == "users" { Text(String(format:UnyriL10n.string("Restricted known installations: %d"),report.installationBlocks ?? 0)).font(.caption) }
                            if section == "feedback" {
                                Button("Open screenshots") { perform {
                                    screenshots = try await request("feedback/\(report.id)").screenshots?.compactMap { $0.thumbnail() } ?? []
                                    noScreenshots = screenshots.isEmpty
                                    screenshotIndex = screenshots.isEmpty ? nil : 0
                                } }.disabled(busy)
                            }
                            if section == "users" || section == "reports", let user = report.userId {
                                Button("Restrict server access") { target = user; targetLabel = section == "users" ? "\(report.description ?? "") · @\(report.reason ?? "")" : ""; restrictionReason = ""; includeInstallations = false; temporary = true; showRestriction = true; actionSaved = false }.disabled(busy)
                            }
                            if section == "reports" {
                                NavigationLink("Review report") {
                                    ReportReviewView(report: report) { status, note, reason in
                                        _ = try await request("reports/\(report.id)", body: ["status":status,"note":note,"decision_reason":reason])
                                        reports = try await request("reports").items ?? []
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .unyriDecorativeTint(Color(hex: 0x7BC9EC)).buttonStyle(UnyriQuietButtonStyle())
        .sheet(isPresented: Binding(get: { screenshotIndex != nil && token != nil }, set: { if !$0 { screenshotIndex = nil } })) {
            NavigationStack {
                if let index = screenshotIndex, screenshots.indices.contains(index) {
                    VStack(spacing: 0) {
                        HStack {
                            Button { screenshotIndex = index - 1 } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44).background(Circle().fill(Color(hex: 0x28282B))) }.disabled(index == 0).accessibilityLabel("Previous")
                            Spacer(); Text("\(index + 1) / \(screenshots.count)"); Spacer()
                            Button { screenshotIndex = index + 1 } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44).background(Circle().fill(Color(hex: 0x28282B))) }.disabled(index == screenshots.count - 1).accessibilityLabel("Next")
                        }.padding()
                        AdminZoomImage(image: screenshots[index]).id(index)
                    }
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { screenshotIndex = nil } } }
                    .privacySensitive()
                    .overlay { if scenePhase != .active { Color(uiColor: .systemBackground).ignoresSafeArea() } }
                }
            }
        }
        .sheet(isPresented:$showRestriction) {
            NavigationStack {
                Form {
                    Section {
                        if !targetLabel.isEmpty { Text(targetLabel).font(.headline) }
                        Text(target).font(.caption).foregroundStyle(.secondary)
                        Text(server.absoluteString).font(.caption).foregroundStyle(.secondary)
                    }
                    TextField("Reason for restriction", text:$restrictionReason, axis:.vertical).lineLimit(2...5).frame(minHeight: 44)
                    Toggle("Include known app installations when blocking or unblocking", isOn:$includeInstallations)
                    Text("This affects other accounts using these installations on this server. Changing the installation ID can bypass it. Unblocking removes only restrictions originating from this account.").font(.footnote)
                    Toggle("For one hour", isOn:$temporary)
                    Button("Confirm restriction",role:.destructive) { perform {
                        var body: [String:Any] = ["blocked":true,"reason":restrictionReason,"include_installations":includeInstallations]
                        if temporary { body["until"] = ISO8601DateFormatter().string(from:Date().addingTimeInterval(3600)) }
                        _ = try await request("users/\(target)/suspension",body:body)
                        showRestriction = false; actionSaved = true
                    } }.disabled(busy || restrictionReason.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)
                    Button("Restore server access") { perform {
                        _ = try await request("users/\(target)/suspension",body:["blocked":false,"reason":restrictionReason,"include_installations":includeInstallations])
                        showRestriction = false; actionSaved = true
                    } }.disabled(busy || restrictionReason.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)
                }.unyriDecorativeTint(Color(hex: 0x7BC9EC)).buttonStyle(UnyriQuietButtonStyle()).unyriScreenBackground().navigationTitle("Restrict server access")
                .toolbar { ToolbarItem(placement:.cancellationAction) { Button("Cancel") { showRestriction = false }.disabled(busy) } }
            }.interactiveDismissDisabled(busy)
        }
        .unyriScreenBackground().navigationTitle("Server administration")
        .sheet(isPresented: Binding(get: { consoleURL != nil }, set: { if !$0 { consoleURL = nil } })) {
            if let url = consoleURL { ConsoleBrowser(url: url).ignoresSafeArea() }
        }
        .task { perform { try await loadStatus() } }
        .task(id:lastAction) { try? await Task.sleep(for:.seconds(900)); if !Task.isCancelled { clear() } }
        .privacySensitive()
        .overlay {
            if scenePhase != .active { Color(uiColor: .systemBackground).ignoresSafeArea() }
        }
        .alert("Generate a different key?", isPresented: $regenerate) {
            Button("Generate a different key", role: .destructive) { perform {
                paperKey = try AdminSigningKey.generate(); paperSaved = false; showPaper = true; manualPaper = false
            } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This replaces only the unfinished setup key on this screen. Cross out the previous copy and write down the new one. Your server key is unchanged until sign-in succeeds.")
        }
        .onChange(of: paperKey) { _ in paperSaved = false }
        .onChange(of: auth.sessionGeneration) { _ in
            operationTask?.cancel(); operationTask = nil; clear(); configured = nil
        }
        .task(id: setupExpiresAt) {
            guard let deadline = setupExpiresAt else { return }
            do { try await Task.sleep(for: .seconds(max(0, deadline - ProcessInfo.processInfo.systemUptime))) }
            catch { return }
            secret = ""; qr = nil; setupReady = false; setupExpiresAt = nil; copiedSecret = false; clearOwnClipboard()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { resumePanel() }
            else { suspendPanel() }
        }
        .onDisappear { operationTask?.cancel(); operationTask = nil; clear() }
    }
}

/// ADMIN CONSOLE PACK 3 - the console opens in the system browser view, not in a
/// WebView owned by this app.
///
/// `SFSafariViewController` keeps the console's own security model intact: it is
/// HTTPS-only, it uses the system cookie store (so the console's `HttpOnly`,
/// `SameSite=Strict` session cookie behaves exactly as it would in Safari), and
/// it never exposes the page to injected app JavaScript.
private struct ConsoleBrowser: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let configuration = SFSafariViewController.Configuration()
        configuration.entersReaderIfAvailable = false
        configuration.barCollapsingEnabled = false
        let controller = SFSafariViewController(url: url, configuration: configuration)
        controller.dismissButtonStyle = .close
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

private struct AdminPaperKeyFields: View {
    @Binding var value: String
    let visible: Bool
    @Environment(\.dynamicTypeSize) private var typeSize
    private func binding(_ index: Int) -> Binding<String> {
        Binding(get: {
            let parts = value.components(separatedBy: "-")
            return index < parts.count ? parts[index] : ""
        }, set: { input in
            let raw = AdminSigningKey.normalize(input)
            if raw.count > 8 {
                let chars = Array(raw.prefix(32))
                value = stride(from: 0, to: chars.count, by: 8).map { String(chars[$0..<min($0+8,chars.count)]) }.joined(separator: "-")
            } else {
                var parts = value.components(separatedBy: "-")
                while parts.count < 4 { parts.append("") }
                parts[index] = raw
                value = parts.prefix(4).joined(separator: "-")
            }
        })
    }
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: typeSize >= .xxLarge ? 1 : 2), spacing: 10) {
            ForEach(0..<4) { index in
                Group {
                    if visible { TextField("XXXXXXXX", text: binding(index)) }
                    else { SecureField("XXXXXXXX", text: binding(index)) }
                }
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.characters).autocorrectionDisabled().keyboardType(.asciiCapable)
                .frame(minHeight: 48).padding(.horizontal, 10).padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(UnyriColors.outlineSecondary, lineWidth: 1))
                .accessibilityLabel(Text(UnyriL10n.string("Administrator paper key") + " \(index + 1)/4"))
                .privacySensitive()
            }
        }
    }
}

private struct ReportReviewView: View {
    let report: AdminReport
    let save: (String,String,String) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var reason = ""
    @State private var status = "in_progress"
    @State private var busy = false
    @State private var failed = false
    var body: some View {
        UnyriSettingsPage("Review report") {
            UnyriSettingsGroup {
                Text(report.description ?? "").font(.subheadline).foregroundColor(UnyriColors.primaryText)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled).padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            UnyriSettingsGroup {
                Picker("Review report", selection: $status) {
                    Text("New").tag("open"); Text("In progress").tag("in_progress")
                    Text("Resolve").tag("resolved"); Text("Dismiss report").tag("dismissed")
                }.pickerStyle(.menu).frame(minHeight: 44).padding(.horizontal, 14)
            }
            UnyriSettingsGroup("Moderator note") {
                TextField("Moderator note", text: $note, axis: .vertical).lineLimit(3...8).padding(14)
            }
            UnyriSettingsGroup("Reason for decision") {
                TextField("Reason for decision", text: $reason, axis: .vertical).lineLimit(3...8).padding(14)
            }
            if failed { UnyriNotice(message: UnyriL10n.string("Administrative request failed. Check the connection, credentials and server configuration.")) }
            if busy { ProgressView().frame(maxWidth: .infinity) }
        }
        .safeAreaInset(edge: .bottom) {
            Button("Save settings") {
                busy = true; failed = false
                Task { @MainActor in
                    defer { busy = false }
                    do { try await save(status,note,reason); dismiss() } catch { failed = true }
                }
            }.disabled(busy || note.utf8.count>4000 || reason.utf8.count>4000 || (["resolved","dismissed"].contains(status) && reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                .unyriProminentAction().frame(maxWidth: .infinity).padding(.horizontal, 16).padding(.vertical, 12)
                .background(Color(hex: 0x1B1B1D))
        }.disabled(busy)
        .onAppear { note = report.reviewNote ?? ""; reason = report.decisionReason ?? ""; status = report.status ?? "open" }
    }
}
private let serverPolicyLabels: [(String,String)] = [
    ("name","Server name"),("operator","Operator"),("contact","Support contact"),
    ("rules","Server rules"),("retention","Data retention policy"),
    ("max_file_bytes","Maximum encrypted file size (bytes)"),
    ("user_quota_bytes","Quota per user (bytes; 0 = unlimited)"),
    ("storage_budget_bytes","Attachment storage budget (bytes; 0 = unlimited)")]

private struct ServerManagementView: View {
    let request: (String,[String:Any]?) async throws -> AdminReply
    @State private var fields: [String:String] = [:]
    @State private var stats: AdminReply?
    @State private var preview: AdminReply?
    @State private var result: AdminReply?
    @State private var busy = false
    @State private var failed = false
    private var valid: Bool {
        !(fields["name"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (4097...104861696).contains(Int64(fields["max_file_bytes"] ?? "") ?? 0) &&
        ["user_quota_bytes","storage_budget_bytes"].allSatisfy { (0...1_000_000_000_000_000).contains(Int64(fields[$0] ?? "") ?? -1) }
    }
    @MainActor private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; failed = false
        Task { @MainActor in defer { busy = false }; do { try await action() } catch { failed = true } }
    }
    @MainActor private func load() async throws {
        let p = try await request("server-policy",nil)
        fields = ["name":p.name ?? "", "operator":p.operator ?? "", "contact":p.contact ?? "", "rules":p.rules ?? "", "retention":p.retention ?? "",
            "max_file_bytes":String(p.maxFileBytes ?? 0),"user_quota_bytes":String(p.userQuotaBytes ?? 0),"storage_budget_bytes":String(p.storageBudgetBytes ?? 0)]
        stats = try await request("storage",nil)
    }
    var body: some View {
        UnyriSettingsPage("Server storage") {
            if failed { UnyriNotice(message: UnyriL10n.string("Administrative request failed. Check the connection, credentials and server configuration.")) }
            UnyriSettingsGroup {
                Button("Refresh") { perform { try await load(); preview = nil } }.frame(minHeight: 44).frame(maxWidth: .infinity)
            }
            if !fields.isEmpty {
                UnyriSettingsGroup("Server card") {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(serverPolicyLabels, id: \.0) { key, label in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(LocalizedStringKey(label)).font(.caption).foregroundColor(UnyriColors.secondaryText)
                                TextField(LocalizedStringKey(label), text: Binding(get: {fields[key] ?? ""}, set: {fields[key] = String($0.prefix(key == "rules" || key == "retention" ? 2000 : 100))}), axis: .vertical)
                                    .font(.subheadline).lineLimit(1...6).frame(minHeight: 44).padding(.horizontal, 10)
                                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x242426)))
                            }
                        }
                    Button("Save settings") { perform {
                        var body: [String:Any] = [:]
                        for (key,_) in serverPolicyLabels {
                            if key.hasSuffix("_bytes") { body[key] = Int64(fields[key] ?? "") }
                            else { body[key] = fields[key] ?? "" }
                        }
                        _ = try await request("server-policy",body); stats = try await request("storage",nil)
                    } }.disabled(!valid)
                            .unyriProminentAction().frame(maxWidth: .infinity)
                    }.padding(14)
                }
                UnyriSettingsGroup("Server storage") {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Includes encrypted attachments and reserved uploads, not the physical disk. Avatars, reports and backups are not included. Only unattached uploads older than 24 hours can be cleaned here. Up to 100 per preview.")
                            .font(.footnote).foregroundColor(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                        Text("Used / reserved").font(.caption).foregroundColor(UnyriColors.secondaryText)
                        Text("\(FileSizeFormatter.format(stats?.usedBytes ?? 0)) / \(FileSizeFormatter.format(stats?.reservedBytes ?? 0))")
                            .font(.headline).foregroundColor(UnyriColors.primaryText).fixedSize(horizontal: false, vertical: true)
                        if stats?.warning == true { Text("Storage budget is at least 90% used.").font(.footnote).foregroundColor(UnyriColors.warning) }
                        Button("Preview abandoned uploads") { perform { preview = try await request("storage/preview",[:]); result = nil } }.frame(minHeight: 44)
                        if let preview {
                            Text("\(preview.items?.count ?? 0) · \(FileSizeFormatter.format(preview.bytes ?? 0))").font(.subheadline.weight(.semibold))
                            LazyVStack(alignment: .leading, spacing: 10) {
                                ForEach(preview.items ?? []) { row in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(row.id).foregroundColor(UnyriColors.primaryText)
                                        Text("\(row.createdAt ?? "") · \(row.bytes ?? 0) B").foregroundColor(UnyriColors.secondaryText)
                                    }.font(.caption).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        Button("Delete listed uploads",role:.destructive) { perform {
                            guard let id = preview.planId else { return }
                            result = try await request("storage/cleanup",["plan_id":id]); self.preview = nil
                            stats = try await request("storage",nil)
                        } }.disabled(preview.items?.isEmpty != false)
                            .frame(minHeight: 48).foregroundColor(UnyriColors.danger)
                        }
                        if let result {
                            Text("Deleted / skipped / failed").font(.caption).foregroundColor(UnyriColors.secondaryText)
                            Text("\(result.deleted ?? 0) / \(result.skipped ?? 0) / \(result.failed ?? 0)").font(.subheadline.weight(.semibold))
                        }
                    }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
        }.disabled(busy).task { perform { try await load() } }
    }

}
struct ServerCardView: View {
    @State private var card: AdminReply?
    @State private var failed = false
    @State private var attempt = 0
    var server = AppConfig.apiBaseURL
    var body: some View {
        UnyriSettingsPage("Server card") {
            UnyriSettingsGroup("Current server") {
                Label { Text(server.absoluteString).font(.subheadline).fixedSize(horizontal: false, vertical: true).textSelection(.enabled) }
                    icon: { Image(systemName: "server.rack").unyriDecorativeForeground(Color(hex: 0x7BC9EC)).accessibilityHidden(true) }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            }
            UnyriSettingsNote("Provided by the server operator. Verify whom you trust before sharing data.")
            if failed {
                UnyriSettingsGroup {
                    Text("Server information could not be loaded.").font(.footnote).foregroundColor(UnyriColors.warning)
                        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    Button { attempt += 1 } label: {
                        UnyriSettingsRow(title: "Retry", icon: "arrow.clockwise", tint: Color(hex: 0x7BC9EC), showsChevron: false)
                    }
                }
            } else if card == nil { ProgressView().frame(maxWidth: .infinity, minHeight: 60) }
            if let card {
                serverDetail("Server name", card.name ?? "—")
                serverDetail("Operator", (card.operator ?? "").isEmpty ? "—" : (card.operator ?? ""))
                serverDetail("Support contact", (card.contact ?? "").isEmpty ? "—" : (card.contact ?? ""))
                serverDetail("Server rules", (card.rules ?? "").isEmpty ? "—" : (card.rules ?? ""))
                serverDetail("Data retention policy", (card.retention ?? "").isEmpty ? "—" : (card.retention ?? ""))
                serverDetail("Maximum encrypted file size", card.maxFileBytes.map { FileSizeFormatter.format($0) } ?? "—")
                serverDetail("Storage limit per account", card.userQuotaBytes.map { $0 == 0 ? UnyriL10n.string("Unlimited") : FileSizeFormatter.format($0) } ?? "—")
            }
        }
        .task(id:attempt) {
            failed = false
            do {
                let configuration = URLSessionConfiguration.ephemeral
                configuration.timeoutIntervalForRequest = 15
                let session = URLSession(configuration:configuration,delegate:AdminRedirectPolicy(),delegateQueue:nil)
                defer { session.invalidateAndCancel() }
                let (bytes,response) = try await session.bytes(from:server.appendingPathComponent("server-info"))
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw APIError.invalidURL }
                var data = Data()
                for try await byte in bytes {
                    guard data.count < 65536 else { throw APIError.invalidURL }
                    data.append(byte)
                }
                let decoder = JSONDecoder();decoder.keyDecodingStrategy = .convertFromSnakeCase
                if !Task.isCancelled { card = try decoder.decode(AdminReply.self,from:data) }
            } catch { if !Task.isCancelled { failed = true } }
        }
    }
    private func serverDetail(_ title: String, _ value: String) -> some View {
        UnyriSettingsGroup(title) {
            Text(value).font(.subheadline).foregroundColor(UnyriColors.primaryText)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

}

private func reportStatusLabel(_ status: String?) -> String {
    switch status {
    case "in_progress": return "In progress"
    case "resolved": return "Resolved"
    case "dismissed": return "Dismissed"
    default: return "New"
    }
}

/// Bounded, memory-only viewer; report screenshots are never copied to Photos or disk.
private struct AdminZoomImage: UIViewRepresentable {
    let image: UIImage
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> AdminZoomScroll {
        let view = AdminZoomScroll(); view.delegate = context.coordinator
        view.minimumZoomScale = 1; view.maximumZoomScale = 5
        view.backgroundColor = .black; view.picture.contentMode = .scaleAspectFit
        view.picture.image = image; view.addSubview(view.picture)
        return view
    }
    func updateUIView(_ view: AdminZoomScroll, context: Context) { view.picture.image = image }
    final class Coordinator: NSObject, UIScrollViewDelegate {
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { (scrollView as? AdminZoomScroll)?.picture }
    }
}
private final class AdminZoomScroll: UIScrollView {
    let picture = UIImageView()
    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale == 1 { picture.frame = bounds; contentSize = bounds.size }
    }
}


private struct CallQualitySettingsView: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @AppStorage(CallQuality.videoKey) private var video = CallQuality.auto.rawValue
    @AppStorage(CallQuality.audioKey) private var audio = CallQuality.auto.rawValue

    var body: some View {
        UnyriSettingsPage("Call quality") {
            qualitySection("Video quality", selection: $video, isVideo: true)
            qualitySection("Audio quality", selection: $audio, isVideo: false)
            UnyriSettingsNote("Applies to your outgoing audio and video from the next call on this phone. Every mode can reduce quality when the network worsens. Incoming quality and data use also depend on the other person.")
        }
    }

    private func qualitySection(_ title: String, selection: Binding<String>, isVideo: Bool) -> some View {
        UnyriSettingsGroup(title) {
            ForEach(CallQuality.allCases, id: \.rawValue) { mode in
                Button {
                    selection.wrappedValue = mode.rawValue
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: mode == .auto ? "slider.horizontal.3" : mode == .economy ? "leaf" : "sparkles")
                            .font(.system(size: 16)).foregroundColor(colorStyle.decorative(mode == .economy ? Color(hex: 0x83D9B7) : Color(hex: 0x7BC9EC)))
                            .frame(width: 30, height: 30).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(label(mode)).font(.subheadline.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                            Text(detail(mode, isVideo: isVideo)).font(.caption).foregroundColor(UnyriColors.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: CallQuality.parse(selection.wrappedValue) == mode ? "checkmark.circle.fill" : "circle")
                            .foregroundColor(CallQuality.parse(selection.wrappedValue) == mode ? colorStyle.role(.categoryMint) : UnyriColors.veryMutedText)
                            .font(.system(size: 20)).accessibilityHidden(true)
                    }.padding(14).frame(minHeight: 60).contentShape(Rectangle())
                }
                .buttonStyle(UnyriQuietButtonStyle())
                .accessibilityAddTraits(CallQuality.parse(selection.wrappedValue) == mode ? [.isSelected] : [])
                if mode != .high { UnyriSettingsDivider() }
            }
        }
    }

    private func label(_ mode: CallQuality) -> LocalizedStringKey {
        switch mode { case .auto: return "Auto"; case .economy: return "Save data"; case .high: return "High" }
    }
    private func detail(_ mode: CallQuality, isVideo: Bool) -> LocalizedStringKey {
        switch mode {
        case .auto: return "Balances quality and available bandwidth. Recommended."
        case .economy: return isVideo ? "Up to 360p, 15 fps. Uses less data and camera processing." : "Less data for voice. Speech may sound less detailed."
        case .high: return isVideo ? "Up to 1080p, 30 fps when the camera and network allow. Uses more data." : "More bandwidth for voice detail. Does not improve a poor connection."
        }
    }
}
