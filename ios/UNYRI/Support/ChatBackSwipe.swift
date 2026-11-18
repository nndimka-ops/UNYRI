import SwiftUI
import UIKit

/// Reuses UINavigationController's interactive transition with the custom chat
/// header. It never installs a competing full-screen drag or changes routing.
struct UnyriChatBackSwipe: UIViewControllerRepresentable {
    let enabled: Bool
    var onNavigationExit: (() -> Void)? = nil
    func makeUIViewController(context: Context) -> UnyriChatBackSwipeAnchor {
        let controller = UnyriChatBackSwipeAnchor(); controller.enabled = enabled
        controller.onNavigationExit = onNavigationExit; return controller
    }
    func updateUIViewController(_ controller: UnyriChatBackSwipeAnchor, context: Context) {
        controller.enabled = enabled; controller.onNavigationExit = onNavigationExit; controller.scheduleAttachment()
    }
    static func dismantleUIViewController(_ controller: UnyriChatBackSwipeAnchor, coordinator: ()) { controller.retire() }
}

@MainActor final class UnyriChatBackSwipeAnchor: UIViewController, UIGestureRecognizerDelegate {
    var enabled = false
    var onNavigationExit: (() -> Void)?
    private var retired = false
    private var appeared = false
    private weak var navigation: UINavigationController?
    private weak var gesture: UIGestureRecognizer?
    private weak var previousDelegate: (any UIGestureRecognizerDelegate)?
    private var previousEnabled = false
    private var attachment: DispatchWorkItem?
    private weak var routeNavigation: UINavigationController?
    private weak var routeController: UIViewController?
    private var hadRouteOwner = false
    private var reportedRouteExit = false

    override func loadView() {
        let anchor = UIView(); anchor.backgroundColor = .clear; anchor.isUserInteractionEnabled = false
        view = anchor
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // A background UIView can enter the window before SwiftUI has made
        // the pushed controller topmost. Attach at the real completed lifecycle
        // boundary instead of guessing a delay or relying on another render.
        appeared = true; scheduleAttachment()
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        appeared = false; restoreAfterTransition()
    }
    func scheduleAttachment() {
        guard !retired, appeared, viewIfLoaded?.window != nil, attachment == nil else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }; self.attachment = nil; self.attach()
        }
        attachment = work; DispatchQueue.main.async(execute: work)
    }
    private func attach() {
        guard !retired, appeared, viewIfLoaded?.window != nil else { return }
        var responder: UIResponder? = self
        var controller: UINavigationController?
        while let current = responder {
            if let value = current as? UIViewController, let nav = value.navigationController { controller = nav; break }
            responder = current.next
        }
        guard let controller, let top = controller.topViewController,
              view.isDescendant(of: top.view), controller.viewControllers.count > 1,
              let pan = controller.interactivePopGestureRecognizer else { return }
        routeNavigation = controller; routeController = top; hadRouteOwner = true
        if gesture === pan, pan.delegate === self { return }
        restoreNow()
        if let prior = pan.delegate as? UnyriChatBackSwipeAnchor {
            previousDelegate = prior.previousDelegate; previousEnabled = prior.previousEnabled
        } else { previousDelegate = pan.delegate; previousEnabled = pan.isEnabled }
        navigation = controller; gesture = pan; pan.delegate = self; pan.isEnabled = true
    }
    func retire() {
        retired = true; attachment?.cancel(); attachment = nil; restoreAfterTransition()
    }
    private func restoreAfterTransition() {
        if let transition = navigation?.transitionCoordinator {
            // Disabling a recognizer during its own interactive pop cancels
            // that transition. Keep ownership through its actual completion.
            transition.animate(alongsideTransition: nil) { [self] context in
                restoreNow()
                if !context.isCancelled { reportNavigationExit() }
            }
        } else { restoreNow(); reportNavigationExit() }
    }
    private func reportNavigationExit() {
        guard hadRouteOwner, !reportedRouteExit, let nav = routeNavigation,
              nav.isBeingDismissed || routeController == nil ||
                !nav.viewControllers.contains(where: { $0 === routeController }) else { return }
        reportedRouteExit = true
        let action = onNavigationExit
        // UIKit/SwiftUI may be dismantling the anchor in its render pass.
        Task { @MainActor in action?() }
    }
    private func restoreNow() {
        defer { gesture = nil; navigation = nil; previousDelegate = nil }
        guard let gesture, gesture.delegate === self else { return }
        gesture.delegate = previousDelegate
        gesture.isEnabled = previousEnabled && (previousDelegate != nil || (navigation?.viewControllers.count ?? 0) > 1)
    }
    func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        guard enabled, !retired, appeared, viewIfLoaded?.window != nil, recognizer === gesture,
              let navigation, navigation.viewControllers.count > 1,
              navigation.transitionCoordinator == nil, let top = navigation.topViewController,
              view.isDescendant(of: top.view) else { return false }
        var ancestor: UIViewController? = top
        while let current = ancestor {
            if current.presentedViewController != nil { return false }
            ancestor = current.parent
        }
        guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
        let velocity = pan.velocity(in: navigation.view)
        let translation = pan.translation(in: navigation.view)
        let vector = velocity == .zero ? translation : velocity
        let direction: CGFloat = navigation.view.effectiveUserInterfaceLayoutDirection == .rightToLeft ? -1 : 1
        return vector == .zero || (vector.x * direction > 0 && abs(vector.x) > abs(vector.y))
    }
}
