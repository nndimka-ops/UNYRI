import AVFoundation
import WebRTC

/// CallKit owns activation; this configures routing before its Start/Answer
/// action is fulfilled. Never let a previous media player's playback category
/// decide where the outgoing ringback goes. WebRTC receives the same category
/// and mode so a later media-engine initialization does not replace the policy.
enum UnyriCallAudioSession {
    /// `outputOverride == nil` keeps the current output (answering a video
    /// call must not pull a speaker route back to the receiver), except that a
    /// connected headset always wins: a speaker override left over from an
    /// earlier call or a room is released, so iOS routes to the headset.
    static func configure(outputOverride: AVAudioSession.PortOverride?) throws {
        // On the shared audio queue: every note, video or voice mutation queued before the
        // call is invalidated and none can interleave with this configuration.
        try UnyriPlaybackAudioSession.callConfiguration { try configureOnAudioQueue(outputOverride: outputOverride) }
    }

    private static func configureOnAudioQueue(outputOverride: AVAudioSession.PortOverride?) throws {
        let rtc = RTCAudioSession.sharedInstance()
        rtc.lockForConfiguration()
        defer { rtc.unlockForConfiguration() }
        let config = RTCAudioSessionConfiguration()
        config.category = AVAudioSession.Category.playAndRecord.rawValue
        config.mode = AVAudioSession.Mode.voiceChat.rawValue
        config.categoryOptions = [.allowBluetoothHFP, .allowBluetoothA2DP]
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord || session.mode != .voiceChat || session.categoryOptions != config.categoryOptions {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: config.categoryOptions)
        }
        // .none releases an old app speaker override and lets iOS honour a
        // connected headset/Bluetooth route; it does not force the receiver.
        if let override = outputOverride ?? (headsetConnected(session) ? AVAudioSession.PortOverride.none : nil) {
            try session.overrideOutputAudioPort(override)
        }
        RTCAudioSessionConfiguration.setWebRTC(config)
    }

    /// Read after the call category is set (the HFP inputs are listed only
    /// then). A speaker override hides the headset from the current outputs,
    /// so its microphone input is what shows it is still there.
    private static func headsetConnected(_ session: AVAudioSession) -> Bool {
        let inputs = session.availableInputs ?? []
        if inputs.contains(where: { $0.portType == .bluetoothHFP || $0.portType == .headsetMic }) { return true }
        return session.currentRoute.outputs.contains {
            [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE, .headphones].contains($0.portType)
        }
    }
}
