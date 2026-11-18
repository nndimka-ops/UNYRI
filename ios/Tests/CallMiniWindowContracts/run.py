"""Execute the production call-window and audio-output policies without SwiftUI.

Foundation supplies geometry on Windows; only CGVector and the engine's audio
route enum need stubs. Gesture wiring checks supplement the tracker contracts;
hidden-work checks keep hidden renderers and timers paused.
"""
from pathlib import Path
import subprocess
import tempfile


root = Path(__file__).resolve().parents[3]
screen = (root / "ios/UNYRI/UI/Calls/CallScreen.swift").read_text(encoding="utf-8")


def declaration(marker):
    start = screen.index(marker)
    opening = screen.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (screen[end] == "{") - (screen[end] == "}")
        end += 1
    return screen[start:end]


policies = "\n".join(declaration(marker) for marker in (
    "enum CallAudioOutputPolicy {",
    "struct CallMiniWindowPlacement: Equatable {",
    "enum CallMiniWindowPolicy {",
    "final class CallMiniWindowDragTracker {",
    "final class CallMiniWindowTouchAreas {",
))

# Both the normal gesture end and GestureState cancellation fallback must record
# the pinch's end; a delayed first drag event uses its touch timestamp.
assert screen.count("tracker.endPinch(at: Date())") == 2
assert "tracker.begin(at: value.time)" in screen
assert "tracker.pinching = false" not in screen

# Nothing draws or ticks while hidden (0.4.15 review): a tucked window and the
# backdrop behind a camera that is off pause the remote Metal view, and the
# status capsule's timer stops while the chrome hides it, and its still text
# and live timeline swap without a crossfade. Every resize asks the area which
# steps differ, so a step that changes nothing never buzzes.
views = (root / "ios/UNYRI/UI/Calls/VideoRenderViews.swift").read_text(encoding="utf-8")
parts = (root / "ios/UNYRI/UI/Calls/CallScreenParts.swift").read_text(encoding="utf-8")
wiring = [
    "view.isEnabled = active" in views,
    "if uiView.isEnabled != active { uiView.isEnabled = active }" in views,
    "picture(width: layout.window.width, drawing: !tucked)" in screen,
    "RemoteVideoView(active: drawing && call.remoteVideoState != .off)" in screen,
    "active: live || video.remoteVideoState == .waiting," in screen,
    "paused: !visible)" in parts,
    ".animation(nil, value: paused)" in parts,
    "CallMiniWindowPolicy.next(placement.size, area: layout.area), area: layout.area)" in screen,
    screen.count("current: placement.size, area:") == 2,
]
assert all(wiring), wiring

stubs = r'''
#if canImport(CoreGraphics)
import CoreGraphics
#endif
enum AudioRouteKind: CaseIterable { case earpiece, speaker, bluetooth, wiredHeadset }
#if !canImport(CoreGraphics)
struct CGVector {
    var dx: CGFloat
    var dy: CGFloat
    static let zero = CGVector(dx: 0, dy: 0)
}
#endif
'''

tests = r'''
@main struct Run {
    static var count = 0
    static func check(_ value: Bool, _ message: String) {
        precondition(value, message)
        count += 1
    }
    static func near(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) < 0.001 }
    static func date(_ seconds: Double) -> Date { Date(timeIntervalSinceReferenceDate: seconds) }

    static func main() {
        typealias P = CallMiniWindowPolicy

        // Route, audio/iPhone, video/iPhone, audio/iPad, video/iPad.
        let highlights: [(AudioRouteKind, [Bool])] = [
            (.earpiece, [false, true, false, true]),
            (.speaker, [true, false, false, false]),
            (.bluetooth, [false, false, false, false]),
            (.wiredHeadset, [false, false, false, false]),
        ]
        let calls = [(false, true), (true, true), (false, false), (true, false)]
        for (route, expected) in highlights {
            for (index, call) in calls.enumerated() {
                check(CallAudioOutputPolicy.highlighted(route, video: call.0, hasReceiver: call.1) == expected[index],
                      "Audio output highlight matrix: \(route), \(call)")
            }
        }
        for (index, call) in calls.enumerated() {
            let expected: [AudioRouteKind] = [.earpiece, .speaker, .speaker, .speaker]
            check(CallAudioOutputPolicy.assumed(video: call.0, hasReceiver: call.1) == expected[index],
                  "The assumed built-in route fits call type and available receiver")
        }

        // A landscape keyboard yields the top clearance before it covers the
        // window. Insets and explicit keyboard overlap describe the same space.
        let short = CGSize(width: 852, height: 184)
        let keyboard = P.area(container: short, keyboardOverlap: 0)
        check(keyboard == P.area(container: CGSize(width: 852, height: 372), keyboardOverlap: 188),
              "A resized container and a keyboard overlap yield the same free area")
        check(keyboard == CGRect(x: 12, y: 12, width: 828, height: 90),
              "Landscape keyboard gives way to a 90 pt free height at y 12")
        for size in CallMiniWindowPlacement.Size.allCases {
            let window = P.windowSize(size, area: keyboard)
            check(window == CGSize(width: 64, height: 90), "Each size fits the landscape keyboard space")
            for corner in [CallMiniWindowPlacement.Corner.topLeading, .topTrailing, .bottomLeading, .bottomTrailing] {
                let origin = P.origin(corner, window: window, area: keyboard)
                check(near(origin.y, 12) && near(origin.y + window.height, 102),
                      "Every corner keeps the window at y 12...102 above the keyboard")
                check(origin.x >= keyboard.minX && origin.x + window.width <= keyboard.maxX,
                      "Landscape keyboard keeps every corner inside the horizontal margins")
            }
        }

        let portrait = P.area(container: CGSize(width: 393, height: 759), keyboardOverlap: 0)
        check(portrait == CGRect(x: 12, y: 112, width: 369, height: 547),
              "Portrait keeps the call bar and composer clearances")
        let portraitWindow = P.windowSize(.medium, area: portrait)
        check(portraitWindow == CGSize(width: 120, height: 168), "Portrait keeps the medium window size")
        check(P.origin(.bottomTrailing, window: portraitWindow, area: portrait) == CGPoint(x: 261, y: 491),
              "Portrait keeps its bottom-trailing resting position")
        let landscape = P.area(container: CGSize(width: 852, height: 372), keyboardOverlap: 0)
        check(landscape == CGRect(x: 12, y: 112, width: 828, height: 160),
              "Landscape without a keyboard keeps both clearances")
        check(P.windowSize(.medium, area: landscape) == CGSize(width: 114, height: 160),
              "Landscape without keyboard keeps its existing fitted size")
        let empty = P.area(container: .zero, keyboardOverlap: 100)
        check(empty.height == 0, "An exhausted container has zero free height")
        let emptyWindow = P.windowSize(.medium, area: empty)
        let emptyRange = P.verticalRange(window: emptyWindow, area: empty)
        check(emptyWindow == CGSize(width: 64, height: 90), "The zero-height area retains the minimum window")
        check(emptyRange.lowerBound == emptyRange.upperBound && emptyRange.upperBound == empty.maxY - 90,
              "Zero height gives way upward without an invalid closed range")
        check(P.origin(.bottomTrailing, window: emptyWindow, area: empty).y == emptyRange.upperBound,
              "An exhausted area still produces a valid resting origin")
        check(P.area(container: CGSize(width: 393, height: 759), keyboardOverlap: -10) == portrait,
              "Negative keyboard overlap never adds free height")

        check(P.rest(origin: CGPoint(x: 270, y: 480), velocity: .zero, window: portraitWindow,
                     container: CGSize(width: 393, height: 759), area: portrait) == .corner(.bottomTrailing),
              "An ordinary release lands at its nearest corner")
        if case .tucked(let leading, _) = P.rest(origin: CGPoint(x: 20, y: 120),
                                                 velocity: CGVector(dx: -1200, dy: 0), window: portraitWindow,
                                                 container: CGSize(width: 393, height: 759), area: portrait) {
            check(leading, "A throw beyond the leading edge tucks on that side")
        } else { preconditionFailure("A throw beyond the edge must tuck") }
        check(P.next(.small, area: portrait) == .medium && P.next(.medium, area: portrait) == .large
                && P.next(.large, area: portrait) == .small,
              "Double taps cycle all three sizes where each fits its own size")
        // Landscape without a keyboard fits medium and large to the same 114 pt:
        // a double tap never lands on a step that changes nothing.
        check(P.windowSize(.large, area: landscape) == P.windowSize(.medium, area: landscape),
              "A short area clamps medium and large to one size")
        check(P.next(.small, area: landscape) == .medium && P.next(.medium, area: landscape) == .small
                && P.next(.large, area: landscape) == .small,
              "Double taps skip a step that fits to the size shown")
        for size in CallMiniWindowPlacement.Size.allCases {
            check(P.next(size, area: keyboard) == size,
                  "With every step fitting the same size a double tap keeps the step")
        }
        // A pinch picks by fitted size; steps of the same size count as one, the current first.
        check(P.size(pinchedWidth: 120 * 1.4, current: .medium, area: portrait) == .large,
              "A portrait pinch out from medium lands on large")
        check(P.size(pinchedWidth: 120 * 0.7, current: .medium, area: portrait) == .small,
              "A portrait pinch in from medium lands on small")
        check(P.size(pinchedWidth: 114 * 1.5, current: .medium, area: landscape) == .medium,
              "A landscape pinch out from medium keeps medium: large would look the same")
        check(P.size(pinchedWidth: 114 * 1.5, current: .large, area: landscape) == .large,
              "A landscape pinch out from large keeps large")
        check(P.size(pinchedWidth: 88 * 1.3, current: .small, area: landscape) == .medium,
              "A landscape pinch out from small lands on the first larger fitted size")
        for size in CallMiniWindowPlacement.Size.allCases {
            check(P.size(pinchedWidth: 200, current: size, area: keyboard) == size
                    && P.size(pinchedWidth: 10, current: size, area: keyboard) == size,
                  "With every step fitting the same size a pinch keeps the step")
        }

        let dragFirst = CallMiniWindowDragTracker()
        dragFirst.begin(at: date(1))
        check(dragFirst.dragging && !dragFirst.pinched, "A fresh drag can move the window")
        dragFirst.add(CGPoint(x: 10, y: 10), at: date(1))
        dragFirst.beginPinch()
        check(dragFirst.pinching && dragFirst.pinched, "A pinch takes over a running drag")
        dragFirst.endPinch(at: date(2))
        check(!dragFirst.pinching && dragFirst.pinched, "The pinch guard survives pinch end")
        dragFirst.dragging = false
        dragFirst.begin(at: date(2.1))
        check(dragFirst.pinched && dragFirst.samples.isEmpty, "The same sequence cannot restart as a throw")

        let pinchFirst = CallMiniWindowDragTracker()
        pinchFirst.beginPinch()
        pinchFirst.begin(at: date(3))
        check(pinchFirst.pinched, "A drag first recognized during a pinch only resizes")
        pinchFirst.endPinch(at: date(3.5))
        pinchFirst.dragging = false
        pinchFirst.begin(at: date(3.6))
        check(pinchFirst.pinched, "A drag first recognized after pinch end still only resizes")
        pinchFirst.dragging = false
        pinchFirst.begin(at: date(3.8))
        check(pinchFirst.pinched, "The guard lasts through the 300 ms sequence boundary")
        pinchFirst.dragging = false
        pinchFirst.begin(at: date(3.801))
        check(!pinchFirst.pinched, "A later independent drag moves normally")

        let delayedDrag = CallMiniWindowDragTracker()
        delayedDrag.beginPinch()
        delayedDrag.endPinch(at: date(10))
        delayedDrag.begin(at: date(10.01))
        check(delayedDrag.pinched, "Pinch, pinch end, then first drag cannot move or tuck the window")
        delayedDrag.add(.zero, at: date(10.01))
        delayedDrag.add(CGPoint(x: 10, y: 20), at: date(10.06))
        let velocity = P.velocity(delayedDrag.samples)
        check(near(velocity.dx, 200) && near(velocity.dy, 400), "Recent touch samples retain their time units")
        delayedDrag.add(CGPoint(x: 20, y: 30), at: date(10.3))
        check(delayedDrag.samples.count == 1 && P.velocity(delayedDrag.samples).dx == 0,
              "Old touches cannot become a release throw after a pause")

        let touches = CallMiniWindowTouchAreas()
        let stale = NSObject(), current = NSObject()
        touches.update(CGRect(x: 10, y: 20, width: 100, height: 80), owner: stale)
        check(touches.contains(CGPoint(x: 20, y: 30)), "The current window owns its touch target")
        touches.update(CGRect(x: 200, y: 300, width: 100, height: 80), owner: current)
        touches.clear(owner: stale)
        check(touches.contains(CGPoint(x: 210, y: 310)) && !touches.contains(CGPoint(x: 20, y: 30)),
              "A stale owner's clear cannot remove its successor's touch target")
        touches.clear(owner: current)
        check(touches.frame.isNull && !touches.contains(CGPoint(x: 210, y: 310)),
              "The current owner's clear releases its touch target")
        var departed: NSObject? = NSObject()
        touches.update(CGRect(x: 0, y: 0, width: 40, height: 40), owner: departed!)
        departed = nil
        check(!touches.contains(CGPoint(x: 20, y: 20)), "A departed owner cannot intercept touches without a clear")

        print("PASS: \(count) call mini-window/audio-output contracts, 3 gesture and 9 hidden-work/resize wiring checks")
    }
}
'''

with tempfile.TemporaryDirectory(prefix="unyri-call-mini-window-contracts-") as directory:
    folder = Path(directory)
    source = folder / "Runner.swift"
    executable = folder / "runner.exe"
    source.write_text("import Foundation\n" + stubs + "\n" + policies + "\n" + tests, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(source), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
