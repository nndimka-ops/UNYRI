import AVFoundation
import Combine
import QuartzCore
import SwiftUI
import UIKit

/// Round video messages in the chat: the composer mode, the recording stage over the
/// timeline, and the round bubble that plays in place.
///
/// Everything that moves is a transform, an opacity change or a Core Animation layer
/// property. Recording and playback state live in small observable objects that only
/// these views observe, so neither re-renders the chat timeline.

// MARK: - Composer mode

/// What a hold on the composer's record button records. A tap on the button switches it.
enum ChatVideoNoteMode {
    static let key = "chatComposerVideoNoteMode"
    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
    static func hint(video: Bool) -> String {
        UnyriL10n.string(video ? "Hold to record a video message" : "Hold to record, release to send")
    }
}

/// The record button's glyph: a microphone that flips into a round camera and back.
struct ChatRecordModeGlyph: View {
    let video: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            Image(systemName: "mic").font(.system(size: 20, weight: .regular))
                .opacity(video ? 0 : 1)
                .scaleEffect(video ? 0.4 : 1)
                .rotationEffect(.degrees(video ? -90 : 0))
            Image(systemName: "video.circle").font(.system(size: 24, weight: .regular))
                .opacity(video ? 1 : 0)
                .scaleEffect(video ? 1 : 0.4)
                .rotationEffect(.degrees(video ? 0 : 90))
        }
        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.62), value: video)
    }
}

// MARK: - Recording stage

/// The stage for a video note: a dim scrim over the timeline and the round live preview
/// centred above the composer. The composer itself stays on top with the recording bar.
/// Observes only the recorder, so nothing here re-renders the chat.
struct ChatVideoNoteStage: ViewModifier {
    @ObservedObject var recorder: VideoNoteRecorder
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var layoutDirection
    /// Follows `recorder.isActive` one update late: when a take ends the circle is still
    /// mounted, so the preview starts its layer exit and the transition already knows how
    /// the take ended (a flight to the timeline on send, back into the button on discard).
    @State private var presented = false

    func body(content: Content) -> some View {
        content
            .overlay {
                ZStack {
                    if presented {
                        // Plain black with opacity: no blur to keep updating under the camera.
                        Color.black.opacity(0.64).ignoresSafeArea()
                            .contentShape(Rectangle())
                            .accessibilityHidden(true)
                            .transition(.asymmetric(insertion: .opacity,
                                                    removal: .opacity.animation(.easeOut(duration: reduceMotion ? 0.2 : 0.3))))
                    }
                    GeometryReader { geometry in
                        if presented {
                            // About 72% of the width on a phone; bounded on an iPad and
                            // above an open keyboard.
                            let side = max(140, min(420, geometry.size.width * 0.72 + 2 * VideoNotePreviewUIView.ringInset,
                                                    geometry.size.height - 24))
                            VideoNotePreview(recorder: recorder, reduceMotion: reduceMotion)
                                .frame(width: side, height: side)
                                .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel(Text(UnyriL10n.string(recorder.isRecording ? "Recording video message" : "Video message")))
                                .accessibilityIdentifier("video-note-recording-circle")
                                // After .position: the transition's frame is the whole stage, so
                                // the emergence anchor still points at the record button.
                                .transition(circleTransition(VideoNoteFlight(size: geometry.size, side: side,
                                                                             rtl: layoutDirection == .rightToLeft)))
                        }
                    }
                    .allowsHitTesting(false)
                    .zIndex(1)
                    // A sibling of the non-interactive preview: its accessibility
                    // element and hit target must stay independent of the circle.
                    GeometryReader { geometry in
                        if presented, recorder.torchAvailable {
                            let side = max(140, min(420, geometry.size.width * 0.72 + 2 * VideoNotePreviewUIView.ringInset,
                                                    geometry.size.height - 24))
                            let point = torchPosition(size: geometry.size, side: side)
                            CallTorchButton(isOn: recorder.torchOn, action: recorder.toggleTorch,
                                            accessibilityID: "video-note-torch")
                                .position(point)
                        }
                    }
                    // The call's accessory also changes availability immediately;
                    // its 0.18 s fade is only for hiding the call chrome.
                    .transaction { $0.animation = nil }
                    .zIndex(2)
                }
            }
            .onAppear { presented = recorder.isActive }
            .onChange(of: recorder.isActive) { active in
                withAnimation(curve(active)) { presented = active }
            }
            .alert(Text(UnyriL10n.string(recorder.accessProblem == .microphoneDenied ? "No microphone access" : "No camera access")),
                   isPresented: Binding(get: { recorder.accessProblem != nil }, set: { if !$0 { recorder.accessProblem = nil } })) {
                Button(UnyriL10n.string("Open app settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                Button(UnyriL10n.string("Cancel"), role: .cancel) {}
            } message: {
                Text(UnyriL10n.string(recorder.accessProblem == .microphoneDenied
                    ? "Allow microphone access in Settings to record video messages."
                    : "Allow camera access in Settings to record video messages."))
            }
            // Leaving the chat ends a note that is playing in it.
            .onDisappear { UnyriVideoNotePlayer.shared.stop() }
    }

    private func torchPosition(size: CGSize, side: CGFloat) -> CGPoint {
        // The 30 pt glass circle touches the outside of the recording rim
        // (radius side/2 - 2), leaving the camera picture entirely uncovered.
        let radius = side / 2 - 2 + 15
        let availableX = max(0, size.width / 2 - 22)
        let availableY = max(0, size.height / 2 - 22)
        var x = min(radius / CGFloat(2).squareRoot(), availableX)
        let y = min((radius * radius - x * x).squareRoot(), availableY)
        // A short stage moves the accessory along the rim before constraining
        // the complete 44 pt target to the viewport.
        x = min((radius * radius - y * y).squareRoot(), availableX)
        return CGPoint(x: size.width / 2 + (layoutDirection == .rightToLeft ? -x : x),
                       y: size.height / 2 + y)
    }

    private func circleTransition(_ flight: VideoNoteFlight) -> AnyTransition {
        if reduceMotion { return .opacity }
        let emerge = AnyTransition.modifier(active: VideoNoteEmergence(shown: false), identity: VideoNoteEmergence(shown: true))
        // Read in the update where the take ended, while the circle is still mounted.
        guard !recorder.isActive, recorder.ending == .sent else { return emerge }
        return .asymmetric(insertion: emerge,
                           removal: .modifier(active: VideoNoteSendFlight(progress: 1, flight: flight),
                                              identity: VideoNoteSendFlight(progress: 0, flight: flight)))
    }

    private func curve(_ active: Bool) -> Animation {
        if reduceMotion { return .easeOut(duration: 0.2) }
        if active { return .spring(response: 0.38, dampingFraction: 0.82) }
        // Linear: the flight modifier shapes its own progress.
        return recorder.ending == .sent ? .linear(duration: 0.52) : .easeIn(duration: 0.26)
    }
}

/// The circle grows out of the record button (bottom trailing corner, just below the
/// timeline) and shrinks back into it.
private struct VideoNoteEmergence: ViewModifier {
    let shown: Bool
    func body(content: Content) -> some View {
        content
            .scaleEffect(shown ? 1 : 0.08, anchor: UnitPoint(x: 0.93, y: 1.04))
            .opacity(shown ? 1 : 0)
    }
}

/// Where a sent note appears: trailing, just above the composer, at the bubble's size.
/// A prediction: if the reader scrolled up it lands off the end, and the fade hides that.
private struct VideoNoteFlight {
    let travel: CGSize
    let endScale: CGFloat

    init(size: CGSize, side: CGFloat, rtl: Bool) {
        let picture = max(1, side - 2 * VideoNotePreviewUIView.ringInset)
        let bubble = min(UnyriSavedChatStyle.mediaWidth, picture)
        let x = rtl ? UnyriSavedChatStyle.side + bubble / 2 : size.width - UnyriSavedChatStyle.side - bubble / 2
        let y = size.height - 8 - bubble / 2
        travel = CGSize(width: x - size.width / 2, height: y - size.height / 2)
        endScale = bubble / picture
    }
}

/// Send: a short settle, then a swing to the note's spot, shrinking to its size and fading
/// on arrival. One linear progress (0...1), shaped here like ChatLockClick.
private struct VideoNoteSendFlight: ViewModifier, Animatable {
    var progress: CGFloat
    let flight: VideoNoteFlight

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let p = progress
        let settleLinear = min(1, p / 0.22)
        let settle = 1 - (1 - settleLinear) * (1 - settleLinear)
        let f = min(1, max(0, (p - 0.16) / 0.84))
        let e = f * f * (3 - 2 * f)
        let scale = (1 - 0.04 * settle) * (1 - e) + flight.endScale * e
        // Mostly sideways first, then down: a swing rather than a straight line.
        let sideways = 2 * (1 - e) * e * 0.85 + e * e
        let down = 2 * (1 - e) * e * 0.15 + e * e
        return content
            .scaleEffect(scale)
            .offset(x: flight.travel.width * sideways, y: flight.travel.height * down)
            .opacity(Double(p < 0.72 ? 1 : max(0, 1 - (p - 0.72) / 0.28)))
    }
}

// MARK: - Inline playback

/// One video note plays at a time, inside its own bubble. Nothing starts by itself.
@MainActor
final class UnyriVideoNotePlayer: ObservableObject {
    static let shared = UnyriVideoNotePlayer()

    @Published private(set) var activeID: String?
    @Published private(set) var playing = false
    private(set) var player: AVPlayer?

    private var lease: MediaCacheLease?
    private var observers: [NSObjectProtocol] = []

    private init() {}

    /// Tap on a note: play it, pause it, or switch from another note to this one.
    func toggle(id: String, url: URL) {
        if activeID == id, let player {
            if playing {
                pause()
            } else {
                // The picture resumes at once; the sound joins when the session is ours.
                player.isMuted = true
                player.play()
                playing = true
                claimAudio(for: player)
            }
            return
        }
        // Switching notes keeps the audio session: no deactivate/activate round trip.
        teardown(releasingSession: false)
        lease = MediaCacheLease(path: url.path)
        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        player.actionAtItemEnd = .pause
        // Muted until the playback session is active (claimAudio): configuring it is an
        // audio-server round trip that must not hold the tap or the main thread.
        player.isMuted = true
        let center = NotificationCenter.default
        // Raw names: the Swift spellings of these constants changed between SDKs.
        for name in ["AVPlayerItemDidPlayToEndTimeNotification", "AVPlayerItemFailedToPlayToEndTimeNotification"] {
            observers.append(center.addObserver(forName: Notification.Name(name), object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.stop() }
            })
        }
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            MainActor.assumeIsolated { self?.pause() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            // The media server restarted: this player and the session are gone.
            MainActor.assumeIsolated { self?.stop() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            // Headphones unplugged: pause like every audio app.
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.pause() }
        })
        self.player = player
        activeID = id
        playing = true
        player.play()
        claimAudio(for: player)
    }

    /// Asks for the shared playback session off the main thread and unmutes this player
    /// once it is active. A refusal (another app's call) stops the note, as before.
    private func claimAudio(for player: AVPlayer) {
        UnyriPlaybackAudioSession.acquire(.videoNote, mode: .moviePlayback) { [weak self, weak player] grant in
            guard let self, let player, self.player === player else { return }
            switch grant {
            case .active:
                guard self.playing else { return }
                player.isMuted = false
                // A deactivation that landed while this note waited may have stopped it.
                if player.rate == 0 { player.play() }
            case .refused: self.stop()
            case .superseded: break
            }
        }
    }

    func pause() {
        guard playing else { return }
        player?.pause()
        playing = false
        releaseSession()
    }

    /// Off the main thread, and only while this player still holds the session.
    private func releaseSession() {
        UnyriPlaybackAudioSession.release(.videoNote)
    }

    func stop(id: String) {
        if activeID == id { stop() }
    }

    /// Ends playback and releases the file and the audio session. A no-op when idle.
    func stop() {
        teardown(releasingSession: true)
    }

    private func teardown(releasingSession: Bool) {
        guard activeID != nil || player != nil else { return }
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        lease = nil
        if playing { playing = false }
        activeID = nil
        if releasingSession { releaseSession() }
    }

}

/// AVPlayerLayer plus a thin progress ring. The ring follows the player from a display
/// link that only runs while the note plays; SwiftUI is not involved per frame.
final class UnyriVideoNoteSurfaceView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var videoLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    private let ring = CAShapeLayer()
    private weak var player: AVPlayer?
    private var link: CADisplayLink?
    var running = false {
        didSet { if running != oldValue { synchronize() } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        videoLayer.videoGravity = .resizeAspectFill
        ring.fillColor = UIColor.clear.cgColor
        ring.strokeColor = UIColor.white.cgColor
        ring.lineWidth = 3
        ring.lineCap = .round
        ring.strokeEnd = 0
        layer.addSublayer(ring)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let side = min(bounds.width, bounds.height)
        guard side > 0 else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.frame = bounds
        ring.path = UIBezierPath(arcCenter: CGPoint(x: bounds.midX, y: bounds.midY), radius: max(1, side / 2 - 2),
                                 startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        synchronize()
    }

    func bind(_ player: AVPlayer?) {
        guard self.player !== player else { return }
        self.player = player
        videoLayer.player = player
        setFraction(0)
    }

    private func synchronize() {
        if running && window != nil {
            guard link == nil else { return }
            let timer = CADisplayLink(target: self, selector: #selector(tick))
            timer.preferredFramesPerSecond = 20
            timer.add(to: .main, forMode: .common)
            link = timer
        } else {
            link?.invalidate()
            link = nil
            tick()
        }
    }

    @objc private func tick() {
        guard let player, let item = player.currentItem else { return }
        let total = item.duration.seconds
        let now = player.currentTime().seconds
        guard total.isFinite, total > 0, now.isFinite else { return }
        setFraction(CGFloat(min(1, max(0, now / total))))
    }

    private func setFraction(_ value: CGFloat) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.strokeEnd = value
        CATransaction.commit()
    }
}

struct UnyriVideoNoteSurface: UIViewRepresentable {
    let player: AVPlayer
    let playing: Bool

    func makeUIView(context: Context) -> UnyriVideoNoteSurfaceView {
        let view = UnyriVideoNoteSurfaceView(frame: .zero)
        view.bind(player)
        view.running = playing
        return view
    }

    func updateUIView(_ view: UnyriVideoNoteSurfaceView, context: Context) {
        view.bind(player)
        view.running = playing
    }

    static func dismantleUIView(_ view: UnyriVideoNoteSurfaceView, coordinator: ()) {
        view.running = false
        view.bind(nil)
    }
}

// MARK: - Posters

/// One sharp poster per note, the frame at `VideoNoteLimits.posterSeconds` (the moment
/// the recorder grabs and the upload thumbnail shows), so a note looks the same queued,
/// sent, received and after a relaunch. Memory only; cleared with the decoded images.
@MainActor
enum VideoNotePosters {
    /// Keyed "a:<attachmentId>".
    private static let posters: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 120
        // About 0.9 MB per 480 px poster.
        cache.totalCostLimit = 48 * 1024 * 1024
        return cache
    }()
    /// The sender's notes still in the outbox (clientMessageId, frame), oldest first.
    /// Strong, unlike the caches: a memory warning, a background purge or another chat's
    /// deletion mid-upload must not turn the sent row into a blur. Released when the sent
    /// row adopts the frame, on cancel and at sign-out.
    private static var sending: [(clientMessageId: String, image: UIImage)] = []
    /// Bounds rows never shown again (the chat left mid-upload, a failed note deleted).
    private static let sendingLimit = 8
    private static func attachmentKey(_ id: String) -> NSString { ("a:" + id) as NSString }
    private static func clientKey(_ id: String) -> NSString { ("c:" + id) as NSString }
    private static func cost(_ image: UIImage) -> Int {
        Int(image.size.width * image.size.height * image.scale * image.scale * 4)
    }

    /// Memory only, safe in a body: the attachment's poster, else the sender's own.
    static func cached(attachmentId: String?, clientMessageId: String?) -> UIImage? {
        if let attachmentId, let hit = posters.object(forKey: attachmentKey(attachmentId)) { return hit }
        guard let clientMessageId else { return nil }
        return sending.last { $0.clientMessageId == clientMessageId }?.image
    }

    /// The sender's frame (the recorder's, or one decoded from the staged file), held
    /// until the sent row adopts it.
    static func store(_ image: UIImage, clientMessageId: String) {
        sending.removeAll { $0.clientMessageId == clientMessageId }
        sending.append((clientMessageId: clientMessageId, image: image))
        if sending.count > sendingLimit { sending.removeFirst(sending.count - sendingLimit) }
        UnyriAttachmentImages.changes.send(.client(clientMessageId))
    }

    /// The send was cancelled or never queued: no row will adopt its frame.
    static func forgetSend(clientMessageId: String) {
        sending.removeAll { $0.clientMessageId == clientMessageId }
    }

    /// Keeps the sender's poster once the upload names the attachment. Call from tasks.
    static func adopt(_ image: UIImage, attachmentId: String, clientMessageId: String?) {
        let key = attachmentKey(attachmentId)
        if posters.object(forKey: key) !== image { posters.setObject(image, forKey: key, cost: cost(image)) }
        // The sent row now pins and retains it; a second copy would only cost memory.
        if let clientMessageId { forgetSend(clientMessageId: clientMessageId) }
        UnyriAttachmentImages.changes.send(.attachment(attachmentId))
    }

    static func remove(attachmentId: String) { posters.removeObject(forKey: attachmentKey(attachmentId)) }

    /// Sign-out and cache erasure clear everything. A chat deletion keeps other chats'
    /// sends: the deleted chat's own were cancelled with it (discardPendingUpload).
    static func clear(includingSends: Bool = true) {
        posters.removeAllObjects()
        if includingSends { sending.removeAll() }
    }

}

// MARK: - Bubble

/// Upload or download progress drawn around the circle.
struct UnyriVideoNoteRing: View {
    let fraction: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            Circle().inset(by: 2).stroke(Color.white.opacity(0.18), lineWidth: 3)
            Circle().inset(by: 2).trim(from: 0, to: max(0.02, min(1, fraction)))
                .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(reduceMotion ? nil : .linear(duration: 0.16), value: fraction)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct UnyriVideoNoteChip: View {
    let text: String
    var body: some View {
        Text(text).font(.caption2.monospacedDigit()).foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(.black.opacity(0.62), in: Capsule())
            .padding(6)
            .accessibilityHidden(true)
    }
}

/// A received or sent round video message. No bubble background: the circle is the
/// message. A tap plays it in place with sound; another tap pauses.
struct UnyriVideoNoteBubble: View {
    let id: String
    /// The sender's own message id: the recorder's poster waits under it until adopted.
    var clientMessageId: String? = nil
    /// The frame an ordinary video of the same dimensions would take (keeps row geometry).
    let size: CGSize
    /// The inline thumbnail (base64), shown softened until a sharp poster exists.
    let thumbnail: String?
    /// The poster the row retained for the long-press preview, which decodes nothing.
    var menuPoster: UIImage? = nil
    let path: String?
    let durationMs: Int64?
    let pendingSend: Bool
    let downloading: Bool
    let failed: Bool
    let menuPreview: Bool
    /// On screen, app active, viewer closed. Playback stops when this turns false. Timeline
    /// rows hold `.active` through a transient `.inactive` (`ChatHostedScene`), so Control
    /// Center or a system alert never stops a playing note; going to the background does.
    let visible: Bool
    @ObservedObject var downloadMeter: AttachmentDownloadMeter
    var onPoster: (UIImage) -> Void = { _ in }
    let download: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var player = UnyriVideoNotePlayer.shared
    // Not @EnvironmentObject: observing the player and recorder re-rendered every
    // visible note on each play, pause, speed change and recording. Plain references
    // serve the tap; only the note that plays watches them (UnyriVideoNoteSoundGuard).
    @Environment(\.videoNoteVoicePlayer) private var voicePlayer
    @Environment(\.videoNoteVoiceRecorder) private var voiceRecorder

    private var side: CGFloat { max(44, min(size.width, size.height)) }
    private var active: Bool { !menuPreview && player.activeID == id }
    private var playing: Bool { active && player.playing }

    var body: some View {
        let poster = menuPoster
        ZStack {
            Circle().fill(Color(white: 0.12))
            if let poster {
                Image(uiImage: poster).resizable().interpolation(.high).scaledToFill()
                    .frame(width: side, height: side).clipShape(Circle())
                    .transition(.opacity)
            }
            if active, let current = player.player {
                UnyriVideoNoteSurface(player: current, playing: player.playing)
                    .frame(width: side, height: side).clipShape(Circle())
                    .allowsHitTesting(false)
            }
            if let symbol = statusSymbol {
                Image(systemName: symbol).font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 48, height: 48).background(.black.opacity(0.45), in: Circle())
                    .transition(.opacity)
            }
            if !pendingSend && downloading {
                UnyriVideoNoteRing(fraction: CGFloat(downloadMeter.percent) / 100)
            }
        }
        .frame(width: side, height: side)
        .overlay(alignment: .bottomLeading) {
            if durationMs != nil { UnyriVideoNoteChip(text: FilePresentation.duration(durationMs)) }
        }
        .frame(width: size.width, height: size.height)
        // Plays on the first tap: no wait for a possible double-tap reaction.
        .modifier(UnyriMessageTap(single: tap, immediate: true))
        .onChange(of: visible) { shown in if !shown { player.stop(id: id) } }
        .onDisappear { if !menuPreview { player.stop(id: id) } }
        // One sound at a time: a voice message, a recording or a call ends the note.
        .background {
            if active, let voicePlayer, let voiceRecorder {
                UnyriVideoNoteSoundGuard(voicePlayer: voicePlayer, voiceRecorder: voiceRecorder) { player.stop(id: id) }
            }
            if active, let path, let current = player.player {
                UnyriPlayerPosterCapture(id: id, url: URL(fileURLWithPath: path), player: current,
                    ready: playing, active: visible)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(UnyriL10n.string("Video message")))
        .accessibilityValue(Text(playing ? UnyriL10n.string("Playing") : active ? UnyriL10n.string("Paused") : FilePresentation.duration(durationMs)))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { tap() }
        .accessibilityIdentifier(menuPreview ? "message-menu-video-note" : "chat-video-note-" + id)
    }

    private var statusSymbol: String? {
        if pendingSend || playing { return nil }
        if downloading { return nil }
        if failed { return "arrow.clockwise" }
        if path == nil { return "arrow.down" }
        return "play.fill"
    }



    private func tap() {
        guard !menuPreview else { return }
        UnyriHaptics.press()
        guard let path else {
            if !downloading { download() }
            return
        }
        if !playing {
            // Never over a call or a recording.
            guard visible, voiceRecorder?.callInProgress != true, voiceRecorder?.hasRecordingSession != true,
                  !NonCallCaptureOwnership.isBusy else { return }
            voicePlayer?.stop()
        }
        player.toggle(id: id, url: URL(fileURLWithPath: path))
    }
}

/// Mounted only behind the note that plays: ends it when a voice message starts, a
/// recording begins or a call arrives. It subscribes to just those three values
/// (deduplicated), so neither it nor the other notes re-render on player changes.
private struct UnyriVideoNoteSoundGuard: View {
    let voicePlayer: VoicePlayerController
    let voiceRecorder: VoiceRecorder
    let stop: () -> Void

    var body: some View {
        Color.clear
            .onReceive(voicePlayer.playingAttachmentPublisher.dropFirst()) { other in if other != nil { stop() } }
            .onReceive(voiceRecorder.$callInProgress.removeDuplicates().dropFirst()) { busy in if busy { stop() } }
            .onReceive(voiceRecorder.$hasRecordingSession.removeDuplicates().dropFirst()) { busy in if busy { stop() } }
            .accessibilityHidden(true)
    }
}

/// The account's voice player and recorder as plain (non-observing) references, set
/// once on the signed-in content. A reference that stays the same never invalidates.
private struct VideoNoteVoicePlayerKey: EnvironmentKey { static var defaultValue: VoicePlayerController? { nil } }
private struct VideoNoteVoiceRecorderKey: EnvironmentKey { static var defaultValue: VoiceRecorder? { nil } }
extension EnvironmentValues {
    var videoNoteVoicePlayer: VoicePlayerController? {
        get { self[VideoNoteVoicePlayerKey.self] }
        set { self[VideoNoteVoicePlayerKey.self] = newValue }
    }
    var videoNoteVoiceRecorder: VoiceRecorder? {
        get { self[VideoNoteVoiceRecorderKey.self] }
        set { self[VideoNoteVoiceRecorderKey.self] = newValue }
    }
}

/// The sender's own note while it waits in the outbox (before the upload finishes).
struct UnyriQueuedVideoNoteBubble: View {
    /// The message's clientMessageId: the recorder's poster is stored under it.
    let id: String
    let preview: QueuedMediaPreview
    let size: CGSize
    var metadata: AnyView? = nil

    @Environment(\.scenePhase) private var phase
    @Environment(\.unyriMessagePreview) private var isMenuPreview
    @State private var visible = false
    @StateObject private var imageSource = UnyriAttachmentImageSource()
    private var imageRequest: AttachmentImageRequest {
        AttachmentImageRequest(id: "file:" + preview.url.path, url: preview.url, mime: "video/mp4",
            clientMessageID: id, note: true, repositoryAttachment: false)
    }

    var body: some View {
        let side = max(44, min(size.width, size.height))
        let poster = imageSource.best(for: imageRequest)
        ZStack {
            Circle().fill(Color(white: 0.12))
            if let poster {
                Image(uiImage: poster).resizable().interpolation(.high).scaledToFill()
                    .frame(width: side, height: side).clipShape(Circle())
            }
        }
        .frame(width: side, height: side)
        .overlay(alignment: .bottomLeading) {
            if preview.duration > 0 { UnyriVideoNoteChip(text: FilePresentation.duration(preview.duration)) }
        }
        .frame(width: size.width, height: size.height)
        .overlay(alignment: .bottomTrailing) { if let metadata { metadata } }
        .modifier(UnyriAttachmentImageBinding(source: imageSource, request: imageRequest,
            onVisibility: { visible = $0 }))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(UnyriL10n.string("Video message")))
        .accessibilityIdentifier("chat-video-note-pending")
    }
}

/// Upload progress as a ring around the circle, with Cancel in the middle.
struct UnyriVideoNoteUploadRing: View {
    @EnvironmentObject private var attachments: AttachmentManager
    let clientMessageId: String
    let cancel: () -> Void
    var body: some View {
        UnyriVideoNoteUploadRingBody(meter: attachments.sendMeter(clientMessageId), cancel: cancel)
    }
}

private struct UnyriVideoNoteUploadRingBody: View {
    @ObservedObject var meter: AttachmentSendMeter
    let cancel: () -> Void
    var body: some View {
        ZStack {
            UnyriVideoNoteRing(fraction: CGFloat(meter.percent ?? 0) / 100)
            Button { UnyriHaptics.selection(); cancel() } label: {
                Image(systemName: "xmark").font(.system(size: 16, weight: .medium)).foregroundStyle(.white)
                    .frame(width: 40, height: 40).background(.black.opacity(0.48), in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(UnyriL10n.string("Cancel sending")))
            .accessibilityValue(Text(meter.percent.map { "\($0)%" } ?? UnyriL10n.string(meter.stage.label)))
            .accessibilityIdentifier("chat-video-note-cancel-upload")
        }
    }
}
