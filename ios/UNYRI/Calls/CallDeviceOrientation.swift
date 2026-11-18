import Foundation
import CoreGraphics

/// Camera/UIDevice names. UIKit's landscape names describe the opposite edge;
/// convert at the UIKit boundary, never in the camera rotation table.
enum CallOrientation: Int, CaseIterable, Sendable {
    case portrait = 0, landscapeLeft = 90, portraitUpsideDown = 180, landscapeRight = 270
    var landscape: Bool { self == .landscapeLeft || self == .landscapeRight }
}

enum CallOrientationPolicy {
    static func frameRotation(_ orientation: CallOrientation, front: Bool) -> Int {
        switch orientation {
        case .portrait: return 90
        case .portraitUpsideDown: return 270
        case .landscapeLeft: return front ? 180 : 0
        case .landscapeRight: return front ? 0 : 180
        }
    }

    /// Wait for an allowed system turn, including a phone turned upside down
    /// beneath a landscape UI. Without landscape in the mask there is no turn
    /// to wait for: use the already-hysteresis-filtered physical orientation.
    static func tag(interface: CallOrientation, physical: CallOrientation,
                    disagreeingSince: TimeInterval?, now: TimeInterval,
                    allowsLandscape: Bool = true) -> CallOrientation {
        if !allowsLandscape { return physical }
        if physical == interface { return interface }
        guard let since = disagreeingSince else { return interface }
        return now - since + 1e-9 >= 0.6 ? physical : interface
    }

    /// Clockwise in the renderer's (y-down) display space. Rotating a mirror
    /// preserves its handedness; reflecting again would not.
    static func selfViewDelta(interface: CallOrientation, tag: CallOrientation) -> Int {
        let degrees = (tag.rawValue - interface.rawValue + 360) % 360
        return degrees > 180 ? degrees - 360 : degrees
    }

    static func swapsAspect(delta: Int) -> Bool { abs(delta) % 180 == 90 }

    static func remoteFills(frame: CGSize, stage: CGSize) -> Bool {
        guard frame.width > 0, frame.height > 0, stage.width > 0, stage.height > 0 else { return true }
        return (frame.width > frame.height) == (stage.width > stage.height)
    }
}

/// Published atomically so compensation never combines different UIKit turns.
struct CallOrientationState: Equatable, Sendable {
    var interface = CallOrientation.portrait
    var tag = CallOrientation.portrait
}

/// The user's fit/fill choice belongs to a pair of orientations. Resolution
/// changes retain it; a screen turn or peer direction change restores auto.
struct CallRemoteVideoFraming: Equatable {
    private(set) var overrideFill: Bool?
    private var frame = CGSize.zero
    private var rotation: Int?
    private var screen = CallOrientation.portrait
    private var stageLandscape = false

    func fills(stage: CGSize) -> Bool { overrideFill ?? CallOrientationPolicy.remoteFills(frame: frame, stage: stage) }
    mutating func toggle(stage: CGSize) { overrideFill = !fills(stage: stage) }
    mutating func updateRemote(_ size: CGSize, rotation: Int) {
        if (size.width > size.height) != (frame.width > frame.height) || self.rotation != rotation { overrideFill = nil }
        frame = size; self.rotation = rotation
    }
    mutating func updateScreen(_ orientation: CallOrientation) {
        if screen != orientation { overrideFill = nil }
        screen = orientation
    }
    mutating func updateStage(_ size: CGSize) {
        let landscape = size.width > size.height
        if landscape != stageLandscape { overrideFill = nil }
        stageLandscape = landscape
    }
}

/// Monotonic-time hysteresis, independent of CoreMotion and UIKit. The 60°
/// threshold is measured from the current axis, across the 0/360 seam too.
struct CallOrientationHysteresis {
    private(set) var orientation: CallOrientation
    private(set) var stableSince: TimeInterval = 0
    private var candidate: CallOrientation?
    private var candidateSince: TimeInterval = 0

    init(orientation: CallOrientation = .portrait) { self.orientation = orientation }

    mutating func sample(x: Double, y: Double, z: Double, at now: TimeInterval) {
        guard x.isFinite, y.isFinite, z.isFinite, abs(z) <= 0.85,
              x * x + y * y > 0.01 else { candidate = nil; return }
        let angle = (atan2(-x, -y) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        let distance = abs(angle - Double(orientation.rawValue))
        // Ignore floating-point roundoff at the exact threshold (atan2 and
        // monotonic-time subtraction), far below the sensor's resolution.
        guard min(distance, 360 - distance) + 1e-9 >= 60 else { candidate = nil; return }
        let nearest = CallOrientation(rawValue: (Int((angle + 45) / 90) % 4) * 90)!
        guard nearest != orientation else { candidate = nil; return }
        if candidate != nearest { candidate = nearest; candidateSince = now }
        if now - candidateSince + 1e-9 >= 0.25 {
            orientation = nearest; stableSince = now; candidate = nil
        }
    }
}

/// A capture-queue read is one small lock, with no actor hop or UIKit access.
final class CallOrientationStore: @unchecked Sendable {
    private let lock = NSLock()
    private var orientation = CallOrientation.portrait
    func read() -> CallOrientation { lock.lock(); defer { lock.unlock() }; return orientation }
    func write(_ value: CallOrientation) { lock.lock(); orientation = value; lock.unlock() }
}

#if canImport(UIKit) && canImport(CoreMotion)
import UIKit
import CoreMotion
import Combine

extension CallOrientation {
    init(interface: UIInterfaceOrientation) {
        switch interface {
        case .landscapeLeft: self = .landscapeRight
        case .landscapeRight: self = .landscapeLeft
        case .portraitUpsideDown: self = .portraitUpsideDown
        default: self = .portrait
        }
    }
}

/// Serial sensor work: only a committed orientation crosses to the main actor.
/// All mutable state below belongs to `queue`, including simulator dwell checks.
private final class CallMotionSampler: @unchecked Sendable {
    let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "UNYRI.CallOrientation"
        queue.maxConcurrentOperationCount = 1
        queue.qualityOfService = .utility
        return queue
    }()
    private var hysteresis: CallOrientationHysteresis
    private var simulatedGeneration = 0
    private let committed: @Sendable (CallOrientation) -> Void

    init(initial: CallOrientation, committed: @escaping @Sendable (CallOrientation) -> Void) {
        hysteresis = CallOrientationHysteresis(orientation: initial)
        self.committed = committed
    }

    func sample(x: Double, y: Double, z: Double) {
        let previous = hysteresis.orientation
        hysteresis.sample(x: x, y: y, z: z, at: ProcessInfo.processInfo.systemUptime)
        if previous != hysteresis.orientation { committed(hysteresis.orientation) }
    }

    #if targetEnvironment(simulator)
    func simulated(degrees: Double?) {
        queue.addOperation { [self] in
            simulatedGeneration &+= 1
            let generation = simulatedGeneration
            guard let degrees else { sample(x: 0, y: 0, z: 1); return }
            let radians = degrees * .pi / 180
            sample(x: -sin(radians), y: -cos(radians), z: 0)
            // UIDevice notifications are events, not a 10 Hz source. One dwell
            // check commits a still-current candidate without a polling loop.
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.25) { [self] in
                queue.addOperation { [self] in
                    guard simulatedGeneration == generation else { return }
                    sample(x: -sin(radians), y: -cos(radians), z: 0)
                }
            }
        }
    }
    #endif
}

/// Shared by calls and rooms, leased only by running local camera capture.
/// UIKit supplies the interface; sensing never reads a scene or ticks on main.
@MainActor
final class CallDeviceOrientation: NSObject, ObservableObject {
    static let shared = CallDeviceOrientation()
    nonisolated let frames = CallOrientationStore()
    @Published private(set) var state = CallOrientationState()
    private let motion = CMMotionManager()
    private var sampler: CallMotionSampler?
    private var owners = Set<UUID>()
    private var captureGeneration = 0
    private var physical = CallOrientation.portrait
    private var allowsLandscape = false
    private var disagreement: (interface: CallOrientation, physical: CallOrientation, since: TimeInterval)?
    private var tagCheck: Task<Void, Never>?
    private var tagDeadline: TimeInterval?

    /// Called by the UIKit rotation host for both geometry and mask changes.
    /// The pair is published once, after its frame tag has been selected.
    func updateInterface(_ interface: CallOrientation, allowsLandscape: Bool) {
        self.allowsLandscape = allowsLandscape
        updateTag(interface: interface)
    }

    func capturing(_ capturing: Bool, owner: UUID) {
        if capturing { owners.insert(owner) } else { owners.remove(owner) }
        if !owners.isEmpty, sampler == nil {
            captureGeneration &+= 1
            let generation = captureGeneration
            let sampler = CallMotionSampler(initial: physical) { [weak self] orientation in
                Task { @MainActor [weak self] in
                    guard let self, !self.owners.isEmpty, self.captureGeneration == generation else { return }
                    self.physical = orientation
                    self.updateTag(interface: self.state.interface)
                }
            }
            self.sampler = sampler
            updateTag(interface: state.interface)
            #if targetEnvironment(simulator)
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            NotificationCenter.default.addObserver(self, selector: #selector(deviceTurned),
                name: UIDevice.orientationDidChangeNotification, object: nil)
            deviceTurned()
            #else
            if motion.isDeviceMotionAvailable {
                motion.deviceMotionUpdateInterval = 0.1
                motion.startDeviceMotionUpdates(to: sampler.queue) { data, _ in
                    guard let gravity = data?.gravity else { return }
                    sampler.sample(x: gravity.x, y: gravity.y, z: gravity.z)
                }
            } else if motion.isAccelerometerAvailable {
                motion.accelerometerUpdateInterval = 0.1
                motion.startAccelerometerUpdates(to: sampler.queue) { data, _ in
                    guard let gravity = data?.acceleration else { return }
                    sampler.sample(x: gravity.x, y: gravity.y, z: gravity.z)
                }
            }
            #endif
        } else if owners.isEmpty, sampler != nil {
            captureGeneration &+= 1
            motion.stopDeviceMotionUpdates(); motion.stopAccelerometerUpdates()
            sampler?.queue.cancelAllOperations(); sampler = nil
            tagCheck?.cancel(); tagCheck = nil; tagDeadline = nil; disagreement = nil
            #if targetEnvironment(simulator)
            NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            #endif
        }
    }

    #if targetEnvironment(simulator)
    @objc private func deviceTurned() {
        let degrees: Double?
        switch UIDevice.current.orientation {
        case .portrait: degrees = 0
        case .landscapeLeft: degrees = 90
        case .landscapeRight: degrees = 270
        case .portraitUpsideDown: degrees = 180
        default: degrees = nil
        }
        sampler?.simulated(degrees: degrees)
    }
    #endif

    private func updateTag(interface: CallOrientation) {
        var next = CallOrientationState(interface: interface, tag: state.tag)
        if !owners.isEmpty {
            let now = ProcessInfo.processInfo.systemUptime
            if interface == physical {
                disagreement = nil
            } else if !allowsLandscape {
                // This picture is already physically corrected. Reacquiring
                // landscape (a quick expand) must not reintroduce a grace
                // period that would briefly send it sideways again.
                disagreement = (interface, physical, now - 0.6)
            } else if disagreement?.interface != interface || disagreement?.physical != physical {
                disagreement = (interface, physical, now)
            }
            next.tag = CallOrientationPolicy.tag(interface: interface, physical: physical,
                disagreeingSince: disagreement?.since, now: now, allowsLandscape: allowsLandscape)
            let deadline = allowsLandscape ? disagreement.map { $0.since + 0.6 } : nil
            scheduleTagCheck(deadline.flatMap { $0 > now ? $0 : nil })
        }
        frames.write(next.tag)
        if next != state { state = next }
    }

    /// Exactly one pending check for a disagreement; layout passes cannot
    /// restart it. Cancel it as soon as capture stops or the mask is portrait.
    private func scheduleTagCheck(_ deadline: TimeInterval?) {
        guard deadline != tagDeadline else { return }
        tagCheck?.cancel(); tagCheck = nil; tagDeadline = deadline
        guard let deadline else { return }
        let delay = max(0, deadline - ProcessInfo.processInfo.systemUptime)
        tagCheck = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
            guard let self, !self.owners.isEmpty else { return }
            self.tagDeadline = nil; self.tagCheck = nil
            self.updateTag(interface: self.state.interface)
        }
    }
}
#endif
