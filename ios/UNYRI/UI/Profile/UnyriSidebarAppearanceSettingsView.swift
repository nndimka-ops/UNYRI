import SwiftUI
import UIKit

/// Layout selection is immediate. The remaining appearance controls keep their draft semantics.
struct UnyriSidebarAppearanceSettingsView: View {
    @EnvironmentObject private var store: UnyriSidebarAppearanceStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("showCallsTab") private var showCallsTab = true
    @AppStorage("showSavedMessages") private var showSavedMessages = true
    @State private var draft = UnyriSidebarAppearanceSettings.defaults
    @State private var loaded = false
    @State private var saveFailed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                drawerPicker
                Text("Choose a sidebar layout. Its width adapts to larger text automatically.")
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                preview
                UnyriSettingsGroup("Opening and gestures") {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Animation duration")
                        Slider(value: $draft.behaviorDuration, in: 100...280, step: 10)
                            .accessibilityIdentifier("sidebar-appearance-duration").accessibilityLabel("Animation duration")
                            .accessibilityValue("\(Int(draft.behaviorDuration)) " + UnyriL10n.string("ms"))
                        Text("Screen dimming")
                        Slider(value: $draft.behaviorScrim, in: 0...70, step: 1)
                            .accessibilityIdentifier("sidebar-appearance-dimming").accessibilityLabel("Screen dimming")
                            .accessibilityValue("\(Int(draft.behaviorScrim))%")
                        Toggle("Open with edge swipe", isOn: $draft.behaviorSwipeOpen)
                        Toggle("Close with swipe", isOn: $draft.behaviorSwipeClose)
                    }.padding(14)
                }
                UnyriSettingsGroup("Navigation") {
                    VStack(spacing: 12) {
                        Toggle("Show Calls in menu", isOn: itemVisibility("calls"))
                        Toggle("Show Saved Messages in menu", isOn: itemVisibility("saved"))
                    }.padding(14)
                }
                Button("Restore default appearance") {
                    guard store.chooseDrawer(.a) else { saveFailed = true; return }
                    draft = .defaults
                }
                    .frame(minHeight: 44).accessibilityIdentifier("sidebar-appearance-reset")
            }.padding(16)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 12) {
                Button { dismiss() } label: { Text("Cancel").frame(maxWidth: .infinity, minHeight: 44) }
                    .accessibilityIdentifier("sidebar-appearance-cancel")
                Button {
                    draft.drawerChoice = store.settings.drawerChoice
                    guard store.apply(draft) else { saveFailed = true; return }
                    showCallsTab = !store.settings.hiddenItems.contains("calls")
                    showSavedMessages = !store.settings.hiddenItems.contains("saved")
                    UnyriHaptics.selection(); dismiss()
                } label: { Text("Apply").frame(maxWidth: .infinity, minHeight: 44) }
                    .accessibilityIdentifier("sidebar-appearance-apply")
            }.buttonStyle(.plain).padding(12).background(.ultraThinMaterial)
        }
        .unyriScreenBackground()
        .navigationTitle("Sidebar appearance").navigationBarTitleDisplayMode(.inline)
        .accessibilityElement(children: .contain).accessibilityIdentifier("sidebar-appearance-editor")
        .onAppear {
            guard !loaded else { return }; loaded = true
            draft = UnyriSidebarVisibility.mergingLegacy(store.settings, showCalls: showCallsTab, showSaved: showSavedMessages)
        }
        .alert("Could not save appearance", isPresented: $saveFailed) { Button("OK", role: .cancel) {} }
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Live preview").font(.headline)
            UnyriDrawerView(choice: DrawerChoice(storedValue: store.settings.drawerChoice), screenWidth: UIScreen.main.bounds.width, availableHeight: 560,
                displayName: "UNYRI", username: "unyri", avatarURL: nil, selectedID: "chats", unreadCount: 3,
                showCalls: !draft.hiddenItems.contains("calls"), showSaved: !draft.hiddenItems.contains("saved"),
                onEdit: {}, onItem: { _ in },
                serverHost: "home.unyri.ru")
                .environment(\.unyriSidebarStyle, draft.resolved)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("sidebar-appearance-preview")
        }
    }

    private var drawerPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Sidebar layout").font(.headline)
            Text("The layout is saved on this device immediately.")
                .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 12) { choiceButton(.a); choiceButton(.c) }
                VStack(alignment: .leading, spacing: 12) { choiceButton(.a); choiceButton(.c) }
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier("sidebar-appearance-choice")
    }

    private func choiceButton(_ choice: DrawerChoice) -> some View {
        let selected = store.settings.drawerChoice == choice.rawValue
        let title: LocalizedStringKey = choice == .a ? "Column (A)" : "Card (C)"
        return Button {
            guard store.chooseDrawer(choice) else { saveFailed = true; return }
            draft.drawerChoice = choice.rawValue
            UnyriHaptics.selection()
        } label: {
            VStack(spacing: 8) {
                // Render the real, inert layout at thumbnail size, without accounts/status IO.
                UnyriDrawerView(choice: choice, screenWidth: 390, availableHeight: 1000,
                    displayName: "UNYRI", username: "unyri", avatarURL: nil, selectedID: "chats", unreadCount: 3,
                    showCalls: !draft.hiddenItems.contains("calls"), showSaved: !draft.hiddenItems.contains("saved"),
                    onEdit: {}, onItem: { _ in }, serverHost: "home.unyri.ru")
                    .environment(\.unyriSidebarStyle, draft.resolved).dynamicTypeSize(.medium)
                    .scaleEffect(0.24, anchor: .topLeading).frame(width: 80, height: 240, alignment: .topLeading)
                    .allowsHitTesting(false).accessibilityHidden(true)
                Text(title)
                    .font(.body.weight(selected ? .semibold : .regular))
                    .fixedSize(horizontal: false, vertical: true)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle").accessibilityHidden(true)
            }.foregroundStyle(selected ? UnyriColors.accent : UnyriColors.primaryText)
                .padding(10).frame(minWidth: 124, minHeight: 44)
                .background(.white.opacity(selected ? 0.10 : 0.04), in: RoundedRectangle(cornerRadius: 20))
                .contentShape(RoundedRectangle(cornerRadius: 20))
        }.buttonStyle(.plain)
            .accessibilityLabel(Text(title))
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("sidebar-appearance-choice-" + choice.rawValue)
    }

    private func itemVisibility(_ id: String) -> Binding<Bool> {
        Binding(get: { !draft.hiddenItems.contains(id) }, set: {
            draft = UnyriSidebarVisibility.setting(id, visible: $0, in: draft)
        })
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
