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
    @ViewBuilder func body(content: Content) -> some View {
        if let quick {
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
        guard let preview = (anchor as? UnyriMessageMenuAnchorView)?.preview else { return }
        while let next = parent.presentedViewController { parent = next }
        guard !parent.isBeingDismissed else { return }
        let keyboardFrame = window.keyboardLayoutGuide.layoutFrame
        let keyboardInset = keyboardFrame.height > 0 && keyboardFrame.minY > 0
            ? max(window.safeAreaInsets.bottom, window.bounds.maxY - keyboardFrame.minY) : window.safeAreaInsets.bottom
        let host = UIHostingController(rootView: UnyriMessageMenuOverlay(
            preview: preview, anchor: CGRect(origin: rect.origin, size: previewSize),
            insets: UIEdgeInsets(top: window.safeAreaInsets.top, left: window.safeAreaInsets.left,
                                 bottom: keyboardInset,
                                 right: window.safeAreaInsets.right), outgoing: outgoing,
            primary: primary, extra: extra, deletion: deletion, reactions: reactions, selectedReaction: selectedReaction,
            choose: { [weak self] action in self?.dismiss(then: action) },
            react: { [weak self] emoji in
                guard let self, self.overlay != nil, !self.reactionSelected else { return }
                self.reactionSelected = true
                // Commit the durable reaction immediately. UIKit dismissal is
                // presentation cleanup, not the owner of the user's selection.
                react(emoji)
                self.dismiss()
            },
            close: { [weak self] in self?.dismiss() })
                .environment(\.locale, Locale(identifier: UnyriL10n.language))
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
        var environment = context.environment
        environment.unyriMessagePreview = true
        view.preview = preview.map { AnyView($0.environment(\.self, environment)) }
        return view
    }
    func updateUIView(_ view: UnyriMessageMenuAnchorView, context: Context) {
        view.open = open; view.menuEnabled = enabled; view.previewSize = previewSize
        var environment = context.environment
        environment.unyriMessagePreview = true
        view.preview = preview.map { AnyView($0.environment(\.self, environment)) }
    }
    static func dismantleUIView(_ view: UnyriMessageMenuAnchorView, coordinator: ()) {
        view.presenter?.dismiss()
        view.detach()
    }
}

final class UnyriMessageMenuAnchorView: UIView, UIGestureRecognizerDelegate {
    weak var presenter: UnyriMessageMenuPresenter?
    var open: (() -> Void)?
    var menuEnabled = true
    var preview: AnyView?
    var previewSize: CGSize?
    var lastTouchPoint: CGPoint?
    var lastTouchWasReaction = false
    private weak var gestureWindow: UIWindow?
    private lazy var press: UILongPressGestureRecognizer = {
        let gesture = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        gesture.minimumPressDuration = 0.35
        gesture.allowableMovement = 10
        gesture.cancelsTouchesInView = true
        gesture.delegate = self
        return gesture
    }()
    override func didMoveToWindow() {
        super.didMoveToWindow()
        detach()
        if let window { window.addGestureRecognizer(press); gestureWindow = window }
    }
    func detach() { gestureWindow?.removeGestureRecognizer(press); gestureWindow = nil }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard menuEnabled, window != nil, presenter?.canOpen == true,
              bounds.contains(touch.location(in: self)), let touched = touch.view else { return false }
        lastTouchPoint = touch.location(in: self)
        if let bar = presenter?.reactionTargets.object(forKey: "__reaction_bar") {
            lastTouchWasReaction = bar.bounds.insetBy(dx: -4, dy: -4).contains(touch.location(in: bar))
        } else { lastTouchWasReaction = false }
        // Let the native reaction button receive the first tap immediately;
        // neither a message long-press nor its double-tap owns this region.
        if lastTouchWasReaction { return false }
        // A window recognizer also sees touches in sheets presented over the chat.
        // Only the owning controller's content may open this message menu.
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController {
                return touched.isDescendant(of: controller.view)
            }
            responder = current.next
        }
        return false
    }
    @objc private func held(_ gesture: UILongPressGestureRecognizer) {
        guard menuEnabled else { return }
        if gesture.state == .began { open?() }
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard menuEnabled, presenter?.canOpen == true else { return false }
        var parent = superview
        while let view = parent {
            if let scroll = view as? UIScrollView, scroll.isDragging || scroll.isDecelerating { return false }
            parent = view.superview
        }
        return true
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
    @StateObject private var menu = UnyriMessageMenuPresenter()
    var body: some View {
        CallHistoryRow(call: call, owner: owner, earlierHistory: earlierHistory, menuPresenter: menu, openMenu: open, selection: selecting ? selected : nil)
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
    let choose: (@escaping () -> Void) -> Void
    let react: (String) -> Void
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var page = 0
    @State private var extendedReactions = false
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
                        Button { if emoji == "＋" { UnyriHaptics.selection(); extendedReactions = true } else { react(emoji) }
                        } label: {
                            ZStack {
                                if selectedReaction == emoji {
                                    Circle().fill(Color.accentColor.opacity(0.2)).frame(width: 38, height: 38)
                                }
                                if emoji == "＋" {
                                    VStack(spacing: 2) { Image(systemName: "square.grid.2x2.fill").font(.system(size: 18)); Text(Locale.current.language.languageCode?.identifier == "ru" ? "Ещё" : "More").font(.system(size: 10)) }.foregroundColor(UnyriColors.accentSoft)
                                } else { Text(emoji).font(.system(size: 30)) }
                            }.frame(width: cellWidth, height: 44).contentShape(Rectangle())
                        }.buttonStyle(.plain)
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

struct UnyriAttachmentGallery: View {
    let cameraEnabled: Bool
    let camera: () -> Void
    let library: () -> Void
    let files: () -> Void
    let selected: ([UnyriGallerySource], MediaSendIntent) -> Void
    @Binding var selection: [String]
    let captures: [URL]
    var sendingAllowed = true
    var selectionLimit = 10
    @Binding var caption: String
    var close: () -> Void = {}
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
    private var sendChoice: MediaGallerySendChoice { .resolve(sendChoiceRaw, legacyHighQuality: highQuality) }
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
                Menu {
                    Button("Recent") { UnyriHaptics.selection(); album = nil; limit = 300; reload() }
                    if selection.isEmpty { Button("System photo picker…") { UnyriHaptics.press(); library() } }
                    ForEach(albums, id: \.localIdentifier) { item in Button(item.localizedTitle ?? "Photos") { UnyriHaptics.selection(); album = item; limit = 300; reload() } }
                } label: { HStack(spacing: 6) { Text(album?.localizedTitle ?? UnyriL10n.string("Photos and videos", comment: "")).lineLimit(1); Image(systemName: "chevron.down").font(.caption.weight(.semibold)) }.font(.subheadline.weight(.semibold)).foregroundStyle(pickerInk).frame(minHeight: 44).contentShape(Rectangle()) }
                Spacer(minLength: 0)
                if !selection.isEmpty { Text("\(selection.count)").font(.caption).foregroundStyle(pickerInk.opacity(0.7)) }
                Button(action: close) { Image(systemName: "xmark").font(.system(size: 17)).foregroundStyle(pickerInk).frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel(Text("Close"))
            }.padding(.horizontal, 13).accessibilityElement(children: .contain).accessibilityIdentifier("gallery-source-header")
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
            VStack(spacing: 7) {
                    HStack(spacing: 7) {
                        TextField("Caption (optional)", text: $caption, axis: .vertical)
                            .font(.subheadline).lineLimit(1...3).foregroundStyle(pickerInk)
                            .textFieldStyle(.plain).padding(.leading, 11).padding(.vertical, 8)
                            .frame(minHeight: 44).contentShape(Rectangle())
                            .accessibilityIdentifier("gallery-caption")
                        Button { sendSelection() } label: {
                            Image(systemName: "arrow.up").font(.system(size: 20, weight: .semibold)).foregroundStyle(pickerInk)
                                .frame(width: 44, height: 44).background(pickerAccent.opacity(selection.isEmpty ? 0.3 : 1), in: Circle())
                        }.disabled(selection.isEmpty || submitted || !sendingAllowed)
                            .accessibilityLabel(Text(UnyriL10n.string("Add to message") + " (\(selection.count))")).accessibilityValue("\(selection.count)").accessibilityIdentifier("gallery-send")
                    }.padding(4).background(pickerInk.opacity(0.06), in: RoundedRectangle(cornerRadius: UnyriAttachmentPickerStyle.captionRadius))
                    HStack(spacing: 8) {
                        Button { UnyriHaptics.selection(); album = nil; limit = 300; reload() } label: {
                            Label("Photos and videos", systemImage: "photo.on.rectangle").font(.caption.weight(.medium)).lineLimit(1)
                                .foregroundStyle(pickerAccent).frame(maxWidth: .infinity, minHeight: 44)
                                .background(pickerInk.opacity(0.03), in: RoundedRectangle(cornerRadius: 12))
                                .contentShape(Rectangle())
                        }
                        Button { UnyriHaptics.press(); files() } label: {
                            Label("Files", systemImage: "doc.text").font(.caption.weight(.medium)).lineLimit(1)
                                .foregroundStyle(pickerInk.opacity(0.75)).frame(maxWidth: .infinity, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                    }.frame(minHeight: max(44, UnyriAttachmentPickerStyle.toolbarHeight))
            }.buttonStyle(.plain).padding(.horizontal, 10).padding(.top, 9).padding(.bottom, 4)
                .accessibilityElement(children: .contain).accessibilityIdentifier("gallery-footer")
        }.foregroundStyle(pickerInk).tint(pickerAccent).disabled(submitted).task {
            reload()
        }.onChange(of: phase) { value in if value == .active { reload() } }
        .alert("Up to 10 attachments at a time.", isPresented: $showLimit) { Button("OK", role: .cancel) {} }
        .alert("Some selected items are no longer available. Check photo access and select them again.", isPresented: $unavailableSelection) { Button("OK", role: .cancel) {} }
        .sheet(item: $preview) { item in
            UnyriGalleryPreview(item: item, selected: selection.contains(item.id), atLimit: selection.count >= selectionLimit, toggle: { toggle(item.id) })
        }
    }
    private func sendSelection() {
        guard !submitted, sendingAllowed, !selection.isEmpty else { return }
        var byID: [String: PHAsset] = [:]
        PHAsset.fetchAssets(withLocalIdentifiers: selection, options: nil).enumerateObjects { asset, _, _ in byID[asset.localIdentifier] = asset }
        let captureByID = Dictionary(uniqueKeysWithValues: captures.map { ($0.absoluteString, $0) })
        let sources: [UnyriGallerySource] = selection.compactMap { id in
            if let asset = byID[id] { return .asset(asset) }
            if let url = captureByID[id] { return .capture(url) }
            return nil
        }
        guard sources.count == selection.count else { unavailableSelection = true; return }
        UnyriHaptics.press(); submitted = true
        selected(sources, sendChoice.intent(caption: caption))
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
        let options = PHFetchOptions(); options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]; options.fetchLimit = limit
        options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        var collections: [PHAssetCollection] = []
        for type in [PHAssetCollectionType.album, .smartAlbum] {
            PHAssetCollection.fetchAssetCollections(with: type, subtype: .any, options: nil).enumerateObjects { item, _, _ in collections.append(item) }
        }
        albums = collections
        let result = album.map { PHAsset.fetchAssets(in: $0, options: options) } ?? PHAsset.fetchAssets(with: options)
        var loaded: [PHAsset] = []; result.enumerateObjects { asset, _, _ in loaded.append(asset) }
        assets = loaded
        var allowed = Set(captures.map(\.absoluteString))
        PHAsset.fetchAssets(withLocalIdentifiers: selection, options: nil).enumerateObjects { item, _, _ in allowed.insert(item.localIdentifier) }
        selection = selection.filter { allowed.contains($0) }
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
            switch item.source {
            case .capture(let url):
                image = await Task.detached(priority: .utility) {
                    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                          let frame = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1600] as CFDictionary) else { return nil as UIImage? }
                    return UIImage(cgImage: frame)
                }.value
                failed = image == nil
            case .asset(let asset):
                if asset.mediaType == .video {
                    let options = PHVideoRequestOptions(); options.isNetworkAccessAllowed = true
                    request = PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { value, _ in
                        DispatchQueue.main.async { guard active else { return }; if let value { player = AVPlayer(playerItem: value) } else { failed = true } }
                    }
                } else {
                    let options = PHImageRequestOptions(); options.isNetworkAccessAllowed = true; options.resizeMode = .fast
                    request = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 1600, height: 1600), contentMode: .aspectFit, options: options) { value, info in
                        DispatchQueue.main.async { guard active else { return }; image = value; if value == nil && info?[PHImageResultIsDegradedKey] as? Bool != true { failed = true } }
                    }
                }
            }
        }.onDisappear { active = false; if let request { PHImageManager.default().cancelImageRequest(request) }; player?.pause(); player = nil }
        .onChange(of: phase) { if $0 != .active { player?.pause() } }
    }
}

struct UnyriGalleryThumbnail: View {
    let asset: PHAsset
    @State private var image: UIImage?
    @State private var request: PHImageRequestID?
    var body: some View {
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
            .overlay(alignment: .bottomLeading) { if asset.mediaType == .video { Text(FilePresentation.duration(Int64(max(0, asset.duration) * 1000))).font(.caption.monospacedDigit()).foregroundStyle(.white).padding(6).background(.black.opacity(0.6)).accessibilityHidden(true) } }
            .onAppear {
                let options = PHImageRequestOptions(); options.deliveryMode = .opportunistic; options.resizeMode = .fast; options.isNetworkAccessAllowed = false
                request = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 240, height: 240), contentMode: .aspectFill, options: options) { result, _ in image = result }
            }.onDisappear { if let request { PHImageManager.default().cancelImageRequest(request) }; request = nil }
    }
}

private struct UnyriGalleryCamera: UIViewRepresentable {
    func makeUIView(context: Context) -> Preview { Preview() }
    func updateUIView(_ view: Preview, context: Context) {}
    static func dismantleUIView(_ view: Preview, coordinator: ()) { view.stop() }
    final class Preview: UIView {
        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "unyri.gallery.preview")
        private let preview = AVCaptureVideoPreviewLayer()
        private var captureClaim: NonCallCaptureOwnership.Claim?
        override init(frame: CGRect) {
            super.init(frame: frame)
            preview.session = session; preview.videoGravity = .resizeAspectFill; layer.addSublayer(preview)
            guard let generation = NonCallCaptureOwnership.currentGeneration(),
                  let claim = NonCallCaptureOwnership.acquire(.preview, generation: generation, stop: { [queue, session] claim in
                      queue.async {
                          session.stopRunning()
                          Task { @MainActor in NonCallCaptureOwnership.release(claim) }
                      }
                  }) else { return }
            captureClaim = claim
            queue.async { [session, claim] in
                guard claim.permit.isValid else { return }
                session.beginConfiguration(); session.sessionPreset = .low
                if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                   let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) { session.addInput(input) }
                session.commitConfiguration()
                if claim.permit.isValid && !session.inputs.isEmpty { session.startRunning() }
                if session.inputs.isEmpty { Task { @MainActor in NonCallCaptureOwnership.release(claim) } }
            }
        }
        required init?(coder: NSCoder) { return nil }
        override func layoutSubviews() { super.layoutSubviews(); preview.frame = bounds }
        func stop() {
            guard let claim = captureClaim else { return }; captureClaim = nil
            NonCallCaptureOwnership.stop(claim)
        }
    }
}

struct UnyriCaptureThumbnail: View {
    let url: URL
    @State private var image: UIImage?
    var body: some View {
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
            .task(id: url) {
                image = await Task.detached(priority: .utility) {
                    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                          let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 240, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil as UIImage? }
                    return UIImage(cgImage: bitmap)
                }.value
            }
    }
}

/// CSS-normalized elliptical corners from the approved Lens reference.
struct UnyriLensContour: Shape {
    var outgoing: Bool
    var attachment = false
    var reaction = false
    func path(in rect: CGRect) -> Path {
        var r: [CGFloat] = reaction ? [7,7,12,12,9,9,11,11] : attachment ? [10,17,28,25,18,24,24,16] : [10,18,35,26,19,25,29,15]
        let w = rect.width, h = rect.height
        let scale = min(1, min(w / (r[0]+r[2]), min(w / (r[4]+r[6]), min(h / (r[1]+r[7]), h / (r[3]+r[5])))))
        r = r.map { $0 * scale }
        let k: CGFloat = 0.55228475
        var p = Path()
        p.move(to: CGPoint(x:r[0],y:0));p.addLine(to: CGPoint(x:w-r[2],y:0))
        p.addCurve(to: CGPoint(x:w,y:r[3]), control1: CGPoint(x:w-r[2]+r[2]*k,y:0), control2: CGPoint(x:w,y:r[3]-r[3]*k))
        p.addLine(to: CGPoint(x:w,y:h-r[5]))
        p.addCurve(to: CGPoint(x:w-r[4],y:h), control1: CGPoint(x:w,y:h-r[5]+r[5]*k), control2: CGPoint(x:w-r[4]+r[4]*k,y:h))
        p.addLine(to: CGPoint(x:r[6],y:h))
        p.addCurve(to: CGPoint(x:0,y:h-r[7]), control1: CGPoint(x:r[6]-r[6]*k,y:h), control2: CGPoint(x:0,y:h-r[7]+r[7]*k))
        p.addLine(to: CGPoint(x:0,y:r[1]))
        p.addCurve(to: CGPoint(x:r[0],y:0), control1: CGPoint(x:0,y:r[1]-r[1]*k), control2: CGPoint(x:r[0]-r[0]*k,y:0));p.closeSubpath()
        if outgoing { p = p.applying(CGAffineTransform(a:-1,b:0,c:0,d:1,tx:w,ty:0)) }
        return p.applying(CGAffineTransform(translationX:rect.minX,y:rect.minY))
    }
}

struct UnyriLensSurface: View {
    let outgoing: Bool
    var attachment = false
    private var colors: [Color] {
        if attachment { return [Color(hex:0x263E4D),Color(hex:0x1A2938)] }
        return outgoing ? [Color(hex:0x453A59),Color(hex:0x302D46),Color(hex:0x26263B)]
                        : [Color(hex:0x2C4352),Color(hex:0x1B2D3B),Color(hex:0x192834)]
    }
    var body: some View {
        let shape = UnyriLensContour(outgoing:outgoing,attachment:attachment)
        GeometryReader { proxy in
            let w = max(1,proxy.size.width), h = max(1,proxy.size.height)
            let dx: CGFloat = outgoing && !attachment ? -0.819152 : 0.819152
            let dy: CGFloat = 0.573576
            let length = w * abs(dx) + h * dy
            let start = UnitPoint(x:0.5-dx*length/(2*w),y:0.5-dy*length/(2*h))
            let end = UnitPoint(x:0.5+dx*length/(2*w),y:0.5+dy*length/(2*h))
            let stops = colors.enumerated().map { index, color in
                Gradient.Stop(color:color,location:colors.count == 3 ? [CGFloat(0),0.65,1][index] : CGFloat(index))
            }
            shape.fill(LinearGradient(gradient:Gradient(stops:stops),startPoint:start,endPoint:end))
                .overlay(shape.stroke(Color(hex:outgoing && !attachment ? 0x716381 : 0x4B6676),lineWidth:1))
        }.allowsHitTesting(false)
    }
}

/// A wrapping reaction row with intrinsic width, including short text bubbles.
struct UnyriReactionRows: Layout {
    private func arrangement(_ proposal: ProposedViewSize, _ views: Subviews) -> (CGSize, [CGPoint]) {
        let limit = proposal.width ?? 280
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var width: CGFloat = 0
        var origins: [CGPoint] = []
        for view in views {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > limit { x = 0; y += rowHeight + 2; rowHeight = 0 }
            origins.append(CGPoint(x: x, y: y))
            width = max(width, x + size.width)
            x += size.width + 4
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


struct UnyriReactionBubbleFrames: PreferenceKey {
    static var defaultValue: [String: Anchor<CGRect>] { [:] }
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

/// Main body plus a content-sized lower lobe; no fill below the unused area.
struct UnyriReactionBubbleContour: Shape {
    var bodyHeight: CGFloat
    var reactionWidth: CGFloat
    let outgoing: Bool
    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(bodyHeight, reactionWidth) }
        set { bodyHeight = newValue.first; reactionWidth = newValue.second }
    }
    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        let body = min(h, max(0, bodyHeight))
        let extra = max(0, h - body)
        let left = min(w, max(0, w - reactionWidth))
        let r = min(24, min(w / 2, body / 2))
        // Match Android: one smooth join, without a straight neck above the pocket.
        let lr = min(16, min(reactionWidth / 2, extra * 0.85))
        let lobeTransitionWidth = min(18, min(max(0, left), reactionWidth * 0.55))
        let k: CGFloat = 0.55228475
        var p = Path()
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
        p.move(to: point(r, 0)); p.addLine(to: point(w - r, 0))
        p.addCurve(to: point(w, r), control1: point(w - r + r * k, 0), control2: point(w, r - r * k))
        if extra > 0.5 && reactionWidth > 0 && left > 0.5 {
            p.addLine(to: point(w, h - lr))
            p.addCurve(to: point(w - lr, h),
                       control1: point(w, h - lr + lr * k),
                       control2: point(w - lr + lr * k, h))
            p.addLine(to: point(left + lr, h))
            p.addCurve(to: point(left, h - lr),
                       control1: point(left + lr - lr * k, h),
                       control2: point(left, h - lr + lr * k))
            let minCorner = min(8, left / 2)
            let joinX = max(max(0, left - lobeTransitionWidth), minCorner)
            let joinHeight = max(0, h - lr - body)
            p.addCurve(to: point(joinX, body),
                       control1: point(left, body + joinHeight * 0.45),
                       control2: point(joinX + (left - joinX) * 0.55, body))
            let corner = min(r, joinX)
            let handle = corner * 0.55228475
            p.addLine(to: point(corner, body))
            p.addCurve(to: point(0, body - corner), control1: point(corner - handle, body), control2: point(0, body - corner + handle))
        } else {
            let bottom = extra > 0.5 && reactionWidth > 0 ? h : body
            p.addLine(to: point(w, bottom - r))
            p.addCurve(to: point(w - r, bottom), control1: point(w, bottom - r + r * k), control2: point(w - r + r * k, bottom))
            p.addLine(to: point(r, bottom))
            p.addCurve(to: point(0, bottom - r), control1: point(r - r * k, bottom), control2: point(0, bottom - r + r * k))
        }
        p.addLine(to: point(0, r)); p.addCurve(to: point(r, 0), control1: point(0, r - r * k), control2: point(r - r * k, 0)); p.closeSubpath()
        if !outgoing { p = p.applying(CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: w, ty: 0)) }
        return p.applying(CGAffineTransform(translationX: rect.minX, y: rect.minY))
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
                        }.foregroundStyle(Color(red: 0.71, green: 0.65, blue: 1))
                        HStack { Image(systemName: "magnifyingglass"); TextField(russian ? "Найти эмодзи" : "Find emoji", text: $query) }
                            .padding(12).background(Color.white.opacity(0.04), in: Capsule()).overlay(Capsule().stroke(Color.purple.opacity(0.25), lineWidth: 0.7))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 4) {
                                ForEach(unyriEmojiCategories.indices, id: \.self) { index in
                                    Button { UnyriHaptics.selection(); category = index; query = "" } label: {
                                        Image(systemName: symbols[index]).font(.system(size: 22)).frame(width: 44, height: 44)
                                            .background(category == index && query.isEmpty ? Color.purple.opacity(0.25) : .clear, in: Capsule())
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
                        Divider().overlay(Color.purple.opacity(0.2))
                        Button { UnyriHaptics.press(); expanded = true } label: {
                            HStack(spacing: 12) { Image(systemName: "square.grid.2x2.fill"); Text(russian ? "Все эмодзи" : "All emoji"); Spacer(); Image(systemName: "chevron.right") }.frame(height: 38)
                        }.foregroundStyle(Color(red: 0.71, green: 0.65, blue: 1))
                    }
                }.padding(14).frame(height: full ? max(0, min(max(240, geometry.size.height * 0.58), geometry.size.height - reservedBottom - 12)) : nil)
                    .background(Color(red: 0.09, green: 0.105, blue: 0.177).opacity(0.97), in: RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(red: 0.47, green: 0.49, blue: 0.88).opacity(0.65), lineWidth: 0.7))
                    .padding(.horizontal, 12).padding(.bottom, reservedBottom)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.16), value: full)
            }
        }.preferredColorScheme(.dark)
    }
}
