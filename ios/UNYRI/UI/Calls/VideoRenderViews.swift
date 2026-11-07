import SwiftUI
import UIKit
import WebRTC

/// UIViewRepresentable wrappers around WebRTC's Metal-backed `RTCMTLVideoView`.
/// The controller owns track<->renderer wiring; these views only provide the
/// platform view and forward attach/detach so a stale view is never kept
/// attached after the call ends.

@MainActor
final class VideoRendererCoordinator {
    weak var controller: CallController?
    private weak var view: RTCMTLVideoView?
    private var local = false
    private var observers = [NSObjectProtocol]()

    func bind(_ view: RTCMTLVideoView, controller: CallController, local: Bool) {
        self.controller = controller
        self.view = view
        self.local = local
        if observers.isEmpty {
            for notification in [UIApplication.didEnterBackgroundNotification, UIApplication.didBecomeActiveNotification] {
                observers.append(NotificationCenter.default.addObserver(forName: notification, object: nil, queue: .main) { [weak self] _ in
                    Task { @MainActor in self?.updateAttachment() }
                })
            }
        }
        updateAttachment()
    }

    private func updateAttachment() {
        guard let view else { return }
        if UIApplication.shared.applicationState == .background {
            if local { controller?.detachLocalRenderer(view) } else { controller?.detachRemoteRenderer(view) }
        } else {
            if local { controller?.attachLocalRenderer(view) } else { controller?.attachRemoteRenderer(view) }
        }
    }

    func dismantle(_ view: RTCMTLVideoView) {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        if local { controller?.detachLocalRenderer(view) } else { controller?.detachRemoteRenderer(view) }
        self.view = nil
    }
}

struct RemoteVideoView: UIViewRepresentable {
    // Existing inline/PiP callers retain their aspect-fill default.
    var fillsBounds = true
    @EnvironmentObject private var controller: CallController

    func makeCoordinator() -> VideoRendererCoordinator { VideoRendererCoordinator() }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = fillsBounds ? .scaleAspectFill : .scaleAspectFit
        view.clipsToBounds = true
        context.coordinator.bind(view, controller: controller, local: false)
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        let mode: UIView.ContentMode = fillsBounds ? .scaleAspectFill : .scaleAspectFit
        if uiView.videoContentMode != mode { uiView.videoContentMode = mode }
        context.coordinator.bind(uiView, controller: controller, local: false)
    }

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: VideoRendererCoordinator) {
        coordinator.dismantle(uiView)
    }
}

struct LocalVideoView: UIViewRepresentable {
    var isFrontFacing: Bool = true
    @EnvironmentObject private var controller: CallController

    func makeCoordinator() -> VideoRendererCoordinator { VideoRendererCoordinator() }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.layer.cornerRadius = UnyriShapes.radiusLocalVideo
        view.layer.cornerCurve = .continuous
        view.layer.masksToBounds = true
        // Mirror only the local self-view, matching the native front-camera
        // preview. The remote view remains unmodified.
        view.transform = CGAffineTransform(scaleX: isFrontFacing ? -1 : 1, y: 1)
        context.coordinator.bind(view, controller: controller, local: true)
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        let mirror = CGAffineTransform(scaleX: isFrontFacing ? -1 : 1, y: 1)
        if uiView.transform != mirror { uiView.transform = mirror }
        context.coordinator.bind(uiView, controller: controller, local: true)
    }

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: VideoRendererCoordinator) {
        coordinator.dismantle(uiView)
    }
}
