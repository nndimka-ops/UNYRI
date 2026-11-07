import Foundation
import LiveKit

/// Deliberately excludes addresses, candidate/transport IDs, ICE URLs,
/// credentials, certificate material and username fragments from test output.
struct SafeICEPath: Encodable {
    let phase: String
    let selectedPairResolved: Bool
    let selectedLocalCandidateResolved: Bool
    let selectedRemoteCandidateResolved: Bool
    let localType: String?
    let remoteType: String?
    let localProtocol: String?
    let relayProtocol: String?
    let pairState: String?
    let nominated: Bool?
    let bytesSent: UInt64?
    let bytesReceived: UInt64?
    let roundTripSeconds: Double?
    var provesRelay: Bool {
        selectedPairResolved && selectedLocalCandidateResolved && localType == "relay" && pairState == "succeeded"
    }
    static func capture(_ stats: TrackStatistics?, phase: String) -> SafeICEPath? {
        guard let stats else { return nil }
        let selected = stats.transportStats?.selectedCandidatePairId
        let pair = stats.iceCandidatePair.first { $0.id == selected }
        let local = stats.localIceCandidate
        let remote = stats.remoteIceCandidate
        let localMatches = local != nil && local?.id == pair?.localCandidateId
        let remoteMatches = remote != nil && remote?.id == pair?.remoteCandidateId
        return SafeICEPath(phase: phase, selectedPairResolved: pair != nil,
            selectedLocalCandidateResolved: localMatches, selectedRemoteCandidateResolved: remoteMatches,
            localType: localMatches ? local?.candidateType?.rawValue : nil,
            remoteType: remoteMatches ? remote?.candidateType?.rawValue : nil,
            localProtocol: localMatches ? local?.protocol : nil,
            relayProtocol: localMatches ? local?.relayProtocol?.rawValue : nil,
            pairState: pair?.state?.rawValue, nominated: pair?.nominated,
            bytesSent: pair?.bytesSent, bytesReceived: pair?.bytesReceived,
            roundTripSeconds: pair?.currentRoundTripTime)
    }
}
enum RelayProbeOptions {
    enum Failure: Error { case selectedRelayNotObserved(SafeICEPath?) }
    static func make(autoSubscribe: Bool, forceRelay: Bool) -> ConnectOptions {
        // An empty override preserves the TURN URLs and temporary credentials
        // returned by the SFU. The SDK installs this explicit policy before
        // initial negotiation and again on full reconnect.
        ConnectOptions(autoSubscribe: autoSubscribe, iceTransportPolicy: forceRelay ? .relay : .all)
    }
    @MainActor static func requireSelectedRelay(track: Track, phase: String) async throws -> SafeICEPath {
        let deadline = Date().addingTimeInterval(4)
        while Date() < deadline {
            if let snapshot = SafeICEPath.capture(track.statistics, phase: phase), snapshot.provesRelay { return snapshot }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        throw Failure.selectedRelayNotObserved(SafeICEPath.capture(track.statistics, phase: phase))
    }
}
