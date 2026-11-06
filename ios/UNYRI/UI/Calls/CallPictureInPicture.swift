import AVKit
import Combine
import SwiftUI
import WebRTC
import OSLog

/// Account-owned presentation only; media and hangup remain in CallController.
@MainActor
final class CallPictureInPicture: NSObject, ObservableObject, AVPictureInPictureControllerDelegate {
    @Published private(set) var restoreRequest = 0
    @Published private(set) var isSystemPresented = false
    private weak var callController: CallController?
    private var controller: AVPictureInPictureController?
    private var content: AVPictureInPictureVideoCallViewController?
    private var video: PipVideoView?
    private var sources = [WeakPipSource]()
    private weak var configuredSource: UIView?
    private var possibleObservation: NSKeyValueObservation?
    private var callID: String?
    private var presentation = CallPipPresentation()
    private let logger = Logger(subsystem: "com.unyri.calls", category: "PictureInPicture")
    private var starting = false
    // Eligibility follows the negotiated video call, not the peer's camera.
    // A paused camera or brief reconnect keeps the window with its avatar.
    private var videoCallAvailable = false
    private var stopRequested = false

    func addSource(_ view: UIView) {
        sources.removeAll { $0.view == nil }
        if !sources.contains(where: { $0.view === view }) { sources.append(WeakPipSource(view)) }
        refreshSource()
    }

    func removeSource(_ view: UIView) {
        sources.removeAll { $0.view == nil || $0.view === view }
        refreshSource()
    }

    private func refreshSource() {
        guard let content, let source = sources.reversed().compactMap({ $0.view }).first(where: { $0.window != nil && !$0.bounds.isEmpty }) else { return }
        if controller == nil {
            let pip = AVPictureInPictureController(contentSource: .init(activeVideoCallSourceView: source, contentViewController: content))
            pip.delegate = self
            pip.canStartPictureInPictureAutomaticallyFromInline = videoCallAvailable
            controller = pip
            configuredSource = source
            possibleObservation = pip.observe(\.isPictureInPicturePossible, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    if UIApplication.shared.applicationState == .background { self?.startIfPossible() }
                }
            }
            return
        }
        guard let controller, !starting, !controller.isPictureInPictureActive, configuredSource !== source else { return }
        controller.contentSource = .init(activeVideoCallSourceView: source, contentViewController: content)
        configuredSource = source
    }

    func startIfPossible() {
        guard videoCallAvailable, UIApplication.shared.applicationState == .background else { return }
        refreshSource()
        guard let controller, controller.isPictureInPicturePossible,
              !starting, !controller.isPictureInPictureActive, !controller.isPictureInPictureSuspended else { return }
        starting = true
        stopRequested = false
        controller.startPictureInPicture()
    }

    func returnToApplication() {
        guard UIApplication.shared.applicationState == .active else { return }
        if starting || controller?.isPictureInPictureActive == true {
            stopRequested = true
            controller?.stopPictureInPicture()
        }
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
            let avatar = UIHostingController(rootView: GeometryReader { geometry in
                CallPeerAvatar(userId: call.remoteUserId, name: call.remoteDisplayName,
                    size: min(96, min(geometry.size.width, geometry.size.height) * 0.65))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }.environmentObject(chats).environmentObject(users))
            avatar.view.backgroundColor = .clear
            content.addChild(avatar)
            view.installAvatar(avatar.view)
            avatar.didMove(toParent: content)
            self.content = content
            view.onVideoSize = { [weak content] size in
                guard size.width > 0, size.height > 0 else { return }
                content?.preferredContentSize = CGSize(width: 300, height: 300 * size.height / size.width)
            }
            video = view
            content.view.layoutIfNeeded()
            owner.attachPictureInPictureRenderer(view.renderer)
        }
        video?.setPresentation(showVideo: showVideo)
        let becameAvailable = !videoCallAvailable
        videoCallAvailable = true
        refreshSource()
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
        if becameAvailable && UIApplication.shared.applicationState == .background { startIfPossible() }
    }

    func stop() {
        videoCallAvailable = false
        isSystemPresented = false
        starting = false
        stopRequested = false
        possibleObservation = nil
        controller?.canStartPictureInPictureAutomaticallyFromInline = false
        controller?.stopPictureInPicture()
        controller?.contentSource = nil
        controller?.delegate = nil
        controller = nil
        configuredSource = nil
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
        restoreRequest += 1
        // Allow SwiftUI to present the existing call before the restore animation.
        DispatchQueue.main.async { [weak self, weak controller] in
            guard let self, let controller, controller === self.controller,
                  self.callID == expectedCallID else { completionHandler(false); return }
            completionHandler(true)
        }
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === self.controller else { return }
        // Native automatic start may arrive while applicationState is still
        // active. Only an explicit subsequent return cancels this transition.
        if !starting { stopRequested = false }
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
        starting = false
        stopRequested = false
        isSystemPresented = false
        video?.renderer.setRunning(false)
        let failure = error as NSError
        // Only platform error identifiers: no peer names, call IDs or media.
        logger.error("Video PiP failed: domain=\(failure.domain, privacy: .public) code=\(failure.code)")
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === self.controller else { return }
        starting = false
        stopRequested = false
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
    func makeUIView(context: Context) -> UIView {
        let view = PipSourceView()
        view.isUserInteractionEnabled = false
        view.onReady = { [weak pip, weak view] in
            guard let view, view.window != nil, !view.bounds.isEmpty else { return }
            pip?.addSource(view)
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
    var onReady: (() -> Void)?
    override func didMoveToWindow() { super.didMoveToWindow(); onReady?() }
    override func layoutSubviews() { super.layoutSubviews(); onReady?() }
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
            let sideways = rotation == ._90 || rotation == ._270
            let oriented = sideways ? CGSize(width: size.height, height: size.width) : size
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
    let displayLayer: AVSampleBufferDisplayLayer
    init(displayLayer: AVSampleBufferDisplayLayer) {
        self.displayLayer = displayLayer
        super.init()
    }
    var onVideoGeometry: ((CGSize, RTCVideoRotation) -> Void)?
    private let lock = NSLock()
    private var busy = false
    private var valid = true
    private var visible = false
    private var running = false
    private var primed = false
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
        lock.lock()
        guard CallPipFramePolicy.acceptsFrame(valid: valid, running: running, primed: primed, visible: visible, busy: busy) else { lock.unlock(); return }
        busy = true
        let expected = generation
        lock.unlock()
        queue.async { [weak self] in
            guard let self else { return }
            let buffer = Self.pixelBuffer(frame.buffer)
            DispatchQueue.main.async {
                self.lock.lock()
                let current = self.valid && (self.running || !self.primed) && self.visible && self.generation == expected
                self.busy = false
                self.lock.unlock()
                guard current, let buffer else { return }
                self.onVideoGeometry?(CGSize(width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)), frame.rotation)
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
