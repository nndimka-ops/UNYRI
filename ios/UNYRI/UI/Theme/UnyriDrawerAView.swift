import SwiftUI

/// Only the selected branch is constructed; both variants receive the same host snapshot.
struct UnyriDrawerView: View {
    let choice: DrawerChoice
    let screenWidth: CGFloat
    let availableHeight: CGFloat
    let displayName: String
    let username: String?
    let avatarURL: URL?
    let selectedID: String
    let unreadCount: Int
    let showCalls: Bool
    let showSaved: Bool
    let onEdit: () -> Void
    let onItem: (String) -> Void
    var badges: [String: String] = [:]
    var onQR: (() -> Void)? = nil
    var showsAccounts = false
    var serverHost: String = AppConfig.apiBaseURL.host ?? ""
    var publicNumber: String? = nil

    var body: some View {
        switch choice {
        case .a:
            UnyriDrawerAView(screenWidth: screenWidth, availableHeight: availableHeight,
                displayName: displayName, username: username, avatarURL: avatarURL,
                selectedID: selectedID, unreadCount: unreadCount, showCalls: showCalls, showSaved: showSaved,
                onEdit: onEdit, onItem: onItem, badges: badges, onQR: onQR,
                showsAccounts: showsAccounts, serverHost: serverHost, publicNumber: publicNumber)
        case .c:
            UnyriSavedSidebarView(screenWidth: screenWidth, availableHeight: availableHeight,
                displayName: displayName, username: username, avatarURL: avatarURL,
                selectedID: selectedID, unreadCount: unreadCount, showCalls: showCalls, showSaved: showSaved,
                onEdit: onEdit, onItem: onItem, badges: badges, onQR: onQR,
                showsAccounts: showsAccounts, serverHost: serverHost, publicNumber: publicNumber)
        }
    }
}

/// Artboard A with the owner's later account, report and footer rules.
/// Navigation, badges, QR presentation and pan ownership remain in LoggedInView.
struct UnyriDrawerAView: View {
    let screenWidth: CGFloat
    let availableHeight: CGFloat
    let displayName: String
    let username: String?
    let avatarURL: URL?
    let selectedID: String
    let unreadCount: Int
    let showCalls: Bool
    let showSaved: Bool
    let onEdit: () -> Void
    let onItem: (String) -> Void
    var badges: [String: String] = [:]
    var onQR: (() -> Void)? = nil
    var showsAccounts = false
    var serverHost: String = AppConfig.apiBaseURL.host ?? ""
    var publicNumber: String? = nil
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1

    /// Owner, 14 Nov: A is 40% of the screen; everything fits by stacking and wrapping.
    private var narrow: Bool { !dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: narrow ? 8 : 12) {
                profile
                if !narrow { statusAndAccounts }
                VStack(spacing: 2) {
                    row("chats", "Chats", asset: "UnyriSidebarChats")
                    row("contacts", "Contacts", asset: "UnyriSidebarContacts")
                    if showCalls { row("calls", "Calls", asset: "UnyriSidebarCalls") }
                    row("rooms", "Rooms", asset: "UnyriSidebarRooms")
                    if showSaved { row("saved", "Saved Messages", asset: "UnyriSidebarSaved") }
                    row("archive", "Archive", asset: "UnyriSidebarArchive")
                    row("transfers", "Transfers", asset: "UnyriSidebarTransfers")
                }
                Rectangle().fill(UnyriColors.sidebarLine).frame(height: 0.5).padding(.horizontal, narrow ? 10 : 14)
                    .accessibilityHidden(true)
                VStack(spacing: 2) {
                    row("settings", "Settings", asset: "UnyriSidebarSettings", secondary: true)
                    row("help", "Help", asset: "UnyriSidebarHelp", secondary: true)
                    row("report", "Report a problem", asset: "UnyriSidebarReport", secondary: true)
                }
                Spacer(minLength: 0)
                server
                Text(verbatim: AppVersion.localizedDisplay(
                    shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                    build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String))
                    .font(.system(size: 11 * textScale)).foregroundStyle(UnyriColors.sidebarMuted)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity).accessibilityIdentifier("drawer-version")
            }
            .padding(.horizontal, narrow ? 8 : 12).padding(.top, 18).padding(.bottom, 14)
            .frame(minHeight: availableHeight, alignment: .top)
            // Inside the scroll view: the host's "main-drawer" identifier replaces one set outside.
            .accessibilityElement(children: .contain).accessibilityIdentifier("drawer-layout-a")
        }
        .frame(width: CGFloat(DrawerChoice.a.width(screenWidth: Double(screenWidth), accessibility: dynamicTypeSize.isAccessibilitySize,
                                                   textScale: Double(textScale))),
               height: availableHeight)
        .background { UnyriDrawerPanelMaterial() }
        .clipShape(RoundedRectangle(cornerRadius: 40, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 40, style: .continuous).stroke(.white.opacity(0.10), lineWidth: 0.5))
    }

    private var profile: some View {
        Group {
            if narrow {
                // Avatar and QR on top; the name may take two lines; status and the account
                // button share a row when both fit and wrap otherwise.
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 0) { avatar; Spacer(minLength: 8); qr }
                    // Owner, 18 Nov (B-031): the name and the number read as one caption;
                    // a long name wraps to a second line and the number follows it.
                    VStack(alignment: .leading, spacing: 2) { profileText; numberButton }
                    UnyriReactionRows(spacing: 4, rowGap: 6) { status; accounts }
                }
            } else if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 12) {
                    avatar
                    profileText
                    numberButton
                    qr
                }
            } else {
                HStack(spacing: 12) { avatar; VStack(alignment: .leading, spacing: 2) { profileText; numberButton }; qr }
            }
        }.padding(.horizontal, narrow ? 2 : 4)
    }

    private var avatar: some View {
        Button(action: onEdit) { UserAvatar(url: avatarURL, name: displayName, size: narrow ? 48 : 56) }
            .buttonStyle(.plain).accessibilityLabel(Text(displayName))
            .accessibilityHint(Text("Open profile")).accessibilityIdentifier("drawer-profile")
    }

    private var profileText: some View {
        Button(action: onEdit) {
            VStack(alignment: .leading, spacing: 2) {
                // Preserve the complete stored name (including legacy names over 32 characters).
                Text(displayName).font(.system(size: (narrow ? 17 : 18) * textScale, weight: .semibold))
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : narrow ? 2 : 1).minimumScaleFactor(narrow ? 13.0 / 17.0 : 16.0 / 18.0)
                    .truncationMode(.tail).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let username, !username.isEmpty {
                    Text("@" + username).font(.system(size: (narrow ? 12 : 13) * textScale))
                        .lineLimit(1).minimumScaleFactor(CGFloat(DrawerCPolicy.usernameMinimumScale)).truncationMode(.middle)
                        .foregroundStyle(UnyriColors.secondaryText)
                }
            }.foregroundStyle(UnyriColors.primaryText).frame(maxWidth: .infinity, minHeight: narrow ? nil : 44, alignment: .leading)
                // Narrow caption: the tap area reaches into the gap above instead of padding the layout.
                .padding(.top, narrow ? 8 : 0).padding(.bottom, narrow ? 1 : 0)
                .contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, narrow ? -8 : 0).padding(.bottom, narrow ? -1 : 0)
            .accessibilityLabel(Text(displayName)).accessibilityHint(Text("Open profile"))
            .accessibilityValue(Text(username.flatMap { $0.isEmpty ? nil : "@" + $0 } ?? ""))
            .accessibilityIdentifier("drawer-name")
    }

    @ViewBuilder private var numberButton: some View {
        if let publicNumber {
            Button { UIPasteboard.general.string = publicNumber; UnyriHaptics.selection() } label: {
                Text(PublicIdentityPolicy.formattedNumber(publicNumber))
                    .font(.system(size: (narrow ? 12 : 13) * textScale).monospacedDigit())
                    .foregroundStyle(UnyriColors.secondaryText).frame(minHeight: narrow ? nil : 44)
                    // Narrow caption: the tap area reaches into the gap below, not into the name.
                    .padding(.top, narrow ? 1 : 0).padding(.bottom, narrow ? 8 : 0).contentShape(Rectangle())
            }.buttonStyle(.plain).padding(.top, narrow ? -1 : 0).padding(.bottom, narrow ? -8 : 0)
                .accessibilityLabel(Text(UnyriL10n.string("Copy number") + " " + publicNumber))
                .accessibilityIdentifier("drawer-public-number")
        }
    }

    private var qr: some View {
        Button { onQR?() } label: {
            Image(systemName: "qrcode").font(.system(size: 20)).foregroundStyle(UnyriColors.primaryText)
                .frame(width: 44, height: 44).modifier(UnyriDrawerGlass())
        }.buttonStyle(.plain).accessibilityLabel("My QR code").accessibilityIdentifier("drawer-qr")
    }

    private var statusAndAccounts: some View {
        // One pair of live controls keeps its identity when the layout changes.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            status
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
            accounts
        }.padding(.horizontal, 4)
    }

    @ViewBuilder private var status: some View {
        if onQR != nil { UnyriSidebarStatusControl(compact: true) }
        else {
            HStack(spacing: 6) {
                Circle().fill(UnyriColors.online).frame(width: 8, height: 8)
                Text("Online").font(.system(size: 13 * textScale))
                Image(systemName: "chevron.down").font(.system(size: 10)).frame(width: 14, height: 14)
            }.foregroundStyle(UnyriColors.primaryText).padding(.horizontal, 12).frame(minHeight: 32)
                .modifier(UnyriDrawerGlass()).frame(minHeight: 44)
                .fixedSize(horizontal: !dynamicTypeSize.isAccessibilitySize, vertical: true)
        }
    }

    @ViewBuilder private var accounts: some View {
        if showsAccounts { UnyriDrawerAccountSwitcher(compact: true) }
        else { UnyriDrawerAddAccountLabel(compact: true).opacity(0.7).allowsHitTesting(false).accessibilityHidden(true) }
    }

    private func row(_ id: String, _ title: LocalizedStringKey, asset: String, secondary: Bool = false) -> some View {
        let selected = selectedID == id
        let count = id == "chats" ? DrawerCPolicy.count(unreadCount) : badges[id]
        return Button { UnyriHaptics.press(); onItem(id) } label: {
            HStack(spacing: narrow ? 10 : 14) {
                icon(asset, size: secondary ? 18 : 20)
                    .foregroundStyle(selected ? UnyriColors.primaryText : UnyriColors.secondaryText)
                // Narrow drawer: a long label wraps by words to a second line.
                Text(title).font(.system(size: (narrow ? (secondary ? 14 : 15) : (secondary ? 15 : 16)) * textScale,
                                         weight: selected ? .semibold : .regular))
                    .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                if let count {
                    if id == "transfers" {
                        Text(count).font(.system(size: 13 * textScale)).foregroundStyle(UnyriColors.secondaryText)
                            .monospacedDigit().accessibilityHidden(true)
                    } else { badge(count, id: id) }
                }
            }.foregroundStyle(secondary ? UnyriColors.sidebarSecondaryText : UnyriColors.primaryText)
                .padding(.horizontal, narrow ? 10 : 14).padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 10 : 4)
                .frame(minHeight: secondary || narrow ? 44 : 48)
                .background(selected ? UnyriColors.sidebarSelected : .clear, in: RoundedRectangle(cornerRadius: secondary ? 22 : 24))
                .contentShape(RoundedRectangle(cornerRadius: secondary ? 22 : 24))
        }.buttonStyle(.plain).accessibilityLabel(Text(title)).accessibilityValue(Text(accessibleCount(count, id: id)))
            .accessibilityAddTraits(selected ? .isSelected : []).accessibilityIdentifier("drawer-" + id)
    }

    private func accessibleCount(_ count: String?, id: String) -> String {
        guard let count else { return "" }
        if id == "transfers" { return count }
        return String(format: UnyriL10n.string(id == "calls" ? "%@ missed calls" : "%@ unread messages"), count)
    }

    private func badge(_ count: String, id: String) -> some View {
        let style = DrawerCPolicy.badge(for: id)
        return Text(count).font(.system(size: 12 * textScale, weight: .bold)).monospacedDigit()
            .foregroundStyle(style == .danger ? Color.white : style == .accent ? UnyriColors.onAccent : UnyriColors.primaryText)
            .padding(.horizontal, 6).frame(minWidth: 22, minHeight: 22)
            .background(style == .danger ? UnyriColors.danger : style == .accent ? UnyriColors.accent : Color.white.opacity(0.1), in: Capsule())
            .accessibilityHidden(true).accessibilityIdentifier("drawer-" + id + "-badge")
    }

    private var server: some View {
        Button { UnyriHaptics.press(); onItem("server") } label: {
            HStack(spacing: narrow ? 6 : 10) {
                icon("UnyriSidebarServer", size: narrow ? 14 : 16)
                // Narrow: "8c86b74c…scw.cloud" on up to two lines (same rule as Android).
                Text(narrow ? DrawerChoice.narrowHostLabel(serverHost) : serverHost)
                    .font(.system(size: (narrow ? 11 : 13) * textScale)).lineLimit(narrow ? 2 : 1).truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }.foregroundStyle(UnyriColors.secondaryText).padding(.horizontal, narrow ? 10 : 14).padding(.vertical, narrow ? 8 : 10)
                .frame(minHeight: 44).background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 18))
                .contentShape(RoundedRectangle(cornerRadius: 18))
        }.buttonStyle(.plain).accessibilityLabel(Text(UnyriL10n.string("Server") + " " + serverHost))
            .accessibilityIdentifier("drawer-server")
    }

    private func icon(_ asset: String, size: CGFloat) -> some View {
        Image(asset).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size).accessibilityHidden(true)
    }
}
