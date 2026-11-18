import AVFoundation
import CoreVideo
import Foundation
import LiveKit

/// Synthetic network probe only. Never opens a camera or microphone, and never
/// prints configuration, room names, JWTs, URLs or key material.
struct Config: Decodable {
    let url: String
    let token: String
    let key: String
    let startFile: String?
    let phaseSeconds: Int?
    let audio: Bool?
    let mode: String?
    let durationSeconds: Int?
    let forceRelay: Bool?
}
private struct ProbeMode: Decodable { let mode: String? }
struct Metrics: Encodable {
    var connected = false
    var initialPublish = false
    var republish = false
    var fullReconnect = false
    var syntheticVideoFrames = 0
    var syntheticAudioBuffers = 0
    var completed = false
    var failure: String?
    var stage = "connect"
    var forcedRelay = false
    var icePolicyRawValue = IceTransportPolicy.all.rawValue
    var manualIceServers = 0
    var icePaths: [SafeICEPath] = []
}
@MainActor
final class Source {
    let video: LocalVideoTrack
    let capturer: BufferCapturer
    var videoFrames = 0
    var audioBuffers = 0
    var audio = false
    var phase: Double = 0
    init(video: LocalVideoTrack, capturer: BufferCapturer) { self.video = video; self.capturer = capturer }
    func frame() throws {
        var value: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, 160, 120, kCVPixelFormatType_32BGRA, attributes, &value) == kCVReturnSuccess,
              let value else { throw ProbeError.buffer }
        CVPixelBufferLockBaseAddress(value, [])
        if let data = CVPixelBufferGetBaseAddress(value) {
            let row = CVPixelBufferGetBytesPerRow(value) / 4
            let pixels = data.assumingMemoryBound(to: UInt32.self)
            for y in 0..<120 { for x in 0..<160 { pixels[y * row + x] = 0xFFCC8844 } }
        }
        CVPixelBufferUnlockBaseAddress(value, [])
        capturer.capture(value)
        videoFrames += 1
    }
    func tone() {
        guard audio, let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480), let samples = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = 480
        for index in 0..<480 {
            samples[index] = Float(sin(phase) * 0.45)
            phase += 2 * .pi * 1000 / 48_000
            if phase > 2 * .pi { phase -= 2 * .pi }
        }
        AudioManager.shared.mixer.capture(appAudio: buffer)
        audioBuffers += 1
    }
}
enum ProbeError: Error { case arguments, config, buffer, reconnect }

@main
struct Probe {
    @MainActor static func main() async {
        guard CommandLine.arguments.count == 3 else { print("PROBE_ARGUMENT_ERROR"); exit(2) }
        let configPath = URL(fileURLWithPath: CommandLine.arguments[1])
        let output = URL(fileURLWithPath: CommandLine.arguments[2])
        var metrics = Metrics()
        func save() { if let data = try? JSONEncoder().encode(metrics) { try? data.write(to: output, options: .atomic) } }
        LiveKitSDK.disableLogging()
        var room: Room?
        var producer: Task<Void, Never>?
        do {
            let data = try Data(contentsOf: configPath)
            if try JSONDecoder().decode(ProbeMode.self, from: data).mode == "fresh" {
                let succeeded = await FreshRoomProbe.run(try JSONDecoder().decode(FreshConfig.self, from: data), output: output)
                print(succeeded ? "FRESH_ROOM_PROBE_COMPLETE" : "FRESH_ROOM_PROBE_FAILED")
                if !succeeded { exit(1) }
                return
            }
            if try JSONDecoder().decode(ProbeMode.self, from: data).mode == "bench" {
                let succeeded = await BenchProbe.run(try JSONDecoder().decode(BenchConfig.self, from: data), output: output)
                print(succeeded ? "BENCH_PROBE_COMPLETE" : "BENCH_PROBE_FAILED")
                if !succeeded { exit(1) }
                return
            }
            let config = try JSONDecoder().decode(Config.self, from: data)
            if config.mode == "receiver" {
                let succeeded = await ReceiverProbe.run(config, output: output)
                print(succeeded ? "RECEIVER_PROBE_COMPLETE" : "RECEIVER_PROBE_FAILED")
                if !succeeded { exit(1) }
                return
            }
            guard config.key.utf8.count == 43 else { throw ProbeError.config }
            try AudioManager.shared.setManualRenderingMode(true)
            AudioManager.shared.mixer.micVolume = 0
            AudioManager.shared.mixer.appVolume = 1
            let provider = BaseKeyProvider(options: KeyProviderOptions(sharedKey: true,
                ratchetSalt: Data("LKFrameEncryptionKey".utf8), ratchetWindowSize: 0,
                uncryptedMagicBytes: Data(), discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .pbkdf2))
            provider.setKey(key: config.key, index: 0)
            let connectOptions = RelayProbeOptions.make(autoSubscribe: false, forceRelay: config.forceRelay == true)
            metrics.forcedRelay = config.forceRelay == true
            metrics.icePolicyRawValue = connectOptions.iceTransportPolicy.rawValue
            metrics.manualIceServers = connectOptions.iceServers.count
            guard !metrics.forcedRelay || connectOptions.iceTransportPolicy == .relay else { throw ProbeError.config }
            let active = Room(connectOptions: connectOptions, roomOptions: RoomOptions(
                adaptiveStream: true, dynacast: true, e2eeOptions: E2EEOptions(keyProvider: provider)))
            room = active
            try await active.connect(url: config.url, token: config.token)
            metrics.connected = true; save()
            try Data("READY".utf8).write(to: output.appendingPathExtension("ready"), options: .atomic)
            if let start = config.startFile {
                let deadline = Date().addingTimeInterval(120)
                while !FileManager.default.fileExists(atPath: start) {
                    guard Date() < deadline else { throw ProbeError.config }
                    try await Task.sleep(nanoseconds: 100_000_000)
                }
            }
            let track = await LocalVideoTrack.createBufferTrack(name: "synthetic-video", source: .camera,
                options: BufferCaptureOptions(dimensions: Dimensions(width: 160, height: 120), fps: 15),
                reportStatistics: config.forceRelay == true)
            guard let capturer = track.capturer as? BufferCapturer else { throw ProbeError.buffer }
            let source = Source(video: track, capturer: capturer)
            source.audio = config.audio ?? true
            try await track.start()
            try source.frame()
            producer = Task { @MainActor in
                var ticks = 0
                while !Task.isCancelled {
                    source.tone()
                    if ticks % 7 == 0 { try? source.frame() }
                    ticks += 1
                    do { try await Task.sleep(nanoseconds: 10_000_000) } catch { return }
                }
            }
            let options = VideoPublishOptions(encoding: VideoEncoding(maxBitrate: 180_000, maxFps: 15),
                simulcast: false, preferredCodec: .vp8)
            var publication = try await active.localParticipant.publish(videoTrack: track, options: options)
            metrics.stage = "audio-publish"; save()
            if source.audio { _ = try await active.localParticipant.setMicrophone(enabled: true) }
            metrics.initialPublish = true; save()
            let duration = UInt64(max(2, min(15, config.phaseSeconds ?? 4))) * 1_000_000_000
            try await Task.sleep(nanoseconds: duration)
            if metrics.forcedRelay {
                metrics.icePaths.append(try await RelayProbeOptions.requireSelectedRelay(track: track, phase: "initial")); save()
            }
            try await active.localParticipant.unpublish(publication: publication)
            metrics.stage = "republish"; save()
            publication = try await active.localParticipant.publish(videoTrack: track, options: options)
            metrics.republish = true; save()
            try await Task.sleep(nanoseconds: duration)
            if metrics.forcedRelay {
                metrics.icePaths.append(try await RelayProbeOptions.requireSelectedRelay(track: track, phase: "republish")); save()
            }
            let beforeReconnect = Set(active.localParticipant.videoTracks.map { $0.sid.stringValue })
            try await active.debug_simulate(scenario: .fullReconnect)
            metrics.stage = "full-reconnect"; save()
            let deadline = Date().addingTimeInterval(25)
            while active.connectionState != .connected || !active.localParticipant.videoTracks.contains(where: {
                !beforeReconnect.contains($0.sid.stringValue) && $0.track != nil
            }) {
                guard Date() < deadline else { throw ProbeError.reconnect }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            metrics.fullReconnect = true; save()
            try await Task.sleep(nanoseconds: duration)
            if metrics.forcedRelay {
                metrics.icePaths.append(try await RelayProbeOptions.requireSelectedRelay(track: track, phase: "full-reconnect")); save()
            }
            producer?.cancel()
            metrics.syntheticVideoFrames = source.videoFrames
            metrics.syntheticAudioBuffers = source.audioBuffers
            await active.disconnect()
            metrics.completed = true; save()
            print("SYNTHETIC_PROBE_COMPLETE")
        } catch {
            producer?.cancel(); await room?.disconnect()
            if case let RelayProbeOptions.Failure.selectedRelayNotObserved(path) = error, let path {
                metrics.icePaths.append(path)
            }
            metrics.failure = (error as? LiveKitError).map { "LiveKit code \($0.type.rawValue)" }
                ?? String(reflecting: type(of: error)); save()
            print("SYNTHETIC_PROBE_FAILED"); exit(1)
        }
    }
}
