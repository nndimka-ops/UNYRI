import Foundation

/// One immutable transport attempt. The OS expiration callback can revoke this
/// synchronously, without waiting for the UI actor or an IO checkpoint.
final class DirectFileExecutionPermit: @unchecked Sendable {
    private let lock = NSLock()
    private var foreground = true
    private var granted = false
    private var revoked = false
    private var interrupted = false
    var allowed: Bool {
        lock.lock(); defer { lock.unlock() }
        return !revoked && (foreground || granted)
    }
    var hasGrant: Bool {
        lock.lock(); defer { lock.unlock() }
        return !revoked && granted
    }
    var wasInterrupted: Bool {
        lock.lock(); defer { lock.unlock() }; return interrupted
    }
    func setForeground(_ value: Bool) {
        lock.lock(); defer { lock.unlock() }; foreground = value
    }
    @discardableResult func grant() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !revoked else { return false }; granted = true; return true
    }
    func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard !revoked && (foreground || granted) else { throw CancellationError() }
        return try operation()
    }
    func revoke(interrupted: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        self.interrupted = self.interrupted || interrupted
        revoked = true; granted = false
    }
}

struct DirectFileContinuationMarker: Codable, Equatable, Sendable {
    let task: String
    let attempt: String
    var requiresContinue: Bool
    var valid: Bool {
        !task.isEmpty && task.utf8.count <= 256 &&
            UUID(uuidString: attempt)?.uuidString.lowercased() == attempt
    }
}

/// No mutable record/queue lookup can widen this membership after submission.
struct DirectFileBackgroundMember: Equatable, Sendable {
    let id: String
    let outgoing: Bool
    let attempt: String
    let lease: UUID
}

/// A system-task entry may finish without retiring its still-usable foreground
/// transport (for example a denied request). Arbitrate this entry separately.
final class DirectFileTaskArbitration: @unchecked Sendable {
    private enum State { case active, interrupted, retired }
    private let lock = NSLock()
    private var state = State.active
    func interrupt() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard state == .active else { return false }
        state = .interrupted; return true
    }
    func finish(interruptionHandled: Bool = false) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard state != .retired, state != .interrupted || interruptionHandled else { return false }
        state = .retired; return true
    }
}
