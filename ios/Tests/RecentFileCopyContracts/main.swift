import Foundation

final class CopyGate: @unchecked Sendable {
    let gate = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var began = false
    private var ended = false
    var started: Bool { lock.lock(); defer { lock.unlock() }; return began }
    var finished: Bool { lock.lock(); defer { lock.unlock() }; return ended }
    func begin() { lock.lock(); began = true; lock.unlock() }
    func finish() { lock.lock(); ended = true; lock.unlock() }
}

@main struct RecentFileCopyContracts {
    @MainActor static func main() async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("recent-copy-" + UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        let source = root.appendingPathComponent("original.mov")
        let owner = root.appendingPathComponent("captured-account", isDirectory: true)
        let bytes = Data(repeating: 42, count: 200_000)
        try bytes.write(to: source)
        func entries() throws -> [UnyriRecentFiles.Entry] {
            try JSONDecoder().decode([UnyriRecentFiles.Entry].self, from: Data(contentsOf: owner.appendingPathComponent("index.json")))
        }
        let normal = CopyGate()
        var held = false, released = false
        let job = Task { @MainActor in
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue,
                acquire: { held = true }, release: {
                    precondition(normal.finished, "Ownership released before worker completion")
                    held = false; released = true
                }, copy: { cancellation in
                    precondition(!Thread.isMainThread, "Production recent-file copy ran on main")
                    normal.begin(); normal.gate.wait()
                    defer { normal.finish() }
                    try UnyriRecentFiles.record([UnyriRecentFiles.Source(url: source)], into: owner, cancellation: cancellation)
                })
        }
        while !normal.started { await Task.yield() }
        precondition(held && !released)
        // This index read must not wait for the blocked bulk-copy lane.
        _ = UnyriRecentFiles.load()
        normal.gate.signal()
        try await job.value
        precondition(!held && released)
        let first = try entries()
        precondition(first.count == 1 && first[0].name == source.lastPathComponent)
        let cached = owner.appendingPathComponent(first[0].file)
        let copied = try Data(contentsOf: cached)
        precondition(copied == bytes, "Recent copy lost source bytes or captured account destination")

        let fresh = root.appendingPathComponent("fresh.txt")
        try Data([7]).write(to: fresh)
        try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue, acquire: {}, release: {}, copy: { cancellation in
            try UnyriRecentFiles.record([UnyriRecentFiles.Source(url: fresh), UnyriRecentFiles.Source(url: cached)],
                                       into: owner, cancellation: cancellation)
        })
        let reordered = try entries()
        precondition(reordered.count == 2 && reordered.first?.name == "fresh.txt" && fm.fileExists(atPath: cached.path),
                     "Existing Recent picks must be bumped before fresh copies without deleting their source")

        let cancelled = CopyGate()
        var cancelledHeld = false, cancelledReleased = false
        let cancelledJob = Task { @MainActor in
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue,
                acquire: { cancelledHeld = true }, release: {
                    precondition(cancelled.finished, "Cancellation released a source before its worker acknowledged it")
                    cancelledHeld = false; cancelledReleased = true
                }, copy: { cancellation in
                    cancelled.begin(); cancelled.gate.wait()
                    defer { cancelled.finish() }
                    try UnyriRecentFiles.record([UnyriRecentFiles.Source(url: source)], into: owner, cancellation: cancellation)
                })
        }
        while !cancelled.started { await Task.yield() }
        cancelledJob.cancel()
        await Task.yield()
        precondition(cancelledHeld && !cancelledReleased, "Cancelled await must drain the worker before releasing ownership")
        cancelled.gate.signal()
        do { try await cancelledJob.value; preconditionFailure("Cancellation ignored") } catch is CancellationError {}
        let afterCancel = try entries()
        precondition(!cancelledHeld && cancelledReleased && afterCancel == reordered, "Cancelled copy published a cache entry")

        let partial = root.appendingPathComponent("cancelled-chunk.bin")
        var chunkReleased = false
        do {
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue, acquire: {},
                release: { chunkReleased = true }, copy: { cancellation in
                    // Run outside a Swift task, as the real DispatchQueue worker does.
                    let reader = AttachmentFileCopy.DocumentRead()
                    try cancellation.withReaderCancellation({ reader.cancel() }) {
                        try reader.copy(from: source, to: partial, maxBytes: UnyriRecentFiles.maxBytes,
                            allocation: { _, _ in try cancellation.check() }, onChunk: { _ in cancellation.cancel() })
                    }
                })
            preconditionFailure("Mid-copy cancellation ignored")
        } catch is CancellationError {}
        precondition(chunkReleased && !fm.fileExists(atPath: partial.path) && fm.fileExists(atPath: source.path),
                     "Chunk cancellation leaked a partial copy or retired the original prematurely")

        enum Failure: Error { case injected }
        var failureReleased = false
        do {
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue, acquire: {},
                release: { failureReleased = true }, copy: { _ in throw Failure.injected })
            preconditionFailure("Worker error ignored")
        } catch Failure.injected {}
        precondition(failureReleased, "A failed worker leaked source ownership")
        var unacquiredReleased = false
        do {
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue,
                acquire: { throw Failure.injected }, release: { unacquiredReleased = true },
                copy: { _ in preconditionFailure("Worker ran without acquiring source ownership") })
            preconditionFailure("Acquisition failure ignored")
        } catch Failure.injected {}
        precondition(!unacquiredReleased, "A failed acquisition released another reader's ownership")
        var queuedAcquired = false
        let queued = Task { @MainActor in
            try await ChatComposerRecentFileCopy.perform(on: UnyriRecentFiles.copyQueue,
                acquire: { queuedAcquired = true }, release: {}, copy: { _ in preconditionFailure("Cancelled queued worker ran") })
        }
        queued.cancel()
        do { try await queued.value; preconditionFailure("Queued cancellation ignored") } catch is CancellationError {}
        precondition(!queuedAcquired, "A cancelled queued operation acquired unnecessary ownership")
        print("PASS: production recent-file worker: off-main IO, responsive index, captured account, ordering, ownership through completion/cancellation, error and queued cancellation")
    }
}
