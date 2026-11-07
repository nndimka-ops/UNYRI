import SwiftUI
import UIKit

/// Candidate only. Native SDK and opening-frame progression proof required.
/// Observe the actual owned List viewport while UIKit is animating keyboard
/// layout. There is deliberately no scroll in keyboardDidShow.
@MainActor
struct ChatKeyboardTailAnchor: UIViewRepresentable {
    let scope: String
    let enabled: Bool
    var bottomSpacing: CGFloat = 8
    let shouldFollow: () -> Bool
    let isCurrent: () -> Bool

    func makeUIView(context: Context) -> ChatKeyboardTailView {
        let view = ChatKeyboardTailView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        configure(view)
        return view
    }
    func updateUIView(_ view: ChatKeyboardTailView, context: Context) { configure(view) }
    private func configure(_ view: ChatKeyboardTailView) {
        view.configure(scope: scope, enabled: enabled, bottomSpacing: bottomSpacing, shouldFollow: shouldFollow, isCurrent: isCurrent)
    }
    static func dismantleUIView(_ view: ChatKeyboardTailView, coordinator: ()) { view.disconnect() }
}

@MainActor
final class ChatKeyboardTailView: UIView {
    private weak var scroll: UIScrollView?
    private weak var ownedWindow: UIWindow?
    private var scope = ""
    private var enabled = false
    private var bottomSpacing: CGFloat = 8
    private var shouldFollow: (() -> Bool)?
    private var isCurrent: (() -> Bool)?
    private var observations: [NSKeyValueObservation] = []
    private var observingNotifications = false
    private var armedScope: String?
    private var animationEnd: TimeInterval = 0
    private var deadline: TimeInterval = 0
    private var curve: UInt = 0
    private var immediate = false
    private var armedWidth: CGFloat = 0
    private var armedWindowSize: CGSize = .zero
    private var writing = false
    private var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    func configure(scope: String, enabled: Bool, bottomSpacing: CGFloat, shouldFollow: @escaping () -> Bool, isCurrent: @escaping () -> Bool) {
        if self.scope != scope || !enabled { cancel() }
        self.scope = scope; self.enabled = enabled
        self.bottomSpacing = bottomSpacing
        self.shouldFollow = shouldFollow; self.isCurrent = isCurrent
        if !enabled { disconnect(); return }
        connectIfPossible()
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { disconnect() } else { connectIfPossible() }
    }
    override func didMoveToSuperview() { super.didMoveToSuperview(); connectIfPossible() }
    override func layoutSubviews() {
        super.layoutSubviews()
        // UIKit layout callback is a second native trigger; adjustedContentInset
        // KVO is supplementary, not a promised UIKit notification contract.
        resizeAtKeyboardLayout()
    }

    private func connectIfPossible() {
        guard enabled, !scope.isEmpty, let window else { return }
        var ancestor = superview
        var discovered: UIScrollView?
        while let candidate = ancestor {
            if let found = candidate as? UIScrollView { discovered = found; break }
            ancestor = candidate.superview
        }
        guard let discovered, discovered.window === window else { return }
        guard scroll !== discovered || ownedWindow !== window else { return }
        disconnect()
        scroll = discovered; ownedWindow = window
        discovered.panGestureRecognizer.addTarget(self, action: #selector(panChanged(_:)))
        observations = [
            discovered.observe(\.bounds, options: [.old, .new]) { [weak self] _, change in
                guard Thread.isMainThread, change.oldValue?.size != change.newValue?.size else { return }
                MainActor.assumeIsolated { self?.resizeAtKeyboardLayout() }
            },
            discovered.observe(\.adjustedContentInset, options: [.old, .new]) { [weak self] _, change in
                guard Thread.isMainThread, change.oldValue != change.newValue else { return }
                MainActor.assumeIsolated { self?.resizeAtKeyboardLayout() }
            },
            discovered.observe(\.contentSize, options: [.old, .new]) { [weak self] _, change in
                guard Thread.isMainThread, change.oldValue != change.newValue else { return }
                MainActor.assumeIsolated { self?.resizeAtKeyboardLayout() }
            }
        ]
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(willChange(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        center.addObserver(self, selector: #selector(finished(_:)), name: UIResponder.keyboardDidChangeFrameNotification, object: nil)
        center.addObserver(self, selector: #selector(finished(_:)), name: UIApplication.didEnterBackgroundNotification, object: nil)
        observingNotifications = true
    }

    func disconnect() {
        cancel()
        scroll?.panGestureRecognizer.removeTarget(self, action: #selector(panChanged(_:)))
        observations.removeAll()
        if observingNotifications { NotificationCenter.default.removeObserver(self); observingNotifications = false }
        scroll = nil; ownedWindow = nil
    }
    private func cancel() { armedScope = nil; animationEnd = 0; deadline = 0 }
    @objc private func finished(_ note: Notification) { cancel() }
    @objc private func panChanged(_ recognizer: UIPanGestureRecognizer) {
        if recognizer.state == .began || recognizer.state == .changed { cancel() }
    }
    private func ownedScroll() -> UIScrollView? {
        guard enabled, !scope.isEmpty, isCurrent?() == true,
              UIApplication.shared.applicationState == .active,
              let scroll, let ownedWindow, window === ownedWindow, scroll.window === ownedWindow,
              !scroll.isTracking, !scroll.isDragging, !scroll.isDecelerating else { return nil }
        var ancestor = superview
        while let candidate = ancestor {
            if candidate === scroll { return scroll }
            if candidate is UIScrollView { return nil }
            ancestor = candidate.superview
        }
        return nil
    }
    @objc private func willChange(_ note: Notification) {
        guard let scroll = ownedScroll(), let window = ownedWindow,
              let begin = (note.userInfo?[UIResponder.keyboardFrameBeginUserInfoKey] as? NSValue)?.cgRectValue,
              let end = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue,
              [begin.minY, end.minY, end.width, end.height].allSatisfy({ $0.isFinite }),
              end.minY < begin.minY - 0.5,
              !window.convert(end, from: window.screen.coordinateSpace).intersection(window.bounds).isEmpty,
              shouldFollow?() == true else { cancel(); return }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        guard duration.isFinite, duration >= 0, duration <= 1 else { cancel(); return }
        let viewport = scroll.convert(scroll.bounds.inset(by: scroll.adjustedContentInset), to: window)
        let actualEnd = convert(bounds, to: window).maxY + bottomSpacing
        let distance = actualEnd - viewport.maxY
        // SwiftUI may have installed the FINAL keyboard inset before this
        // observer runs. Preserve the parent's captured pre-opening tail intent
        // instead of rejecting that legitimate newly-covered distance.
        let keyboardTravel = min(window.bounds.height, abs(end.minY - begin.minY))
        guard distance.isFinite, keyboardTravel.isFinite,
              distance >= -120, distance <= 120 + keyboardTravel else { cancel(); return }
        curve = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 0
        guard curve <= 7 else { cancel(); return }
        armedScope = scope; immediate = duration == 0
        armedWidth = scroll.bounds.width; armedWindowSize = window.bounds.size
        animationEnd = now + duration
        deadline = animationEnd + 0.10
        // Start now if the inset already changed; against an unchanged viewport
        // this is a no-op. Later real layout events keep the same owned intent.
        resizeAtKeyboardLayout()
    }
    private func resizeAtKeyboardLayout() {
        guard !writing, armedScope == scope, now <= deadline, let scroll = ownedScroll(), let window = ownedWindow else { return }
        let pixel = 1 / window.screen.scale
        guard abs(scroll.bounds.width - armedWidth) <= pixel,
              abs(window.bounds.width - armedWindowSize.width) <= pixel,
              abs(window.bounds.height - armedWindowSize.height) <= pixel else { cancel(); return }
        // Never recreate the original after-opening jump on a late layout pass.
        guard immediate || now < animationEnd || UIView.inheritedAnimationDuration > 0 else { cancel(); return }
        let inset = scroll.adjustedContentInset
        let values = [scroll.contentSize.height, scroll.bounds.height, inset.top, inset.bottom, scroll.contentOffset.y]
        guard values.allSatisfy({ $0.isFinite }), scroll.bounds.height > 0 else { cancel(); return }
        let viewport = scroll.convert(scroll.bounds.inset(by: inset), to: window)
        let actualEnd = convert(bounds, to: window).maxY + bottomSpacing
        let requested = scroll.contentOffset.y + actualEnd - viewport.maxY
        let minimum = -inset.top
        let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + inset.bottom)
        guard [viewport.maxY, actualEnd, requested, minimum, maximum].allSatisfy({ $0.isFinite }) else { cancel(); return }
        let target = min(maximum, max(minimum, requested))
        guard abs(target - scroll.contentOffset.y) > 1 / window.screen.scale else { return }
        writing = true
        defer { writing = false }
        let point = CGPoint(x: scroll.contentOffset.x, y: target)
        if UIView.inheritedAnimationDuration > 0 {
            scroll.setContentOffset(point, animated: false)
        } else {
            let remaining = max(0, animationEnd - now)
            UIView.animate(withDuration: remaining, delay: 0,
                options: [UIView.AnimationOptions(rawValue: curve << 16), .beginFromCurrentState, .allowUserInteraction]) {
                scroll.setContentOffset(point, animated: false)
            }
        }
    }
}
