import AVFoundation
import Foundation
import LiveKit

/// Protected fixture input. No field from this type is ever written to output.
struct FreshConfig: Decodable {
    let apiURL: String
    let accessToken: String
    let inviteSecret: String
    let roomID: UUID
    let key: String
    let phaseSeconds: Int?
    let audio: Bool?
    let startFile: String?
}

private struct FreshGrant: Decodable {
    let livekitUrl: String
    let participantToken: String
    let participantIdentity: String
    let e2eeRequired: Bool
}
private struct FreshMetrics: Encodable {
    var completed = false
    var initialPublish = false
    var sdkRetriesDisabled = false
    var oldRoomDisconnected = false
    var freshAdmission = false
    var freshProvider = false
    var freshRoom = false
    var restoredAudio = false
    var restoredVideo = false
    var newPublication = false
    var canceledPublishStopped = false
    var joinRequests = 0
    var sourceVideoFrames = 0
    var sourceAudioBuffers = 0
    var stage = "admission"
    var failureCode: Int?
}
private final class RejectFreshRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor enum FreshRoomProbe {
    static func run(_ config: FreshConfig, output: URL) async -> Bool {
        var metrics = FreshMetrics()
        func save() {
            if let data = try? JSONEncoder().encode(metrics) { try? data.write(to: output, options: .atomic) }
        }
        var room: Room?
        var producer: Task<Void, Never>?
        var source: Source?
        let session = URLSession(configuration: .ephemeral, delegate: RejectFreshRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            guard let origin = URLComponents(string: config.apiURL), origin.scheme == "https", origin.host != nil,
                  origin.user == nil, origin.password == nil, origin.query == nil, origin.fragment == nil,
                  origin.path.isEmpty || origin.path == "/", config.key.utf8.count == 43, config.inviteSecret.utf8.count == 43 else {
                throw ProbeError.config
            }
            func grant() async throws -> FreshGrant {
                struct Body: Encodable { let invite_secret: String }
                let address = config.apiURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    + "/conferences/\(config.roomID.uuidString.lowercased())/join"
                var request = URLRequest(url: URL(string: address)!)
                request.httpMethod = "POST"; request.timeoutInterval = 30
                request.setValue("Bearer " + config.accessToken, forHTTPHeaderField: "Authorization")
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONEncoder().encode(Body(invite_secret: config.inviteSecret))
                let (data, reply) = try await session.data(for: request)
                guard (reply as? HTTPURLResponse)?.statusCode == 200 else { throw ProbeError.config }
                let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
                let value = try decoder.decode(FreshGrant.self, from: data)
                guard value.e2eeRequired, value.livekitUrl.hasPrefix("wss://"), !value.participantToken.isEmpty else { throw ProbeError.config }
                metrics.joinRequests += 1; return value
            }
            func encryptedRoom() -> (Room, BaseKeyProvider) {
                let provider = BaseKeyProvider(options: KeyProviderOptions(sharedKey: true,
                    ratchetSalt: Data("LKFrameEncryptionKey".utf8), ratchetWindowSize: 0,
                    uncryptedMagicBytes: Data(), discardFrameWhenCryptorNotReady: true, keyDerivationAlgorithm: .pbkdf2))
                provider.setKey(key: config.key, index: 0)
                let value = Room(connectOptions: ConnectOptions(autoSubscribe: false, reconnectAttempts: 0),
                    roomOptions: RoomOptions(defaultVideoPublishOptions: VideoPublishOptions(preferredCodec: .vp8),
                        suspendLocalVideoTracksInBackground: false,
                        e2eeOptions: E2EEOptions(keyProvider: provider, encryptionType: .gcm)))
                return (value, provider)
            }
            func publish(_ active: Room) async throws -> Source {
                let track = await LocalVideoTrack.createBufferTrack(name: "synthetic-fresh-video", source: .camera,
                    options: BufferCaptureOptions(dimensions: Dimensions(width: 160, height: 120), fps: 15))
                guard let capturer = track.capturer as? BufferCapturer else { throw ProbeError.buffer }
                let source = Source(video: track, capturer: capturer); source.audio = config.audio ?? true
                try await track.start(); try source.frame()
                producer = Task { @MainActor in
                    var tick = 0
                    while !Task.isCancelled {
                        source.tone(); if tick % 7 == 0 { try? source.frame() }; tick += 1
                        do { try await Task.sleep(nanoseconds: 10_000_000) } catch { return }
                    }
                }
                _ = try await active.localParticipant.publish(videoTrack: track,
                    options: VideoPublishOptions(encoding: VideoEncoding(maxBitrate: 180_000, maxFps: 15), simulcast: false, preferredCodec: .vp8))
                if source.audio { _ = try await active.localParticipant.setMicrophone(enabled: true) }
                return source
            }
            try AudioManager.shared.setManualRenderingMode(true)
            AudioManager.shared.mixer.micVolume = 0; AudioManager.shared.mixer.appVolume = 1
            let firstGrant = try await grant()
            let (first, firstProvider) = encryptedRoom(); room = first
            try await first.connect(url: firstGrant.livekitUrl, token: firstGrant.participantToken)
            guard first.localParticipant.identity?.stringValue == firstGrant.participantIdentity,
                  first.e2eeManager?.frameEncryptionType == .gcm else { throw ProbeError.config }
            // No first buffer means publication suspends while waiting for
            // dimensions, after capture has started but before a publication
            // exists. Cancellation must stop that otherwise orphaned capturer.
            let canceledTrack = await LocalVideoTrack.createBufferTrack(name: "synthetic-cancel", source: .camera,
                options: BufferCaptureOptions(dimensions: Dimensions(width: 160, height: 120), fps: 15))
            let canceledPublish = Task { try await first.localParticipant.publish(videoTrack: canceledTrack) }
            try await Task.sleep(nanoseconds: 150_000_000)
            canceledPublish.cancel(); _ = try? await canceledPublish.value
            metrics.canceledPublishStopped = canceledTrack.trackState == .stopped && canceledTrack.capturer.captureState == .stopped
                && !first.localParticipant.videoTracks.contains(where: { $0.track === canceledTrack })
            guard metrics.canceledPublishStopped else { throw ProbeError.config }
            try Data("READY".utf8).write(to: output.appendingPathExtension("ready"), options: .atomic)
            if let start = config.startFile {
                let until = Date().addingTimeInterval(180)
                while !FileManager.default.fileExists(atPath: start) {
                    guard Date() < until else { throw ProbeError.config }
                    try await Task.sleep(nanoseconds: 100_000_000)
                }
            }
            let duration = UInt64(max(3, min(15, config.phaseSeconds ?? 5))) * 1_000_000_000
            source = try await publish(first)
            let firstSIDs = Set(first.localParticipant.videoTracks.map { $0.sid.stringValue })
            metrics.initialPublish = true; metrics.stage = "initial-media"; save()
            try await Task.sleep(nanoseconds: duration)
            // Actual SDK network-recovery entry point. Zero must DISCONNECT,
            // never republish behind the app's capture/crypto owner.
            try await first.debug_simulate(scenario: .fullReconnect)
            let until = Date().addingTimeInterval(10)
            while first.connectionState != .disconnected {
                guard Date() < until else { throw ProbeError.reconnect }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            metrics.sdkRetriesDisabled = first.localParticipant.videoTracks.isEmpty
            metrics.oldRoomDisconnected = true; metrics.stage = "fresh-admission"; save()
            producer?.cancel(); await producer?.value
            metrics.sourceVideoFrames += source?.videoFrames ?? 0
            metrics.sourceAudioBuffers += source?.audioBuffers ?? 0
            try? await source?.video.stop(); await first.disconnect()
            // Same sequence as the controller, exercised with real SDK media
            // and a real, freshly authenticated backend grant.
            let nextGrant = try await grant(); metrics.freshAdmission = metrics.joinRequests == 2
            let (next, nextProvider) = encryptedRoom(); room = next
            metrics.freshRoom = next !== first; metrics.freshProvider = nextProvider !== firstProvider
            try await next.connect(url: nextGrant.livekitUrl, token: nextGrant.participantToken)
            guard next.localParticipant.identity?.stringValue == nextGrant.participantIdentity,
                  next.e2eeManager?.frameEncryptionType == .gcm else { throw ProbeError.config }
            source = try await publish(next)
            metrics.restoredVideo = !next.localParticipant.videoTracks.isEmpty
            metrics.restoredAudio = !(config.audio ?? true) || !next.localParticipant.audioTracks.isEmpty
            metrics.newPublication = next.localParticipant.videoTracks.allSatisfy { !firstSIDs.contains($0.sid.stringValue) }
            metrics.stage = "fresh-media"; save()
            try await Task.sleep(nanoseconds: duration)
            producer?.cancel(); await producer?.value
            metrics.sourceVideoFrames += source?.videoFrames ?? 0
            metrics.sourceAudioBuffers += source?.audioBuffers ?? 0
            try? await source?.video.stop(); await next.disconnect()
            metrics.completed = metrics.sdkRetriesDisabled && metrics.freshAdmission && metrics.freshProvider && metrics.freshRoom
                && metrics.restoredAudio && metrics.restoredVideo && metrics.newPublication && metrics.canceledPublishStopped
            metrics.stage = "finished"; save(); return metrics.completed
        } catch {
            producer?.cancel(); await producer?.value
            try? await source?.video.stop(); await room?.disconnect()
            metrics.failureCode = (error as? LiveKitError)?.type.rawValue ?? -1
            save(); return false
        }
    }
}
