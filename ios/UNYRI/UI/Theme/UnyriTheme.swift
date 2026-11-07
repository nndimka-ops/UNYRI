import SwiftUI
import UIKit
import ImageIO

// MARK: - Hex color helpers (static, precomputed — no runtime hex parsing)

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }

    init(hex: UInt32, alpha: Double) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

// MARK: - App decorative color style

private struct UnyriAppColorStyleKey: EnvironmentKey {
    static let defaultValue = UnyriAppColorStyle.monochrome
}
extension EnvironmentValues {
    var unyriAppColorStyle: UnyriAppColorStyle {
        get { self[UnyriAppColorStyleKey.self] }
        set { self[UnyriAppColorStyleKey.self] = newValue }
    }
}

extension UnyriAppColorStyle {
    func decorative(_ source: Color) -> Color {
        isMonochrome ? Color(hex: Self.monochromeAccent) : source
    }
    func decorative<S: ShapeStyle>(_ source: S) -> AnyShapeStyle {
        isMonochrome ? AnyShapeStyle(Color(hex: Self.monochromeAccent)) : AnyShapeStyle(source)
    }
    func surface(_ source: Color) -> Color {
        isMonochrome ? Color(hex: 0x202020) : source
    }
    func surface<S: ShapeStyle>(_ source: S) -> AnyShapeStyle {
        isMonochrome ? AnyShapeStyle(Color(hex: 0x202020)) : AnyShapeStyle(source)
    }
    func material(hex: UInt32) -> Color { Color(hex: materialRGB(hex)) }
    func role(_ role: UnyriAppColorRole) -> Color { Color(hex: accentRGB(role)) }
    func controlTint(_ source: Color) -> Color { isMonochrome ? Color(hex: Self.monochromeControlTint) : source }
    var onColorfulButton: Color { isMonochrome ? Color(hex: 0x222222) : .white }
}

/// AppStorage subscribes at the app root, not in scrolling rows. Changing the
/// environment preserves view/navigation identity, focus and in-flight work.
private struct UnyriAppColorThemeRoot: ViewModifier {
    @AppStorage(UnyriAppColorStyle.preferenceKey) private var rawStyle = UnyriAppColorStyle.monochrome.rawValue
    func body(content: Content) -> some View {
        let style = UnyriAppColorStyle.parse(rawStyle)
        content.environment(\.unyriAppColorStyle, style)
            .tint(style.isMonochrome ? Color(hex: UnyriAppColorStyle.monochromeControlTint) : Color.accentColor)
    }
}

private struct UnyriDecorativeForeground: ViewModifier {
    @Environment(\.unyriAppColorStyle) private var style
    let source: Color
    func body(content: Content) -> some View { content.foregroundColor(style.decorative(source)) }
}
private struct UnyriDecorativeTint: ViewModifier {
    @Environment(\.unyriAppColorStyle) private var style
    let source: Color
    func body(content: Content) -> some View { content.tint(style.controlTint(source)) }
}
extension View {
    func unyriAppColorThemeRoot() -> some View { modifier(UnyriAppColorThemeRoot()) }
    func unyriDecorativeForeground(_ color: Color) -> some View { modifier(UnyriDecorativeForeground(source: color)) }
    func unyriDecorativeTint(_ color: Color) -> some View { modifier(UnyriDecorativeTint(source: color)) }
}

struct UnyriAppColorThemePicker: View {
    @Environment(\.unyriAppColorStyle) private var style
    @AppStorage(UnyriAppColorStyle.preferenceKey) private var rawStyle = UnyriAppColorStyle.monochrome.rawValue
    var body: some View {
        UnyriSettingsGroup("App color theme") {
            choice(.colorful, title: "Colorful UNYRI", subtitle: "Color accents for icons and controls")
            UnyriSettingsDivider()
            choice(.monochrome, title: "Monochrome", subtitle: "Neutral decoration; safety and message status colors stay visible")
        }
        UnyriSettingsNote("App colors are saved on this device. Your custom chat settings are kept.")
    }
    private func choice(_ value: UnyriAppColorStyle, title: String, subtitle: String) -> some View {
        Button {
            guard UnyriAppColorStyle.parse(rawStyle) != value else { return }
            UnyriHaptics.selection(); rawStyle = value.rawValue
        } label: {
            HStack(spacing: 12) {
                HStack(spacing: 3) {
                    ForEach(Array(UnyriAppColorRole.allCases.prefix(3).enumerated()), id: \.offset) { _, role in
                        Capsule().fill(value.role(role)).frame(width: 7, height: 25)
                    }
                }.frame(width: 32).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(LocalizedStringKey(title)).font(.subheadline.weight(.semibold)).foregroundStyle(UnyriColors.primaryText)
                    Text(LocalizedStringKey(subtitle)).font(.caption).foregroundStyle(UnyriColors.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: style == value ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 21)).foregroundStyle(style == value ? style.role(.categoryMint) : UnyriColors.veryMutedText)
                    .accessibilityHidden(true)
            }.padding(14).frame(minHeight: 62).contentShape(Rectangle())
        }.buttonStyle(UnyriQuietButtonStyle())
            .accessibilityIdentifier("app-color-style-" + value.rawValue)
            .accessibilityAddTraits(style == value ? .isSelected : [])
    }
}

// MARK: - UNYRI dark semantic palette (shared with Android UnyriDarkColors)

enum UnyriColors {
    // Core dark surfaces
    static let background = UnyriSavedChatStyle.background
    static let elevatedBackground = Color(hex: 0x202020)
    static let deepPanel = Color(hex: 0x202020)
    static let chatPanel = deepPanel.opacity(0.96)

    /// Calls retain their dedicated near-black background and semantic states.
    static let callBackground = Color(hex: 0x0A0A0A)

    // Brand accent scale
    static let accent = Color(hex: 0xDEDEDE)
    static let accentBright = Color(hex: 0xF1F1F1)
    static let accentSoft = Color(hex: 0xDEDEDE)
    static let accentPressed = Color(hex: 0xB0B0B0)

    // Support accents
    static let supportBlue = Color(hex: 0xDEDEDE)
    static let supportCyan = Color(hex: 0xDEDEDE)
    static let success = Color(hex: 0x38D996)
    static let warning = Color(hex: 0xF2B84B)
    static let danger = Color(hex: 0xFF4D5F)
    static let dangerPressed = Color(hex: 0xB72B3C)

    // Text
    static let primaryText = Color(hex: 0xF1F1F1)
    static let secondaryText = Color(hex: 0xB0B0B0)
    static let mutedText = Color(hex: 0xB0B0B0)
    static let veryMutedText = Color(hex: 0x737373)
    static let onAccent = Color(hex: 0x222222)

    // Generic semantic surfaces; the approved chat uses its saved per-type tokens.
    static let textOutgoingBubble = Color(hex: 0xD4D4D4)
    static let textIncomingBubble = Color(hex: 0x2A2A2A)
    static let outgoingBubble = Color(hex: 0xD4D4D4)
    static let onOutgoingBubble = Color(hex: 0x171717)
    static let incomingBubble = Color(hex: 0x2A2A2A)
    static let onIncomingBubble = Color(hex: 0xF1F1F1)
    static let readTick = UnyriSavedChatStyle.readTicks

    // Glass surfaces
    static let glassPrimary = Color(hex: 0x282828)
    static let glassSecondary = Color(hex: 0x2A2A2A)
    static let glassFloating = Color(hex: 0x202020)
    static let bannerBackground = Color(hex: 0x202020)
    static let glassHighlight = Color(hex: 0xFFFFFF, alpha: 15 / 255)
    static let glassPressed = Color(hex: 0xFFFFFF, alpha: 20 / 255)

    // Outlines
    static let outlineSubtle = Color(hex: 0xFFFFFF, alpha: 20 / 255)
    static let outlineSecondary = Color(hex: 0xFFFFFF, alpha: 31 / 255)
    static let outlineActive = Color(hex: 0x737373)
    static let outlineSelected = Color(hex: 0xDEDEDE)

    // Glow
    static let glowAccentSoft = Color.clear
    static let glowAccentStrong = Color.clear
}

/// One shared saved tile; the app-wide setting is resolved once at the root.
struct UnyriConversationBackground: View {
    @Environment(\.unyriChatAppearance) private var appearance
    var body: some View {
        ZStack {
            UnyriSavedChatStyle.background
            if appearance.settings.patterned && UnyriSavedChatStyle.pattern != "none" {
                Image(uiImage: UnyriSavedPatternTile.image).resizable(resizingMode: .tile)
                    .opacity(UnyriSavedChatStyle.patternOpacity)
                    .offset(x: UnyriSavedChatStyle.patternX, y: UnyriSavedChatStyle.patternY)
            }
        }
        .allowsHitTesting(false).accessibilityHidden(true)
    }
}

extension View {
    /// Apply to screen roots; form/list rows keep their solid readable surfaces.
    func unyriScreenBackground() -> some View {
        self.scrollContentBackground(.hidden)
            .background { UnyriConversationBackground().ignoresSafeArea() }
            .background { UnyriNativeBackAppearance().allowsHitTesting(false).accessibilityHidden(true) }
    }
}

struct UnyriBubbleFill: View {
    var outgoing: Bool
    var body: some View {
        LinearGradient(colors: outgoing
            ? [UnyriColors.accent.opacity(0.72), UnyriColors.outgoingBubble]
            : [UnyriColors.incomingBubble, UnyriColors.deepPanel],
            startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

// MARK: - Semantic radii (UNYRI Glass Flow)

enum UnyriShapes {
    static let radiusSmall: CGFloat = 10
    static let radiusMedium: CGFloat = 14
    static let radiusCard: CGFloat = 18
    static let radiusBubble: CGFloat = 18
    static let radiusChatRow: CGFloat = 18
    static let radiusAttachment: CGFloat = 18
    static let radiusChip: CGFloat = 19
    static let radiusComposer: CGFloat = 27
    static let radiusSearch: CGFloat = 23
    static let radiusBottomNav: CGFloat = 30
    static let radiusSheetTop: CGFloat = 24
    static let radiusLocalVideo: CGFloat = 20
    static let radiusCallControl: CGFloat = 28
}

// MARK: - Spacing scale

enum UnyriSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
    static let xxxl: CGFloat = 32
}

// MARK: - Semantic text roles (sizes tuned in later component phases)

enum UnyriTypography {
    static let largeTitle: Font = .system(size: 34, weight: .bold)
    static let screenTitle: Font = .system(size: 28, weight: .bold)
    static let sectionTitle: Font = .system(size: 16, weight: .semibold)
    static let body: Font = .system(size: 16)
    static let bodySecondary: Font = .system(size: 14)
    static let caption: Font = .system(size: 12)
    static let timestamp: Font = .system(size: 11, weight: .medium)
    static let tabLabel: Font = .system(size: 12, weight: .medium)
    static let buttonLabel: Font = .system(size: 14, weight: .semibold)
    static let callDuration: Font = .system(size: 16, weight: .semibold).monospacedDigit()
    static let status: Font = .system(size: 12)
}

// MARK: - Shared component styles (unchanged)

/// Discrete UIKit feedback follows the device's system haptic settings.
@MainActor
enum UnyriHaptics {
    static func press() { UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.45) }
    static func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    static func longPress() { UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.65) }
}

private struct UnyriPressFeedback: ViewModifier {
    @Environment(\.isEnabled) private var isEnabled
    let pressed: Bool
    var allowed = true
    func body(content: Content) -> some View {
        // Observe the Button's own press state. A gesture on its label can
        // compete with Button/NavigationLink activation inside scroll views.
        content.onChange(of: pressed) { isPressed in
            if isPressed && isEnabled && allowed {
                Task { @MainActor in
                    PerformanceRecorder.shared.record(.action)
                    PerformanceMonitor.shared.activity()
                    UnyriHaptics.press()
                }
            }
        }
    }
}

/// Filled accent button used across the auth screens.
struct UnyriPrimaryButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var isLoading = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(UnyriColors.onAccent)
            .tint(UnyriColors.onAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(configuration.isPressed || isLoading ? UnyriColors.accentPressed : UnyriColors.accent)
            )
            .opacity(isLoading ? 0.7 : 1.0)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.975 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: configuration.isPressed)
            .modifier(UnyriPressFeedback(pressed: configuration.isPressed, allowed: !isLoading))
    }
}

/// Shared rounded input-field style.
struct UnyriTextFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(.secondarySystemBackground))
            )
    }
}

extension View {
    func unyriFieldStyle() -> some View {
        modifier(UnyriTextFieldStyle())
    }

    /// Native prominent controls need the matching ink when the shared accent
    /// is light. Keep UIKit's button geometry and disabled-state behavior.
    func unyriProminentAction() -> some View {
        self.buttonStyle(.borderedProminent)
            .tint(UnyriColors.accent)
            .foregroundStyle(UnyriColors.onAccent)
    }
}


/// Inline feedback uses shape and an icon as well as color; text scales with Dynamic Type.
struct UnyriNotice: View {
    let message: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.circle")
                .foregroundColor(UnyriColors.warning)
                .accessibilityHidden(true)
            Text(message)
                .foregroundColor(UnyriColors.primaryText)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.footnote)
        .padding(14)
        .background(UnyriColors.glassSecondary, in: RoundedRectangle(cornerRadius: UnyriShapes.radiusMedium))
        .accessibilityElement(children: .combine)
    }
}


/// Small, finite brand response. No animation loop or startup delay.
struct UnyriBrandHeader: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    var body: some View {
        VStack(spacing: 4) {
            Button(action: {}) {
                Image("UnyriBrandMark")
                    .resizable().renderingMode(.original).scaledToFit()
                    .frame(width: 72, height: 72)
            }
            .buttonStyle(UnyriBrandPressStyle())
            .accessibilityLabel("UNYRI")
            Text("UNYRI").font(.system(size: 34, weight: .bold, design: .rounded))
        }
        .opacity(appeared || reduceMotion ? 1 : 0.75)
        .scaleEffect(appeared || reduceMotion ? 1 : 0.97)
        .onAppear {
            guard !appeared else { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { appeared = true }
        }
    }
}

private struct UnyriBrandPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.95 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: configuration.isPressed)
    }
}


/// Small press response without changing the button's layout or hit area.
struct UnyriQuietButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.82 : 1)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.985 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: configuration.isPressed)
            .modifier(UnyriPressFeedback(pressed: configuration.isPressed))
    }
}

/// Fixed one-line footprint: reconnect never moves the conversation.
struct UnyriStatusText: View {
    @ScaledMetric(relativeTo: .caption2) private var lineHeight: CGFloat = 14
    let text: String
    var body: some View {
        Text(text)
            .font(.caption2).lineLimit(1)
            .frame(minHeight: lineHeight, alignment: .leading)
    }
}


/// Bounded per-screen tracking prevents animation replay after lazy row recycling.
final class UnyriMessageEntranceGate {
    let openedAt = Date()
    private var seen = Set<String>()
    func claim(id: String, createdAt: Date, pending: Bool, now: Date = Date()) -> Bool {
        guard pending, createdAt >= openedAt, createdAt <= now,
              now.timeIntervalSince(createdAt) <= 5, seen.count < 512 else { return false }
        return seen.insert(id).inserted
    }
}

struct UnyriNewMessage: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var settled: Bool
    init(gate: UnyriMessageEntranceGate, id: String, createdAt: Date, pending: Bool) {
        // State survives updates of status/content for the same row identity.
        _settled = State(initialValue: !gate.claim(id: id, createdAt: createdAt, pending: pending))
    }
    func body(content: Content) -> some View {
        content
            .opacity(settled || reduceMotion ? 1 : 0.65)
            .offset(y: settled || reduceMotion ? 0 : 4)
            .onAppear { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.16)) { settled = true } }
    }
}


/// Compact native header: only the small underline animates during active work.
struct UnyriConnectionHeader: View {
    @ObservedObject var realtime: RealtimeClient
    let routeAvailable: Bool
    let loading: Bool
    let error: Bool
    let onRetry: () -> Void
    var visible: Bool = true
    var contentReady: Bool = false
    @AppStorage("cocoConnectionPing") private var showPing = false
    @Environment(\.scenePhase) private var scene
    @State private var unavailable = false
    @State private var ping: Int?
    private var connected: Bool { realtime.state == .connected }
    private var recoveryKey: String { "\(connected)|\(routeAvailable)|\(scene == .active && visible)" }
    private var pingKey: String { "\(connected)|\(showPing)|\(scene == .active && visible)" }
    private var status: String {
        if connected {
            if error || realtime.syncPhase == .failed { return "Sync paused" }
            if !contentReady && (loading || realtime.syncPhase == .running) { return "Syncing…" }
            return "Connection online"
        }
        if !routeAvailable { return "No connection" }
        return unavailable ? "Server unavailable" : "Connecting…"
    }
    private var busy: Bool { status == "Connecting…" || status == "Syncing…" || (status == "Server unavailable" && realtime.state == .connecting) }
    private var tint: Color {
        switch status {
        case "Connection online": return Color(hex: 0x32C9E9)
        case "Syncing…": return Color(hex: 0xAA85ED)
        case "Server unavailable": return Color(hex: 0xEC8589)
        default: return Color(hex: 0xE5AC5E)
        }
    }
    var body: some View {
        Button(action: onRetry) {
            VStack(spacing: 5) {
                HStack(spacing: 5) {
                    Text(UnyriL10n.string(status)).font(.system(size: 13)).lineLimit(1)
                    if showPing && connected {
                        Text((ping.map(String.init) ?? "—") + " " + UnyriL10n.string("ms"))
                            .font(.system(size: 10)).monospacedDigit().foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                    }
                }
                ConnectionUnderline(tint: tint, online: status == "Connection online", busy: busy && visible)
            }
            .foregroundStyle(Color(hex: 0xF2F3F7)).frame(maxWidth: .infinity).frame(height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(status != "Server unavailable" && status != "Sync paused")
        .task(id: recoveryKey) {
            unavailable = false
            if !connected && routeAvailable && scene == .active && visible {
                do { try await Task.sleep(nanoseconds: 8_000_000_000) } catch { return }
                if !Task.isCancelled { unavailable = true }
            }
        }
        .task(id: pingKey) {
            ping = nil
            guard showPing, connected, scene == .active, visible else { return }
            while !Task.isCancelled {
                let value = await realtime.measureDisplayPing()
                guard !Task.isCancelled else { return }
                ping = value
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            }
        }
    }
}

private struct ConnectionUnderline: View {
    let tint: Color
    let online: Bool
    let busy: Bool
    @Environment(\.scenePhase) private var scene
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var moving = false
    private var animated: Bool { busy && scene == .active && !reduceMotion }
    var body: some View {
        ZStack(alignment: .leading) {
            Capsule().fill(LinearGradient(colors: [tint, online ? Color(hex: 0xA783EC) : tint.opacity(0.6)], startPoint: .leading, endPoint: .trailing))
            if animated {
                Capsule().fill(.white.opacity(0.65)).frame(width: 8)
                    .offset(x: moving ? 30 : -8)
                    .onAppear { moving = false; withAnimation(.linear(duration: 1.4).delay(0.7).repeatForever(autoreverses: false)) { moving = true } }
                    .onDisappear { moving = false }
            }
        }
        .frame(width: 30, height: 2).clipShape(Capsule()).accessibilityHidden(true)
    }
}

struct UnyriConnectionPingSetting: View {
    @AppStorage("cocoConnectionPing") private var enabled = false
    var body: some View {
        Toggle(isOn: $enabled) {
            VStack(alignment: .leading, spacing: 4) {
                Text(UnyriL10n.string("Show ping"))
                Text(UnyriL10n.string("Server response time next to the status")).font(.caption).foregroundStyle(UnyriColors.secondaryText)
            }
        }.onChange(of: enabled) { _ in UnyriHaptics.selection() }
    }
}
