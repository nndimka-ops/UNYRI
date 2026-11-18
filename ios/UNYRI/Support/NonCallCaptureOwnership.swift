import Foundation

final class NonCallCapturePermit: @unchecked Sendable {
    private let lock = NSLock()
    private var valid = true
    var isValid: Bool { lock.lock(); defer { lock.unlock() }; return valid }
    func invalidate() { lock.lock(); valid = false; lock.unlock() }
}

/// Process-scoped exclusion for the four non-call capture entry points. An
/// incoming call interrupts these owners, rather than treating them as a call.
@MainActor
enum NonCallCaptureOwnership {
    enum Kind { case camera, preview, voice }
    struct Claim: Equatable, Sendable {
        let id: UUID
        let generation: UInt64
        let permit = NonCallCapturePermit()
        static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id && lhs.generation == rhs.generation }
    }
    private struct Entry {
        let claim: Claim
        let kind: Kind
        var stopping = false
        var stop: (Claim) -> Void
    }
    private static var entry: Entry?
    static var currentGeneration: () -> UInt64? = { nil }
    static var callsBusy: () -> Bool = { true }
    static var isBusy: Bool { entry != nil }

    static func acquire(_ kind: Kind, generation: UInt64, stop: @escaping (Claim) -> Void) -> Claim? {
        guard entry == nil, currentGeneration() == generation, !callsBusy() else { return nil }
        let claim = Claim(id: UUID(), generation: generation)
        entry = Entry(claim: claim, kind: kind, stop: stop)
        return claim
    }
    static func isCurrent(_ claim: Claim) -> Bool {
        entry?.claim == claim && entry?.stopping == false && claim.permit.isValid
            && currentGeneration() == claim.generation && !callsBusy()
    }
    static func replaceStop(_ claim: Claim, stop: @escaping (Claim) -> Void) -> Bool {
        guard isCurrent(claim) else { return false }
        entry?.stop = stop
        return true
    }
    static func release(_ claim: Claim) {
        guard entry?.claim == claim else { return }
        claim.permit.invalidate()
        entry = nil
    }
    static func stop(_ claim: Claim) {
        guard var value = entry, value.claim == claim, !value.stopping else { return }
        value.stopping = true; entry = value
        claim.permit.invalidate()
        value.stop(claim)
    }
    static func interrupt() { if let claim = entry?.claim { stop(claim) } }
    static func interrupt(generation: UInt64) {
        if entry?.claim.generation == generation { interrupt() }
    }
    static func waitForRelease(until deadline: Date? = nil) async throws {
        let limit = deadline ?? Date().addingTimeInterval(5)
        while isBusy {
            try Task.checkCancellation()
            guard Date() < limit else { throw CancellationError() }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        try Task.checkCancellation()
    }
    static func prepare(_ kind: Kind, generation: UInt64, stop: @escaping (Claim) -> Void) async throws -> Claim? {
        // A camera tile's passive preview yields to explicit capture. A voice
        // recording or system picker is never silently replaced by another one.
        if entry?.kind == .preview {
            interrupt(); try await waitForRelease()
        }
        try Task.checkCancellation()
        return acquire(kind, generation: generation, stop: stop)
    }
}

#if canImport(UIKit)
import UIKit

/// Keeps the lease through UIKit camera teardown, including a system-call
/// interruption while the picker or its result-staging task is still open.
@MainActor
final class NativeCameraPickerLease {
    private let claim: NonCallCaptureOwnership.Claim
    private weak var picker: UIImagePickerController?
    private var closing = false
    init(_ claim: NonCallCaptureOwnership.Claim) { self.claim = claim }
    var isCurrent: Bool { !closing && NonCallCaptureOwnership.isCurrent(claim) }
    func attach(_ picker: UIImagePickerController, interrupted: @escaping () -> Void) -> Bool {
        guard isCurrent else { return false }
        self.picker = picker
        return NonCallCaptureOwnership.replaceStop(claim) { [weak self] claim in
            interrupted()
            if let self { self.close() } else { NonCallCaptureOwnership.release(claim) }
        }
    }
    func close() {
        guard !closing else { return }; closing = true
        let claim = claim
        guard let picker else { NonCallCaptureOwnership.release(claim); return }
        let finish = {
            // Source replacement after dismissal releases UIKit's capture
            // controller before any waiting call can initialize local tracks.
            picker.delegate = nil
            if picker.sourceType == .camera { picker.sourceType = .photoLibrary }
            NonCallCaptureOwnership.release(claim)
        }
        if picker.presentingViewController != nil { picker.dismiss(animated: false, completion: finish) }
        else { finish() }
    }
}
#endif
