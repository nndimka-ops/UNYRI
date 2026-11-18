"""Regression fixtures for scalar layout portability and call rotation ownership.

Compiles production declarations, replacing only UIKit/Combine boundaries.
Does not claim native rotation, rendering, rotation-lock or camera validation.
"""
import argparse
import os
import plistlib
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument("--generate-only", action="store_true", help="Check wiring and emit Swift fixtures without compiling")
parser.add_argument("--swiftc", default=shutil.which("swiftc") or "swiftc")
args = parser.parse_args()
OUT = ROOT / "runtime-logs/call-orientation-regressions"
OUT.mkdir(parents=True, exist_ok=True)


def declaration(source, marker):
    start = source.index(marker)
    cursor = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[cursor] == "{") - (source[cursor] == "}")
        cursor += 1
    return source[start:cursor]


models = (ROOT / "ios/UNYRI/Calls/CallModels.swift").read_text(encoding="utf-8")
layout = declaration(models, "enum CallLayoutPolicy {")
assert not re.search(r"\b(CGRect|CGPoint|CGSize)\b", layout), "Keep layout values and helpers scalar-only"
# This target intentionally shadows Foundation's geometry with small stubs:
# no CGRect(x:y:width:height:) and no geometry Equatable conformances. The old
# Landscape requires both; this catches that dependency without importing SwiftUI.
layout_source = r'''
import Foundation
struct CGRect {}
struct CGPoint { var x: CGFloat; var y: CGFloat }
struct CGSize { var width: CGFloat; var height: CGFloat }
''' + layout + r'''
@main struct Run {
    static func near(_ actual: CGFloat, _ expected: CGFloat) { precondition(abs(actual - expected) < 0.00001) }
    static func main() {
        let phones: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
            (667, 375, 105, 6), (734, 372, 104.16, 6), (814, 409, 114.52, 12),
            (852, 393, 110.04, 6), (932, 430, 120.4, 12)
        ]
        for (width, height, avatar, spacing) in phones {
            let p = CallLayoutPolicy.landscape(availableWidth: width, availableHeight: height)
            precondition(p == CallLayoutPolicy.landscape(availableWidth: width, availableHeight: height))
            near(p.avatar, avatar); near(p.controlSpacing, spacing); near(p.control, 64)
            near(p.freeX, 24); near(p.freeY, 64); near(p.freeWidth, width - 48); near(p.freeHeight, height - 176)
            near(p.previewWidth, height * 0.30); near(p.previewHeight, height * 0.30 / (4.0 / 3.0))
            near(p.previewX, width - 12 - p.previewWidth); near(p.previewY, 64)
            let preview = CallLayoutPolicy.previewSize(availableWidth: width, availableHeight: height)
            near(preview.width, p.previewWidth); near(preview.height, p.previewHeight)
            let identityWidth = p.avatar + p.identityGap + p.identityTextWidth
            precondition((width + identityWidth) / 2 + 12 <= p.previewX + 0.00001)
            precondition(p.avatar <= p.freeHeight && 140 <= p.freeHeight)
            precondition(p.freeY + p.freeHeight + 8 <= height - p.controlsHeight)
            precondition(p.previewY + p.previewHeight + 12 <= height - p.controlsHeight)
            precondition(CallLayoutPolicy.failureTopInset(availableWidth: width, availableHeight: height) == 56)
            precondition(CallLayoutPolicy.encryptionColumns(availableWidth: width, availableHeight: height) == 2)
        }
        precondition(CallLayoutPolicy.encryptionColumns(availableWidth: 375, availableHeight: 667) == 1)
        print("PASS: production landscape layout with non-Equatable, minimal CoreGraphics stubs")
    }
}
'''

rotation_source = (ROOT / "ios/UNYRI/UI/Calls/CallScreenRotation.swift").read_text(encoding="utf-8")
# CALL_ORIENTATION_SPEC.md: the owner can be enabled before attachment, audio
# calls also rotate, and the bridge must not depend on appearance forwarding.
assert "override func didMoveToWindow()" in rotation_source
assert "view.appeared" not in rotation_source
assert "view.synchronize()" in rotation_source
screen = (ROOT / "ios/UNYRI/UI/Calls/CallScreen.swift").read_text(encoding="utf-8")
assert "rotationReady && scenePhase != .background && !systemPipPresented" in screen
assert ".onAppear {\n            rotation.beginPresentation()" in screen
assert "while morph.busy" not in screen and "50_000_000" not in screen
assert "morph.whenSettled { rotationReady = true }" in screen
assert "completion?()" in declaration(screen, "final class CallMorphAnchors {")
orientation_source = (ROOT / "ios/UNYRI/Calls/CallDeviceOrientation.swift").read_text(encoding="utf-8")
assert "Timer" not in orientation_source and "RunLoop.main" not in orientation_source
assert "startDeviceMotionUpdates(to: sampler.queue)" in orientation_source
assert "startAccelerometerUpdates(to: sampler.queue)" in orientation_source
assert "interfaceOrientation" not in orientation_source, "UIKit must supply the single orientation pair"
assert "deviceOrientation.state.interface" in screen and "deviceOrientation.state.tag" in screen
assert "rotation.$interfaceOrientation" not in screen
renderers = (ROOT / "ios/UNYRI/UI/Calls/VideoRenderViews.swift").read_text(encoding="utf-8")
assert "var fillsBounds: Bool? = true" in renderers, "Mini window retains fill"
sheet = declaration((ROOT / "ios/UNYRI/UI/Calls/CallScreenParts.swift").read_text(encoding="utf-8"),
                    "struct CallEncryptionSheet:")
assert "GeometryReader" not in sheet, "Portrait sheet must keep its original container"
rooms = (ROOT / "ios/UNYRI/Conferences/ConferenceViews.swift").read_text(encoding="utf-8")
assert "selfViewDelta(interface: .portrait, tag: orientation.state.tag)" in rooms
assert "swap ? geometry.size.height : geometry.size.width" in rooms
presentation = declaration(rotation_source, "private func presentationTransition(in window:")
assert "view.isDescendant(of: controller.view)" in presentation
engine = (ROOT / "ios/UNYRI/WebRTC/WebRTCEngineImpl.swift").read_text(encoding="utf-8")
camera = declaration(engine, "private func startCamera(")
assert camera.index("beginCameraChange()") < camera.index("capturer.startCapture(") < camera.index("setFront(frontFacing)")
assert camera.index("setFront(frontFacing)") < camera.index(".localCameraFacingChanged(")
for name in ("Info.plist", "Info-Alpha.plist", "Info-Release.plist"):
    with (ROOT / "ios/UNYRI" / name).open("rb") as stream:
        info = plistlib.load(stream)
    orientations = info.get("UISupportedInterfaceOrientations~iphone", info["UISupportedInterfaceOrientations"])
    assert set(orientations) == {"UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft",
                                 "UIInterfaceOrientationLandscapeRight"}, name
rotation = declaration(rotation_source, "@MainActor\nfinal class CallScreenRotation:")
orientation = declaration((ROOT / "ios/UNYRI/Calls/CallDeviceOrientation.swift").read_text(encoding="utf-8"),
                          "enum CallOrientation:")
lease_source = r'''
import Foundation
''' + orientation + r'''
protocol ObservableObject: AnyObject {}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}
enum Idiom { case phone, pad }
@MainActor final class UIDevice { static let current = UIDevice(); var userInterfaceIdiom = Idiom.phone }
@MainActor final class UIWindowScene {
    var interfaceOrientation = CallOrientation.portrait
    var owners = Set<UUID>()
    var changes = 0
}
extension CallOrientation { init(interface: CallOrientation) { self = interface } }
@MainActor final class CallDeviceOrientation {
    static let shared = CallDeviceOrientation()
    func updateInterface(_ interface: CallOrientation, allowsLandscape: Bool) {}
}
struct UIInterfaceOrientationMask: OptionSet, Sendable {
    let rawValue: Int
    static let portrait = Self(rawValue: 1)
    static let landscapeLeft = Self(rawValue: 2)
    static let landscapeRight = Self(rawValue: 4)
}
@MainActor enum UnyriMediaOrientation {
    static func mask(for scene: UIWindowScene?) -> UIInterfaceOrientationMask {
        guard let scene, !scene.owners.isEmpty else { return .portrait }
        return [.portrait, .landscapeLeft, .landscapeRight]
    }
    static func set(_ enabled: Bool, token: UUID, scene: UIWindowScene) {
        if enabled { scene.owners.insert(token) } else { scene.owners.remove(token) }
        scene.changes += 1
    }
}
''' + rotation + r'''
@main struct Run {
    @MainActor static func main() async throws {
        let rotation = CallScreenRotation(), first = UIWindowScene(), second = UIWindowScene()
        rotation.beginPresentation()
        rotation.allowRotation(true) // SwiftUI update arrives before a window exists.
        rotation.attach(first)       // No appearance callback is delivered.
        precondition(first.owners.count == 1, "Attaching must apply the retained rotation intent")
        rotation.attach(first); rotation.allowRotation(true)
        precondition(first.changes == 1, "Layout passes cannot repeatedly invalidate the scene")
        rotation.attach(nil)
        precondition(first.owners.isEmpty)
        rotation.attach(second)
        precondition(second.owners.count == 1 && first.owners.isEmpty, "Only the attached scene owns the token")
        rotation.allowRotation(false)
        precondition(second.owners.isEmpty, "Background/PiP/morphing releases the token")
        rotation.attach(first)
        precondition(first.owners.isEmpty, "Attachment alone cannot enable a background cover")
        rotation.allowRotation(true)
        first.interfaceOrientation = .landscapeLeft
        rotation.geometry(CGSize(width: 852, height: 393), transitioning: false)
        var completed = 0
        rotation.beforeLeaving { completed += 1 }
        rotation.beforeLeaving { completed += 10 }
        precondition(first.owners.isEmpty && completed == 0)
        first.interfaceOrientation = .portrait
        rotation.geometry(CGSize(width: 852, height: 393), transitioning: false)
        precondition(completed == 0, "A portrait tag alone cannot uncover landscape geometry")
        rotation.geometry(CGSize(width: 393, height: 852), transitioning: true)
        precondition(completed == 0, "Wait for system transition completion")
        rotation.geometry(CGSize(width: 393, height: 852), transitioning: false)
        precondition(completed == 11, "Concurrent leave requests both complete once")
        rotation.beginPresentation()
        rotation.allowRotation(true)
        precondition(first.owners.count == 1, "A later call can acquire rotation again")
        first.interfaceOrientation = .landscapeRight
        rotation.geometry(CGSize(width: 852, height: 393), transitioning: false)
        rotation.beforeLeaving { completed += 1 }
        precondition(CallScreenRotation.leaveTimeout == 1.5)
        try await Task.sleep(nanoseconds: 1_650_000_000)
        precondition(completed == 12, "Keep the 1.5 second leave fallback, with no stale action replay")
        rotation.beginPresentation(); rotation.allowRotation(true)
        first.interfaceOrientation = .landscapeLeft
        rotation.geometry(CGSize(width: 667, height: 375), transitioning: false)
        let mediaOwner = UUID()
        UnyriMediaOrientation.set(true, token: mediaOwner, scene: first)
        rotation.beforeLeaving { completed += 1 }
        precondition(completed == 13, "Another landscape owner: no portrait completion will arrive, finish immediately")
        UnyriMediaOrientation.set(false, token: mediaOwner, scene: first)
        rotation.disappeared()
        rotation.allowRotation(true) // A late layout callback from the departing bridge.
        rotation.attach(second)
        precondition(first.owners.isEmpty && second.owners.isEmpty, "A disappeared cover cannot reacquire its lease")
        print("PASS: production rotation coordinator: attachment-before-appearance, ownership, background release, portrait ordering, coalesced requests, another owner and 1.5s fallback")
    }
}
'''

sampler = declaration(orientation_source, "private final class CallMotionSampler:")
device = declaration(orientation_source, "@MainActor\nfinal class CallDeviceOrientation:")
# Compile the real queue, capture ownership and one-shot scheduler, not a copy
# of their logic. Only CoreMotion and Combine are replaced at the boundary.
motion_source = orientation_source.split("#if canImport(UIKit)", 1)[0] + r'''
protocol ObservableObject: AnyObject {}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}
struct Gravity: Sendable { let x: Double; let y: Double; let z: Double }
struct MotionData: Sendable { let gravity: Gravity }
struct AccelerationData: Sendable { let acceleration: Gravity }
@MainActor final class CMMotionManager {
    static var latest: CMMotionManager?
    var isDeviceMotionAvailable = true
    var isAccelerometerAvailable = true
    var deviceMotionUpdateInterval = 0.0
    var accelerometerUpdateInterval = 0.0
    var starts = 0
    var running = false
    private var queue: OperationQueue?
    private var handler: (@Sendable (MotionData?, Error?) -> Void)?
    init() { Self.latest = self }
    func startDeviceMotionUpdates(to queue: OperationQueue,
                                  withHandler handler: @escaping @Sendable (MotionData?, Error?) -> Void) {
        self.queue = queue; self.handler = handler; starts += 1; running = true
    }
    func startAccelerometerUpdates(to queue: OperationQueue,
                                   withHandler handler: @escaping @Sendable (AccelerationData?, Error?) -> Void) {
        preconditionFailure("This fixture uses device motion")
    }
    func stopDeviceMotionUpdates() { running = false }
    func stopAccelerometerUpdates() {}
    // Intentionally retain a callback after stop to exercise stale delivery.
    func emit(_ gravity: Gravity) {
        guard let queue, let handler else { return }
        queue.addOperation {
            precondition(!Thread.isMainThread, "Sensor/hysteresis work stays off main")
            handler(MotionData(gravity: gravity), nil)
        }
    }
}
''' + sampler + "\n" + device + r'''
@main struct Run {
    @MainActor static func main() async throws {
        let orientation = CallDeviceOrientation.shared
        let sensor = CMMotionManager.latest!
        let owner = UUID(), other = UUID()
        orientation.updateInterface(.landscapeLeft, allowsLandscape: true)
        precondition(sensor.starts == 0, "Audio/interface changes never start sensing")
        orientation.updateInterface(.portrait, allowsLandscape: false)
        orientation.capturing(true, owner: owner)
        orientation.capturing(true, owner: owner)
        precondition(sensor.starts == 1 && sensor.deviceMotionUpdateInterval == 0.1)
        let left = Gravity(x: -1, y: 0, z: 0)
        sensor.emit(left)
        try await Task.sleep(nanoseconds: 350_000_000)
        sensor.emit(left)
        try await Task.sleep(nanoseconds: 100_000_000)
        precondition(orientation.state == CallOrientationState(interface: .portrait, tag: .landscapeLeft),
                     "Portrait-only mask commits physical immediately after hysteresis")
        precondition(orientation.frames.read() == orientation.state.tag)
        orientation.updateInterface(.landscapeLeft, allowsLandscape: true)
        let upsideDown = Gravity(x: 0, y: 1, z: 0)
        sensor.emit(upsideDown)
        try await Task.sleep(nanoseconds: 350_000_000)
        sensor.emit(upsideDown)
        try await Task.sleep(nanoseconds: 100_000_000)
        precondition(orientation.state.tag == .landscapeLeft, "Allowed turn keeps its grace period")
        // No further sensor events: the single check must finish the grace.
        try await Task.sleep(nanoseconds: 650_000_000)
        precondition(orientation.state == CallOrientationState(interface: .landscapeLeft, tag: .portraitUpsideDown))
        orientation.updateInterface(.landscapeRight, allowsLandscape: true)
        precondition(orientation.state.tag == .landscapeRight)
        orientation.updateInterface(.portrait, allowsLandscape: false)
        precondition(orientation.state.tag == .portraitUpsideDown, "Mask release cancels grace immediately")
        orientation.updateInterface(.portrait, allowsLandscape: true)
        precondition(orientation.state.tag == .portraitUpsideDown, "Quick expansion cannot restart grace on an already corrected picture")
        orientation.updateInterface(.portrait, allowsLandscape: false)
        orientation.capturing(false, owner: owner)
        precondition(!sensor.running)
        sensor.emit(left)
        try await Task.sleep(nanoseconds: 350_000_000)
        sensor.emit(left)
        try await Task.sleep(nanoseconds: 650_000_000)
        precondition(orientation.state.tag == .portraitUpsideDown, "Stopped capture rejects stale callbacks and deadline")
        orientation.capturing(true, owner: owner)
        orientation.capturing(true, owner: other)
        orientation.capturing(false, owner: owner)
        precondition(sensor.running && sensor.starts == 2, "One sensor shared by capture leases")
        orientation.capturing(false, owner: other)
        precondition(!sensor.running, "Last camera lease stops sensing")
        print("PASS: production off-main hysteresis, capture leases, atomic pair, mask release and one-shot grace")
    }
}
'''

presentation_source = r'''
import Foundation
@MainActor final class UIView {
    weak var superview: UIView?
    func isDescendant(of target: UIView) -> Bool {
        var current: UIView? = self
        while let view = current {
            if view === target { return true }
            current = view.superview
        }
        return false
    }
}
@MainActor final class UIViewControllerTransitionCoordinator {}
@MainActor class UIViewController {
    let view = UIView()
    var parent: UIViewController?
    var presentedViewController: UIViewController?
    var isBeingPresented = false
    var transitionCoordinator: UIViewControllerTransitionCoordinator?
}
@MainActor final class UIWindow { var rootViewController: UIViewController? }
@MainActor final class Probe: UIViewController {
''' + presentation.replace("private func", "func", 1) + r'''
}
@main struct Run {
    @MainActor static func main() {
        let root = UIViewController(), cover = UIViewController(), sheet = UIViewController(), probe = Probe()
        let window = UIWindow()
        window.rootViewController = root
        root.presentedViewController = cover; cover.presentedViewController = sheet
        probe.view.superview = cover.view
        sheet.isBeingPresented = true; sheet.transitionCoordinator = UIViewControllerTransitionCoordinator()
        precondition(probe.presentationTransition(in: window) == nil, "A sheet/alert above the cover cannot drop rotation")
        cover.isBeingPresented = true; cover.transitionCoordinator = UIViewControllerTransitionCoordinator()
        precondition(probe.presentationTransition(in: window) === cover.transitionCoordinator, "Initial cover presentation keeps portrait")
        probe.parent = cover
        precondition(probe.presentationTransition(in: window) === cover.transitionCoordinator, "Containment path sees own cover")
        cover.isBeingPresented = false
        precondition(probe.presentationTransition(in: window) == nil, "Containment ignores the sheet's transition too")
        print("PASS: production presentation gate ignores sheets/alerts above its own cover")
    }
}
'''

morph = declaration(screen, "final class CallMorphAnchors {")
morph_source = r'''
import Foundation
@MainActor final class MorphCompletion {
    private var settledCallback: (() -> Void)?
''' + declaration(morph, "private(set) var busy = false") + "\n" + declaration(morph, "func whenSettled(") + "\n" + declaration(morph, "func cancelSettledCallback()") + r'''
    func begin() { busy = true }
    func finish() { busy = false }
}
@main struct Run {
    @MainActor static func main() {
        let morph = MorphCompletion()
        var count = 0
        morph.whenSettled { count += 1 }
        precondition(count == 1, "Initial cover has no morph to wait for")
        morph.begin(); morph.whenSettled { count += 1 }
        precondition(count == 1, "Expansion waits for ghost removal")
        morph.finish(); morph.finish()
        precondition(count == 2, "Animator and safety completion cannot both call back")
        morph.begin(); morph.whenSettled { count += 1 }; morph.cancelSettledCallback(); morph.finish()
        precondition(count == 2, "Departing cover cancels its callback")
        print("PASS: production morph completion callback, no polling")
    }
}
'''

media = (ROOT / "ios/UNYRI/UI/Chats/UnyriMediaViewer.swift").read_text(encoding="utf-8")
mask = declaration(media, "@MainActor\nenum UnyriMediaOrientation {")
mask_source = r'''
import Foundation
struct UIInterfaceOrientationMask: OptionSet, Sendable {
    let rawValue: Int
    static let portrait = Self(rawValue: 1)
    static let landscapeLeft = Self(rawValue: 2)
    static let landscapeRight = Self(rawValue: 4)
    static let all = Self(rawValue: 15)
}
enum Idiom { case phone, pad }
@MainActor final class UIDevice { static let current = UIDevice(); var userInterfaceIdiom = Idiom.phone }
@MainActor final class UIViewController {
    var children: [UIViewController] = []
    var presentedViewController: UIViewController?
    var invalidations = 0
    func setNeedsUpdateOfSupportedInterfaceOrientations() { invalidations += 1 }
}
@MainActor final class UIWindow {
    let rootViewController: UIViewController?
    init(_ root: UIViewController) { rootViewController = root }
}
enum Preferences { case iOS(interfaceOrientations: UIInterfaceOrientationMask) }
@MainActor final class UIWindowScene {
    struct Session { let persistentIdentifier = UUID().uuidString }
    let session = Session()
    var windows: [UIWindow] = []
    var requests: [UIInterfaceOrientationMask] = []
    func requestGeometryUpdate(_ preference: Preferences, errorHandler: (Error) -> Void) {
        if case .iOS(let mask) = preference { requests.append(mask) }
    }
}
''' + mask + r'''
@main struct Run {
    @MainActor static func main() {
        let scene = UIWindowScene(), other = UIWindowScene()
        let root = UIViewController(), child = UIViewController(), cover = UIViewController()
        root.children = [child]; scene.windows = [UIWindow(root), UIWindow(root)]
        let media = UUID(), call = UUID()
        precondition(UnyriMediaOrientation.mask(for: scene) == .portrait)
        UnyriMediaOrientation.set(true, token: media, scene: scene)
        precondition(root.invalidations == 1 && child.invalidations == 1, "Visit controllers once, including children")
        root.presentedViewController = cover
        UnyriMediaOrientation.set(true, token: call, scene: scene)
        precondition(cover.invalidations == 1 && scene.requests.count == 2,
                     "A newly mounted cover needs invalidation even when the union mask is unchanged")
        precondition(UnyriMediaOrientation.mask(for: other) == .portrait, "A lease cannot unlock another scene")
        UnyriMediaOrientation.set(true, token: call, scene: scene)
        precondition(scene.requests.count == 2, "Repeated layout must not issue geometry requests")
        UnyriMediaOrientation.set(false, token: call, scene: scene)
        precondition(UnyriMediaOrientation.mask(for: scene) != .portrait)
        UnyriMediaOrientation.set(false, token: media, scene: scene)
        precondition(UnyriMediaOrientation.mask(for: scene) == .portrait && scene.requests.last == UIInterfaceOrientationMask.portrait)
        print("PASS: production scene rotation mask: all controller invalidation, same-mask new cover, idempotence, scene isolation and portrait release")
    }
}
'''

for name, contents in [
    ("Layout", layout_source),
    ("Lease", lease_source),
    ("Motion", motion_source),
    ("Presentation", presentation_source),
    ("Morph", morph_source),
    ("Mask", mask_source),
]:
    source = OUT / (name + ".swift")
    source.write_text(contents, encoding="utf-8")
    binary = OUT / (name + (".exe" if os.name == "nt" else ""))
    if args.generate_only:
        print("GENERATED (not compiled):", source.relative_to(ROOT))
        continue
    subprocess.run([args.swiftc, "-swift-version", "6", "-strict-concurrency=complete", "-parse-as-library",
                    str(source), "-o", str(binary)], check=True, cwd=ROOT)
    subprocess.run([str(binary)], check=True, timeout=15, cwd=ROOT)
