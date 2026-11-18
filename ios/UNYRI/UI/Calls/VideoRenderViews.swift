import SwiftUI
import UIKit
import WebRTC

@MainActor
protocol CallVideoGeometryReceiving: AnyObject {
    func videoGeometryChanged(_ size: CGSize, rotation: Int)
}

/// The same Metal renderer; only geometry changes escape the engine's existing
/// first-frame wrapper. No second sink and no per-frame main-thread callback.
final class CallMetalVideoView: RTCMTLVideoView, CallVideoGeometryReceiving {
    var onGeometry: ((CGSize, Int) -> Void)?
    var preferredFillsBounds: Bool? { didSet { updateFit() } }
    private var frameSize = CGSize.zero
    func videoGeometryChanged(_ size: CGSize, rotation: Int) {
        frameSize = size; updateFit(); onGeometry?(size, rotation)
    }
    override func layoutSubviews() { super.layoutSubviews(); updateFit() }
    private func updateFit() {
        let fills = preferredFillsBounds ?? CallOrientationPolicy.remoteFills(frame: frameSize, stage: bounds.size)
        let mode: UIView.ContentMode = fills ? .scaleAspectFill : .scaleAspectFit
        if videoContentMode != mode { videoContentMode = mode }
    }
}

private struct CallSelfViewDeltaKey: EnvironmentKey { static let defaultValue = 0 }
extension EnvironmentValues {
    var callSelfViewDelta: Int {
        get { self[CallSelfViewDeltaKey.self] }
        set { self[CallSelfViewDeltaKey.self] = newValue }
    }
}

/// Rotate the existing renderer inside the tile, swapping its proposed bounds
/// before rotation so the surface fills the swapped tile without stretching.
struct CompensatedLocalVideoView: View {
    let isFrontFacing: Bool
    let delta: Int
    var body: some View {
        GeometryReader { geometry in
            let swap = CallOrientationPolicy.swapsAspect(delta: delta)
            LocalVideoView(isFrontFacing: isFrontFacing)
                .frame(width: swap ? geometry.size.height : geometry.size.width,
                       height: swap ? geometry.size.width : geometry.size.height)
                .rotationEffect(.degrees(Double(delta)))
                .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
    }
}

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
    // Preserve fill for the mini window. The full call passes its fit/fill decision.
    var fillsBounds: Bool? = true
    /// False while nothing of it can be seen: the Metal view pauses its
    /// display link and drops frames, keeping the last one drawn. It stays
    /// attached, so the engine's wrapper still sees every frame (first-frame
    /// and remote video state go on).
    var active = true
    var onGeometry: ((CGSize, Int) -> Void)? = nil
    @EnvironmentObject private var controller: CallController

    func makeCoordinator() -> VideoRendererCoordinator { VideoRendererCoordinator() }

    func makeUIView(context: Context) -> CallMetalVideoView {
        let view = CallMetalVideoView(frame: .zero)
        view.onGeometry = onGeometry
        view.preferredFillsBounds = fillsBounds
        view.clipsToBounds = true
        view.isEnabled = active
        context.coordinator.bind(view, controller: controller, local: false)
        return view
    }

    func updateUIView(_ uiView: CallMetalVideoView, context: Context) {
        uiView.onGeometry = onGeometry
        uiView.preferredFillsBounds = fillsBounds
        if uiView.isEnabled != active { uiView.isEnabled = active }
        context.coordinator.bind(uiView, controller: controller, local: false)
    }

    static func dismantleUIView(_ uiView: CallMetalVideoView, coordinator: VideoRendererCoordinator) {
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
        // Front-camera frames are already mirrored on capture (CallFrontCameraMirror),
        // so the self-view shows them as they are sent.
        view.transform = .identity
        context.coordinator.bind(view, controller: controller, local: true)
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        if uiView.transform != .identity { uiView.transform = .identity }
        context.coordinator.bind(uiView, controller: controller, local: true)
    }

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: VideoRendererCoordinator) {
        coordinator.dismantle(uiView)
    }
}
