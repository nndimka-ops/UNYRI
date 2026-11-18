import AVKit
import Combine
import SwiftUI
import WebRTC
import OSLog

/// System Picture in Picture for a 1:1 video call. Account-owned presentation only;
/// media and hangup remain in CallController.
///
/// Uses the video-call content source (`activeVideoCallSourceView` +
/// `AVPictureInPictureVideoCallViewController`), so the system window shows only the
/// close and "back to full screen" buttons, like FaceTime/WhatsApp/Telegram. Player
/// controls (skip ±10 s, play/pause, LIVE, progress) come only from a playback
/// content source, which calls must not use. Closing the window never ends the call.
@MainActor
final class CallPictureInPicture: NSObject, ObservableObject, AVPictureInPictureControllerDelegate {
    @Published private(set) var restoreRequest = 0
    @Published private(set) var isSystemPresented = false
    private weak var callController: CallController?
    private var controller: AVPictureInPictureController?
    private var content: AVPictureInPictureVideoCallViewController?
    private var video: PipVideoView?
    private var sources = [WeakPipSource]()
    private weak var hostWindow: UIWindow?
    /// Lowest-priority source in the app window itself: another full-screen cover
    /// (media viewer, camera) takes the SwiftUI sources out of the window.
    private var windowSource: UIView?
    private weak var configuredSource: UIView?
    private var possibleObservation: NSKeyValueObservation?
    private var callID: String?
    private var presentation = CallPipPresentation()
    private let logger = Logger(subsystem: "com.unyri.calls", category: "PictureInPicture")
    /// AVKit announced a start (WillStart) that has not finished yet.
    private var starting = false
    // Eligibility follows the negotiated video call, not the peer's camera.
    // A paused camera or brief reconnect keeps the window with its avatar.
    private var videoCallAvailable = false
    private var stopRequested = false
    /// The restore button was used: AVKit stops the window itself.
    private var restoring = false

    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(applicationWillResignActive),
            name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(applicationDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    /// A source view entered or left a window, or was laid out again.
    func sourceChanged(_ view: UIView) {
        sources.removeAll { $0.view == nil }
        if let window = view.window, !view.bounds.isEmpty {
            hostWindow = window
            if !sources.contains(where: { $0.view === view }) { sources.append(WeakPipSource(view)) }
        }
        refreshSource()
    }

    func removeSource(_ view: UIView) {
        sources.removeAll { $0.view == nil || $0.view === view }
        refreshSource()
    }

    private func refreshSource() {
        guard let content else { return }
        if let hostWindow, windowSource?.window !== hostWindow {
            windowSource?.removeFromSuperview()
            let fallback = UIView(frame: hostWindow.bounds)
            fallback.isUserInteractionEnabled = false
            fallback.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            hostWindow.insertSubview(fallback, at: 0)
            windowSource = fallback
        }
        // The newest SwiftUI source still in a window wins (call screen over root).
        let candidates = sources.reversed().compactMap { $0.view } + [windowSource].compactMap { $0 }
        guard let source = candidates.first(where: { $0.window != nil && !$0.bounds.isEmpty }) else { return }
        if controller == nil {
            let pip = AVPictureInPictureController(contentSource: .init(activeVideoCallSourceView: source, contentViewController: content))
            pip.delegate = self
            pip.canStartPictureInPictureAutomaticallyFromInline = videoCallAvailable
            controller = pip
            configuredSource = source
            let described = describe(source)
            logger.info("PiP controller created: \(described, privacy: .public)")
            // Logging only. AVKit starts the window itself when the app leaves
            // (automatic inline start); a start requested from the background
            // is not supported, and its failure report used to stop the frames
            // of a window the system had started.
            possibleObservation = pip.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] _, change in
                let possible = change.newValue ?? false
                Task { @MainActor in self?.logger.info("PiP possible=\(possible)") }
            }
            return
        }
        guard let controller, !starting, !controller.isPictureInPictureActive,
              configuredSource !== source else { return }
        controller.contentSource = .init(activeVideoCallSourceView: source, contentViewController: content)
        configuredSource = source
        let described = describe(source)
        logger.info("PiP source moved: \(described, privacy: .public)")
    }

    func returnToApplication() {
        guard UIApplication.shared.applicationState == .active else { return }
        // The restore button already stops the window; a second stop cuts its animation.
        guard !restoring else { return }
        if starting || controller?.isPictureInPictureActive == true {
            stopRequested = true
            controller?.stopPictureInPicture()
        }
    }

    /// Leaving the app begins a fresh native start: an earlier return-to-app
    /// intent must not cancel it. Logs what AVKit decides the start on.
    @objc private func applicationWillResignActive() {
        logState("Resign active")
        guard !starting, controller?.isPictureInPictureActive != true else { return }
        stopRequested = false
    }

    @objc private func applicationDidEnterBackground() {
        logState("Background")
    }

    /// One line per app transition (no peer names, call IDs or media): why
    /// the automatic start did or did not happen.
    private func logState(_ event: String) {
        guard callID != nil else { return }
        let hasController = controller != nil
        let possible = controller?.isPictureInPicturePossible ?? false
        let automatic = controller?.canStartPictureInPictureAutomaticallyFromInline ?? false
        let active = controller?.isPictureInPictureActive ?? false
        let videoCall = videoCallAvailable
        let isStarting = starting
        let source = describe(configuredSource)
        let appLock = UnyriAppLock.shared.enabled
        // The app-lock shield is a window above alerts; up while AVKit decides,
        // it covers the source view.
        let windows = hostWindow?.windowScene?.windows ?? []
        let shield = windows.contains { $0.windowLevel.rawValue > UIWindow.Level.alert.rawValue && !$0.isHidden }
        logger.info("\(event, privacy: .public): controller=\(hasController) possible=\(possible) automatic=\(automatic) active=\(active) starting=\(isStarting) videoCall=\(videoCall) appLock=\(appLock) shield=\(shield) source=\(source, privacy: .public)")
    }

    /// Which source, its size and whether it is really on screen.
    private func describe(_ view: UIView?) -> String {
        guard let view else { return "none" }
        let role = view === windowSource ? "window" : (view as? PipSourceView)?.role ?? "unknown"
        var visible = view.window != nil
        var current: UIView? = view
        while let ancestor = current, visible {
            if ancestor.isHidden || ancestor.alpha < 0.01 || ancestor.layer.opacity < 0.01 { visible = false }
            current = ancestor.superview
        }
        return "\(role) \(Int(view.bounds.width))x\(Int(view.bounds.height)) inWindow=\(view.window != nil) visible=\(visible)"
    }

    func update(_ state: CallUiState, owner: CallController, chats: ChatsRepository, users: UsersRepository) {
        // Detach through the owner that attached the renderer, before replacing it.
        if callController !== owner { stop() }
        callController = owner
        guard let (call, showVideo) = presentation.update(state) else {
            stop()
            return
        }
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        if callID != call.callId {
            stop()
            callID = call.callId
            let view = PipVideoView(frame: CGRect(x: 0, y: 0, width: 300, height: 400))
            let content = AVPictureInPictureVideoCallViewController()
            content.preferredContentSize = CGSize(width: 300, height: 400)
            content.view.frame = view.bounds
            content.view.backgroundColor = .black
            content.view.addSubview(view)
            view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                view.leadingAnchor.constraint(equalTo: content.view.leadingAnchor),
                view.trailingAnchor.constraint(equalTo: content.view.trailingAnchor),
                view.topAnchor.constraint(equalTo: content.view.topAnchor),
                view.bottomAnchor.constraint(equalTo: content.view.bottomAnchor)
            ])
            // Shown whenever no fresh peer frame is available (camera paused, both
            // cameras off, reconnecting): the window stays and says who is on the call.
            let avatar = UIHostingController(rootView: GeometryReader { geometry in
                let side = min(geometry.size.width, geometry.size.height)
                VStack(spacing: 6) {
                    CallPeerAvatar(userId: call.remoteUserId, name: call.visibleRemoteName,
                        size: min(96, side * (side > 140 ? 0.5 : 0.65)))
                    if side > 140 {
                        Text(call.visibleRemoteName).font(.footnote.weight(.semibold)).foregroundColor(.white)
                            .lineLimit(1).minimumScaleFactor(0.7).padding(.horizontal, 8)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }.environmentObject(chats).environmentObject(users))
            avatar.view.backgroundColor = .clear
            content.addChild(avatar)
            view.installAvatar(avatar.view)
            avatar.didMove(toParent: content)
            self.content = content
            // The window follows the peer's aspect ratio, also after resolution or rotation changes.
            view.onVideoSize = { [weak content] size in
                guard let content, let preferred = CallPipGeometry.preferredContentSize(for: size),
                      content.preferredContentSize != preferred else { return }
                content.preferredContentSize = preferred
            }
            video = view
            content.view.layoutIfNeeded()
            owner.attachPictureInPictureRenderer(view.renderer)
        }
        video?.setPresentation(showVideo: showVideo)
        videoCallAvailable = true
        refreshSource()
        // Only the automatic start: a call that becomes video while the app is
        // already away gets its window the next time the app leaves.
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    func stop() {
        videoCallAvailable = false
        isSystemPresented = false
        starting = false
        stopRequested = false
        restoring = false
        possibleObservation = nil
        controller?.canStartPictureInPictureAutomaticallyFromInline = false
        controller?.stopPictureInPicture()
        controller?.contentSource = nil
        controller?.delegate = nil
        controller = nil
        configuredSource = nil
        windowSource?.removeFromSuperview()
        windowSource = nil
        if let video { callController?.detachPictureInPictureRenderer(video.renderer) }
        video?.renderer.invalidate()
        video = nil
        content = nil
        callID = nil
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        guard controller === self.controller, callID != nil else { completionHandler(false); return }
        let expectedCallID = callID
        restoring = true
        restoreRequest += 1
        // One main-queue turn lets SwiftUI present the full call screen; AVKit then
        // animates the window back into the app.
        DispatchQueue.main.async { [weak self, weak controller] in
            guard let self, let controller, controller === self.controller,
                  self.callID == expectedCallID else { completionHandler(false); return }
            completionHandler(true)
        }
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === self.controller else { return }
        // Native automatic start may arrive while applicationState is still
        // active. Only an explicit return during the transition cancels it.
        logger.info("Video PiP will start")
        restoring = false
        starting = true
        isSystemPresented = true
        video?.renderer.setRunning(true)
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === self.controller else { return }
        starting = false
        if !videoCallAvailable || stopRequested { controller.stopPictureInPicture() }
        logger.info("Video PiP started")
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        guard controller === self.controller else { return }
        let failure = error as NSError
        let active = controller.isPictureInPictureActive
        // Only platform error identifiers: no peer names, call IDs or media.
        logger.error("Video PiP failed: domain=\(failure.domain, privacy: .public) code=\(failure.code) active=\(active)")
        // A window the system did start stays fed and presented; a failure
        // report must never blank it. Without one, nothing is on screen.
        guard !active else { return }
        starting = false
        stopRequested = false
        isSystemPresented = false
        video?.renderer.setRunning(false)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === self.controller else { return }
        // X (or a system stop) while the app is away: the call goes on and stays
        // reachable from the call banner/island. Nothing reopens the window from
        // the background; the automatic start comes back with the next exit.
        let away = UIApplication.shared.applicationState != .active
        logger.info("Video PiP stopped: away=\(away)")
        starting = false
        stopRequested = false
        restoring = false
        isSystemPresented = false
        video?.renderer.setRunning(false)
        refreshSource()
    }
}

private final class WeakPipSource {
    weak var view: UIView?
    init(_ view: UIView) { self.view = view }
}

struct CallPipSource: UIViewRepresentable {
    let pip: CallPictureInPicture
    /// Names the source in the PiP log only.
    var role = "root"
    func makeUIView(context: Context) -> UIView {
        let view = PipSourceView()
        view.role = role
        view.isUserInteractionEnabled = false
        // Also on leaving a window, so the controller moves to a source still on screen.
        view.onChange = { [weak pip, weak view] in
            guard let view else { return }
            pip?.sourceChanged(view)
        }
        context.coordinator.pip = pip
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator() }
    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) { coordinator.pip?.removeSource(view) }
    final class Coordinator { weak var pip: CallPictureInPicture? }
}

private final class PipSourceView: UIView {
    var onChange: (() -> Void)?
    var role = "root"
    override func didMoveToWindow() { super.didMoveToWindow(); onChange?() }
    override func layoutSubviews() { super.layoutSubviews(); onChange?() }
}

/// AVSampleBufferDisplayLayer works in background on iOS 16/17 as well as newer iOS.
/// The normal Metal renderer remains attached independently to the same track.
private final class PipVideoView: UIView {
    var onVideoSize: ((CGSize) -> Void)?
    private var videoSize = CGSize.zero
    private let surface = PipSampleBufferView()
    lazy var renderer = PipFrameRenderer(displayLayer: surface.displayLayer)
    private let placeholder = UIView()
    private var rotation: RTCVideoRotation = ._0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(red: 0.06, green: 0.05, blue: 0.12, alpha: 1)
        clipsToBounds = true
        addSubview(surface)
        renderer.displayLayer.videoGravity = .resizeAspectFill
        placeholder.backgroundColor = backgroundColor
        addSubview(placeholder)
        renderer.onVideoGeometry = { [weak self] size, rotation in
            guard let self else { return }
            let oriented = CallPipGeometry.orientedSize(size, rotationDegrees: rotation.rawValue)
            if self.videoSize != oriented || self.rotation != rotation {
                self.rotation = rotation
                self.videoSize = oriented
                self.onVideoSize?(oriented)
                self.setNeedsLayout()
            }
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func installAvatar(_ view: UIView) {
        placeholder.addSubview(view)
        view.frame = placeholder.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    func setPresentation(showVideo: Bool) {
        placeholder.isHidden = showVideo
        renderer.setVisible(showVideo)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeholder.frame = bounds
        let sideways = rotation == ._90 || rotation == ._270
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        surface.bounds = CGRect(origin: .zero, size: sideways ? CGSize(width: bounds.height, height: bounds.width) : bounds.size)
        surface.center = CGPoint(x: bounds.midX, y: bounds.midY)
        surface.transform = CGAffineTransform(rotationAngle: CGFloat(rotation.rawValue) * .pi / 180)
        CATransaction.commit()
    }
}

/// A sample-buffer-backed view remains renderable while UIKit/Metal is suspended.
private final class PipSampleBufferView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
}

/// At most one frame is in flight: slow rendering never queues seconds of old video.
private final class PipFrameRenderer: NSObject, RTCVideoRenderer {
    private struct Geometry: Equatable {
        let width: Int32
        let height: Int32
        let rotation: RTCVideoRotation
    }
    let displayLayer: AVSampleBufferDisplayLayer
    init(displayLayer: AVSampleBufferDisplayLayer) {
        self.displayLayer = displayLayer
        super.init()
    }
    /// Main thread: raw frame size and rotation whenever either changes.
    var onVideoGeometry: ((CGSize, RTCVideoRotation) -> Void)?
    private let lock = NSLock()
    private var busy = false
    private var valid = true
    private var visible = false
    private var running = false
    private var primed = false
    private var geometry: Geometry?
    private var generation: UInt64 = 0
    private let queue = DispatchQueue(label: "unyri.call.pip.frames", qos: .userInitiated)

    func setRunning(_ value: Bool) {
        lock.lock(); running = value; generation &+= 1; lock.unlock()
    }
    func setVisible(_ value: Bool) {
        lock.lock()
        let changed = visible != value
        visible = value
        if changed { primed = false }
        if changed { generation &+= 1 }
        lock.unlock()
        if changed && !value { displayLayer.flushAndRemoveImage() }
    }
    func invalidate() {
        lock.lock(); valid = false; generation &+= 1; lock.unlock()
        displayLayer.flushAndRemoveImage()
    }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        let next = Geometry(width: frame.width, height: frame.height, rotation: frame.rotation)
        lock.lock()
        // Size and rotation come from every frame (cheap), so the window keeps the
        // peer's aspect ratio even while inline frames are not decoded. A change
        // re-primes once: the parked first frame then matches the new geometry.
        let geometryChanged = valid && geometry != next
        if geometryChanged { geometry = next; primed = false }
        guard CallPipFramePolicy.acceptsFrame(valid: valid, running: running, primed: primed, visible: visible, busy: busy) else {
            lock.unlock()
            if geometryChanged { publishGeometry(next) }
            return
        }
        busy = true
        let expected = generation
        lock.unlock()
        if geometryChanged { publishGeometry(next) }
        queue.async { [weak self] in
            guard let self else { return }
            let buffer = Self.pixelBuffer(frame.buffer)
            DispatchQueue.main.async {
                self.lock.lock()
                let current = self.valid && (self.running || !self.primed) && self.visible && self.generation == expected
                self.busy = false
                self.lock.unlock()
                guard current, let buffer else { return }
                if self.displayLayer.status == .failed { self.displayLayer.flush() }
                guard self.displayLayer.isReadyForMoreMediaData else { return }
                var format: CMVideoFormatDescription?
                guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
                    imageBuffer: buffer, formatDescriptionOut: &format) == noErr, let format else { return }
                var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 30), presentationTimeStamp: .zero, decodeTimeStamp: .invalid)
                var sample: CMSampleBuffer?
                guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: buffer,
                    formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr, let sample else { return }
                if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) {
                    let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
                    CFDictionarySetValue(dictionary, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                        Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
                }
                self.displayLayer.enqueue(sample)
                self.lock.lock(); self.primed = true; self.lock.unlock()
            }
        }
    }

    private func publishGeometry(_ geometry: Geometry) {
        let size = CGSize(width: CGFloat(geometry.width), height: CGFloat(geometry.height))
        let rotation = geometry.rotation
        DispatchQueue.main.async { [weak self] in self?.onVideoGeometry?(size, rotation) }
    }

    private static func pixelBuffer(_ source: RTCVideoFrameBuffer) -> CVPixelBuffer? {
        if let native = source as? RTCCVPixelBuffer { return native.pixelBuffer }
        let i420 = source.toI420()
        var result: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, Int(i420.width), Int(i420.height),
            kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attributes, &result) == kCVReturnSuccess,
            let result else { return nil }
        CVPixelBufferLockBaseAddress(result, [])
        defer { CVPixelBufferUnlockBaseAddress(result, []) }
        guard let y = CVPixelBufferGetBaseAddressOfPlane(result, 0), let uv = CVPixelBufferGetBaseAddressOfPlane(result, 1) else { return nil }
        for row in 0..<Int(i420.height) {
            memcpy(y.advanced(by: row * CVPixelBufferGetBytesPerRowOfPlane(result, 0)),
                i420.dataY.advanced(by: row * Int(i420.strideY)), Int(i420.width))
        }
        for row in 0..<Int(i420.chromaHeight) {
            let target = uv.advanced(by: row * CVPixelBufferGetBytesPerRowOfPlane(result, 1)).assumingMemoryBound(to: UInt8.self)
            let u = i420.dataU.advanced(by: row * Int(i420.strideU))
            let v = i420.dataV.advanced(by: row * Int(i420.strideV))
            for column in 0..<Int(i420.chromaWidth) { target[column * 2] = u[column]; target[column * 2 + 1] = v[column] }
        }
        return result
    }
}
