import Foundation

/// The hot path has its own serial executor. A GiB contains 65,536 frames, but
/// it produces at most four UI progress deliveries per second.
actor DirectFileStream {
    enum Event: Sendable { case progress(DirectFileProgress), verifying, completed, paused(String) }
    private var record: DirectFileRecord
    private let disk: DirectFileDisk
    private let peer: DirectFilePeer
    private let event: @Sendable (Event) async -> Void
    private let execution: DirectFileExecutionPermit
    private func executionAllowed() -> Bool { execution.allowed }
    private var nextOffset: Int64
    private var acknowledged: Int64
    private var stopped = false
    private var finished = false
    private var started = false
    private var writer: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var lastActivity = ProcessInfo.processInfo.systemUptime
    private var lastProgress = 0.0
    private var lastStats = DirectFileProgress(offset: 0)
    private var sampler: DirectFileRateSampler
    private var verificationDeadline: TimeInterval?
    private var verifying = false

    static func verificationBudget(bytes: Int64) -> TimeInterval {
        max(45, ceil(Double(max(0, bytes)) / Double(4 * 1024 * 1024)) + 30)
    }

    init(record: DirectFileRecord, disk: DirectFileDisk, peer: DirectFilePeer,
         execution: DirectFileExecutionPermit,
         event: @escaping @Sendable (Event) async -> Void) {
        self.record = record; self.disk = disk; self.peer = peer; self.event = event
        self.execution = execution
        nextOffset = record.durableOffset; acknowledged = record.durableOffset
        sampler = DirectFileRateSampler(offset: record.durableOffset, uptime: ProcessInfo.processInfo.systemUptime)
        lastStats.offset = record.durableOffset
    }

    func start() {
        guard !started, !stopped, executionAllowed() else { return }
        started = true
        sampler = DirectFileRateSampler(offset: record.durableOffset, uptime: ProcessInfo.processInfo.systemUptime)
        if record.outgoing { writer = Task(priority: .utility) { await pump() } }
        timer = Task(priority: .utility) {
            while !Task.isCancelled, !self.stopped, !self.finished {
                do {
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                    guard !self.stopped, !self.finished, self.executionAllowed() else { return }
                    let now = ProcessInfo.processInfo.systemUptime
                    if let deadline = self.verificationDeadline, now >= deadline {
                        await self.fail("verification_failed"); return
                    }
                    if self.verificationDeadline == nil, now - self.lastActivity > 45 {
                        await self.fail("disconnected"); return
                    }
                    if self.verifying, !self.record.outgoing {
                        // The known durable checkpoint is safe to heartbeat
                        // without queuing behind the long final hash actor.
                        try self.requireExecution(); try await self.sendSnapshot(self.sampler.idle(uptime: now))
                    } else if !self.record.outgoing, let offset = try await self.disk.checkpoint(self.record) {
                        try await self.sendAck(offset)
                    } else if !self.record.outgoing {
                        let idle = try self.sampler.idle(uptime: ProcessInfo.processInfo.systemUptime)
                        try self.requireExecution(); try await self.sendSnapshot(idle)
                    }
                } catch {
                    if !Task.isCancelled {
                        // An idle ACK can meet an already closed data channel.
                        // That is recoverable network loss, never disk full.
                        await self.fail(error is DirectFilePeer.Failure ? "connection_lost" : "disk_full")
                    }
                    return
                }
            }
        }
    }

    private func send(_ packet: Data) async throws {
        try await peer.send(packet, execution: execution)
    }

    private func requireExecution() throws {
        guard !stopped, executionAllowed() else { throw CancellationError() }
    }

    private func pump() async {
        do {
            while !stopped, !Task.isCancelled, executionAllowed() {
                if nextOffset == record.offer.size {
                    while acknowledged < record.offer.size {
                        try await Task.sleep(nanoseconds: 30_000_000)
                        guard !stopped, executionAllowed() else { throw CancellationError() }
                    }
                    if verificationDeadline == nil {
                        verificationDeadline = ProcessInfo.processInfo.systemUptime + Self.verificationBudget(bytes: record.offer.size)
                    }
                    try requireExecution(); try await send(DirectFilePacket.encode(.init(kind: .finish,
                        offset: record.offer.size, body: Self.digestBytes(record.offer.sha256))))
                    return
                }
                if nextOffset - acknowledged >= 512 * 1024 {
                    try await Task.sleep(nanoseconds: 20_000_000); continue
                }
                let offset = nextOffset
                let bytes = try await disk.sourceChunk(record, offset: offset, execution: execution)
                guard !stopped, !Task.isCancelled, executionAllowed() else { throw CancellationError() }
                // Reserve before the async send: a fast legitimate cumulative
                // ACK may arrive before the awaiting producer is resumed.
                nextOffset += Int64(bytes.count)
                try requireExecution(); try await send(DirectFilePacket.encode(.init(kind: .chunk, offset: offset, body: bytes)))
                await Task.yield()
            }
        } catch { if !stopped { await fail("source_changed") } }
    }

    func receive(_ bytes: Data) async throws {
        guard !stopped, executionAllowed(), record.offer.expiresAt > Int64(Date().timeIntervalSince1970) else { throw DirectFilePacket.Failure.invalid }
        let frame = try DirectFilePacket.decode(bytes)
        if !started { start() }
        lastActivity = ProcessInfo.processInfo.systemUptime
        if record.outgoing {
            switch frame.kind {
            case .durableAck:
                guard frame.offset >= acknowledged, frame.offset <= nextOffset else { throw DirectFilePacket.Failure.invalid }
                let stats = DirectFilePacket.AckStats.decode(frame.body)
                guard stats != nil || lastStats.sample == 0 else { throw DirectFilePacket.Failure.invalid }
                if let stats {
                    guard stats.sample >= lastStats.sample else { throw DirectFilePacket.Failure.invalid }
                    if stats.sample == lastStats.sample {
                        guard frame.offset == lastStats.offset, stats.speedBytesPerSecond == lastStats.speedBytesPerSecond else { throw DirectFilePacket.Failure.invalid }
                        return
                    }
                }
                if frame.offset > acknowledged {
                    try await disk.senderAcknowledged(record, offset: frame.offset, execution: execution)
                    acknowledged = frame.offset; record.durableOffset = frame.offset
                }
                lastStats = DirectFileProgress(offset: frame.offset, sample: stats?.sample ?? 0,
                    speedBytesPerSecond: stats?.speedBytesPerSecond ?? 0)
                await publish(lastStats)
            case .completeAck:
                guard frame.offset == record.offer.size, frame.body == Self.digestBytes(record.offer.sha256),
                      nextOffset == record.offer.size, acknowledged == record.offer.size else { throw DirectFilePacket.Failure.invalid }
                finished = true; timer?.cancel(); await event(.completed)
            default: throw DirectFilePacket.Failure.invalid
            }
        } else {
            guard record.consentGiven else { throw DirectFilePacket.Failure.invalid }
            switch frame.kind {
            case .chunk:
                guard !finished else { throw DirectFilePacket.Failure.invalid }
                if let offset = try await disk.append(frame, record: record, execution: execution) { try await sendAck(offset) }
            case .finish:
                guard frame.offset == record.offer.size, frame.body == Self.digestBytes(record.offer.sha256) else { throw DirectFilePacket.Failure.invalid }
                if !finished {
                    verifying = true
                    if verificationDeadline == nil {
                        verificationDeadline = ProcessInfo.processInfo.systemUptime + Self.verificationBudget(bytes: record.offer.size)
                    }
                    await event(.verifying)
                    _ = try await disk.finishReceiver(record, digest: frame.body, execution: execution)
                    try Task.checkCancellation()
                    guard !stopped, executionAllowed() else { throw CancellationError() }
                    record.durableOffset = record.offer.size; finished = true; timer?.cancel()
                }
                try requireExecution(); try await send(DirectFilePacket.encode(.init(kind: .completeAck, offset: record.offer.size, body: frame.body)))
                await event(.completed)
            default: throw DirectFilePacket.Failure.invalid
            }
        }
    }

    private func sendAck(_ offset: Int64) async throws {
        guard !stopped, executionAllowed(), offset >= record.durableOffset, offset <= record.offer.size else { return }
        record.durableOffset = offset
        let snapshot = try sampler.acknowledge(offset: offset, uptime: ProcessInfo.processInfo.systemUptime)
        try await sendSnapshot(snapshot)
    }

    private func sendSnapshot(_ snapshot: DirectFileProgress) async throws {
        guard executionAllowed() else { throw CancellationError() }
        lastStats = snapshot
        let stats = DirectFilePacket.AckStats(sample: lastStats.sample, speedBytesPerSecond: lastStats.speedBytesPerSecond)
        try requireExecution(); try await send(DirectFilePacket.encode(.init(kind: .durableAck, offset: snapshot.offset, body: stats.encoded)))
        await publish(snapshot)
    }

    private func publish(_ progress: DirectFileProgress) async {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastProgress >= 0.25 else { return }
        lastProgress = now
        await event(.progress(progress))
    }

    private func fail(_ reason: String) async {
        guard !stopped else { return }
        stopped = true; writer?.cancel(); timer?.cancel()
        await event(.paused(reason))
    }

    func stop() async -> Int64 {
        stopped = true; writer?.cancel(); timer?.cancel()
        if !record.outgoing, let offset = try? await disk.checkpoint(record) { record.durableOffset = offset }
        await disk.suspend(record.id)
        return (try? await disk.durableOffset(record)) ?? record.durableOffset
    }

    private static func digestBytes(_ value: String) -> Data {
        let raw = Array(value.utf8); var result = Data()
        guard raw.count == 64 else { return result }
        for index in stride(from: 0, to: 64, by: 2) {
            guard let byte = UInt8(String(bytes: raw[index...index + 1], encoding: .utf8) ?? "", radix: 16) else { return Data() }
            result.append(byte)
        }
        return result
    }
}
