import SwiftUI
import UIKit

/// The editor export is presentation data. Navigation and gesture ownership
/// remain in LoggedInView; opening this view creates no network requests.
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
    var previewOnline: Bool? = nil
    let onClose: () -> Void
    let onEdit: () -> Void
    let onSearch: () -> Void
    let onPeople: () -> Void
    let onLogout: () -> Void
    let onItem: (String) -> Void

    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1
    @Environment(\.unyriSidebarStyle) private var style
    private var metrics: UnyriSavedSidebarStyle.Metrics { style.metrics(screenWidth: screenWidth) }
    private var centeredProfile: Bool { metrics.profileLayout == "center" || dynamicTypeSize.isAccessibilitySize }

    private var items: [UnyriSavedSidebarStyle.Item] {
        var values = style.items.filter { $0.visible && ($0.id != "calls" || showCalls) && ($0.id != "saved" || showSaved) }
            .sorted { $0.order == $1.order ? $0.id < $1.id : $0.order < $1.order }
        // Add the new destination without rewriting the user's saved sidebar
        // theme, item ordering, hidden rows or account preferences.
        let position = values.firstIndex(where: { $0.id == "calls" }).map { $0 + 1 }
            ?? values.firstIndex(where: { $0.id == "chats" }).map { $0 + 1 } ?? 0
        values.insert(.init(id: "rooms", title: "Rooms", icon: UnyriSymbols.rooms, color: 0xAC9CDD,
            visible: true, order: position, section: "main"), at: position)
        let archivePosition = values.firstIndex(where: { $0.id == "chats" }).map { $0 + 1 } ?? 0
        values.insert(.init(id: "archive", title: "Archive", icon: "archivebox", color: 0x7BC9EC,
            visible: true, order: archivePosition, section: "main"), at: archivePosition)
        let transfersPosition = values.firstIndex(where: { $0.id == "archive" }).map { $0 + 1 } ?? 0
        values.insert(.init(id: "transfers", title: "Transfers", icon: "arrow.up.arrow.down",
            color: values.first(where: { $0.id == "chats" })?.color ?? 0x7BC9EC,
            visible: true, order: transfersPosition, section: "main"), at: transfersPosition)
        return values
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                profile
                if style.quickVisible { quickActions.padding(.bottom, style.quickBottom) }
                if style.rowsGrouped {
                    rows(section: "main")
                    if items.contains(where: { $0.section == "service" }) { divider }
                    rows(section: "service")
                } else {
                    VStack(spacing: style.geometryRowGap) { ForEach(items) { item in row(item) } }
                }
                Spacer(minLength: style.footerGap)
                footer
            }
            .padding(.top, style.geometryTop)
            .padding(.horizontal, metrics.inset)
            .padding(.bottom, style.geometryBottom)
            .frame(minHeight: max(0, availableHeight), alignment: .top)
        }
        .frame(width: metrics.panelWidth, height: availableHeight)
        .background {
            UnyriSavedSidebarMaterial(size: CGSize(width: metrics.panelWidth, height: availableHeight))
                .overlay { UnyriSavedSidebarPattern().allowsHitTesting(false) }
        }
        .clipShape(UnyriSavedSidebarShape(radius: style.geometryCorner))
        .overlay {
            UnyriSavedSidebarRightEdge(radius: style.geometryCorner)
                .stroke(colorStyle.material(hex: style.materialBorderColor).opacity(style.materialBorderOpacity / 100),
                        lineWidth: style.materialBorderWidth)
                .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.4), radius: style.materialShadow * 2, x: style.materialShadow / 2, y: 0)
    }

    @ViewBuilder private var profile: some View {
        VStack(alignment: centeredProfile ? .center : .leading, spacing: 0) {
            if centeredProfile {
                VStack(spacing: style.profileGap) {
                    if style.profileShowClose { closeButton.frame(maxWidth: .infinity, alignment: .trailing) }
                    Button(action: onEdit) {
                        VStack(spacing: style.profileGap) { if style.profileShowAvatar { avatar }; profileName }
                            .frame(maxWidth: .infinity)
                            .contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("drawer-profile")
                }
            } else {
                HStack(spacing: style.profileGap) {
                    Button(action: onEdit) {
                        HStack(spacing: style.profileGap) {
                            if style.profileShowAvatar { avatar }
                            profileName.frame(maxWidth: .infinity, alignment: .leading)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("drawer-profile")
                    if style.profileShowClose { closeButton }
                }.frame(minHeight: 44)
            }
            if style.profileShowEdit {
                Button(action: onEdit) {
                    HStack(spacing: 6) {
                        icon("UnyriSidebarEdit", size: 13)
                        Text("Profile").font(sidebarFont(style.editSize))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(colorStyle.decorative(Color(hex: style.editColor)))
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.leading, centeredProfile ? 0 : metrics.editIndent)
                .accessibilityIdentifier("drawer-edit-profile")
            }
        }
        .padding(metrics.profilePadding)
        .background(colorStyle.material(hex: style.profileFill).opacity(style.profileFillOpacity / 100),
                    in: RoundedRectangle(cornerRadius: style.rowsRadius))
        .padding(.bottom, style.profileBottom + 8)
    }

    private var profileName: some View {
        VStack(alignment: centeredProfile ? .center : .leading, spacing: 2) {
            Text(displayName).font(sidebarFont(style.profileNameSize, weight: .semibold))
                .lineLimit(2).truncationMode(.tail)
                .foregroundStyle(colorStyle.material(hex: style.typographyText))
                .fixedSize(horizontal: false, vertical: true)
            if style.profileShowUsername, let username {
                Text("@" + username).font(sidebarFont(style.profileUserSize))
                    .foregroundStyle(colorStyle.material(hex: style.typographyMuted)).lineLimit(1)
                    .truncationMode(.tail)
            }
        }
    }

    private var avatar: some View {
        Group {
            if avatarURL != nil {
                UserAvatar(url: avatarURL, name: displayName, size: metrics.avatarSize)
            } else {
                RoundedRectangle(cornerRadius: metrics.avatarSize * style.avatarRadius / 100)
                    .fill(colorStyle.material(hex: style.avatarColor))
                    .overlay {
                        Text(String(displayName.prefix(1)).uppercased())
                            .font(.system(size: metrics.avatarSize * 0.4, weight: .semibold))
                            .foregroundStyle(colorStyle.material(hex: style.avatarInk))
                    }
                    .frame(width: metrics.avatarSize, height: metrics.avatarSize)
            }
        }
        .overlay {
            if style.avatarBorderWidth > 0 {
                Circle().stroke(colorStyle.material(hex: style.materialBorderColor), lineWidth: style.avatarBorderWidth)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            // The export enables this affordance, not the actual online state.
            if style.avatarOnline {
                if let previewOnline {
                    if previewOnline { UnyriSavedSidebarPresenceMark(size: max(6, metrics.avatarSize * 0.18)) }
                } else { UnyriSavedSidebarPresence(size: max(6, metrics.avatarSize * 0.18)) }
            }
        }
    }

    private var closeButton: some View {
        Button(action: onClose) {
            icon("UnyriSidebarClose", size: style.iconsSize)
                .foregroundStyle(colorStyle.material(hex: style.typographyMuted))
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel("Close menu").accessibilityIdentifier("drawer-close")
    }

    @ViewBuilder private var quickActions: some View {
        if metrics.quickSideBySide && !dynamicTypeSize.isAccessibilitySize {
            HStack(spacing: style.quickGap) {
                quickAction("Search", image: "UnyriSidebarSearch", identifier: "drawer-search", action: onSearch)
                quickAction("People", image: "UnyriSidebarPeople", identifier: "drawer-people", action: onPeople)
            }
        } else {
            VStack(spacing: style.quickGap) {
                quickAction("Search", image: "UnyriSidebarSearch", identifier: "drawer-search", action: onSearch)
                quickAction("People", image: "UnyriSidebarPeople", identifier: "drawer-people", action: onPeople)
            }
        }
    }

    private func quickAction(_ title: LocalizedStringKey, image: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                icon(image, size: style.quickIconSize)
                Text(title).font(sidebarFont(style.quickSize))
                    .lineLimit(1).minimumScaleFactor(0.85)
            }
            .foregroundStyle(colorStyle.decorative(Color(hex: style.quickInk)))
            .padding(.horizontal, 4).padding(.vertical, 7)
            .frame(maxWidth: .infinity, minHeight: max(44, style.quickHeight))
            .background(colorStyle.material(hex: style.quickFill), in: RoundedRectangle(cornerRadius: style.quickRadius))
            .overlay(RoundedRectangle(cornerRadius: style.quickRadius)
                .stroke(colorStyle.material(hex: style.quickBorder), lineWidth: style.quickBorderWidth))
            .contentShape(RoundedRectangle(cornerRadius: style.quickRadius))
        }.buttonStyle(.plain).accessibilityIdentifier(identifier)
    }

    private func rows(section: String) -> some View {
        VStack(spacing: style.geometryRowGap) {
            ForEach(items.filter { $0.section == section }) { item in row(item) }
        }
    }

    private func row(_ item: UnyriSavedSidebarStyle.Item) -> some View {
        let selected = selectedID == item.id
        let secondary = item.section != "main"
        let tint = colorStyle.decorative(Color(hex: style.iconsColored ? item.color : style.iconsColor))
        return Button { onItem(item.id) } label: {
            HStack(spacing: metrics.iconGap) {
                rowIcon(item.id)
                    .foregroundStyle(tint)
                    .padding(style.iconsTiles ? metrics.tilePadding : 0)
                    .background(style.iconsTiles ? tint.opacity(style.iconsTileOpacity / 100) : .clear,
                                in: RoundedRectangle(cornerRadius: style.iconsTileRadius))
                Text(title(item.id)).font(sidebarFont(secondary ? style.typographySecondarySize : style.typographySize))
                    .tracking(style.typographyLetterSpacing)
                    .foregroundStyle(colorStyle.material(hex: selected ? style.selectedInk : secondary ? style.typographyMuted : style.typographyText))
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if item.id == "chats", unreadCount > 0 { unreadBadge }
                if style.iconsChevrons {
                    icon("UnyriSidebarChevron", size: 12).foregroundStyle(colorStyle.material(hex: style.typographyMuted))
                }
            }
            .padding(.horizontal, metrics.rowPaddingX).padding(.vertical, style.geometryRowPaddingY)
            .frame(maxWidth: .infinity, minHeight: max(44, secondary ? style.geometrySecondaryHeight : style.geometryRowHeight), alignment: .leading)
            .background(colorStyle.material(hex: selected ? style.selectedFill : style.rowsFill)
                .opacity((selected ? style.selectedOpacity : style.rowsFillOpacity) / 100),
                in: RoundedRectangle(cornerRadius: style.rowsRadius))
            .overlay(alignment: .leading) {
                if selected && style.selectedMarker {
                    Capsule().fill(colorStyle.decorative(Color(hex: style.selectedMarkerColor)))
                        .frame(width: style.selectedMarkerWidth, height: style.selectedMarkerHeight)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: style.rowsRadius))
        }
        .buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("drawer-" + item.id)
    }

    private var unreadBadge: some View {
        Text(unreadCount > 99 ? "99+" : String(unreadCount))
            .font(sidebarFont(style.selectedBadgeSize))
            .foregroundStyle(colorStyle.isMonochrome ? Color(hex: 0x222222) : Color(hex: style.selectedBadgeInk))
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(colorStyle.decorative(Color(hex: style.selectedBadge)), in: RoundedRectangle(cornerRadius: style.selectedBadgeRadius))
    }

    private var divider: some View {
        Rectangle().fill(colorStyle.material(hex: style.dividerColor).opacity(style.dividerOpacity / 100))
            .frame(height: style.dividerVisible ? style.dividerThickness : 0)
            .padding(.horizontal, style.dividerInset)
            .padding(.vertical, style.dividerVisible ? style.dividerGap : 0)
            .accessibilityHidden(true)
    }

    private var footer: some View {
        VStack(spacing: 0) {
            divider
            if style.footerShowLogout {
                Button(action: onLogout) {
                    HStack(spacing: metrics.iconGap) {
                        icon("UnyriSidebarLogout", size: style.iconsSize)
                        Text("Sign out of account").font(sidebarFont(style.footerSize))
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .foregroundStyle(Color(hex: style.footerColor))
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .background(Color(hex: style.footerFill).opacity(style.footerFillOpacity / 100),
                                in: RoundedRectangle(cornerRadius: style.footerRadius))
                    .contentShape(RoundedRectangle(cornerRadius: style.footerRadius))
                }.buttonStyle(.plain).accessibilityIdentifier("drawer-logout")
            }
            if style.footerShowVersion {
                Text("\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—") · \(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—")")
                    .font(sidebarFont(style.footerVersionSize))
                    .foregroundStyle(colorStyle.material(hex: style.typographyMuted))
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.horizontal, 12).padding(.top, 8)
                    .accessibilityIdentifier("drawer-version")
            }
        }
    }

    private func sidebarFont(_ size: CGFloat, weight: Font.Weight? = nil) -> Font {
        let design: Font.Design = style.typographyFamily == "serif" ? .serif : style.typographyFamily == "mono" ? .monospaced : .default
        let chosen: Font.Weight = weight ?? (style.typographyWeight == "600" ? .semibold : style.typographyWeight == "500" ? .medium : .regular)
        return .system(size: size * textScale, weight: chosen, design: design)
    }

    private func icon(_ name: String, size: CGFloat) -> some View {
        Image(name).renderingMode(.template).resizable().scaledToFit()
            .frame(width: size, height: size).accessibilityHidden(true)
    }
    @ViewBuilder private func rowIcon(_ id: String) -> some View {
        if id == "rooms" || id == "archive" || id == "transfers" {
            Image(systemName: id == "rooms" ? UnyriSymbols.rooms : id == "transfers" ? "arrow.up.arrow.down" : "archivebox").font(.system(size: style.iconsSize, weight: .regular))
                .frame(width: style.iconsSize, height: style.iconsSize).accessibilityHidden(true)
        } else { icon(asset(id), size: style.iconsSize) }
    }
    private func asset(_ id: String) -> String {
        switch id {
        case "chats": return "UnyriSidebarChats"
        case "calls": return "UnyriSidebarCalls"
        case "saved": return "UnyriSidebarSaved"
        case "settings": return "UnyriSidebarSettings"
        case "server": return "UnyriSidebarServer"
        case "devices": return "UnyriSidebarDevices"
        case "help": return "UnyriSidebarHelp"
        default: return "UnyriSidebarReport"
        }
    }
    private func title(_ id: String) -> LocalizedStringKey {
        switch id {
        case "chats": return "Chats"
        case "archive": return "Archive"
        case "transfers": return "Transfers"
        case "calls": return "Calls"
        case "rooms": return "Rooms"
        case "saved": return "Saved Messages"
        case "settings": return "Settings"
        case "server": return "Server"
        case "devices": return "Devices"
        case "help": return "Help"
        default: return "Report a problem"
        }
    }
}

/// Observe transport only while the drawer is mounted, in this tiny child.
/// Presence deltas must not invalidate the entire authenticated tab host.
private struct UnyriSavedSidebarPresence: View {
    let size: CGFloat
    @EnvironmentObject private var realtime: RealtimeClient
    var body: some View {
        if realtime.state == .connected { UnyriSavedSidebarPresenceMark(size: size) }
    }
}

private struct UnyriSavedSidebarPresenceMark: View {
    let size: CGFloat
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriSidebarStyle) private var style
    var body: some View {
        Circle().fill(Color(hex: style.avatarOnlineColor))
            .frame(width: size, height: size)
            .overlay(Circle().stroke(colorStyle.material(hex: style.materialBackground), lineWidth: 1.5))
            .accessibilityLabel("Online")
    }
}

private struct UnyriSavedSidebarMaterial: View {
    let size: CGSize
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriSidebarStyle) private var style
    var body: some View {
        let radians = style.materialAngle * .pi / 180
        let dx = sin(radians), dy = -cos(radians)
        let width = max(1, size.width), height = max(1, size.height)
        let length = abs(width * dx) + abs(height * dy)
        let start = UnitPoint(x: 0.5 - dx * length / (2 * width), y: 0.5 - dy * length / (2 * height))
        let end = UnitPoint(x: 1 - start.x, y: 1 - start.y)
        if style.materialGradient {
            LinearGradient(stops: [
                .init(color: colorStyle.material(hex: style.materialTop).opacity(style.materialOpacity / 100), location: 0),
                .init(color: colorStyle.material(hex: style.materialBackground).opacity(style.materialOpacity / 100), location: 0.55),
                .init(color: colorStyle.material(hex: style.materialBottom).opacity(style.materialOpacity / 100), location: 1)
            ], startPoint: start, endPoint: end)
        } else {
            colorStyle.material(hex: style.materialBackground).opacity(style.materialOpacity / 100)
        }
    }
}

/// A bounded, static canvas. Appearance changes redraw it; no clock or
/// background worker runs while a menu is sitting on screen.
private struct UnyriSavedSidebarPattern: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @Environment(\.unyriSidebarStyle) private var style
    var body: some View {
        if style.patternKind != "none" && style.patternOpacity > 0 {
            Canvas { context, size in
                let spacing = max(24, style.patternSpacing)
                let side = min(style.patternSize, spacing * 0.8)
                let color = colorStyle.decorative(Color(hex: style.patternColor)).opacity(style.patternOpacity / 100)
                for y in stride(from: CGFloat(0), through: min(size.height, 2_000), by: spacing) {
                    for x in stride(from: CGFloat(0), through: min(size.width, 1_000), by: spacing) {
                        var path = Path()
                        switch style.patternKind {
                        case "dots":
                            let diameter = max(1, style.patternSize / 5)
                            path.addEllipse(in: CGRect(x: x - diameter / 2, y: y - diameter / 2, width: diameter, height: diameter))
                        case "lines":
                            path.move(to: CGPoint(x: x - side / 2, y: y)); path.addLine(to: CGPoint(x: x + side / 2, y: y))
                        default:
                            path.move(to: CGPoint(x: x, y: y - side / 2))
                            path.addLine(to: CGPoint(x: x + side / 2, y: y))
                            path.addLine(to: CGPoint(x: x, y: y + side / 2))
                            path.addLine(to: CGPoint(x: x - side / 2, y: y)); path.closeSubpath()
                        }
                        if style.patternKind == "dots" { context.fill(path, with: .color(color)) }
                        else { context.stroke(path, with: .color(color), lineWidth: style.patternStroke) }
                    }
                }
            }
            .rotationEffect(.degrees(style.patternRotation))
            .accessibilityHidden(true)
        }
    }
}

private struct UnyriSavedSidebarShape: Shape {
    let radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topRight, .bottomRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

private struct UnyriSavedSidebarRightEdge: Shape {
    let radius: CGFloat
    func path(in rect: CGRect) -> Path {
        let radius = min(radius, min(rect.width, rect.height) / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
        path.addArc(center: CGPoint(x: rect.maxX - radius, y: rect.minY + radius), radius: radius,
                    startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
        path.addArc(center: CGPoint(x: rect.maxX - radius, y: rect.maxY - radius), radius: radius,
                    startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        return path
    }
}
