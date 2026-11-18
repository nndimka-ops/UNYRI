import Accelerate
import AVFoundation
import CoreVideo
import Foundation
import LiveKit

/// Mirrors NV12 camera buffers left-right in display space (shared by rooms and
/// 1:1 calls). A portrait frame arrives rotated by 90/270 degrees, where a
/// left-right mirror is a top-bottom flip of the buffer.
final class UnyriPixelBufferMirror: @unchecked Sendable {
    private let lock = NSLock()
    private var pool: CVPixelBufferPool?
    private var poolKey: (width: Int, height: Int, format: OSType)?

    func mirrored(_ source: CVPixelBuffer, quarterTurn: Bool) -> CVPixelBuffer? {
        let format = CVPixelBufferGetPixelFormatType(source)
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
                || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
              let target = makeBuffer(like: source, format: format) else { return nil }
        CVPixelBufferLockBaseAddress(source, .readOnly)
        CVPixelBufferLockBaseAddress(target, [])
        defer {
            CVPixelBufferUnlockBaseAddress(target, [])
            CVPixelBufferUnlockBaseAddress(source, .readOnly)
        }
        // Plane 0: Y (1 byte per pixel). Plane 1: interleaved CbCr (2 bytes per chroma pair).
        for plane in 0..<2 {
            guard let src = CVPixelBufferGetBaseAddressOfPlane(source, plane),
                  let dst = CVPixelBufferGetBaseAddressOfPlane(target, plane) else { return nil }
            let width = CVPixelBufferGetWidthOfPlane(source, plane)
            let height = CVPixelBufferGetHeightOfPlane(source, plane)
            var input = vImage_Buffer(data: src, height: vImagePixelCount(height), width: vImagePixelCount(width),
                                      rowBytes: CVPixelBufferGetBytesPerRowOfPlane(source, plane))
            var output = vImage_Buffer(data: dst, height: vImagePixelCount(height), width: vImagePixelCount(width),
                                       rowBytes: CVPixelBufferGetBytesPerRowOfPlane(target, plane))
            let flags = vImage_Flags(kvImageNoFlags)
            let status: vImage_Error
            if plane == 0 {
                status = quarterTurn ? vImageVerticalReflect_Planar8(&input, &output, flags)
                                     : vImageHorizontalReflect_Planar8(&input, &output, flags)
            } else {
                // A CbCr pair moves as one 16-bit unit, so the pair order is preserved.
                status = quarterTurn ? vImageVerticalReflect_Planar16U(&input, &output, flags)
                                     : vImageHorizontalReflect_Planar16U(&input, &output, flags)
            }
            guard status == kvImageNoError else { return nil }
        }
        return target
    }

    private func makeBuffer(like source: CVPixelBuffer, format: OSType) -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(source), height = CVPixelBufferGetHeight(source)
        lock.lock(); defer { lock.unlock() }
        if poolKey.map({ $0.width != width || $0.height != height || $0.format != format }) ?? true {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: format,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
            ]
            var created: CVPixelBufferPool?
            CVPixelBufferPoolCreate(kCFAllocatorDefault, [kCVPixelBufferPoolMinimumBufferCountKey as String: 3] as CFDictionary,
                                    attributes as CFDictionary, &created)
            pool = created; poolKey = (width, height, format)
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer) == kCVReturnSuccess else { return nil }
        return buffer
    }
}

/// Rooms show everyone the way they see themselves in a mirror: front-camera
/// frames are mirrored on the sender, before encoding, so every viewer on every
/// platform gets the sender's own self-view. The back camera is sent as is.
final class ConferenceFrontCameraMirror: NSObject, VideoProcessor, VideoCapturerDelegate, @unchecked Sendable {
    private weak var capturer: CameraCapturer?
    private let mirror = UnyriPixelBufferMirror()
    private let orientation: CallOrientationStore
    private let orientationOwner = UUID()
    private var captureObservers = [NSObjectProtocol]()

    @MainActor
    init(capturer: CameraCapturer, orientation: CallOrientationStore) {
        self.capturer = capturer; self.orientation = orientation; super.init()
        capturer.add(delegate: self)
        for name in [AVCaptureSession.didStartRunningNotification, AVCaptureSession.didStopRunningNotification,
                     AVCaptureSession.wasInterruptedNotification, AVCaptureSession.interruptionEndedNotification] {
            captureObservers.append(NotificationCenter.default.addObserver(forName: name,
                object: capturer.captureSession, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.synchronizeCapture() }
            })
        }
        synchronizeCapture()
    }

    deinit {
        captureObservers.forEach { NotificationCenter.default.removeObserver($0) }
        let owner = orientationOwner
        Task { @MainActor in CallDeviceOrientation.shared.capturing(false, owner: owner) }
    }

    func capturer(_ capturer: VideoCapturer, didUpdate state: VideoCapturer.CapturerState) {
        // SDK state and session notifications are infrequent lifecycle events.
        // Re-read current state on main, so queued start/stop events cannot
        // resurrect a sensor lease after capture has already stopped.
        Task { @MainActor [weak self] in self?.synchronizeCapture() }
    }

    @MainActor private func synchronizeCapture() {
        let running = capturer.map { $0.captureState == .started && $0.captureSession.isRunning && !$0.captureSession.isInterrupted } ?? false
        CallDeviceOrientation.shared.capturing(running, owner: orientationOwner)
    }

    @objc func process(frame: VideoFrame) -> VideoFrame? {
        let front = capturer?.position == .front
        let degrees = CallOrientationPolicy.frameRotation(orientation.read(), front: front)
        let rotation: VideoRotation
        switch degrees {
        case 0: rotation = ._0
        case 180: rotation = ._180
        case 270: rotation = ._270
        default: rotation = ._90
        }
        if front, let source = (frame.buffer as? CVPixelVideoBuffer)?.pixelBuffer,
           let target = mirror.mirrored(source, quarterTurn: degrees == 90 || degrees == 270) {
            return VideoFrame(dimensions: frame.dimensions, rotation: rotation, timeStampNs: frame.timeStampNs,
                              rtpTimestamp: frame.rtpTimestamp, buffer: CVPixelVideoBuffer(pixelBuffer: target))
        }
        return VideoFrame(dimensions: frame.dimensions, rotation: rotation, timeStampNs: frame.timeStampNs,
                          rtpTimestamp: frame.rtpTimestamp, buffer: frame.buffer)
    }
}
