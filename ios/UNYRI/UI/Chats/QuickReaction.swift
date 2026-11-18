import SwiftUI
import UIKit

private struct UnyriMessagePreviewKey: EnvironmentKey { static let defaultValue = false }
@MainActor final class UnyriMessagePreviewState {
    var downloading = false
    var downloadFailed = false
    // Owned by this message row, not another global image cache. Reuse the
    // already decoded pixels when the menu creates a fresh SwiftUI subtree.
    // The visible row/thumbnail owns the pixels. Do not bypass the global
    // memory budget by retaining every image in long-lived lazy row state.
    private final class MediaImage {
        weak var image: UIImage?
        init(_ image: UIImage) { self.image = image }
    }
    private var mediaImages: [String: MediaImage] = [:]
    private var mediaGeneration: UInt64 = ConversationImages.generation
    func retainMediaImage(_ image: UIImage, attachmentID: String) {
        resetMediaIfNeeded()
        mediaImages[attachmentID] = MediaImage(image)
    }
    func mediaImage(attachmentID: String) -> UIImage? {
        resetMediaIfNeeded()
        return mediaImages[attachmentID]?.image
    }
    private func resetMediaIfNeeded() {
        guard mediaGeneration != ConversationImages.generation else { return }
        mediaImages.removeAll()
        mediaGeneration = ConversationImages.generation
    }
}
private struct UnyriMessagePreviewStateKey: EnvironmentKey { static let defaultValue: UnyriMessagePreviewState? = nil }
extension EnvironmentValues {
    var unyriMessagePreview: Bool {
        get { self[UnyriMessagePreviewKey.self] }
        set { self[UnyriMessagePreviewKey.self] = newValue }
    }
    var unyriMessagePreviewState: UnyriMessagePreviewState? {
        get { self[UnyriMessagePreviewStateKey.self] }
        set { self[UnyriMessagePreviewStateKey.self] = newValue }
    }
}

private struct UnyriMessageQuickReactionKey: EnvironmentKey {
    static let defaultValue: (() -> Void)? = nil
}
extension EnvironmentValues {
    var unyriMessageQuickReaction: (() -> Void)? {
        get { self[UnyriMessageQuickReactionKey.self] }
        set { self[UnyriMessageQuickReactionKey.self] = newValue }
    }
}

struct UnyriQuickReactionSettingsView: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    @AppStorage("quickReaction") private var selected = "👍"
    var body: some View {
        UnyriSettingsPage("Quick reaction") {
            UnyriSettingsGroup {
                ForEach(UnyriQuickReactionPolicy.choices + [""], id: \.self) { emoji in
                    Button { selected = emoji } label: {
                        HStack(spacing: 12) {
                            if emoji.isEmpty { Text("Off").font(.subheadline) }
                            else { Text(emoji).font(.system(size: 25)) }
                            Spacer(minLength: 8)
                            Image(systemName: selected == emoji ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 20))
                                .foregroundColor(selected == emoji ? colorStyle.role(.categoryMint) : UnyriColors.veryMutedText)
                                .accessibilityHidden(true)
                        }.padding(.horizontal, 16).frame(minHeight: 50).contentShape(Rectangle())
                    }.accessibilityAddTraits(selected == emoji ? .isSelected : [])
                    if !emoji.isEmpty { UnyriSettingsDivider() }
                }
            }
            UnyriSettingsNote("Double-tap a message to react. In the photo viewer, double-tap still zooms.")
        }
            .onAppear { selected = UnyriQuickReactionPolicy.normalize(selected) }
    }
}

struct UnyriMessageDoubleTap: ViewModifier {
    let action: (() -> Void)?
    @ViewBuilder func body(content: Content) -> some View {
        if let action { content.onTapGesture(count: 2) { if UnyriMessageMenuPresenter.canActivateMessage { action() } } }
        else { content }
    }
}

/// Single and double taps are exclusive: opening/playing waits for the double-tap decision.
/// Context menus keep their native long-press recognizer; no timers or global touch interceptors.
struct UnyriMessageTap: ViewModifier {
    @Environment(\.unyriMessageQuickReaction) private var quick
    @Environment(\.isEnabled) private var enabled
    let single: () -> Void
    /// Playback surfaces (a round video note) react on the first tap instead of waiting
    /// out the double-tap window of the quick reaction; react from the menu instead.
    let immediate: Bool
    init(single: @escaping () -> Void, immediate: Bool = false) {
        self.single = single
        self.immediate = immediate
    }
    @ViewBuilder func body(content: Content) -> some View {
        if let quick, !immediate {
            content.contentShape(Rectangle()).gesture(
                TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { result in
                    guard enabled, UnyriMessageMenuPresenter.canActivateMessage else { return }
                    switch result {
                    case .first: quick()
                    case .second: single()
                    }
                })
        } else { content.contentShape(Rectangle()).onTapGesture { if enabled && UnyriMessageMenuPresenter.canActivateMessage { single() } } }
    }
}

struct UnyriMessageActionButton<Label: View>: View {
    @Environment(\.unyriMessageQuickReaction) private var quick
    @Environment(\.isEnabled) private var enabled
    let immediate: Bool
    let action: () -> Void
    let label: () -> Label
    init(immediate: Bool = false, action: @escaping () -> Void, @ViewBuilder label: @escaping () -> Label) {
        self.immediate = immediate
        self.action = action
        self.label = label
    }
    var body: some View {
        // Explicit playback controls must not wait for a possible reaction tap.
        if quick != nil && !immediate {
            label().frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                .modifier(UnyriMessageTap(single: action))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { if enabled && UnyriMessageMenuPresenter.canActivateMessage { action() } }
        } else {
            Button { if UnyriMessageMenuPresenter.canActivateMessage { action() } } label: {
                label().frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            }
        }
    }
}

struct UnyriMessageSelectionTap: ViewModifier {
    let action: (() -> Void)?
    @ViewBuilder func body(content: Content) -> some View {
        if let action { content.contentShape(Rectangle()).onTapGesture(perform: action) }
        else { content }
    }
}


// The menu renders the same isolated message surface, never a crop of the window.
struct UnyriMessageMenuAction: Identifiable {
    let id: String
    let title: String
    let symbol: String
    var destructive = false
    var confirmationMessage: String? = nil
    let perform: () -> Void
}

/// One bounded layout for portrait, keyboard, landscape and large text.
struct UnyriMessageMenuLayout {
    let reactionWidth: CGFloat
    let columns: Int
    let showReactions: Bool
    let showReactionShortcut: Bool
    let previewSize: CGSize
    let menuWidth: CGFloat
    let menuHeight: CGFloat
    let width: CGFloat
    let origin: CGPoint
    let totalHeight: CGFloat

    init(viewport: CGSize, anchor: CGRect, top: CGFloat, bottom: CGFloat, left: CGFloat, right: CGFloat,
         outgoing: Bool, rows: Int, rowHeight: CGFloat, reactionCount: Int, allowReactions: Bool) {
        let safeTop = top + 8
        let available = max(0, viewport.height - safeTop - bottom - 8)
        let availableWidth = max(1, viewport.width - left - right - 32)
        reactionWidth = min(288, availableWidth)
        columns = reactionWidth >= 264 ? 6 : 3
        let reactionRows = (reactionCount + columns - 1) / columns
        let naturalReactions = CGFloat(reactionRows * 44 + 8)
        showReactions = allowReactions && reactionCount > 0 && available >= 180 && naturalReactions <= available * 0.3
        showReactionShortcut = allowReactions && reactionCount > 0 && !showReactions
        let reactionHeight = showReactions ? naturalReactions : 0
        menuWidth = min(260, availableWidth)
        let desiredMenu = CGFloat(max(1, rows + (showReactionShortcut ? 1 : 0))) * rowHeight + 8
        let menuReserve = min(max(0, available - reactionHeight), max(44, min(desiredMenu, available * 0.55)))
        let previewBudget = available >= 140 ? max(0, min(220, available - reactionHeight - menuReserve - 16)) : 0
        let scale = min(1, min(availableWidth / max(1, anchor.width), previewBudget / max(1, anchor.height)))
        previewSize = CGSize(width: anchor.width * scale, height: anchor.height * scale)
        let gaps = CGFloat((showReactions ? 1 : 0) + (previewSize.height > 0 ? 1 : 0)) * 8
        menuHeight = min(desiredMenu, max(0, available - reactionHeight - previewSize.height - gaps))
        width = max(menuWidth, max(previewSize.width, showReactions ? reactionWidth : 0))
        totalHeight = menuHeight + reactionHeight + previewSize.height + gaps
        let x = max(left + 16, min(outgoing ? anchor.maxX - width : anchor.minX, viewport.width - right - width - 16))
        let y = max(safeTop, min(anchor.minY - reactionHeight - 8, viewport.height - bottom - 8 - totalHeight))
        origin = CGPoint(x: x, y: y)
    }
}

enum UnyriMessageMenuPreviewGeometry {
    static func size(proposed: CGSize?, source: CGSize) -> CGSize {
        guard let proposed, proposed.width.isFinite, proposed.height.isFinite,
              proposed.width > 0, proposed.height > 0 else { return source }
        return proposed
    }
}

@MainActor
final class UnyriMessageMenuPresenter: ObservableObject {
    private static weak var active: UnyriMessageMenuPresenter?
    private static var ignoreTapsUntil: TimeInterval = 0
    static var canActivateMessage: Bool { active?.overlay == nil && CACurrentMediaTime() >= ignoreTapsUntil }
    var canOpen: Bool { Self.active?.overlay == nil }
    var lastTouchWasReaction: Bool { (anchor as? UnyriMessageMenuAnchorView)?.lastTouchWasReaction == true }
    weak var anchor: UIView?
    private weak var overlay: UIViewController?
    private var reactionSelected = false
    let reactionTargets = NSMapTable<NSString, UIView>(keyOptions: .strongMemory, valueOptions: .weakMemory)
    /// The open menu's emoji cells: a picked emoji's flyer starts where its cell was.
    let menuReactionCells = NSMapTable<NSString, UIView>.strongToWeakObjects()
    private var pendingReactionOrigin: UnyriReactionBursts.Origin?
    /// The picked cell's spot, once, for the bubble's react closure (nil from the extended picker).
    func takeReactionOrigin() -> UnyriReactionBursts.Origin? {
        defer { pendingReactionOrigin = nil }
        return pendingReactionOrigin
    }

    func show(primary: [UnyriMessageMenuAction], extra: [UnyriMessageMenuAction],
              deletion: [UnyriMessageMenuAction], reactions: [String], selectedReaction: String? = nil,
              react: @escaping (String) -> Void, outgoing: Bool) {
        guard canOpen, overlay == nil, let anchor, let window = anchor.window,
              var parent = window.rootViewController else { return }
        let sourceRect = anchor.convert(anchor.bounds, to: window)
        let previewSize = UnyriMessageMenuPreviewGeometry.size(proposed: (anchor as? UnyriMessageMenuAnchorView)?.previewSize, source: sourceRect.size)
        var rect = sourceRect.intersection(window.bounds)
        // Only an anchor intersecting the unobscured scroll viewport may open.
        var ancestor = anchor.superview
        while let view = ancestor {
            if let scroll = view as? UIScrollView {
                let visible = scroll.bounds.inset(by: scroll.adjustedContentInset)
                rect = rect.intersection(scroll.convert(visible, to: window))
            }
            ancestor = view.superview
        }
        guard !rect.isNull, rect.width > 1, rect.height > 1 else { return }
        guard let preview = (anchor as? UnyriMessageMenuAnchorView)?.resolvedPreview else { return }
        while let next = parent.presentedViewController { parent = next }
        guard !parent.isBeingDismissed else { return }
        let keyboardFrame = window.keyboardLayoutGuide.layoutFrame
        let keyboardInset = keyboardFrame.height > 0 && keyboardFrame.minY > 0
            ? max(window.safeAreaInsets.bottom, window.bounds.maxY - keyboardFrame.minY) : window.safeAreaInsets.bottom
        menuReactionCells.removeAllObjects()
        let host = UIHostingController(rootView: UnyriMessageMenuOverlay(
            preview: preview, anchor: CGRect(origin: rect.origin, size: previewSize),
            insets: UIEdgeInsets(top: window.safeAreaInsets.top, left: window.safeAreaInsets.left,
                                 bottom: keyboardInset,
                                 right: window.safeAreaInsets.right), outgoing: outgoing,
            primary: primary, extra: extra, deletion: deletion, reactions: reactions, selectedReaction: selectedReaction,
            cells: menuReactionCells,
            choose: { [weak self] action in self?.dismiss(then: action) },
            react: { [weak self] emoji in
                guard let self, self.overlay != nil, !self.reactionSelected else { return }
                self.reactionSelected = true
                // A strip cell still on screen is where the flyer starts; the pressed glyph
                // is the 30 pt emoji scaled 1.35 by UnyriReactionItemButtonStyle.
                if let cell = self.menuReactionCells.object(forKey: emoji as NSString), let cellWindow = cell.window {
                    self.pendingReactionOrigin = UnyriReactionBursts.Origin(window: cellWindow,
                        point: cell.convert(CGPoint(x: cell.bounds.midX, y: cell.bounds.midY), to: nil), glyph: 30 * 1.35, source: .menu)
                } else { self.pendingReactionOrigin = nil }
                // Commit the durable reaction immediately. UIKit dismissal is
                // presentation cleanup, not the owner of the user's selection.
                react(emoji)
                self.dismiss()
            },
            close: { [weak self] in self?.dismiss() })
                .unyriLocale()
                .preferredColorScheme(.dark))
        host.modalPresentationStyle = .overFullScreen
        host.view.backgroundColor = .clear
        host.view.accessibilityViewIsModal = true
        // This independently presented host must keep UNYRI's dark material,
        // including when the source UIKit trait is temporarily unspecified.
        host.overrideUserInterfaceStyle = .dark
        reactionSelected = false
        overlay = host
        Self.active = self
        Self.ignoreTapsUntil = CACurrentMediaTime() + 0.2
        UnyriHaptics.longPress()
        parent.present(host, animated: false)
    }

    func dismiss(then action: (() -> Void)? = nil) {
        guard let overlay else { return }
        self.overlay = nil
        if Self.active === self { Self.active = nil }
        Self.ignoreTapsUntil = CACurrentMediaTime() + 0.2
        overlay.dismiss(animated: false, completion: action)
    }
}

struct UnyriMessageMenuAnchor: UIViewRepresentable {
    let presenter: UnyriMessageMenuPresenter
    let open: () -> Void
    var enabled = true
    var preview: AnyView? = nil
    /// Built only when the menu opens; building the preview on every row pass doubled
    /// the cost of each bubble while scrolling.
    var previewBuilder: (() -> AnyView)? = nil
    var previewSize: CGSize? = nil
    func makeUIView(context: Context) -> UnyriMessageMenuAnchorView {
        let view = UnyriMessageMenuAnchorView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.presenter = presenter
        presenter.anchor = view
        view.open = open
        view.menuEnabled = enabled
        view.previewSize = previewSize
        configurePreview(view, context: context)
        return view
    }
    func updateUIView(_ view: UnyriMessageMenuAnchorView, context: Context) {
        view.open = open; view.menuEnabled = enabled; view.previewSize = previewSize
        configurePreview(view, context: context)
    }
    private func configurePreview(_ view: UnyriMessageMenuAnchorView, context: Context) {
        var environment = context.environment
        environment.unyriMessagePreview = true
        // The menu is hosted outside the cell. Apply the SAME live bridge inside
        // the captured environment, so activation/background changes still reach it.
        func hosted(_ content: AnyView) -> AnyView {
            if let scene = environment.chatHostedScene {
                return AnyView(content.modifier(ChatHostedLivePhase(scene: scene)).environment(\.self, environment))
            }
            return AnyView(content.environment(\.self, environment))
        }
        if let previewBuilder {
            view.preview = nil
            view.previewFactory = { hosted(previewBuilder()) }
        } else {
            view.previewFactory = nil
            view.preview = preview.map { hosted($0) }
        }
    }
    static func dismantleUIView(_ view: UnyriMessageMenuAnchorView, coordinator: ()) {
        view.presenter?.dismiss()
        view.detach()
    }
}

/// One long-press recognizer per window for every message bubble (timeline audit
/// P1-4). Each bubble used to add and remove its own window recognizer as rows
/// scrolled in and out, and every touch was offered to dozens of them.
final class UnyriMessageMenuHub: NSObject, UIGestureRecognizerDelegate {
    private static let hubs = NSMapTable<UIWindow, UnyriMessageMenuHub>.weakToStrongObjects()
    static func hub(for window: UIWindow) -> UnyriMessageMenuHub {
        if let hub = hubs.object(forKey: window) { return hub }
        let hub = UnyriMessageMenuHub(window: window)
        hubs.setObject(hub, forKey: window)
        return hub
    }
    private weak var window: UIWindow?
    private let anchors = NSHashTable<UnyriMessageMenuAnchorView>.weakObjects()
    private weak var target: UnyriMessageMenuAnchorView?
    private lazy var press: UILongPressGestureRecognizer = {
        let gesture = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        gesture.minimumPressDuration = 0.35
        gesture.allowableMovement = 10
        gesture.cancelsTouchesInView = false
        gesture.delaysTouchesBegan = false
        gesture.delegate = self
        return gesture
    }()
    private init(window: UIWindow) {
        self.window = window
        super.init()
        window.addGestureRecognizer(press)
    }
    func register(_ anchor: UnyriMessageMenuAnchorView) { anchors.add(anchor) }
    func unregister(_ anchor: UnyriMessageMenuAnchorView) {
        anchors.remove(anchor)
        if target === anchor { target = nil }
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // A second finger during a press must not retarget or cancel the first.
        if press.state == .began || press.state == .changed || (target != nil && press.state == .possible && press.numberOfTouches > 0) { return false }
        target = nil
        // Innermost match wins (a bubble inside an album row).
        var best: UnyriMessageMenuAnchorView?
        for anchor in anchors.allObjects where anchor.window === window && anchor.accepts(touch) {
            if let current = best, current.bounds.width * current.bounds.height <= anchor.bounds.width * anchor.bounds.height { continue }
            best = anchor
        }
        guard let best, best.prepare(for: touch) else { return false }
        target = best
        return true
    }
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        target?.mayBegin() ?? false
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        otherGestureRecognizer is UIPanGestureRecognizer
    }
    @objc private func held(_ gesture: UILongPressGestureRecognizer) {
        if gesture.state == .began { target?.fire() }
        if gesture.state == .ended || gesture.state == .cancelled || gesture.state == .failed { target = nil }
    }
}

final class UnyriMessageMenuAnchorView: UIView {
    weak var presenter: UnyriMessageMenuPresenter?
    var open: (() -> Void)?
    var menuEnabled = true
    var preview: AnyView?
    var previewFactory: (() -> AnyView)?
    var resolvedPreview: AnyView? { preview ?? previewFactory?() }
    var previewSize: CGSize?
    var lastTouchPoint: CGPoint?
    var lastTouchWasReaction = false
    private weak var hub: UnyriMessageMenuHub?
    private weak var cachedController: UIViewController?
    private weak var cachedScrollView: UIScrollView?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        detach()
        cachedController = nil
        cachedScrollView = nil
        if let window {
            let hub = UnyriMessageMenuHub.hub(for: window)
            hub.register(self)
            self.hub = hub
        }
    }
    func detach() { hub?.unregister(self); hub = nil }
    private func owningController() -> UIViewController? {
        if let cachedController { return cachedController }
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController {
                cachedController = controller
                return controller
            }
            responder = current.next
        }
        return nil
    }
    private func owningScrollView() -> UIScrollView? {
        if let cachedScrollView { return cachedScrollView }
        var parent = superview
        while let view = parent {
            if let scroll = view as? UIScrollView {
                cachedScrollView = scroll
                return scroll
            }
            parent = view.superview
        }
        return nil
    }
    /// Cheap geometric test used by the hub to pick the bubble under the finger.
    func accepts(_ touch: UITouch) -> Bool {
        menuEnabled && window != nil && presenter?.canOpen == true && bounds.contains(touch.location(in: self))
    }
    /// Same checks as the former per-bubble recognizer's shouldReceive.
    func prepare(for touch: UITouch) -> Bool {
        guard let touched = touch.view else { return false }
        lastTouchPoint = touch.location(in: self)
        if let bar = presenter?.reactionTargets.object(forKey: "__reaction_bar") {
            lastTouchWasReaction = bar.bounds.insetBy(dx: -4, dy: -4).contains(touch.location(in: bar))
        } else { lastTouchWasReaction = false }
        // Let the native reaction button receive the first tap immediately;
        // neither a message long-press nor its double-tap owns this region.
        if lastTouchWasReaction { return false }
        // A window recognizer also sees touches in sheets presented over the chat.
        // Only the owning controller's content may open this message menu.
        if let controller = owningController() {
            return touched.isDescendant(of: controller.view)
        }
        return false
    }
    func mayBegin() -> Bool {
        guard menuEnabled, presenter?.canOpen == true else { return false }
        if let scroll = owningScrollView(), scroll.isDragging || scroll.isDecelerating { return false }
        return true
    }
    func fire() {
        guard menuEnabled else { return }
        open?()
    }
}

struct UnyriCallHistoryMenuRow: View {
    let call: CallDTO
    let owner: UUID?
    let earlierHistory: Bool
    let selecting: Bool
    let selected: Bool
    let onSelect: () -> Void
    let onDelete: (Bool) -> Void
    var callBack: (() -> Void)? = nil
    @StateObject private var menu = UnyriMessageMenuPresenter()
    var body: some View {
        CallHistoryRow(call: call, owner: owner, earlierHistory: earlierHistory, menuPresenter: menu, openMenu: open,
                       selection: selecting ? selected : nil, callBack: call.isEnded ? callBack : nil)
            .contentShape(Rectangle())
            .onTapGesture { if selecting && call.isEnded { onSelect() } }
    }
    private func open() {
        guard call.isEnded else { return }
        if selecting { onSelect(); return }
        menu.show(primary: [UnyriMessageMenuAction(id: "select", title: "Select", symbol: "checkmark.circle", perform: onSelect)], extra: [],
            deletion: [
                UnyriMessageMenuAction(id: "all", title: "Delete for everyone", symbol: "trash", destructive: true,
                    confirmationMessage: "This call record will be removed for both participants.", perform: { onDelete(true) }),
                UnyriMessageMenuAction(id: "me", title: "Delete for me", symbol: "trash", destructive: true,
                    confirmationMessage: "This call record will be removed from your account. The other participant will keep it.", perform: { onDelete(false) })
            ], reactions: [], react: { _ in }, outgoing: call.callerUserId == owner)
    }
}


private struct UnyriReactionItemButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 1.35 : 1.0)
            .animation(.spring(response: 0.22, dampingFraction: 0.55), value: configuration.isPressed)
    }
}

private struct UnyriMessageMenuOverlay: View {
    let preview: AnyView
    let anchor: CGRect
    let insets: UIEdgeInsets
    let outgoing: Bool
    let primary: [UnyriMessageMenuAction]
    let extra: [UnyriMessageMenuAction]
    let deletion: [UnyriMessageMenuAction]
    let reactions: [String]
    let selectedReaction: String?
    let cells: NSMapTable<NSString, UIView>
    let choose: (@escaping () -> Void) -> Void
    let react: (String) -> Void
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var page = 0
    @State private var extendedReactions = false
    @State private var reactionStripAppeared = false
    @State private var pendingDeletion: UnyriMessageMenuAction?
    @State private var alsoDelete = false
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 46

    var body: some View {
        GeometryReader { geometry in
            let rows = pendingDeletion != nil ? 7 : page == 0 ? primary.count + (extra.isEmpty ? 0 : 1) + (deletion.isEmpty ? 0 : 1) : 1 + (page == 1 ? extra.count : deletion.count)
            let layout = UnyriMessageMenuLayout(viewport: geometry.size, anchor: anchor,
                top: insets.top, bottom: insets.bottom, left: insets.left, right: insets.right,
                outgoing: outgoing, rows: rows, rowHeight: rowHeight, reactionCount: reactions.count, allowReactions: page != 2)
            ZStack(alignment: .topLeading) {
                UnyriChatMenuBackdrop(close: close)
                if !extendedReactions { VStack(alignment: outgoing ? .trailing : .leading, spacing: 8) {
                    if layout.showReactions {
                        reactionStrip(width: layout.reactionWidth, columns: layout.columns)
                            .scaleEffect(reactionStripAppeared || reduceMotion ? 1.0 : 0.72, anchor: outgoing ? .topTrailing : .topLeading)
                            .opacity(reactionStripAppeared || reduceMotion ? 1.0 : 0.0)
                            .zIndex(1)

                    }
                    if layout.previewSize.height > 0 {
                        Group {
                            preview.frame(width: anchor.width, height: anchor.height, alignment: .topLeading)
                                    .scaleEffect(layout.previewSize.width / max(1, anchor.width), anchor: .topLeading)
                                    .frame(width: layout.previewSize.width, height: layout.previewSize.height, alignment: .topLeading)
                        }.clipped().allowsHitTesting(false).accessibilityHidden(true)
                    }
                    ScrollView(showsIndicators: false) {
                        VStack(spacing: 0) {
                            if layout.showReactionShortcut {
                                navigationRow("React", symbol: "face.smiling") { extendedReactions = true }
                            }
                            if let pending = pendingDeletion {
                                navigationRow("Back", symbol: "chevron.left") { pendingDeletion = nil; alsoDelete = false; page = 0 }
                                Text(LocalizedStringKey(pending.confirmationMessage == nil ? "Delete message" : "Delete call record?")).font(.headline).frame(maxWidth: .infinity, alignment: .leading).padding(16)
                                if deletion.contains(where: { $0.id == "all" }) {
                                    Toggle("Also delete for the other participant", isOn: $alsoDelete).padding(.horizontal, 16).padding(.vertical, 8)
                                } else { Text("Only deleted for you").padding(16) }
                                navigationRow("Delete", symbol: "trash", destructive: true) {
                                    let action = alsoDelete ? deletion.first(where: { $0.id == "all" }) ?? pending : pending
                                    choose(action.perform)
                                }
                                navigationRow("Cancel", symbol: "xmark", action: close)
                            } else if page == 0 {
                                ForEach(primary) { row($0) }
                                if !extra.isEmpty { navigationRow("More options", symbol: "ellipsis") { page = 1 } }
                                if deletion.count == 1 { navigationRow(LocalizedStringKey(deletion[0].title), symbol: "trash", destructive: true) { page = 2; alsoDelete = false; pendingDeletion = deletion[0] } }
                                else if !deletion.isEmpty { navigationRow("Delete", symbol: "trash", destructive: true) { page = 2; alsoDelete = false; pendingDeletion = deletion.first(where: { $0.id == "me" }) ?? deletion.first } }
                            } else {
                                navigationRow("Back", symbol: "chevron.left") { page = 0 }
                                ForEach(page == 1 ? extra : deletion) { action in
                                    if page == 2 {
                                        navigationRow(LocalizedStringKey(action.title), symbol: action.symbol, destructive: true) { pendingDeletion = action }
                                    } else { row(action) }
                                }
                                if page == 2 { navigationRow("Cancel", symbol: "xmark", action: close) }
                            }
                        }.padding(.vertical, 4)
                    }.id("\(page)-\(pendingDeletion?.id ?? "")").frame(width: layout.menuWidth, height: layout.menuHeight)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
                }
                    .frame(width: layout.width, alignment: outgoing ? .trailing : .leading).offset(x: layout.origin.x, y: layout.origin.y)
                } else { UnyriEmojiPicker(embedded: true, close: { extendedReactions = false }) { emoji in react(emoji) } }
            }.frame(width: geometry.size.width, height: geometry.size.height)
        }.ignoresSafeArea().animation(reduceMotion ? nil : .easeInOut(duration: 0.16), value: extendedReactions)
        .onAppear {
            withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.72)) {
                reactionStripAppeared = true
            }
        }
            .accessibilityAction(.escape) {
                if pendingDeletion != nil { pendingDeletion = nil; alsoDelete = false; page = 0 }
                else if page != 0 { page = 0 }
                else { close() }
            }
    }
    private func reactionStrip(width: CGFloat, columns: Int) -> some View {
        let rowCount = (reactions.count + columns - 1) / columns
        let cellWidth = width / CGFloat(columns)
        return VStack(spacing: 0) {
            ForEach(0..<rowCount, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<min(columns, reactions.count - row * columns), id: \.self) { column in
                        let emoji = reactions[row * columns + column]
                        Button {
                            if emoji == "＋" {
                                UnyriHaptics.selection()
                                extendedReactions = true
                            } else {
                                react(emoji)
                            }
                        } label: {
                            ZStack {
                                if selectedReaction == emoji {
                                    Circle().fill(UnyriColors.primaryText.opacity(0.2)).frame(width: 38, height: 38)
                                }
                                if emoji == "＋" {
                                    VStack(spacing: 2) { Image(systemName: "square.grid.2x2.fill").font(.system(size: 18)); Text(UnyriL10n.language == "ru" ? "Ещё" : "More").font(.system(size: 10)) }.foregroundColor(UnyriColors.accentSoft)
                                } else { Text(emoji).font(.system(size: 30)) }
                            }.frame(width: cellWidth, height: 44).contentShape(Rectangle())
                                .background(UnyriMenuReactionCell(cells: cells, emoji: emoji))
                        }.buttonStyle(UnyriReactionItemButtonStyle())
                            .accessibilityAddTraits(selectedReaction == emoji ? [.isSelected] : [])
                    }
                }.frame(width: width, alignment: .leading)
            }
        }
    }
    private func row(_ action: UnyriMessageMenuAction) -> some View {
        // Reaction and selection callbacks own their feedback, including
        // direct bubble taps; menu navigation must not deliver it twice.
        navigationRow(LocalizedStringKey(action.title), symbol: action.symbol, destructive: action.destructive, feedback: action.id != "reaction" && action.id != "select") { choose(action.perform) }
    }
    private func navigationRow(_ title: LocalizedStringKey, symbol: String, destructive: Bool = false, feedback: Bool = true, action: @escaping () -> Void) -> some View {
        Button { if feedback { UnyriHaptics.selection() }; action() } label: {
            HStack(spacing: 12) {
                if symbol == "chevron.left" { UnyriBackGlyph(tint: .primary) }
                else { Image(systemName: symbol).font(.system(size: 19)).frame(width: 24) }
                Text(title).font(.body).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }.foregroundStyle(destructive ? Color.red : Color.primary)
                .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: rowHeight)
                .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}


// The custom attachment grid keeps capture and library selection in one place.
import Photos
import ImageIO
import AVFoundation
import AVKit

enum UnyriGallerySource { case asset(PHAsset); case capture(URL) }

/// What the attach sheet's main button does with the picks. Add (a tap) puts them in
/// the composer tray for review, the owner's decision of 12 Nov 2026; Send now (a long
/// press on Add) sends them at once, as the gallery arrow did since 116ca60a.
enum UnyriAttachmentCommit { case add, sendNow }

struct UnyriAttachmentGallery: View {
    let cameraEnabled: Bool
    let camera: () -> Void
    let library: () -> Void
    let files: () -> Void
    let selected: ([UnyriGallerySource], MediaSendIntent, UnyriAttachmentCommit) -> Void
    @Binding var selection: [String]
    let captures: [URL]
    var sendingAllowed = true
    var selectionLimit = 10
    @Binding var caption: String
    var close: () -> Void = {}
    /// Files tab: picked, scanned or ticked recent documents. They join the composer
    /// tray; only Send now (long press on Add) sends them right away.
    var documents: ([AttachmentFileCopy.DocumentSource], UnyriAttachmentCommit) -> Void = { _, _ in }
    /// The composer menu's "Files" opens the sheet on the Files tab.
    var startOnFiles = false
    private enum Tab { case gallery, files }
    @State private var chosenTab: Tab?
    private var tab: Tab {
        get { chosenTab ?? (startOnFiles ? .files : .gallery) }
        nonmutating set { chosenTab = newValue }
    }
    @State private var fileSelection: [String] = []
    @State private var recentFiles: [UnyriRecentFiles.Entry] = []
    @State private var mediaAsFiles = false
    @State private var pickingFiles = false
    @State private var scanning = false
    @Namespace private var tabSelection
    @AppStorage("mediaSendHighQuality") private var highQuality = false
    @AppStorage(MediaGallerySendChoice.preferenceKey) private var sendChoiceRaw = ""
    @State private var submitted = false
    @State private var unavailableSelection = false
    @Environment(\.scenePhase) private var phase
    @State private var assets: [PHAsset] = []
    @State private var preview: UnyriGalleryPreviewItem?
    @State private var showLimit = false
    @State private var limit = 300
    @State private var albums: [PHAssetCollection] = []
    @State private var album: PHAssetCollection?
    @State private var access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    private let columns = Array(repeating: GridItem(.flexible(), spacing: UnyriAttachmentPickerStyle.gap), count: UnyriAttachmentPickerStyle.columns)
    private var pickerInk: Color { Color(hex: UnyriAttachmentPickerStyle.foreground) }
    private var pickerAccent: Color { Color(hex: UnyriAttachmentPickerStyle.accent) }
    private var sendChoice: MediaGallerySendChoice { mediaAsFiles ? .original : .resolve(sendChoiceRaw, legacyHighQuality: highQuality) }
    private var activeCount: Int { tab == .files ? fileSelection.count : selection.count }
    private func modeTitle(_ mode: MediaGallerySendChoice) -> String {
        switch mode {
        case .standard: return "Standard quality"
        case .high: return "High"
        case .original: return "Original"
        }
    }
    private func modeDetail(_ mode: MediaGallerySendChoice) -> String {
        switch mode {
        case .standard: return "Smaller size, faster sending"
        case .high: return "More detail, larger size"
        case .original: return "As files, without additional compression"
        }
    }
    private var sendModeMenu: some View {
        Menu {
            ForEach(MediaGallerySendChoice.allCases, id: \.rawValue) { mode in
                Button {
                    UnyriHaptics.selection()
                    mediaAsFiles = false
                    sendChoiceRaw = mode.rawValue
                    mode.save()
                } label: {
                    VStack(alignment: .leading) {
                        Text(LocalizedStringKey(modeTitle(mode)))
                        Text(LocalizedStringKey(modeDetail(mode)))
                    }
                    if sendChoice == mode { Image(systemName: "checkmark") }
                }.accessibilityIdentifier("gallery-mode-\(mode.rawValue)")
            }
            Divider()
            Button("Clear selection") { UnyriHaptics.selection(); selection.removeAll() }.disabled(selection.isEmpty)
        } label: {
            HStack(spacing: 7) {
                Image(systemName: sendChoice == .original ? "doc" : "photo.on.rectangle")
                Text(LocalizedStringKey(modeTitle(sendChoice))).fixedSize(horizontal: false, vertical: true)
                Image(systemName: "chevron.down").font(.caption.weight(.semibold))
            }
            .font(.subheadline.weight(.medium)).foregroundStyle(pickerAccent)
            .padding(.horizontal, 11).frame(minHeight: 44)
            .background(pickerInk.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
        }
        .accessibilityLabel(Text("Quality:"))
        .accessibilityValue(Text(LocalizedStringKey(modeTitle(sendChoice))))
        .accessibilityIdentifier("gallery-send-options")
    }
    private var tileShape: RoundedRectangle { RoundedRectangle(cornerRadius: UnyriAttachmentPickerStyle.tileRadius) }
    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(pickerInk.opacity(0.28)).frame(width: 30, height: 4).padding(.top, 9).padding(.bottom, 8).accessibilityHidden(true)
            HStack(spacing: 8) {
                if tab == .files {
                    Text("Files").font(.subheadline.weight(.semibold)).foregroundStyle(pickerInk).frame(minHeight: 44)
                } else {
                Menu {
                    Button("Recent") { UnyriHaptics.selection(); album = nil; limit = 300; reload() }
                    if selection.isEmpty { Button("System photo picker…") { UnyriHaptics.press(); library() } }
                    ForEach(albums, id: \.localIdentifier) { item in Button(item.localizedTitle ?? "Photos") { UnyriHaptics.selection(); album = item; limit = 300; reload() } }
                } label: { HStack(spacing: 6) { Text(album?.localizedTitle ?? UnyriL10n.string("Photos and videos", comment: "")).lineLimit(1); Image(systemName: "chevron.down").font(.caption.weight(.semibold)) }.font(.subheadline.weight(.semibold)).foregroundStyle(pickerInk).frame(minHeight: 44).contentShape(Rectangle()) }
                }
                Spacer(minLength: 0)
                if activeCount > 0 { Text("\(activeCount)/\(selectionLimit)").font(.caption).monospacedDigit().foregroundStyle(pickerInk.opacity(0.7)) }
                Button(action: close) { Image(systemName: "xmark").font(.system(size: 17)).foregroundStyle(pickerInk).frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Close"))
            }.padding(.horizontal, 13).accessibilityElement(children: .contain).accessibilityIdentifier("gallery-source-header")
            VStack(spacing: 0) {
            if tab == .gallery {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    Text("Quality:").font(.subheadline).foregroundStyle(pickerInk.opacity(0.7)).fixedSize()
                    sendModeMenu.fixedSize(horizontal: true, vertical: false)
                    Spacer(minLength: 0)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Quality:").font(.subheadline).foregroundStyle(pickerInk.opacity(0.7))
                    sendModeMenu
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 13).padding(.bottom, 8)
            .accessibilityElement(children: .contain).accessibilityIdentifier("gallery-send-mode")
                if access != .authorized {
                    Button("Photo access") {
                        UnyriHaptics.press()
                        if access == .limited {
                            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                               var controller = scene.windows.first(where: \.isKeyWindow)?.rootViewController {
                                while let presented = controller.presentedViewController { controller = presented }
                                PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: controller) { _ in reload() }
                            }
                        } else if access == .denied || access == .restricted {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        } else { Task { access = await PHPhotoLibrary.requestAuthorization(for: .readWrite); reload() } }
                    }
                    .font(.caption.weight(.semibold)).foregroundStyle(pickerAccent)
                    .frame(maxWidth: .infinity, minHeight: 44).padding(.horizontal, 10)
                }
            ScrollView {
                LazyVGrid(columns: columns, spacing: UnyriAttachmentPickerStyle.gap) {
                    ForEach(captures, id: \.absoluteString) { url in
                        ZStack(alignment: .topTrailing) {
                            Button { UnyriHaptics.press(); preview = UnyriGalleryPreviewItem(source: .capture(url), id: url.absoluteString) } label: { UnyriCaptureThumbnail(url: url) }.buttonStyle(.plain).accessibilityLabel(Text("Photo"))
                            selectionButton(url.absoluteString)
                        }.clipShape(tileShape)
                    }
                    ForEach(assets, id: \.localIdentifier) { asset in
                        ZStack(alignment: .topTrailing) {
                            Button { UnyriHaptics.press(); preview = UnyriGalleryPreviewItem(source: .asset(asset), id: asset.localIdentifier) } label: { UnyriGalleryThumbnail(asset: asset) }.buttonStyle(.plain).accessibilityLabel(Text(asset.mediaType == .video ? "Video" : "Photo"))
                            selectionButton(asset.localIdentifier, isVideo: asset.mediaType == .video)
                        }.clipShape(tileShape)
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("gallery-asset-" + asset.localIdentifier)
                    }
                }.padding(.horizontal, 10)
                if assets.count >= limit { Button("Load more") { UnyriHaptics.press(); limit += 300; reload() }.frame(minHeight: 44) }
                if assets.isEmpty { Text(access == .authorized || access == .limited ? "No results." : "Allow photo access or open Photos.").font(.footnote).foregroundStyle(.secondary).padding() }
            }.accessibilityIdentifier("gallery-grid")
            } else {
                UnyriAttachmentFilesTab(ink: pickerInk, accent: pickerAccent, limit: selectionLimit,
                    selection: $fileSelection, recents: recentFiles,
                    chooseFiles: { UnyriHaptics.press(); pickingFiles = true },
                    scan: scannerAvailable ? { UnyriHaptics.press(); scanning = true } : nil,
                    mediaAsFiles: { UnyriHaptics.selection(); mediaAsFiles = true; selection.removeAll(); withAnimation(.easeOut(duration: 0.18)) { tab = .gallery } },
                    remove: { id in UnyriRecentFiles.remove(id); fileSelection.removeAll { $0 == id }; recentFiles = UnyriRecentFiles.load() },
                    atLimit: { showLimit = true })
            }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { footer }
        }.foregroundStyle(pickerInk).tint(pickerAccent).disabled(submitted).onAppear {
            if tab == .files { recentFiles = UnyriRecentFiles.load() }
        }.task {
            reload()
        }.onChange(of: phase) { value in if value == .active { reload() } }
        .sheet(isPresented: $pickingFiles) {
            UnyriMultiFilePicker(limit: selectionLimit, picked: { sources in
                pickingFiles = false
                guard !sources.isEmpty, !submitted, sendingAllowed else { return }
                UnyriHaptics.press(); submitted = true
                documents(sources, .add)
            }, cancelled: { pickingFiles = false }).ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $scanning) { scanner }
        .alert("Up to 10 attachments at a time.", isPresented: $showLimit) { Button("OK", role: .cancel) {} }
        .alert("Some selected items are no longer available. Check photo access and select them again.", isPresented: $unavailableSelection) { Button("OK", role: .cancel) {} }
        .sheet(item: $preview) { item in
            UnyriGalleryPreview(item: item, selected: selection.contains(item.id), atLimit: selection.count >= selectionLimit, toggle: { toggle(item.id) })
        }
    }
    private var scannerAvailable: Bool {
        #if canImport(VisionKit)
        return cameraEnabled && UnyriDocumentScanner.isAvailable
        #else
        return false
        #endif
    }
    @ViewBuilder private var scanner: some View {
        #if canImport(VisionKit)
        UnyriDocumentScanner { url in
            scanning = false
            guard let url, !submitted, sendingAllowed else { return }
            UnyriHaptics.press(); submitted = true
            documents([AttachmentFileCopy.DocumentSource(url)], .add)
        }.ignoresSafeArea()
        #endif
    }
    /// Glass footer (Telegram-like, in our monochrome): a caption capsule with the Add
    /// button inside, and a glass tab bar whose selection slides between tabs. The
    /// caption field edits the composer text itself, so it stays there after Add.
    private var footer: some View {
        let canSend = activeCount > 0 && !submitted && sendingAllowed
        let addTitle = activeCount > 0 ? String(format: UnyriL10n.string("Add (%d)"), activeCount) : UnyriL10n.string("Add")
        return VStack(spacing: 10) {
            HStack(alignment: .bottom, spacing: 4) {
                TextField("Caption (optional)", text: $caption, axis: .vertical)
                    .font(.body).lineLimit(1...4).foregroundStyle(pickerInk)
                    .textFieldStyle(.plain).padding(.leading, 16).padding(.vertical, 12)
                    .frame(minHeight: 46).contentShape(Rectangle())
                    .accessibilityIdentifier("gallery-caption")
                // Tap: Add to the composer tray. Long press: the system menu with Send now.
                Menu {
                    Button { finish(.sendNow) } label: { Label("Send now", systemImage: "paperplane") }
                        .accessibilityIdentifier("gallery-send-now")
                } label: {
                    Text(addTitle).font(.subheadline.weight(.semibold)).monospacedDigit()
                        .lineLimit(1).fixedSize()
                        .foregroundStyle(canSend ? UnyriSavedChatStyle.micInk : pickerInk.opacity(0.45))
                        .padding(.horizontal, 14).frame(minHeight: 36)
                        .background(canSend ? UnyriSavedChatStyle.micColor : pickerInk.opacity(0.10), in: Capsule())
                        .padding(.horizontal, 5).frame(minHeight: 46).contentShape(Rectangle())
                } primaryAction: {
                    finish(.add)
                }
                .disabled(!canSend)
                .animation(.easeOut(duration: 0.15), value: canSend)
                .accessibilityLabel(Text(addTitle)).accessibilityValue("\(activeCount)")
                .accessibilityAction(named: Text("Send now")) { finish(.sendNow) }
                .accessibilityIdentifier("gallery-add")
            }
            .padding(.trailing, 2)
            .modifier(UnyriAttachGlass(shape: RoundedRectangle(cornerRadius: 23, style: .continuous)))
            HStack(spacing: 0) {
                tabSegment(.gallery, title: "Photos and videos", symbol: "photo.on.rectangle", id: "attach-tab-gallery")
                tabSegment(.files, title: "Files", symbol: "doc.text", id: "attach-tab-files")
            }
            .padding(4)
            .modifier(UnyriAttachGlass(shape: Capsule()))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 6)
        .accessibilityElement(children: .contain).accessibilityIdentifier("gallery-footer")
    }
    private func tabSegment(_ value: Tab, title: LocalizedStringKey, symbol: String, id: String) -> some View {
        let selected = tab == value
        return Button {
            guard tab != value else { return }
            UnyriHaptics.selection()
            if value == .files { recentFiles = UnyriRecentFiles.load() }
            withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) { tab = value }
        } label: {
            Label(title, systemImage: symbol).font(.footnote.weight(.semibold)).lineLimit(1)
                .foregroundStyle(selected ? pickerInk : pickerInk.opacity(0.55))
                .frame(maxWidth: .infinity, minHeight: 40)
                .background {
                    if selected {
                        Capsule().fill(pickerInk.opacity(0.14))
                            .matchedGeometryEffect(id: "attach-tab-selection", in: tabSelection)
                    }
                }
                .contentShape(Capsule())
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(id)
    }
    private func finish(_ commit: UnyriAttachmentCommit) {
        if tab == .files { finishFiles(commit) } else { finishSelection(commit) }
    }
    private func finishFiles(_ commit: UnyriAttachmentCommit) {
        guard !submitted, sendingAllowed, !fileSelection.isEmpty else { return }
        let byID = Dictionary(uniqueKeysWithValues: recentFiles.map { ($0.id, $0) })
        let urls = fileSelection.compactMap { byID[$0].map(UnyriRecentFiles.url(for:)) }
            .filter { FileManager.default.fileExists(atPath: $0.path) }
        guard urls.count == fileSelection.count else {
            recentFiles = UnyriRecentFiles.load(); fileSelection.removeAll { id in !recentFiles.contains { $0.id == id } }
            unavailableSelection = true; return
        }
        UnyriHaptics.press(); submitted = true
        documents(urls.map { AttachmentFileCopy.DocumentSource($0) }, commit)
    }
    private func finishSelection(_ commit: UnyriAttachmentCommit) {
        guard !submitted, sendingAllowed, !selection.isEmpty else { return }
        var byID: [String: PHAsset] = [:]
        let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if access == .authorized || access == .limited {
            PHAsset.fetchAssets(withLocalIdentifiers: selection, options: nil).enumerateObjects { asset, _, _ in byID[asset.localIdentifier] = asset }
        }
        let captureByID = Dictionary(uniqueKeysWithValues: captures.map { ($0.absoluteString, $0) })
        let sources: [UnyriGallerySource] = selection.compactMap { id in
            if let asset = byID[id] { return .asset(asset) }
            if let url = captureByID[id] { return .capture(url) }
            return nil
        }
        guard sources.count == selection.count else { unavailableSelection = true; return }
        UnyriHaptics.press(); submitted = true
        selected(sources, sendChoice.intent(caption: caption), commit)
    }
    private func toggle(_ id: String) {
        if let index = selection.firstIndex(of: id) { UnyriHaptics.selection(); selection.remove(at: index) }
        else if selection.count < selectionLimit { UnyriHaptics.selection(); selection.append(id) }
        else { showLimit = true }
    }
    private func selectionButton(_ id: String, isVideo: Bool = false) -> some View {
        let number = selection.firstIndex(of: id).map { $0 + 1 }
        return Button { toggle(id) } label: {
            Text(number.map(String.init) ?? "").font(.caption.bold())
                .foregroundStyle(pickerInk)
                .frame(width: 26, height: 26).background(number == nil ? Color.black.opacity(0.45) : pickerAccent, in: Circle())
                .overlay(Circle().stroke(number == nil ? pickerInk.opacity(0.85) : pickerAccent, lineWidth: 1.5))
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).frame(width: 44, height: 44).contentShape(Rectangle()).zIndex(1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(number == nil ? "Select" : "Remove from selection"))
            .accessibilityValue(number.map(String.init) ?? "")
            .accessibilityAddTraits(number == nil ? [.isButton] : [.isButton, .isSelected])
            .accessibilityIdentifier(isVideo ? "gallery-select-video" : "gallery-select-photo")
            .disabled(number == nil && selection.count >= selectionLimit)
    }
    private func reload() {
        access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard access == .authorized || access == .limited else { assets = []; selection = selection.filter { id in captures.contains { $0.absoluteString == id } }; return }
        let currentLimit = limit
        let currentAlbum = album
        let currentCaptures = captures
        let currentSelection = selection
        Task.detached(priority: .userInitiated) {
            let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            guard access == .authorized || access == .limited else { return }
            let options = PHFetchOptions()
            options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            options.fetchLimit = currentLimit
            options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
            var collections: [PHAssetCollection] = []
            for type in [PHAssetCollectionType.album, .smartAlbum] {
                PHAssetCollection.fetchAssetCollections(with: type, subtype: .any, options: nil).enumerateObjects { item, _, _ in collections.append(item) }
            }
            let result = currentAlbum.map { PHAsset.fetchAssets(in: $0, options: options) } ?? PHAsset.fetchAssets(with: options)
            var loaded: [PHAsset] = []
            result.enumerateObjects { asset, _, _ in loaded.append(asset) }
            var allowed = Set(currentCaptures.map(\.absoluteString))
            PHAsset.fetchAssets(withLocalIdentifiers: currentSelection, options: nil).enumerateObjects { item, _, _ in allowed.insert(item.localIdentifier) }
            let filteredSelection = currentSelection.filter { allowed.contains($0) }
            await MainActor.run {
                self.albums = collections
                self.assets = loaded
                self.selection = filteredSelection
            }
        }
    }
}

/// Files tab of the attach sheet (Telegram's File tab as a reference, not a copy):
/// pick several files from Files, scan a paper document, send photos/videos as
/// files, or tick up to the limit of recently sent files and add them to the message.
struct UnyriAttachmentFilesTab: View {
    let ink: Color
    let accent: Color
    let limit: Int
    @Binding var selection: [String]
    let recents: [UnyriRecentFiles.Entry]
    let chooseFiles: () -> Void
    let scan: (() -> Void)?
    let mediaAsFiles: () -> Void
    let remove: (String) -> Void
    let atLimit: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: 0) {
                    action("folder", "Choose from Files", detail: "Several at once, sent right away", id: "files-choose", perform: chooseFiles)
                    if let scan {
                        divider
                        action("doc.viewfinder", "Scan document", detail: "Paper pages as one PDF", id: "files-scan", perform: scan)
                    }
                    divider
                    action("photo.on.rectangle.angled", "Photo or video as file", detail: "Original quality, without compression", id: "files-media-as-files", perform: mediaAsFiles)
                }
                .background(ink.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 10)
                if recents.isEmpty {
                    Text("Files you send will appear here, ready to send again.")
                        .font(.footnote).foregroundStyle(ink.opacity(0.55))
                        .frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        .padding(.horizontal, 24).padding(.top, 28)
                        .accessibilityIdentifier("files-recent-empty")
                } else {
                    Text("Recently sent files").font(.footnote.weight(.semibold)).textCase(.uppercase)
                        .foregroundStyle(ink.opacity(0.5))
                        .padding(.horizontal, 22).padding(.top, 18).padding(.bottom, 6)
                    LazyVStack(spacing: 0) {
                        ForEach(Array(recents.enumerated()), id: \.element.id) { index, entry in
                            if index > 0 { divider }
                            row(entry)
                        }
                    }
                    .background(ink.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 10)
                }
            }.padding(.top, 4).padding(.bottom, 12)
        }
        .accessibilityIdentifier("files-tab")
    }

    private var divider: some View { Rectangle().fill(ink.opacity(0.08)).frame(height: 0.5).padding(.leading, 64) }

    private func action(_ symbol: String, _ title: LocalizedStringKey, detail: LocalizedStringKey, id: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.system(size: 19)).foregroundStyle(accent)
                    .frame(width: 38, height: 38).background(ink.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(ink)
                    Text(detail).font(.caption).foregroundStyle(ink.opacity(0.55))
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(ink.opacity(0.35))
            }
            .padding(.horizontal, 12).frame(minHeight: 58).contentShape(Rectangle())
        }
        .buttonStyle(.plain).accessibilityIdentifier(id)
    }

    private func row(_ entry: UnyriRecentFiles.Entry) -> some View {
        let number = selection.firstIndex(of: entry.id).map { $0 + 1 }
        let ext = (entry.name as NSString).pathExtension.uppercased()
        return Button { toggle(entry.id) } label: {
            HStack(spacing: 14) {
                Text(ext.isEmpty ? "FILE" : String(ext.prefix(4)))
                    .font(.system(size: 11, weight: .bold)).foregroundStyle(ink.opacity(0.85))
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .frame(width: 38, height: 38).background(ink.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.name).font(.subheadline.weight(.medium)).foregroundStyle(ink)
                        .lineLimit(1).truncationMode(.middle)
                    Text(ByteCountFormatter.string(fromByteCount: entry.bytes, countStyle: .file) + " · " + Self.dateText(entry.date))
                        .font(.caption).monospacedDigit().foregroundStyle(ink.opacity(0.55))
                }
                Spacer(minLength: 8)
                ZStack {
                    Circle().stroke(number == nil ? ink.opacity(0.45) : accent, lineWidth: 1.5)
                    if let number {
                        Circle().fill(accent)
                        Text("\(number)").font(.caption.bold()).foregroundStyle(ink)
                    }
                }.frame(width: 24, height: 24)
            }
            .padding(.horizontal, 12).frame(minHeight: 58).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { Button(role: .destructive) { remove(entry.id) } label: { Label("Remove from recent", systemImage: "trash") } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(entry.name))
        .accessibilityValue(Text(number.map(String.init) ?? ""))
        .accessibilityAddTraits(number == nil ? [.isButton] : [.isButton, .isSelected])
        .accessibilityIdentifier("files-recent-row")
    }

    private func toggle(_ id: String) {
        if let index = selection.firstIndex(of: id) { UnyriHaptics.selection(); selection.remove(at: index) }
        else if selection.count < limit { UnyriHaptics.selection(); selection.append(id) }
        else { atLimit() }
    }

    static func dateText(_ date: Date) -> String {
        Calendar.current.isDateInToday(date)
            ? date.formatted(date: .omitted, time: .shortened)
            : date.formatted(.dateTime.day().month(.abbreviated))
    }
}

/// Liquid Glass on iOS 26, material with a hairline before it.
struct UnyriAttachGlass<S: Shape>: ViewModifier {
    let shape: S
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(Color.white.opacity(0.10), lineWidth: 0.5))
        }
    }
}

private struct UnyriGalleryPreviewItem: Identifiable {
    let source: UnyriGallerySource
    let id: String
}

private struct UnyriGalleryPreview: View {
    let item: UnyriGalleryPreviewItem
    let selected: Bool
    let atLimit: Bool
    let toggle: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @Environment(\.unyriAppColorStyle) private var appColorStyle
    @State private var image: UIImage?
    @State private var player: AVPlayer?
    @State private var request: PHImageRequestID?
    @State private var failed = false
    @State private var active = true
    @State private var previewTicket = UUID()
    private var actionTint: Color {
        appColorStyle.decorative(Color(hex: UnyriAttachmentPickerStyle.accent))
    }
    var body: some View {
        NavigationStack {
            ZStack {
                Color.black
                if let player { VideoPlayer(player: player) }
                else if let image { Image(uiImage: image).resizable().scaledToFit() }
                else if failed { Text("Preview unavailable. You can still select the file.").foregroundStyle(.white).padding() }
                else { ProgressView().tint(.white) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .navigationTitle("Preview")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(UnyriColors.elevatedBackground, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { UnyriHaptics.press(); dismiss() } label: {
                        Image(systemName: "xmark").font(.system(size: 17, weight: .medium))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain).foregroundStyle(UnyriColors.primaryText)
                    .accessibilityLabel("Close")
                    .accessibilityIdentifier("gallery-preview-close")
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    Divider().overlay(UnyriColors.outlineSubtle)
                    Button(action: toggle) {
                        Label(LocalizedStringKey(selected ? "Remove from selection" : atLimit ? "Up to 10 attachments at a time." : "Select"),
                              systemImage: selected ? "minus.circle" : "checkmark.circle")
                            .font(.body.weight(.semibold)).multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity).padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    .tint(actionTint).foregroundStyle(appColorStyle.onColorfulButton)
                    .disabled(!selected && atLimit)
                    .accessibilityIdentifier("gallery-preview-selection")
                    .padding(.horizontal, 16).padding(.vertical, 12)
                }.background(UnyriColors.elevatedBackground)
            }
        }.accessibilityIdentifier("gallery-preview").task(id: item.id) {
            if let request { PHImageManager.default().cancelImageRequest(request) }
            request = nil; player?.pause(); player = nil; image = nil; failed = false
            active = true; previewTicket = UUID()
            let ticket = previewTicket
            let generation = ConversationImages.generation
            switch item.source {
            case .capture(let url):
                image = UnyriAttachmentImages.best(.file(url))?.image
                if let image, max(image.size.width, image.size.height) * image.scale >= 1600 { return }
                let decoded = await Task.detached(priority: .utility) {
                    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                          let frame = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1600] as CFDictionary) else { return nil as UIImage? }
                    return UIImage(cgImage: frame)
                }.value
                guard !Task.isCancelled, active, previewTicket == ticket, generation == ConversationImages.generation else { return }
                image = decoded
                if let decoded { ConversationImages.cacheAttachmentImage(decoded, path: url.path, pixelEdge: 2048, generation: generation) }
                failed = image == nil
            case .asset(let asset):
                if asset.mediaType == .video {
                    let options = PHVideoRequestOptions(); options.isNetworkAccessAllowed = true
                    request = PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { value, _ in
                        DispatchQueue.main.async { guard active, previewTicket == ticket else { return }; if let value { player = AVPlayer(playerItem: value) } else { failed = true } }
                    }
                } else {
                    let options = PHImageRequestOptions(); options.isNetworkAccessAllowed = true; options.resizeMode = .exact
                    request = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 1600, height: 1600), contentMode: .aspectFit, options: options) { value, info in
                        let degraded = info?[PHImageResultIsDegradedKey] as? Bool == true
                        DispatchQueue.main.async {
                            guard active, previewTicket == ticket, generation == ConversationImages.generation else { return }
                            if let value {
                                UnyriAttachmentImages.publish(value, id: "photos:" + asset.localIdentifier, origin: degraded ? .inline : .localImage)
                                image = UnyriAttachmentImages.best(id: "photos:" + asset.localIdentifier)?.image
                            } else if !degraded { failed = true }
                        }
                    }
                }
            }
        }.onDisappear { active = false; if let request { PHImageManager.default().cancelImageRequest(request) }; player?.pause(); player = nil }
        .onChange(of: phase) { if $0 != .active { player?.pause() } }
    }
}

struct UnyriGalleryThumbnail: View {
    let asset: PHAsset
    var body: some View {
        UnyriPhotosAttachmentThumbnail(asset: asset).aspectRatio(1, contentMode: .fit)
            .overlay(alignment: .bottomLeading) {
                if asset.mediaType == .video { Text(FilePresentation.duration(Int64(max(0, asset.duration) * 1000)))
                    .font(.caption.monospacedDigit()).foregroundStyle(.white).padding(6)
                    .background(.black.opacity(0.6)).accessibilityHidden(true) }
            }.clipped().contentShape(Rectangle())
    }
}

struct UnyriCaptureThumbnail: View {
    let url: URL
    var body: some View {
        UnyriAttachmentImage(request: .file(url, pixelEdge: 256)) { image in
            Color.secondary.opacity(0.15).aspectRatio(1, contentMode: .fit)
                .overlay {
                    GeometryReader { geometry in
                        if let image {
                            Image(uiImage: image).resizable().scaledToFill()
                                .frame(width: geometry.size.width, height: geometry.size.height)
                                .clipped().accessibilityHidden(true)
                        }
                    }.allowsHitTesting(false)
                }.clipped().contentShape(Rectangle())
        }
    }
}


/// A wrapping reaction row with intrinsic width, including short text bubbles.
struct UnyriReactionRows: Layout {
    /// Between chips on a row, and between wrapped rows.
    let spacing: CGFloat
    let rowGap: CGFloat
    /// Caps the wrap width without widening the row: a maxWidth frame would grow to the
    /// cap and centre the chips (and widen a big emoji's whole message).
    var maxWidth: CGFloat = .infinity
    private func arrangement(_ proposal: ProposedViewSize, _ views: Subviews) -> (CGSize, [CGPoint]) {
        let limit = min(proposal.width ?? 280, maxWidth)
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var width: CGFloat = 0
        var origins: [CGPoint] = []
        for view in views {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > limit { x = 0; y += rowHeight + rowGap; rowHeight = 0 }
            origins.append(CGPoint(x: x, y: y))
            width = max(width, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: width, height: y + rowHeight), origins)
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrangement(proposal, subviews).0
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let origins = arrangement(ProposedViewSize(width: bounds.width, height: nil), subviews).1
        for (index, view) in subviews.enumerated() {
            view.place(at: CGPoint(x: bounds.minX + origins[index].x, y: bounds.minY + origins[index].y), proposal: .unspecified)
        }
    }
}

struct UnyriReactionTarget: UIViewRepresentable {
    let presenter: UnyriMessageMenuPresenter
    let emoji: String
    func makeUIView(context: Context) -> UIView {
        let view = UIView(); view.isUserInteractionEnabled = false
        if !context.environment.unyriMessagePreview { presenter.reactionTargets.setObject(view, forKey: emoji as NSString) }
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {
        if !context.environment.unyriMessagePreview { presenter.reactionTargets.setObject(view, forKey: emoji as NSString) }
    }
}


// Shared transient backdrop for message and header menus.
struct UnyriChatMenuBackdrop: View {
    let close: () -> Void
    var body: some View {
        Rectangle().fill(.ultraThinMaterial).overlay(Color.black.opacity(0.25))
            .environment(\.colorScheme, .dark)
            .ignoresSafeArea().onTapGesture(perform: close)
            .accessibilityLabel(Text("Close")).accessibilityAddTraits(.isButton)
    }
}

// Shared lazy catalog for message insertion and extended reactions.
struct UnyriEmojiCategory {
    let name: String
    let ru: String
    let keywords: String
    let emojis: [String]
}
let unyriEmojiCategories: [UnyriEmojiCategory] = [
    UnyriEmojiCategory(name: "Faces", ru: "Лица", keywords: "радость смех улыбка грусть злость happy laugh sad angry", emojis: "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Hearts", ru: "Сердца", keywords: "любовь сердце love heart", emojis: "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💌".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Gestures", ru: "Жесты", keywords: "рука спасибо привет да нет hand thanks hello yes no", emojis: "👍 👎 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✊ 👊 🤛 🤜 💅 🤳".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Cats and animals", ru: "Животные", keywords: "кот кошка собака cat dog animal pet", emojis: "😺 😸 😹 😻 😼 😽 🙀 😿 😾 🐱 🐈 🐈‍⬛ 🐶 🐕 🐩 🐺 🦊 🦝 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐘 🦒 🦔 🐿️ 🐇".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Nature", ru: "Природа", keywords: "цветок солнце погода луна flower sun weather moon", emojis: "💐 🌸 🌹 🌺 🌻 🌼 🌷 🌱 🌲 🌳 🌴 🌵 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🌍 🌎 🌏 🌕 🌙 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌈 ☀️ 🌤️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🌊".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Food", ru: "Еда", keywords: "еда кофе чай торт пицца food coffee tea cake pizza", emojis: "🍎 🍏 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥒 🌶️ 🥕 🌽 🥔 🍞 🥐 🧀 🥚 🍳 🥓 🥩 🍗 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🍝 🍜 🍣 🍤 🍚 🍙 🍿 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍯 🥛 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍹".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Activities", ru: "Занятия", keywords: "спорт праздник музыка игра sports party music game", emojis: "🎉 🎊 🎈 🎁 🎀 🎂 🏆 🥇 🥈 🥉 🏅 ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎿 🛷 🏂 🏋️ 🤸 🏊 🚴 🧘 🎮 🕹️ 🎲 🧩 ♟️ 🎨 🎭 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Travel and objects", ru: "Предметы", keywords: "машина самолет дом телефон работа car plane home phone work", emojis: "🚗 🚕 🚌 🚎 🏎️ 🚓 🚑 🚒 🚚 🚲 🛵 🏍️ 🚂 🚆 ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏡 🏢 🏥 🏫 🏰 🗼 🗽 🏖️ 🏝️ ⛰️ 🏕️ 📱 💻 ⌨️ 🖥️ 📷 🎥 📺 💡 🔦 🔋 🔌 📚 📖 📝 ✏️ 🖊️ 📌 📎 ✂️ 🔒 🔑 🔨 🛠️ 💎 💰 💸 ⏰ ⌚ 📦".components(separatedBy: " ")),
    UnyriEmojiCategory(name: "Symbols", ru: "Символы", keywords: "символ галочка огонь сто внимание yes check warning", emojis: "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ⁉️ ‼️ 💯 🆗 🆕 🆒 🆘 🆓 🔝 🔙 🔜 🔛 🔚 ♻️ ⚠️ 🚫 ⛔ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔶 🔷 🔺 🔻 🔸 🔹 💠 ♦️ ♠️ ♥️ ♣️ 🎯 💤 💬 👀 🧠 🫶".components(separatedBy: " ")),
 ]
struct UnyriEmojiPicker: View {
    var initiallyExpanded = true
    var embedded = false
    var bottomInset: CGFloat = 76
    var close: (() -> Void)? = nil
    let choose: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false
    @State private var category = 0
    @State private var query = ""
    private var russian: Bool { UnyriL10n.language == "ru" }
    private var full: Bool { initiallyExpanded || expanded }
    private let symbols = ["face.smiling", "heart", "hand.wave", "pawprint", "sun.max", "cup.and.saucer", "soccerball", "airplane", "checkmark.circle"]
    private func finish() { UnyriHaptics.press(); if let close { close() } else { dismiss() } }
    private var visible: [String] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if term.isEmpty { return unyriEmojiCategories[category].emojis }
        var seen = Set<String>()
        return unyriEmojiCategories.filter { ($0.name + " " + $0.ru + " " + $0.keywords).localizedCaseInsensitiveContains(term) || $0.emojis.contains(where: { $0.contains(term) }) }
            .flatMap { group in group.emojis.contains(where: { $0.contains(term) }) ? group.emojis.filter { $0.contains(term) } : group.emojis }
            .filter { seen.insert($0).inserted }
    }
    var body: some View {
        GeometryReader { geometry in
            let reservedBottom = embedded ? 24 : max(0, bottomInset)
            ZStack(alignment: .bottom) {
                if !embedded {
                    // Keep the draft readable while choosing multiple emoji.
                    // Embedded reactions retain the message menu backdrop.
                    Color.black.opacity(0.08).ignoresSafeArea().onTapGesture(perform: finish)
                }
                VStack(spacing: 12) {
                    if full {
                        HStack {
                            UnyriBackButton(action: { if initiallyExpanded { finish() } else { UnyriHaptics.press(); expanded = false } })
                            Spacer(); Text(russian ? "Эмодзи" : "Emoji").font(.headline); Spacer()
                            Button(action: finish) { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close")
                        }.foregroundStyle(UnyriColors.primaryText)
                        HStack { Image(systemName: "magnifyingglass"); TextField(russian ? "Найти эмодзи" : "Find emoji", text: $query) }
                            .padding(12).background(Color.white.opacity(0.04), in: Capsule()).overlay(Capsule().stroke(Color.white.opacity(0.10), lineWidth: 0.7))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 4) {
                                ForEach(unyriEmojiCategories.indices, id: \.self) { index in
                                    Button { UnyriHaptics.selection(); category = index; query = "" } label: {
                                        Image(systemName: symbols[index]).font(.system(size: 22)).frame(width: 44, height: 44)
                                            .background(category == index && query.isEmpty ? Color.white.opacity(0.14) : .clear, in: Capsule())
                                    }.buttonStyle(.plain).accessibilityLabel(russian ? unyriEmojiCategories[index].ru : unyriEmojiCategories[index].name)
                                }
                            }
                        }
                        Text(query.isEmpty ? (russian ? unyriEmojiCategories[category].ru : unyriEmojiCategories[category].name) : (russian ? "Результаты" : "Results")).font(.caption).foregroundColor(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                        let filteredEmoji = visible
                        if filteredEmoji.isEmpty { Text(russian ? "Ничего не найдено" : "No results").foregroundColor(.secondary) }
                        ScrollView {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 44))], spacing: 2) {
                                ForEach(filteredEmoji, id: \.self) { emoji in
                                    Button { choose(emoji) } label: { Text(emoji).font(.system(size: 28)).frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.plain)
                                }
                            }
                        }
                    } else {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 0) {
                            ForEach(["😊", "😂", "❤️", "👍", "😍", "🔥", "🙏", "😎", "😘", "🥰", "😢", "🎉"], id: \.self) { emoji in
                                Button { choose(emoji) } label: { Text(emoji).font(.system(size: 28)).frame(height: 48) }.buttonStyle(.plain)
                            }
                        }
                        Divider().overlay(Color.white.opacity(0.10))
                        Button { UnyriHaptics.press(); expanded = true } label: {
                            HStack(spacing: 12) { Image(systemName: "square.grid.2x2.fill"); Text(russian ? "Все эмодзи" : "All emoji"); Spacer(); Image(systemName: "chevron.right") }.frame(height: 38)
                        }.foregroundStyle(UnyriColors.primaryText)
                    }
                }.padding(14).frame(height: full ? max(0, min(max(240, geometry.size.height * 0.58), geometry.size.height - reservedBottom - 12)) : nil)
                    .modifier(UnyriAttachGlass(shape: RoundedRectangle(cornerRadius: 24, style: .continuous)))
                    .padding(.horizontal, 12).padding(.bottom, reservedBottom)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.16), value: full)
            }
        }.preferredColorScheme(.dark)
    }
}


// MARK: - Reaction landing ("drop and ripple")
// The chosen emoji travels into its chip, the chip lands with a small squash, and a
// monochrome light ring crosses the bubble under the text. Everything is one-shot Core
// Animation that exists only during an effect; SwiftUI never observes the coordinator.

import Combine
import os

private let reactionFXLog = Logger(subsystem: "com.unyri.timeline", category: "fx")

@MainActor
final class UnyriReactionBursts {
    static let shared = UnyriReactionBursts()
    private typealias Policy = UnyriReactionBurstPolicy

    enum Source { case menu, doubleTap, chip, peer }
    enum Landing { case silent, animated }
    /// Where a flyer starts: a menu cell or the fingertip, in window coordinates.
    struct Origin {
        weak var window: UIWindow?
        let point: CGPoint
        /// The emoji's point size there (0 for a fingertip).
        let glyph: CGFloat
        let source: Source
    }
    /// One expected landing, from the intent (or a peer's arrival) until its chip takes it.
    @MainActor
    final class Token {
        let key: String, messageKey: String, emoji: String
        var source: Source
        let born = CACurrentMediaTime()
        var flyer: CALayer?
        var flyerScale: CGFloat = 1
        var launched = false
        var onLand: ((Landing) -> Void)?
        var onDone: (() -> Void)?
        var own: Bool { source != .peer }
        init(key: String, messageKey: String, emoji: String, source: Source) {
            self.key = key; self.messageKey = messageKey; self.emoji = emoji; self.source = source
        }
        /// Each closure runs once.
        func finishLanding(_ landing: Landing) { let run = onLand; onLand = nil; run?(landing) }
        func finish() { let run = onDone; onDone = nil; run?() }
    }

    private var armed: (messageID: String, origin: Origin, at: CFTimeInterval)?
    private var pending: [String: Token] = [:]
    /// Bubbles keep their effect surface until these times.
    private var lingering: [String: CFTimeInterval] = [:]
    private let surfaces = NSMapTable<NSString, UnyriReactionSurfaceView>.strongToWeakObjects()
    /// Holds flyers above everything in the window; exists only while one is out.
    private var host: UIView?
    /// Bumped by cancelAll, so a cancelled flyer's completion never touches a newer host.
    private var hostGeneration = 0
    private var flyers = 0, effects = 0
    private var subscription: AnyCancellable?
    private weak var source: MessagesRepository?
    private var quietUntil: CFTimeInterval = 0

    func attach(_ repository: MessagesRepository) {
        quiet(Policy.quietAfterAttach)
        guard source !== repository else { return }
        source = repository
        // Sent on the main actor before the repository publishes the new reactions and
        // delivered synchronously, so the tokens exist before any chip renders.
        subscription = repository.peerReactionArrivals.sink { [weak self] arrivals in self?.expectPeer(arrivals) }
    }

    /// No peer effects until then: opening the chat, a reconnect's sync.
    func quiet(_ seconds: Double) { quietUntil = max(quietUntil, CACurrentMediaTime() + seconds) }

    /// The next reaction intent for this message starts its flyer at `origin`.
    func arm(messageID: String, origin: Origin) {
        armed = (messageID, origin, CACurrentMediaTime())
        reactionFXLog.debug("arm \(String(describing: origin.source), privacy: .public)")
    }

    /// My reaction is on its way (the ChatScreen funnels, after their guards).
    func expectAdd(chatId: String, serverId: String, emoji: String, messageID: String) {
        let key = Policy.key(chat: chatId, server: serverId, emoji: emoji)
        if let previous = pending[key] { dissolve(previous) }
        let token = Token(key: key, messageKey: Policy.messageKey(chat: chatId, server: serverId), emoji: emoji, source: .chip)
        let now = CACurrentMediaTime()
        if let armed, armed.messageID == messageID, now - armed.at < Policy.armLifetime, let window = armed.origin.window,
           !UIAccessibility.isReduceMotionEnabled, !UIAccessibility.isVoiceOverRunning {
            token.source = armed.origin.source
            spawnFlyer(token, origin: armed.origin, window: window)
        }
        armed = nil
        pending[key] = token
        lingering[token.messageKey] = now + Policy.pendingLifetime
        DispatchQueue.main.asyncAfter(deadline: .now() + Policy.flyerWait) { [weak self] in
            // Still no chip: the flyer fades where it hovers and the chip pops in place.
            if !token.launched { self?.dissolve(token) }
        }
        scheduleExpiry([token])
    }

    /// Someone else reacted to a loaded message. Plays only in a calm, active chat; my own
    /// intent for the same chip wins.
    func expectPeer(_ arrivals: [UnyriPeerReaction]) {
        let now = CACurrentMediaTime()
        guard UIApplication.shared.applicationState == .active, UnyriMessageMenuPresenter.canActivateMessage,
              !UnyriMediaWorkState.shared.viewerOpen, now >= quietUntil else { return }
        var tokens: [Token] = []
        for arrival in arrivals where tokens.count < Policy.peerBatch {
            let key = Policy.key(chat: arrival.chatId, server: arrival.targetServerId, emoji: arrival.emoji)
            guard !isPending(key) else { continue }
            let token = Token(key: key, messageKey: Policy.messageKey(chat: arrival.chatId, server: arrival.targetServerId),
                              emoji: arrival.emoji, source: .peer)
            pending[key] = token
            lingering[token.messageKey] = now + Policy.pendingLifetime
            tokens.append(token)
        }
        guard !tokens.isEmpty else { return }
        reactionFXLog.debug("peer \(tokens.count, privacy: .public)")
        scheduleExpiry(tokens)
    }

    /// The safety net: a landing that never started shows its chip plainly and lets go.
    private func scheduleExpiry(_ tokens: [Token]) {
        DispatchQueue.main.asyncAfter(deadline: .now() + Policy.pendingLifetime) { [weak self] in
            for token in tokens {
                if self?.pending[token.key] === token { self?.pending[token.key] = nil }
                guard !token.launched else { continue }
                token.launched = true
                self?.dissolve(token)
                token.finishLanding(.silent)
                token.finish()
                reactionFXLog.debug("silent reason=timeout")
            }
        }
    }

    /// Whether a chip's landing is still coming. A plain read, cheap when nothing is pending.
    func isPending(_ key: String) -> Bool {
        guard !pending.isEmpty, let token = pending[key] else { return false }
        return CACurrentMediaTime() - token.born < Policy.pendingLifetime
    }

    /// The chip claims its landing; nil when none is coming.
    func take(_ key: String) -> Token? {
        guard isPending(key) else { return nil }
        return pending.removeValue(forKey: key)
    }

    /// Whether a bubble mounts its effect surface: only while a landing is coming or playing.
    func wantsSurface(_ messageKey: String) -> Bool {
        guard !pending.isEmpty || !lingering.isEmpty else { return false }
        let now = CACurrentMediaTime()
        for (key, until) in lingering where until <= now { lingering[key] = nil }
        return lingering[messageKey] != nil
            || pending.values.contains { $0.messageKey == messageKey && now - $0.born < Policy.pendingLifetime }
    }

    func register(surface: UnyriReactionSurfaceView, messageKey: String) {
        surfaces.setObject(surface, forKey: messageKey as NSString)
    }

    /// The chip's probe has a frame in the window.
    func targetReady(_ token: Token, probe: UIView, glyphPoint: CGPoint, glyph: CGFloat, bare: Bool, appearing: Bool) {
        // One main turn later, so the cell has finished sizing itself.
        DispatchQueue.main.async { [weak self, weak probe] in
            guard let self, !token.launched, let probe, let window = probe.window else { return }
            token.launched = true
            let chip = probe.convert(probe.bounds, to: window)
            guard Self.viewport(of: probe, in: window).intersection(chip).height >= chip.height / 2 else {
                self.dissolve(token)
                token.finishLanding(.silent)
                token.finish()
                reactionFXLog.debug("silent reason=offscreen")
                return
            }
            let reduce = UIAccessibility.isReduceMotionEnabled
            guard !reduce, let flyer = token.flyer else {
                self.dissolve(token)
                self.land(token, probe: probe, bare: bare, appearing: appearing, reduce: reduce)
                return
            }
            let duration = self.launch(token, flyer: flyer, to: probe.convert(glyphPoint, to: window), glyph: glyph)
            DispatchQueue.main.asyncAfter(deadline: .now() + duration - Policy.crossfade) { [weak self, weak probe] in
                guard let self, let probe else { token.finishLanding(.silent); token.finish(); return }
                self.land(token, probe: probe, bare: bare, appearing: appearing, reduce: false)
            }
        }
    }

    /// Flies the flyer along an upward arc into the chip's emoji and returns the duration.
    private func launch(_ token: Token, flyer: CALayer, to target: CGPoint, glyph: CGFloat) -> Double {
        let from = flyer.presentation()?.position ?? flyer.position
        let distance = hypot(target.x - from.x, target.y - from.y)
        let duration = Policy.flightDuration(distance: distance)
        let path = CGMutablePath()
        path.move(to: from)
        path.addQuadCurve(to: target, control: Policy.arcControl(from: from, to: target))
        // It arrives at the chip's own entry scale (0.8), so the hand-over is seamless.
        let start = token.flyerScale, end = glyph / Policy.flyerGlyph * 0.8
        let turn: CGFloat = target.x >= from.x ? 1 : -1
        let position = CAKeyframeAnimation(keyPath: "position")
        position.path = path
        position.timingFunction = CAMediaTimingFunction(controlPoints: 0.38, 0, 0.72, 0.78)
        let scale = CAKeyframeAnimation(keyPath: "transform.scale")
        scale.values = [start, 1.12 * (start + (end - start) * 0.35), end]
        scale.keyTimes = [0, 0.35, 1]
        scale.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeIn)]
        let tilt = CAKeyframeAnimation(keyPath: "transform.rotation.z")
        tilt.values = [0, 0.14 * turn, -0.04 * turn, 0]
        tilt.keyTimes = [0, 0.4, 0.85, 1]
        let fade = CAKeyframeAnimation(keyPath: "opacity")
        fade.values = [1, 1, 0]
        fade.keyTimes = [0, 0.88, 1]
        let group = CAAnimationGroup()
        group.animations = [position, scale, tilt, fade]
        for animation in [position, scale, tilt, fade, group] as [CAAnimation] { animation.duration = duration }
        token.flyer = nil
        let generation = hostGeneration
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in flyer.removeFromSuperlayer(); self?.flyerGone(generation) }
        flyer.position = target
        flyer.opacity = 0
        flyer.transform = CATransform3DMakeScale(end, end, 1)
        flyer.add(group, forKey: "flight")
        CATransaction.commit()
        reactionFXLog.debug("launch d=\(Double(distance), privacy: .public) D=\(duration, privacy: .public)")
        return duration
    }

    private func land(_ token: Token, probe: UIView, bare: Bool, appearing: Bool, reduce: Bool) {
        // The chip's pop or bump. No haptic: the action's single one played at the tap.
        token.finishLanding(.animated)
        guard effects < Policy.maxEffects else { token.finish(); return }
        let info = ProcessInfo.processInfo
        let quiet = info.isLowPowerModeEnabled || info.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
        let done: () -> Void = { [weak self] in self?.effects -= 1; token.finish() }
        var layers = 0
        if bare {
            effects += 1
            layers = UnyriReactionLayers.halo(in: probe.layer, rect: probe.bounds, own: token.own, reduce: reduce, quiet: quiet, done: done)
        } else if let surface = surfaces.object(forKey: token.messageKey as NSString), surface.window === probe.window {
            effects += 1
            lingering[token.messageKey] = CACurrentMediaTime() + Policy.surfaceLinger
            layers = surface.play(chip: probe.convert(probe.bounds, to: surface), own: token.own, grew: !appearing,
                                  reduce: reduce, quiet: quiet, done: done)
        } else { token.finish() }
        reactionFXLog.debug("land own=\(token.own, privacy: .public) bare=\(bare, privacy: .public) quiet=\(quiet, privacy: .public) layers=\(layers, privacy: .public)")
    }

    /// The unobscured part of the scroll view holding `view`, measured like the message menu does.
    private static func viewport(of view: UIView, in window: UIWindow) -> CGRect {
        var node = view.superview
        while let current = node {
            if let scroll = current as? UIScrollView {
                return scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window)
            }
            node = current.superview
        }
        return window.bounds
    }

    private func spawnFlyer(_ token: Token, origin: Origin, window: UIWindow) {
        let image = UnyriEmojiBitmap.image(token.emoji, size: Policy.flyerGlyph, scale: window.traitCollection.displayScale)
        let layer = CALayer()
        layer.contents = image.cgImage
        layer.contentsScale = image.scale
        layer.bounds = CGRect(origin: .zero, size: image.size)
        layer.position = origin.point
        // From the menu it starts as the pressed glyph and lifts a little; from a fingertip it pops out.
        let menu = origin.source == .menu
        let base = max(0.5, origin.glyph / Policy.flyerGlyph)
        let from: CGFloat = menu ? base : 0.3, to: CGFloat = menu ? base * 1.06 : 0.75
        let curve = CAMediaTimingFunction(controlPoints: 0.2, 0.8, 0.3, 1)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.transform = CATransform3DMakeScale(to, to, 1)
        host(in: window).layer.addSublayer(layer)
        let lift = CABasicAnimation(keyPath: "transform.scale")
        lift.fromValue = from
        lift.toValue = to
        lift.duration = 0.12
        lift.timingFunction = curve
        layer.add(lift, forKey: "lift")
        if !menu {
            let appear = CABasicAnimation(keyPath: "opacity")
            appear.fromValue = 0
            appear.toValue = 1
            appear.duration = 0.12
            appear.timingFunction = curve
            layer.add(appear, forKey: "appear")
        }
        CATransaction.commit()
        token.flyer = layer
        token.flyerScale = to
        flyers += 1
        reactionFXLog.debug("spawn \(String(describing: origin.source), privacy: .public) scale=\(Double(to), privacy: .public)")
    }

    /// No chip to fly to: the flyer shrinks and fades where it is.
    private func dissolve(_ token: Token) {
        guard let flyer = token.flyer else { return }
        token.flyer = nil
        let presented = flyer.presentation()
        let scale = (presented?.value(forKeyPath: "transform.scale") as? CGFloat) ?? token.flyerScale
        let opacity = presented?.opacity ?? 1
        let generation = hostGeneration
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in flyer.removeFromSuperlayer(); self?.flyerGone(generation) }
        let shrink = CABasicAnimation(keyPath: "transform.scale")
        shrink.fromValue = scale
        shrink.toValue = scale * 0.6
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = opacity
        fade.toValue = 0
        let group = CAAnimationGroup()
        group.animations = [shrink, fade]
        for animation in [shrink, fade, group] as [CAAnimation] { animation.duration = 0.14 }
        group.timingFunction = CAMediaTimingFunction(name: .easeIn)
        flyer.opacity = 0
        flyer.transform = CATransform3DMakeScale(scale * 0.6, scale * 0.6, 1)
        flyer.add(group, forKey: "dissolve")
        CATransaction.commit()
    }

    private func host(in window: UIWindow) -> UIView {
        if let host, host.window === window { window.bringSubviewToFront(host); return host }
        host?.removeFromSuperview()
        let view = UIView(frame: window.bounds)
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        window.addSubview(view)
        host = view
        return view
    }

    private func flyerGone(_ generation: Int) {
        guard generation == hostGeneration else { return }
        flyers = max(0, flyers - 1)
        guard flyers == 0, let host else { return }
        host.removeFromSuperview()
        self.host = nil
        reactionFXLog.debug("host removed")
    }

    /// The chat closed: flyers and their host go now; effects inside cells finish on their own.
    func cancelAll() {
        for token in pending.values { token.flyer?.removeFromSuperlayer(); token.flyer = nil }
        pending.removeAll()
        armed = nil
        lingering.removeAll()
        host?.removeFromSuperview()
        host = nil
        flyers = 0
        hostGeneration &+= 1
    }
}

/// The landing on one reaction chip: invisible while its flyer or effect is coming, then a
/// pop (new chip) or a bump (one more on it); a dip when my reaction leaves a chip others
/// keep, a shrink-out when a chip goes and others stay. Identity transform at rest.
struct UnyriReactionLanding: ViewModifier {
    let reaction: ReactionSummary
    /// Nil where nothing plays (album rows, previews, unsent messages).
    let burstKey: String?
    /// On the wallpaper: a halo instead of the ring inside the bubble.
    let bare: Bool
    /// The emoji's point size, its slot's width and the slot's inset from the leading edge.
    let glyph: CGFloat
    let slot: CGFloat
    let lead: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.unyriMessagePreview) private var inPreview
    @Environment(\.layoutDirection) private var layoutDirection
    /// Plain flags: setting them re-renders nothing, so chips scrolling into view cost no
    /// extra pass.
    private final class Marks {
        var appeared = false
        var renderedAwaiting = false
    }
    @State private var marks = Marks()
    /// Shows a chip whose first frame hid it for a landing that expired before it appeared.
    @State private var released = false
    @State private var hidden = false
    @State private var pop: CGFloat = 1
    @State private var entry: CGFloat = 0.8
    @State private var bump: CGFloat = 0
    @State private var dip: CGFloat = 0
    /// One probe per landing in progress: a second landing on this chip (a peer's, then
    /// mine) gets a fresh view that reports, and never cuts the first one's flight or halo.
    @State private var probes: [Probe] = []
    private struct Probe: Identifiable {
        let token: UnyriReactionBursts.Token
        let appearing: Bool
        var id: ObjectIdentifier { ObjectIdentifier(token) }
    }
    private struct Face: Equatable {
        let count: Int
        let mine: Bool
    }
    private var key: String? { inPreview ? nil : burstKey }

    func body(content: Content) -> some View {
        // A plain read, not observed: a new chip whose landing is coming is invisible from
        // its first frame, so the static chip never flashes before the flyer arrives.
        let awaiting = !released && !marks.appeared && key.map { UnyriReactionBursts.shared.isPending($0) } == true
        marks.renderedAwaiting = awaiting
        let face = Face(count: reaction.count, mine: reaction.isMine)
        return content
            .modifier(UnyriReactionChipMotion(pop: pop, from: entry, bump: bump, dip: dip))
            .opacity(awaiting || hidden ? 0 : 1)
            // After the motion, so the probe sits at the untransformed frame.
            .background {
                ForEach(probes) { probe in
                    UnyriReactionBurstProbe(token: probe.token, appearing: probe.appearing, bare: bare, glyph: glyph, slot: slot,
                                            lead: lead, rtl: layoutDirection == .rightToLeft)
                }
            }
            .transition(.asymmetric(insertion: .identity, removal: reduceMotion ? .identity : .scale(scale: 0.3).combined(with: .opacity)))
            .onAppear { claim(appearing: true) }
            .onChange(of: face) { [face] new in
                if new.count > face.count { claim(appearing: false) }
                else if face.mine, !new.mine, !reduceMotion { withAnimation(.linear(duration: 0.24)) { dip += 1 } }
            }
    }

    private func claim(appearing new: Bool) {
        marks.appeared = true
        guard let key, let token = UnyriReactionBursts.shared.take(key) else {
            if marks.renderedAwaiting { released = true }
            return
        }
        if new { hidden = true; pop = 0; entry = token.flyer != nil ? 0.8 : 0.3 }
        let reduce = reduceMotion
        token.onLand = { landing in
            guard landing == .animated else { hidden = false; pop = 1; return }
            if reduce {
                pop = 1
                if new { withAnimation(.easeOut(duration: 0.2)) { hidden = false } }
                return
            }
            hidden = false
            if new { withAnimation(.linear(duration: 0.40)) { pop = 1 } }
            else { withAnimation(.linear(duration: 0.34)) { bump += 1 } }
        }
        // Only its own probe goes: an earlier landing ending must not unmount a newer one.
        let id = ObjectIdentifier(token)
        token.onDone = { probes.removeAll { $0.id == id } }
        probes.append(Probe(token: token, appearing: new))
    }
}

/// A chip's pop, bump and dip as one transform, anchored at its centre.
struct UnyriReactionChipMotion: GeometryEffect {
    var pop: CGFloat
    let from: CGFloat
    var bump: CGFloat
    var dip: CGFloat
    var animatableData: AnimatablePair<CGFloat, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(pop, AnimatablePair(bump, dip)) }
        set { pop = newValue.first; bump = newValue.second.first; dip = newValue.second.second }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        let landing = UnyriReactionBurstPolicy.popScale(pop, from: from)
        // Bump and dip count up by one per play; the fraction is that play's progress.
        let extra = UnyriReactionBurstPolicy.bumpScale(bump - bump.rounded(.down)) * UnyriReactionBurstPolicy.dipScale(dip - dip.rounded(.down))
        let sx = landing.width * extra, sy = landing.height * extra
        guard sx != 1 || sy != 1 else { return ProjectionTransform(CGAffineTransform.identity) }
        return ProjectionTransform(CGAffineTransform(translationX: size.width / 2 * (1 - sx), y: size.height / 2 * (1 - sy))
            .scaledBy(x: sx, y: sy))
    }
}

/// A chip gives a little under the finger; nothing moves with Reduce Motion.
struct UnyriReactionChipPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.92 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.2, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Sits behind a chip while its landing is coming and reports, once, where the emoji is.
/// A bare chip's halo is drawn in its layer, so it scrolls with the cell.
struct UnyriReactionBurstProbe: UIViewRepresentable {
    let token: UnyriReactionBursts.Token
    let appearing: Bool
    let bare: Bool
    let glyph: CGFloat
    let slot: CGFloat
    let lead: CGFloat
    let rtl: Bool

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.spec = self
        return view
    }
    func updateUIView(_ view: ProbeView, context: Context) { view.spec = self }

    final class ProbeView: UIView {
        var spec: UnyriReactionBurstProbe?
        private var reported = false
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            backgroundColor = .clear
            clipsToBounds = false
            isAccessibilityElement = false
            accessibilityElementsHidden = true
        }
        required init?(coder: NSCoder) { nil }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { setNeedsLayout() }
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            guard !reported, window != nil, bounds.width > 0, bounds.height > 0, let spec else { return }
            reported = true
            let x = UnyriReactionBurstPolicy.glyphCenterX(width: bounds.width, lead: spec.lead, slot: spec.slot, rtl: spec.rtl)
            UnyriReactionBursts.shared.targetReady(spec.token, probe: self, glyphPoint: CGPoint(x: x, y: bounds.midY),
                                                   glyph: spec.glyph, bare: spec.bare, appearing: spec.appearing)
        }
    }
}

/// An inert view behind a menu emoji cell, so a pick knows where its emoji was.
struct UnyriMenuReactionCell: UIViewRepresentable {
    let cells: NSMapTable<NSString, UIView>
    let emoji: String
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        cells.setObject(view, forKey: emoji as NSString)
        return view
    }
    func updateUIView(_ view: UIView, context: Context) { cells.setObject(view, forKey: emoji as NSString) }
}

/// The bubble's effect layer, above its fill and under its text and chips. Mounted only
/// while a landing is coming or playing on this message; idle it has no sublayers.
struct UnyriReactionSurface: UIViewRepresentable {
    let messageKey: String
    let shape: AnyShape
    /// A light bubble gets a brighter crest and flash.
    let light: Bool
    func makeUIView(context: Context) -> UnyriReactionSurfaceView {
        let view = UnyriReactionSurfaceView()
        apply(view, context)
        return view
    }
    func updateUIView(_ view: UnyriReactionSurfaceView, context: Context) { apply(view, context) }
    private func apply(_ view: UnyriReactionSurfaceView, _ context: Context) {
        view.shape = shape
        view.light = light
        if !context.environment.unyriMessagePreview { UnyriReactionBursts.shared.register(surface: view, messageKey: messageKey) }
    }
}

final class UnyriReactionSurfaceView: UIView {
    var shape: AnyShape?
    var light = false
    private var boxes: [CALayer] = []
    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        isAccessibilityElement = false
        accessibilityElementsHidden = true
    }
    required init?(coder: NSCoder) { nil }

    /// A flash under the chip and, with motion, the light ring (with an echo for my own
    /// reaction), masked to the bubble. Returns the layers made; `done` runs once all end.
    func play(chip: CGRect, own: Bool, grew: Bool, reduce: Bool, quiet: Bool, done: @escaping () -> Void) -> Int {
        guard bounds.width > 1, bounds.height > 1, let shape else { done(); return 0 }
        let box = CALayer()
        box.frame = bounds
        let mask = CAShapeLayer()
        mask.path = shape.path(in: bounds).cgPath
        box.mask = mask
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self, weak box] in
            box?.removeFromSuperlayer()
            self?.boxes.removeAll { $0 === box }
            done()
        }
        layer.addSublayer(box)
        boxes.append(box)
        var layers = 3 // box, mask, flash
        UnyriReactionLayers.flash(in: box, capsule: chip, peak: Float((light ? 0.45 : 0.30) * (own ? 1 : 0.5)),
                                  duration: reduce ? 0.32 : 0.26)
        if !reduce, let texture = UnyriReactionRingTexture.image(light: light) {
            let radius = UnyriReactionBurstPolicy.rippleRadius(chip: chip, bounds: bounds, own: own, grew: grew)
            let duration = UnyriReactionBurstPolicy.rippleDuration(radius: radius)
            let center = CGPoint(x: chip.midX, y: chip.midY)
            UnyriReactionLayers.ring(in: box, texture: texture, center: center, radius: radius, delay: 0, duration: duration,
                                     peak: own ? 1 : (grew ? 0.5 : 0.6))
            layers += 1
            if own && !quiet {
                UnyriReactionLayers.ring(in: box, texture: texture, center: center, radius: radius * 0.62, delay: 0.08,
                                         duration: duration * 0.85, peak: 0.55)
                layers += 1
            }
        }
        CATransaction.commit()
        return layers
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // The bubble may still be growing while an effect plays.
        guard !boxes.isEmpty, let shape else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for box in boxes {
            box.frame = bounds
            (box.mask as? CAShapeLayer)?.path = shape.path(in: bounds).cgPath
        }
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // Removing the layers ends their animations, so the completions still release the counters.
        guard window == nil else { return }
        boxes.forEach { $0.removeFromSuperlayer() }
        boxes.removeAll()
    }
}

/// Builders for the effect layers. All white at low alpha; the emoji keep their colours.
@MainActor
enum UnyriReactionLayers {
    static func ring(in box: CALayer, texture: CGImage, center: CGPoint, radius: CGFloat, delay: Double, duration: Double, peak: Float) {
        let ring = CALayer()
        ring.contents = texture
        ring.bounds = CGRect(x: 0, y: 0, width: radius * 2, height: radius * 2)
        ring.position = center
        ring.opacity = 0
        box.addSublayer(ring)
        let grow = CABasicAnimation(keyPath: "transform.scale")
        grow.fromValue = 0.06
        grow.toValue = 1
        grow.timingFunction = CAMediaTimingFunction(controlPoints: 0.15, 0.75, 0.3, 1)
        let fade = CAKeyframeAnimation(keyPath: "opacity")
        fade.values = [0, peak, peak * 0.85, 0]
        fade.keyTimes = [0, 0.08, 0.4, 1]
        let group = CAAnimationGroup()
        group.animations = [grow, fade]
        for animation in [grow, fade, group] as [CAAnimation] { animation.duration = duration }
        if delay > 0 { group.beginTime = CACurrentMediaTime() + delay; group.fillMode = .backwards }
        ring.add(group, forKey: "ripple")
    }

    static func flash(in box: CALayer, capsule rect: CGRect, peak: Float, duration: Double) {
        let flash = CAShapeLayer()
        flash.path = UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2).cgPath
        flash.fillColor = UIColor.white.cgColor
        flash.opacity = 0
        box.addSublayer(flash)
        let glow = CAKeyframeAnimation(keyPath: "opacity")
        glow.values = [0, peak, 0]
        glow.keyTimes = [0, 0.15, 1]
        glow.duration = duration
        glow.timingFunction = CAMediaTimingFunction(name: .easeOut)
        flash.add(glow, forKey: "flash")
    }

    /// A capsule outline that swells off a bare chip and fades (no flash: the opaque chip
    /// would hide it). Returns the layers made; `done` runs once all end.
    static func halo(in layer: CALayer, rect: CGRect, own: Bool, reduce: Bool, quiet: Bool, done: @escaping () -> Void) -> Int {
        func capsule(_ r: CGRect) -> CGPath { UIBezierPath(roundedRect: r, cornerRadius: r.height / 2).cgPath }
        var made: [CALayer] = []
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        // Set before the animations: it covers the ones added after it.
        CATransaction.setCompletionBlock { made.forEach { $0.removeFromSuperlayer() }; done() }
        func add(delay: Double, peak: Float) {
            let halo = CAShapeLayer()
            halo.fillColor = nil
            halo.strokeColor = UIColor.white.cgColor
            halo.lineWidth = 1.25
            halo.opacity = 0
            layer.addSublayer(halo)
            made.append(halo)
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.toValue = 0
            if reduce {
                // A still outline that only fades.
                halo.path = capsule(rect.insetBy(dx: -4, dy: -3))
                fade.fromValue = 0.4
                fade.duration = 0.3
                halo.add(fade, forKey: "halo")
                return
            }
            halo.path = capsule(rect.insetBy(dx: -12, dy: -9))
            fade.fromValue = peak
            let swell = CABasicAnimation(keyPath: "path")
            swell.fromValue = capsule(rect)
            swell.toValue = halo.path
            let group = CAAnimationGroup()
            group.animations = [swell, fade]
            for animation in [swell, fade, group] as [CAAnimation] { animation.duration = 0.40 }
            group.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 0.7, 0.3, 1)
            if delay > 0 { group.beginTime = CACurrentMediaTime() + delay; group.fillMode = .backwards }
            halo.add(group, forKey: "halo")
        }
        add(delay: 0, peak: own ? 0.55 : 0.30)
        if own && !quiet && !reduce { add(delay: 0.08, peak: 0.35) }
        CATransaction.commit()
        return made.count
    }
}

/// The flyer's bitmap of an emoji, drawn once per emoji, size and screen scale.
@MainActor
enum UnyriEmojiBitmap {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 24
        return cache
    }()

    static func image(_ emoji: String, size: CGFloat, scale: CGFloat) -> UIImage {
        let key = "\(emoji)|\(size)|\(scale)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: size)]
        let text = emoji as NSString
        let measured = text.size(withAttributes: attributes)
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: CGSize(width: ceil(measured.width), height: ceil(measured.height)), format: format)
            .image { _ in text.draw(at: .zero, withAttributes: attributes) }
        cache.setObject(image, forKey: key)
        return image
    }
}

/// The light ring: a soft dark trough just inside a bright crest, like light bending
/// through glass. Two 320 px images (dark and light bubbles), drawn on first use.
@MainActor
enum UnyriReactionRingTexture {
    private static var dark: CGImage?
    private static var bright: CGImage?

    static func image(light: Bool) -> CGImage? {
        if let cached = light ? bright : dark { return cached }
        let made = draw(crest: light ? 0.60 : 0.22, trough: light ? 0.08 : 0.10)
        if light { bright = made } else { dark = made }
        return made
    }

    private static func draw(crest: CGFloat, trough: CGFloat) -> CGImage? {
        let side = 320
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let components: [CGFloat] = [0, 0, 0, 0, 0, 0, 0, trough, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, crest, 1, 1, 1, 0]
        let locations: [CGFloat] = [0, 0.70, 0.78, 0.80, 0.90, 1.0]
        guard let gradient = CGGradient(colorSpace: space, colorComponents: components, locations: locations, count: locations.count)
        else { return nil }
        let center = CGPoint(x: CGFloat(side) / 2, y: CGFloat(side) / 2)
        context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: CGFloat(side) / 2, options: [])
        return context.makeImage()
    }
}
