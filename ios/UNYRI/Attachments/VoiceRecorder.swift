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
                recorder?.pause()
                ownsRecordingAudioSession = false
            }
        }
    }
    var recordedSeconds: TimeInterval { recorder?.currentTime ?? 0 }

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
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [])
            try session.setActive(true)
            ownsRecordingAudioSession = true
        } catch { releaseCaptureClaim(); throw error }
        #endif

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        do {
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            guard recorder.record() else { throw VoiceRecordError.couldNotStart }
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
        let elapsed = Int64(recorder.currentTime * 1000)
        recorder.stop()
        self.recorder = nil
        self.outputURL = nil
        hasRecordingSession = false
        releaseRecordingAudioSession()
        releaseCaptureClaim()
        guard elapsed > 0, FileManager.default.fileExists(atPath: url.path) else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (url, elapsed)
    }

    func interrupt() {
        let handler = onInterruptedDraft
        if let result = stop() { handler?(result.fileURL, result.durationMs) }
    }

    func cancel() {
        onInterruptedDraft = nil
        stopAndCleanup()
    }

    func togglePause() {
        guard !callInProgress else { return }
        objectWillChange.send()
        if recorder?.isRecording == true { recorder?.pause() } else { recorder?.record() }
    }
    func level() -> Double {
        guard let recorder, recorder.isRecording else { return 0 }
        recorder.updateMeters()
        return pow(10, Double(recorder.averagePower(forChannel: 0)) / 20)
    }
    var isRecording: Bool { recorder?.isRecording == true }

    private func releaseRecordingAudioSession() {
        guard ownsRecordingAudioSession else { return }
        ownsRecordingAudioSession = false
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        // A call or another media owner may have replaced the recorder's route.
        guard !callInProgress, session.category == .playAndRecord, session.mode == .default else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
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
        if let outputURL {
            try? FileManager.default.removeItem(at: outputURL)
        }
        outputURL = nil
        hasRecordingSession = false
        releaseRecordingAudioSession()
        releaseCaptureClaim()
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
        case .couldNotStart: return "Could not start recording."
        case .permissionDenied: return "Microphone access is required to record a voice message."
        case .callInProgress: return "Finish the call before recording a voice message."
        }
    }
}
