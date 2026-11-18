"""Run production torch event emission and controller handling without Apple SDKs.

Camera hardware and KVO delivery still require an iOS device. The fixtures only
replace that boundary; the event, dispatch branches and sync method are extracted.
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
controller = (root / "ios/UNYRI/Calls/CallController.swift").read_text(encoding="utf-8")
engine = (root / "ios/UNYRI/WebRTC/WebRTCEngineImpl.swift").read_text(encoding="utf-8")
events = (root / "ios/UNYRI/Calls/WebRTCEngine.swift").read_text(encoding="utf-8")


def member(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private func ", "func ")


handler = member(controller, "private func handleEngine(_ event: WebRtcEvent)")
guard = handler[handler.index("guard !isShutDown"):handler.index("switch event")]
branches = handler[handler.index("case .localCameraFacingChanged("):handler.index("case .audioRouteChanged(")]
source = r'''
import Foundation
enum PeerConnectionState: Equatable { case connected }
struct AudioRoute: Equatable {}
__EVENTS__
@MainActor final class EngineProbe {
    var cameraGeneration: UInt64 = 7
    var available = false
    var reads = 0
    var writes: [Bool] = []
    func isCurrentCameraGeneration(_ value: UInt64) -> Bool { value == cameraGeneration }
    func localCameraHasTorch() -> Bool { reads += 1; return available }
    func setTorch(_ value: Bool) -> Bool { writes.append(value); return value && available }
}
@MainActor final class ControllerProbe {
    enum Event { case localCameraFacingChanged(Bool) }
    let engine = EngineProbe()
    var torchAvailable = false
    var torchOn = false
    var isShutDown = false
    var generation: UInt64 = 1
    var apiGeneration: UInt64 = 1
    var facingChanges: [Bool] = []
    func apply(_ event: Event) {
        if case .localCameraFacingChanged(let value) = event { facingChanges.append(value) }
    }
    __SYNC__
    func handleEngine(_ event: WebRtcEvent) {
        __GUARD__
        switch event {
        __BRANCHES__
        default: break
        }
    }
}
@MainActor final class EventSink {
    var sent: [WebRtcEvent] = []
    func send(_ event: WebRtcEvent) { sent.append(event) }
}
@MainActor final class EngineEmitter {
    var captureRunning = true
    var cameraStartPending = false
    var activeCameraDevice: Int? = 1
    var cameraGeneration: UInt64 = 7
    let events = EventSink()
    __EMIT__
}
@main struct Run {
    @MainActor static func main() {
        var count = 0
        func check(_ value: Bool, _ message: String) { precondition(value, message); count += 1 }
        let p = ControllerProbe()
        p.engine.available = true
        p.handleEngine(.localTorchChanged(cameraGeneration: 7))
        check(p.torchAvailable && !p.torchOn && p.engine.reads == 1,
              "a current torch event refreshes availability without lighting it")
        check(p.facingChanges.isEmpty, "torch events never enter the facing reducer")
        p.torchOn = true
        p.engine.available = false
        p.handleEngine(.localTorchChanged(cameraGeneration: 7))
        check(!p.torchAvailable && !p.torchOn && p.engine.writes.isEmpty,
              "interruption or heat clears the lit state without attempting to relight")
        p.engine.available = true
        p.handleEngine(.localTorchChanged(cameraGeneration: 7))
        check(p.torchAvailable && !p.torchOn && p.engine.writes.isEmpty,
              "availability returning leaves the torch dark")
        p.torchOn = true
        p.handleEngine(.localTorchChanged(cameraGeneration: 6))
        check(p.engine.reads == 3 && p.torchOn && p.engine.writes.isEmpty,
              "an old camera's torch event cannot touch its successor")
        p.isShutDown = true
        p.handleEngine(.localTorchChanged(cameraGeneration: 7))
        p.isShutDown = false
        p.apiGeneration = 2
        p.handleEngine(.localTorchChanged(cameraGeneration: 7))
        check(p.engine.reads == 3 && p.engine.writes.isEmpty,
              "shutdown and stale account events are ignored")
        p.apiGeneration = 1
        p.handleEngine(.localCameraFacingChanged(cameraGeneration: 7, isFrontFacing: false))
        check(p.facingChanges == [false] && p.engine.writes == [true],
              "a real facing change still reduces facing and synchronizes its torch")

        let e = EngineEmitter()
        e.torchChanged()
        check(e.events.sent == [.localTorchChanged(cameraGeneration: 7)],
              "the engine emits only the dedicated torch event")
        e.cameraStartPending = true; e.torchChanged()
        e.cameraStartPending = false; e.captureRunning = false; e.torchChanged()
        e.captureRunning = true; e.activeCameraDevice = nil; e.torchChanged()
        check(e.events.sent.count == 1, "a pending, stopped or missing camera emits nothing")
        e.activeCameraDevice = 2; e.cameraGeneration = 8; e.torchChanged()
        check(e.events.sent.last == .localTorchChanged(cameraGeneration: 8),
              "a new camera's event carries its own generation")
        print("PASS: \(count) production torch event contracts")
    }
}
'''
for key, value in {
    "__EVENTS__": member(events, "enum WebRtcEvent: Equatable"),
    "__SYNC__": member(controller, "private func syncTorch()"),
    "__GUARD__": guard,
    "__BRANCHES__": branches,
    "__EMIT__": member(engine, "private func torchChanged()"),
}.items():
    source = source.replace(key, value)

with tempfile.TemporaryDirectory(prefix="unyri-call-torch-contracts-") as directory:
    folder = Path(directory)
    main = folder / "Runner.swift"
    executable = folder / ("runner.exe" if os.name == "nt" else "runner")
    main.write_text(source, encoding="utf-8")
    subprocess.run([os.environ.get("SWIFTC", "swiftc"), "-parse-as-library", str(main), "-o", str(executable)],
                   check=True, timeout=90)
    subprocess.run([str(executable)], check=True, timeout=30)
