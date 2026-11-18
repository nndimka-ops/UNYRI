import AVFoundation
import Combine
import Foundation

/// AAC-LC in an MP4/M4A container via `AVAudioRecorder` — the exact codec and
/// container Android's `VoiceRecorder` produces with `MediaRecorder`
/// (AAC-LC, mono, 16 kHz, 32 kbps, `.m4a`). No extra native codec dependency;
/// the output is directly decodable by both platforms.
///
/// Account-scoped: one instance per `AccountServices`; recording state is
/// discarded on logout. Microphone permission is requested only when the user
/// taps record (never on app launch) — iOS prompts on the first record
/// attempt, and the UI requests it explicitly before calling `start()`.
@MainActor
final class VoiceRecorder: ObservableObject {
    var onInterruptedDraft: ((URL, Int64) -> Void)?
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?
    private var ownsRecordingAudioSession = false
    private var captureClaim: NonCallCaptureOwnership.Claim?
    @Published private(set) var hasRecordingSession = false
    @Published var callInProgress = false {
        didSet {
            // Preserve a voice draft, but never let it record the call or
            // compete with CallKit's microphone/audio-session ownership.
            if callInProgress {
                rememberPausedLength()
                recorder?.pause()
                ownsRecordingAudioSession = false
            }
        }
    }
    /// AVAudioRecorder documents `currentTime` as valid only while recording.
    /// The length at the last pause keeps the clock and a paused draft from
    /// falling back to 0:00 (a 0 length used to delete the draft on Stop/Send).
    private var pausedSeconds: TimeInterval = 0
    var recordedSeconds: TimeInterval {
        guard let recorder else { return 0 }
        return recorder.isRecording ? recorder.currentTime : max(recorder.currentTime, pausedSeconds)
    }
    private func rememberPausedLength() {
        if let recorder, recorder.isRecording { pausedSeconds = max(pausedSeconds, recorder.currentTime) }
    }

    /// Maximum recording length before auto-stop (Android has no explicit cap,
    /// but iOS records to an unbounded file; 30 min is a sane ceiling).
    static let maxRecordingSeconds: TimeInterval = 30 * 60

    func start(captureClaim suppliedClaim: NonCallCaptureOwnership.Claim? = nil) throws -> URL {
        guard !callInProgress else { throw VoiceRecordError.callInProgress }
        stopAndCleanup()
        guard let generation = NonCallCaptureOwnership.currentGeneration() else { throw VoiceRecordError.callInProgress }
        let candidate = suppliedClaim ?? NonCallCaptureOwnership.acquire(.voice, generation: generation, stop: { [weak self] claim in
            self?.interrupt(); NonCallCaptureOwnership.release(claim)
        })
        guard let claim = candidate, NonCallCaptureOwnership.isCurrent(claim),
              NonCallCaptureOwnership.replaceStop(claim, stop: { [weak self] claim in
                  self?.interrupt(); NonCallCaptureOwnership.release(claim)
              }) else { throw VoiceRecordError.callInProgress }
        captureClaim = claim

        #if os(iOS)
        do {
            // On the shared audio queue, after any deactivation still queued by a player or
            // recorder that just finished, so that deactivation can never land on this take.
            try UnyriPlaybackAudioSession.synchronousClaim {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .default, options: [])
                // iOS mutes haptics while recording unless the app opts in; every
                // start/lock/cancel/finish tap was silent.
                try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
                try session.setActive(true)
            }
            ownsRecordingAudioSession = true
        } catch { releaseCaptureClaim(); throw error }
        #endif

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            // Still AAC-LC in .m4a (Android decodes it natively); 24 kHz / 48 kbps
            // instead of 16 kHz / 32 kbps, which sounded muffled.
            AVSampleRateKey: 24_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 48_000,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        do {
            let recorder = try Self.makeRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.prepareToRecord()            // avoids clipping the first syllable
            guard recorder.record() else { throw VoiceRecordError.couldNotStart }
            observeInterruptions()
            self.recorder = recorder
            self.outputURL = url
            hasRecordingSession = true
            return url
        } catch {
            try? FileManager.default.removeItem(at: url)
            releaseRecordingAudioSession()
            releaseCaptureClaim()
            throw error
        }
    }

    /// Stops recording and returns the file URL + recorded duration, or nil
    /// if recording never produced a usable result (too short / interrupted) —
    /// a corrupt file is never auto-sent (Android's `VoiceRecorder.stop()`).
    func stop() -> (fileURL: URL, durationMs: Int64)? {
        defer { onInterruptedDraft = nil }
        guard let recorder, let url = outputURL else { return nil }
        let wasRecording = recorder.isRecording
        var elapsed = Int64(max(recorder.currentTime, pausedSeconds) * 1000)
        recorder.stop()
        self.recorder = nil
        self.outputURL = nil
        pausedSeconds = 0
        hasRecordingSession = false
        stopObservingInterruptions()
        releaseRecordingAudioSession()
        releaseCaptureClaim()
        // A call or Siri can pause the recorder before we hear about it; then the
        // finished file itself says whether anything was recorded. A recorder
        // that was still running and reports 0 really recorded nothing.
        if elapsed <= 0, !wasRecording, FileManager.default.fileExists(atPath: url.path) { elapsed = Self.recordedLengthMs(url) }
        guard elapsed > 0, FileManager.default.fileExists(atPath: url.path) else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (url, elapsed)
    }

    /// Length of a finished recording read from the file (0 if it has no audio).
    private static func recordedLengthMs(_ url: URL) -> Int64 {
        #if os(iOS)
        guard let file = try? AVAudioFile(forReading: url), file.fileFormat.sampleRate > 0, file.length > 0 else { return 0 }
        return Int64(Double(file.length) / file.fileFormat.sampleRate * 1000)
        #else
        return 0
        #endif
    }

    func interrupt() {
        let handler = onInterruptedDraft
        if let result = stop() { handler?(result.fileURL, result.durationMs) }
    }

    /// A phone/FaceTime call, Siri or an alarm silently paused AVAudioRecorder while
    /// the UI kept "recording" with a frozen timer; keep what was recorded instead.
    private var interruptionObserver: NSObjectProtocol?
    private func observeInterruptions() {
        #if os(iOS)
        guard interruptionObserver == nil else { return }
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
                                                                      object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            MainActor.assumeIsolated { if self?.hasRecordingSession == true { self?.interrupt() } }
        }
        #endif
    }
    private func stopObservingInterruptions() {
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
    }

    func cancel() {
        onInterruptedDraft = nil
        stopAndCleanup()
    }

    func togglePause() {
        guard !callInProgress else { return }
        objectWillChange.send()
        if recorder?.isRecording == true { rememberPausedLength(); recorder?.pause() } else { recorder?.record() }
    }
    func level() -> Double {
        guard let recorder, recorder.isRecording else { return 0 }
        recorder.updateMeters()
        // Speech sits around -30…-10 dB; a linear amplitude drew it as a flat line.
        let db = Double(recorder.averagePower(forChannel: 0))
        return min(1, max(0, (db + 50) / 50))
    }
    var isRecording: Bool { recorder?.isRecording == true }

    private func releaseRecordingAudioSession() {
        guard ownsRecordingAudioSession else { return }
        ownsRecordingAudioSession = false
        #if os(iOS)
        // A call may have replaced the recorder's route; another media owner is checked on
        // the audio queue. Off the main thread: deactivating can take hundreds of ms.
        guard !callInProgress else { return }
        UnyriPlaybackAudioSession.deactivate(ifStill: .playAndRecord, mode: .default, label: "voiceRecorder", reason: 5)
        #endif
    }

    /// Assemble resumed segments without reading whole recordings into memory.
    static func join(_ first: URL, _ second: URL) async throws -> URL {
        let composition = AVMutableComposition()
        guard let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw VoiceRecordError.couldNotStart }
        var cursor = CMTime.zero
        for url in [first, second] {
            let asset = AVURLAsset(url: url)
            guard let audio = try await asset.loadTracks(withMediaType: .audio).first else { throw VoiceRecordError.couldNotStart }
            let duration = try await asset.load(.duration)
            try track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: audio, at: cursor)
            cursor = CMTimeAdd(cursor, duration)
        }
        let result = FileManager.default.temporaryDirectory.appendingPathComponent("voice-join-\(UUID().uuidString).m4a")
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else { throw VoiceRecordError.couldNotStart }
        exporter.outputURL = result
        exporter.outputFileType = .m4a
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                exporter.exportAsynchronously {
                    if exporter.status == .completed { continuation.resume() }
                    else { continuation.resume(throwing: exporter.error ?? VoiceRecordError.couldNotStart) }
                }
            }
            return result
        } catch { try? FileManager.default.removeItem(at: result); throw error }
    }

    private func stopAndCleanup() {
        recorder?.stop()
        recorder = nil
        pausedSeconds = 0
        if let outputURL {
            try? FileManager.default.removeItem(at: outputURL)
        }
        outputURL = nil
        hasRecordingSession = false
        stopObservingInterruptions()
        releaseRecordingAudioSession()
        releaseCaptureClaim()
    }
    private static func makeRecorder(url: URL, settings: [String: Any]) throws -> AVAudioRecorder {
        #if targetEnvironment(simulator)
        // UI walkthroughs on a Mac without a microphone: a tone-writing stand-in.
        if ProcessInfo.processInfo.environment["UNYRI_FAKE_MIC"] == "1" {
            return try SimulatorToneRecorder(url: url, settings: settings)
        }
        #endif
        return try AVAudioRecorder(url: url, settings: settings)
    }
    private func releaseCaptureClaim() {
        if let captureClaim { NonCallCaptureOwnership.release(captureClaim) }
        captureClaim = nil
    }
}

enum VoiceRecordError: Error, LocalizedError {
    case couldNotStart
    case permissionDenied
    case callInProgress

    var errorDescription: String? {
        switch self {
        case .couldNotStart: return UnyriL10n.string("Could not start recording.")
        case .permissionDenied: return UnyriL10n.string("Microphone access is required to record a voice message.")
        // Same wording (and catalog entry) as the composer's hold hint.
        case .callInProgress: return UnyriL10n.string("Finish the call before recording.")
        }
    }
}

#if targetEnvironment(simulator)
/// Simulator-only recorder for UI tests (UNYRI_FAKE_MIC=1): pretends to record and
/// writes a soft 440 Hz tone of the recorded length on stop. Never compiled for devices.
private final class SimulatorToneRecorder: AVAudioRecorder {
    private let target: URL
    private var startedAt: Date?
    private var accumulated: TimeInterval = 0
    override init(url: URL, settings: [String: Any]) throws {
        target = url
        try super.init(url: url.deletingPathExtension().appendingPathExtension("unused.caf"),
                       settings: [AVFormatIDKey: Int(kAudioFormatLinearPCM), AVSampleRateKey: 24_000, AVNumberOfChannelsKey: 1])
    }
    override func prepareToRecord() -> Bool { true }
    override func record() -> Bool { if startedAt == nil { startedAt = Date() }; return true }
    override func pause() { if let startedAt { accumulated += Date().timeIntervalSince(startedAt) }; startedAt = nil }
    override var isRecording: Bool { startedAt != nil }
    override var currentTime: TimeInterval { accumulated + (startedAt.map { Date().timeIntervalSince($0) } ?? 0) }
    override func updateMeters() {}
    override func averagePower(forChannel channelNumber: Int) -> Float { isRecording ? Float(-28 + 14 * sin(Date().timeIntervalSince1970 * 7)) : -160 }
    override func stop() {
        let seconds = currentTime
        pause()
        guard seconds > 0, let format = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1) else { return }
        let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 24_000,
                                       AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 48_000]
        guard let file = try? AVAudioFile(forWriting: target, settings: settings),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(seconds * 24_000)) else { return }
        buffer.frameLength = buffer.frameCapacity
        if let samples = buffer.floatChannelData?[0] {
            for index in 0..<Int(buffer.frameLength) { samples[index] = 0.08 * sin(2 * .pi * 440 * Float(index) / 24_000) }
        }
        try? file.write(from: buffer)
    }
}
#endif
