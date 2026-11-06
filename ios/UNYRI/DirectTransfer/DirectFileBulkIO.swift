import CryptoKit
import Foundation

/// Three fixed bulk lanes. Short disk operations never wait for their loops.
/// No provider URL or handle is retained after its submitted worker exits.
actor DirectFileBulkIO {
    enum Lane: Hashable, Sendable { case staging, outbound, inbound }
    enum Failure: Error { case busy, stale, invalid }
    private struct Lease {
        let id: String
        let token: UUID
        let cancel: @Sendable () -> Void
        let drain: @Sendable () async -> Void
    }
    private var leases: [Lane: Lease] = [:]
    private var stopped = false

    func run<Value: Sendable>(lane: Lane, id: String,
                             onCancel: @escaping @Sendable () -> Void = {},
                             operation: @escaping @Sendable () throws -> Value) async throws -> Value {
        try Task.checkCancellation()
        guard !stopped, leases[lane] == nil else { throw Failure.busy }
        let token = UUID()
        let task = Task.detached(priority: lane == .staging ? .utility : .userInitiated) {
            try Task.checkCancellation()
            return try operation()
        }
        leases[lane] = Lease(id: id, token: token,
                            cancel: { onCancel(); task.cancel() }, drain: { _ = try? await task.value })
        do {
            let result = try await withTaskCancellationHandler(operation: { try await task.value },
                                                                onCancel: { onCancel(); task.cancel() })
            try Task.checkCancellation()
            guard !stopped, leases[lane]?.token == token else { throw Failure.stale }
            leases[lane] = nil
            return result
        } catch {
            // Detached work does not inherit cancellation. Await its actual
            // exit before the caller can release a FileProvider access grant.
            onCancel(); task.cancel(); _ = try? await task.value
            if leases[lane]?.token == token { leases[lane] = nil }
            throw error
        }
    }

    func cancelAndDrain(_ id: String) async {
        let owned = leases.filter { $0.value.id == id }
        owned.values.forEach { $0.cancel() }
        for (lane, lease) in owned {
            await lease.drain()
            if leases[lane]?.token == lease.token { leases[lane] = nil }
        }
    }
    func retire() async {
        stopped = true
        let owned = leases
        owned.values.forEach { $0.cancel() }
        for lease in owned.values { await lease.drain() }
        leases = [:]
    }

    nonisolated static func digest(file: URL, bytes: Int64) throws -> String {
        guard bytes >= 0 else { throw Failure.invalid }
        let reader = try FileHandle(forReadingFrom: file)
        defer { try? reader.close() }
        var digest = SHA256(), remaining = bytes
        while remaining > 0 {
            try Task.checkCancellation()
            let count = Int(min(remaining, 64 * 1024))
            guard let block = try reader.read(upToCount: count), block.count == count else { throw Failure.invalid }
            digest.update(data: block); remaining -= Int64(block.count)
        }
        try Task.checkCancellation()
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
