import SwiftUI

/// All controls edit a local draft. Only Apply crosses the persistence boundary.
struct UnyriChatAppearanceSettingsView: View {
    @EnvironmentObject private var store: UnyriChatAppearanceStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @State private var draft: UnyriChatAppearanceSettings
    @State private var resolvedDraft: UnyriResolvedChatAppearance
    @State private var attachments = false
    @State private var compactPage = 1

    init(initial: UnyriChatAppearanceSettings = .read()) {
        let chosen = initial.sanitized()
        _draft = State(initialValue: chosen)
        _resolvedDraft = State(initialValue: UnyriResolvedChatAppearance(settings: chosen))
    }

    var body: some View {
        GeometryReader { geometry in
            Group {
                if geometry.size.width >= 600 && geometry.size.height < 600 {
                    HStack(alignment: .top, spacing: 14) {
                        preview.frame(width: geometry.size.width * 0.44)
                        controls
                    }
                } else if geometry.size.height >= 560 && !dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 12) {
                        preview.frame(height: min(300, geometry.size.height * 0.43))
                        controls
                    }
                } else {
                    VStack(spacing: 12) {
                        Picker(UnyriL10n.string("Appearance section"), selection: $compactPage) {
                            Text(UnyriL10n.string("Live preview")).tag(0)
                            Text(UnyriL10n.string("Settings")).tag(1)
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
        .navigationTitle(UnyriL10n.string("Theme appearance"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // NavigationLink may reuse destination State after a save. Begin
            // each entry from the current immutable saved snapshot, without IO.
            draft = store.appearance.settings
            resolvedDraft = store.appearance
        }
        .onChange(of: draft) { value in resolvedDraft = UnyriResolvedChatAppearance(settings: value) }
    }

    private var preview: some View {
        VStack(spacing: 10) {
            HStack {
                Text(UnyriL10n.string("Live preview")).font(.headline)
                Spacer(minLength: 6)
                Text(UnyriL10n.string("Example only")).font(.caption).foregroundStyle(.secondary)
            }
            HStack(spacing: 8) {
                previewTab("Messages", chosen: !attachments, identifier: "chat-appearance-preview-messages") { attachments = false }
                previewTab("Attachments", chosen: attachments, identifier: "chat-appearance-preview-attachments") { attachments = true }
            }
            ScrollView {
                UnyriAppearancePreviewMessages(attachments: attachments)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
        }
        .padding(12)
        .background { UnyriConversationBackground() }
        .environment(\.unyriChatAppearance, resolvedDraft)
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.14), lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-appearance-preview")
    }

    private var controls: some View {
        ScrollView {
            VStack(spacing: 12) {
                if appColorStyle == .monochrome {
                    Text(UnyriL10n.string("This editor shows your saved message colors. They return in the colorful theme."))
                        .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                group("Text") {
                    Text(UnyriL10n.string("Message font")).font(.subheadline).foregroundStyle(.secondary)
                    choice("System font", chosen: draft.fontFamily == "system", id: "chat-appearance-font-system", font: .system(.body)) { draft.fontFamily = "system" }
                    choice("Serif font", chosen: draft.fontFamily == "serif", id: "chat-appearance-font-serif", font: .system(.body, design: .serif)) { draft.fontFamily = "serif" }
                    choice("Monospaced font", chosen: draft.fontFamily == "monospace", id: "chat-appearance-font-monospace", font: .system(.body, design: .monospaced)) { draft.fontFamily = "monospace" }
                    slider("Chat text size", value: Binding(get: { Double(draft.fontSize) }, set: { draft.fontSize = Int($0.rounded()) }), range: 13...22, step: 1, valueLabel: String(draft.fontSize), id: "chat-appearance-font-size")
                    Toggle(UnyriL10n.string("Slightly heavier text"), isOn: $draft.mediumText)
                        .accessibilityIdentifier("chat-appearance-medium")
                        .onChange(of: draft.mediumText) { _ in UnyriHaptics.selection() }
                    slider("Line spacing", value: $draft.lineHeight, range: 1.05...1.7, step: 0.05,
                        valueLabel: String(format: "%.2f", draft.lineHeight), id: "chat-appearance-line-height")
                }
                group("Message layout") {
                    choice("Saved spacing", chosen: draft.density == "default", id: "chat-appearance-density-default") { draft.density = "default" }
                    choice("Compact spacing", chosen: draft.density == "compact", id: "chat-appearance-density-compact") { draft.density = "compact" }
                    choice("Relaxed spacing", chosen: draft.density == "relaxed", id: "chat-appearance-density-relaxed") { draft.density = "relaxed" }
                    Toggle(UnyriL10n.string("Use saved message shape"), isOn: Binding(get: { draft.cornerRadius == nil }, set: { draft.cornerRadius = $0 ? nil : 25; UnyriHaptics.selection() }))
                        .accessibilityIdentifier("chat-appearance-default-radius")
                    if draft.cornerRadius != nil {
                        slider("Corner rounding", value: Binding(get: { draft.cornerRadius ?? 25 }, set: { draft.cornerRadius = $0 }),
                            range: 0...42, step: 1, valueLabel: String(Int(draft.cornerRadius ?? 25)), id: "chat-appearance-radius")
                    }
                    Text(UnyriL10n.string("The message notch is preserved. Photos and videos keep their own shape."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                group("Message colors") {
                    ForEach(["default", "blue", "violet", "green", "graphite"], id: \.self) { id in
                        colorChoice(id)
                    }
                    Toggle(UnyriL10n.string("Use saved surface transparency"), isOn: Binding(get: { draft.transparency == nil }, set: { draft.transparency = $0 ? nil : 23; UnyriHaptics.selection() }))
                        .accessibilityIdentifier("chat-appearance-default-transparency")
                    if draft.transparency != nil {
                        slider("Surface transparency", value: Binding(get: { Double(draft.transparency ?? 23) }, set: { draft.transparency = Int($0.rounded()) }),
                            range: 0...85, step: 1, valueLabel: "\(draft.transparency ?? 23)%", id: "chat-appearance-transparency")
                    }
                    Text(UnyriL10n.string("Only message surfaces change. Photo and video quality stays the same."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                group("App background") {
                    Toggle(UnyriL10n.string("Subtle pattern"), isOn: $draft.patterned)
                        .accessibilityIdentifier("chat-appearance-pattern")
                        .onChange(of: draft.patterned) { _ in UnyriHaptics.selection() }
                    Text(UnyriL10n.string("One static background throughout UNYRI. No animation or extra work while scrolling."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                group("UNYRI default") {
                    Button {
                        UnyriHaptics.selection(); draft = .init()
                    } label: {
                        Label(UnyriL10n.string("Reset preview to UNYRI default"), systemImage: "arrow.counterclockwise")
                            .frame(maxWidth: .infinity, alignment: .leading).frame(minHeight: 48)
                            .contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("chat-appearance-reset")
                    Text(UnyriL10n.string("Reset changes this preview. Tap Apply to save, or Cancel to keep your current theme."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                group("Connection status") {
                    UnyriConnectionPingSetting()
                    Text(UnyriL10n.string("This connection option is saved immediately and is separate from the theme preview."))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }.padding(.bottom, 12)
        }
        // Give the container its own accessibility boundary. A bare container
        // identifier can overwrite child button identifiers in SwiftUI.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-appearance-controls")
        .tint(.white)
    }

    private var footer: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { cancelButton; applyButton }.fixedSize(horizontal: true, vertical: false)
            VStack(spacing: 8) { cancelButton; applyButton }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 12).padding(.top, 10).padding(.bottom, 8)
        .background(UnyriSavedChatStyle.background)
    }
    private var cancelButton: some View {
        Button { UnyriHaptics.selection(); dismiss() } label: {
            Text(UnyriL10n.string("Cancel")).font(.body.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 22).padding(.vertical, 12)
                .frame(minWidth: 130, maxWidth: .infinity, minHeight: 48)
                .background(Color(hex: 0x282828), in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.18), lineWidth: 1))
        }.buttonStyle(.plain).foregroundStyle(.white).accessibilityIdentifier("chat-appearance-cancel")
    }
    private var applyButton: some View {
        Button { UnyriHaptics.selection(); store.apply(draft); dismiss() } label: {
            Text(UnyriL10n.string("Apply")).font(.body.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 22).padding(.vertical, 12)
                .frame(minWidth: 130, maxWidth: .infinity, minHeight: 48)
                .background(Color(hex: 0xE5E5E5), in: Capsule())
        }.buttonStyle(.plain).foregroundStyle(.black).accessibilityIdentifier("chat-appearance-apply")
    }

    private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(UnyriL10n.string(title)).font(.headline)
            content()
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(hex: 0x242424), in: RoundedRectangle(cornerRadius: 18))
    }
    private func choice(_ title: String, chosen: Bool, id: String, font: Font = .body, action: @escaping () -> Void) -> some View {
        Button { UnyriHaptics.selection(); action() } label: {
            HStack(spacing: 12) {
                Text(UnyriL10n.string(title)).font(font).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Image(systemName: chosen ? "largecircle.fill.circle" : "circle").font(.system(size: 22)).accessibilityHidden(true)
            }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 48)
                .background(chosen ? Color.white.opacity(0.07) : .clear, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).foregroundStyle(.white)
            .accessibilityIdentifier(id)
            .accessibilityAddTraits(chosen ? .isSelected : [])
            .accessibilityValue(UnyriL10n.string(chosen ? "Selected" : "Not selected"))
    }
    private func colorChoice(_ id: String) -> some View {
        let title: String
        switch id {
        case "blue": title = "Blue"
        case "violet": title = "Violet"
        case "green": title = "Green"
        case "graphite": title = "Graphite"
        default: title = "Saved color"
        }
        return Button { UnyriHaptics.selection(); draft.outgoingColor = id } label: {
            HStack(spacing: 12) {
                Circle().fill(Color(hex: UnyriResolvedChatAppearance.outgoingHex(id)))
                    .frame(width: 26, height: 26).overlay(Circle().stroke(Color.white.opacity(0.22), lineWidth: 1)).accessibilityHidden(true)
                Text(UnyriL10n.string(title)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Image(systemName: draft.outgoingColor == id ? "largecircle.fill.circle" : "circle").font(.system(size: 22)).accessibilityHidden(true)
            }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 48)
                .background(draft.outgoingColor == id ? Color.white.opacity(0.07) : .clear, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).foregroundStyle(.white)
            .accessibilityIdentifier("chat-appearance-color-" + id)
            .accessibilityAddTraits(draft.outgoingColor == id ? .isSelected : [])
            .accessibilityValue(UnyriL10n.string(draft.outgoingColor == id ? "Selected" : "Not selected"))
    }
    private func previewTab(_ title: String, chosen: Bool, identifier: String, action: @escaping () -> Void) -> some View {
        Button { UnyriHaptics.selection(); action() } label: {
            Text(UnyriL10n.string(title)).font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(chosen ? Color.white.opacity(0.09) : .clear, in: Capsule())
        }.buttonStyle(.plain).foregroundStyle(chosen ? .white : Color(hex: 0xB0B0B0))
            .accessibilityIdentifier(identifier).accessibilityAddTraits(chosen ? .isSelected : [])
    }
    private func slider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double, valueLabel: String, id: String) -> some View {
        VStack(spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(UnyriL10n.string(title)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(valueLabel).monospacedDigit().foregroundStyle(.secondary)
            }
            Slider(value: value, in: range, step: step, onEditingChanged: { editing in if !editing { UnyriHaptics.selection() } })
                .frame(minHeight: 44).accessibilityLabel(UnyriL10n.string(title))
                .accessibilityValue(valueLabel).accessibilityIdentifier(id)
        }
    }
}
