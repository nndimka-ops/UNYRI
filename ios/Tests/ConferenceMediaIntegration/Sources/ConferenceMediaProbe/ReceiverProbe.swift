import AVFoundation
import Foundation
import LiveKit

private struct ReceiverMetrics: Encodable {
    var connected = false
    var completed = false
    var videoFrames = 0
    var videoContentFrames = 0
    var audioBuffers = 0
    var audioToneBuffers = 0
    var maxAudioRMS = 0.0
    var encryptedPublications = 0
    var plaintextPublications = 0
    var failure: String?
    var forcedRelay = false
    var icePolicyRawValue = IceTransportPolicy.all.rawValue
    var manualIceServers = 0
    var icePaths: [SafeICEPath] = []
}

/// This probe records counts and signal energy only; it writes no received
/// pixels, PCM, participant identifiers, room names or cryptographic material.
private final class ReceiverSink: NSObject, VideoRenderer, AudioRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var metrics = ReceiverMetrics()
    @MainActor var isAdaptiveStreamEnabled: Bool { true }
    @MainActor var adaptiveStreamSize: CGSize { CGSize(width: 160, height: 120) }
    func modify(_ body: (inout ReceiverMetrics) -> Void) { lock.lock(); defer { lock.unlock() }; body(&metrics) }
    func snapshot() -> ReceiverMetrics { lock.lock(); defer { lock.unlock() }; return metrics }
    nonisolated func render(frame: VideoFrame) {
        let content = frame.buffer.toI420()?.withUnsafePlanes { planes -> Bool in
            var energy = 0, count = 0
            for y in stride(from: 0, to: Int(planes.height), by: 8) {
                for x in stride(from: 0, to: Int(planes.width), by: 8) {
                    energy += Int(planes.dataY[y * Int(planes.strideY) + x]); count += 1
                }
            }
            return count > 0 && energy / count > 40
        } ?? false
        modify { $0.videoFrames += 1; if content { $0.videoContentFrames += 1 } }
    }
    nonisolated func render(pcmBuffer: AVAudioPCMBuffer) {
        let count = Int(pcmBuffer.frameLength)
        guard count > 0 else { return }
        var energy = 0.0, sine = 0.0, cosine = 0.0
        for index in 0..<count {
            let value: Double
            if let data = pcmBuffer.floatChannelData?[0] { value = Double(data[index]) }
            else if let data = pcmBuffer.int16ChannelData?[0] { value = Double(data[index]) / 32768 }
            else { return }
            energy += value * value
            let phase = 2 * Double.pi * 1000 * Double(index) / pcmBuffer.format.sampleRate
            sine += value * sin(phase); cosine += value * cos(phase)
        }
        let rms = sqrt(energy / Double(count)) * 32768
        let ratio = min(1, 2 * (sine * sine + cosine * cosine) / (Double(count) * max(energy, 1e-20)))
        modify {
            $0.audioBuffers += 1; $0.maxAudioRMS = max($0.maxAudioRMS, rms)
            if rms > 100 && ratio > 0.6 { $0.audioToneBuffers += 1 }
        }
    }
}

private final class ReceiverDelegate: NSObject, RoomDelegate, @unchecked Sendable {
    let sink = ReceiverSink()
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) {
        sink.modify {
            if publication.encryptionType == .gcm { $0.encryptedPublications += 1 }
            else { $0.plaintextPublications += 1 }
        }
        if let video = publication.track as? RemoteVideoTrack { video.add(videoRenderer: sink) }
        if let audio = publication.track as? RemoteAudioTrack { audio.add(audioRenderer: sink) }
    }
}

enum ReceiverProbe {
    @MainActor static func run(_ config: Config, output: URL) async -> Bool {
        let delegate = ReceiverDelegate()
        var room: Room?
        func save() { if let data = try? JSONEncoder().encode(delegate.sink.snapshot()) { try? data.write(to: output, options: .atomic) } }
        do {
            try AudioManager.shared.setManualRenderingMode(true)
            let provider = BaseKeyProvider(options: KeyProviderOptions(sharedKey: true,
                ratchetSalt: Data("LKFrameEncryptionKey".utf8), ratchetWindowSize: 0,
                uncryptedMagicBytes: Data(), discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .pbkdf2))
            provider.setKey(key: config.key, index: 0)
            let connectOptions = RelayProbeOptions.make(autoSubscribe: true, forceRelay: config.forceRelay == true)
            delegate.sink.modify {
                $0.forcedRelay = config.forceRelay == true; $0.icePolicyRawValue = connectOptions.iceTransportPolicy.rawValue
                $0.manualIceServers = connectOptions.iceServers.count
            }
            guard config.forceRelay != true || connectOptions.iceTransportPolicy == .relay else { throw ProbeError.config }
            let active = Room(delegate: delegate, connectOptions: connectOptions,
                roomOptions: RoomOptions(adaptiveStream: false, e2eeOptions: E2EEOptions(keyProvider: provider),
                    reportRemoteTrackStatistics: config.forceRelay == true))
            room = active
            try await active.connect(url: config.url, token: config.token)
            delegate.sink.modify { $0.connected = true }; save()
            try Data("READY".utf8).write(to: output.appendingPathExtension("ready"), options: .atomic)
            for _ in 0..<max(5, min(180, config.durationSeconds ?? 60)) {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                if config.forceRelay == true {
                    let paths = active.remoteParticipants.values.flatMap { $0.videoTracks + $0.audioTracks }.compactMap {
                        SafeICEPath.capture($0.track?.statistics, phase: "receiver-\($0.kind)")
                    }
                    delegate.sink.modify { $0.icePaths = paths }
                }
                save()
            }
            if config.forceRelay == true, !delegate.sink.snapshot().icePaths.contains(where: \.provesRelay) {
                throw RelayProbeOptions.Failure.selectedRelayNotObserved(delegate.sink.snapshot().icePaths.first)
            }
            await active.disconnect()
            delegate.sink.modify { $0.completed = true }; save()
            withExtendedLifetime(delegate) {}
            return true
        } catch {
            await room?.disconnect()
            delegate.sink.modify {
                $0.failure = (error as? LiveKitError).map { "LiveKit code \($0.type.rawValue)" } ?? String(reflecting: type(of: error))
            }
            save(); return false
        }
    }
}
