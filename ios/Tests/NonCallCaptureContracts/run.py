"""Execute real capture registry and picker-drain methods with native seams."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
policy = ROOT / "ios/UNYRI/Support/NonCallCaptureOwnership.swift"
text = policy.read_text(encoding="utf-8")
native = text[text.index("@MainActor\nfinal class NativeCameraPickerLease"):text.rindex("#endif")]
harness = r'''
import Foundation
@MainActor final class UIImagePickerController {
    enum Source { case camera, photoLibrary }
    var sourceType = Source.photoLibrary
    var delegate: NSObject? = NSObject()
    var presentingViewController: NSObject? = NSObject()
    var dismisses = 0
    var completion: (() -> Void)?
    func dismiss(animated: Bool, completion: (() -> Void)?) { dismisses += 1; self.completion = completion }
    func finishDismiss() { presentingViewController = nil; completion?() }
}
__NATIVE__
@main struct Checks {
    @MainActor static func main() async {
        var checks = 0, generation: UInt64 = 1, callBusy = false
        func check(_ condition: @autoclosure () -> Bool, _ label: String) { precondition(condition(), label); checks += 1 }
        NonCallCaptureOwnership.currentGeneration = { generation }
        NonCallCaptureOwnership.callsBusy = { callBusy }
        callBusy = true
        for kind in [NonCallCaptureOwnership.Kind.camera, .preview, .voice] {
            check(NonCallCaptureOwnership.acquire(kind, generation: 1, stop: { _ in }) == nil,
                  "a private/group call blocks every non-call capture entry before permission/native startup")
        }
        callBusy = false
        check(NonCallCaptureOwnership.acquire(.camera, generation: 2, stop: { _ in }) == nil,
              "a stale account cannot reserve new hardware")
        var stops = 0
        let pending = NonCallCaptureOwnership.acquire(.camera, generation: 1, stop: { _ in stops += 1 })!
        check(NonCallCaptureOwnership.isCurrent(pending) && NonCallCaptureOwnership.isBusy,
              "pending permission already reserves outgoing/group capture admission")
        check(NonCallCaptureOwnership.acquire(.voice, generation: 1, stop: { _ in }) == nil,
              "camera and microphone claims cannot overlap")
        NonCallCaptureOwnership.interrupt(); NonCallCaptureOwnership.interrupt()
        check(stops == 1 && !pending.permit.isValid && !NonCallCaptureOwnership.isCurrent(pending),
              "incoming invalidates pending/native callbacks synchronously and requests stop once")
        check(NonCallCaptureOwnership.isBusy, "incoming may ring while actual camera drain retains hardware ownership")
        var drained = false
        let waiter = Task { try! await NonCallCaptureOwnership.waitForRelease(); drained = true }
        try! await Task.sleep(nanoseconds: 45_000_000)
        check(!drained, "Answer cannot proceed merely because stop was requested")
        NonCallCaptureOwnership.release(pending); await waiter.value
        check(drained && !NonCallCaptureOwnership.isBusy, "actual native stop completion releases the pending Answer")
        check(!NonCallCaptureOwnership.isCurrent(pending), "late permission result cannot activate a canceled camera")
        let next = NonCallCaptureOwnership.acquire(.voice, generation: 1, stop: { _ in })!
        NonCallCaptureOwnership.release(pending); NonCallCaptureOwnership.stop(pending)
        check(NonCallCaptureOwnership.isCurrent(next), "old release/stop callbacks cannot clear a successor claim")
        NonCallCaptureOwnership.release(next)
        var previewStopped = false
        let preview = NonCallCaptureOwnership.acquire(.preview, generation: 1, stop: { _ in previewStopped = true })!
        var explicitClaim: NonCallCaptureOwnership.Claim?
        let explicit = Task { explicitClaim = try! await NonCallCaptureOwnership.prepare(.camera, generation: 1, stop: { _ in }) }
        while !previewStopped { await Task.yield() }
        check(explicitClaim == nil && NonCallCaptureOwnership.isBusy, "explicit camera waits for the passive preview's serial stop")
        NonCallCaptureOwnership.release(preview); await explicit.value
        check(explicitClaim != nil && NonCallCaptureOwnership.isCurrent(explicitClaim!), "camera handoff happens after preview capture stopped")
        let other = try! await NonCallCaptureOwnership.prepare(.voice, generation: 1, stop: { _ in })
        check(other == nil && NonCallCaptureOwnership.isCurrent(explicitClaim!), "another explicit capture cannot silently replace an open system camera")
        NonCallCaptureOwnership.release(explicitClaim!)
        let camera = NonCallCaptureOwnership.acquire(.camera, generation: 1, stop: { _ in })!
        let picker = UIImagePickerController(), lease = NativeCameraPickerLease(camera)
        var closedUI = false
        check(lease.attach(picker, interrupted: { closedUI = true }), "native picker adopts the permission claim before opening camera")
        picker.sourceType = .camera
        NonCallCaptureOwnership.interrupt()
        check(closedUI && picker.dismisses == 1 && !lease.isCurrent && NonCallCaptureOwnership.isBusy,
              "incoming dismisses its own system picker and invalidates late image/video callbacks")
        check(picker.sourceType == .camera, "claim stays reserved until UIKit's actual dismissal callback")
        picker.finishDismiss()
        check(picker.sourceType == .photoLibrary && picker.delegate == nil && !NonCallCaptureOwnership.isBusy,
              "camera source/delegate teardown precedes capture-lease release")
        let replacement = NonCallCaptureOwnership.acquire(.preview, generation: 1, stop: { _ in })!
        picker.finishDismiss()
        check(NonCallCaptureOwnership.isCurrent(replacement), "duplicated old UIKit completion cannot stop a new capture")
        generation = 2
        check(!NonCallCaptureOwnership.isCurrent(replacement), "old native callback fails after account generation change")
        NonCallCaptureOwnership.interrupt(generation: 1)
        check(!replacement.permit.isValid && NonCallCaptureOwnership.isBusy, "logout drains old native capture before successor admission")
        NonCallCaptureOwnership.release(replacement)
        let newAccount = NonCallCaptureOwnership.acquire(.voice, generation: 2, stop: { _ in })!
        NonCallCaptureOwnership.interrupt(generation: 1)
        check(NonCallCaptureOwnership.isCurrent(newAccount), "old-account teardown cannot interrupt successor microphone capture")
        do { try await NonCallCaptureOwnership.waitForRelease(until: Date().addingTimeInterval(-1)); preconditionFailure("deadline ignored") }
        catch { check(NonCallCaptureOwnership.isBusy, "deadline failure never releases still-owned hardware early") }
        NonCallCaptureOwnership.release(newAccount)
        var draftPreserved = false
        let voice = NonCallCaptureOwnership.acquire(.voice, generation: 2, stop: { claim in
            draftPreserved = true; NonCallCaptureOwnership.release(claim)
        })!
        NonCallCaptureOwnership.interrupt()
        check(draftPreserved && !voice.permit.isValid && !NonCallCaptureOwnership.isBusy,
              "incoming can preserve a voice draft and ring after synchronous recorder stop")
        NonCallCaptureOwnership.currentGeneration = { nil }
        check(NonCallCaptureOwnership.acquire(.camera, generation: 2, stop: { _ in }) == nil,
              "logged-out context never starts a new camera")
        print("PASS: \(checks) actual-policy capture and native-drain contracts")
    }
}
'''.replace("__NATIVE__", native)
with tempfile.TemporaryDirectory(prefix="coco-capture-leases-") as temporary:
    folder = Path(temporary)
    swift = folder / "main.swift"
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    swift.write_text(harness, encoding="utf-8")
    subprocess.run(["swiftc", "-parse-as-library", str(policy), str(swift), "-o", str(binary)], check=True, cwd=ROOT)
    subprocess.run([str(binary)], check=True, timeout=30, cwd=ROOT)
