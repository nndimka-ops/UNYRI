import CoreGraphics
import Foundation
import LiveKit

struct BenchConfig: Decodable {
    struct Member: Decodable, Sendable { let token: String; let identity: String }
    let url: String
    let key: String
    let participants: [Member]
    let durationSeconds: Int?
    let remoteSubscriptions: Int?
}
private struct BenchRoomReport: Encodable {
    var connected = false
    var published = false
    var subscribedPeers = 0
    var decodedPeers = 0
    var contentFrames = 0
    var plaintextPublications = 0
    var failures = 0
    var peerFrameCounts: [Int: Int] = [:]
}
private struct BenchReport: Encodable {
    var completed = false
    var passed = false
    var participants = 30
    var subscriptionsPerRoom = 6
    var generatedVideoFrames = 0
    var seconds = 0.0
    var sourceWidth = 160
    var sourceHeight = 120
    var sourceFPS = 5
    var rooms: [BenchRoomReport] = []
    var failure: String?
}
private final class BenchCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = BenchRoomReport()
    private var renderers: [Int: BenchVideoSink] = [:]
    func modify(_ body: (inout BenchRoomReport) -> Void) { lock.lock(); defer { lock.unlock() }; body(&value) }
    func attach(_ video: RemoteVideoTrack, peer: Int) {
        let renderer = BenchVideoSink(counter: self, peer: peer)
        lock.lock(); renderers[peer] = renderer; value.subscribedPeers = renderers.count; lock.unlock()
        video.add(videoRenderer: renderer)
    }
    func frame(peer: Int) {
        modify {
            $0.peerFrameCounts[peer, default: 0] += 1
            $0.contentFrames += 1; $0.decodedPeers = $0.peerFrameCounts.count
        }
    }
    func snapshot() -> BenchRoomReport { lock.lock(); defer { lock.unlock() }; return value }
}
private final class BenchVideoSink: NSObject, VideoRenderer, @unchecked Sendable {
    private weak var counter: BenchCounter?
    private let peer: Int
    init(counter: BenchCounter, peer: Int) { self.counter = counter; self.peer = peer }
    @MainActor var isAdaptiveStreamEnabled: Bool { true }
    @MainActor var adaptiveStreamSize: CGSize { CGSize(width: 160, height: 120) }
    nonisolated func render(frame: VideoFrame) {
        let content = frame.buffer.toI420()?.withUnsafePlanes { plane -> Bool in
            var sum = 0, count = 0
            for y in stride(from: 0, to: Int(plane.height), by: 16) {
                for x in stride(from: 0, to: Int(plane.width), by: 16) {
                    sum += Int(plane.dataY[y * Int(plane.strideY) + x]); count += 1
                }
            }
            return count > 0 && sum / count > 40
        } ?? false
        if content { counter?.frame(peer: peer) }
    }
}
private final class BenchDelegate: NSObject, RoomDelegate, @unchecked Sendable {
    let counter = BenchCounter()
    let targets: [String: Int]
    init(targets: [String: Int]) { self.targets = targets }
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didPublishTrack publication: RemoteTrackPublication) {
        guard let identity = participant.identity?.stringValue, targets[identity] != nil, publication.kind == .video else { return }
        guard publication.encryptionType == .gcm else { counter.modify { $0.plaintextPublications += 1 }; return }
        Task {
            do { try await publication.set(subscribed: true) }
            catch { self.counter.modify { $0.failures += 1 } }
        }
    }
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) {
        guard let identity = participant.identity?.stringValue, let peer = targets[identity],
              let video = publication.track as? RemoteVideoTrack else { return }
        guard publication.encryptionType == .gcm else { counter.modify { $0.plaintextPublications += 1 }; return }
        counter.attach(video, peer: peer)
    }
}
@MainActor
private final class BenchSession {
    let room: Room
    let delegate: BenchDelegate
    var source: Source?
    init(room: Room, delegate: BenchDelegate) { self.room = room; self.delegate = delegate }
}
enum BenchProbe {
    @MainActor static func run(_ config: BenchConfig, output: URL) async -> Bool {
        var result = BenchReport()
        var sessions: [BenchSession] = []
        var producer: Task<Void, Never>?
        let started = Date()
        func save() {
            result.rooms = sessions.map { $0.delegate.counter.snapshot() }
            result.generatedVideoFrames = sessions.reduce(0) { $0 + ($1.source?.videoFrames ?? 0) }
            result.seconds = Date().timeIntervalSince(started)
            if let data = try? JSONEncoder().encode(result) { try? data.write(to: output, options: .atomic) }
        }
        do {
            guard config.participants.count == 30, Set(config.participants.map(\.identity)).count == 30, config.key.count == 43 else { throw ProbeError.config }
            let subscriptions = max(1, min(8, config.remoteSubscriptions ?? 6))
            result.subscriptionsPerRoom = subscriptions
            try AudioManager.shared.setManualRenderingMode(true)
            for index in 0..<30 {
                var targets: [String: Int] = [:]
                for offset in 1...subscriptions {
                    let target = (index + offset) % 30
                    targets[config.participants[target].identity] = target
                }
                let delegate = BenchDelegate(targets: targets)
                let provider = BaseKeyProvider(options: KeyProviderOptions(sharedKey: true,
                    ratchetSalt: Data("LKFrameEncryptionKey".utf8), ratchetWindowSize: 0,
                    uncryptedMagicBytes: Data(), discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .pbkdf2))
                provider.setKey(key: config.key, index: 0)
                let room = Room(delegate: delegate, connectOptions: ConnectOptions(autoSubscribe: false),
                    roomOptions: RoomOptions(adaptiveStream: false, dynacast: false, e2eeOptions: E2EEOptions(keyProvider: provider)))
                sessions.append(BenchSession(room: room, delegate: delegate))
            }
            for start in stride(from: 0, to: 30, by: 4) {
                var batch: [Task<Void, Error>] = []
                for index in start..<min(start + 4, 30) {
                    let session = sessions[index], member = config.participants[index], url = config.url
                    batch.append(Task { @MainActor in
                        try await session.room.connect(url: url, token: member.token)
                        guard session.room.localParticipant.identity?.stringValue == member.identity else { throw ProbeError.config }
                        session.delegate.counter.modify { $0.connected = true }
                    })
                }
                do { for task in batch { try await task.value } }
                catch {
                    batch.forEach { $0.cancel() }
                    for task in batch { _ = try? await task.value }
                    throw error
                }
                save()
            }
            for session in sessions {
                let track = await LocalVideoTrack.createBufferTrack(name: "synthetic-bench-video", source: .camera,
                    options: BufferCaptureOptions(dimensions: Dimensions(width: 160, height: 120), fps: 5))
                guard let capturer = track.capturer as? BufferCapturer else { throw ProbeError.buffer }
                let source = Source(video: track, capturer: capturer)
                try await track.start(); try source.frame(); session.source = source
            }
            producer = Task { @MainActor in
                while !Task.isCancelled {
                    let tick = DispatchTime.now().uptimeNanoseconds
                    for session in sessions { try? session.source?.frame() }
                    let elapsed = DispatchTime.now().uptimeNanoseconds - tick
                    do { try await Task.sleep(nanoseconds: elapsed < 200_000_000 ? 200_000_000 - elapsed : 1) }
                    catch { return }
                }
            }
            let options = VideoPublishOptions(encoding: VideoEncoding(maxBitrate: 80_000, maxFps: 5), simulcast: false, preferredCodec: .vp8)
            for start in stride(from: 0, to: 30, by: 4) {
                var batch: [Task<Void, Error>] = []
                for index in start..<min(start + 4, 30) {
                    let session = sessions[index]
                    batch.append(Task { @MainActor in
                        guard let source = session.source else { throw ProbeError.buffer }
                        _ = try await session.room.localParticipant.publish(videoTrack: source.video, options: options)
                        session.delegate.counter.modify { $0.published = true }
                    })
                }
                do { for task in batch { try await task.value } }
                catch {
                    batch.forEach { $0.cancel() }
                    for task in batch { _ = try? await task.value }
                    throw error
                }
                save()
            }
            try Data("READY".utf8).write(to: output.appendingPathExtension("ready"), options: .atomic)
            for _ in 0..<max(5, min(60, config.durationSeconds ?? 15)) {
                try await Task.sleep(nanoseconds: 1_000_000_000); save()
            }
            producer?.cancel()
            result.rooms = sessions.map { $0.delegate.counter.snapshot() }
            result.passed = result.rooms.count == 30 && result.rooms.allSatisfy {
                $0.connected && $0.published && $0.decodedPeers == subscriptions && $0.plaintextPublications == 0 && $0.failures == 0
                    && $0.peerFrameCounts.values.allSatisfy { $0 >= 5 }
            }
            for session in sessions { await session.room.disconnect() }
            result.completed = true; save()
            return result.passed
        } catch {
            producer?.cancel()
            for session in sessions { await session.room.disconnect() }
            result.failure = (error as? LiveKitError).map { "LiveKit code \($0.type.rawValue)" } ?? String(reflecting: type(of: error))
            save(); return false
        }
    }
}
