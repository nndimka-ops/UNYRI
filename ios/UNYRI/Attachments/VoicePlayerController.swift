import AVFoundation
import Combine
import Foundation
#if os(iOS)
import MediaPlayer
import UIKit
#endif

struct VoicePlaybackState: Equatable {
    var playingAttachmentId: String?
    var pausedAttachmentId: String?
    var durationMs: Int = 0
    var positionMs: Int = 0
    var speed: Float = VoicePlaybackState.savedSpeed

    /// The chosen speed survives new messages and relaunches (Telegram-like).
    static var savedSpeed: Float {
        get { let value = UserDefaults.standard.float(forKey: "voice-playback-speed"); return [1, 1.5, 2].contains(value) ? value : 1 }
        set { UserDefaults.standard.set(newValue, forKey: "voice-playback-speed") }
    }
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
///
/// Playback keeps going when the screen locks or the app moves to the
/// background (`.playback` category plus the `audio` background mode), with
/// lock-screen play/pause/scrub. Calls, Siri, alarms and unplugged headphones
/// pause and keep the position; only an explicit `stop()` ends a message.
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
            if playingAttachment.value != state.playingAttachmentId { playingAttachment.send(state.playingAttachmentId) }
        }
    }
    private let playingAttachment = CurrentValueSubject<String?, Never>(nil)
    /// Only the message that is playing, deduplicated: for views that must react to
    /// another sound starting without observing every play/pause/speed change.
    var playingAttachmentPublisher: AnyPublisher<String?, Never> {
        playingAttachment.removeDuplicates().eraseToAnyPublisher()
    }

    private var cacheLease: MediaCacheLease?
    private var player: AVAudioPlayer?
    private var delegate: PlayerDelegate?
    private var progressTimer: Timer?
    var playbackAllowed: () -> Bool = { true }
    private var audioObservers = Set<AnyCancellable>()
    private var ownsPlaybackSession = false
    /// Who started the current message (a chat id), so leaving one chat never
    /// stops audio that another screen started.
    private var playbackOwner: String?
    /// Lock-screen title, for example the sender or chat name.
    private var nowPlayingTitle: String?
    #if os(iOS)
    private var remoteCommandTargets: [(command: MPRemoteCommand, target: Any)] = []
    private var publishedNowPlaying = false
    #endif

    init() {
        #if os(iOS)
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .receive(on: DispatchQueue.main).sink { [weak self] notification in
                // A call/Siri/alarm pauses (keeps the position) instead of discarding it.
                if let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                   AVAudioSession.InterruptionType(rawValue: raw) == .began { self?.pause() }
            }.store(in: &audioObservers)
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: DispatchQueue.main).sink { [weak self] notification in
                // Headphones unplugged: pause like every audio app, keep the position.
                if let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable { self?.pause() }
            }.store(in: &audioObservers)
        NotificationCenter.default.publisher(for: AVAudioSession.mediaServicesWereResetNotification)
            .receive(on: DispatchQueue.main).sink { [weak self] _ in
                // The audio daemon restarted: the player and the session are no longer valid.
                self?.stop()
            }.store(in: &audioObservers)
        #endif
    }

    private func preparePlayback() throws {
        #if os(iOS)
        // On the shared audio queue, after any deactivation still queued by a player or
        // recorder that just finished, so that deactivation can never silence this message.
        try UnyriPlaybackAudioSession.synchronousClaim {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        }
        ownsPlaybackSession = true
        #endif
    }

    private func releasePlaybackSession() {
        guard ownsPlaybackSession else { return }
        ownsPlaybackSession = false
        #if os(iOS)
        // CallKit/recording may already have taken ownership. Never deactivate their session;
        // the category and mode are re-checked on the audio queue. Off the main thread:
        // deactivating can take hundreds of milliseconds.
        guard playbackAllowed() else { return }
        UnyriPlaybackAudioSession.deactivate(ifStill: .playback, mode: .default, label: "voicePlayer", reason: 3)
        #endif
    }

    /// Toggles play for `attachmentId` at `fileURL`: playing the SAME message
    /// pauses it; playing a different one stops the current and starts the new
    /// one. Mirrors Android's `togglePlay`.
    ///
    /// - Parameters:
    ///   - title: shown on the lock screen (sender or chat name); defaults to "Voice message".
    ///   - owner: the chat that started playback, for `stop(owner:)` when that chat closes.
    func togglePlay(attachmentId: String, fileURL: URL, title: String? = nil, owner: String? = nil) {
        guard playbackAllowed() else { stop(); return }
        if state.playingAttachmentId == attachmentId { pause(); return }
        if state.pausedAttachmentId == attachmentId, player != nil {
            if let title { nowPlayingTitle = title }
            if let owner { playbackOwner = owner }
            resume()
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
            player.rate = VoicePlaybackState.savedSpeed
            player.prepareToPlay()
            guard player.play() else {
                stop()
                return
            }
            self.player = player
            self.delegate = delegate
            playbackOwner = owner
            nowPlayingTitle = title
            state = VoicePlaybackState(
                playingAttachmentId: attachmentId,
                durationMs: Int(player.duration * 1000),
                positionMs: 0
            )
            startProgressTimer()
            publishNowPlaying()
        } catch {
            stop()
        }
    }

    /// Continues the paused message from its position (bubble tap or lock screen).
    @discardableResult
    private func resume() -> Bool {
        guard let paused = state.pausedAttachmentId, let player else { return false }
        guard playbackAllowed() else { stop(); return false }
        do { try preparePlayback() } catch { stop(); return false }
        guard player.play() else { stop(); return false }
        state.playingAttachmentId = paused
        state.pausedAttachmentId = nil
        startProgressTimer()
        publishNowPlaying()
        return true
    }

    func seek(fraction: Double) {
        guard fraction.isFinite, let player else { return }
        player.currentTime = player.duration * min(1, max(0, fraction))
        state.positionMs = currentPositionMs()
        publishNowPlaying()
    }
    func cycleSpeed() {
        state.speed = state.speed == 1 ? 1.5 : state.speed == 1.5 ? 2 : 1
        VoicePlaybackState.savedSpeed = state.speed
        player?.rate = state.speed
        publishNowPlaying()
    }
    /// Pauses the playing message and keeps its position for the next tap.
    func pause() {
        guard let playing = state.playingAttachmentId else { return }
        player?.pause()
        progressTimer?.invalidate()
        progressTimer = nil
        state.positionMs = currentPositionMs()
        state.playingAttachmentId = nil
        state.pausedAttachmentId = playing
        publishNowPlaying()
        releasePlaybackSession()
    }
    /// Ends playback completely: releases the file and the audio session and
    /// removes the lock-screen controls. The next tap starts from the beginning.
    func stop() {
        progressTimer?.invalidate()
        progressTimer = nil
        player?.stop()
        player = nil
        cacheLease = nil
        delegate = nil
        playbackOwner = nil
        nowPlayingTitle = nil
        state = VoicePlaybackState()
        clearNowPlaying()
        releasePlaybackSession()
    }
    /// Stops playing or paused audio that `owner` started (the chat id passed to
    /// `togglePlay`), for example when that chat is closed. Audio started by
    /// another chat keeps playing; audio started without an owner is stopped.
    func stop(owner: String) {
        guard state.playingAttachmentId != nil || state.pausedAttachmentId != nil else { return }
        guard playbackOwner == nil || playbackOwner == owner else { return }
        stop()
    }

    func currentPositionMs() -> Int {
        Int((player?.currentTime ?? 0) * 1000)
    }

    private func startProgressTimer() {
        progressTimer?.invalidate()
        // `.common` keeps the waveform/slider moving while the chat list is being
        // scrolled; a default-mode timer is paused during UIScrollView tracking.
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.state.playingAttachmentId != nil else { return }
                #if os(iOS)
                // Locked screen or background: nothing on screen to move. The lock
                // screen extrapolates from the published elapsed time and rate.
                guard UIApplication.shared.applicationState != .background else { return }
                #endif
                self.state.positionMs = self.currentPositionMs()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        progressTimer = timer
    }

    // MARK: Lock screen (Now Playing + remote commands)

    private func publishNowPlaying() {
        #if os(iOS)
        guard let player, state.playingAttachmentId != nil || state.pausedAttachmentId != nil else { return }
        let playing = state.playingAttachmentId != nil
        let kind = UnyriL10n.string("Voice message")
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: nowPlayingTitle ?? kind,
            MPMediaItemPropertyPlaybackDuration: player.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? Double(player.rate) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if nowPlayingTitle != nil { info[MPMediaItemPropertyArtist] = kind }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        publishedNowPlaying = true
        enableRemoteCommands()
        #endif
    }

    private func clearNowPlaying() {
        #if os(iOS)
        for registration in remoteCommandTargets { registration.command.removeTarget(registration.target) }
        remoteCommandTargets.removeAll()
        // Never clear Now Playing that another player (the video viewer) published.
        if publishedNowPlaying {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            publishedNowPlaying = false
        }
        #endif
    }

    #if os(iOS)
    private func enableRemoteCommands() {
        guard remoteCommandTargets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        registerRemote(center.playCommand) { $0.remotePlay() }
        registerRemote(center.pauseCommand) { $0.remotePause() }
        registerRemote(center.togglePlayPauseCommand) { $0.state.playingAttachmentId != nil ? $0.remotePause() : $0.remotePlay() }
        let scrub = center.changePlaybackPositionCommand
        scrub.isEnabled = true
        let target = scrub.addTarget { [weak self] event in
            guard let position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime else { return .commandFailed }
            return MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
                self?.remoteSeek(to: position) ?? .noActionableNowPlayingItem
            }
        }
        remoteCommandTargets.append((command: scrub, target: target))
    }

    private func registerRemote(_ command: MPRemoteCommand,
                                _ action: @escaping @MainActor (VoicePlayerController) -> MPRemoteCommandHandlerStatus) {
        command.isEnabled = true
        // Remote commands are delivered on the main thread.
        let target = command.addTarget { [weak self] _ in
            MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
                guard let controller = self else { return .noActionableNowPlayingItem }
                return action(controller)
            }
        }
        remoteCommandTargets.append((command: command, target: target))
    }

    private func remotePlay() -> MPRemoteCommandHandlerStatus {
        if state.playingAttachmentId != nil { return .success }
        guard state.pausedAttachmentId != nil else { return .noActionableNowPlayingItem }
        return resume() ? .success : .commandFailed
    }

    private func remotePause() -> MPRemoteCommandHandlerStatus {
        guard state.playingAttachmentId != nil else {
            return state.pausedAttachmentId != nil ? .success : .noActionableNowPlayingItem
        }
        pause()
        return .success
    }

    private func remoteSeek(to seconds: TimeInterval) -> MPRemoteCommandHandlerStatus {
        guard let player, player.duration > 0, seconds.isFinite else { return .commandFailed }
        seek(fraction: seconds / player.duration)
        return .success
    }
    #endif

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
