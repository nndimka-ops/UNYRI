import AVFoundation
import WebRTC

/// CallKit owns activation; this configures routing before its Start/Answer
/// action is fulfilled. Never let a previous media player's playback category
/// decide where the outgoing ringback goes. WebRTC receives the same category
/// and mode so a later media-engine initialization does not replace the policy.
enum UnyriCallAudioSession {
    static func configure(outputOverride: AVAudioSession.PortOverride) throws {
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
        try session.overrideOutputAudioPort(outputOverride)
        RTCAudioSessionConfiguration.setWebRTC(config)
    }
}
