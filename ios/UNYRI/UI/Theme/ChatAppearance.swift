import SwiftUI
import UIKit
import Combine

extension UnyriSavedChatStyle {
    static let background = Color(hex: backgroundHex)
    static let incoming = Color(hex: incomingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(inTransparency)))
    static let outgoing = Color(hex: outgoingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(outTransparency)))
    static let text = Color(hex: textHex)
    static let outText = Color(hex: outTextHex)
    static let muted = Color(hex: mutedHex)
    static let accent = Color(hex: accentHex)
    static let buttonInk = Color(hex: buttonInkHex)
    static let ticks = Color(hex: ticksHex)
    static let readTicks = Color(hex: readTicksHex)
    static let border = Color(hex: borderHex)
    static let entry = Color(hex: entryHex)
    static let reaction = Color(hex: reactionHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(reactionTransparency)))
    static let voiceIncoming = Color(hex: voiceIncomingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(voiceInTransparency)))
    static let voiceOutgoing = Color(hex: voiceOutgoingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(voiceOutTransparency)))
    static let fileIncoming = Color(hex: fileIncomingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(fileInTransparency)))
    static let fileOutgoing = Color(hex: fileOutgoingHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(fileOutTransparency)))
    static let field = Color(hex: fieldColorHex, alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: Double(fieldTransparency)))
    static let topColor = Color.black.opacity((100 - chromeTransparency) / 100)
    static let bottomColor = background.opacity((100 - chromeTransparency) / 100)
    static let waveQuiet = Color(hex: 0x737373)
    static let reactionWidth = max(reactionHeight + 3, emojiSize + 10)
    static let mediaShape = RoundedRectangle(cornerRadius: mediaRadius, style: .circular)
    static let topInk = Color.white
    static let bottomInk = accent
    static let micColor = accent
    static let micInk = buttonInk
    static let sent = ticks
    static let delivered = ticks
    static let read = readTicks
}

/// The approved iOS input replaces only the previous generated default. Other
/// saved field colors retain their exact quantized alpha, independently of the
/// in-app message-surface slider. No stored setting or shared design is changed.
enum UnyriComposerSurfaceMetrics {
    static let legacyDefaultRGB: UInt32 = 0x000000
    static let legacyDefaultTransparency: Double = 20
    static let approvedDefaultRGB: UInt32 = 0xFFFFFF
    static let approvedDefaultTransparency: Double = 86

    struct Field: Equatable {
        let rgb: UInt32
        let alpha: Double
    }

    static func resolvedField(rgb: UInt32, transparency: Double) -> Field {
        let usesApprovedDefault = rgb == legacyDefaultRGB && transparency == legacyDefaultTransparency
        return Field(rgb: usesApprovedDefault ? approvedDefaultRGB : rgb,
            alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: usesApprovedDefault ? approvedDefaultTransparency : transparency))
    }

    /// Source-over in the same sRGB channel space as the saved field colors.
    /// A translucent white tint becomes dark gray over a black conversation;
    /// accessibility fallback must never turn that tint into opaque white.
    static func compositedRGB(field: Field, backgroundRGB: UInt32) -> UInt32 {
        let alpha = min(1, max(0, field.alpha))
        func channel(_ shift: UInt32) -> UInt32 {
            let foreground = Double((field.rgb >> shift) & 0xFF)
            let background = Double((backgroundRGB >> shift) & 0xFF)
            return UInt32((foreground * alpha + background * (1 - alpha)).rounded())
        }
        return (channel(16) << 16) | (channel(8) << 8) | channel(0)
    }
}

/// Central input styling, separate from scrolling rows and generated settings.
enum UnyriComposerStyle {
    // The default uses the system optical material without an extra white veil.
    // An explicitly customized field still supplies its saved tint exactly once.
    static let usesSystemGlass = UnyriSavedChatStyle.fieldColorHex == UnyriComposerSurfaceMetrics.legacyDefaultRGB
        && Double(UnyriSavedChatStyle.fieldTransparency) == UnyriComposerSurfaceMetrics.legacyDefaultTransparency
    static let cornerRadius: CGFloat = 22
    static let field = UnyriComposerSurfaceMetrics.resolvedField(
        rgb: UnyriSavedChatStyle.fieldColorHex, transparency: Double(UnyriSavedChatStyle.fieldTransparency))
    static let fieldColor = Color(hex: field.rgb, alpha: field.alpha)
    static let solidColor = Color(hex: UnyriComposerSurfaceMetrics.compositedRGB(
        field: field, backgroundRGB: UnyriSavedChatStyle.backgroundHex))
}

/// One native glass surface behind the existing input, without capturing the chat.
/// All tint alpha is already resolved; it is applied exactly once.
struct UnyriComposerSurface<Content: View>: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    let cornerRadius: CGFloat
    let fieldColor: Color
    private let usesSystemGlass: Bool
    let solidColor: Color
    let content: Content

    init(cornerRadius: CGFloat = UnyriComposerStyle.cornerRadius,
         fieldColor: Color? = nil,
         solidColor: Color = UnyriComposerStyle.solidColor,
         @ViewBuilder content: () -> Content) {
        self.cornerRadius = cornerRadius
        self.fieldColor = fieldColor ?? UnyriComposerStyle.fieldColor
        self.usesSystemGlass = fieldColor == nil && UnyriComposerStyle.usesSystemGlass
        self.solidColor = solidColor
        self.content = content()
    }

    private var solid: Bool { reduceTransparency || lowPower || contrast == .increased }

    var body: some View {
        content
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .background {
                let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                if solid {
                    shape.fill(solidColor)
                } else if #available(iOS 26.0, *) {
                    if usesSystemGlass {
                        shape.fill(.clear).glassEffect(.regular, in: shape)
                    } else {
                        shape.fill(.clear).glassEffect(.regular.tint(fieldColor), in: shape)
                    }
                } else {
                    shape.fill(.ultraThinMaterial).overlay(shape.fill(fieldColor))
                        .overlay(shape.strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5))
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
                let next = ProcessInfo.processInfo.isLowPowerModeEnabled
                if lowPower != next { lowPower = next }
            }
    }
}

/// One immutable snapshot for the actual chat and draft preview. No defaults/JSON
/// access in rows; UIFont metrics are resolved once when settings change.
struct UnyriResolvedChatAppearance {
    let settings: UnyriChatAppearanceSettings
    let appColorStyle: UnyriAppColorStyle
    let fontSize: CGFloat
    let fontFamily: String
    let mediumText: Bool
    let lineHeight: CGFloat
    let extraLineSpacing: CGFloat
    let padX: CGFloat
    let padY: CGFloat
    let voicePadY: CGFloat
    let gap: CGFloat
    let groupGap: CGFloat
    let incoming: Color
    let outgoing: Color
    let voiceIncoming: Color
    let voiceOutgoing: Color
    let fileIncoming: Color
    let fileOutgoing: Color
    private let design: Font.Design

    init(settings: UnyriChatAppearanceSettings, appColorStyle: UnyriAppColorStyle = .monochrome) {
        let chosen = settings.sanitized()
        self.settings = chosen; self.appColorStyle = appColorStyle
        fontSize = CGFloat(chosen.fontSize); fontFamily = chosen.fontFamily
        mediumText = chosen.mediumText; lineHeight = CGFloat(chosen.lineHeight)
        design = chosen.fontFamily == "serif" ? .serif : chosen.fontFamily == "monospace" ? .monospaced : .default
        let uiDesign: UIFontDescriptor.SystemDesign = chosen.fontFamily == "serif" ? .serif : chosen.fontFamily == "monospace" ? .monospaced : .default
        let base = UIFont.systemFont(ofSize: CGFloat(chosen.fontSize), weight: chosen.mediumText ? .medium : .regular)
        let actual = UIFont(descriptor: base.fontDescriptor.withDesign(uiDesign) ?? base.fontDescriptor, size: CGFloat(chosen.fontSize))
        extraLineSpacing = max(0, CGFloat(chosen.fontSize) * CGFloat(chosen.lineHeight) - actual.lineHeight)
        switch chosen.density {
        case "compact": (padX, padY, voicePadY, gap, groupGap) = (10, 6, 3, 3, 3)
        case "relaxed": (padX, padY, voicePadY, gap, groupGap) = (16, 10, 7, 8, 8)
        default: (padX, padY, voicePadY, gap, groupGap) = (UnyriSavedChatStyle.padX, UnyriSavedChatStyle.padY, UnyriSavedChatStyle.voicePadY, UnyriSavedChatStyle.gap, UnyriSavedChatStyle.groupGap)
        }
        func surface(_ hex: UInt32, transparency: CGFloat, outgoing: Bool) -> Color {
            let tint = outgoing && chosen.outgoingColor != "default" ? Self.outgoingHex(chosen.outgoingColor) : hex
            return Color(hex: appColorStyle.materialRGB(tint), alpha: UnyriAppearanceSurfaceMetrics.alpha(transparency: chosen.transparency.map { Double($0) } ?? Double(transparency)))
        }
        incoming = surface(UnyriSavedChatStyle.incomingHex, transparency: UnyriSavedChatStyle.inTransparency, outgoing: false)
        outgoing = surface(UnyriSavedChatStyle.outgoingHex, transparency: UnyriSavedChatStyle.outTransparency, outgoing: true)
        voiceIncoming = surface(UnyriSavedChatStyle.voiceIncomingHex, transparency: UnyriSavedChatStyle.voiceInTransparency, outgoing: false)
        voiceOutgoing = surface(UnyriSavedChatStyle.voiceOutgoingHex, transparency: UnyriSavedChatStyle.voiceOutTransparency, outgoing: true)
        fileIncoming = surface(UnyriSavedChatStyle.fileIncomingHex, transparency: UnyriSavedChatStyle.fileInTransparency, outgoing: false)
        fileOutgoing = surface(UnyriSavedChatStyle.fileOutgoingHex, transparency: UnyriSavedChatStyle.fileOutTransparency, outgoing: true)
    }

    func messageFont(scale: CGFloat = 1) -> Font {
        .system(size: fontSize * scale, weight: mediumText ? .medium : .regular, design: design)
    }
    static func outgoingHex(_ id: String) -> UInt32 {
        switch id {
        case "blue": return 0x1C2D50
        case "violet": return 0x2C2655
        case "green": return 0x173F36
        case "graphite": return 0x2A2A2A
        default: return UnyriSavedChatStyle.outgoingHex
        }
    }
    func bodyShape(isMine: Bool, layoutDirection: LayoutDirection = .leftToRight) -> UnyriSavedBubbleShape {
        let c = isMine
            ? [UnyriSavedChatStyle.outTopLeft, UnyriSavedChatStyle.outTopRight, UnyriSavedChatStyle.outBottomRight, UnyriSavedChatStyle.outBottomLeft]
            : [UnyriSavedChatStyle.inTopLeft, UnyriSavedChatStyle.inTopRight, UnyriSavedChatStyle.inBottomRight, UnyriSavedChatStyle.inBottomLeft]
        return shape(c, direction: layoutDirection)
    }
    func voiceShape(isMine: Bool, layoutDirection: LayoutDirection = .leftToRight) -> UnyriSavedBubbleShape {
        let r = UnyriSavedChatStyle.voiceRadius, s = UnyriSavedChatStyle.voiceSmallRadius
        return shape(isMine ? [s, r, r, s] : [r, s, s, r], direction: layoutDirection)
    }
    func fileShape(isMine: Bool, layoutDirection: LayoutDirection = .leftToRight) -> UnyriSavedBubbleShape {
        let r = UnyriSavedChatStyle.fileRadius, s = UnyriSavedChatStyle.fileSmallRadius
        return shape(isMine ? [r, s, s, r] : [s, r, r, s], direction: layoutDirection)
    }
    var documentAlbumShape: UnyriSavedBubbleShape {
        let r = CGFloat(settings.cornerRadius ?? Double(UnyriSavedChatStyle.mediaRadius))
        return UnyriSavedBubbleShape(corners: [r, r, r, r])
    }
    func voiceBackground(isMine: Bool) -> Color { isMine ? voiceOutgoing : voiceIncoming }
    func fileBackground(isMine: Bool) -> Color { isMine ? fileOutgoing : fileIncoming }
    private func shape(_ original: [CGFloat], direction: LayoutDirection) -> UnyriSavedBubbleShape {
        let corners = UnyriAppearanceCornerMetrics.applyingOverride(original, radius: settings.cornerRadius)
        return UnyriSavedBubbleShape(corners: direction == .rightToLeft ? [corners[1], corners[0], corners[3], corners[2]] : corners)
    }
}

/// CSS scales every corner by the same factor when any adjacent pair overlaps.
/// Keeping zero corners literal preserves the approved compact message notch.
struct UnyriSavedBubbleShape: Shape {
    var corners: [CGFloat]
    func path(in rect: CGRect) -> Path {
        let c = UnyriAppearanceCornerMetrics.normalized(corners, width: rect.width, height: rect.height)
        let tl = c[0], tr = c[1], br = c[2], bl = c[3]
        let k: CGFloat = 0.5522847498307936
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + tl, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - tr, y: rect.minY))
        p.addCurve(to: CGPoint(x: rect.maxX, y: rect.minY + tr), control1: CGPoint(x: rect.maxX - tr + k * tr, y: rect.minY), control2: CGPoint(x: rect.maxX, y: rect.minY + tr - k * tr))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br))
        p.addCurve(to: CGPoint(x: rect.maxX - br, y: rect.maxY), control1: CGPoint(x: rect.maxX, y: rect.maxY - br + k * br), control2: CGPoint(x: rect.maxX - br + k * br, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + bl, y: rect.maxY))
        p.addCurve(to: CGPoint(x: rect.minX, y: rect.maxY - bl), control1: CGPoint(x: rect.minX + bl - k * bl, y: rect.maxY), control2: CGPoint(x: rect.minX, y: rect.maxY - bl + k * bl))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + tl))
        p.addCurve(to: CGPoint(x: rect.minX + tl, y: rect.minY), control1: CGPoint(x: rect.minX, y: rect.minY + tl - k * tl), control2: CGPoint(x: rect.minX + tl - k * tl, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

private struct UnyriChatAppearanceKey: EnvironmentKey {
    static let defaultValue = UnyriResolvedChatAppearance(settings: .init())
}
extension EnvironmentValues {
    var unyriChatAppearance: UnyriResolvedChatAppearance {
        get { self[UnyriChatAppearanceKey.self] }
        set { self[UnyriChatAppearanceKey.self] = newValue }
    }
}

@MainActor
final class UnyriChatAppearanceStore: ObservableObject {
    static let shared = UnyriChatAppearanceStore()
    @Published private(set) var appearance: UnyriResolvedChatAppearance
    private(set) var monochromeAppearance: UnyriResolvedChatAppearance
    private let defaults: UserDefaults
    private let domainName: String?
    private var observation: NSObjectProtocol?

    init(defaults: UserDefaults = .standard, domainName: String? = Bundle.main.bundleIdentifier) {
        self.defaults = defaults; self.domainName = domainName
        UnyriChatAppearanceSettings.adoptSavedPreset(in: defaults, domainName: domainName)
        let initial = UnyriChatAppearanceSettings.read(from: defaults)
        appearance = UnyriResolvedChatAppearance(settings: initial, appColorStyle: .colorful)
        monochromeAppearance = UnyriResolvedChatAppearance(settings: initial, appColorStyle: .monochrome)
        observation = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: defaults, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.reload() }
        }
    }
    deinit { if let observation { NotificationCenter.default.removeObserver(observation) } }
    func apply(_ draft: UnyriChatAppearanceSettings) {
        draft.save(to: defaults, domainName: domainName)
        reload()
    }
    private func reload() {
        let next = UnyriChatAppearanceSettings.read(from: defaults)
        guard next != appearance.settings else { return }
        monochromeAppearance = UnyriResolvedChatAppearance(settings: next, appColorStyle: .monochrome)
        appearance = UnyriResolvedChatAppearance(settings: next, appColorStyle: .colorful)
    }
}

@MainActor
private struct UnyriChatAppearanceRoot: ViewModifier {
    @StateObject private var store = UnyriChatAppearanceStore.shared
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    func body(content: Content) -> some View {
        content.environmentObject(store).environment(\.unyriChatAppearance,
            appColorStyle == .monochrome ? store.monochromeAppearance : store.appearance)
    }
}
extension View {
    @MainActor func unyriChatAppearanceRoot() -> some View { modifier(UnyriChatAppearanceRoot()) }
    func unyriAppearanceMaximumWidth(_ width: CGFloat) -> some View { UnyriAppearanceWidthLimit(maximum: width) { self } }
}

/// Cache the exact wrapping proposal. Placement must not propose the narrower
/// returned intrinsic width again, or multiline content changes its height.
struct UnyriAppearanceWidthLimit: Layout {
    var maximum: CGFloat
    struct Cache { var proposedWidth: CGFloat? }
    func makeCache(subviews: Subviews) -> Cache { Cache() }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        guard let first = subviews.first else { return .zero }
        let available = proposal.width.flatMap { $0.isFinite ? max(1, $0) : nil }
        let limit = maximum.isFinite ? max(1, maximum) : available ?? 10_000
        let width = min(available ?? limit, limit)
        cache.proposedWidth = width
        return first.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        guard let first = subviews.first else { return }
        first.place(at: bounds.origin, proposal: ProposedViewSize(width: cache.proposedWidth, height: nil))
    }
}

/// Static wallpaper. One retained raster tile is shared by every screen; no
/// frame-driven work, animation, image decoding, JSON parsing or layout reads.
enum UnyriSavedPatternTile {
    static let image: UIImage = {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2; format.opaque = false
        let raster = UIGraphicsImageRenderer(size: CGSize(width: 192, height: 192), format: format).image { renderer in
            let c = renderer.cgContext
            c.setStrokeColor(UIColor(red: 119 / 255, green: 119 / 255, blue: 119 / 255, alpha: 1).cgColor)
            c.setLineWidth(UnyriSavedChatStyle.patternStroke)
            c.setLineCap(.round); c.setLineJoin(.round)
            let spacing: CGFloat = UnyriSavedChatStyle.patternDensity == "dense" ? 32 : UnyriSavedChatStyle.patternDensity == "normal" ? 64 : 96
            var row = 0
            var index = 0
            var y = spacing / 2
            while y < 192 {
                var x = spacing / 2 + (row % 2 == 0 ? 0 : spacing * 0.12)
                while x < 192 {
                    c.saveGState(); c.translateBy(x: x, y: y)
                    c.rotate(by: UnyriSavedChatStyle.patternRotation * .pi / 180)
                    if UnyriSavedChatStyle.pattern == "dots" {
                        let radius = 1.1 + UnyriSavedChatStyle.patternStroke * 0.35
                        c.setFillColor(UIColor(red: 119 / 255, green: 119 / 255, blue: 119 / 255, alpha: 1).cgColor)
                        c.fillEllipse(in: CGRect(x: -radius, y: -radius, width: radius * 2, height: radius * 2))
                    } else {
                        switch index % 6 {
                        case 0:
                            c.addPath(UIBezierPath(roundedRect: CGRect(x: -10, y: -7, width: 20, height: 14), cornerRadius: 3).cgPath)
                            c.move(to: CGPoint(x: -5, y: 7)); c.addLine(to: CGPoint(x: -5, y: 11)); c.addLine(to: CGPoint(x: 0, y: 7))
                        case 1, 5: c.addEllipse(in: CGRect(x: -9, y: -9, width: 18, height: 18))
                        case 2:
                            c.move(to: CGPoint(x: 0, y: -9))
                            c.addQuadCurve(to: CGPoint(x: 9, y: 0), control: CGPoint(x: 2, y: -2))
                            c.addQuadCurve(to: CGPoint(x: 0, y: 9), control: CGPoint(x: 2, y: 2))
                            c.addQuadCurve(to: CGPoint(x: -9, y: 0), control: CGPoint(x: -2, y: 2))
                            c.addQuadCurve(to: CGPoint(x: 0, y: -9), control: CGPoint(x: -2, y: -2))
                        case 3:
                            c.move(to: CGPoint(x: 0, y: -9)); c.addLine(to: CGPoint(x: 9, y: 0)); c.addLine(to: CGPoint(x: 0, y: 9)); c.addLine(to: CGPoint(x: -9, y: 0)); c.closePath()
                        default:
                            c.move(to: CGPoint(x: -4, y: 0)); c.addLine(to: CGPoint(x: 4, y: 0))
                            c.move(to: CGPoint(x: 0, y: -4)); c.addLine(to: CGPoint(x: 0, y: 4))
                        }
                        c.strokePath()
                        index += 1
                    }
                    c.restoreGState(); x += spacing
                }
                y += spacing; row += 1
            }
        }
        guard let pixels = raster.cgImage else { return raster }
        // 384 source pixels / 96 points = scale 4, independent of display density.
        return UIImage(cgImage: pixels, scale: CGFloat(pixels.width) / max(3, UnyriSavedChatStyle.patternScale * 3), orientation: .up)
    }()
}
