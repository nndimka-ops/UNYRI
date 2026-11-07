import Foundation
import WebRTC

/// Thread-safe data-plane bridge. Native callbacks and the serial send/receive
/// tasks stay off the main actor; only infrequent connection state reaches UI.
final class DirectFilePeerPipe: @unchecked Sendable {
    private let lock = NSLock()
    private var channel: RTCDataChannel?
    private var routeVerified = false
    private var closed = false
    private var incomingBytes = 0
    private var outgoingBytes = 0
    private var receiveTail: Task<Void, Never>?
    private var receiveTasks: [UUID: Task<Void, Never>] = [:]
    private var handler: (@Sendable (Data) async throws -> Void)?
    private var failure: (@Sendable (Error) -> Void)?
    private let writer = Writer()

    func configure(handler: (@Sendable (Data) async throws -> Void)?) {
        lock.lock(); self.handler = handler; lock.unlock()
    }
    func configure(failure: @escaping @Sendable (Error) -> Void) {
        lock.lock(); self.failure = failure; lock.unlock()
    }
    func install(_ value: RTCDataChannel) { lock.lock(); channel = value; lock.unlock() }
    func verifyRoute(_ value: Bool) { lock.lock(); routeVerified = value; lock.unlock() }

    func close() {
        lock.lock(); closed = true; routeVerified = false; channel = nil
        let tasks = Array(receiveTasks.values)
        receiveTail = nil; receiveTasks = [:]; handler = nil; failure = nil
        lock.unlock(); tasks.forEach { $0.cancel() }
    }

    func send(_ data: Data, execution: DirectFileExecutionPermit? = nil) async throws {
        guard reserveOutgoing(data.count) else { throw DirectFilePeer.Failure.congested }
        defer { releaseOutgoing(data.count) }
        try await writer.send(data, pipe: self, execution: execution)
    }
    private func reserveOutgoing(_ count: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !closed, count > 0, count <= DirectFilePeerPolicy.maxFrameBytes,
              outgoingBytes + count <= Int(DirectFilePeerPolicy.highWaterBytes) else { return false }
        outgoingBytes += count; return true
    }
    private func releaseOutgoing(_ count: Int) { lock.lock(); outgoingBytes -= count; lock.unlock() }

    private struct Snapshot: @unchecked Sendable {
        let channel: RTCDataChannel?
        let verified: Bool
        let stopped: Bool
    }
    private func snapshot() -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(channel: channel, verified: routeVerified, stopped: closed)
    }
    private func write(_ data: Data, execution: DirectFileExecutionPermit?) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 15
        var waitingForLowWater = false
        while true {
            try Task.checkCancellation()
            guard execution?.allowed != false else { throw CancellationError() }
            let current = snapshot()
            guard !current.stopped, let channel = current.channel, channel.readyState == .open else { throw DirectFilePeer.Failure.closed }
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw DirectFilePeer.Failure.congested }
            if channel.bufferedAmount + UInt64(data.count) > DirectFilePeerPolicy.highWaterBytes { waitingForLowWater = true }
            if current.verified && (!waitingForLowWater || channel.bufferedAmount <= DirectFilePeerPolicy.lowWaterBytes) {
                let write = {
                    channel.bufferedAmount + UInt64(data.count) <= DirectFilePeerPolicy.highWaterBytes &&
                        channel.sendData(RTCDataBuffer(data: data, isBinary: true))
                }
                let sent = try execution.map { try $0.perform(write) } ?? write()
                guard sent else { throw DirectFilePeer.Failure.congested }
                return
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func receive(_ bytes: Data, binary: Bool) {
        lock.lock()
        guard !closed, binary, !bytes.isEmpty, bytes.count <= DirectFilePeerPolicy.maxFrameBytes,
              receiveTasks.count < 64,
              // At most the 512KiB peer window plus one executing 16KiB frame:
              // its ACK can refill the window before its UI callback returns.
              incomingBytes + max(0, bytes.count - 14) <= Int(DirectFilePeerPolicy.highWaterBytes) + DirectFilePacket.chunkBytes, let receive = handler else {
            let failed = failure
            let reason: Error = !binary || bytes.isEmpty || bytes.count > DirectFilePeerPolicy.maxFrameBytes
                ? DirectFilePeer.Failure.protocolViolation : DirectFilePeer.Failure.congested
            lock.unlock(); failed?(reason); return
        }
        let cost = max(0, bytes.count - 14)
        incomingBytes += cost
        let id = UUID()
        let previous = receiveTail
        receiveTail = Task.detached(priority: .utility) { [self] in
            defer { releaseIncoming(cost, id: id) }
            await previous?.value
            do {
                try Task.checkCancellation()
                let deadline = ProcessInfo.processInfo.systemUptime + 3
                while !snapshot().verified {
                    guard !snapshot().stopped, ProcessInfo.processInfo.systemUptime < deadline else { throw DirectFilePeer.Failure.closed }
                    try await Task.sleep(nanoseconds: 20_000_000)
                }
                guard !snapshot().stopped else { return }
                try await receive(bytes)
            } catch { if !Task.isCancelled { notifyFailure(error) } }
        }
        receiveTasks[id] = receiveTail
        lock.unlock()
    }
    private func releaseIncoming(_ count: Int, id: UUID) { lock.lock(); incomingBytes -= count; receiveTasks[id] = nil; lock.unlock() }
    private func notifyFailure(_ reason: Error) { lock.lock(); let callback = failure; lock.unlock(); callback?(reason) }

    private actor Writer {
        private var tail: Task<Void, Error>?
        func send(_ bytes: Data, pipe: DirectFilePeerPipe, execution: DirectFileExecutionPermit?) async throws {
            let previous = tail
            let task = Task {
                _ = try? await previous?.value
                try Task.checkCancellation()
                try await pipe.write(bytes, execution: execution)
            }
            tail = task
            try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
        }
    }
}
