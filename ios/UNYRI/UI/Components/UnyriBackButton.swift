import SwiftUI
import UIKit

/// One circular Back visual. The existing caller owns navigation and guards.
struct UnyriBackButton: View {
    let action: () -> Void
    var enabled = true
    var label: LocalizedStringKey = "Back"
    var tint: Color = Color(hex: 0xF5F5F5)
    var fill: Color = Color(hex: 0x242424)
    var pressedFill: Color = Color(hex: 0x414141)

    var body: some View {
        Button(action: action) { UnyriBackGlyph(tint: tint) }
            .buttonStyle(UnyriBackButtonStyle(fill: fill, pressedFill: pressedFill))
            .disabled(!enabled)
            .accessibilityLabel(Text(label))
    }
}

/// Menu rows reuse the arrow without changing their native full-width layout.
struct UnyriBackGlyph: View {
    @Environment(\.layoutDirection) private var direction
    var tint: Color = Color(hex: 0xF5F5F5)
    var body: some View {
        UnyriBackArrow().stroke(tint, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            .frame(width: 25, height: 25)
            .scaleEffect(x: direction == .rightToLeft ? -1 : 1, y: 1)
    }
}

/// A custom UNYRI Back owns its own circle. Hide only this toolbar
/// item's additional system wrapper on iOS 26, leaving Save/other items native.
struct UnyriBackToolbarItem: ToolbarContent {
    let action: () -> Void
    var enabled = true
    var label: LocalizedStringKey = "Back"
    var identifier = ""

    @ToolbarContentBuilder
    var body: some ToolbarContent {
        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .navigationBarLeading) { button }
                .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .navigationBarLeading) { button }
        }
    }
    private var button: some View {
        UnyriBackButton(action: action, enabled: enabled, label: label)
            .accessibilityIdentifier(identifier)
    }
}

private struct UnyriBackButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var direction
    @Environment(\.isEnabled) private var enabled
    let fill: Color
    let pressedFill: Color

    func makeBody(configuration: Configuration) -> some View {
        let pressed = enabled && configuration.isPressed
        let motion = pressed && !reduceMotion
        return configuration.label
            .offset(x: motion ? (direction == .rightToLeft ? 3 : -3) : 0)
            .opacity(enabled ? 1 : 0.4)
            .frame(width: 48, height: 48)
            .background {
                if #available(iOS 26.0, *) {
                    Circle().fill(.clear).frame(width: 44, height: 44)
                        .glassEffect(.regular, in: Circle())
                        .scaleEffect(motion ? 0.94 : 1)
                } else {
                    Circle().fill(pressed ? pressedFill : fill).frame(width: 40, height: 40)
                        .scaleEffect(motion ? 0.94 : 1)
                }
            }
            .contentShape(Rectangle())
            .animation(reduceMotion ? nil : .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.18), value: pressed)
    }
}

private struct UnyriBackArrow: Shape {
    func path(in rect: CGRect) -> Path {
        let sx = rect.width / 24, sy = rect.height / 24
        var path = Path()
        path.move(to: CGPoint(x: 19*sx, y: 12*sy)); path.addLine(to: CGPoint(x: 5*sx, y: 12*sy))
        path.move(to: CGPoint(x: 12*sx, y: 19*sy)); path.addLine(to: CGPoint(x: 5*sx, y: 12*sy)); path.addLine(to: CGPoint(x: 12*sx, y: 5*sy))
        return path
    }
}

/// Styles only the navigation controller containing this UNYRI screen. UIKit
/// still owns the Back action, its history menu and its interactive edge pop.
struct UnyriNativeBackAppearance: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UnyriNativeBackAnchor { UnyriNativeBackAnchor() }
    func updateUIViewController(_ controller: UnyriNativeBackAnchor, context: Context) {
        // Body/text updates do not render images or reapply appearance.
    }
    static func dismantleUIViewController(_ controller: UnyriNativeBackAnchor, coordinator: ()) { controller.retire() }
}

@MainActor
final class UnyriNativeBackAnchor: UIViewController {
    private let token = UUID()
    private var retired = false
    private weak var navigation: UINavigationController?
    private var session: UnyriNativeBackSession?

    override func loadView() {
        let anchor = UIView(); anchor.backgroundColor = .clear; anchor.isUserInteractionEnabled = false
        view = anchor
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); attach()
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated); attach()
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard previousTraitCollection?.displayScale != traitCollection.displayScale else { return }
        attach(refresh: true)
    }
    private func attach(refresh: Bool = false) {
        guard !retired, viewIfLoaded?.window != nil else { return }
        var responder: UIResponder? = self
        var controller: UINavigationController?
        while let current = responder {
            if let value = current as? UIViewController, let nav = value.navigationController { controller = nav; break }
            responder = current.next
        }
        guard let controller else { return }
        if navigation !== controller {
            session?.release(token); navigation = controller
            session = UnyriNativeBackSession.acquire(controller, token: token)
        }
        if refresh || controller.topViewController.map({ view.isDescendant(of: $0.view) }) == true {
            session?.refresh(top: controller.topViewController, scale: view.window?.screen.scale ?? traitCollection.displayScale)
        }
    }
    func retire() {
        guard !retired else { return }; retired = true
        let owned = session; session = nil
        if let transition = navigation?.transitionCoordinator {
            transition.animate(alongsideTransition: nil) { [token] _ in owned?.release(token) }
        } else { owned?.release(token) }
        navigation = nil
    }
}

@MainActor
private final class UnyriNativeBackSession {
    private static let sessions = NSMapTable<UINavigationController, UnyriNativeBackSession>(keyOptions: .weakMemory, valueOptions: .strongMemory)
    private static var images: [CGFloat: UIImage] = [:]
    private weak var navigation: UINavigationController?
    private var owners = Set<UUID>()
    private var generation = 0
    private var image: UIImage?
    private struct Original {
        let image: UIImage
        let mask: UIImage
    }
    private enum Slot: CaseIterable { case standard, compact, scrollEdge, compactScrollEdge }
    private var barOriginals: [Slot: Original] = [:]
    private final class ItemOriginals {
        weak var item: UINavigationItem?
        let mode: UINavigationItem.BackButtonDisplayMode
        var appearances: [Slot: Original] = [:]
        init(_ item: UINavigationItem) { self.item = item; mode = item.backButtonDisplayMode }
    }
    private var items: [ObjectIdentifier: ItemOriginals] = [:]

    private init(_ controller: UINavigationController) { navigation = controller }
    static func acquire(_ controller: UINavigationController, token: UUID) -> UnyriNativeBackSession {
        let session = sessions.object(forKey: controller) ?? UnyriNativeBackSession(controller)
        sessions.setObject(session, forKey: controller)
        session.generation += 1; session.owners.insert(token)
        return session
    }
    func release(_ token: UUID) {
        guard owners.remove(token) != nil else { return }
        generation += 1; let expected = generation
        // Coalesce lifecycle handoff. An outgoing anchor cannot undo a style
        // already leased by the incoming UNYRI screen on the same controller.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.generation == expected, self.owners.isEmpty else { return }
            self.restore()
            if let controller = self.navigation, Self.sessions.object(forKey: controller) === self {
                Self.sessions.removeObject(forKey: controller)
            }
        }
    }
    func refresh(top: UIViewController?, scale: CGFloat) {
        guard let controller = navigation else { return }
        let previous = image, next = Self.indicator(scale: max(1, scale)); image = next
        for slot in Slot.allCases {
            if let current = barAppearance(controller.navigationBar, slot), current.backIndicatorImage !== next {
                if current.backIndicatorImage !== previous {
                    barOriginals[slot] = Original(image: current.backIndicatorImage, mask: current.backIndicatorTransitionMaskImage)
                }
                let styled = UINavigationBarAppearance(barAppearance: current)
                styled.setBackIndicatorImage(next, transitionMaskImage: next)
                setBarAppearance(controller.navigationBar, slot, styled)
            }
        }
        guard let top, top.navigationController === controller else { return }
        items = items.filter { $0.value.item != nil }
        let item = top.navigationItem, key = ObjectIdentifier(top.navigationItem)
        let saved = items[key].flatMap { $0.item === item ? $0 : nil } ?? ItemOriginals(item)
        items[key] = saved
        if item.backBarButtonItem == nil { item.backButtonDisplayMode = .minimal }
        for slot in Slot.allCases {
            if let current = itemAppearance(item, slot), current.backIndicatorImage !== next {
                if current.backIndicatorImage !== previous {
                    saved.appearances[slot] = Original(image: current.backIndicatorImage, mask: current.backIndicatorTransitionMaskImage)
                }
                let styled = UINavigationBarAppearance(barAppearance: current)
                styled.setBackIndicatorImage(next, transitionMaskImage: next)
                setItemAppearance(item, slot, styled)
            }
        }
    }
    private func restore() {
        guard let controller = navigation, let image else { return }
        for (slot, original) in barOriginals {
            guard let current = barAppearance(controller.navigationBar, slot), current.backIndicatorImage === image else { continue }
            let restored = UINavigationBarAppearance(barAppearance: current)
            restored.setBackIndicatorImage(original.image, transitionMaskImage: original.mask)
            setBarAppearance(controller.navigationBar, slot, restored)
        }
        for saved in items.values {
            guard let item = saved.item else { continue }
            if item.backBarButtonItem == nil && item.backButtonDisplayMode == .minimal { item.backButtonDisplayMode = saved.mode }
            for (slot, original) in saved.appearances {
                guard let current = itemAppearance(item, slot), current.backIndicatorImage === image else { continue }
                let restored = UINavigationBarAppearance(barAppearance: current)
                restored.setBackIndicatorImage(original.image, transitionMaskImage: original.mask)
                setItemAppearance(item, slot, restored)
            }
        }
    }
    private static func indicator(scale: CGFloat) -> UIImage {
        if let saved = images[scale] { return saved }
        let format = UIGraphicsImageRendererFormat(); format.scale = scale; format.opaque = false
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40), format: format).image { renderer in
            let context = renderer.cgContext
            // iOS 26 owns one native 44-point glass background. The indicator
            // canvas remains transparent, without a second embedded plate.
            if #unavailable(iOS 26.0) {
                context.addEllipse(in: CGRect(x: 0, y: 0, width: 40, height: 40))
                context.setFillColor(UIColor(white: 36 / 255, alpha: 1).cgColor); context.fillPath()
            }
            context.saveGState(); context.translateBy(x: 7.5, y: 7.5)
            context.addPath(UnyriBackArrow().path(in: CGRect(x: 0, y: 0, width: 25, height: 25)).cgPath)
            context.setStrokeColor(UIColor(white: 245 / 255, alpha: 1).cgColor)
            context.setLineWidth(2); context.setLineCap(.round); context.setLineJoin(.round); context.strokePath(); context.restoreGState()
        }.withRenderingMode(.alwaysOriginal).imageFlippedForRightToLeftLayoutDirection()
        images[scale] = image; return image
    }
    private func barAppearance(_ bar: UINavigationBar, _ slot: Slot) -> UINavigationBarAppearance? {
        switch slot { case .standard: return bar.standardAppearance; case .compact: return bar.compactAppearance; case .scrollEdge: return bar.scrollEdgeAppearance; case .compactScrollEdge: return bar.compactScrollEdgeAppearance }
    }
    private func setBarAppearance(_ bar: UINavigationBar, _ slot: Slot, _ value: UINavigationBarAppearance) {
        switch slot { case .standard: bar.standardAppearance = value; case .compact: bar.compactAppearance = value; case .scrollEdge: bar.scrollEdgeAppearance = value; case .compactScrollEdge: bar.compactScrollEdgeAppearance = value }
    }
    private func itemAppearance(_ item: UINavigationItem, _ slot: Slot) -> UINavigationBarAppearance? {
        switch slot { case .standard: return item.standardAppearance; case .compact: return item.compactAppearance; case .scrollEdge: return item.scrollEdgeAppearance; case .compactScrollEdge: return item.compactScrollEdgeAppearance }
    }
    private func setItemAppearance(_ item: UINavigationItem, _ slot: Slot, _ value: UINavigationBarAppearance) {
        switch slot { case .standard: item.standardAppearance = value; case .compact: item.compactAppearance = value; case .scrollEdge: item.scrollEdgeAppearance = value; case .compactScrollEdge: item.compactScrollEdgeAppearance = value }
    }
}
