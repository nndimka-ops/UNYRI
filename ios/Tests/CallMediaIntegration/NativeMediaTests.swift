import AVFoundation
import Combine
import CoreVideo
import WebRTC
import XCTest
@testable import UNYRIMediaQA

/// Opt-in, two distinct native peers over the public COCO signaling service.
/// iOS Simulator receives the real phone camera; its outbound video is synthetic.
/// RTP counters do not prove acoustic loudness or physical iPhone camera behavior.
@MainActor final class NativeMediaTests: XCTestCase {
    private var engine: WebRTCEngineImpl!
    private var socket: URLSessionWebSocketTask!
    private var fixture: [String: Any] = [:]
    private var callID: String?
    private var remoteSet = false
    private var pendingIce: [[String: Any]] = []
    private var subscriptions = Set<AnyCancellable>()
    private var producer: Task<Void, Never>?
    private var receiver: Task<Void, Never>?
    private var keeper: Task<Void, Never>?
    private var failure: Error?
    private var connected = false
    private let sink = FrameCounter()

    private func send(_ object: [String: Any]) async throws {
        let bytes = try JSONSerialization.data(withJSONObject: object)
        try await socket.send(.string(String(decoding: bytes, as: UTF8.self)))
    }
    private func post(_ path: String) async throws {
        var req = URLRequest(url: URL(string: (fixture["api"] as! String) + path)!)
        req.httpMethod = "POST"; req.httpBody = Data("{}".utf8)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer " + (fixture["access_token"] as! String), forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.data(for: req)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    private func peerConnection() throws -> RTCPeerConnection {
        for entry in Mirror(reflecting: engine!).children where entry.label == "peerConnection" {
            let optional = Mirror(reflecting: entry.value)
            if let pc = optional.children.first?.value as? RTCPeerConnection { return pc }
        }
        throw NSError(domain: "COCO media QA", code: 1)
    }
    private func stats() async throws -> [String: Double] {
        let pc = try peerConnection()
        let report: RTCStatisticsReport = await withCheckedContinuation { done in pc.statistics { done.resume(returning: $0) } }
        var result: [String: Double] = [:]
        for row in report.statistics.values where row.type == "inbound-rtp" || row.type == "outbound-rtp" {
            let kind = (row.values["kind"] as? String) ?? (row.values["mediaType"] as? String) ?? "unknown"
            for key in ["bytesSent", "bytesReceived", "packetsSent", "packetsReceived", "framesEncoded", "framesDecoded"] {
                if let n = row.values[key] as? NSNumber { result[row.type + "." + kind + "." + key, default: 0] += n.doubleValue }
            }
        }
        return result
    }
    private func produceSyntheticVideo(_ pc: RTCPeerConnection) {
        let factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
        let source = factory.videoSource(); source.adaptOutputFormat(toWidth: 320, height: 240, fps: 10)
        let capturer = RTCVideoCapturer(delegate: source)
        let track = factory.videoTrack(with: source, trackId: "owned-simulator-synthetic-video")
        pc.add(track, streamIds: ["owned-media-qa"])
        producer = Task { @MainActor in
            var sequence = 0
            while !Task.isCancelled {
                var pixel: CVPixelBuffer?
                let status = CVPixelBufferCreate(kCFAllocatorDefault, 320, 240, kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel)
                if status == kCVReturnSuccess, let pixel {
                    CVPixelBufferLockBaseAddress(pixel, [])
                    if let base = CVPixelBufferGetBaseAddress(pixel) {
                        memset(base, Int32(sequence % 200 + 30), CVPixelBufferGetBytesPerRow(pixel) * 240)
                    }
                    CVPixelBufferUnlockBaseAddress(pixel, [])
                    let frame = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixel), rotation: ._0,
                        timeStampNs: Int64(ProcessInfo.processInfo.systemUptime * 1_000_000_000))
                    source.capturer(capturer, didCapture: frame)
                }
                sequence += 1
                _ = factory; _ = track
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
    }
    private func process(_ row: [String: Any]) async throws {
        guard let type = row["type"] as? String else { return }
        if type == "call.ringing" {
            guard callID == nil else { return }
            callID = row["call_id"] as? String
            let data = try JSONSerialization.data(withJSONObject: fixture["ice_servers"]!)
            engine.initialize(iceServers: try JSONDecoder().decode([CallICEServer].self, from: data), mediaKind: .video)
            engine.setSpeakerOn(false)
            engine.attachPictureInPictureRenderer(sink)
            produceSyntheticVideo(try peerConnection())
            try await post("/calls/" + callID! + "/accept")
            print("UNYRI_NATIVE_MEDIA_ACCEPTED")
        }
        guard let callID, row["call_id"] as? String == callID else { return }
        switch type {
        case "call.offer":
            try await engine.setRemoteDescription(type: "offer", sdp: row["sdp"] as! String)
            remoteSet = true
            for ice in pendingIce { apply(ice) }; pendingIce.removeAll()
            let answer = try await engine.createAnswer()
            try await send(["type": "call.answer", "call_id": callID, "sdp": answer])
        case "call.ice": if remoteSet { apply(row) } else { pendingIce.append(row) }
        default: break
        }
    }
    private func apply(_ row: [String: Any]) {
        engine.addIceCandidate(candidate: row["candidate"] as! String, sdpMid: row["sdp_mid"] as? String,
            sdpMLineIndex: row["sdp_m_line_index"] as? Int)
    }
    func testAudioConfigurationBeforeAnyMediaStart() throws {
        let rtc=RTCAudioSession.sharedInstance()
        let previousManual=rtc.useManualAudio
        rtc.useManualAudio=true
        defer { rtc.useManualAudio=previousManual }
        try UnyriCallAudioSession.configure(outputOverride:.none)
        let session=AVAudioSession.sharedInstance()
        XCTAssertEqual(session.category,.playAndRecord)
        XCTAssertEqual(session.mode,.voiceChat)
        XCTAssertFalse(session.categoryOptions.contains(.defaultToSpeaker))
        XCTAssertTrue(rtc.useManualAudio,"Preparation must preserve CallKit activation ownership")
        print("UNYRI_AUDIO_CONFIG_COMPLETE voiceChat=true defaultToSpeaker=false manualActivationPreserved=true acousticVolumeMeasured=false")
    }
    func testRealPhoneToSimulatorMedia() async throws { try await exerciseMedia(requireDuplexAudio:true) }
    func testRealPhoneToSimulatorVideo() async throws { try await exerciseMedia(requireDuplexAudio:false) }
    private func exerciseMedia(requireDuplexAudio:Bool) async throws {
        continueAfterFailure = false
        guard let url = Bundle(for: Self.self).url(forResource: "owned-media-fixture", withExtension: "json") else { throw XCTSkip("Explicit owned fixture required") }
        fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        XCTAssertTrue((fixture["username"] as! String).hasPrefix("qa_media_"))
        XCTAssertEqual(fixture["role"] as? String, "callee")
        engine = WebRTCEngineImpl(); RTCInitializeSSL()
        RTCAudioSession.sharedInstance().useManualAudio = false
        RTCAudioSession.sharedInstance().isAudioEnabled = true
        var req = URLRequest(url: URL(string: fixture["ws"] as! String)!)
        req.setValue("Bearer " + (fixture["access_token"] as! String), forHTTPHeaderField: "Authorization")
        socket = URLSession.shared.webSocketTask(with: req); socket.resume()
        engine.events.sink { [weak self] event in
            guard let self else { return }
            switch event {
            case .localIceCandidate(let candidate, let mid, let index):
                if let id = self.callID { Task { try? await self.send(["type":"call.ice", "call_id":id,"candidate":candidate,"sdp_mid":mid as Any? ?? NSNull(),"sdp_m_line_index":index as Any? ?? NSNull()]) } }
            case .connectionStateChanged(.connected): self.connected = true
            default: break
            }
        }.store(in: &subscriptions)
        receiver = Task { @MainActor in
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    let bytes: Data
                    switch message { case .data(let data): bytes=data; case .string(let text): bytes=Data(text.utf8); @unknown default: continue }
                    if let row = try JSONSerialization.jsonObject(with: bytes) as? [String:Any] { try await process(row) }
                }
            } catch { if !Task.isCancelled { self.failure = error } }
        }
        keeper = Task { @MainActor in
            while !Task.isCancelled {
                if let id=callID { try? await send(["type":"call.keep_alive","call_id":id]) }
                else { try? await send(["type":"PING","request_id":"owned-media-ready"]) }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
        defer {
            receiver?.cancel(); keeper?.cancel(); producer?.cancel()
            socket?.cancel(with: .normalClosure, reason: nil)
            engine.dispose(); subscriptions.removeAll()
        }
        print("UNYRI_NATIVE_MEDIA_READY callee=true productionEngine=true")
        let deadline=Date().addingTimeInterval(180)
        while !connected && failure == nil && Date()<deadline { try await Task.sleep(nanoseconds: 200_000_000) }
        if let failure { throw failure }; XCTAssertTrue(connected, "Real ICE connection required")
        try await post("/calls/" + callID! + "/connected")
        try await Task.sleep(nanoseconds: 4_000_000_000)
        let before = try await stats()
        try await Task.sleep(nanoseconds: 8_000_000_000)
        let after = try await stats()
        print("UNYRI_NATIVE_MEDIA_RESULT " + String(decoding:try JSONSerialization.data(withJSONObject:["before":before,"after":after,"decodedFramesAtSink":sink.count,"physicalIPhone":false,"outboundVideoSynthetic":true,"requiresDuplexAudio":requireDuplexAudio],options:.sortedKeys),as:UTF8.self))
        var required=["inbound-rtp.audio.bytesReceived", "inbound-rtp.video.framesDecoded", "outbound-rtp.video.framesEncoded"]
        if requireDuplexAudio { required.append("outbound-rtp.audio.bytesSent") }
        for key in required {
            XCTAssertGreaterThan(after[key, default:0], before[key, default:0], key)
        }
        XCTAssertGreaterThan(sink.count, 5)
        try await Task.sleep(nanoseconds: 12_000_000_000)
        print("UNYRI_NATIVE_MEDIA_COMPLETE")
    }
}

private final class FrameCounter: NSObject, RTCVideoRenderer {
    private let lock=NSLock(); private var frames=0
    var count:Int { lock.lock();defer{lock.unlock()};return frames }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame:RTCVideoFrame?) { if frame != nil {lock.lock();frames += 1;lock.unlock()} }
}
