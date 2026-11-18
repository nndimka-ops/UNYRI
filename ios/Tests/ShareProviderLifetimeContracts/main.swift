import Foundation
import Dispatch

/// A provider whose URL exists ONLY while its completion is on the stack.
/// Deferring the consumer onto another queue makes this fixture fail.
final class NSItemProvider: @unchecked Sendable {
    let source: URL
    let size: Int
    let finished = DispatchSemaphore(value: 0)
    private let callbackMarker = UUID().uuidString
    private let lock = NSLock()
    private var count = 0
    private var completed = false
    var calls: Int { lock.lock(); defer { lock.unlock() }; return count }
    var returned: Bool { lock.lock(); defer { lock.unlock() }; return completed }
    var insideCallback: Bool { Thread.current.threadDictionary[callbackMarker] as? Bool == true }
    init(source: URL, size: Int = 65_536) { self.source = source; self.size = size }
    func loadItem(forTypeIdentifier: String, options: [AnyHashable: Any]?,
                  completionHandler: @escaping @Sendable (Any?, Error?) -> Void) {
        precondition(!Thread.isMainThread && forTypeIdentifier == "public.file-url")
        lock.lock(); count += 1; lock.unlock()
        Thread.current.threadDictionary[callbackMarker] = true
        defer {
            Thread.current.threadDictionary.removeObject(forKey: callbackMarker)
            try? FileManager.default.removeItem(at: source)
            lock.lock(); completed = true; lock.unlock()
            finished.signal()
        }
        do { try Data(repeating: 7, count: size).write(to: source) }
        catch { completionHandler(nil, error); return }
        completionHandler(source as NSURL, nil)
    }
}

@MainActor final class ProviderBridge {
    // PRODUCTION_BRIDGE
    nonisolated static func start(_ provider: NSItemProvider, cancellation: UnyriShareStore.CopyCancellation,
                                  completion: @escaping @Sendable (URL?, Error?) -> Void) {
        loadURLItem(provider, cancellation: cancellation, completion: completion)
    }
}

@main struct ShareProviderLifetimeContracts {
    static func consume(_ provider: NSItemProvider, destination: URL, token: UnyriShareStore.CopyCancellation,
                        cancelDuringCopy: Bool = false) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            ProviderBridge.start(provider, cancellation: token) { url, error in
                do {
                    if let error { throw error }
                    guard let url else { throw CocoaError(.fileReadUnknown) }
                    precondition(provider.insideCallback && !provider.returned, "Consumer escaped the provider callback stack or released temporary URL")
                    _ = try UnyriShareStore.copy(url, to: destination, limit: 1_048_576, cancellation: token, progress: { _ in
                        if cancelDuringCopy { token.cancel() }
                    })
                    precondition(!provider.returned, "Copy outlived provider completion")
                    continuation.resume()
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
    static func main() async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        let provider = NSItemProvider(source: root.appendingPathComponent("temporary.mov"))
        let destination = root.appendingPathComponent("staged.mov")
        try await consume(provider, destination: destination, token: .init())
        precondition(provider.finished.wait(timeout: .now() + 2) == .success)
        precondition(provider.returned && !fm.fileExists(atPath: provider.source.path))
        let copied = try Data(contentsOf: destination)
        precondition(copied == Data(repeating: 7, count: provider.size), "Provider deleted its URL before staging finished")

        let cancelled = UnyriShareStore.CopyCancellation(); cancelled.cancel()
        let unopened = NSItemProvider(source: root.appendingPathComponent("never-requested.mov"))
        do {
            try await consume(unopened, destination: root.appendingPathComponent("never-staged.mov"), token: cancelled)
            preconditionFailure("Already-cancelled request started")
        } catch is CancellationError {}
        precondition(unopened.calls == 0, "Close before loading still requested a provider original")

        let partial = NSItemProvider(source: root.appendingPathComponent("partial.mov"), size: 524_288)
        let partialDestination = root.appendingPathComponent("partial-staged.mov")
        do {
            try await consume(partial, destination: partialDestination, token: .init(), cancelDuringCopy: true)
            preconditionFailure("Copy ignored Close")
        } catch is CancellationError {}
        precondition(partial.finished.wait(timeout: .now() + 2) == .success)
        precondition(!fm.fileExists(atPath: partialDestination.path) && !fm.fileExists(atPath: partial.source.path))
        print("PASS: production URL-provider bridge copies within callback lifetime; immediate provider deletion, off-main request, cancel-before-request and cancelled partial-copy cleanup")
    }
}
