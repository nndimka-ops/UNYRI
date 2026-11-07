import Foundation
import WebRTC

/// This connection is data-only and intentionally has no relationship to the
/// call engine, AVAudioSession, camera, microphone or CallKit.
enum DirectFilePeerPolicy {
    static let label = "coco-file-v1"
    static let maxFrameBytes = 32 * 1024
    static let highWaterBytes: UInt64 = 512 * 1024
    static let lowWaterBytes: UInt64 = 128 * 1024

    static func directCandidate(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 4096,
              !value.contains("\r"), !value.contains("\n"),
              value.hasPrefix("candidate:") || value.hasPrefix("a=candidate:") else { return false }
        let fields = value.split(whereSeparator: { $0 == " " || $0 == "\t" })
        guard fields.count >= 8, (fields.count - 8).isMultiple(of: 2), fields[6] == "typ",
              fields[1] == "1", ["udp", "tcp"].contains(fields[2].lowercased()),
              UInt32(fields[3]) != nil, let port = UInt16(fields[5]), port > 0,
              ["host", "srflx", "prflx"].contains(String(fields[7])),
              fields[4].range(of: "^[A-Za-z0-9:.%_\\-]+$", options: .regularExpression) != nil else { return false }
        let foundation = fields[0].replacingOccurrences(of: "a=", with: "").dropFirst("candidate:".count)
        guard !foundation.isEmpty, foundation.count <= 64,
              foundation.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "/") }) else { return false }
        var seen = Set<String>()
        for index in stride(from: 8, to: fields.count, by: 2) {
            let key = String(fields[index]), item = String(fields[index + 1])
            guard seen.insert(key).inserted,
                  ["raddr", "rport", "tcptype", "generation", "ufrag", "network-id", "network-cost"].contains(key),
                  item.range(of: "^[A-Za-z0-9:.%_+\\-/]+$", options: .regularExpression) != nil else { return false }
            if key == "rport", UInt16(item) == nil { return false }
            if key == "tcptype", !["active", "passive", "so"].contains(item) { return false }
        }
        return true
    }

    static func dataOnlyDescription(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 32 * 1024,
              value.unicodeScalars.allSatisfy({ $0.value == 9 || $0.value == 10 || $0.value == 13 || (32...126).contains($0.value) }) else { return false }
        let lines = value.components(separatedBy: .newlines).filter { !$0.isEmpty }
        let media = lines.filter { $0.hasPrefix("m=") }
        let fingerprints = lines.filter { $0.hasPrefix("a=fingerprint:") }
        guard media.count == 1,
              media[0].range(of: "^m=application [0-9]{1,5} (UDP/DTLS/SCTP|TCP/DTLS/SCTP) webrtc-datachannel$", options: .regularExpression) != nil,
              !fingerprints.isEmpty, Set(fingerprints.map { $0.lowercased() }).count == 1,
              fingerprints.allSatisfy({ $0.range(of: "^a=fingerprint:sha-256 ([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}$", options: .regularExpression) != nil }),
              lines.contains(where: { $0.hasPrefix("a=ice-ufrag:") && $0.count > 12 }),
              lines.contains(where: { $0.hasPrefix("a=ice-pwd:") && $0.count > 10 }),
              lines.contains(where: { $0.range(of: "^a=sctp-port:[0-9]{1,5}$", options: .regularExpression) != nil }) else { return false }
        return lines.filter { $0.hasPrefix("a=candidate:") }.allSatisfy(directCandidate)
    }

    static func stunURLs(_ urls: [String]) -> [String] {
        Array(Set(urls.filter {
            $0.utf8.count <= 512 && !$0.contains(where: { $0.isWhitespace }) &&
                ($0.lowercased().hasPrefix("stun:") || $0.lowercased().hasPrefix("stuns:"))
        })).sorted()
    }
}

@MainActor
final class DirectFilePeer: NSObject {
    enum Failure: Error { case closed, negotiation, nonDirectRoute, routeNotReady, protocolViolation, congested }
    enum State { case connecting, connected, disconnected, failed, closed }

    var onCandidate: ((String, String?, Int) -> Void)?
    var onIceGatheringComplete: (() -> Void)?
    var onState: ((State) -> Void)?
    private(set) var failureReason = "no_direct_route"
    /// Optional bounded QA diagnostics; never includes SDP, file data or IDs.
    var onDiagnostic: ((String) -> Void)?
    var onPacket: (@Sendable (Data) async throws -> Void)? { didSet { pipe.configure(handler: onPacket) } }

    private let factory: RTCPeerConnectionFactory
    private var connection: RTCPeerConnection?
    private var channel: RTCDataChannel?
    private let initiator: Bool
    private let trickleICE: Bool
    private var fastConnectionDeadline: Task<Void, Never>?
    var timingID: String?
    var timingOutgoing = false
    private var timingChannelObserved = false
    private var timingRouteObserved = false
    private var closed = false
    private var directPathVerified = false
    private var pathEpoch: UInt64 = 0
    nonisolated private let pipe = DirectFilePeerPipe()

    init(stunURLs: [String], initiator: Bool, trickleICE: Bool = false) throws {
        self.initiator = initiator; self.trickleICE = trickleICE
        factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory())
        super.init()
        pipe.configure(failure: { [weak self] error in Task { @MainActor in self?.failed(error) } })
        let configuration = RTCConfiguration()
        // There is deliberately no TURN URL, credential or relay fallback.
        let urls = DirectFilePeerPolicy.stunURLs(stunURLs)
        configuration.iceServers = urls.isEmpty ? [] : [RTCIceServer(urlStrings: urls)]
        configuration.iceTransportPolicy = .all
        configuration.bundlePolicy = .maxBundle
        configuration.rtcpMuxPolicy = .require
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherOnce
        guard let created = factory.peerConnection(with: configuration,
            constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: self) else {
            throw Failure.negotiation
        }
        connection = created
        if initiator {
            let settings = RTCDataChannelConfiguration()
            settings.isOrdered = true
            settings.protocol = DirectFilePeerPolicy.label
            guard let data = created.dataChannel(forLabel: DirectFilePeerPolicy.label, configuration: settings) else {
                created.close(); connection = nil; throw Failure.negotiation
            }
            install(data)
        }
        if trickleICE {
            fastConnectionDeadline = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                guard let self, !self.closed, !self.directPathVerified else { return }
                self.failed()
            }
        }
    }

    var bufferedBytes: UInt64 { channel?.bufferedAmount ?? 0 }
    var isOpen: Bool { !closed && channel?.readyState == .open }

    func createOffer() async throws -> String {
        try await DirectFileTiming.measureAsync(.sdpOffer, id: timingID, outgoing: timingOutgoing) { try await localDescription(offer: true) }
    }
    func createAnswer() async throws -> String {
        try await DirectFileTiming.measureAsync(.sdpAnswer, id: timingID, outgoing: timingOutgoing) { try await localDescription(offer: false) }
    }

    private func localDescription(offer: Bool) async throws -> String {
        guard let pc = connection, !closed else { throw Failure.closed }
        let constraints = RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "false", "OfferToReceiveVideo": "false"], optionalConstraints: nil)
        let description = try await withCheckedThrowingContinuation { (result: CheckedContinuation<RTCSessionDescription, Error>) in
            let completed: (RTCSessionDescription?, Error?) -> Void = { value, error in
                if let value { result.resume(returning: value) }
                else { result.resume(throwing: error ?? Failure.negotiation) }
            }
            if offer { pc.offer(for: constraints, completionHandler: completed) }
            else { pc.answer(for: constraints, completionHandler: completed) }
        }
        guard connection === pc, !closed, DirectFilePeerPolicy.dataOnlyDescription(description.sdp) else {
            throw Failure.protocolViolation
        }
        try await withCheckedThrowingContinuation { (result: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(description) { error in
                if let error { result.resume(throwing: error) } else { result.resume() }
            }
        }
        guard connection === pc, !closed else { throw Failure.closed }
        if trickleICE {
            let initial = pc.localDescription?.sdp ?? description.sdp
            guard DirectFilePeerPolicy.dataOnlyDescription(initial) else { throw Failure.protocolViolation }
            return initial
        }
        // Full SDP is sent once through Signal instead of emitting one Signal
        // packet per candidate. An ICE restart creates a fresh owned peer.
        for _ in 0..<120 {
            try Task.checkCancellation()
            guard connection === pc, !closed else { throw Failure.closed }
            if pc.iceGatheringState == .complete, let complete = pc.localDescription?.sdp {
                guard DirectFilePeerPolicy.dataOnlyDescription(complete) else { throw Failure.protocolViolation }
                return complete
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw Failure.negotiation
    }

    func setRemoteDescription(_ sdp: String, offer: Bool) async throws {
        let timing = DirectFileTiming.begin(.remoteDescription, id: timingID, outgoing: timingOutgoing, status: offer ? 1 : 0)
        var timingSuccess = false
        defer { DirectFileTiming.end(timing, success: timingSuccess) }
        guard let pc = connection, !closed else { throw Failure.closed }
        guard DirectFilePeerPolicy.dataOnlyDescription(sdp) else { throw Failure.protocolViolation }
        try await withCheckedThrowingContinuation { (result: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(RTCSessionDescription(type: offer ? .offer : .answer, sdp: sdp)) { error in
                if let error { result.resume(throwing: error) } else { result.resume() }
            }
        }
        guard connection === pc, !closed else { throw Failure.closed }
        timingSuccess = true
    }

    func addCandidate(_ value: String, mid: String?, index: Int) async throws {
        guard let pc = connection, !closed else { throw Failure.closed }
        guard DirectFilePeerPolicy.directCandidate(value), (0...16).contains(index),
              mid == nil || (mid!.utf8.count <= 64 && !mid!.contains(where: { $0.isNewline })) else {
            throw Failure.nonDirectRoute
        }
        try await withCheckedThrowingContinuation { (result: CheckedContinuation<Void, Error>) in
            pc.add(RTCIceCandidate(sdp: value, sdpMLineIndex: Int32(index), sdpMid: mid)) { error in
                if let error { result.resume(throwing: error) } else { result.resume() }
            }
        }
        guard connection === pc, !closed else { throw Failure.closed }
    }

    /// Not a getStats "succeeded pair" guess: the transport must identify the
    /// actual selected pair and BOTH selected candidates must be non-relay.
    func verifyDirectPath() async throws {
        guard let pc = connection, !closed else { throw Failure.closed }
        let epoch = pathEpoch
        let timingID = self.timingID, timingOutgoing = self.timingOutgoing
        let direct = await withCheckedContinuation { (result: CheckedContinuation<Int, Never>) in
            pc.statistics { report in
                let statistics = report.statistics
                for transport in statistics.values where transport.type == "transport" {
                    DirectFileTiming.mark(.dtlsStateObserved, id: timingID, outgoing: timingOutgoing,
                        status: DirectFileTiming.dtls(transport.values["dtlsState"] as? String))
                }
                let selected = statistics.values.filter { $0.type == "transport" }.compactMap {
                    $0.values["selectedCandidatePairId"] as? String
                }
                let valid = !selected.isEmpty && selected.allSatisfy { identifier in
                    guard let pair = statistics[identifier], pair.type == "candidate-pair",
                          pair.values["state"] as? String == "succeeded",
                          let localID = pair.values["localCandidateId"] as? String,
                          let remoteID = pair.values["remoteCandidateId"] as? String,
                          let local = statistics[localID], let remote = statistics[remoteID],
                          let localType = local.values["candidateType"] as? String,
                          let remoteType = remote.values["candidateType"] as? String else { return false }
                    return ["host", "srflx", "prflx"].contains(localType) &&
                        ["host", "srflx", "prflx"].contains(remoteType)
                }
                result.resume(returning: selected.isEmpty ? 0 : valid ? 1 : -1)
            }
        }
        guard connection === pc, !closed else { throw Failure.closed }
        guard pathEpoch == epoch else { onDiagnostic?("selected_pair_epoch_changed"); throw Failure.routeNotReady }
        guard direct != 0 else { throw Failure.routeNotReady }
        guard direct == 1 else { throw Failure.nonDirectRoute }
        directPathVerified = true; pipe.verifyRoute(true)
        if !timingRouteObserved {
            timingRouteObserved = true
            DirectFileTiming.mark(.directVerified, id: timingID, outgoing: timingOutgoing)
        }
        onDiagnostic?("selected_pair_verified epoch=\(epoch)")
    }

    private func channelOpened() async {
        if !timingChannelObserved {
            timingChannelObserved = true
            DirectFileTiming.mark(.channelOpen, id: timingID, outgoing: timingOutgoing)
        }
        for _ in 0..<20 {
            guard isOpen else { return }
            do { try await verifyDirectPath(); onState?(.connected); return }
            catch Failure.routeNotReady { try? await Task.sleep(nanoseconds: 100_000_000) }
            catch { onDiagnostic?("channel_route_failure \(error)"); failed(); return }
        }
        onDiagnostic?("channel_route_timeout"); failed()
    }

    nonisolated func send(_ packet: Data, execution: DirectFileExecutionPermit? = nil) async throws {
        try await pipe.send(packet, execution: execution)
    }

    func close() {
        guard !closed else { return }
        closed = true; pathEpoch &+= 1; directPathVerified = false
        fastConnectionDeadline?.cancel(); fastConnectionDeadline = nil
        pipe.close()
        channel?.delegate = nil; channel?.close(); channel = nil
        connection?.delegate = nil; connection?.close(); connection = nil
        onState?(.closed)
        onCandidate = nil; onIceGatheringComplete = nil; onPacket = nil; onState = nil
    }

    private func install(_ data: RTCDataChannel) {
        channel = data; data.delegate = self; pipe.install(data)
        if data.readyState == .open { Task { @MainActor in await channelOpened() } }
    }

    private func failed(_ error: Error? = nil) {
        if let error {
            guard let reason = DirectFileSenderFailurePolicy.reason(error, during: .checkpoint) else { return }
            failureReason = reason
            onDiagnostic?("pipe_failure " + reason)
        }
        onState?(.failed)
        close()
    }

}

extension DirectFilePeer: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        Task { @MainActor in if self.connection === peerConnection { self.failed() } }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { return }
            DirectFileTiming.mark(.iceConnectionState, id: self.timingID, outgoing: self.timingOutgoing, status: Int(newState.rawValue))
            switch newState {
            case .connected, .completed:
                if self.isOpen { await self.channelOpened() }
            case .disconnected: self.directPathVerified = false; self.pipe.verifyRoute(false); self.pathEpoch &+= 1; self.onState?(.disconnected)
            case .failed: self.failed()
            case .closed: self.close()
            default: self.onState?(.connecting)
            }
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { return }
            DirectFileTiming.mark(.iceGatheringState, id: self.timingID, outgoing: self.timingOutgoing, status: Int(newState.rawValue))
        }
        guard newState == .complete else { return }
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { return }
            self.onIceGatheringComplete?()
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { return }
            guard DirectFilePeerPolicy.directCandidate(candidate.sdp) else { self.failed(); return }
            self.onCandidate?(candidate.sdp, candidate.sdpMid, Int(candidate.sdpMLineIndex))
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { dataChannel.close(); return }
            guard !self.initiator, self.channel == nil, dataChannel.label == DirectFilePeerPolicy.label,
                  dataChannel.isOrdered, dataChannel.isReliable, !dataChannel.isNegotiated,
                  dataChannel.protocol == DirectFilePeerPolicy.label else { self.failed(); return }
            self.install(dataChannel)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate,
        remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason: String) {
        Task { @MainActor in
            guard self.connection === peerConnection, !self.closed else { return }
            self.pathEpoch &+= 1; self.directPathVerified = false; self.pipe.verifyRoute(false)
            self.onDiagnostic?("selected_pair_change epoch=\(self.pathEpoch)")
            guard DirectFilePeerPolicy.directCandidate(local.sdp), DirectFilePeerPolicy.directCandidate(remote.sdp) else {
                self.failed(); return
            }
            if self.isOpen { await self.channelOpened() }
        }
    }
}

extension DirectFilePeer: RTCDataChannelDelegate {
    nonisolated func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        Task { @MainActor in
            guard self.channel === dataChannel, !self.closed else { return }
            if dataChannel.readyState == .open {
                await self.channelOpened()
            } else if dataChannel.readyState == .closed { self.close() }
        }
    }
    nonisolated func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        pipe.receive(buffer.data, binary: buffer.isBinary)
    }
}
