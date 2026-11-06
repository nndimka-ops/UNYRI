import AVFoundation
import Combine
import Foundation

struct VoicePlaybackState: Equatable {
    var playingAttachmentId: String?
    var pausedAttachmentId: String?
    var durationMs: Int = 0
    var positionMs: Int = 0
    var speed: Float = 1
}

/// High-frequency position changes are observed only by the waveform and slider.
@MainActor
final class VoicePlaybackProgress: ObservableObject {
    @Published var positionMs: Int = 0
}

/// Exactly one voice message plays at a time app-wide via a single shared
/// `AVAudioPlayer` — never one per bubble (Android's `VoicePlayerController`,
/// Этап H п.30). Scrolling the chat list never disturbs in-progress playback.
///
/// Account-scoped: created per `AccountServices`, stopped on logout so stale
/// audio can never leak into the next account.
@MainActor
final class VoicePlayerController: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    let progress = VoicePlaybackProgress()
    private(set) var state = VoicePlaybackState() {
        willSet {
            var previous = state
            var next = newValue
            previous.positionMs = 0
            next.positionMs = 0
            if previous != next { objectWillChange.send() }
        }
        didSet {
            if progress.positionMs != state.positionMs { progress.positionMs = state.positionMs }
        }
    }

    private var cacheLease: MediaCacheLease?
    private var player: AVAudioPlayer?
    private var delegate: PlayerDelegate?
    private var progressTimer: Timer?
    var playbackAllowed: () -> Bool = { true }
    private var audioObservers = Set<AnyCancellable>()
    private var ownsPlaybackSession = false

    init() {
        #if os(iOS)
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .receive(on: DispatchQueue.main).sink { [weak self] notification in
                if let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                   AVAudioSession.InterruptionType(rawValue: raw) == .began { self?.stop() }
            }.store(in: &audioObservers)
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: DispatchQueue.main).sink { [weak self] notification in
                if let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable { self?.stop() }
            }.store(in: &audioObservers)
        #endif
    }

    private func preparePlayback() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default)
        try session.setActive(true)
        ownsPlaybackSession = true
        #endif
    }

    private func releasePlaybackSession() {
        guard ownsPlaybackSession else { return }
        ownsPlaybackSession = false
        #if os(iOS)
        // CallKit/recording may already have taken ownership. Never deactivate their session.
        guard playbackAllowed() else { return }
        let session = AVAudioSession.sharedInstance()
        guard session.category == .playback else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    /// Toggles play for `attachmentId` at `fileURL`: playing the SAME message
    /// stops it; playing a different one stops the current and starts the new
    /// one. Mirrors Android's `togglePlay`.
    func togglePlay(attachmentId: String, fileURL: URL) {
        guard playbackAllowed() else { stop(); return }
        if state.playingAttachmentId == attachmentId {
            player?.pause()
            progressTimer?.invalidate()
            progressTimer = nil
            state.playingAttachmentId = nil
            state.pausedAttachmentId = attachmentId
            releasePlaybackSession()
            return
        }
        if state.pausedAttachmentId == attachmentId, let player {
            do { try preparePlayback() } catch { stop(); return }
            guard player.play() else { stop(); return }
            state.playingAttachmentId = attachmentId
            state.pausedAttachmentId = nil
            startProgressTimer()
            return
        }
        stop()
        do {
            try preparePlayback()
            cacheLease = MediaCacheLease(path: fileURL.path)
            let player = try AVAudioPlayer(contentsOf: fileURL)
            let delegate = PlayerDelegate()
            delegate.onFinish = { [weak self, weak player] in
                Task { @MainActor in
                    guard let self, let player, self.player === player else { return }
                    self.stop()
                }
            }
            player.delegate = delegate
            player.enableRate = true
            player.prepareToPlay()
            guard player.play() else {
                stop()
                return
            }
            self.player = player
            self.delegate = delegate
            state = VoicePlaybackState(
                playingAttachmentId: attachmentId,
                durationMs: Int(player.duration * 1000),
                positionMs: 0
            )
            startProgressTimer()
        } catch {
            stop()
        }
    }

    func seek(fraction: Double) {
        guard fraction.isFinite, let player else { return }
        player.currentTime = player.duration * min(1, max(0, fraction))
        state.positionMs = currentPositionMs()
    }
    func cycleSpeed() {
        state.speed = state.speed == 1 ? 1.5 : state.speed == 1.5 ? 2 : 1
        player?.rate = state.speed
    }
    func stop() {
        progressTimer?.invalidate()
        progressTimer = nil
        player?.stop()
        player = nil
        cacheLease = nil
        delegate = nil
        state = VoicePlaybackState()
        releasePlaybackSession()
    }

    func currentPositionMs() -> Int {
        Int((player?.currentTime ?? 0) * 1000)
    }

    private func startProgressTimer() {
        progressTimer?.invalidate()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.state.playingAttachmentId != nil else { return }
                self.state.positionMs = self.currentPositionMs()
            }
        }
    }

    /// AVAudioPlayer holds its delegate weakly, so the controller keeps a
    /// strong reference; the delegate routes natural completion back to stop()
    /// so resources are released (Android's `setOnCompletionListener { stop() }`).
    private final class PlayerDelegate: NSObject, AVAudioPlayerDelegate {
        var onFinish: (() -> Void)?

        func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
            onFinish?()
        }

        func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
            onFinish?()
        }
    }
}
