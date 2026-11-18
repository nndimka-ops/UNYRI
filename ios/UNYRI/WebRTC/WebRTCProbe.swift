import WebRTC

/// Compile/link probe for the WebRTC iOS dependency (checkpoint A). Proves the
/// SPM package resolves, `import WebRTC` compiles, and the native framework
/// links in the CI xcodebuild — without constructing real media or a live call.
///
/// Never invoked at runtime; the mere presence of these references exercises
/// the WebRTC symbol surface so a missing/failed link fails the build.
enum WebRTCProbe {
    static func makePeerConnectionFactory() -> RTCPeerConnectionFactory {
        let decoder = RTCDefaultVideoDecoderFactory()
        let encoder = RTCDefaultVideoEncoderFactory()
        return RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
    }

    static func makeConfiguration(iceServers: [RTCIceServer]) -> RTCConfiguration {
        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.iceTransportPolicy = .all
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        return config
    }

    static func makeIceServer(urls: [String]) -> RTCIceServer {
        RTCIceServer(urlStrings: urls)
    }
}
