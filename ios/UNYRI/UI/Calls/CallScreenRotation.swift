import SwiftUI
import UIKit

/// The cover owns the lease. Leaving it is a two-phase operation: first the
/// system rotates the still-opaque call to portrait, then the cover may leave.
@MainActor
final class CallScreenRotation: ObservableObject {
    private let token = UUID()
    private weak var scene: UIWindowScene?
    // SwiftUI can update the background representable before it has a window.
    // Keep the requested state and apply it when the real scene attaches.
    private var allowed = false
    private var presented = false
    private var leased = false
    private var leaving = false
    private var pending: (() -> Void)?
    private var portraitGeometry = true
    private var transitioning = false

    func attach(_ scene: UIWindowScene?) {
        guard scene !== self.scene else { return }
        release()
        self.scene = scene
        synchronizeOrientation()
        synchronizeLease()
    }

    func allowRotation(_ allowed: Bool) {
        self.allowed = allowed
        synchronizeLease()
    }

    private func synchronizeLease() {
        guard presented, allowed, !leaving, let scene else { release(); return }
        if !leased {
            leased = true
            UnyriMediaOrientation.set(true, token: token, scene: scene)
            synchronizeOrientation()
        }
    }

    private func release() {
        if leased, let scene {
            UnyriMediaOrientation.set(false, token: token, scene: scene)
            synchronizeOrientation()
        }
        leased = false
    }

    private func synchronizeOrientation() {
        let mask = UnyriMediaOrientation.mask(for: scene)
        CallDeviceOrientation.shared.updateInterface(CallOrientation(interface: scene?.interfaceOrientation ?? .portrait),
            allowsLandscape: mask.contains(.landscapeLeft) || mask.contains(.landscapeRight))
    }

    /// Bumped by every new leave request, so a stale safety timer never runs a later action.
    private var leaveGeneration = 0
    /// Portrait normally lands within one system rotation (~0.4 s). A declined
    /// geometry request has a logged safety fallback so leave cannot get stuck.
    static let leaveTimeout: TimeInterval = 1.5

    func beforeLeaving(_ action: @escaping () -> Void) {
        // Preserve all callers while one system rotation is in flight.
        if let previous = pending { pending = { previous(); action() }; return }
        leaving = true
        pending = action
        leaveGeneration &+= 1
        let generation = leaveGeneration
        release()
        finishIfPortrait()
        guard pending != nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.leaveTimeout) { [weak self] in
            guard let self, self.leaveGeneration == generation, let action = self.pending else { return }
            self.pending = nil
            NSLog("CallScreenRotation: portrait leave timed out after %.1f s", Self.leaveTimeout)
            action()
        }
    }

    func geometry(_ size: CGSize, transitioning: Bool) {
        self.transitioning = transitioning
        portraitGeometry = size.width <= size.height
        synchronizeOrientation()
        finishIfPortrait()
    }

    private func finishIfPortrait() {
        // iPad already allowed every orientation before calls; do not deadlock
        // its existing multitasking/media orientation behavior.
        let portrait = UIDevice.current.userInterfaceIdiom == .pad
            || (scene?.interfaceOrientation == .portrait && portraitGeometry && !transitioning)
        // A media viewer below the call can still own landscape. No portrait
        // completion will arrive; keep that owner's existing orientation.
        let mask = UnyriMediaOrientation.mask(for: scene)
        let anotherOwner = mask.contains(.landscapeLeft) || mask.contains(.landscapeRight)
        guard (scene == nil || portrait || anotherOwner), let action = pending else { return }
        pending = nil
        action()
    }

    func disappeared() {
        presented = false
        allowed = false
        leaveGeneration &+= 1
        release(); pending = nil; leaving = false
    }

    func beginPresentation() {
        presented = true
        allowed = false
        leaveGeneration &+= 1
        release(); leaving = false; pending = nil
    }

    func cancelLeaving() { leaving = false; synchronizeLease() }
}

/// Receives real window attachment and system rotation completion. The 1.5 s
/// leave fallback belongs to the coordinator; this host never owns video.
struct CallRotationHost: UIViewControllerRepresentable {
    let rotation: CallScreenRotation
    let active: Bool

    func makeUIViewController(context: Context) -> Observer {
        let view = Observer()
        view.rotation = rotation
        return view
    }
    func updateUIViewController(_ view: Observer, context: Context) {
        view.rotation = rotation
        view.allowed = active
        view.synchronize()
    }
    static func dismantleUIViewController(_ view: Observer, coordinator: ()) {
        view.allowed = false
        view.rotation?.disappeared()
    }

    final class AnchorView: UIView {
        var windowChanged: (() -> Void)?
        override func didMoveToWindow() { super.didMoveToWindow(); windowChanged?() }
    }

    final class Observer: UIViewController {
        var rotation: CallScreenRotation?
        var allowed = false
        private var turning = false
        private var waitingForPresentation = false

        override func loadView() {
            let anchor = AnchorView()
            anchor.isUserInteractionEnabled = false
            anchor.windowChanged = { [weak self] in self?.synchronize() }
            view = anchor
        }

        func synchronize() {
            let window = view.window
            rotation?.attach(window?.windowScene)
            // A UIViewControllerRepresentable used as a SwiftUI background is
            // not guaranteed an appearance callback on every iOS version.
            // Window attachment, not an `appeared` latch, owns the lease.
            guard allowed, let window else { rotation?.allowRotation(false); return }
            if let coordinator = presentationTransition(in: window) {
                rotation?.allowRotation(false)
                guard !waitingForPresentation else { return }
                waitingForPresentation = true
                let coordinated = coordinator.animate(alongsideTransition: nil) { [weak self] _ in
                    // Let UIKit clear isBeingPresented before rechecking.
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.waitingForPresentation = false
                        self.synchronize()
                    }
                }
                if coordinated { return }
                waitingForPresentation = false
            }
            rotation?.allowRotation(true)
            rotation?.geometry(window.bounds.size, transitioning: turning)
        }

        private func presentationTransition(in window: UIWindow) -> UIViewControllerTransitionCoordinator? {
            // Initial system cover presentation also grows in portrait. The
            // custom expansion morph is gated by its completion callback.
            var current: UIViewController? = self
            while let controller = current {
                if controller.isBeingPresented, let coordinator = controller.transitionCoordinator { return coordinator }
                current = controller.parent
            }
            current = window.rootViewController
            while let controller = current {
                // Stop at the cover containing this anchor. A sheet or alert
                // presented ABOVE it must never revoke the call's lease.
                if view.isDescendant(of: controller.view) {
                    return controller.isBeingPresented ? controller.transitionCoordinator : nil
                }
                current = controller.presentedViewController
            }
            return nil
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            synchronize()
        }
        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            synchronize()
        }
        override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
            super.viewWillTransition(to: size, with: coordinator)
            turning = true
            rotation?.geometry(size, transitioning: true)
            coordinator.animate(alongsideTransition: nil) { [weak self] _ in
                guard let self else { return }
                self.turning = false
                self.synchronize()
            }
        }
    }
}
