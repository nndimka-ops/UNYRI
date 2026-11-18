import Combine
import SwiftUI
import os
import UIKit

// MARK: - Row visibility

/// Whether a row hosted in the UIKit timeline is on screen. Cells know this; SwiftUI's
/// scroll-visibility modifiers need a SwiftUI scroll view and do not report here.
@MainActor
final class ChatHostedRowVisibility: ObservableObject {
    @Published var displayed = false
}

private struct ChatHostedRowVisibilityKey: EnvironmentKey {
    static var defaultValue: ChatHostedRowVisibility? { nil }
}

extension EnvironmentValues {
    var chatHostedRowVisibility: ChatHostedRowVisibility? {
        get { self[ChatHostedRowVisibilityKey.self] }
        set { self[ChatHostedRowVisibilityKey.self] = newValue }
    }
}

// MARK: - Live scene phase

/// The scene phase rows see. `ChatTimelineCellRoot` replaces a row's whole environment
/// with the copy taken when the cell was configured, so a phase change never reached rows
/// already on screen: a row configured while the app was inactive never decoded its
/// media, and one configured while active kept working in the background. One per
/// timeline; it publishes only when the phase really changes (no timer, no polling).
///
/// Rows follow only `.active` and `.background`. While Control Center, Notification
/// Center, a system alert, Face ID or the app-switcher gesture covers the app it is
/// `.inactive` but still on screen; passing that on cancelled running downloads (they
/// restart from byte 0) and stopped a playing video note. A timeline created while
/// inactive starts there and turns active on the first `.active`.
///
/// UIKit's own transitions drive it as well as SwiftUI's updates. SwiftUI passes a new
/// `scenePhase` to the timeline only when it happens to update the representable; on a
/// phone (owner, 14 Nov, 0.4.16) a return from the app switcher left every row at
/// `.background`: no photo was decoded again, not even rows scrolled into view later.
@MainActor
final class ChatHostedScene: ObservableObject {
    @Published private(set) var phase: ScenePhase
    private var scheduled: ScenePhase?
    private var observers: [AnyCancellable] = []

    init(phase: ScenePhase) {
        self.phase = phase
        let center = NotificationCenter.default
        observers.append(center.publisher(for: UIApplication.didBecomeActiveNotification).sink { [weak self] _ in
            MainActor.assumeIsolated { self?.follow(.active) }
        })
        observers.append(center.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in
            MainActor.assumeIsolated { self?.follow(.background) }
        })
        // Created while the app is already active (the usual case): never start behind it.
        if phase != .active, UIApplication.shared.applicationState == .active { follow(.active) }
    }

    /// Called from `update(_:environment:)` (inside a SwiftUI update) and from UIKit's
    /// lifecycle notifications: publish on the next turn of the main queue, never during
    /// an update itself.
    func follow(_ next: ScenePhase) {
        // A transient .inactive keeps what rows see; .background still stops their work.
        guard next != .inactive else { return }
        // A late or coalesced SwiftUI update never moves rows against what the app really
        // is: no .background while it is in the foreground, no .active while backgrounded.
        let state = UIApplication.shared.applicationState
        if next == .background, state == .active { return }
        if next == .active, state == .background { return }
        guard next != (scheduled ?? phase) else { return }
        scheduled = next
        DispatchQueue.main.async { [weak self] in
            guard let self, let value = self.scheduled else { return }
            self.scheduled = nil
            guard self.phase != value else { return }
            self.phase = value
            #if os(iOS)
            // Device reports: which phase the timeline rows follow (ui_update operation 74).
            if PerformanceRecorder.shared.isEnabled {
                PerformanceRecorder.shared.record(.uiUpdate, ["operation": 74, "phase": value == .active ? 1 : 0,
                    "app_state": Double(UIApplication.shared.applicationState.rawValue)])
            }
            #endif
        }
    }
}

private struct ChatHostedSceneKey: EnvironmentKey {
    static var defaultValue: ChatHostedScene? { nil }
}

extension EnvironmentValues {
    var chatHostedScene: ChatHostedScene? {
        get { self[ChatHostedSceneKey.self] }
        set { self[ChatHostedSceneKey.self] = newValue }
    }
}

/// A modifier, so a phase change re-renders only the views that read `scenePhase`
/// (media bubbles, queued media, video notes), never the whole row.
struct ChatHostedLivePhase: ViewModifier {
    @ObservedObject var scene: ChatHostedScene
    func body(content: Content) -> some View {
        content.environment(\.scenePhase, scene.phase).environment(\.chatHostedScene, scene)
    }
}

// MARK: - Values exchanged with the chat screen

/// A type-erased Equatable value: a row is reconfigured only when its fingerprint changes.
/// One allocation per value (it used to box the value and capture a second copy), and the
/// same instance compares in O(1).
struct ChatTimelineFingerprint: Equatable {
    private let box: ChatTimelineFingerprintBox
    init<Value: Equatable>(_ value: Value) { box = ChatTimelineTypedFingerprint(value) }
    static func == (lhs: ChatTimelineFingerprint, rhs: ChatTimelineFingerprint) -> Bool {
        lhs.box === rhs.box || lhs.box.isEqual(to: rhs.box)
    }
}

private class ChatTimelineFingerprintBox {
    func isEqual(to other: ChatTimelineFingerprintBox) -> Bool { false }
}

private final class ChatTimelineTypedFingerprint<Value: Equatable>: ChatTimelineFingerprintBox {
    let value: Value
    init(_ value: Value) { self.value = value }
    override func isEqual(to other: ChatTimelineFingerprintBox) -> Bool {
        guard let other = other as? ChatTimelineTypedFingerprint<Value> else { return false }
        return value == other.value
    }
}

/// Generation, account, server, chat and screen nonce: a change resets the list completely.
/// A value compared field by field, not a string formatted on every chat pass.
struct ChatTimelineScope: Equatable {
    let generation: UInt64
    let owner: String?
    let server: URL
    let chat: String
    let nonce: UUID
    /// The same chat across visits (no screen nonce): key of the row height memory.
    var memoryKey: String { "\(generation)|\(owner ?? "")|\(server.absoluteString)|\(chat)" }
}

/// A row on screen, in points from the top of the unobscured area (below the header,
/// above the composer).
struct ChatTimelineVisibleRow {
    let id: String
    let frame: CGRect
}

struct ChatTimelineVisibility {
    let rows: [ChatTimelineVisibleRow]
    /// Size of the unobscured area.
    let viewport: CGSize
    /// Distance from the newest position, in points (0 at the end).
    let distanceFromLatest: CGFloat
    /// The list is pinned to the newest row.
    let following: Bool
    /// A finger moved the list since the chat opened.
    let userScrolled: Bool
    /// Oldest row index on screen, counted from the oldest loaded row.
    let topmostIndexFromOldest: Int?
}

/// Where the chat opens.
enum ChatTimelineInitialPosition: Equatable {
    case latest
    /// A row, with the distance of its top from the top of the unobscured area.
    case row(String, originY: CGFloat?)
}

/// Commands from the chat screen and the state it reads back. Held by the screen as
/// a reference that is never observed, so reading or commanding never re-renders it.
@MainActor
final class ChatTimelineController {
    fileprivate weak var viewController: ChatTimelineViewController?
    /// Composer height (without the home indicator area) and header height. Sent here
    /// directly, never through SwiftUI state, so a composer that wraps a line or a header
    /// that settles moves the list without a chat pass. Kept until the list exists.
    fileprivate private(set) var bottomChrome: CGFloat = 0
    fileprivate private(set) var topChrome: CGFloat = 44
    /// A tap on the list ends composer editing (only while the composer has focus).
    fileprivate private(set) var tapEndsEditing = false

    func setBottomChrome(_ height: CGFloat) {
        guard height.isFinite, abs(height - bottomChrome) > 0.5 else { return }
        bottomChrome = height
        viewController?.chromeChanged()
    }

    func setTopChrome(_ height: CGFloat) {
        guard height.isFinite, height > 0, abs(height - topChrome) > 0.5 else { return }
        topChrome = height
        viewController?.chromeChanged()
    }

    func setTapEndsEditing(_ enabled: Bool) {
        guard enabled != tapEndsEditing else { return }
        tapEndsEditing = enabled
        viewController?.syncBackgroundTap()
    }

    var isFollowing: Bool { viewController?.isFollowing ?? true }
    var isAttached: Bool { viewController != nil }

    func scrollToLatest(animated: Bool) { viewController?.scrollToLatest(animated: animated) }

    /// Scrolls a row into the middle of the unobscured area. False when the row is not loaded.
    @discardableResult
    func scroll(to id: String, animated: Bool) -> Bool { viewController?.scroll(to: id, animated: animated) ?? false }

    /// While frozen the list never follows new rows (a call screen, viewer or menu covers it).
    func setFrozen(_ frozen: Bool) { viewController?.setFrozen(frozen) }
}

// MARK: - Representable

/// How a newly arrived row moves in.
enum ChatTimelineArrival { case outgoing, incoming, other }

/// Row heights that outlive one visit to a chat. Opening a chat was dominated by the
/// hosted SwiftUI rows measuring themselves (about 1 s of main-thread work over four
/// openings in a profile); coming back to a chat measured the same rows again. A height
/// is reused only for the same row content (fingerprint), width and settings, and only
/// for a cell's first sizing pass: content that changes size later measures as usual.
@MainActor
final class ChatTimelineHeightMemory {
    static let shared = ChatTimelineHeightMemory()
    private struct Entry { let fingerprint: ChatTimelineFingerprint; let height: CGFloat }
    private struct Chat { var environment: ChatTimelineFingerprint; var width: CGFloat; var rows: [String: Entry] }
    private var chats: [String: Chat] = [:]
    private var order: [String] = []
    private let maxChats = 8
    private let maxRows = 160
    private var observers: [NSObjectProtocol] = []

    private init() {
        // Fingerprints hold message content: keep it no longer than needed.
        for name in [UIApplication.didReceiveMemoryWarningNotification, UIApplication.didEnterBackgroundNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in ChatTimelineHeightMemory.shared.forgetAll() }
            })
        }
    }

    static let trace = Logger(subsystem: "com.unyri.timeline", category: "heights")
    private(set) var hits = 0, misses = 0, measured = 0

    func height(scope: String, environment: ChatTimelineFingerprint, width: CGFloat,
                id: String, fingerprint: ChatTimelineFingerprint) -> CGFloat? {
        guard let chat = chats[scope] else { misses += 1; return nil }
        guard chat.environment == environment else { misses += 1; return nil }
        guard abs(chat.width - width) < 0.5 else { misses += 1; return nil }
        guard let entry = chat.rows[id] else { misses += 1; return nil }
        guard entry.fingerprint == fingerprint else { misses += 1; return nil }
        hits += 1
        return entry.height
    }
    func noteMeasured() { measured += 1 }
    func report(_ label: String) {
        Self.trace.debug("\(label, privacy: .public) hits=\(self.hits, privacy: .public) misses=\(self.misses, privacy: .public) measured=\(self.measured, privacy: .public)")
        hits = 0; misses = 0; measured = 0
    }

    func remember(scope: String, environment: ChatTimelineFingerprint, width: CGFloat,
                  id: String, fingerprint: ChatTimelineFingerprint, height: CGFloat) {
        guard height.isFinite, height > 0, width > 0 else { return }
        if let chat = chats[scope], chat.environment == environment, abs(chat.width - width) < 0.5 {
            // Updated in place: copying the per-chat dictionary on every measurement was wasted work.
            if chat.rows[id] == nil, chat.rows.count >= maxRows { chats[scope]?.rows.removeAll(keepingCapacity: true) }
        } else {
            chats[scope] = Chat(environment: environment, width: width, rows: [:])
        }
        chats[scope]?.rows[id] = Entry(fingerprint: fingerprint, height: height)
        if order.last != scope {
            order.removeAll { $0 == scope }
            order.append(scope)
            while order.count > maxChats { chats[order.removeFirst()] = nil }
        }
    }

    /// Sign-out or an account switch: nothing of the old account stays in memory.
    func forgetAll() { chats.removeAll(); order.removeAll() }
}

struct ChatTimelineHost: UIViewControllerRepresentable {
    let controller: ChatTimelineController
    let scope: ChatTimelineScope
    /// Row ids, newest first.
    let ids: [String]
    /// Read only when the list resets (a new scope), not on every chat pass.
    let initialPosition: () -> ChatTimelineInitialPosition
    /// Height guess for a row not measured yet, for a row width.
    let estimatedHeight: (String, CGFloat) -> CGFloat
    /// The SwiftUI row for an id, for a row width.
    let content: (String, CGFloat) -> AnyView
    /// What the row shows, as an equatable value (row reconfigured when it changes).
    let fingerprint: (String) -> ChatTimelineFingerprint
    /// Covers everything `fingerprint` reads (with `ids` and the scope). While it is
    /// unchanged an update does no per-row work: most chat passes change no row.
    let rowsRevision: ChatTimelineFingerprint
    /// Settings every row depends on (appearance, text size): all visible rows reconfigure when it changes.
    let environmentToken: ChatTimelineFingerprint
    var onVisibility: (ChatTimelineVisibility) -> Void = { _ in }
    var onBackgroundTap: () -> Void = {}
    var onUserScroll: () -> Void = {}
    /// The chat's rows were shown positioned for the first time (count of rows).
    var onFirstPresented: (Int) -> Void = { _ in }
    /// Rows whose height depends only on their fingerprint (not on live state such as a
    /// transfer's progress or a voice transcript) may reuse a remembered height.
    var remembersHeight: (String) -> Bool = { _ in false }
    /// Who sent a row: your own messages and incoming ones arrive differently.
    var arrival: (String) -> ChatTimelineArrival = { _ in .other }

    func makeUIViewController(context: Context) -> ChatTimelineViewController {
        let viewController = ChatTimelineViewController()
        controller.viewController = viewController
        return viewController
    }

    func updateUIViewController(_ viewController: ChatTimelineViewController, context: Context) {
        if controller.viewController !== viewController { controller.viewController = viewController }
        viewController.update(self, environment: context.environment)
    }

    static func dismantleUIViewController(_ viewController: ChatTimelineViewController, coordinator: ()) {
        viewController.dismantle()
    }
}

/// The last keyboard frame app-wide (screen coordinates), so a list created while the
/// keyboard is up knows where it is: its own observer only hears later changes.
@MainActor
enum ChatTimelineKeyboard {
    private(set) static var lastFrame: CGRect?
    private static var observer: NSObjectProtocol?
    static func startObserving() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification,
                                                          object: nil, queue: .main) { note in
            let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
            MainActor.assumeIsolated { lastFrame = frame }
        }
    }
}

// MARK: - Cell

final class ChatTimelineCell: UICollectionViewCell {
    let visibility = ChatHostedRowVisibility()
    var rowID: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        contentView.backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// The row this cell measures for, set by the timeline before the cell is displayed.
    var measuresRow: (() -> String?)?
    /// A height remembered for exactly this row content; used once, for the first sizing.
    var rememberedHeight: CGFloat?
    /// Called with a height the row really measured (not a remembered or passed-through one).
    var onMeasured: ((String, CGFloat) -> Void)?

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        guard let attributes = layoutAttributes.copy() as? UICollectionViewLayoutAttributes else { return layoutAttributes }
        // A reused cell asked about a row it no longer shows would report another
        // message's size; keep the current one. Then let the hosted SwiftUI content
        // finish switching to this row before it is measured.
        if let expected = measuresRow?(), expected != rowID { return layoutAttributes }
        if let remembered = rememberedHeight {
            rememberedHeight = nil
            attributes.size = CGSize(width: layoutAttributes.size.width, height: remembered)
            return attributes
        }
        ChatTimelineHeightMemory.shared.noteMeasured()
        contentView.setNeedsLayout()
        contentView.layoutIfNeeded()
        let width = layoutAttributes.size.width
        let fitted = contentView.systemLayoutSizeFitting(CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
        let scale = window?.screen.scale ?? UIScreen.main.scale
        // The hosted view adds the safe area it currently overlaps (34 pt under the home
        // indicator, up to 62 under the status bar) to the size it reports. A row's
        // height must not depend on where it is on screen: every change moved the rows
        // above it.
        let safe = contentView.safeAreaInsets
        let content = fitted.height - max(0, safe.top) - max(0, safe.bottom)
        let height = content.isFinite ? max(1, (content * scale).rounded(.up) / scale) : layoutAttributes.size.height
        attributes.size = CGSize(width: width, height: height)
        if let rowID, content.isFinite { onMeasured?(rowID, height) }
        if let rowID { ChatTimelineTrace.sizes.debug("measured \(String(rowID.suffix(12)), privacy: .public) \(Double(height), privacy: .public)") }
        return attributes
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        if visibility.displayed { visibility.displayed = false }
        rowID = nil
        rememberedHeight = nil
        onMeasured = nil
    }
}

private struct ChatTimelineCellRoot: View {
    let content: AnyView
    let environment: EnvironmentValues
    let visibility: ChatHostedRowVisibility
    let scene: ChatHostedScene
    let id: String
    var body: some View {
        content
            // Rows never inset themselves from the status bar or the home indicator; the
            // timeline's own content insets keep them clear of the header and composer.
            .ignoresSafeArea()
            // Values that change while a row stays on screen must sit inside the
            // configure-time copy below, or that copy overrides them. Never add them to
            // the environment token instead: that would re-host and re-measure every
            // visible row on each app switch.
            .modifier(ChatHostedLivePhase(scene: scene))
            .environment(\.chatHostedRowVisibility, visibility)
            .environment(\.self, environment)
            // A reused cell must never carry another message's @State.
            .id(id)
    }
}

// MARK: - Collection view

final class ChatTimelineCollectionView: UICollectionView {
    override func touchesShouldCancel(in view: UIView) -> Bool { true }

    // VoiceOver keeps UIKit's own element order (newest message first, swiping right goes
    // back in time) because that order scrolls offscreen messages into view by itself.

    /// Three-finger scrolls in visual terms: "up" brings older messages into view,
    /// which is a larger offset in this flipped list.
    override func accessibilityScroll(_ direction: UIAccessibilityScrollDirection) -> Bool {
        let page = max(1, bounds.height - contentInset.top - contentInset.bottom) * 0.85
        let minimum = -contentInset.top
        let maximum = max(minimum, contentSize.height + contentInset.bottom - bounds.height)
        let target: CGFloat
        switch direction {
        case .up: target = min(maximum, contentOffset.y + page)
        case .down: target = max(minimum, contentOffset.y - page)
        default: return false
        }
        guard abs(target - contentOffset.y) > 0.5 else { return false }
        setContentOffset(CGPoint(x: contentOffset.x, y: target), animated: false)
        UIAccessibility.post(notification: .pageScrolled, argument: nil)
        return true
    }
}

// MARK: - View controller

@MainActor
final class ChatTimelineViewController: UIViewController, UICollectionViewDelegate, UIGestureRecognizerDelegate {
    private let layout = ChatTimelineLayout()
    private var collectionView: ChatTimelineCollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var host: ChatTimelineHost?
    private var environment = EnvironmentValues()
    /// The live scene phase for rows already built (see `ChatHostedScene`).
    private lazy var hostedScene = ChatHostedScene(phase: environment.scenePhase)
    private var environmentToken: ChatTimelineFingerprint?
    private var rowsRevision: ChatTimelineFingerprint?
    private var scope: ChatTimelineScope?
    private var memoryKey: String?
    /// Fingerprints for the current rows revision, shared by reconfigure and configure.
    private var printCache: [String: ChatTimelineFingerprint] = [:]
    /// The content and width each row was last really measured for (this visit).
    private var measuredPrints: [String: (print: ChatTimelineFingerprint, width: CGFloat)] = [:]
    /// Where the chrome heights and the tap setting come from.
    private weak var chromeSource: ChatTimelineController?
    private var lastTracedOffset: CGFloat = 0
    /// The last time the timeline moved its own offset (diagnostics: what caused a jump).
    /// 1 pin, 2 place, 3 insets while following, 4 insets while reading, 5 anchor after an
    /// update, 6 anchor after a reconfigure, 7 jump to a message.
    private var lastProgrammaticMove: (code: Double, at: TimeInterval) = (0, 0)
    private var reportedBlank = false
    private func noteMove(_ code: Double) { lastProgrammaticMove = (code, ProcessInfo.processInfo.systemUptime) }

    /// Device diagnostics for what the eye sees as a jump or an empty chat (numbers only).
    private func recordTimelineEvent(_ operation: Double, _ extra: [String: Double] = [:]) {
        #if os(iOS)
        guard PerformanceRecorder.shared.isEnabled else { return }
        var metrics = extra.filter { $0.value.isFinite && $0.value >= 0 }
        metrics["operation"] = operation
        PerformanceRecorder.shared.record(.uiUpdate, metrics)
        #endif
    }
    private var ids: [String] = []
    private var indexByID: [String: Int] = [:]
    private var configuredFingerprints: [String: ChatTimelineFingerprint] = [:]
    private var bottomChrome: CGFloat = 0
    private var topChrome: CGFloat = 0
    private(set) var isFollowing = true
    private var anchorID: String?
    private var frozen = false
    private var userScrolled = false
    private var needsInitialPosition = true
    private var initialPosition: ChatTimelineInitialPosition = .latest
    private var visibilityScheduled = false
    private var backgroundTap: UITapGestureRecognizer!
    private var jumpTarget: String?
    /// A composer that just shrank (a sent multi-line message or reply) keeps its old
    /// height until the sent row arrives, so the row takes its place in one motion
    /// instead of the chat dipping and then rising.
    private var heldBottomChrome: CGFloat?
    private var holdToken: UUID?
    /// New rows arrived while the reader was at the end but the list was frozen.
    private var followAfterUnfreeze = false
    private var viewerObservation: AnyCancellable?
    private var keyboardObservation: AnyCancellable?
    /// The running keyboard animation.
    private var keyboardAnimationEnd: TimeInterval = 0
    private var keyboardCurve: UInt = 7
    /// The keyboard's end frame (screen coordinates). The list keeps its full height and
    /// moves up by the overlap instead of shrinking (see `applyInsets`).
    private var keyboardFrame: CGRect?
    /// How far the timeline reaches under the status bar and the home indicator. The
    /// timeline's content insets use these (see `measureScreenEdges`).
    private var screenEdges: (top: CGFloat, bottom: CGFloat) = (0, 0)

    /// Frozen: a covering screen, the full-screen media viewer (hosted by a cell that
    /// must not scroll away and be recycled) or an open message menu.
    private var effectivelyFrozen: Bool {
        frozen || UnyriMediaWorkState.shared.viewerOpen || !UnyriMessageMenuPresenter.canActivateMessage
    }

    override func loadView() {
        let root = UIView()
        root.backgroundColor = .clear
        view = root
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let collectionView = ChatTimelineCollectionView(frame: view.bounds, collectionViewLayout: layout)
        // Placed by `applyInsets` (it moves up, past the screen top, while the keyboard is up).
        collectionView.autoresizingMask = []
        collectionView.transform = CGAffineTransform(scaleX: 1, y: -1)
        collectionView.backgroundColor = .clear
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.keyboardDismissMode = .interactive
        collectionView.alwaysBounceVertical = true
        collectionView.showsVerticalScrollIndicator = false
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.isPrefetchingEnabled = false
        collectionView.allowsSelection = false
        collectionView.delaysContentTouches = false
        collectionView.selfSizingInvalidation = .enabledIncludingConstraints
        collectionView.accessibilityIdentifier = "chat-timeline"
        collectionView.delegate = self
        collectionView.register(ChatTimelineCell.self, forCellWithReuseIdentifier: "row")
        hideEdgeEffects(collectionView)
        view.addSubview(collectionView)
        self.collectionView = collectionView

        layout.itemID = { [weak self] index in
            guard let self, index >= 0, index < self.ids.count else { return nil }
            return self.ids[index]
        }
        layout.indexOfID = { [weak self] id in self?.indexByID[id] }
        layout.estimatedHeight = { [weak self] index in
            guard let self, index < self.ids.count, let host = self.host else { return 64 }
            return host.estimatedHeight(self.ids[index], self.collectionView.bounds.width)
        }
        layout.anchorIndex = { [weak self] in
            guard let self, !self.isFollowing || self.effectivelyFrozen, let id = self.anchorID else { return nil }
            return self.indexByID[id]
        }

        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { [weak self] view, indexPath, id in
            let cell = view.dequeueReusableCell(withReuseIdentifier: "row", for: indexPath) as! ChatTimelineCell
            self?.configure(cell, id: id)
            return cell
        }

        let tap = UITapGestureRecognizer(target: self, action: #selector(backgroundTapped))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        tap.isEnabled = false
        collectionView.addGestureRecognizer(tap)
        backgroundTap = tap

        ChatTimelineKeyboard.startObserving()
        keyboardFrame = ChatTimelineKeyboard.lastFrame
        keyboardObservation = NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
            .sink { [weak self] note in
                let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
                let curve = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
                guard let self else { return }
                if duration.isFinite, duration > 0, duration < 2 {
                    self.keyboardAnimationEnd = ProcessInfo.processInfo.systemUptime + duration
                    self.keyboardCurve = curve <= 7 ? curve : 7
                }
                self.keyboardFrame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
                // Insets and offset follow the keyboard on its own curve (applyInsets).
                self.applyInsets()
            }
        viewerObservation = UnyriMediaWorkState.shared.$viewerOpen.removeDuplicates().sink { [weak self] open in
            guard !open else { return }
            DispatchQueue.main.async { self?.resumeIfNeeded() }
        }
    }

    // MARK: Update from SwiftUI

    func update(_ host: ChatTimelineHost, environment: EnvironmentValues) {
        loadViewIfNeeded()
        attach(host.controller)
        self.host = host
        // New cells always configure through the latest host and environment.
        self.environment = environment
        // Rows already on screen follow the phase live; nothing is reconfigured.
        hostedScene.follow(environment.scenePhase)
        if scope != host.scope {
            reset(scope: host.scope, initialPosition: host.initialPosition())
        }
        // Most chat passes change nothing a row shows: then no row is fingerprinted.
        let envChanged = environmentToken != host.environmentToken
        let rowsChanged = rowsRevision != host.rowsRevision
        environmentToken = host.environmentToken
        rowsRevision = host.rowsRevision
        if envChanged || rowsChanged { printCache.removeAll(keepingCapacity: true) }
        if envChanged {
            // Estimates and heights measured under other settings are not reused.
            layout.forgetAllEstimates()
            measuredPrints.removeAll()
        }
        // Same array buffer on an unchanged pass: this compares in O(1).
        if ids != host.ids { apply(ids: host.ids) }
        if envChanged || rowsChanged { reconfigureVisible(all: envChanged) }
        positionInitiallyIfNeeded()
        resumeIfNeeded()
    }

    /// Takes the chrome heights and the tap setting from the screen's controller.
    private func attach(_ controller: ChatTimelineController) {
        guard chromeSource !== controller else { return }
        chromeSource = controller
        bottomChrome = controller.bottomChrome
        topChrome = controller.topChrome
        syncBackgroundTap()
        applyInsets()
    }

    fileprivate func syncBackgroundTap() {
        backgroundTap?.isEnabled = chromeSource?.tapEndsEditing ?? false
    }

    /// The composer or header changed height (sent by the controller, not by a chat pass).
    fileprivate func chromeChanged() {
        guard isViewLoaded, let source = chromeSource else { return }
        let bottom = source.bottomChrome, top = source.topChrome
        guard abs(bottom - bottomChrome) > 0.5 || abs(top - topChrome) > 0.5 else { return }
        if bottom < bottomChrome - 0.5, isFollowing, !needsInitialPosition, view.window != nil {
            holdBottomChrome(bottomChrome)
        }
        bottomChrome = bottom
        topChrome = top
        // The composer grows or shrinks (a second line, the attachment tray, a reply
        // panel) with SwiftUI's own short animation; the list follows it smoothly
        // instead of landing on the final height before the composer does.
        let keyboardMoving = keyboardAnimationEnd > ProcessInfo.processInfo.systemUptime
        if !keyboardMoving, !needsInitialPosition, view.window != nil, !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction]) {
                self.applyInsets()
            }
        } else {
            applyInsets()
        }
    }

    /// One fingerprint per row and rows revision.
    private func fingerprint(_ id: String, host: ChatTimelineHost) -> ChatTimelineFingerprint {
        if let cached = printCache[id] { return cached }
        let value = host.fingerprint(id)
        printCache[id] = value
        return value
    }

    func dismantle() {
        ChatTimelineHeightMemory.shared.report("chat closed")
        for cell in collectionView.visibleCells.compactMap({ $0 as? ChatTimelineCell }) where cell.visibility.displayed {
            cell.visibility.displayed = false
        }
        host = nil
    }

    private func reset(scope: ChatTimelineScope, initialPosition: ChatTimelineInitialPosition) {
        if !ids.isEmpty { recordTimelineEvent(70, ["rows": Double(ids.count)]) }
        self.scope = scope
        memoryKey = scope.memoryKey
        self.initialPosition = initialPosition
        layout.resetHeights()
        configuredFingerprints.removeAll()
        printCache.removeAll()
        measuredPrints.removeAll()
        rowsRevision = nil
        isFollowing = true
        anchorID = nil
        userScrolled = false
        needsInitialPosition = true
        ids = []
        indexByID = [:]
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        dataSource.applySnapshotUsingReloadData(snapshot)
    }

    private func apply(ids newIDs: [String]) {
        // Duplicate ids crash a diffable data source: keep the first of each. Without
        // duplicates the host's own array is kept, so the next pass compares in O(1).
        var seen = Set<String>(minimumCapacity: newIDs.count)
        var unique = newIDs
        if !newIDs.allSatisfy({ seen.insert($0).inserted }) {
            seen.removeAll(keepingCapacity: true)
            unique = newIDs.filter { seen.insert($0).inserted }
        }
        let previous = ids
        let previousIndex = indexByID
        let wasEmpty = previous.isEmpty
        if wasEmpty, needsInitialPosition { seedRememberedHeights(unique) }
        forgetChangedEstimates(previous: previous, previousIndex: previousIndex, next: unique)
        // Arrivals at the newest end slide in while the reader is at the end. The loaded
        // window has a fixed size, so the oldest rows may leave in the same update.
        let arrivals = Self.newestArrivals(previous: previous, next: unique)
        let prefixInserted = !wasEmpty && arrivals > 0
        let frozenNow = effectivelyFrozen
        let animate = prefixInserted && isFollowing && !frozenNow && !needsInitialPosition && view.window != nil &&
            arrivals <= 3
        if prefixInserted, heldBottomChrome != nil {
            // The sent row arrived: release the held composer height together with it.
            heldBottomChrome = nil; holdToken = nil
            if animate { UIView.animate(withDuration: 0.3, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) { self.applyInsets() } }
            else { applyInsets() }
        }
        if frozenNow, isFollowing, !wasEmpty, !needsInitialPosition {
            // Keep what is on screen still; catch up with the end when uncovered.
            if anchorID == nil { anchorID = lowestFullyVisibleID() }
            if prefixInserted { followAfterUnfreeze = true }
        }
        if !isFollowing || frozenNow { layout.captureAnchor() }
        ids = unique
        indexByID = Dictionary(uniqueKeysWithValues: unique.enumerated().map { ($1, $0) })
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(unique, toSection: 0)
        layout.animatesArrivals = animate
        let arrival = host?.arrival
        layout.arrivalStyle = { [unique] index in index < unique.count ? arrival?(unique[index]) ?? .other : .other }
        if wasEmpty {
            dataSource.applySnapshotUsingReloadData(snapshot)
        } else {
            dataSource.apply(snapshot, animatingDifferences: animate)
        }
        layout.animatesArrivals = false
        layout.arrivalStyle = nil
        ChatTimelineTrace.log.debug("apply rows \(previous.count, privacy: .public) -> \(unique.count, privacy: .public) arrivals \(arrivals, privacy: .public) following \(self.isFollowing, privacy: .public)")
        if let shift = layout.consumeAnchorShift() {
            noteMove(5)
            if abs(shift) > 250 { recordTimelineEvent(71, ["shift": Double(abs(shift)), "rows": Double(unique.count)]) }
            ChatTimelineTrace.log.debug("apply anchor shift \(Double(shift), privacy: .public)")
            collectionView.contentOffset.y += shift
        }
        if isFollowing && !frozenNow && !needsInitialPosition {
            pin(to: minOffset, animated: false)
        }
        scheduleVisibility()
    }

    /// After a freeze ends, a reader who was at the end glides to the rows that arrived.
    private func resumeIfNeeded() {
        guard followAfterUnfreeze, !effectivelyFrozen, !needsInitialPosition else { return }
        followAfterUnfreeze = false
        anchorID = nil
        isFollowing = true
        pin(to: minOffset, animated: view.window != nil)
        scheduleVisibility()
    }

    /// Heights from the last visit for the rows the chat opens on (the newest ones, or
    /// those around the saved reading position), so the first layout is already exact.
    private func seedRememberedHeights(_ rows: [String]) {
        guard let host, let scope = memoryKey, let environment = environmentToken, !rows.isEmpty else { return }
        let width = collectionView.bounds.width
        guard width > 0 else { return }
        var center = 0
        if case .row(let id, _) = initialPosition, let index = rows.firstIndex(of: id) { center = index }
        let range = max(0, center - 16)..<min(rows.count, center + 16)
        var remembered: [String: CGFloat] = [:]
        for id in rows[range] where host.remembersHeight(id) {
            if let height = ChatTimelineHeightMemory.shared.height(scope: scope, environment: environment,
                width: width, id: id, fingerprint: fingerprint(id, host: host)) { remembered[id] = height }
        }
        if !remembered.isEmpty { layout.seed(remembered, width: width) }
    }

    /// An estimate depends on its row and the rows next to it (date separator, grouping):
    /// only rows that are new or have new neighbours are estimated again. Forgetting
    /// every estimate re-estimated all loaded rows (text scanned per character) for each
    /// arriving message.
    private func forgetChangedEstimates(previous: [String], previousIndex: [String: Int], next: [String]) {
        guard !previous.isEmpty else { layout.forgetAllEstimates(); return }
        var stale: [String] = []
        for (index, id) in next.enumerated() {
            guard let old = previousIndex[id], old < previous.count else { stale.append(id); continue }
            let before = index > 0 ? next[index - 1] : nil
            let after = index + 1 < next.count ? next[index + 1] : nil
            let oldBefore = old > 0 ? previous[old - 1] : nil
            let oldAfter = old + 1 < previous.count ? previous[old + 1] : nil
            if before != oldBefore || after != oldAfter { stale.append(id) }
        }
        if !stale.isEmpty { layout.forgetEstimates(stale) }
    }

    /// How many rows were added at the newest end: `next` starts with them, then
    /// continues with `previous` (possibly shortened at its oldest end). 0 otherwise.
    static func newestArrivals(previous: [String], next: [String]) -> Int {
        guard let head = previous.first, let start = next.firstIndex(of: head), start > 0, start <= 10 else { return 0 }
        let overlap = min(previous.count, next.count - start)
        guard overlap > 0 else { return 0 }
        for offset in 0..<overlap where next[start + offset] != previous[offset] { return 0 }
        return start
    }

    private func configure(_ cell: ChatTimelineCell, id: String) {
        guard let host else { return }
        cell.rowID = id
        cell.measuresRow = { [weak self, weak cell] in
            guard let self, let cell, let indexPath = self.collectionView.indexPath(for: cell),
                  indexPath.item < self.ids.count else { return nil }
            return self.ids[indexPath.item]
        }
        let rowPrint = fingerprint(id, host: host)
        configuredFingerprints[id] = rowPrint
        let width = collectionView.bounds.width
        let remembers = host.remembersHeight(id)
        cell.rememberedHeight = nil
        if remembers {
            if let known = layout.measuredHeight(of: id), let measured = measuredPrints[id],
               measured.print == rowPrint, abs(measured.width - width) < 0.5 {
                // Measured earlier in this visit for the same content and width (a row
                // scrolled back into view): host it without measuring it again.
                cell.rememberedHeight = known
            } else if let scope = memoryKey, let environmentToken {
                cell.rememberedHeight = ChatTimelineHeightMemory.shared.height(scope: scope, environment: environmentToken,
                    width: width, id: id, fingerprint: rowPrint)
            }
        }
        cell.onMeasured = { [weak self] rowID, height in
            guard let self, rowID == id, let measuredFor = self.configuredFingerprints[rowID] else { return }
            let width = self.collectionView.bounds.width
            self.measuredPrints[rowID] = (measuredFor, width)
            guard remembers, let scope = self.memoryKey, let environment = self.environmentToken else { return }
            ChatTimelineHeightMemory.shared.remember(scope: scope, environment: environment,
                width: width, id: rowID, fingerprint: measuredFor, height: height)
        }
        let root = ChatTimelineCellRoot(content: host.content(id, width), environment: environment,
                                        visibility: cell.visibility, scene: hostedScene, id: id)
        cell.contentConfiguration = UIHostingConfiguration { root }
            .margins(.all, 0)
            .minSize(width: 0, height: 0)
    }

    private func reconfigureVisible(all: Bool) {
        guard let host, !ids.isEmpty else { return }
        var changed: [String] = []
        for cell in collectionView.visibleCells.compactMap({ $0 as? ChatTimelineCell }) {
            guard let id = cell.rowID, indexByID[id] != nil else { continue }
            if all || configuredFingerprints[id] != fingerprint(id, host: host) { changed.append(id) }
        }
        guard !changed.isEmpty else { return }
        layout.forgetEstimates(changed)
        var snapshot = dataSource.snapshot()
        snapshot.reconfigureItems(changed)
        if !isFollowing { layout.captureAnchor() }
        let update = {
            self.dataSource.apply(snapshot, animatingDifferences: false)
            if let shift = self.layout.consumeAnchorShift() { self.noteMove(6); self.collectionView.contentOffset.y += shift }
            // A row that grows (a reaction, an edit, a transcript) does so together with
            // its SwiftUI content's own short animation, not in one frame under it.
            self.collectionView.layoutIfNeeded()
        }
        if all || needsInitialPosition || view.window == nil || UIAccessibility.isReduceMotionEnabled {
            update()
        } else {
            UIView.animate(withDuration: 0.16, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: update)
        }
    }

    // MARK: Insets and position

    private var minOffset: CGFloat { -collectionView.contentInset.top }
    private var maxOffset: CGFloat {
        max(minOffset, collectionView.contentSize.height + collectionView.contentInset.bottom - collectionView.bounds.height)
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        measureScreenEdges()
        applyInsets()
    }

    /// How far the timeline reaches under the status bar and the home indicator, from
    /// geometry (SwiftUI manages this controller's own safe area).
    private func measureScreenEdges() {
        guard let window = view.window else { return }
        let frame = view.convert(view.bounds, to: window)
        let safe = window.safeAreaInsets
        let top = max(0, min(frame.height, safe.top - frame.minY))
        let bottom = max(0, min(frame.height, frame.maxY - (window.bounds.height - safe.bottom)))
        screenEdges = (top, bottom)
    }

    /// iOS 26 scroll-edge blur is laid out in the scroll view's own (flipped) space and
    /// smeared the whole timeline; the chat draws its own soft edges instead.
    private func hideEdgeEffects(_ scrollView: UIScrollView) {
        if #available(iOS 26.0, *) {
            for effect in [scrollView.topEdgeEffect, scrollView.bottomEdgeEffect,
                           scrollView.leftEdgeEffect, scrollView.rightEdgeEffect] where !effect.isHidden {
                effect.isHidden = true
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        measureScreenEdges()
        hideEdgeEffects(collectionView)
        applyInsets()
        positionInitiallyIfNeeded()
    }

    /// List-space top inset = visual bottom (composer + home indicator + 20 pt gap);
    /// list-space bottom inset = visual top (status bar + header + 8 pt).
    private func holdBottomChrome(_ height: CGFloat) {
        heldBottomChrome = max(heldBottomChrome ?? 0, height)
        let token = UUID()
        holdToken = token
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self, self.holdToken == token else { return }
            self.heldBottomChrome = nil; self.holdToken = nil
            UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) { self.applyInsets() }
        }
    }

    /// How far a docked keyboard covers this view from its bottom edge (0 while it is
    /// hidden, floating or undocked).
    private func keyboardOverlap() -> CGFloat {
        guard let frame = keyboardFrame, !frame.isEmpty, let window = view.window else { return 0 }
        let keyboard = window.convert(frame, from: window.screen.coordinateSpace)
        guard keyboard.maxY >= window.bounds.maxY - 1 else { return 0 }
        let own = view.convert(view.bounds, to: window)
        return max(0, min(own.height, own.maxY - keyboard.minY))
    }

    private func applyInsets() {
        guard let collectionView else { return }
        // The list keeps the view's size and ignores the keyboard area (the screen lets it).
        // A docked keyboard moves the whole list up by what it covers beyond the home
        // indicator, and the visual top inset grows by as much, so the unobscured area still
        // starts below the header. The content offset does not change for the keyboard: the
        // list-space window is the same with and without it, at the end of the chat or
        // mid-history. Showing or hiding the keyboard is only a move on its curve: no row is
        // built or measured behind the keyboard, and the rows it pushes above the screen stay
        // built for its return.
        let lift = max(0, keyboardOverlap() - screenEdges.bottom)
        let visualBottom = max(bottomChrome, heldBottomChrome ?? 0) + screenEdges.bottom + 20
        let visualTop = topChrome + screenEdges.top + 8 + lift
        let bounds = view.bounds
        let center = CGPoint(x: bounds.midX, y: bounds.midY - lift)
        let current = collectionView.bounds.size
        let resized = abs(current.width - bounds.width) > 0.5 || abs(current.height - bounds.height) > 0.5
        let moved = abs(collectionView.center.x - center.x) > 0.5 || abs(collectionView.center.y - center.y) > 0.5
        let old = collectionView.contentInset
        guard resized || moved || abs(old.top - visualBottom) > 0.5 || abs(old.bottom - visualTop) > 0.5 else { return }
        let offset = collectionView.contentOffset.y
        let change = {
            // Bounds and center, not frame: the list is flipped by a transform.
            if resized { collectionView.bounds.size = bounds.size }
            if moved { collectionView.center = center }
            collectionView.contentInset = UIEdgeInsets(top: visualBottom, left: 0, bottom: visualTop, right: 0)
            collectionView.verticalScrollIndicatorInsets = collectionView.contentInset
            // Content stays glued to the composer: a taller composer lifts what is on screen.
            if self.isFollowing || self.needsInitialPosition {
                self.noteMove(3)
                collectionView.contentOffset.y = -visualBottom
            } else {
                // Only the composer's height moves the reading position; the keyboard does not.
                let shift = old.top - visualBottom
                if abs(shift) > 0.5 {
                    self.noteMove(4)
                    ChatTimelineTrace.log.debug("insets moved reading position by \(Double(shift), privacy: .public)")
                }
                collectionView.contentOffset.y = offset + shift
            }
        }
        // A keyboard change follows the keyboard on its own curve; outside the keyboard's
        // animation it would land in one frame.
        let remaining = keyboardAnimationEnd - ProcessInfo.processInfo.systemUptime
        if UIView.inheritedAnimationDuration == 0, remaining > 0.02, !needsInitialPosition, view.window != nil {
            UIView.animate(withDuration: remaining, delay: 0,
                           options: [UIView.AnimationOptions(rawValue: keyboardCurve << 16), .beginFromCurrentState, .allowUserInteraction],
                           animations: change)
        } else {
            change()
        }
        scheduleVisibility()
    }

    private func positionInitiallyIfNeeded() {
        guard needsInitialPosition, !ids.isEmpty, collectionView.bounds.height > 0, view.window != nil else { return }
        collectionView.layoutIfNeeded()
        switch initialPosition {
        case .latest:
            pin(to: minOffset, animated: false)
            isFollowing = true
        case .row(let id, let originY):
            if let index = indexByID[id] {
                place(index: index, visualTop: originY ?? 0)
                // Rows around it measured themselves: place once more with real heights.
                collectionView.layoutIfNeeded()
                place(index: index, visualTop: originY ?? 0)
                isFollowing = collectionView.contentOffset.y <= minOffset + 1
                anchorID = isFollowing ? nil : id
            } else {
                pin(to: minOffset, animated: false)
                isFollowing = true
            }
        }
        needsInitialPosition = false
        scheduleVisibility()
        let count = ids.count
        DispatchQueue.main.async { [weak self] in self?.host?.onFirstPresented(count) }
    }

    private func pin(to offset: CGFloat, animated: Bool) {
        noteMove(1)
        let target = CGPoint(x: 0, y: offset)
        if animated { collectionView.setContentOffset(target, animated: true) }
        else if abs(collectionView.contentOffset.y - offset) > 0.01 { collectionView.contentOffset = target }
    }

    /// Unobscured height (between the header and the composer).
    private var unobscuredHeight: CGFloat {
        collectionView.bounds.height - collectionView.contentInset.top - collectionView.contentInset.bottom
    }

    /// Places a row so its visual top is `visualTop` points below the unobscured top.
    private func place(index: Int, visualTop: CGFloat) {
        noteMove(2)
        guard let frame = layout.frame(at: index) else { return }
        // Visual top of a row (from the view's top) = height - (maxY - offset).
        // Unobscured top = contentInset.bottom (the visual top inset).
        let wanted = collectionView.contentInset.bottom + visualTop
        let offset = frame.maxY - collectionView.bounds.height + wanted
        pin(to: min(maxOffset, max(minOffset, offset)), animated: false)
    }

    private func centerOffset(index: Int) -> CGFloat? {
        guard let frame = layout.frame(at: index) else { return nil }
        let visualTop = max(0, (unobscuredHeight - frame.height) / 2)
        let wanted = collectionView.contentInset.bottom + visualTop
        return min(maxOffset, max(minOffset, frame.maxY - collectionView.bounds.height + wanted))
    }

    func scrollToLatest(animated: Bool) {
        jumpTarget = nil
        anchorID = nil
        isFollowing = true
        pin(to: minOffset, animated: animated && view.window != nil)
        scheduleVisibility()
    }

    func scroll(to id: String, animated: Bool) -> Bool {
        guard let index = indexByID[id], let target = centerOffset(index: index) else { return false }
        noteMove(7)
        jumpTarget = id
        anchorID = id
        isFollowing = false
        let distance = abs(target - collectionView.contentOffset.y)
        let screen = max(1, unobscuredHeight)
        if animated && distance > screen * 2 {
            // Far jump: land a screen away without animation, then glide in.
            let near = target + (target > collectionView.contentOffset.y ? -screen : screen)
            pin(to: near, animated: false)
            collectionView.layoutIfNeeded()
            if let refined = centerOffset(index: index) { pin(to: refined, animated: true) }
        } else {
            pin(to: target, animated: animated && view.window != nil)
            if !animated { finishJump() }
        }
        scheduleVisibility()
        return true
    }

    private func finishJump() {
        guard let id = jumpTarget, let index = indexByID[id] else { jumpTarget = nil; return }
        jumpTarget = nil
        collectionView.layoutIfNeeded()
        if let target = centerOffset(index: index), abs(target - collectionView.contentOffset.y) > 2 {
            pin(to: target, animated: false)
        }
        isFollowing = collectionView.contentOffset.y <= minOffset + 1
        scheduleVisibility()
    }

    func setFrozen(_ value: Bool) {
        guard frozen != value else { return }
        frozen = value
        guard !value else { return }
        if followAfterUnfreeze { resumeIfNeeded() }
        else if isFollowing, !effectivelyFrozen { anchorID = nil; pin(to: minOffset, animated: false) }
        // Rows already on screen are readable again even when nothing moves: report
        // them, or a read refused while covered waits for the next scroll.
        scheduleVisibility()
    }

    // MARK: Scroll delegate

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        userScrolled = true
        jumpTarget = nil
        host?.onUserScroll()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let restingMove = scrollView.contentOffset.y - lastTracedOffset
        lastTracedOffset = scrollView.contentOffset.y
        if abs(restingMove) > 250, !needsInitialPosition, view.window != nil {
            let recent = ProcessInfo.processInfo.systemUptime - lastProgrammaticMove.at < 0.3 ? lastProgrammaticMove.code : 0
            ChatTimelineTrace.log.debug("jump \(Double(restingMove), privacy: .public) cause \(recent, privacy: .public)")
            // A fast fling covers this much between frames by itself: only a move the
            // timeline made, or one under the finger, is worth a report.
            if recent != 0 || scrollView.isTracking {
                recordTimelineEvent(72, ["delta": Double(abs(restingMove)), "cause": recent,
                                         "dragging": scrollView.isTracking ? 1 : 0])
            }
        }
        if !(scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating), abs(restingMove) > 1, !needsInitialPosition {
            ChatTimelineTrace.log.debug("moved at rest by \(Double(restingMove), privacy: .public) following \(self.isFollowing, privacy: .public)")
        }
        guard !needsInitialPosition else { return }
        let atEnd = scrollView.contentOffset.y <= minOffset + 1
        let interacting = scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating
        // The finger decides; a programmatic move only ever re-joins the end.
        if interacting { isFollowing = atEnd } else if atEnd, jumpTarget == nil { isFollowing = true }
        if !isFollowing, jumpTarget == nil { anchorID = lowestFullyVisibleID() }
        scheduleVisibility()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        if jumpTarget != nil { finishJump() }
        if scrollView.contentOffset.y <= minOffset + 1 { isFollowing = true; anchorID = nil }
        scheduleVisibility()
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { scheduleVisibility() }

    /// A fling toward the newest message stops on it. Left to the scroll view, a fast
    /// fling overshot the end by almost a screen and rubber-banded back (device
    /// recording, 0.4.9); the finger can still pull past the end for feedback.
    func scrollViewWillEndDragging(_ scrollView: UIScrollView, withVelocity velocity: CGPoint,
                                   targetContentOffset: UnsafeMutablePointer<CGPoint>) {
        if targetContentOffset.pointee.y < minOffset {
            targetContentOffset.pointee.y = minOffset
        }
    }

    func scrollViewShouldScrollToTop(_ scrollView: UIScrollView) -> Bool {
        // The status bar tap goes to the oldest loaded message (the visual top).
        isFollowing = false
        anchorID = nil
        collectionView.setContentOffset(CGPoint(x: 0, y: maxOffset), animated: true)
        return false
    }

    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let cell = cell as? ChatTimelineCell else { return }
        if !cell.visibility.displayed { cell.visibility.displayed = true }
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let cell = cell as? ChatTimelineCell else { return }
        if cell.visibility.displayed { cell.visibility.displayed = false }
    }

    // MARK: Visibility

    /// Lowest index (nearest the newest end) whose row is fully inside the unobscured area.
    private func lowestFullyVisibleID() -> String? {
        let low = collectionView.contentOffset.y + collectionView.contentInset.top
        let high = collectionView.contentOffset.y + collectionView.bounds.height - collectionView.contentInset.bottom
        guard high > low else { return nil }
        let attributes = layout.layoutAttributesForElements(in: CGRect(x: 0, y: low, width: collectionView.bounds.width, height: high - low)) ?? []
        let full = attributes.filter { $0.frame.minY >= low - 0.5 && $0.frame.maxY <= high + 0.5 }
        let pick = (full.isEmpty ? attributes : full).min { $0.indexPath.item < $1.indexPath.item }
        return pick.flatMap { ids.indices.contains($0.indexPath.item) ? ids[$0.indexPath.item] : nil }
    }

    private func scheduleVisibility() {
        guard !visibilityScheduled else { return }
        visibilityScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.visibilityScheduled = false
            self.reportVisibility()
        }
    }

    private func reportVisibility() {
        guard let host, !needsInitialPosition, collectionView.bounds.height > 0 else { return }
        if !ids.isEmpty, collectionView.visibleCells.isEmpty {
            if !reportedBlank {
                reportedBlank = true
                let recent = ProcessInfo.processInfo.systemUptime - lastProgrammaticMove.at < 0.5 ? lastProgrammaticMove.code : 0
                ChatTimelineTrace.log.debug("blank with \(self.ids.count, privacy: .public) rows cause \(recent, privacy: .public)")
                recordTimelineEvent(73, ["rows": Double(ids.count), "cause": recent])
            }
        } else { reportedBlank = false }
        let offset = collectionView.contentOffset.y
        let height = collectionView.bounds.height
        let visualTop = collectionView.contentInset.bottom
        let unobscured = CGSize(width: collectionView.bounds.width, height: max(0, unobscuredHeight))
        let rect = CGRect(x: 0, y: offset, width: collectionView.bounds.width, height: height)
        var rows: [ChatTimelineVisibleRow] = []
        var topmost: Int?
        for attributes in layout.layoutAttributesForElements(in: rect) ?? [] {
            let index = attributes.indexPath.item
            guard index < ids.count else { continue }
            let frame = attributes.frame
            // List space to visual space, then relative to the unobscured top.
            let visualMinY = height - (frame.maxY - offset) - visualTop
            let visual = CGRect(x: frame.minX, y: visualMinY, width: frame.width, height: frame.height)
            guard visual.maxY > 0, visual.minY < unobscured.height else { continue }
            rows.append(ChatTimelineVisibleRow(id: ids[index], frame: visual))
            topmost = max(topmost ?? index, index)
        }
        let distance = max(0, offset - minOffset)
        host.onVisibility(ChatTimelineVisibility(rows: rows, viewport: unobscured, distanceFromLatest: distance,
            following: isFollowing, userScrolled: userScrolled,
            topmostIndexFromOldest: topmost.map { ids.count - 1 - $0 }))
    }

    // MARK: Gestures

    @objc private func backgroundTapped() { host?.onBackgroundTap() }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
}
