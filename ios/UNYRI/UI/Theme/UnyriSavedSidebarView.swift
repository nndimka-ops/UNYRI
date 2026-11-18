import SwiftUI
import UIKit

/// Final artboard C (v17). Navigation and the window pan remain owned by LoggedInView.
struct UnyriSavedSidebarView: View {
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1

    private var width: CGFloat {
        CGFloat(DrawerCPolicy.width(screenWidth: Double(screenWidth), accessibility: dynamicTypeSize.isAccessibilitySize))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                profile
                VStack(spacing: 2) {
                    mainRow("chats", "Chats", asset: "UnyriSidebarChats")
                    mainRow("contacts", "Contacts", asset: "UnyriSidebarContacts")
                    if showCalls { mainRow("calls", "Calls", asset: "UnyriSidebarCalls") }
                    mainRow("rooms", "Rooms", asset: "UnyriSidebarRooms")
                    if showSaved { mainRow("saved", "Saved Messages", asset: "UnyriSidebarSaved") }
                }
                Rectangle().fill(UnyriColors.sidebarLine).frame(height: 0.5).padding(.horizontal, 14)
                    .accessibilityHidden(true)
                secondary
                report
                Spacer(minLength: 0)
                Text(verbatim: AppVersion.localizedDisplay(
                    shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                    build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String))
                    .font(.system(size: 11 * textScale)).foregroundStyle(UnyriColors.sidebarMuted)
                    .frame(maxWidth: .infinity).accessibilityIdentifier("drawer-version")
            }
            .padding(.horizontal, 12).padding(.vertical, 14)
            .frame(minHeight: availableHeight, alignment: .top)
            // Inside the scroll view: the host's "main-drawer" identifier replaces one set outside.
            .accessibilityElement(children: .contain).accessibilityIdentifier("drawer-layout-c")
        }
        .frame(width: width, height: availableHeight)
        .background { UnyriDrawerPanelMaterial() }
        .clipShape(RoundedRectangle(cornerRadius: 40, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 40, style: .continuous).stroke(.white.opacity(0.10), lineWidth: 0.5))
    }

    private var profile: some View {
        VStack(alignment: .leading, spacing: 14) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 12) { avatar; profileText }
            } else {
                HStack(alignment: .center, spacing: 12) { avatar; profileText }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { status; qr }
                VStack(spacing: 8) { status; qr }
            }
            if showsAccounts { UnyriDrawerAccountSwitcher() }
            else { UnyriDrawerAddAccountLabel().opacity(0.7).allowsHitTesting(false) }
        }
        .foregroundStyle(UnyriColors.primaryText)
        .padding(16)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 30))
        .overlay(RoundedRectangle(cornerRadius: 30).stroke(.white.opacity(0.09), lineWidth: 0.5))
        .accessibilityElement(children: .contain).accessibilityIdentifier("drawer-profile-card")
    }

    private var avatar: some View {
        Button(action: onEdit) { UserAvatar(url: avatarURL, name: displayName, size: 64) }
            .buttonStyle(.plain).accessibilityLabel(Text(displayName))
            .accessibilityHint(Text("Open profile")).accessibilityIdentifier("drawer-profile")
    }

    private var profileText: some View {
        VStack(alignment: .leading, spacing: 2) {
            Button(action: onEdit) {
                UnyriDrawerProfileName(name: displayName, scale: textScale)
                    .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                    .contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(Text(displayName)).accessibilityHint(Text("Open profile"))
            if let publicNumber {
                Button { UIPasteboard.general.string = publicNumber; UnyriHaptics.selection() } label: {
                    Text(PublicIdentityPolicy.formattedNumber(publicNumber)).font(.system(size: 15 * textScale).monospacedDigit())
                        .foregroundStyle(UnyriColors.secondaryText).frame(minHeight: 44)
                }.buttonStyle(.plain).accessibilityLabel(Text(UnyriL10n.string("Copy number") + " " + publicNumber))
                    .accessibilityIdentifier("drawer-public-number")
            }
            if let username {
                Text("@" + username).font(.system(size: 13 * textScale))
                    .lineLimit(1).minimumScaleFactor(CGFloat(DrawerCPolicy.usernameMinimumScale)).truncationMode(.middle)
                    .foregroundStyle(UnyriColors.secondaryText)
            }
            Button { UnyriHaptics.press(); onItem("server") } label: {
                HStack(spacing: 3) {
                    Text(serverHost).lineLimit(1).truncationMode(.middle)
                    Text("›").accessibilityHidden(true)
                }.font(.system(size: 12 * textScale)).foregroundStyle(UnyriColors.secondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(Text(UnyriL10n.string("Server") + " " + serverHost))
                .accessibilityIdentifier("drawer-server")
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var status: some View {
        if onQR != nil { UnyriSidebarStatusControl() }
        else { Text("Online").font(.system(size: 13 * textScale)).frame(maxWidth: .infinity, minHeight: 44).padding(.horizontal, 10).modifier(UnyriDrawerGlass()) }
    }

    private var qr: some View {
        Button { onQR?() } label: {
            HStack(spacing: 6) {
                Image(systemName: "qrcode").font(.system(size: 16))
                Text("My QR").font(.system(size: 13 * textScale)).lineLimit(1).minimumScaleFactor(0.8)
            }.padding(.horizontal, 10).frame(maxWidth: .infinity, minHeight: 44).modifier(UnyriDrawerGlass())
        }.buttonStyle(.plain).accessibilityLabel("My QR code").accessibilityIdentifier("drawer-qr")
    }

    private func mainRow(_ id: String, _ title: LocalizedStringKey, asset: String) -> some View {
        let selected = selectedID == id
        let count = id == "chats" ? DrawerCPolicy.count(unreadCount) : badges[id]
        return Button { UnyriHaptics.press(); onItem(id) } label: {
            HStack(spacing: 14) {
                icon(asset, size: 22).foregroundStyle(selected ? UnyriColors.primaryText : UnyriColors.secondaryText)
                Text(title).font(.system(size: 17 * textScale, weight: selected ? .semibold : .regular))
                    .lineLimit(1).minimumScaleFactor(0.4)
                    .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                if let count { badge(count, id: id) }
            }.foregroundStyle(UnyriColors.primaryText)
                .padding(.horizontal, 14).padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 10 : 0)
                .frame(minHeight: 52)
                .background(selected ? UnyriColors.sidebarSelected : .clear, in: RoundedRectangle(cornerRadius: 26))
                .contentShape(RoundedRectangle(cornerRadius: 26))
        }.buttonStyle(DrawerCPressStyle(reduceMotion: reduceMotion))
            .accessibilityLabel(Text(title)).accessibilityValue(Text(accessibleCount(count, id: id)))
            .accessibilityAddTraits(selected ? .isSelected : []).accessibilityIdentifier("drawer-" + id)
    }

    private var secondary: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                pill("archive", "Archive", asset: "UnyriSidebarArchive")
                pill("transfers", "Transfers", asset: "UnyriSidebarTransfers")
            }
            HStack(spacing: 6) {
                pill("settings", "Settings", asset: "UnyriSidebarSettings")
                pill("help", "Help", asset: "UnyriSidebarHelp")
            }
        }
    }

    private func pill(_ id: String, _ title: String, asset: String) -> some View {
        let count = badges[id]
        let spoken = UnyriL10n.string(title) + (id == "archive" && count != nil ? ", " + accessibleCount(count, id: id) : "")
        return Button { UnyriHaptics.press(); onItem(id) } label: {
            VStack(spacing: 2) {
                HStack(spacing: 8) {
                    if !dynamicTypeSize.isAccessibilitySize { icon(asset, size: 16) }
                    Text(UnyriL10n.string(title) + (id == "transfers" && !dynamicTypeSize.isAccessibilitySize && count != nil ? " · " + (count ?? "") : ""))
                        .lineLimit(1).minimumScaleFactor(0.5)
                    if id == "archive", let count, !dynamicTypeSize.isAccessibilitySize { badge(count, id: id) }
                }
                if dynamicTypeSize.isAccessibilitySize, let count {
                    if id == "archive" { badge(count, id: id) }
                    else if id == "transfers" { Text("· " + count).monospacedDigit().lineLimit(1) }
                }
            }.font(.system(size: 14 * textScale)).foregroundStyle(UnyriColors.sidebarSecondaryText)
                .multilineTextAlignment(.center).padding(.horizontal, 8).padding(.vertical, 6)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 22))
                .contentShape(RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(DrawerCPressStyle(reduceMotion: reduceMotion))
            .accessibilityLabel(Text(spoken)).accessibilityIdentifier("drawer-" + id)
    }

    private var report: some View {
        Button { UnyriHaptics.press(); onItem("report") } label: {
            HStack(spacing: 8) {
                icon("UnyriSidebarReport", size: 16)
                Text("Report a problem").font(.system(size: 14 * textScale)).fixedSize(horizontal: false, vertical: true)
            }.foregroundStyle(UnyriColors.sidebarSecondaryText).padding(.horizontal, 12).padding(.vertical, 6)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 22))
                .contentShape(RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(DrawerCPressStyle(reduceMotion: reduceMotion)).accessibilityIdentifier("drawer-report")
    }

    private func accessibleCount(_ count: String?, id: String) -> String {
        guard let count else { return "" }
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

    private func icon(_ asset: String, size: CGFloat) -> some View {
        Image(asset).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size).accessibilityHidden(true)
    }
}

struct UnyriDrawerPanelMaterial: View {
    @ViewBuilder var body: some View {
        let shape = RoundedRectangle(cornerRadius: 40, style: .continuous)
        if #available(iOS 26.0, *) {
            shape.fill(UnyriColors.sidebarBottom.opacity(0.3)).glassEffect(.regular, in: shape)
        } else {
            shape.fill(.ultraThinMaterial).overlay {
                LinearGradient(colors: [UnyriColors.sidebarTop.opacity(0.86), UnyriColors.sidebarBottom.opacity(0.90)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            }.clipShape(shape)
        }
    }
}

/// UILabel measures the actual available column in the first SwiftUI layout pass,
/// without GeometryReader/State feedback or a character-count approximation.
private struct UnyriDrawerProfileName: UIViewRepresentable {
    let name: String
    let scale: CGFloat
    func makeUIView(context: Context) -> UnyriDrawerNameLabel {
        let label = UnyriDrawerNameLabel()
        label.isAccessibilityElement = false
        label.minimumScaleFactor = 0.8
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return label
    }
    func updateUIView(_ label: UnyriDrawerNameLabel, context: Context) {
        if label.text != name || label.textScale != scale {
            label.text = name
            label.textScale = scale
            label.invalidateIntrinsicContentSize()
            label.setNeedsLayout()
        }
        label.textColor = UIColor(UnyriColors.primaryText)
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView label: UnyriDrawerNameLabel, context: Context) -> CGSize? {
        let font = UIFont.systemFont(ofSize: 20 * scale, weight: .bold)
        let measured = (name as NSString).size(withAttributes: [.font: font]).width
        let width = proposal.width.map { $0.isFinite ? max(1, $0) : max(1, measured) } ?? max(1, measured)
        let policy = DrawerCPolicy.nameLayout(measuredWidth: Double(measured), availableWidth: Double(width))
        let chosenFont = UIFont.systemFont(ofSize: CGFloat(policy.pointSize) * scale, weight: .bold)
        return CGSize(width: width, height: ceil(chosenFont.lineHeight * CGFloat(policy.lines)))
    }
}

/// Final bounds decide rendering as well as measurement; an intermediate
/// SwiftUI min/ideal-width probe cannot leave the UILabel in the wrong mode.
private final class UnyriDrawerNameLabel: UILabel {
    var textScale: CGFloat = 1
    override func layoutSubviews() {
        let measured = ((text ?? "") as NSString).size(withAttributes: [
            .font: UIFont.systemFont(ofSize: 20 * textScale, weight: .bold)
        ]).width
        let policy = DrawerCPolicy.nameLayout(measuredWidth: Double(measured), availableWidth: Double(bounds.width))
        let chosen = UIFont.systemFont(ofSize: CGFloat(policy.pointSize) * textScale, weight: .bold)
        if font != chosen { font = chosen }
        if numberOfLines != policy.lines { numberOfLines = policy.lines }
        if adjustsFontSizeToFitWidth != (policy.lines == 1) { adjustsFontSizeToFitWidth = policy.lines == 1 }
        if lineBreakMode != .byTruncatingTail { lineBreakMode = .byTruncatingTail }
        if preferredMaxLayoutWidth != bounds.width { preferredMaxLayoutWidth = bounds.width }
        super.layoutSubviews()
    }
}

private struct DrawerCPressStyle: ButtonStyle {
    let reduceMotion: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed && !reduceMotion ? 0.985 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Shared glass capsule: iOS 26 native glass; the established material fallback below.
struct UnyriDrawerGlass: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.background(.white.opacity(0.07), in: Capsule()).glassEffect(.regular, in: Capsule())
        } else {
            content.background(.ultraThinMaterial, in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.12), lineWidth: 0.5))
        }
    }
}

/// StateObject owns this per-view snapshot: reconstructing the status control
/// must not read and decode preferences again before a change notification.
@MainActor
private final class UnyriSidebarNotificationCache: ObservableObject {
    @Published var value: UnyriNotificationPreferences
    init() { value = UnyriNotificationPreferencesStorage.load() }
}

/// Online / Invisible / Do not disturb in one tap. Invisible hides the online status
/// (privacy "online: nobody"); Do not disturb turns off message alerts, which the
/// server also respects for pushes. Observes privacy only inside this small view.
struct UnyriSidebarStatusControl: View {
    var compact = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @EnvironmentObject private var privacy: UserPrivacyRepository
    @Environment(\.unyriSidebarStyle) private var style
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @StateObject private var notificationCache = UnyriSidebarNotificationCache()
    private var notifications: UnyriNotificationPreferences {
        get { notificationCache.value }
        nonmutating set { notificationCache.value = newValue }
    }
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1
    enum Status: String, CaseIterable { case online, invisible, busy }
    private var status: Status {
        if !notifications.messages { return .busy }
        if privacy.value?.onlineVisibility == .nobody { return .invisible }
        return .online
    }
    var body: some View {
        Menu {
            ForEach(Status.allCases, id: \.self) { option in
                Button { choose(option) } label: {
                    Label(title(option), systemImage: option == status ? "checkmark" : symbol(option))
                }
            }
        } label: {
            HStack(spacing: 6) {
                Circle().fill(dot(status)).frame(width: 8, height: 8)
                Text(title(status)).font(.system(size: 13 * textScale, weight: .medium))
                    .lineLimit(compact ? nil : 1).minimumScaleFactor(compact ? 1 : 0.5)
                    .fixedSize(horizontal: false, vertical: true)
                if compact {
                    Image(systemName: "chevron.down").font(.system(size: 10))
                        .frame(width: 14, height: 14)
                        .foregroundStyle(UnyriColors.secondaryText).accessibilityHidden(true)
                }
            }
            .foregroundStyle(colorStyle.material(hex: style.typographyText))
            .padding(.horizontal, compact ? 12 : 10)
            .frame(maxWidth: compact && !dynamicTypeSize.isAccessibilitySize ? nil : .infinity, minHeight: compact ? 32 : 44)
            .modifier(UnyriDrawerGlass())
            .frame(minHeight: 44)
            .contentShape(Capsule())
        }
        .disabled(privacy.busy)
        .accessibilityIdentifier("drawer-status")
        .task { if privacy.value == nil { await privacy.refresh() } }
        .onReceive(NotificationCenter.default.publisher(for: UnyriNotificationPreferencesStorage.changed)) { _ in
            notifications = UnyriNotificationPreferencesStorage.load()
        }
    }
    private func title(_ value: Status) -> String {
        switch value {
        case .online: return UnyriL10n.string("Online")
        case .invisible: return UnyriL10n.string("Invisible")
        case .busy: return UnyriL10n.string("Do not disturb")
        }
    }
    private func symbol(_ value: Status) -> String {
        switch value { case .online: return "circle.fill"; case .invisible: return "eye.slash"; case .busy: return "moon.fill" }
    }
    private func dot(_ value: Status) -> Color {
        switch value {
        case .online: return UnyriColors.online
        case .invisible: return Color.gray
        case .busy: return Color(red: 0.86, green: 0.36, blue: 0.36)
        }
    }
    private func choose(_ value: Status) {
        UnyriHaptics.selection()
        var prefs = UnyriNotificationPreferencesStorage.load()
        let wantsAlerts = value != .busy
        if prefs.messages != wantsAlerts { prefs.messages = wantsAlerts; UnyriNotificationPreferencesStorage.save(prefs) }
        notifications = prefs
        guard let current = privacy.value else { return }
        let visibility: PrivacyVisibility = value == .invisible ? .nobody : .everyone
        guard current.onlineVisibility != visibility else { return }
        var next = current; next.onlineVisibility = visibility
        Task { await privacy.save(next) }
    }
}
