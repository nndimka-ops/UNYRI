import AVFoundation
import CallKit
import Combine
import CoreMedia
import CoreVideo
import Foundation
import os
import QuartzCore
import SwiftUI
import UIKit
import VideoToolbox

/// Round video messages ("video notes"): a square H.264/AAC mp4 recorded from the
/// front camera, at most `VideoNoteLimits.maxSeconds` long.
///
/// The capture session exists only while a note is being recorded and is stopped right
/// after. Frames go straight into an `AVAssetWriter` (aspect-fill to a square), so the
/// file is ready the moment recording stops: no export step, and the camera can be
/// flipped mid-recording. Video frames are delivered upright and, for the front camera,
/// mirrored, which is exactly what the preview shows.
enum VideoNoteLimits {
    static let maxSeconds: Double = {
        #if targetEnvironment(simulator)
        // Walkthroughs only: reach the limit and the final seconds quickly.
        if let raw = ProcessInfo.processInfo.environment["UNYRI_VIDEO_NOTE_MAX_SECONDS"], let value = Int(raw), value >= 8 {
            return Double(value)
        }
        #endif
        return 60
    }()
    /// Shorter recordings are discarded, like an accidental voice hold.
    static let minSeconds: Double = 1
    static let side = 480
    static let videoBitRate = 1_200_000
    static let audioBitRate = 64_000
    /// The poster moment: frame 0 comes before auto-exposure settles, 0.5 s is still the
    /// opening of the note. The recorder grab, the upload thumbnail and the file decode
    /// all use it, so every side shows the same picture.
    static let posterSeconds: Double = 0.5
    static func posterTime(durationSeconds: Double) -> CMTime {
        CMTime(seconds: max(0, min(posterSeconds, durationSeconds / 2)), preferredTimescale: 600)
    }
}

struct VideoNoteRecording: Sendable {
    let fileURL: URL
    let durationMs: Int64
    /// One square frame at `VideoNoteLimits.posterSeconds`, taken from the live buffers.
    var poster: UIImage? = nil
}

/// The poster frame from a capture buffer.
enum VideoNotePosterFrame {
    /// Centre square of an upright frame, copied into owned memory so the poster never
    /// pins a buffer of the capture pool. The pixels are already oriented and mirrored
    /// by the connection, exactly like the file.
    static func square(_ pixels: CVPixelBuffer) -> CGImage? {
        var converted: CGImage?
        guard VTCreateCGImageFromCVPixelBuffer(pixels, options: nil, imageOut: &converted) == noErr,
              let frame = converted else { return nil }
        let crop = min(frame.width, frame.height)
        let side = min(crop, VideoNoteLimits.side)
        guard side > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        context.interpolationQuality = .medium
        let scale = CGFloat(side) / CGFloat(crop)
        let width = CGFloat(frame.width) * scale
        let height = CGFloat(frame.height) * scale
        context.draw(frame, in: CGRect(x: (CGFloat(side) - width) / 2, y: (CGFloat(side) - height) / 2, width: width, height: height))
        return context.makeImage()
    }
}

enum VideoNoteAccess: Sendable {
    case granted, cameraDenied, microphoneDenied
}

enum VideoNoteRecordError: Error, LocalizedError {
    case cameraUnavailable
    case couldNotStart
    case callInProgress

    var errorDescription: String? {
        switch self {
        case .cameraUnavailable: return UnyriL10n.string("Camera is unavailable.")
        case .couldNotStart: return UnyriL10n.string("Could not start recording.")
        case .callInProgress: return UnyriL10n.string("Finish the call before recording.")
        }
    }
}

/// Interface orientation when recording starts (named like `UIInterfaceOrientation`).
enum VideoNoteOrientation: Sendable {
    case portrait, portraitUpsideDown, landscapeLeft, landscapeRight

    var capture: AVCaptureVideoOrientation {
        switch self {
        case .portrait: return .portrait
        case .portraitUpsideDown: return .portraitUpsideDown
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }
}

/// What the recording bar in the composer needs from a recorder (voice or video note).
@MainActor
protocol ChatRecordingSource: ObservableObject, Sendable {
    var isRecording: Bool { get }
    var recordedSeconds: TimeInterval { get }
    func level() -> Double
}

extension VoiceRecorder: ChatRecordingSource {}

// MARK: - Recorder

@MainActor
final class VideoNoteRecorder: ObservableObject, ChatRecordingSource {
    enum Phase { case idle, starting, recording, stopped }
    enum Ending: Equatable { case sent, discarded }

    /// `starting`: the camera is warming up. `recording`: frames are being written.
    /// `stopped`: capture was interrupted; what was recorded waits for Send or Discard.
    @Published private(set) var phase = Phase.idle
    /// How the last take ended, for the preview's exit. It changes in the same call as
    /// `phase`, so it is deliberately not @Published.
    private(set) var ending = Ending.discarded
    @Published private(set) var frontCamera = true
    /// The session delivers frames (the preview connection exists from here on).
    @Published private(set) var previewLive = false
    /// Set when the camera or microphone permission is denied: drives the Settings alert.
    @Published var accessProblem: VideoNoteAccess?

    /// Created on the main thread before the session is configured on its own queue
    /// (the order Apple's camera samples use); the preview view only hosts it.
    private(set) var previewLayer: AVCaptureVideoPreviewLayer?
    private(set) var orientation = VideoNoteOrientation.portrait

    /// A call, Siri, the app leaving the foreground: capture stopped, the take is kept.
    var onInterrupted: (() -> Void)?
    var onLimitReached: (() -> Void)?
    var onFailed: (() -> Void)?

    private var capture: (any VideoNoteCapturing)?
    private var token = 0
    private var startedAt: TimeInterval?
    private var frozenSeconds: TimeInterval = 0
    private var finishTask: Task<VideoNoteRecording?, Never>?
    /// The capture session stopping. It can outlast `finishTask`: the file is handed over
    /// first, and the audio session and the claim are released only after this.
    private var stopTask: Task<Void, Never>?
    private var captureClaim: NonCallCaptureOwnership.Claim?
    private var ownsAudioSession = false
    private var observers: [NSObjectProtocol] = []

    var isActive: Bool { phase != .idle }
    var isRecording: Bool { phase == .recording }
    var recordedSeconds: TimeInterval {
        guard phase == .recording, let startedAt else { return frozenSeconds }
        return min(VideoNoteLimits.maxSeconds, max(0, ProcessInfo.processInfo.systemUptime - startedAt))
    }
    func level() -> Double {
        guard phase == .recording else { return 0 }
        return capture?.level() ?? 0
    }

    /// Takes are written to tmp under this prefix and staged elsewhere on Send, so a
    /// file left here belongs to a take that was interrupted with the process.
    nonisolated static let takePrefix = "video-note-"

    /// At launch nothing is recording yet: every take still in tmp is stale.
    nonisolated static func removeStaleTakes() {
        DispatchQueue.global(qos: .utility).async {
            let manager = FileManager.default
            guard let files = try? manager.contentsOfDirectory(at: manager.temporaryDirectory, includingPropertiesForKeys: nil) else { return }
            for file in files where file.lastPathComponent.hasPrefix(takePrefix) && file.pathExtension == "mp4" {
                try? manager.removeItem(at: file)
            }
        }
    }

    /// AVAudioSession reports a raw OSStatus (for example while a cellular call holds
    /// the microphone); the person sees the recorder's own message instead.
    private static func audioSessionFailure(_ error: Error) -> VideoNoteRecordError {
        let code = (error as NSError).code
        let busy = code == AVAudioSession.ErrorCode.insufficientPriority.rawValue ||
            code == AVAudioSession.ErrorCode.cannotInterruptOthers.rawValue ||
            NonCallCaptureOwnership.callsBusy() ||
            CXCallObserver().calls.contains { !$0.hasEnded }
        return busy ? .callInProgress : .couldNotStart
    }

    /// UI walkthroughs on a Mac without a camera: `UNYRI_FAKE_CAMERA=1` records a short
    /// synthetic square video instead. Always false on a device.
    static var usesSyntheticCamera: Bool {
        #if targetEnvironment(simulator)
        return ProcessInfo.processInfo.environment["UNYRI_FAKE_CAMERA"] == "1"
        #else
        return false
        #endif
    }

    /// Camera first, then microphone. iOS shows each prompt once; afterwards this
    /// returns at once. Called only when the user holds the button, never at launch.
    static func requestAccess() async -> VideoNoteAccess {
        if usesSyntheticCamera { return .granted }
        guard await AVCaptureDevice.requestAccess(for: .video) else { return .cameraDenied }
        guard await AVCaptureDevice.requestAccess(for: .audio) else { return .microphoneDenied }
        return .granted
    }

    /// Waits for a previous take to finish closing the camera (a quick cancel-and-hold-again).
    func settle() async {
        if let finishTask { _ = await finishTask.value }
        if let stopTask { await stopTask.value }
    }

    /// Opens the camera and starts recording as soon as the first frame arrives.
    /// The caller has already asked for camera and microphone access.
    func start(captureClaim suppliedClaim: NonCallCaptureOwnership.Claim? = nil) throws {
        guard phase == .idle, capture == nil else { throw VideoNoteRecordError.couldNotStart }
        guard let generation = NonCallCaptureOwnership.currentGeneration() else { throw VideoNoteRecordError.callInProgress }
        let candidate = suppliedClaim ?? NonCallCaptureOwnership.acquire(.camera, generation: generation, stop: { claim in
            NonCallCaptureOwnership.release(claim)
        })
        guard let claim = candidate, NonCallCaptureOwnership.isCurrent(claim),
              NonCallCaptureOwnership.replaceStop(claim, stop: { [weak self] claim in
                  // A call takes the camera and microphone: stop now, release the claim
                  // once the capture session has really let go of them.
                  if let self, self.captureClaim == claim { self.interrupt() } else { NonCallCaptureOwnership.release(claim) }
              }) else {
            if suppliedClaim == nil, let candidate { NonCallCaptureOwnership.release(candidate) }
            throw VideoNoteRecordError.callInProgress
        }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(Self.takePrefix + "\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
        var made: (any VideoNoteCapturing)?
        #if targetEnvironment(simulator)
        if Self.usesSyntheticCamera { made = VideoNoteSyntheticCapture(url: url) }
        #endif
        if made == nil {
            guard VideoNoteCameraCapture.camera(front: true) != nil || VideoNoteCameraCapture.camera(front: false) != nil else {
                if suppliedClaim == nil { NonCallCaptureOwnership.release(claim) }
                throw VideoNoteRecordError.cameraUnavailable
            }
            do { try activateAudioSession() } catch {
                if suppliedClaim == nil { NonCallCaptureOwnership.release(claim) }
                throw Self.audioSessionFailure(error)
            }
            made = VideoNoteCameraCapture(url: url)
        }
        guard let capture = made else { throw VideoNoteRecordError.couldNotStart }

        captureClaim = claim
        self.capture = capture
        if let session = capture.session {
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            previewLayer = layer
        } else {
            previewLayer = nil
        }
        finishTask = nil
        token &+= 1
        let current = token
        startedAt = nil
        frozenSeconds = 0
        ending = .discarded
        frontCamera = true
        previewLive = false
        orientation = Self.interfaceOrientation()
        observeInterruptions(capture.session)
        phase = .starting
        capture.start(front: true, orientation: orientation) { [weak self] event in
            Task { @MainActor in self?.handle(event, token: current) }
        }
    }

    func flipCamera() {
        guard phase == .starting || phase == .recording else { return }
        capture?.flip()
    }

    /// Stops recording and returns the file, or nil when nothing usable was recorded.
    /// The interface goes back to idle at once; the camera closes in the background.
    func finish() async -> VideoNoteRecording? {
        guard phase != .idle || finishTask != nil else { return nil }
        freeze()
        // A take without frames froze at 0 s: it leaves like a discard, as the hint says.
        if phase != .idle { ending = frozenSeconds >= VideoNoteLimits.minSeconds ? .sent : .discarded }
        phase = .idle
        let current = token
        let result = await beginFinish().value
        if current == token { finishTask = nil; clearHandlers() }
        return result
    }

    /// Stops capture but keeps what was recorded for a later `finish()` or `cancel()`.
    func interrupt() {
        guard phase == .starting || phase == .recording else { return }
        freeze()
        phase = .stopped
        _ = beginFinish()
        let handler = onInterrupted
        handler?()
    }

    /// Stops recording and deletes the take.
    func cancel() {
        guard phase != .idle || finishTask != nil else { return }
        freeze()
        frozenSeconds = 0
        ending = .discarded
        phase = .idle
        clearHandlers()
        let current = token
        let task = beginFinish()
        Task { @MainActor [weak self] in
            if let result = await task.value { try? FileManager.default.removeItem(at: result.fileURL) }
            guard let self, current == self.token else { return }
            self.finishTask = nil
        }
    }

    // MARK: Private

    private func handle(_ event: VideoNoteCaptureEvent, token: Int) {
        guard token == self.token, capture != nil else { return }
        switch event {
        case .running:
            orientPreview()
            if !previewLive { previewLive = true }
        case .started:
            guard phase == .starting else { return }
            startedAt = ProcessInfo.processInfo.systemUptime
            phase = .recording
        case .limitReached:
            guard phase == .recording else { return }
            if let handler = onLimitReached { handler() } else { interrupt() }
        case .failed:
            guard phase == .starting || phase == .recording else { return }
            let handler = onFailed
            cancel()
            handler?()
        case .camera(let front):
            // Replacing the camera recreates the preview connection.
            orientPreview()
            if frontCamera != front { frontCamera = front }
        }
    }

    /// The preview follows the interface orientation the recording started in.
    /// (Its connection may be changed from the main thread while the session runs.)
    private func orientPreview() {
        guard let connection = previewLayer?.connection, connection.isVideoOrientationSupported else { return }
        connection.videoOrientation = orientation.capture
    }

    private func freeze() {
        if let startedAt {
            frozenSeconds = min(VideoNoteLimits.maxSeconds, max(0, ProcessInfo.processInfo.systemUptime - startedAt))
        }
        startedAt = nil
    }

    private func clearHandlers() {
        onInterrupted = nil
        onLimitReached = nil
        onFailed = nil
    }

    private func beginFinish() -> Task<VideoNoteRecording?, Never> {
        if let finishTask { return finishTask }
        let capture = self.capture
        let current = token
        // The file and the session stop in parallel: stopRunning blocks for a few hundred
        // milliseconds and the file does not need it, so a sent note reaches the outbox
        // sooner. Only the camera, microphone and claim wait for the stop.
        let task = Task { @MainActor () -> VideoNoteRecording? in
            await withCheckedContinuation { (continuation: CheckedContinuation<VideoNoteRecording?, Never>) in
                guard let capture else { continuation.resume(returning: nil); return }
                capture.finish { continuation.resume(returning: $0) }
            }
        }
        stopTask = Task { @MainActor [weak self] in
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                guard let capture else { continuation.resume(); return }
                capture.stop { continuation.resume() }
            }
            self?.captureStopped(token: current)
        }
        finishTask = task
        return task
    }

    /// The capture session has stopped: only now give up the audio session and the claim.
    private func captureStopped(token: Int) {
        guard token == self.token else { return }
        stopTask = nil
        removeObservers()
        capture = nil
        // The session stays on the layer: a stopped preview keeps its last frame, and the
        // view hosting it clears the session when it is dismantled.
        previewLayer = nil
        if previewLive { previewLive = false }
        releaseAudioSession()
        if let captureClaim { NonCallCaptureOwnership.release(captureClaim) }
        captureClaim = nil
    }

    /// The same audio session rules as `VoiceRecorder`: this object configures the
    /// session (the capture session does not), and haptics stay audible while recording.
    private func activateAudioSession() throws {
        // On the shared audio queue, after any deactivation still queued by a player or
        // recorder that just finished, so that deactivation can never land on this take.
        try UnyriPlaybackAudioSession.synchronousClaim {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [])
            try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
            try session.setActive(true)
        }
        ownsAudioSession = true
    }

    private func releaseAudioSession() {
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        // A call may have replaced the recorder's route; another media owner is checked on
        // the audio queue. Deactivating is an audio-server round trip of up to several
        // hundred milliseconds: it must never hold the main thread after a note is sent.
        guard !NonCallCaptureOwnership.callsBusy() else { return }
        UnyriPlaybackAudioSession.deactivate(ifStill: .playAndRecord, mode: .default, label: "videoNoteRecorder", reason: 4)
    }

    private func observeInterruptions(_ session: AVCaptureSession?) {
        removeObservers()
        let center = NotificationCenter.default
        if let session {
            // Raw names: the Swift spellings of these constants changed between SDKs.
            for name in ["AVCaptureSessionWasInterruptedNotification", "AVCaptureSessionRuntimeErrorNotification"] {
                observers.append(center.addObserver(forName: Notification.Name(name), object: session, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.interrupt() }
                })
            }
        }
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            MainActor.assumeIsolated { self?.interrupt() }
        })
    }

    private func removeObservers() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    private static func interfaceOrientation() -> VideoNoteOrientation {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first
        switch scene?.interfaceOrientation ?? .portrait {
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }
}

// MARK: - Capture core

/// Reported on the capture queue; the recorder hops to the main actor.
private enum VideoNoteCaptureEvent: Sendable {
    case running
    case started
    case limitReached
    case failed
    case camera(front: Bool)
}

private protocol VideoNoteCapturing: AnyObject, Sendable {
    var session: AVCaptureSession? { get }
    func start(front: Bool, orientation: VideoNoteOrientation, events: @escaping @Sendable (VideoNoteCaptureEvent) -> Void)
    func flip()
    /// Idempotent: stops writing, finalizes the file and reports it (nil if unusable).
    /// Does not wait for capture to stop; frames that still arrive are dropped.
    func finish(_ completion: @escaping @Sendable (VideoNoteRecording?) -> Void)
    /// Idempotent: stops capture and reports once the camera and microphone are released.
    func stop(_ completion: @escaping @Sendable () -> Void)
    func level() -> Double
}

/// AVCaptureSession -> AVAssetWriter.
///
/// Two serial queues: `sessionQueue` configures, starts, flips and stops the session
/// (`startRunning`/`stopRunning` block); `dataQueue` receives the sample buffers and owns
/// the writer. Stopping the session therefore never waits behind a sample callback.
private final class VideoNoteCameraCapture: NSObject, VideoNoteCapturing,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate, @unchecked Sendable {
    private let captureSession = AVCaptureSession()
    var session: AVCaptureSession? { captureSession }
    private let sessionQueue = DispatchQueue(label: "unyri.video-note.session", qos: .userInitiated)
    private let dataQueue = DispatchQueue(label: "unyri.video-note.data", qos: .userInitiated)
    private let url: URL
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()

    // Session queue only.
    private var cameraInput: AVCaptureDeviceInput?
    private var orientation = VideoNoteOrientation.portrait
    private var stopped = false

    // Data queue only.
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var accepting = false
    private var writingStarted = false
    private var firstTime = CMTime.invalid
    private var lastVideoTime = CMTime.invalid
    private var poster: CGImage?
    private var finishing = false
    private var finished = false
    private var result: VideoNoteRecording?
    private var waiters: [@Sendable (VideoNoteRecording?) -> Void] = []

    // Any thread, under `lock`.
    private let lock = NSLock()
    private var events: (@Sendable (VideoNoteCaptureEvent) -> Void)?
    private var currentLevel: Double = 0

    init(url: URL) {
        self.url = url
        super.init()
    }

    static func camera(front: Bool) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
    }

    func level() -> Double {
        lock.lock(); defer { lock.unlock() }
        return currentLevel
    }

    private func emit(_ event: VideoNoteCaptureEvent) {
        lock.lock()
        let handler = events
        lock.unlock()
        handler?(event)
    }

    func start(front: Bool, orientation: VideoNoteOrientation, events: @escaping @Sendable (VideoNoteCaptureEvent) -> Void) {
        lock.lock()
        self.events = events
        lock.unlock()
        sessionQueue.async { [self] in
            guard !stopped else { return }
            self.orientation = orientation
            do {
                let hasAudio = try configureSession(front: front)
                // The writer is ready before the first buffer can arrive.
                try dataQueue.sync { try prepareWriter(audio: hasAudio) }
                captureSession.startRunning()
                guard captureSession.isRunning else { throw VideoNoteRecordError.couldNotStart }
                emit(.running)
            } catch {
                dataQueue.sync { accepting = false }
                emit(.failed)
            }
        }
    }

    func flip() {
        sessionQueue.async { [self] in
            guard !stopped, captureSession.isRunning, let current = cameraInput else { return }
            let front = current.device.position != .front
            guard let device = Self.camera(front: front), let input = try? AVCaptureDeviceInput(device: device) else { return }
            captureSession.beginConfiguration()
            captureSession.removeInput(current)
            if captureSession.canAddInput(input) {
                captureSession.addInput(input)
                cameraInput = input
            } else if captureSession.canAddInput(current) {
                captureSession.addInput(current)
            }
            // Replacing the input recreates the output connection.
            orientOutput()
            captureSession.commitConfiguration()
            emit(.camera(front: cameraInput?.device.position == .front))
        }
    }

    func finish(_ completion: @escaping @Sendable (VideoNoteRecording?) -> Void) {
        // Not behind stopRunning: once finalize starts, `accepting` is false and late
        // buffers are dropped, so the note ends where the finger lifted.
        dataQueue.async { [self] in finalize(completion) }
    }

    func stop(_ completion: @escaping @Sendable () -> Void) {
        sessionQueue.async { [self] in
            if !stopped {
                stopped = true
                if captureSession.isRunning { captureSession.stopRunning() }
                videoOutput.setSampleBufferDelegate(nil, queue: nil)
                audioOutput.setSampleBufferDelegate(nil, queue: nil)
            }
            completion()
        }
    }

    // MARK: Session queue

    private func configureSession(front: Bool) throws -> Bool {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }
        // The recorder owns the audio session (see activateAudioSession).
        captureSession.automaticallyConfiguresApplicationAudioSession = false
        if captureSession.canSetSessionPreset(.vga640x480) { captureSession.sessionPreset = .vga640x480 }
        else if captureSession.canSetSessionPreset(.medium) { captureSession.sessionPreset = .medium }

        guard let device = Self.camera(front: front) ?? Self.camera(front: !front) else { throw VideoNoteRecordError.cameraUnavailable }
        let camera = try AVCaptureDeviceInput(device: device)
        guard captureSession.canAddInput(camera) else { throw VideoNoteRecordError.couldNotStart }
        captureSession.addInput(camera)
        cameraInput = camera

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: dataQueue)
        guard captureSession.canAddOutput(videoOutput) else { throw VideoNoteRecordError.couldNotStart }
        captureSession.addOutput(videoOutput)

        var hasAudio = false
        if let microphone = AVCaptureDevice.default(for: .audio),
           let input = try? AVCaptureDeviceInput(device: microphone), captureSession.canAddInput(input) {
            captureSession.addInput(input)
            audioOutput.setSampleBufferDelegate(self, queue: dataQueue)
            if captureSession.canAddOutput(audioOutput) {
                captureSession.addOutput(audioOutput)
                hasAudio = true
            }
        }
        orientOutput()
        return hasAudio
    }

    /// Frames arrive upright for the interface and mirrored for the front camera, so the
    /// written video looks exactly like the preview.
    private func orientOutput() {
        guard let connection = videoOutput.connection(with: .video) else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = orientation.capture }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = cameraInput?.device.position == .front
        }
    }

    // MARK: Data queue

    private func prepareWriter(audio: Bool) throws {
        // finish() no longer waits for the session queue: a release during camera setup
        // has already settled the take, and a writer opened now would never be closed.
        guard !finishing else { throw VideoNoteRecordError.couldNotStart }
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        let compression: [String: Any] = [
            AVVideoAverageBitRateKey: VideoNoteLimits.videoBitRate,
            AVVideoMaxKeyFrameIntervalKey: 60,
            AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            AVVideoExpectedSourceFrameRateKey: 30,
            AVVideoAllowFrameReorderingKey: false,
        ]
        let plainSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: VideoNoteLimits.side,
            AVVideoHeightKey: VideoNoteLimits.side,
            // The camera delivers 4:3; the writer crops the centre to a square.
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
        ]
        var videoSettings = plainSettings
        videoSettings[AVVideoCompressionPropertiesKey] = compression
        // An encoder that refuses the tuning still records with its defaults.
        if !writer.canApply(outputSettings: videoSettings, forMediaType: .video) { videoSettings = plainSettings }
        guard writer.canApply(outputSettings: videoSettings, forMediaType: .video) else { throw VideoNoteRecordError.couldNotStart }
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = true
        guard writer.canAdd(videoInput) else { throw VideoNoteRecordError.couldNotStart }
        writer.add(videoInput)

        var audioInput: AVAssetWriterInput?
        if audio {
            let audioSettings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: VideoNoteLimits.audioBitRate,
            ]
            if writer.canApply(outputSettings: audioSettings, forMediaType: .audio) {
                let input = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
                input.expectsMediaDataInRealTime = true
                if writer.canAdd(input) {
                    writer.add(input)
                    audioInput = input
                }
            }
        }
        guard writer.startWriting() else { throw writer.error ?? VideoNoteRecordError.couldNotStart }
        self.writer = writer
        self.videoInput = videoInput
        self.audioInput = audioInput
        accepting = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard accepting, let writer, CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let time = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard time.isValid else { return }
        let isVideo = output === videoOutput
        if !isVideo, let channel = connection.audioChannels.first {
            // Speech sits around -30…-10 dB (the same mapping as the voice recorder).
            let value = min(1, max(0, (Double(channel.averagePowerLevel) + 50) / 50))
            lock.lock(); currentLevel = value; lock.unlock()
        }
        guard writer.status == .writing else {
            if writer.status == .failed { accepting = false; emit(.failed) }
            return
        }
        if !writingStarted {
            // Start on a picture: a note never opens with sound over a black frame.
            guard isVideo else { return }
            writer.startSession(atSourceTime: time)
            writingStarted = true
            firstTime = time
            emit(.started)
        }
        if isVideo {
            if CMTimeGetSeconds(CMTimeSubtract(time, firstTime)) >= VideoNoteLimits.maxSeconds {
                accepting = false
                emit(.limitReached)
                return
            }
            if let videoInput, videoInput.isReadyForMoreMediaData, videoInput.append(sampleBuffer) {
                lastVideoTime = time
                // One conversion per note, once exposure has had time to settle.
                if poster == nil, CMTimeGetSeconds(CMTimeSubtract(time, firstTime)) >= VideoNoteLimits.posterSeconds,
                   let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) {
                    poster = VideoNotePosterFrame.square(pixels)
                }
            }
        } else if let audioInput, audioInput.isReadyForMoreMediaData, CMTimeCompare(time, firstTime) >= 0 {
            _ = audioInput.append(sampleBuffer)
        }
    }

    /// Idempotent: the first caller finalizes the file, later callers get the same result.
    private func finalize(_ completion: @escaping @Sendable (VideoNoteRecording?) -> Void) {
        if finished { completion(result); return }
        waiters.append(completion)
        guard !finishing else { return }
        finishing = true
        accepting = false
        guard let writer, writer.status == .writing else { complete(nil); return }
        guard writingStarted, firstTime.isValid, lastVideoTime.isValid else {
            writer.cancelWriting()
            complete(nil)
            return
        }
        let end = CMTimeAdd(lastVideoTime, CMTime(value: 1, timescale: 30))
        let seconds = CMTimeGetSeconds(CMTimeSubtract(end, firstTime))
        videoInput?.markAsFinished()
        audioInput?.markAsFinished()
        // Sound recorded after the last picture is trimmed.
        writer.endSession(atSourceTime: end)
        writer.finishWriting { [self] in
            dataQueue.async { [self] in
                let done = self.writer?.status == .completed && seconds.isFinite && seconds > 0
                complete(done ? VideoNoteRecording(fileURL: url, durationMs: Int64(seconds * 1000),
                                                   poster: poster.map { UIImage(cgImage: $0) }) : nil)
            }
        }
    }

    private func complete(_ value: VideoNoteRecording?) {
        if value == nil { try? FileManager.default.removeItem(at: url) }
        result = value
        finished = true
        writer = nil
        videoInput = nil
        audioInput = nil
        poster = nil
        lock.lock()
        events = nil
        lock.unlock()
        let pending = waiters
        waiters = []
        for waiter in pending { waiter(value) }
    }
}

#if targetEnvironment(simulator)
/// Simulator-only stand-in for UI tests (UNYRI_FAKE_CAMERA=1): pretends to record and
/// writes moving colour frames of the recorded length on stop. Never compiled for devices.
private final class VideoNoteSyntheticCapture: VideoNoteCapturing, @unchecked Sendable {
    var session: AVCaptureSession? { nil }
    private let queue = DispatchQueue(label: "unyri.video-note.synthetic")
    private let url: URL
    private var startedAt: Date?
    private var front = true
    private var events: (@Sendable (VideoNoteCaptureEvent) -> Void)?
    private var limit: DispatchWorkItem?
    private var finished = false
    private var result: VideoNoteRecording?

    init(url: URL) { self.url = url }

    func level() -> Double { 0.45 + 0.3 * sin(Date().timeIntervalSince1970 * 7) }

    func start(front: Bool, orientation: VideoNoteOrientation, events: @escaping @Sendable (VideoNoteCaptureEvent) -> Void) {
        queue.async { [self] in
            guard !finished else { return }
            self.events = events
            self.front = front
            startedAt = Date()
            events(.running)
            events(.started)
            let item = DispatchWorkItem { [weak self] in self?.events?(.limitReached) }
            limit = item
            queue.asyncAfter(deadline: .now() + VideoNoteLimits.maxSeconds, execute: item)
        }
    }

    func flip() {
        queue.async { [self] in
            guard !finished else { return }
            front.toggle()
            events?(.camera(front: front))
        }
    }

    func finish(_ completion: @escaping @Sendable (VideoNoteRecording?) -> Void) {
        queue.async { [self] in
            if finished { completion(result); return }
            finished = true
            limit?.cancel()
            limit = nil
            events = nil
            let seconds = min(VideoNoteLimits.maxSeconds, startedAt.map { Date().timeIntervalSince($0) } ?? 0)
            result = seconds > 0 ? Self.write(url: url, seconds: seconds) : nil
            completion(result)
        }
    }

    func stop(_ completion: @escaping @Sendable () -> Void) {
        queue.async { completion() }
    }

    private static func write(url: URL, seconds: Double) -> VideoNoteRecording? {
        try? FileManager.default.removeItem(at: url)
        let side = VideoNoteLimits.side
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return nil }
        let settings: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: side, AVVideoHeightKey: side]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferWidthKey as String: side,
            kCVPixelBufferHeightKey as String: side,
        ]
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: attributes)
        guard writer.canAdd(input) else { return nil }
        writer.add(input)
        guard writer.startWriting() else { return nil }
        writer.startSession(atSourceTime: .zero)
        let fps: Int32 = 10
        let frames = max(1, Int((seconds * Double(fps)).rounded(.up)))
        // The same poster moment as the camera path (frame 5 at 10 fps).
        let posterIndex = min(frames - 1, Int((VideoNoteLimits.posterSeconds * Double(fps)).rounded()))
        var poster: CGImage?
        var written = 0
        for index in 0..<frames {
            var waited = 0
            while !input.isReadyForMoreMediaData, waited < 2000 {
                Thread.sleep(forTimeInterval: 0.002)
                waited += 1
            }
            guard input.isReadyForMoreMediaData, let pool = adaptor.pixelBufferPool else { break }
            var buffer: CVPixelBuffer?
            _ = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { break }
            paint(buffer, phase: Double(index) / Double(fps))
            if index == posterIndex { poster = VideoNotePosterFrame.square(buffer) }
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(index), timescale: fps)) else { break }
            written += 1
        }
        guard written > 0 else {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        input.markAsFinished()
        writer.endSession(atSourceTime: CMTime(value: CMTimeValue(written), timescale: fps))
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return VideoNoteRecording(fileURL: url, durationMs: Int64(Double(written) / Double(fps) * 1000),
                                  poster: poster.map { UIImage(cgImage: $0) })
    }

    private static func paint(_ buffer: CVPixelBuffer, phase: Double) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { return }
        let hue = CGFloat((phase / 6).truncatingRemainder(dividingBy: 1))
        context.setFillColor(UIColor(hue: hue, saturation: 0.5, brightness: 0.5, alpha: 1).cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let radius = CGFloat(width) * 0.12
        let x = CGFloat(width) * (0.5 + 0.3 * CGFloat(cos(phase * 2)))
        let y = CGFloat(height) * (0.5 + 0.3 * CGFloat(sin(phase * 2)))
        context.setFillColor(UIColor(white: 1, alpha: 0.9).cgColor)
        context.fillEllipse(in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
    }
}
#endif

// MARK: - Live preview

/// The round camera preview, its progress ring and the recording halo.
///
/// Everything moves on the render server: one progress animation per recording (stroke
/// and head), finite opacity and transform animations, and a 10 Hz display link that runs
/// only while recording and feeds the voice level into the glow. Nothing here re-renders
/// SwiftUI.
final class VideoNotePreviewUIView: UIView {
    enum Stage: Equatable, CustomStringConvertible {
        case warming, live, held, exit(VideoNoteRecorder.Ending)

        var description: String {
            switch self {
            case .warming: return "warming"
            case .live: return "live"
            case .held: return "held"
            case .exit(let ending): return "exit(\(ending))"
            }
        }
    }

    /// Space between the view's edge and the picture, where the ring is drawn.
    static let ringInset: CGFloat = 7

    private static let log = Logger(subsystem: "com.unyri.videonote", category: "fx")
    /// Frame-rate caps: anything tied to recording, the progress, short entry and exit one-shots.
    private static let level30 = CAFrameRateRange(minimum: 15, maximum: 30, preferred: 30)
    private static let slow30 = CAFrameRateRange(minimum: 10, maximum: 30, preferred: 30)
    private static let oneShot60 = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)

    /// Read on each tick while recording.
    var levelSource: (@MainActor () -> Double)?
    var elapsedSource: (@MainActor () -> TimeInterval)?

    // Bottom to top. Only aura, ripples and rim scale; only headArm rotates.
    private let aura = CAGradientLayer()
    private let ripples = [CAShapeLayer(), CAShapeLayer()]
    private let placeholder = CAGradientLayer()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let vignette = CAGradientLayer()
    private let veil = CALayer()
    private let edge = CAShapeLayer()
    private let rim = CALayer()
    private let track = CAShapeLayer()
    private let marks = (0..<4).map { _ in CALayer() }
    private let ring = CAShapeLayer()
    private let headArm = CALayer()
    private let headGlow = CAGradientLayer()
    private let headDot = CALayer()

    private var stage: Stage?
    private var meter = VideoNoteHaloMeter()
    private var link: CADisplayLink?
    private var lastTick: CFTimeInterval = 0
    private var nextRipple = 0
    private var reduceMotion = false
    /// Low Power Mode or a hot device: a 5 Hz tick and no onset ripples.
    private var lean = false
    private var inFinalSeconds = false
    private var progressRunning = false
    private var showsFront = true
    private var pictureRadius: CGFloat = 1
    private var ringRadius: CGFloat = 1
    private var outset: CGFloat = 14

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        // Depth for the camera flip; scale and z rotation are unaffected.
        var perspective = CATransform3DIdentity
        perspective.m34 = -1 / 800
        layer.sublayerTransform = perspective

        aura.type = .radial
        aura.startPoint = CGPoint(x: 0.5, y: 0.5)
        aura.endPoint = CGPoint(x: 1, y: 1)
        aura.opacity = 0
        for ripple in ripples {
            ripple.fillColor = UIColor.clear.cgColor
            ripple.strokeColor = UIColor.white.cgColor
            ripple.lineWidth = 1.25
            ripple.opacity = 0
        }
        placeholder.colors = [UIColor(white: 0.26, alpha: 1).cgColor, UIColor(white: 0.08, alpha: 1).cgColor]
        placeholder.startPoint = CGPoint(x: 0.2, y: 0)
        placeholder.endPoint = CGPoint(x: 0.8, y: 1)
        placeholder.masksToBounds = true
        // A static glass-lens edge over the picture.
        vignette.type = .radial
        vignette.startPoint = CGPoint(x: 0.5, y: 0.5)
        vignette.endPoint = CGPoint(x: 1, y: 1)
        vignette.locations = [0, 0.76, 1]
        vignette.colors = [UIColor.clear.cgColor, UIColor.clear.cgColor, UIColor(white: 0, alpha: 0.24).cgColor]
        vignette.masksToBounds = true
        veil.backgroundColor = UIColor.black.cgColor
        veil.opacity = 0
        edge.fillColor = UIColor.clear.cgColor
        edge.strokeColor = UIColor.white.cgColor
        edge.lineWidth = 1
        edge.opacity = 0.20

        for shape in [track, ring] {
            shape.fillColor = UIColor.clear.cgColor
            shape.strokeColor = UIColor.white.cgColor
            shape.lineWidth = 3
            shape.lineCap = .round
        }
        // An opaque stroke dimmed by layer opacity, so its brightness can animate.
        track.opacity = 0.16
        ring.strokeEnd = 0
        for mark in marks {
            mark.bounds = CGRect(x: 0, y: 0, width: 3, height: 3)
            mark.cornerRadius = 1.5
            mark.backgroundColor = UIColor.white.cgColor
            mark.opacity = 0
        }
        headArm.opacity = 0
        headGlow.type = .radial
        headGlow.startPoint = CGPoint(x: 0.5, y: 0.5)
        headGlow.endPoint = CGPoint(x: 1, y: 1)
        headGlow.colors = [UIColor(white: 1, alpha: 0.5).cgColor, UIColor(white: 1, alpha: 0).cgColor]
        headGlow.bounds = CGRect(x: 0, y: 0, width: 18, height: 18)
        headDot.bounds = CGRect(x: 0, y: 0, width: 5, height: 5)
        headDot.cornerRadius = 2.5
        headDot.backgroundColor = UIColor.white.cgColor
        headArm.addSublayer(headGlow)
        headArm.addSublayer(headDot)
        rim.addSublayer(track)
        marks.forEach { rim.addSublayer($0) }
        rim.addSublayer(ring)
        rim.addSublayer(headArm)

        let stack: [CALayer] = [aura, ripples[0], ripples[1], placeholder, vignette, veil, edge, rim]
        stack.forEach { layer.addSublayer($0) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let side = min(bounds.width, bounds.height)
        guard side > 0 else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let local = CGRect(origin: .zero, size: bounds.size)
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let localCenter = CGPoint(x: local.midX, y: local.midY)
        let picture = CGRect(x: center.x - side / 2, y: center.y - side / 2, width: side, height: side)
            .insetBy(dx: Self.ringInset, dy: Self.ringInset)
        pictureRadius = picture.width / 2
        ringRadius = max(1, side / 2 - 2)
        outset = CGFloat(VideoNoteHaloGeometry.outset(side: Double(side)))
        let auraRadius = side / 2 + outset
        // Bounds and position, never frame: these layers can carry a transform right now.
        aura.bounds = CGRect(x: 0, y: 0, width: 2 * auraRadius, height: 2 * auraRadius)
        aura.position = center
        let stops = VideoNoteHaloGeometry.auraStops(side: Double(side), ringInset: Double(Self.ringInset))
        aura.locations = stops.map { NSNumber(value: $0.location) }
        aura.colors = stops.map { UIColor(white: 1, alpha: CGFloat($0.alpha)).cgColor }
        for ripple in ripples {
            ripple.bounds = local
            ripple.position = center
            ripple.path = Self.circle(radius: ringRadius + 1.5, center: localCenter)
        }
        let pictured: [CALayer] = [placeholder, vignette, veil]
        for sublayer in pictured {
            sublayer.frame = picture
            sublayer.cornerRadius = pictureRadius
        }
        previewLayer?.frame = picture
        previewLayer?.cornerRadius = pictureRadius
        edge.frame = bounds
        edge.path = Self.circle(radius: pictureRadius + 0.5, center: localCenter)
        rim.bounds = local
        rim.position = center
        let ringPath = Self.circle(radius: ringRadius, center: localCenter)
        for shape in [track, ring] {
            shape.frame = local
            shape.path = ringPath
        }
        let total = VideoNoteLimits.maxSeconds
        for (mark, seconds) in zip(marks, VideoNoteCountdown.marks(total: total)) {
            let angle = -Double.pi / 2 + 2 * Double.pi * seconds / total
            mark.position = CGPoint(x: localCenter.x + ringRadius * CGFloat(cos(angle)),
                                    y: localCenter.y + ringRadius * CGFloat(sin(angle)))
        }
        // Inside the rim, so in its local space.
        headArm.bounds = local
        headArm.position = localCenter
        let head = CGPoint(x: localCenter.x, y: localCenter.y - ringRadius)
        headGlow.position = head
        headDot.position = head
        CATransaction.commit()
    }

    /// A circle that starts at 12 o'clock and runs clockwise, like the progress.
    private static func circle(radius: CGFloat, center: CGPoint) -> CGPath {
        UIBezierPath(arcCenter: center, radius: radius, startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
    }

    /// Shows the recorder's preview layer above the placeholder; the live picture fades in
    /// instead of popping. Nil removes it.
    func host(_ preview: AVCaptureVideoPreviewLayer?) {
        guard previewLayer !== preview else { return }
        // A circle on its way out never takes the layer of a take started meanwhile.
        if preview != nil, case .exit? = stage { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if let old = previewLayer {
            old.removeFromSuperlayer()
            // The recorder leaves a stopped session on the layer for its last frame.
            if old.session?.isRunning != true { old.session = nil }
        }
        previewLayer = preview
        if let preview {
            preview.masksToBounds = true
            layer.insertSublayer(preview, above: placeholder)
        }
        CATransaction.commit()
        if let preview { animate(preview, "opacity", to: Float(1), duration: 0.22, timing: .easeOut, from: Float(0)) }
        setNeedsLayout()
    }

    /// A short flip of the picture when the camera changes (a transform, on the render server).
    func setFront(_ front: Bool, animated: Bool) {
        guard showsFront != front else { return }
        showsFront = front
        guard animated else { return }
        let flip = CABasicAnimation(keyPath: "transform.rotation.y")
        flip.fromValue = front ? -Double.pi / 2 : Double.pi / 2
        flip.toValue = 0
        flip.duration = 0.32
        flip.timingFunction = CAMediaTimingFunction(name: .easeOut)
        flip.preferredFrameRateRange = Self.oneShot60
        let flipped: [CALayer] = [placeholder, vignette, veil]
        flipped.forEach { $0.add(flip, forKey: "flip") }
        previewLayer?.add(flip, forKey: "flip")
    }

    func setProgress(running: Bool, elapsed: TimeInterval, total: TimeInterval) {
        guard running != progressRunning, total > 0 else { return }
        progressRunning = running
        guard running else {
            // Keep the ring and head where they stopped (an interrupted take waits for Send or Discard).
            freeze(ring, "strokeEnd")
            freeze(headArm, "transform.rotation.z")
            return
        }
        let from = max(0, min(1, elapsed / total))
        let duration = max(0.1, total - elapsed)
        // One transaction, so the head stays on the stroke's end.
        CATransaction.begin()
        progress(ring, "strokeEnd", from: from, to: 1, duration: duration)
        progress(headArm, "transform.rotation.z", from: 2 * Double.pi * from, to: 2 * Double.pi, duration: duration)
        CATransaction.commit()
    }

    private func progress(_ target: CALayer, _ key: String, from: Double, to: Double, duration: CFTimeInterval) {
        let animation = CABasicAnimation(keyPath: key)
        animation.fromValue = from
        animation.toValue = to
        animation.duration = duration
        animation.timingFunction = CAMediaTimingFunction(name: .linear)
        animation.fillMode = .forwards
        animation.isRemovedOnCompletion = false
        animation.preferredFrameRateRange = Self.slow30
        target.add(animation, forKey: "progress")
    }

    private func freeze(_ target: CALayer, _ key: String) {
        let shown: Any? = target.presentation()?.value(forKeyPath: key) ?? target.value(forKeyPath: key)
        target.removeAnimation(forKey: "progress")
        setModel(target, key, shown)
    }

    // MARK: Stages

    func setStage(_ next: Stage, reduceMotion: Bool) {
        self.reduceMotion = reduceMotion
        guard next != stage else { return }
        let previous = stage
        // An exit is final for this view: every take gets a fresh one.
        if case .exit? = previous { return }
        stage = next
        let before = previous?.description ?? "nil", after = next.description
        Self.log.debug("stage \(before, privacy: .public) -> \(after, privacy: .public)")
        if previous == nil {
            // Ended before anything was shown.
            if case .exit = next { return }
            enter(delayAura: next == .warming)
        }
        switch next {
        case .warming: break
        case .live: goLive()
        case .held: hold()
        case .exit(let ending):
            stopTick()
            setProgress(running: false, elapsed: 0, total: 1)
            removeTransient()
            if ending == .sent { seal() } else { lampOut() }
        }
    }

    /// The track and the picture edge draw in; the glow waits until the circle has grown
    /// out of the record button.
    private func enter(delayAura: Bool) {
        if !reduceMotion {
            animate(track, "strokeEnd", to: CGFloat(1), duration: 0.42, timing: .easeOut, from: CGFloat(0))
            animate(edge, "strokeEnd", to: CGFloat(1), duration: 0.42, timing: .easeInEaseOut, from: CGFloat(0))
        }
        animate(aura, "opacity", to: Float(0.18), duration: 0.30, delay: delayAura ? 0.10 : 0)
    }

    private func goLive() {
        let info = ProcessInfo.processInfo
        lean = info.isLowPowerModeEnabled || info.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
        animate(aura, "opacity", to: Float(0.30), duration: 0.25)
        // "Recording now": one ripple, also in lean mode.
        if !reduceMotion { ripple(0.6) }
        animate(headArm, "opacity", to: Float(1), duration: 0.2)
        if !reduceMotion {
            let pop = CAKeyframeAnimation(keyPath: "transform.scale")
            pop.values = [0.2, 1.15, 1.0]
            pop.keyTimes = [0, 0.6, 1]
            pop.duration = 0.24
            pop.preferredFrameRateRange = Self.oneShot60
            headDot.add(pop, forKey: "pop")
        }
        startTick()
    }

    fileprivate func tick(_ displayLink: CADisplayLink) {
        let now = displayLink.timestamp
        let dt = lastTick == 0 ? 0.1 : now - lastTick
        lastTick = now
        let total = VideoNoteLimits.maxSeconds
        let elapsed = elapsedSource?() ?? 0
        let step = meter.step(level: levelSource?() ?? 0, dt: dt, now: now, remaining: total - elapsed,
                              calm: reduceMotion, ripplesAllowed: !reduceMotion && !lean,
                              warnAt: VideoNoteCountdown.warningSeconds)
        if let energy = step.apply { applyLevel(energy, duration: min(0.3, max(0.1, dt))) }
        if let energy = step.ripple { ripple(energy) }
        if step.warn { enterFinalSeconds(elapsed: elapsed, total: total) }
    }

    /// Each segment starts from what is on screen, so 10 Hz steps chain without jumps.
    private func applyLevel(_ energy: Double, duration: CFTimeInterval) {
        let range = Self.level30
        if reduceMotion {
            animate(aura, "opacity", to: Float((inFinalSeconds ? 0.50 : 0.30) + 0.30 * energy), duration: duration, range: range)
        } else if inFinalSeconds {
            animate(aura, "opacity", to: Float(0.50 + 0.50 * energy), duration: duration, range: range)
            animate(aura, "transform.scale", to: 1 + 0.015 * energy, duration: duration, range: range)
        } else {
            animate(aura, "opacity", to: Float(0.30 + 0.70 * energy), duration: duration, range: range)
            animate(aura, "transform.scale", to: 1 + 0.04 * energy, duration: duration, range: range)
            animate(track, "opacity", to: Float(0.16 + 0.10 * energy), duration: duration, range: range)
        }
    }

    /// One thin ring travels outward; two layers take turns.
    private func ripple(_ energy: Double) {
        // Not laid out yet: the growth would be computed for a 1 pt ring.
        guard ringRadius > 1 else { return }
        let shape = ripples[nextRipple]
        nextRipple ^= 1
        let duration: CFTimeInterval = 0.9
        let grow = CABasicAnimation(keyPath: "transform.scale")
        grow.fromValue = 1.0
        grow.toValue = 1 + 0.9 * Double(outset / ringRadius)
        grow.duration = duration
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.20 + 0.25 * energy
        fade.toValue = 0.0
        fade.duration = duration
        let group = CAAnimationGroup()
        group.animations = [grow, fade]
        group.duration = duration
        group.timingFunction = CAMediaTimingFunction(controlPoints: 0.22, 0.61, 0.36, 1)
        group.preferredFrameRateRange = Self.level30
        shape.add(group, forKey: "ripple")
    }

    /// T-5: the marks for the last seconds appear and the head pulses as it reaches each.
    private func enterFinalSeconds(elapsed: TimeInterval, total: TimeInterval) {
        inFinalSeconds = true
        Self.log.debug("final seconds at \(elapsed, format: .fixed(precision: 2), privacy: .public)")
        UnyriHaptics.selection()
        if UIAccessibility.isVoiceOverRunning {
            UIAccessibility.post(notification: .announcement, argument: UnyriL10n.string("5 seconds left"))
        }
        // The brighter glow shows even in silence, when the meter commits nothing.
        applyLevel(meter.energy, duration: 0.3)
        animate(track, "opacity", to: Float(0.30), duration: 0.3, range: Self.level30)
        for (index, (mark, seconds)) in zip(marks, VideoNoteCountdown.marks(total: total)).enumerated() where seconds > elapsed {
            let delay = reduceMotion ? 0 : 0.05 * Double(index)
            animate(mark, "opacity", to: Float(0.85), duration: 0.22, delay: delay, timing: .easeOut, range: Self.level30, from: Float(0))
            if !reduceMotion {
                animate(mark, "transform.scale", to: 1.0, duration: 0.22, delay: delay, timing: .easeOut, range: Self.level30, from: 0.4)
            }
        }
        guard !reduceMotion else { return }
        let plan = VideoNoteCountdown.pulses(elapsed: elapsed, total: total)
        guard plan.count > 0 else { return }
        let pulse = CAKeyframeAnimation(keyPath: "transform.scale")
        pulse.values = [1.0, 1.45, 1.0, 1.0]
        pulse.keyTimes = [0, 0.12, 0.45, 1]
        pulse.duration = 1
        pulse.repeatCount = Float(plan.count)
        pulse.beginTime = CACurrentMediaTime() + plan.delay
        pulse.preferredFrameRateRange = Self.level30
        headDot.add(pulse, forKey: "countdown")
        headGlow.add(pulse, forKey: "countdown")
    }

    /// Interrupted: the picture keeps its last frame under a light veil and the glow settles.
    private func hold() {
        stopTick()
        setProgress(running: false, elapsed: 0, total: 1)
        ([headDot, headGlow] as [CALayer]).forEach { $0.removeAnimation(forKey: "countdown") }
        animate(aura, "opacity", to: Float(0.18), duration: 0.4, timing: .easeInEaseOut)
        if !reduceMotion { animate(aura, "transform.scale", to: 1.0, duration: 0.4, timing: .easeInEaseOut) }
        animate(veil, "opacity", to: Float(0.28), duration: 0.3, timing: .easeOut)
        animate(headArm, "opacity", to: Float(0.5), duration: 0.2)
        animate(track, "opacity", to: Float(0.16), duration: 0.3)
    }

    /// Send, alongside the SwiftUI flight: the ring and head contract onto the picture edge,
    /// the edge flashes once and the lens clears, so what lands looks like the bubble.
    private func seal() {
        animate(aura, "opacity", to: Float(0), duration: 0.16, timing: .easeIn)
        if !reduceMotion {
            animate(aura, "transform.scale", to: 0.96, duration: 0.16, timing: .easeIn)
            animate(rim, "transform.scale", to: Double(pictureRadius / ringRadius), duration: 0.18, timing: .easeIn)
        }
        animate(rim, "opacity", to: Float(0), duration: 0.22, timing: .easeIn)
        animate(vignette, "opacity", to: Float(0), duration: 0.20, delay: 0.14)
        // A held take lands bright.
        if veil.opacity > 0 { animate(veil, "opacity", to: Float(0), duration: 0.20) }
        let flash = CAKeyframeAnimation(keyPath: "opacity")
        flash.values = [0.20, reduceMotion ? 0.5 : 0.9, 0.0]
        flash.keyTimes = [0, 0.3, 1]
        flash.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeIn)]
        flash.duration = 0.34
        flash.beginTime = CACurrentMediaTime() + 0.06
        flash.fillMode = .backwards
        flash.preferredFrameRateRange = Self.oneShot60
        setModel(edge, "opacity", Float(0))
        edge.add(flash, forKey: "seal")
    }

    /// Discard, too short or failure: time winds back, the picture goes dark and the glow
    /// disperses while the circle drops back into the record button.
    private func lampOut() {
        if reduceMotion {
            animate(rim, "opacity", to: Float(0), duration: 0.16)
        } else {
            animate(ring, "strokeEnd", to: CGFloat(0), duration: 0.22, timing: .easeIn)
            animate(rim, "opacity", to: Float(0), duration: 0.16, delay: 0.08, timing: .easeIn)
            animate(aura, "transform.scale", to: 1.12, duration: 0.22, timing: .easeOut)
        }
        animate(headArm, "opacity", to: Float(0), duration: 0.12)
        animate(edge, "opacity", to: Float(0), duration: 0.14)
        animate(aura, "opacity", to: Float(0), duration: 0.22, timing: .easeOut)
        animate(veil, "opacity", to: Float(0.6), duration: 0.20, timing: .easeOut)
    }

    private func removeTransient() {
        ripples.forEach { $0.removeAnimation(forKey: "ripple") }
        ([headDot, headGlow] as [CALayer]).forEach { $0.removeAnimation(forKey: "countdown") }
    }

    // MARK: Tick

    /// The only periodic work: while recording and on screen.
    private func startTick() {
        guard link == nil, stage == .live, window != nil else { return }
        let proxy = VideoNoteTickProxy()
        proxy.owner = self
        let displayLink = CADisplayLink(target: proxy, selector: #selector(VideoNoteTickProxy.fire(_:)))
        displayLink.preferredFrameRateRange = lean ? CAFrameRateRange(minimum: 4, maximum: 5, preferred: 5)
            : CAFrameRateRange(minimum: 8, maximum: 10, preferred: 10)
        displayLink.add(to: .main, forMode: .common)
        link = displayLink
        lastTick = 0
        Self.log.debug("tick on \(self.lean ? 5 : 10, privacy: .public)Hz")
    }

    private func stopTick() {
        guard let displayLink = link else { return }
        displayLink.invalidate()
        link = nil
        Self.log.debug("tick off")
    }

    /// SwiftUI is dismantling the view.
    func teardown() { stopTick() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { stopTick() } else { startTick() }
    }

    // MARK: Animation helpers

    /// An explicit animation from what is on screen; the model is set first, without actions.
    private func animate(_ target: CALayer, _ key: String, to value: Any, duration: CFTimeInterval, delay: CFTimeInterval = 0,
                         timing: CAMediaTimingFunctionName = .linear, range: CAFrameRateRange = VideoNotePreviewUIView.oneShot60,
                         from: Any? = nil) {
        let start: Any? = from ?? target.presentation()?.value(forKeyPath: key) ?? target.value(forKeyPath: key)
        setModel(target, key, value)
        let animation = CABasicAnimation(keyPath: key)
        animation.fromValue = start
        animation.toValue = value
        animation.duration = duration
        animation.timingFunction = CAMediaTimingFunction(name: timing)
        if delay > 0 {
            animation.beginTime = CACurrentMediaTime() + delay
            animation.fillMode = .backwards
        }
        animation.preferredFrameRateRange = range
        target.add(animation, forKey: key)
    }

    private func setModel(_ target: CALayer, _ key: String, _ value: Any?) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        target.setValue(value, forKeyPath: key)
        CATransaction.commit()
    }
}

/// The display link's target. It holds the view weakly, so the link never keeps it alive,
/// and stops the link itself once the view is gone.
@MainActor
private final class VideoNoteTickProxy: NSObject {
    weak var owner: VideoNotePreviewUIView?

    @objc func fire(_ link: CADisplayLink) {
        guard let owner else { link.invalidate(); return }
        owner.tick(link)
    }
}

struct VideoNotePreview: UIViewRepresentable {
    @ObservedObject var recorder: VideoNoteRecorder
    var reduceMotion = false

    func makeUIView(context: Context) -> VideoNotePreviewUIView {
        let view = VideoNotePreviewUIView(frame: .zero)
        let source = recorder
        view.levelSource = { [weak source] in source?.level() ?? 0 }
        view.elapsedSource = { [weak source] in source?.recordedSeconds ?? 0 }
        view.setFront(recorder.frontCamera, animated: false)
        apply(view)
        return view
    }

    func updateUIView(_ view: VideoNotePreviewUIView, context: Context) { apply(view) }

    static func dismantleUIView(_ view: VideoNotePreviewUIView, coordinator: ()) {
        view.teardown()
        view.host(nil)
    }

    private func apply(_ view: VideoNotePreviewUIView) {
        // Until the first frames arrive the placeholder shows instead of a black circle.
        // Never host(nil) here: a stopped or ending take keeps its last frame.
        if recorder.previewLive, let layer = recorder.previewLayer { view.host(layer) }
        view.setFront(recorder.frontCamera, animated: !reduceMotion)
        view.setProgress(running: recorder.isRecording, elapsed: recorder.recordedSeconds, total: VideoNoteLimits.maxSeconds)
        view.setStage(stage, reduceMotion: reduceMotion)
    }

    private var stage: VideoNotePreviewUIView.Stage {
        switch recorder.phase {
        case .starting: return .warming
        case .recording: return .live
        case .stopped: return .held
        case .idle: return .exit(recorder.ending)
        }
    }
}
