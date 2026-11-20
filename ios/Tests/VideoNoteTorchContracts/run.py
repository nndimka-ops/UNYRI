"""Exercise production video-note torch methods without Apple camera hardware.

Only AVFoundation devices, queues' native session boundary, and file writing are
substituted. Camera availability KVO and LED behavior still need a real iPhone.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


root = Path(__file__).resolve().parents[3]
recorder = (root / "ios/UNYRI/Attachments/VideoNoteRecorder.swift").read_text(encoding="utf-8")


def member(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func ", "func ")


swift = os.environ.get("SWIFTC") or shutil.which("swiftc")
if not swift and os.name == "nt":
    candidate = Path(os.environ["LOCALAPPDATA"]) / "Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe"
    if candidate.is_file():
        swift = str(candidate)
if not swift:
    raise SystemExit("Swift compiler unavailable; set SWIFTC.")

recorder_core = recorder[recorder.index("@MainActor\nfinal class VideoNoteRecorder"):recorder.index("// MARK: - Capture core")]
camera_core = recorder[recorder.index("private final class VideoNoteCameraCapture"):recorder.index("// MARK: Data queue")]

# KVO itself is an Apple-only boundary. Assert its production queue/identity wiring;
# all state transitions invoked by that callback execute below as extracted Swift.
observation = member(camera_core, "private func observeTorch()")
assert r"device.observe(\.isTorchAvailable" in observation
assert "self.sessionQueue.async" in observation
assert "ObjectIdentifier(current) == id" in observation
assert "refreshTorchState()" in observation
assert "turnTorchOff(device)" in member(camera_core, "private func configureSession(front:")

source = r'''
import Foundation
import Dispatch

// Camera and notification fixtures replace frameworks, not torch decisions.
enum VideoNoteLimits { static let minSeconds: Double = 1; static let maxSeconds: Double = 60 }
struct VideoNoteRecording: Sendable { let fileURL: URL }
enum VideoNoteOrientation: Sendable { case portrait }
enum VideoNoteRecordError: Error { case couldNotStart }
__EVENT__

final class QueueMarker: @unchecked Sendable {
    static let shared = QueueMarker()
    let key = DispatchSpecificKey<Bool>()
}
final class AVCaptureDevice: @unchecked Sendable {
    enum Position { case front, back }
    enum TorchMode { case off, on }
    let position: Position
    var hasTorch = true, isTorchAvailable = true, refusesWrites = false, refusesLock = false
    var actualMode = TorchMode.off
    var locked = false, locks = 0, unlocks = 0
    var writes: [TorchMode] = []
    init(_ position: Position) { self.position = position }
    var torchMode: TorchMode {
        get { actualMode }
        set {
            precondition(locked && DispatchQueue.getSpecific(key: QueueMarker.shared.key) == true,
                         "every torch write must hold the configuration lock on sessionQueue")
            writes.append(newValue)
            if !refusesWrites { actualMode = newValue }
        }
    }
    func lockForConfiguration() throws {
        precondition(DispatchQueue.getSpecific(key: QueueMarker.shared.key) == true,
                     "device configuration must run on sessionQueue")
        locks += 1
        if refusesLock { throw VideoNoteRecordError.couldNotStart }
        locked = true
    }
    func unlockForConfiguration() { precondition(locked); locked = false; unlocks += 1 }
}
final class AVCaptureDeviceInput: @unchecked Sendable {
    let device: AVCaptureDevice
    init(device: AVCaptureDevice) throws { self.device = device }
}
final class Fixtures: @unchecked Sendable {
    static let shared = Fixtures()
    var front = AVCaptureDevice(.front), back = AVCaptureDevice(.back)
}
final class AVCaptureSession: NSObject, @unchecked Sendable {
    var isRunning = true
    var input: AVCaptureDeviceInput?
    var offBeforeStop = false
    func startRunning() { isRunning = true }
    func stopRunning() {
        offBeforeStop = input?.device.torchMode != .on
        input?.device.actualMode = .off // Native shutdown also releases a refused LED.
        isRunning = false
    }
    func beginConfiguration() {}
    func commitConfiguration() {}
    func removeInput(_ input: AVCaptureDeviceInput) { input.device.actualMode = .off; self.input = nil }
    func canAddInput(_ input: AVCaptureDeviceInput) -> Bool { true }
    func addInput(_ input: AVCaptureDeviceInput) { self.input = input }
}
final class Output: @unchecked Sendable {
    func setSampleBufferDelegate(_ delegate: NSObject?, queue: DispatchQueue?) {}
}
final class CameraProbe: @unchecked Sendable {
    let sessionQueue = DispatchQueue(label: "video-note-torch-contract.session")
    let dataQueue = DispatchQueue(label: "video-note-torch-contract.data")
    let captureSession = AVCaptureSession()
    let videoOutput = Output(), audioOutput = Output()
    let lock = NSLock()
    var events: (@Sendable (VideoNoteCaptureEvent) -> Void)?
    var cameraInput: AVCaptureDeviceInput?
    var orientation = VideoNoteOrientation.portrait
    var stopped = false, torchDisabled = false, accepting = false, failConfiguration = false
    var torchAvailability: Int?
    var torchRevision: UInt64 = 0
    var emitted: [VideoNoteCaptureEvent] = []
    init(front: Bool = false) {
        sessionQueue.setSpecific(key: QueueMarker.shared.key, value: true)
        cameraInput = try! AVCaptureDeviceInput(device: Self.camera(front: front)!)
        captureSession.input = cameraInput
    }
    static func camera(front: Bool) -> AVCaptureDevice? { front ? Fixtures.shared.front : Fixtures.shared.back }
    func emit(_ event: VideoNoteCaptureEvent) { emitted.append(event) }
    func observeTorch() {} // KVO registration is asserted against production above.
    func orientOutput() {}
    func configureSession(front: Bool) throws -> Bool {
        if failConfiguration { throw VideoNoteRecordError.couldNotStart }
        cameraInput = try AVCaptureDeviceInput(device: Self.camera(front: front)!)
        captureSession.input = cameraInput
        if let cameraInput { turnTorchOff(cameraInput.device) }
        return false
    }
    func prepareWriter(audio: Bool) throws {}
    func finalize(_ completion: @escaping @Sendable (VideoNoteRecording?) -> Void) { completion(nil) }
    func drain() { sessionQueue.sync {} }
    func state() -> (available: Bool, on: Bool) {
        sessionQueue.sync {
            for event in emitted.reversed() {
                if case .torch(let available, let on, _) = event { return (available, on) }
            }
            preconditionFailure("production did not publish a torch state")
        }
    }
    __CAMERA_METHODS__
}

struct Notification {
    struct Name: Hashable, Sendable { let rawValue: String; init(_ value: String) { rawValue = value } }
    let name: Name
    var userInfo: [String: Any]?
}
final class NotificationCenter: @unchecked Sendable {
    static let `default` = NotificationCenter()
    final class Token: NSObject, @unchecked Sendable {
        let name: Notification.Name
        let action: @Sendable (Notification) -> Void
        init(_ name: Notification.Name, _ action: @escaping @Sendable (Notification) -> Void) {
            self.name = name; self.action = action
        }
    }
    var tokens: [Token] = []
    func addObserver(forName name: Notification.Name, object: AnyObject?, queue: OperationQueue?,
                     using action: @escaping @Sendable (Notification) -> Void) -> NSObjectProtocol {
        let token = Token(name, action); tokens.append(token); return token
    }
    func removeObserver(_ value: NSObjectProtocol) { tokens.removeAll { $0 === (value as AnyObject) } }
    @MainActor func post(_ name: String, userInfo: [String: Any]? = nil) {
        for token in tokens where token.name.rawValue == name {
            token.action(Notification(name: token.name, userInfo: userInfo))
        }
    }
}
enum AVAudioSession {
    static let interruptionNotification = Notification.Name("audio-interruption")
    enum InterruptionType: UInt { case ended = 0, began = 1 }
}
let AVAudioSessionInterruptionTypeKey = "type"
enum UIApplication { static let willResignActiveNotification = Notification.Name("app-inactive") }

@MainActor final class RecorderProbe {
    enum Phase { case idle, starting, recording, stopped }
    enum Ending { case sent, discarded }
    var phase = Phase.recording, ending = Ending.discarded
    var capture: CameraProbe?
    var token = 1
    var torchRevision: UInt64 = 0
    var torchAvailable = true, torchOn = true, frontCamera = false, previewLive = true
    var startedAt: TimeInterval?
    var frozenSeconds: TimeInterval = 3
    var finishTask: Task<VideoNoteRecording?, Never>?
    var stopTask: Task<Void, Never>?
    var onInterrupted: (() -> Void)?, onLimitReached: (() -> Void)?, onFailed: (() -> Void)?
    var observers: [NSObjectProtocol] = []
    init(_ capture: CameraProbe) { self.capture = capture }
    func orientPreview() {}
    func captureStopped(token: Int) {
        guard token == self.token else { return }
        capture = nil; stopTask = nil; removeObservers()
    }
    func settle() async {
        if let finishTask { _ = await finishTask.value }
        if let stopTask { await stopTask.value }
    }
    __RECORDER_METHODS__
}

@main struct Run {
    @MainActor static func main() async {
        var checks = 0
        func check(_ condition: @autoclosure () -> Bool, _ message: String) {
            precondition(condition(), message); checks += 1
        }
        func fresh(front: Bool = false) -> CameraProbe {
            Fixtures.shared.front = AVCaptureDevice(.front)
            Fixtures.shared.back = AVCaptureDevice(.back)
            return CameraProbe(front: front)
        }
        func refresh(_ camera: CameraProbe) { camera.sessionQueue.sync { camera.refreshTorchState() } }

        let c = fresh()
        refresh(c)
        check(c.state().available && !c.state().on, "a running torch-equipped back camera starts dark and available")
        c.toggleTorch(); c.drain()
        check(c.state().on, "toggle publishes the actual device on mode")
        c.toggleTorch(); c.drain()
        check(!c.state().on, "toggle publishes actual off mode")
        c.sessionQueue.sync { c.cameraInput!.device.refusesWrites = true }
        c.toggleTorch(); c.drain()
        check(!c.state().on, "a refused on write must never publish the requested on state")
        c.sessionQueue.sync { c.cameraInput!.device.refusesWrites = false; c.cameraInput!.device.refusesLock = true }
        c.toggleTorch(); c.drain()
        check(!c.state().on, "a refused lock publishes the unchanged off state")
        c.sessionQueue.sync { c.cameraInput!.device.refusesLock = false }
        c.toggleTorch(); c.drain()
        c.sessionQueue.sync { c.cameraInput!.device.refusesLock = true }
        c.toggleTorch(); c.drain()
        check(c.state().on, "a failed off lock publishes the device's still-on result")
        c.sessionQueue.sync { c.cameraInput!.device.refusesLock = false; c.cameraInput!.device.isTorchAvailable = false }
        refresh(c)
        check(!c.state().available && !c.state().on, "availability loss clears UI and requests off even when unavailable")
        check(c.sessionQueue.sync { c.cameraInput!.device.torchMode == .off }, "availability loss physically darkens the camera")
        c.sessionQueue.sync { c.cameraInput!.device.isTorchAvailable = true }
        refresh(c)
        check(c.state().available && !c.state().on, "availability recovery never relights the torch")
        c.sessionQueue.sync { c.cameraInput!.device.hasTorch = false }
        refresh(c)
        check(!c.state().available, "a device without a torch never exposes the control")
        c.sessionQueue.sync { c.cameraInput!.device.hasTorch = true; c.captureSession.isRunning = false }
        refresh(c)
        check(!c.state().available, "a nonrunning capture cannot expose torch availability")
        c.sessionQueue.sync { c.captureSession.isRunning = true; c.cameraInput = nil }
        refresh(c)
        check(!c.state().available && !c.state().on, "a missing active input has no torch")
        c.stop {}; c.drain()

        let f = fresh(front: true)
        refresh(f)
        f.toggleTorch(); f.drain()
        check(!f.state().available && !f.state().on, "a front device is excluded even if its fixture reports a torch")
        f.flip(); f.drain()
        check(f.state().available && !f.state().on, "flipping to back re-evaluates availability without turning it on")
        f.toggleTorch(); f.drain()
        check(f.state().on, "back camera can be lit after a flip")
        let oldBack = Fixtures.shared.back
        f.flip(); f.drain()
        check(!f.state().available && !f.state().on && oldBack.torchMode == .off,
              "flipping to front darkens the previous back camera")
        f.stop {}; f.drain()

        let stopping = fresh()
        stopping.toggleTorch(); stopping.drain()
        stopping.stop {}; stopping.drain()
        check(!stopping.state().available && !stopping.state().on && stopping.captureSession.offBeforeStop,
              "stop turns off before native session shutdown")
        stopping.toggleTorch(); stopping.drain()
        check(!stopping.state().on, "a queued or late tap cannot relight a stopped take")
        let refusedOff = fresh()
        refusedOff.toggleTorch(); refusedOff.drain()
        refusedOff.sessionQueue.sync { refusedOff.cameraInput!.device.refusesLock = true }
        refusedOff.stop {}; refusedOff.drain()
        check(refusedOff.cameraInput!.device.torchMode == .off && !refusedOff.state().on,
              "native stop releases the LED even if the off configuration lock fails")
        let finalized = fresh()
        finalized.toggleTorch(); finalized.drain()
        finalized.finish { _ in }; finalized.drain()
        check(!finalized.state().available && !finalized.state().on, "file finish disables torch independently of session stop")
        finalized.toggleTorch(); finalized.drain()
        check(!finalized.state().on, "disabled finishing capture rejects another tap")
        finalized.stop {}; finalized.drain()

        let starting = fresh()
        starting.captureSession.isRunning = false
        starting.start(front: false, orientation: .portrait) { _ in }
        starting.drain()
        check(starting.state().available && !starting.state().on, "start evaluates actual camera availability after session startup")
        starting.stop {}; starting.drain()
        let startupFailure = fresh()
        startupFailure.toggleTorch(); startupFailure.drain()
        startupFailure.sessionQueue.sync { startupFailure.failConfiguration = true }
        startupFailure.start(front: false, orientation: .portrait) { _ in }
        startupFailure.drain()
        check(startupFailure.torchDisabled && !startupFailure.state().on && startupFailure.cameraInput!.device.torchMode == .off,
              "a startup failure disables the torch before reporting failure")
        startupFailure.stop {}; startupFailure.drain()

        let bridgeCamera = fresh()
        let bridge = RecorderProbe(bridgeCamera)
        bridge.torchAvailable = false
        bridge.toggleTorch(); bridgeCamera.drain()
        check(bridgeCamera.cameraInput!.device.torchMode == .off, "UI toggle refuses unavailable capture")
        bridge.torchAvailable = true
        bridge.toggleTorch(); bridgeCamera.drain()
        check(bridgeCamera.cameraInput!.device.torchMode == .on, "UI toggle delegates to the capture queue")
        bridge.phase = .idle
        bridge.toggleTorch(); bridgeCamera.drain()
        check(bridgeCamera.cameraInput!.device.torchMode == .on, "UI toggle refuses an inactive take")
        bridge.phase = .recording
        bridge.flipCamera(); bridgeCamera.drain()
        check(bridgeCamera.cameraInput!.device.position == .front && !bridgeCamera.state().on,
              "UI flip delegates to the production camera flip and darkens the back camera")
        bridge.cancel(); await bridge.settle()

        let eventCamera = fresh()
        let r = RecorderProbe(eventCamera)
        r.torchOn = false; r.torchAvailable = false
        r.handle(.torch(available: true, on: true, revision: 2), token: 1)
        check(r.torchAvailable && r.torchOn, "MainActor publishes the current capture result")
        r.handle(.torch(available: false, on: false, revision: 1), token: 1)
        check(r.torchOn, "out-of-order callbacks cannot replace a newer device result")
        r.handle(.torch(available: false, on: false, revision: 3), token: 0)
        check(r.torchOn, "an old take's event cannot change the new take")
        r.phase = .stopped
        r.handle(.torch(available: true, on: true, revision: 4), token: 1)
        check(r.torchRevision == 2, "inactive recorders reject late torch state")
        r.phase = .recording
        r.cancel(); await r.settle()

        for action in ["send", "cancel", "interrupt", "failure", "session-interruption", "runtime-error", "background", "audio-interruption"] {
            let camera = fresh()
            camera.toggleTorch(); camera.drain()
            let recorder = RecorderProbe(camera)
            recorder.observeInterruptions(camera.captureSession)
            switch action {
            case "send": _ = await recorder.finish()
            case "cancel": recorder.cancel()
            case "interrupt": recorder.interrupt()
            case "failure": recorder.handle(.failed, token: 1)
            case "session-interruption": NotificationCenter.default.post("AVCaptureSessionWasInterruptedNotification")
            case "runtime-error": NotificationCenter.default.post("AVCaptureSessionRuntimeErrorNotification")
            case "background": NotificationCenter.default.post("app-inactive")
            default: NotificationCenter.default.post("audio-interruption", userInfo: [AVAudioSessionInterruptionTypeKey: UInt(1)])
            }
            check(!recorder.torchAvailable && !recorder.torchOn, "\(action) synchronously clears published torch state")
            recorder.handle(.torch(available: true, on: true, revision: 999), token: 1)
            check(!recorder.torchOn, "\(action) cannot be undone by a late capture result")
            await recorder.settle(); camera.drain()
            check(!camera.state().on && camera.cameraInput!.device.torchMode == .off,
                  "\(action) physically disables the LED")
        }

        let teardown = fresh()
        teardown.toggleTorch(); teardown.drain()
        var released: RecorderProbe? = RecorderProbe(teardown)
        check(released != nil, "teardown fixture owns the active recorder")
        released = nil
        teardown.drain()
        check(teardown.stopped && !teardown.state().on && teardown.cameraInput!.device.torchMode == .off,
              "recorder deinit stops capture and darkens the LED")
        print("PASS: \(checks) production video-note torch contracts; Swift 6 strict concurrency")
    }
}
'''

source = source.replace("__EVENT__", member(recorder, "private enum VideoNoteCaptureEvent: Sendable").replace("private enum", "enum"))
source = source.replace("__CAMERA_METHODS__", "\n".join(member(camera_core, marker) for marker in [
    "func start(front:", "func flip()", "func toggleTorch()", "func disableTorch()",
    "func finish(", "func stop(", "private func disableTorchOnSessionQueue()",
    "private func turnTorchOff(", "private func refreshTorchState()",
]))
source = source.replace("__RECORDER_METHODS__", "\n".join(member(recorder_core, marker) for marker in [
    "deinit", "func flipCamera()", "func toggleTorch()", "func finish()", "func interrupt()", "func cancel()",
    "private func handle(", "private func resetTorchState()", "private func freeze()", "private func clearHandlers()",
    "private func beginFinish()", "private func observeInterruptions(", "private func removeObservers()",
]))

with tempfile.TemporaryDirectory(prefix="unyri-video-note-torch-") as directory:
    folder = Path(directory)
    main = folder / "Runner.swift"
    executable = folder / ("runner.exe" if os.name == "nt" else "runner")
    main.write_text(source, encoding="utf-8")
    subprocess.run([swift, "-swift-version", "6", "-strict-concurrency=complete", "-parse-as-library",
                    str(main), "-o", str(executable)], check=True, timeout=90)
    subprocess.run([str(executable)], check=True, timeout=30)
