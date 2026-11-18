import Foundation

// Native seams only. run.py inserts the complete production policy unchanged.
protocol ObservableObject {}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
}
@MainActor final class UIApplication {
    enum State { case active, inactive, background }
    static let shared = UIApplication()
    var applicationState = State.active
    static let didBecomeActiveNotification = Notification.Name("test.didBecomeActive")
    static let willResignActiveNotification = Notification.Name("test.willResignActive")
}
struct AVError: Error {
    enum Code: Sendable {
        case operationInterrupted, mediaServicesWereReset, decoderTemporarilyUnavailable
        case fileFormatNotRecognized, fileFailedToParse, failedToParse, decoderNotFound
        case undecodableMediaData, formatUnsupported, contentIsProtected, unknown, decodeFailed
    }
    let code: Code
}
@MainActor enum ConversationImages {
    static var generation: UInt64 = 0
    static var failures = Set<String>()
    static var retries = 0
    static func key(_ path: String, _ edge: Int) -> String { "\(edge):" + path }
    static func noteVideoFrameFailed(path: String, pixelEdge: Int, generation expected: UInt64) {
        guard expected == generation else { return }
        failures.insert(key(path, pixelEdge))
    }
    static func videoFrameFailed(path: String, pixelEdge: Int) -> Bool {
        failures.contains(key(path, pixelEdge))
    }
    static func retryFailedVideoFrames() { failures.removeAll(); retries += 1 }
    static func clear() { failures.removeAll(); generation &+= 1 }
}

// __PRODUCTION_POLICY__

@main struct FrameRetryContracts {
    @MainActor static func main() throws {
        let policy = UnyriVideoFrameFailures.shared
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data([0]).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let path = file.path
        var checks = 0
        func check(_ value: @autoclosure () -> Bool, _ label: String) {
            precondition(value(), label)
            checks += 1
        }
        func resign() {
            NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
            UIApplication.shared.applicationState = .inactive
        }
        func activate() {
            UIApplication.shared.applicationState = .active
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        }
        func reset() {
            ConversationImages.clear()
            activate()
        }
        func failed(_ code: AVError.Code, since epoch: UInt64?) -> Bool {
            policy.failed(AVError(code: code), path: path, pixelEdge: 768,
                          generation: ConversationImages.generation, since: epoch)
        }

        // Idle activations are silent: no timer, no per-row fanout, no cache mutation.
        let idleGeneration = policy.retryGeneration
        for _ in 0..<100 { resign(); activate() }
        check(policy.retryGeneration == idleGeneration && ConversationImages.retries == 0,
              "idle activations publish and clear nothing")

        // Both an attempt started active and one started inactive must retry on resume.
        // Exercise every error category, including an apparently permanent file error.
        for code in [AVError.Code.fileFormatNotRecognized, .unknown, .decodeFailed,
                     .operationInterrupted, .mediaServicesWereReset, .decoderTemporarilyUnavailable] {
            for startsActive in [true, false] {
                reset()
                if !startsActive { resign() }
                let epoch = policy.attemptEpoch
                if startsActive { resign() }
                let before = policy.retryGeneration
                check(!failed(code, since: epoch), "inactive failure defers rather than spins")
                check(!policy.holds(path: path, pixelEdge: 768) && ConversationImages.failures.isEmpty,
                      "inactive failure enters neither the file memo nor the pause")
                activate()
                check(policy.retryGeneration == before + 1, "resume restarts the mounted frame")
                activate()
                check(policy.retryGeneration == before + 1, "retry is consumed once")
            }
        }

        // UIApplication can resume before AVFoundation delivers the error. Merely
        // arming didBecomeActive here would miss that activation and leave the blur.
        reset()
        let oldEpoch = policy.attemptEpoch
        resign(); activate()
        let returnedGeneration = policy.retryGeneration
        check(failed(.fileFormatNotRecognized, since: oldEpoch), "late result requests an immediate local retry")
        check(ConversationImages.failures.isEmpty && !policy.holds(path: path, pixelEdge: 768),
              "late interrupted result is not mistaken for a corrupt file")
        check(!failed(.unknown, since: policy.attemptEpoch), "the fresh steady attempt never immediately loops")
        check(policy.holds(path: path, pixelEdge: 768), "steady transient failure observes the existing backoff")
        activate()
        check(policy.retryGeneration == returnedGeneration + 1 && !policy.holds(path: path, pixelEdge: 768),
              "activation also clears transient backoff")

        reset(); resign()
        let inactiveEpoch = policy.attemptEpoch
        check(inactiveEpoch == nil, "an attempt started inactive has no steady epoch")
        activate()
        check(failed(.decodeFailed, since: inactiveEpoch), "inactive-start result delivered active retries locally")
        check(!failed(.decoderTemporarilyUnavailable, since: policy.attemptEpoch),
              "the fresh interrupted attempt waits for a later event")

        // A steady decoder interruption is retryable even without a lifecycle change
        // during that attempt. No memo or cooldown hides it from the next natural start.
        for code in [AVError.Code.operationInterrupted, .mediaServicesWereReset, .decoderTemporarilyUnavailable] {
            reset()
            let before = policy.retryGeneration
            check(!failed(code, since: policy.attemptEpoch), "steady interruption schedules no immediate loop")
            check(!policy.holds(path: path, pixelEdge: 768), "steady interruption is not held")
            activate()
            check(policy.retryGeneration == before + 1, "steady interruption arms activation retry")
        }

        reset()
        check(!failed(.decoderNotFound, since: policy.attemptEpoch), "steady file error is memoized")
        check(ConversationImages.failures.count == 1 && policy.holds(path: path, pixelEdge: 768),
              "only a steady existing-file error enters the memo")
        check(!policy.holds(path: path, pixelEdge: 312), "failure memo is scoped to frame size")
        activate()
        check(!policy.holds(path: path, pixelEdge: 768), "activation releases the permanent-looking file error")

        reset()
        let missing = file.appendingPathExtension("missing").path
        check(!policy.failed(AVError(code: .decoderNotFound), path: missing, pixelEdge: 768,
                             generation: ConversationImages.generation, since: policy.attemptEpoch),
              "a missing file never triggers immediate retries")
        check(ConversationImages.failures.isEmpty && policy.holds(path: missing, pixelEdge: 768),
              "a missing file only gets transient backoff")

        // Background is deferred as well; clearing decoded pixels must not disarm a
        // still-mounted frame's pending activation retry.
        reset()
        let backgroundEpoch = policy.attemptEpoch
        resign(); UIApplication.shared.applicationState = .background
        check(!failed(.unknown, since: backgroundEpoch), "background result defers")
        let backgroundGeneration = policy.retryGeneration
        ConversationImages.clear()
        activate()
        check(policy.retryGeneration == backgroundGeneration + 1, "cache clear preserves deferred resume work")
        check(!policy.holds(path: path, pixelEdge: 768), "cache clear leaves no stale pause")

        reset()
        let staleCache = ConversationImages.generation
        ConversationImages.clear()
        let staleRetry = policy.retryGeneration
        check(!policy.failed(AVError(code: .decoderNotFound), path: path, pixelEdge: 768,
                             generation: staleCache, since: policy.attemptEpoch), "stale cache result is ignored")
        activate()
        check(ConversationImages.failures.isEmpty && policy.retryGeneration == staleRetry,
              "stale result cannot poison a successor cache or publish a retry")

        // Received and queued note posters use this same policy. Their loader swallows
        // both duration and frame errors, so even a steady failure must arm activation.
        reset()
        let posterEpoch = policy.attemptEpoch
        resign()
        let posterGeneration = policy.retryGeneration
        check(!policy.posterFailed(since: posterEpoch), "inactive poster waits for resume")
        check(ConversationImages.failures.isEmpty && !policy.holds(path: path, pixelEdge: 768),
              "poster failure never creates a memo or cooldown")
        activate()
        check(policy.retryGeneration == posterGeneration + 1, "resume retries note posters")
        reset()
        let latePosterEpoch = policy.attemptEpoch
        resign(); activate()
        check(policy.posterFailed(since: latePosterEpoch), "late poster result retries locally")
        check(!policy.posterFailed(since: policy.attemptEpoch), "fresh steady poster failure never loops")
        let steadyPosterGeneration = policy.retryGeneration
        activate(); activate()
        check(policy.retryGeneration == steadyPosterGeneration + 1, "steady poster failure arms exactly one activation")

        print("frame retry behavioral contracts: \(checks) passed")
    }
}
