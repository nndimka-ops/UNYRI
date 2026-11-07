import SwiftUI
import UIKit

/// The live panel and its preview share the same renderer. Form changes stay
/// in this draft until Apply succeeds; leaving the screen performs no write.
struct UnyriSidebarAppearanceSettingsView: View {
    @EnvironmentObject private var store: UnyriSidebarAppearanceStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @AppStorage("showCallsTab") private var showCallsTab = true
    @AppStorage("showSavedMessages") private var showSavedMessages = true
    @State private var draft = UnyriSidebarAppearanceSettings.defaults
    @State private var previewStyle = UnyriSidebarAppearanceSettings.defaults.resolved
    @State private var loaded = false
    @State private var expanded: Set<String> = ["layout"]
    @State private var compactPage = 1
    @State private var saveFailed = false
    private let localizationBundle: Bundle

    init() {
        // Resolve the selected-language bundle once, not from every form row
        // while a slider is being dragged.
        localizationBundle = UnyriL10n.bundle
    }
    private func localized(_ key: String) -> String {
        localizationBundle.localizedString(forKey: key, value: key, table: nil)
    }

    var body: some View {
        GeometryReader { geometry in
            Group {
                if geometry.size.width >= 650 && geometry.size.height < 600 {
                    HStack(alignment: .top, spacing: 12) {
                        preview.frame(width: min(390, geometry.size.width * 0.46))
                        controls
                    }
                } else if geometry.size.height >= 650 && !dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 10) {
                        preview.frame(height: min(400, max(330, geometry.size.height * 0.48)))
                        controls
                    }
                } else {
                    VStack(spacing: 10) {
                        Picker(localized("Appearance section"), selection: $compactPage) {
                            Text(localized("Live preview")).tag(0)
                            Text(localized("Settings")).tag(1)
                        }.pickerStyle(.segmented)
                        if compactPage == 0 { preview }
                        else { controls }
                    }
                }
            }
            .padding(.horizontal, 12).padding(.top, 8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { footer }
        .unyriScreenBackground()
        .navigationTitle(localized("Sidebar appearance"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sidebar-appearance-editor")
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let initial = UnyriSidebarVisibility.mergingLegacy(store.settings, showCalls: showCallsTab, showSaved: showSavedMessages)
            draft = initial
            previewStyle = initial.resolved
        }
        .onChange(of: draft) { value in
            if value.hasValidColors { previewStyle = value.resolved }
        }
        .alert(localized("Could not save appearance"), isPresented: $saveFailed) {
            Button(localized("OK"), role: .cancel) {}
        } message: { Text(localized("Your previous sidebar settings are unchanged. Please try again.")) }
    }

    private var preview: some View {
        VStack(spacing: 8) {
            HStack {
                Text(localized("Live preview")).font(.headline)
                Spacer(minLength: 4)
                Text(localized("Example only")).font(.caption).foregroundStyle(.secondary)
            }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Color(hex: previewStyle.behaviorChatBackground)
                    VStack(spacing: 18) {
                        ForEach(0..<4) { _ in
                            HStack(spacing: 10) {
                                Circle().fill(Color.white.opacity(0.08)).frame(width: 35, height: 35)
                                RoundedRectangle(cornerRadius: 5).fill(Color.white.opacity(0.06)).frame(height: 12)
                            }
                        }
                        Spacer(minLength: 0)
                    }.padding(18).allowsHitTesting(false).accessibilityHidden(true)
                    Color.black.opacity(previewStyle.behaviorScrim / 100).allowsHitTesting(false)
                    UnyriSavedSidebarView(screenWidth: geometry.size.width, availableHeight: geometry.size.height,
                        displayName: "UNYRI", username: "unyri", avatarURL: nil, selectedID: "settings", unreadCount: 3,
                        showCalls: true, showSaved: true, previewOnline: false,
                        onClose: {}, onEdit: {}, onSearch: {}, onPeople: {}, onLogout: {}, onItem: { _ in })
                }
                .environment(\.unyriSidebarStyle, previewStyle)
                // Editing stored colors must stay visible while Mono is active.
                .environment(\.unyriAppColorStyle, .colorful)
                .clipShape(RoundedRectangle(cornerRadius: 18))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sidebar-appearance-preview")
    }

    private var controls: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                Text(localized("Sidebar changes are saved on this device only. Apply when you like the preview."))
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                if appColorStyle.isMonochrome {
                    Text(localized("This preview shows saved colors. The monochrome theme keeps the real sidebar neutral."))
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
                }
                layoutControls
                materialControls
                textControls
                profileControls
                iconControls
                rowControls
                navigationControls
                itemControls
                patternControls
                behaviorControls
                Button {
                    UnyriHaptics.selection()
                    draft = .defaults
                } label: {
                    Label(localized("Restore standard"), systemImage: "arrow.counterclockwise")
                        .font(.body).frame(maxWidth: .infinity, minHeight: 48)
                }.buttonStyle(UnyriQuietButtonStyle()).accessibilityIdentifier("sidebar-appearance-reset")
                Text(localized("Restore changes the preview. Apply saves it; Cancel keeps your current sidebar."))
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText).fixedSize(horizontal: false, vertical: true)
            }.padding(.bottom, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sidebar-appearance-controls")
        .unyriDecorativeTint(Color(hex: 0x858585))
    }

    private var layoutControls: some View {
        group("Layout", id: "layout", icon: "rectangle.leadinghalf.inset.filled") {
            slider("Panel width", key: \.geometryWidth, range: 34...80, suffix: "%", id: "width")
            slider("Corner rounding", key: \.geometryCorner, range: 0...40)
            slider("Content inset", key: \.geometryInset, range: 6...24)
            slider("Main row height", key: \.geometryRowHeight, range: 44...66)
            slider("Service row height", key: \.geometrySecondaryHeight, range: 44...66)
            slider("Row spacing", key: \.geometryRowGap, range: 0...10)
            slider("Horizontal row padding", key: \.geometryRowPaddingX, range: 6...22)
            slider("Vertical row padding", key: \.geometryRowPaddingY, range: 4...12)
            slider("Icon and text spacing", key: \.geometryIconGap, range: 6...20)
        }
    }

    private var materialControls: some View {
        group("Panel material", id: "material", icon: "square.stack.3d.up") {
            color("Background color", key: \.materialBackground)
            toggle("Gradient", key: \.materialGradient)
            if draft.materialGradient {
                color("Top gradient color", key: \.materialTop)
                color("Bottom gradient color", key: \.materialBottom)
                slider("Gradient angle", key: \.materialAngle, range: 0...360, suffix: "°")
            }
            slider("Background opacity", key: \.materialOpacity, range: 40...100, suffix: "%")
            color("Outline color", key: \.materialBorderColor)
            slider("Outline width", key: \.materialBorderWidth, range: 0...2, step: 0.25)
            slider("Outline opacity", key: \.materialBorderOpacity, range: 0...100, suffix: "%")
            slider("Shadow softness", key: \.materialShadow, range: 0...12)
        }
    }

    private var textControls: some View {
        group("Text", id: "text", icon: "textformat") {
            choice("Typeface", key: \.typographyFamily, options: [("system", "System font"), ("serif", "Serif font"), ("mono", "Monospaced font")])
            choice("Text weight", key: \.typographyWeight, options: [("400", "Regular"), ("500", "Medium"), ("600", "Semibold")])
            slider("Main text size", key: \.typographySize, range: 11...18)
            slider("Service text size", key: \.typographySecondarySize, range: 10...16)
            slider("Letter spacing", key: \.typographyLetterSpacing, range: 0...1, step: 0.1)
            color("Text color", key: \.typographyText)
            color("Secondary text color", key: \.typographyMuted)
        }
    }

    private var profileControls: some View {
        group("Profile", id: "profile", icon: "person.crop.circle") {
            choice("Profile layout", key: \.profileLayout, options: [("compact", "Compact"), ("center", "Centered")])
            toggle("Show avatar", key: \.profileShowAvatar)
            slider("Avatar size limit", key: \.avatarSize, range: 26...68)
            Text(localized("The avatar automatically fits the panel width."))
                .font(.caption).foregroundStyle(UnyriColors.secondaryText)
            toggle("Show username", key: \.profileShowUsername)
            toggle("Show profile shortcut", key: \.profileShowEdit)
            toggle("Show connection dot", key: \.avatarOnline)
            Text(localized("The green dot appears only when the real server connection is active."))
                .font(.caption).foregroundStyle(UnyriColors.secondaryText)
        }
    }

    private var iconControls: some View {
        group("Icons", id: "icons", icon: "square.grid.2x2") {
            slider("Icon size", key: \.iconsSize, range: 14...26)
            toggle("Colorful icons", key: \.iconsColored)
            if !draft.iconsColored { color("Icon color", key: \.iconsColor) }
            toggle("Icon backgrounds", key: \.iconsTiles)
            if draft.iconsTiles {
                slider("Icon background padding", key: \.iconsTileSize, range: 0...8)
                slider("Icon background rounding", key: \.iconsTileRadius, range: 0...16)
            }
            toggle("Show row arrows", key: \.iconsChevrons)
        }
    }

    private var rowControls: some View {
        group("Rows and selection", id: "rows", icon: "list.bullet.rectangle") {
            slider("Row rounding", key: \.rowsRadius, range: 0...32)
            color("Row background", key: \.rowsFill)
            slider("Row opacity", key: \.rowsFillOpacity, range: 0...100, suffix: "%")
            toggle("Group main and service items", key: \.rowsGrouped)
            color("Selected row color", key: \.selectedFill)
            slider("Selected row opacity", key: \.selectedOpacity, range: 0...100, suffix: "%")
            color("Unread badge color", key: \.selectedBadge)
            toggle("Show dividers", key: \.dividerVisible)
        }
    }

    private var navigationControls: some View {
        group("Shortcuts and footer", id: "navigation", icon: "arrow.turn.down.right") {
            toggle("Show search and people shortcuts", key: \.quickVisible)
            choice("Shortcut layout", key: \.quickLayout, options: [("row", "Side by side"), ("stack", "Stacked")])
            toggle("Show sign-out button", key: \.footerShowLogout)
            toggle("Show version and build", key: \.footerShowVersion)
        }
    }

    private var itemControls: some View {
        group("Menu items", id: "items", icon: "line.3.horizontal.decrease") {
            Text(localized("Use the arrows to reorder. Chats and Settings always stay available."))
                .font(.caption).foregroundStyle(UnyriColors.secondaryText)
            ForEach(orderedItems) { item in
                itemEditor(item)
                if item.id != orderedItems.last?.id { Divider().overlay(Color.white.opacity(0.08)) }
            }
        }
    }

    private var orderedItems: [UnyriSavedSidebarStyle.Item] {
        let order = draft.sanitized().itemOrder
        return order.compactMap { id in UnyriSavedSidebarStyle.items.first { $0.id == id } }
    }
    private func itemEditor(_ item: UnyriSavedSidebarStyle.Item) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(get: { !draft.hiddenItems.contains(item.id) }, set: { visible in
                if visible { draft.hiddenItems.remove(item.id) } else { draft.hiddenItems.insert(item.id) }
                UnyriHaptics.selection()
            })) {
                Text(itemTitle(item.id)).font(.subheadline)
            }
            .disabled(item.id == "chats" || item.id == "settings")
            .accessibilityIdentifier("sidebar-item-visible-" + item.id)
            HStack(spacing: 8) {
                Text(localized("Position")).font(.caption).foregroundStyle(.secondary)
                Spacer(minLength: 0)
                moveButton(item, direction: -1)
                moveButton(item, direction: 1)
            }
            if draft.iconsColored {
                UnyriSidebarColorControl(title: "Icon color", hex: Binding(get: {
                    draft.itemColors[item.id] ?? String(format: "#%06X", item.color)
                }, set: { draft.itemColors[item.id] = $0 }), localizationBundle: localizationBundle)
                .accessibilityIdentifier("sidebar-item-color-" + item.id)
            }
        }.padding(.vertical, 5)
    }
    private func moveButton(_ item: UnyriSavedSidebarStyle.Item, direction: Int) -> some View {
        Button {
            guard let target = neighbor(item, direction: direction),
                  let sourceIndex = draft.itemOrder.firstIndex(of: item.id),
                  let targetIndex = draft.itemOrder.firstIndex(of: target) else { return }
            draft.itemOrder.swapAt(sourceIndex, targetIndex)
            UnyriHaptics.selection()
        } label: {
            Image(systemName: direction < 0 ? "arrow.up" : "arrow.down")
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(UnyriQuietButtonStyle()).disabled(neighbor(item, direction: direction) == nil)
            .accessibilityLabel(localized(direction < 0 ? "Move up" : "Move down"))
            .accessibilityIdentifier("sidebar-item-" + item.id + (direction < 0 ? "-up" : "-down"))
    }
    private func neighbor(_ item: UnyriSavedSidebarStyle.Item, direction: Int) -> String? {
        let peers = orderedItems.filter { !draft.rowsGrouped || $0.section == item.section }
        guard let index = peers.firstIndex(where: { $0.id == item.id }), peers.indices.contains(index + direction) else { return nil }
        return peers[index + direction].id
    }

    private var patternControls: some View {
        group("Background pattern", id: "pattern", icon: "circle.grid.3x3") {
            choice("Pattern", key: \.patternKind, options: [("none", "None"), ("dots", "Dots"), ("lines", "Lines"), ("diamonds", "Diamonds")])
            if draft.patternKind != "none" {
                color("Pattern color", key: \.patternColor)
                slider("Pattern opacity", key: \.patternOpacity, range: 0...12, suffix: "%")
                slider("Pattern size", key: \.patternSize, range: 8...40)
                slider("Pattern spacing", key: \.patternSpacing, range: 24...120)
            }
        }
    }

    private var behaviorControls: some View {
        group("Opening and gestures", id: "behavior", icon: "hand.draw") {
            slider("Animation duration", key: \.behaviorDuration, range: 100...280, step: 10, suffix: " ms")
            slider("Screen dimming", key: \.behaviorScrim, range: 0...70, suffix: "%")
            toggle("Open with edge swipe", key: \.behaviorSwipeOpen)
            toggle("Close with swipe", key: \.behaviorSwipeClose)
            Text(localized("The menu and close buttons remain available. System Reduce Motion is respected."))
                .font(.caption).foregroundStyle(UnyriColors.secondaryText)
        }
    }

    private var footer: some View {
        VStack(spacing: 6) {
            if !draft.hasValidColors {
                Text(localized("Complete each HEX color before applying."))
                    .font(.caption).foregroundStyle(UnyriColors.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 6) { cancelButton; applyButton }
                } else {
                    HStack(spacing: 12) { cancelButton; applyButton }
                }
            }
        }.padding(.horizontal, 16).padding(.vertical, 10).background(UnyriColors.background.opacity(0.98))
    }
    private var cancelButton: some View {
        Button { dismiss() } label: {
            Text(localized("Cancel")).font(.body)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
            .buttonStyle(UnyriQuietButtonStyle()).accessibilityIdentifier("sidebar-appearance-cancel")
    }
    private var applyButton: some View {
        Button {
            guard draft.hasValidColors else { return }
            guard store.apply(draft) else { saveFailed = true; return }
            showCallsTab = !store.settings.hiddenItems.contains("calls")
            showSavedMessages = !store.settings.hiddenItems.contains("saved")
            UnyriHaptics.selection(); dismiss()
        } label: {
            Text(localized("Apply")).font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
        }.buttonStyle(.borderedProminent).tint(Color(hex: 0xDEDEDE)).foregroundStyle(Color(hex: 0x222222))
            .disabled(!draft.hasValidColors)
            .accessibilityIdentifier("sidebar-appearance-apply")
    }

    private func group<Content: View>(_ title: String, id: String, icon: String, @ViewBuilder content: @escaping () -> Content) -> some View {
        DisclosureGroup(isExpanded: Binding(get: { expanded.contains(id) }, set: { visible in
            if visible { expanded.insert(id) } else { expanded.remove(id) }
        })) {
            VStack(alignment: .leading, spacing: 14, content: content).padding(.top, 14)
        } label: {
            Label(localized(title), systemImage: icon).font(.subheadline.weight(.semibold))
                .foregroundStyle(UnyriColors.primaryText).frame(minHeight: 34)
        }
        .padding(14).background(UnyriColors.elevatedBackground, in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sidebar-appearance-group-" + id)
    }
    private func slider(_ title: String, key: WritableKeyPath<UnyriSidebarAppearanceSettings, Double>, range: ClosedRange<Double>, step: Double = 1, suffix: String = "", id: String? = nil) -> some View {
        let displayedValue = (step >= 1 ? String(Int(draft[keyPath: key])) : String(format: "%.2f", draft[keyPath: key])) + suffix
        return VStack(spacing: 4) {
            HStack {
                Text(localized(title)).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(displayedValue)
                    .font(.caption.monospacedDigit()).foregroundStyle(UnyriColors.secondaryText)
            }
            Slider(value: binding(key), in: range, step: step, onEditingChanged: { editing in if !editing { UnyriHaptics.selection() } })
                .accessibilityLabel(localized(title))
                .accessibilityValue(displayedValue)
                .accessibilityIdentifier("sidebar-appearance-" + (id ?? String(describing: key)))
        }
    }
    private func toggle(_ title: String, key: WritableKeyPath<UnyriSidebarAppearanceSettings, Bool>) -> some View {
        Toggle(localized(title), isOn: binding(key))
            .font(.subheadline).onChange(of: draft[keyPath: key]) { _ in UnyriHaptics.selection() }
    }
    private func choice(_ title: String, key: WritableKeyPath<UnyriSidebarAppearanceSettings, String>, options: [(String, String)]) -> some View {
        Picker(localized(title), selection: binding(key)) {
            ForEach(options, id: \.0) { option in Text(localized(option.1)).tag(option.0) }
        }.font(.subheadline).pickerStyle(.menu)
    }
    private func color(_ title: String, key: WritableKeyPath<UnyriSidebarAppearanceSettings, String>) -> some View {
        UnyriSidebarColorControl(title: title, hex: binding(key), localizationBundle: localizationBundle)
    }
    private func binding<Value>(_ key: WritableKeyPath<UnyriSidebarAppearanceSettings, Value>) -> Binding<Value> {
        Binding(get: { draft[keyPath: key] }, set: { draft[keyPath: key] = $0 })
    }
    private func itemTitle(_ id: String) -> String {
        switch id {
        case "chats": return localized("Chats")
        case "calls": return localized("Calls")
        case "saved": return localized("Saved Messages")
        case "settings": return localized("Settings")
        case "server": return localized("Server")
        case "devices": return localized("Devices")
        case "help": return localized("Help")
        default: return localized("Report a problem")
        }
    }
}

/// The two older navigation preferences also control the hidden TabView.
/// Merge existing restrictions into the draft; only a successful Apply may
/// change those preferences. Chats/Settings are unaffected by this bridge.
enum UnyriSidebarVisibility {
    static func mergingLegacy(_ settings: UnyriSidebarAppearanceSettings, showCalls: Bool, showSaved: Bool) -> UnyriSidebarAppearanceSettings {
        var result = settings
        if !showCalls { result.hiddenItems.insert("calls") }
        if !showSaved { result.hiddenItems.insert("saved") }
        return result
    }
    static func setting(_ id: String, visible: Bool, in settings: UnyriSidebarAppearanceSettings) -> UnyriSidebarAppearanceSettings {
        guard id == "calls" || id == "saved" else { return settings }
        var result = settings
        if visible { result.hiddenItems.remove(id) } else { result.hiddenItems.insert(id) }
        return result.sanitized()
    }
}

private struct UnyriSidebarColorControl: View {
    let title: String
    @Binding var hex: String
    let localizationBundle: Bundle
    @State private var input = ""
    @State private var lastValidHex = "#000000"
    private func localized(_ key: String) -> String { localizationBundle.localizedString(forKey: key, value: key, table: nil) }
    private var normalizedInput: String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let value = trimmed.hasPrefix("#") ? String(trimmed.dropFirst()) : trimmed
        guard value.count == 6, value.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "0123456789abcdefABCDEF").contains($0) }) else { return nil }
        return "#" + value.uppercased()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ColorPicker(localized(title), selection: Binding(get: { color }, set: { selected in
                var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
                guard UIColor(selected).getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return }
                hex = String(format: "#%02X%02X%02X", Int((min(1, max(0, red)) * 255).rounded()), Int((min(1, max(0, green)) * 255).rounded()), Int((min(1, max(0, blue)) * 255).rounded()))
            }), supportsOpacity: false).font(.subheadline)
            HStack {
                Text("HEX").font(.caption).foregroundStyle(UnyriColors.secondaryText)
                TextField("#RRGGBB", text: $input)
                    .font(.system(.caption, design: .monospaced)).textInputAutocapitalization(.characters)
                    .autocorrectionDisabled().keyboardType(.asciiCapable)
                    .accessibilityLabel(localized(title) + " HEX")
                    .padding(9).background(Color.black.opacity(0.2), in: RoundedRectangle(cornerRadius: 8))
            }
            if !input.isEmpty && normalizedInput == nil {
                Text(localized("Enter six hexadecimal digits. The last valid color stays in the preview."))
                    .font(.caption).foregroundStyle(UnyriColors.warning).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .contain)
        .onAppear {
            input = hex
            if let valid = normalizedInput { lastValidHex = valid }
        }
        .onChange(of: input) { value in
            if let valid = normalizedInput {
                lastValidHex = valid
                if valid != hex { hex = valid }
            } else if value != hex { hex = value }
        }
        .onChange(of: hex) { value in if normalizedInput != value { input = value } }
    }
    private var color: Color {
        Color(hex: UInt32(lastValidHex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0)
    }
}
