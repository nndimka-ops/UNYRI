import SwiftUI

struct CallHistoryRow: View {
    let call: CallDTO
    let owner: UUID?
    var name: String? = nil
    var listRow = false
    var earlierHistory = false
    var peer: PublicUser? = nil
    var menuPresenter: UnyriMessageMenuPresenter? = nil
    var openMenu: (() -> Void)? = nil
    var selection: Bool? = nil
    /// Chat timeline: tapping the row calls back (Telegram-like).
    var callBack: (() -> Void)? = nil
    @EnvironmentObject private var users: UsersRepository
    @Environment(\.unyriChatAppearance) private var appearance
    @State private var avatarURL: URL?
    private var title: String {
        let bundled = call.validatedPeer(owner: owner)
        let handle = peer?.peerIdentityLabel(legacyServer: users.legacyIdentityServer) ??
            PeerIdentityPolicy.label(publicUsername: bundled?.publicUsername, unyriNumber: bundled?.unyriNumber,
                                     legacyUsername: bundled?.username, legacyServer: users.legacyIdentityServer)
        return CallPeerTitlePolicy.title(displayName: name.map { DisplayNameSanitizer.display($0) },
            publicIdentity: handle, unknown: UnyriL10n.string("Unknown user"))
    }
    var body: some View {
        if listRow {
            HStack(spacing: 12) {
                UserAvatar(url: avatarURL, name:title, size:44)
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        if call.unread { Circle().fill(UnyriColors.danger).frame(width: 7, height: 7).accessibilityLabel("Unread") }
                        Spacer(minLength: 4)
                        Text(RelativeTime.listLabel(for: call.createdAt))
                            .font(.caption2).foregroundColor(UnyriColors.mutedText).lineLimit(1)
                    }
                    HStack(spacing: 5) {
                        Image(systemName: call.callerUserId == owner ? "arrow.up.right" : "arrow.down.left")
                        Text(LocalizedStringKey(call.historyLabel(owner: owner)))
                        Text("· " + UnyriL10n.string(call.mediaKind == .video ? "Video" : "Audio", comment: ""))
                        if let duration = call.durationSeconds {
                            Text("· \(duration / 60):\(String(format: "%02d", duration % 60))")
                        }
                    }.font(.caption).foregroundColor(call.isMissedIncoming(owner: owner) ? UnyriColors.danger : UnyriColors.secondaryText)
                }
            }.foregroundColor(UnyriColors.primaryText)
                .padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .task(id: "\(peer?.id ?? ""):\(peer?.avatarVersion ?? 0)") {
                    avatarURL = nil
                    if let peer { avatarURL = await users.avatarURL(id: peer.id, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion ?? 0) }
                }
        } else {
        HStack {
            if !listRow && call.callerUserId == owner { Spacer(minLength: 32) }
            if let selection, call.callerUserId == owner { selectionMark(selection) }
            callSurface
            .background {
                if let menuPresenter, let openMenu {
                    UnyriMessageMenuAnchor(presenter: menuPresenter, open: openMenu, enabled: selection == nil,
                        preview: AnyView(callSurface))
                }
            }
            if let selection, call.callerUserId != owner { selectionMark(selection) }
            if !listRow && call.callerUserId != owner { Spacer(minLength: 32) }
        }.padding(.vertical, 3)
        }
    }
    @ViewBuilder private var callSurface: some View {
        if listRow { listSurface } else { chatSurface }
    }

    /// In the chat: a compact bubble in the message colours — title, direction arrow,
    /// time and duration, and the call-back glyph on the trailing side.
    private var chatSurface: some View {
        let outgoing = call.callerUserId == owner
        let missed = call.isMissedIncoming(owner: owner)
        var details = call.createdAt.formatted(date: .omitted, time: .shortened)
        if let duration = call.durationSeconds, duration > 0 {
            details += duration >= 60 ? " · \(duration / 60) " + UnyriL10n.string("min") : " · \(duration) " + UnyriL10n.string("sec")
        }
        return HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                if earlierHistory { Text("Earlier call — before this sign-in").font(.caption2).foregroundColor(UnyriColors.secondaryText) }
                Text(LocalizedStringKey(call.historyLabel(owner: owner))).font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(outgoing ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                HStack(spacing: 4) {
                    Image(systemName: outgoing ? "arrow.up.right" : "arrow.down.left").font(.system(size: 10, weight: .bold))
                        .foregroundStyle(missed ? UnyriColors.danger : UnyriSavedChatStyle.muted)
                    Text(details).font(.system(size: 12)).monospacedDigit().foregroundStyle(UnyriSavedChatStyle.muted)
                }
            }
            Image(systemName: call.mediaKind == .video ? "video" : "phone")
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(outgoing ? UnyriSavedChatStyle.outText : UnyriSavedChatStyle.text)
                .frame(width: 36, height: 36)
                .background(Color.white.opacity(0.08), in: Circle())
                // Only the glyph calls back (a tap anywhere on the bubble started calls by accident).
                .contentShape(Circle().inset(by: -6))
                .onTapGesture { if selection == nil, let callBack { UnyriHaptics.press(); callBack() } }
                .accessibilityHidden(true)
        }
        .padding(.leading, 13).padding(.trailing, 8).padding(.vertical, 7)
        .fixedSize(horizontal: true, vertical: false)
        .background(outgoing ? appearance.outgoing : appearance.incoming, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
        // VoiceOver calls back through an action, never by activating the bubble itself.
        .accessibilityAction(named: Text("Call back")) { if selection == nil, let callBack { callBack() } }
    }

    private var listSurface: some View {
        HStack(spacing: 12) {
            Image(systemName: call.mediaKind == .video ? "video.fill" : "phone.fill")
                .foregroundColor(call.historyLabel(owner: owner) == "Missed call" ? UnyriColors.danger : UnyriColors.accent)
            VStack(alignment: .leading, spacing: 4) {
                if earlierHistory { Text("Earlier call — before this sign-in").font(.caption2).foregroundColor(UnyriColors.secondaryText) }
                if name != nil { Text(title).font(.subheadline.weight(.semibold)).lineLimit(1) }
                Text(LocalizedStringKey(call.historyLabel(owner: owner))).font(.subheadline.weight(.medium))
                Text(UnyriL10n.string(call.mediaKind == .video ? "Video" : "Audio", comment: "") + (call.durationSeconds.map { " · \($0 / 60):\(String(format: "%02d", $0 % 60))" } ?? ""))
                    .font(.caption).foregroundColor(UnyriColors.secondaryText)
                Text(call.createdAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption2).foregroundColor(UnyriColors.mutedText)
            }
        }
        .foregroundColor(UnyriColors.primaryText)
        .padding(14)
        .frame(maxWidth: listRow ? .infinity : nil, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(listRow ? Color.clear : UnyriColors.glassPrimary))
    }

    private func selectionMark(_ selected: Bool) -> some View {
        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 24, weight: .semibold)).foregroundStyle(UnyriColors.accentSoft)
            .frame(width: 32, height: 44).accessibilityHidden(true)
    }

}

/// The presenter's callbacks behind one stable reference. LoggedInView builds
/// fresh closures on every render; keeping them here lets `CallScreen` compare
/// equal and skip re-rendering when only its parent re-rendered.
@MainActor
final class CallScreenActions: ObservableObject {
    private(set) var onMinimize: () -> Void = {}
    private(set) var onOpenChat: (String) -> Void = { _ in }
    private(set) var setExpanded: (Bool) -> Void = { _ in }

    func update(onMinimize: @escaping () -> Void, onOpenChat: @escaping (String) -> Void,
                setExpanded: @escaping (Bool) -> Void) {
        self.onMinimize = onMinimize
        self.onOpenChat = onOpenChat
        self.setExpanded = setExpanded
    }
}

/// The full-screen call cover's content. A separate small view so
/// LoggedInView's body stays a single expression for this presentation.
struct CallScreenHost: View {
    let pictureInPicture: CallPictureInPicture
    let controller: CallController
    let callKit: CallKitCoordinator
    let bannerController: ForegroundMessageBannerController
    let morph: CallMorphAnchors
    let rotation: CallScreenRotation
    let onMinimize: () -> Void
    let onOpenChat: (String) -> Void
    let setExpanded: (Bool) -> Void
    let onVisibilityChange: (Bool) -> Void
    @StateObject private var actions = CallScreenActions()

    var body: some View {
        let _ = actions.update(onMinimize: onMinimize, onOpenChat: onOpenChat, setExpanded: setExpanded)
        CallScreen(actions: actions, pictureInPicture: pictureInPicture, bannerController: bannerController, morph: morph, rotation: rotation)
            .equatable()
            .environmentObject(pictureInPicture)
            .environmentObject(controller)
            .environmentObject(callKit)
            .environmentObject(bannerController)
            .background(CallMorphWindowAnchor(morph: morph).frame(width: 0, height: 0))
            .onAppear { onVisibilityChange(true) }
            .onDisappear { onVisibilityChange(false) }
    }
}

/// Functional call screen mirroring Android's call UI (outgoing/incoming/
/// connecting/connected). The single `CallController.state` is the source of
/// truth; this view only renders it and forwards user actions.
///
/// One top bar (minimize button and status capsule) sits over both layouts
/// and never changes identity, so switching layouts moves nothing in it.
/// Calling, connecting, connected and reconnecting share ONE layout with fixed
/// slots (avatar, name, four buttons), so nothing jumps between phases. A call
/// placed or answered as video has its own single layout (four slots: camera,
/// microphone, end, output; the flip sits on the self-view) from the first
/// frame through connected, chosen from the call itself
/// (`CallPresentationPolicy`); its stage shows the audio-style identity until
/// a picture is live. The voice halo, the status capsule and the message
/// banner observe their own sources, never this view.
struct CallScreen: View, Equatable {
    let actions: CallScreenActions
    let pictureInPicture: CallPictureInPicture
    let bannerController: ForegroundMessageBannerController
    let morph: CallMorphAnchors
    let rotation: CallScreenRotation
    @EnvironmentObject private var controller: CallController
    @EnvironmentObject private var callKit: CallKitCoordinator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var deviceOrientation = CallDeviceOrientation.shared
    @State private var rotationReady = false
    @State private var systemPipPresented = false
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    @State private var videoChromeHidden = false
    @State private var remoteFraming = CallRemoteVideoFraming()
    /// "Reconnecting…" appears only after a drop lasts ~1.2 s.
    @State private var reconnectingShown = false
    /// Keep the last remote frame through a reconnect instead of black.
    @State private var staleRemoteFrame = false
    /// The stage's size, measured where the root GeometryReader used to be.
    @State private var size: CGSize = .zero

    /// Reference-only on purpose: the parent's renders must not re-render the
    /// call (e7b8347d, 7625ca32). The body still runs on every publish of the
    /// controller, but nothing rendered may read `controller` or `callKit`
    /// inside a closure SwiftUI evaluates later and may keep (GeometryReader
    /// content, a stored @ViewBuilder): that kept the tray, the self-view and
    /// the avatar at the state of the moment the call connected (0.4.10-0.4.11:
    /// buttons that did not change, no self-view, the avatar over live video).
    /// Everything rendered comes from the `Snapshot` taken at the top of
    /// `body`; button actions read live state.
    nonisolated static func == (lhs: CallScreen, rhs: CallScreen) -> Bool {
        lhs.actions === rhs.actions && lhs.pictureInPicture === rhs.pictureInPicture
            && lhs.bannerController === rhs.bannerController && lhs.morph === rhs.morph && lhs.rotation === rhs.rotation
    }

    /// Reserved at the top of both layouts: the 44 pt minimize button and the
    /// capsule with 6 pt above and below.
    static let topBarHeight: CGFloat = 56

    #if UNYRI_ALPHA_LAN
    /// QA builds only: the simulator never has a live peer picture, so
    /// walkthroughs force the top bar's real glass with
    /// UNYRI_WALK_FORCE_CALL_GLASS=1.
    private static let qaForceGlass = ProcessInfo.processInfo.environment["UNYRI_WALK_FORCE_CALL_GLASS"] == "1"
    #else
    private static let qaForceGlass = false
    #endif

    var body: some View {
        let snap = snapshot
        let actionError = callKit.actionError
        let reconnecting = snap.reconnecting
        // A tap hides the chrome only while a picture is live in the call.
        let chromeHidable = snap.videoLive && (snap.stage?.inCall ?? false)
        let chromeHidden = videoChromeHidden && chromeHidable
        // An overlay cannot grow its base. Layout uses this pass's proposal,
        // never the preceding preference value during a system rotation.
        // The kept reader captures only this pass's Snapshot, not controller.
        Color.clear
        .overlay(alignment: .topLeading) {
            GeometryReader { proxy in
                let size = proxy.size
                // An upgrade or downgrade crossfades: the screen is never empty.
                ZStack(alignment: .topLeading) {
                    if snap.videoLayout, let stage = snap.stage {
                        videoStage(stage, snap, size: size, chromeHidden: chromeHidden).transition(.opacity)
                    } else {
                        audioStage(snap, size: size).transition(.opacity)
                    }
                }
                .animation(reduceMotion ? .easeInOut(duration: 0.2) : .easeInOut(duration: 0.3), value: snap.videoLayout)
                .preference(key: CallScreenSizeKey.self, value: size)
            }
        }
        .onPreferenceChange(CallScreenSizeKey.self) { next in
            remoteFraming.updateStage(next)
            size = next
        }
        .onReceive(deviceOrientation.$state) { orientation in
            remoteFraming.updateScreen(orientation.interface)
        }
        .onReceive(pictureInPicture.$isSystemPresented) { systemPipPresented = $0 }
        .background(CallRotationHost(rotation: rotation, active: rotationReady && scenePhase != .background && !systemPipPresented))
        .background(CallPipSource(pip: pictureInPicture, role: "callScreen"))
        // One bar for both layouts, outside the layout branch: its identity
        // never changes, so nothing in it jumps when the layout switches.
        .overlay(alignment: .top) { topBar(snap, chromeHidden: chromeHidden) }
        .overlay(alignment: .top) { CallMessageBannerOverlay(controller: bannerController, open: openChat) }
        // The same filtered controls as the stage: the picture and the
        // avatar over it can never disagree.
        .background { backdrop(snap.videoLayout ? snap.controls : nil, chromeHidden: chromeHidden) }
        .onChange(of: snap.stage?.call.callId) { _ in
            videoChromeHidden = false
            remoteFraming = CallRemoteVideoFraming()
            staleRemoteFrame = false
        }
        // The chrome comes back when the picture stops.
        .onChange(of: chromeHidable) { hidable in if !hidable { videoChromeHidden = false } }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
            let next = ProcessInfo.processInfo.isLowPowerModeEnabled
            if lowPower != next { lowPower = next }
        }
        .onChange(of: reconnecting) { reconnecting in
            if reconnecting, controller.videoPresentation?.remoteVideoState == .on { staleRemoteFrame = true }
        }
        .onChange(of: controller.videoPresentation?.remoteVideoState) { state in
            if state != .waiting { staleRemoteFrame = false }
        }
        .performanceScreen(.call)
        .environment(\.callStageSize, size)
        .task(id: reconnecting) {
            reconnectingShown = false
            guard reconnecting else { return }
            do { try await Task.sleep(nanoseconds: 1_200_000_000) } catch { return }
            reconnectingShown = true
        }
        .task(id: staleRemoteFrame && !reconnecting) {
            // A recovered peer sends a fresh frame within moments; if none
            // comes, fall back to the avatar instead of a frozen picture.
            guard staleRemoteFrame, !reconnecting else { return }
            do { try await Task.sleep(nanoseconds: 4_000_000_000) } catch { return }
            staleRemoteFrame = false
        }
        .onAppear {
            rotation.beginPresentation()
            // Expansion completes when its ghost leaves; no busy polling.
            morph.whenSettled { rotationReady = true }
            bannerController.isCallExpanded = true
            actions.setExpanded(true)
            NotificationCenter.default.post(name: .unyriCallScreenVisibility, object: true)
        }
        .onDisappear {
            morph.cancelSettledCallback()
            rotationReady = false
            rotation.disappeared()
            bannerController.isCallExpanded = false
            actions.setExpanded(false)
            NotificationCenter.default.post(name: .unyriCallScreenVisibility, object: false)
        }
        // VoiceOver's two-finger scrub collapses the call, like the button.
        .accessibilityElement(children: .contain)
        .accessibilityAction(.escape) {
            if let key = Self.minimizeKey(controller.state, starting: callKit.startingCall) { minimize(callID: key) }
        }
        .alert("Call error", isPresented: Binding(
            get: { callKit.actionError != nil },
            set: { if !$0 { callKit.actionError = nil } }
        )) {
            Button("OK") { callKit.actionError = nil }
        } message: { Text(verbatim: actionError ?? "") }
    }

    private func openChat(_ chatId: String) { rotation.beforeLeaving { actions.onOpenChat(chatId) } }

    // MARK: - One snapshot per body pass

    /// Everything the screen renders, read once per body pass and handed down
    /// as values (see `==`).
    private struct Snapshot {
        let state: CallUiState
        let stage: Stage?
        /// The controls of the call on screen, never another call's.
        let controls: ConnectedState?
        let videoLayout: Bool
        let reconnecting: Bool
        let connectedAt: Date?
        /// The call the minimize button collapses (nil hides it).
        let minimizeKey: String?
        /// The own camera shows frames for this call and another camera
        /// exists: the self-view carries the flip.
        let canFlip: Bool
        /// The own camera runs on a camera with a torch (the back one): the
        /// self-view carries the torch beside the flip.
        let canTorch: Bool
        let torchOn: Bool
        /// What the status capsule says the call is doing.
        let statusPhase: CallStatusPhase?
        let ringPhase: CallRingIndicator.Phase?
        let ringbackStartedAt: TimeInterval?
        /// The video layout shows a live picture: the peer's (a kept frame
        /// included) or our own camera.
        let videoLive: Bool
        /// The peer's picture (a kept frame included) fills the screen, so
        /// the aurora under the top bar is paused and covered: the only time
        /// the bar draws real glass. Our camera alone floats in a corner
        /// over the drifting aurora, which keeps the flat rest look.
        let remotePictureLive: Bool
        /// Value captured before any kept GeometryReader/ViewBuilder closure.
        let selfViewDelta: Int
    }

    private var snapshot: Snapshot {
        let state = controller.state
        let starting = callKit.startingCall
        let stage = Self.stage(state, starting: starting)
        let all = controller.audioControls
        let controls = all.flatMap { c in c.call.callId == stage?.call.callId ? c : nil }
        // From the call itself: the instant "Calling…" screen, ringing and
        // connecting of a video call already use the video layout.
        let videoLayout = stage != nil && CallPresentationPolicy.showsVideoLayout(state: state, controls: all,
            startingVideo: starting?.isVideo, endedInVideo: controller.endedInVideoLayout)
        let reconnecting: Bool = { if case .reconnecting = state { return true }; return false }()
        let cameraReady = controls.map { $0.videoMode == .video && $0.localVideoEnabled && $0.localCameraState == .on } ?? false
        let remoteLive = controls.map(remoteVideoVisible) ?? false
        return Snapshot(state: state, stage: stage, controls: controls, videoLayout: videoLayout,
                        reconnecting: reconnecting, connectedAt: controller.connectedAt,
                        minimizeKey: Self.minimizeKey(state, starting: starting),
                        canFlip: cameraReady && controller.hasMultipleCameras(),
                        canTorch: cameraReady && controller.torchAvailable && controls?.localCameraIsFrontFacing == false,
                        torchOn: controller.torchOn,
                        statusPhase: Self.statusPhase(stage),
                        ringPhase: controller.ringIndicator?.phase ?? (stage?.phase == .starting ? .sending : nil),
                        ringbackStartedAt: controller.ringbackStartedAt,
                        videoLive: videoLayout && (remoteLive || cameraReady),
                        remotePictureLive: videoLayout && remoteLive,
                        selfViewDelta: CallOrientationPolicy.selfViewDelta(interface: deviceOrientation.state.interface,
                                                                         tag: deviceOrientation.state.tag))
    }

    /// While the call is still being created the button is already there, so
    /// the screen never rebuilds and the avatar never jumps when ringing
    /// starts (report 9 Nov: "opens like this, then normally").
    private static func minimizeKey(_ state: CallUiState, starting: CallKitCoordinator.StartingCall?) -> String? {
        CallMinimizePolicy.callID(state) ?? (state == .idle && starting != nil ? startingMinimizeKey : nil)
    }

    private static func statusPhase(_ stage: Stage?) -> CallStatusPhase? {
        guard let stage else { return nil }
        switch stage.phase {
        case .starting: return .starting
        case .calling: return .calling
        case .incoming: return .incoming(video: stage.call.mediaKind == .video)
        case .connecting: return .connecting
        case .connected: return .connected
        case .reconnecting: return .reconnecting
        case .ended(let reason): return .ended(reason)
        }
    }

    private func remoteVideoVisible(_ c: ConnectedState) -> Bool {
        c.remoteVideoState == .on || (staleRemoteFrame && c.remoteVideoEnabled)
    }

    /// One aurora for every mode; remote video lies over it. The aurora pauses
    /// while a live picture covers it, and the renderer stays mounted for the
    /// whole video call, from ringing on (it reports fresh frames and keeps the
    /// last one), so connecting never mounts a view. The drift also stops in
    /// Low Power Mode. With the peer's picture, static shades keep the status
    /// bar, the top bar and the tray legible over bright video.
    private func backdrop(_ video: ConnectedState?, chromeHidden: Bool) -> some View {
        let live = video.map(remoteVideoVisible) ?? false
        let fade: Animation = reduceMotion ? .easeInOut(duration: 0.15) : .easeInOut(duration: 0.25)
        return ZStack {
            Color.black
            UnyriCallAurora(animated: !reduceMotion && !live && !lowPower)
            if let video {
                // Fades against the identity that leaves or returns over it.
                // Draws only while shown or about to be (the first frame is on
                // its way); with the peer's camera off it stays mounted, paused.
                RemoteVideoView(fillsBounds: remoteFraming.fills(stage: size),
                                active: live || video.remoteVideoState == .waiting,
                                onGeometry: { geometry, rotation in
                                    remoteFraming.updateRemote(geometry, rotation: rotation)
                                })
                    .opacity(live ? 1 : 0)
                    .animation(fade, value: live)
                CallVideoScrims(bottomVisible: !chromeHidden)
                    .opacity(live ? 1 : 0)
                    .animation(fade, value: live)
                Color.black.opacity(live && reconnectingShown ? 0.45 : 0)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: reconnectingShown)
            }
        }
        .ignoresSafeArea(.container)
    }

    static let startingMinimizeKey = "starting"

    private func minimize(callID: String) {
        let allowed = callID == Self.startingMinimizeKey
            ? controller.state == .idle && callKit.startingCall != nil
            : CallMinimizePolicy.canComplete(requested: callID, state: controller.state)
        guard allowed else { return }
        UnyriHaptics.press()
        // The morph dismisses the cover itself; where it cannot run (iOS 16,
        // VoiceOver, busy) the cover slides down as before.
        rotation.beforeLeaving {
            let stillAllowed = callID == Self.startingMinimizeKey
                ? controller.state == .idle && callKit.startingCall != nil
                : CallMinimizePolicy.canComplete(requested: callID, state: controller.state)
            guard stillAllowed else { rotation.cancelLeaving(); return }
            let onMinimize = actions.onMinimize
            if !morph.collapse(style: .current(reduceMotion: reduceMotion), dismiss: onMinimize) { onMinimize() }
        }
    }

    /// The minimize button on the left and the status capsule in the middle,
    /// over both layouts at one height. The swipe owner is a sibling behind
    /// the controls, never their parent: a parent pan can consume the
    /// button's tap on recent UIKit/SwiftUI runtimes. Both surfaces stay for
    /// the whole call, so they draw real glass only over the peer's picture
    /// (one shared pass on iOS 26) and the flat rest look over the aurora:
    /// an audio call re-blurs nothing at rest. Each applies its glass to its
    /// own content, and a hidden one turns its glass off with it.
    private func topBar(_ snap: Snapshot, chromeHidden: Bool) -> some View {
        let key = snap.minimizeKey
        let callId = snap.stage?.call.callId
        let controller = self.controller
        let glass = snap.remotePictureLive || Self.qaForceGlass
        return ZStack {
            Color.clear.contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 16).onEnded { value in
                    if let key, CallMinimizePolicy.isDownwardSwipe(x: value.translation.width, y: value.translation.height) {
                        minimize(callID: key)
                    }
                })
                .allowsHitTesting(key != nil && !chromeHidden)
                .accessibilityHidden(true)
                // Replace only the gesture owner when a different call starts.
                // A held drag from the preceding call must never collapse it.
                .id(key ?? "no-call")
            HStack(spacing: 0) {
                CallMinimizeButton(glass: glass, visible: key != nil && !chromeHidden) { if let key { minimize(callID: key) } }
                    .allowsHitTesting(key != nil && !chromeHidden)
                    .accessibilityHidden(key == nil || chromeHidden)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            CallStatusCapsule(phase: snap.statusPhase, ring: snap.ringPhase, callId: callId, start: snap.connectedAt,
                              quality: controller.quality,
                              // Live on demand (the sheet asks), never a render's copy.
                              verification: { [weak controller] in callId.flatMap { controller?.verificationTranscript(callId: $0) } },
                              chromeHidden: chromeHidden, glass: glass)
                .frame(maxWidth: max(120, size.width - 2 * (12 + 44 + 8)))
        }
        .frame(height: Self.topBarHeight)
        .modifier(CallTopBarGlassGroup())
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: chromeHidden)
    }

    // MARK: - One layout for every audio phase

    private struct Stage {
        enum Phase: Equatable { case starting, calling, incoming, connecting, connected, reconnecting, ended(CallEndReason) }
        let call: CallInfo
        let phase: Phase
        var isEnded: Bool { if case .ended = phase { return true }; return false }
        /// Media is (or was) established: the timer, the quality and the camera run.
        var inCall: Bool { phase == .connected || phase == .reconnecting }
    }

    private static func stage(_ state: CallUiState, starting: CallKitCoordinator.StartingCall?) -> Stage? {
        switch state {
        case .idle:
            // Shown the instant the call button is tapped; becomes the ringing
            // screen in place once the call exists.
            guard let starting else { return nil }
            return Stage(call: CallInfo(callId: Self.startingMinimizeKey, remoteUserId: starting.remoteUserId,
                                        remoteDisplayName: starting.name,
                                        mediaKind: starting.isVideo ? .video : .audio, direction: .outgoing),
                         phase: .starting)
        case .outgoingRinging(let call): return Stage(call: call, phase: .calling)
        case .incomingRinging(let call): return Stage(call: call, phase: .incoming)
        case .connecting(let call): return Stage(call: call, phase: .connecting)
        case .connected(let c): return Stage(call: c.call, phase: .connected)
        case .reconnecting(let call): return Stage(call: call, phase: .reconnecting)
        case .ended(let call, let reason): return Stage(call: call, phase: .ended(reason))
        case .failed: return nil
        }
    }

    private func audioStage(_ snap: Snapshot, size: CGSize) -> some View {
        ScrollView {
            Group {
                if let stage = snap.stage {
                    stableLayout(stage, snap, size: size)
                } else if case .failed(_, let reason) = snap.state {
                    failedLayout(reason, snap).padding(.top, CallLayoutPolicy.failureTopInset(availableWidth: size.width, availableHeight: size.height))
                } else {
                    Color.clear
                }
            }
            .frame(width: size.width)
            .frame(minHeight: size.height)
        }
        .modifier(CallScrollBounce())
        .overlay(alignment: .top) {
            CallNoticeToast(notice: snap.controls?.visibleNotice).padding(.top, Self.topBarHeight + UnyriSpacing.sm)
        }
        .overlay(alignment: .topTrailing) { upgradePreview(snap.controls, size: size, delta: snap.selfViewDelta) }
    }

    /// The status and the timer live in the top bar's capsule, so the stage
    /// is only the avatar, the name and the buttons.
    @ViewBuilder
    private func stableLayout(_ stage: Stage, _ snap: Snapshot, size: CGSize) -> some View {
        if CallLayoutPolicy.isLandscape(availableWidth: size.width, availableHeight: size.height) {
            let layout = CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height)
            VStack(spacing: 0) {
                Color.clear.frame(height: layout.freeY)
                landscapeIdentity(stage, snap, size: size, hidden: false)
                    .frame(height: layout.freeHeight)
                Spacer(minLength: 8)
                controlsRow(stage, snap.controls, size: layout.control, spacing: layout.controlSpacing)
                    .padding(.vertical, UnyriSpacing.md)
                Color.clear.frame(height: UnyriSpacing.lg)
            }
            .frame(width: size.width, height: size.height)
        } else {
            portraitStableLayout(stage, snap, size: size)
        }
    }

    private func portraitStableLayout(_ stage: Stage, _ snap: Snapshot, size: CGSize) -> some View {
        let count = CallLayoutPolicy.audioControlCount
        let control = CallLayoutPolicy.controlSize(availableWidth: size.width, controlCount: count)
        let spacing = CallLayoutPolicy.controlSpacing(availableWidth: size.width, controlCount: count)
        let avatarSize = CallLayoutPolicy.avatarSize(availableHeight: size.height, isVideo: false)
        return VStack(spacing: 0) {
            Spacer().frame(height: Self.topBarHeight)
            Spacer(minLength: 8)
            avatar(stage.call, size: avatarSize, halo: haloMode(stage.phase))
            Spacer().frame(height: UnyriSpacing.xl)
            nameBlock(stage.call.visibleRemoteName)
            callPath(stage, snap, hidden: false).padding(.top, 12)
            Spacer(minLength: 8)
            controlsRow(stage, snap.controls, size: control, spacing: spacing)
                .padding(.horizontal, CallLayoutPolicy.trayPadding(availableWidth: size.width))
                .padding(.vertical, UnyriSpacing.md)
                .frame(maxWidth: CallLayoutPolicy.trayWidth(availableWidth: size.width))
            Spacer().frame(height: UnyriSpacing.xxxl)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, CallLayoutPolicy.trayMargin(availableWidth: size.width))
    }

    private func landscapeIdentity(_ stage: Stage, _ snap: Snapshot, size: CGSize, hidden: Bool) -> some View {
        let layout = CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height)
        return HStack(spacing: layout.identityGap) {
            avatar(stage.call, size: layout.avatar, halo: hidden ? .off : haloMode(stage.phase))
            VStack(spacing: 8) {
                nameBlock(stage.call.visibleRemoteName)
                callPath(stage, snap, hidden: hidden)
            }
            .frame(maxWidth: layout.identityTextWidth)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, layout.freeX)
    }

    /// An outgoing call keeps the two-phones slot from the tap through its
    /// end screen; `videoIdentity` reserves the same height.
    private static func showsCallPath(_ stage: Stage, _ snap: Snapshot) -> Bool {
        stage.call.direction == .outgoing && snap.ringPhase != nil
    }

    /// The slot and its 12 pt gap under the name.
    private static let callPathHeight = CallRingIndicatorView.size.height + 12

    @ViewBuilder
    private func callPath(_ stage: Stage, _ snap: Snapshot, hidden: Bool) -> some View {
        if Self.showsCallPath(stage, snap), let phase = snap.ringPhase {
            // Unmounting clears every native layer, including when video chrome
            // hides the identity, and an answered call's end shows no graphic.
            // The empty slot stays, so neither the answer nor the end moves
            // the avatar and the name.
            ZStack {
                if !hidden && !(stage.isEnded && phase == .answered) {
                    CallRingIndicatorView(phase: phase, connectedAt: snap.connectedAt,
                                          ringbackStartedAt: snap.ringbackStartedAt)
                }
            }
            .frame(width: CallRingIndicatorView.size.width, height: CallRingIndicatorView.size.height)
        }
    }

    private func haloMode(_ phase: Stage.Phase) -> CallVoiceHalo.Mode {
        switch phase {
        case .starting, .calling, .incoming, .connecting: return .ambient
        case .connected, .reconnecting: return .voice
        case .ended: return .off
        }
    }

    /// Two lines are always reserved, so a long or late-resolved name never
    /// pushes the buttons.
    private func nameBlock(_ name: String) -> some View {
        ZStack(alignment: .bottom) {
            Text(verbatim: "A\nA")
                .font(UnyriTypography.screenTitle)
                .lineLimit(CallLayoutPolicy.callNameMaxLines)
                .hidden()
            Text(name)
                .font(UnyriTypography.screenTitle)
                .foregroundColor(UnyriColors.primaryText)
                .lineLimit(CallLayoutPolicy.callNameMaxLines)
                .truncationMode(.tail)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, UnyriSpacing.xl)
    }

    /// Camera · Mute · End · Output. Audio calls expose the video upgrade only
    /// once connected; ringing and connecting keep working mute/output controls.
    /// White only off the default (FaceTime): the microphone muted, the
    /// output on the speaker of an iPhone (headphones and an iPad's speaker
    /// stay dark).
    @ViewBuilder
    private func controlsRow(_ stage: Stage, _ controls: ConnectedState?, size: CGFloat, spacing: CGFloat) -> some View {
        if stage.phase == .incoming {
            HStack(spacing: UnyriSpacing.xxxl) {
                controlCircle("phone.down.fill", label: "Decline", endCall: true, size: size, action: { Task { await controller.rejectIncoming() } })
                controlCircle("phone.fill", label: "Accept", color: UnyriColors.success, active: true, size: size, action: { callKit.answerIncoming() })
            }
        } else {
            let live = controls != nil && !stage.isEnded
            let muted = controls?.isMuted ?? false
            let row = CallControlRowPolicy.audio(videoAvailable: stage.phase == .connected && controls?.videoUpgrade == .idle,
                                                 live: live, ended: stage.isEnded)
            // B-067: only usable controls, centred; an inserted control re-centres the row.
            HStack(spacing: spacing) {
                ForEach(row, id: \.self) { control in
                    switch control {
                    case .video:
                        controlCircle("video.fill", label: "Start video", size: size,
                                      identifier: "call-video", action: { controller.requestVideoUpgrade() })
                            .transition(controlTransition)
                    case .mute:
                        controlCircle(muted ? "mic.slash.fill" : "mic.fill", label: muted ? "Unmute microphone" : "Mute microphone",
                                      active: muted, size: size, identifier: "call-mute",
                                      action: { controller.toggleMute() })
                            .transition(controlTransition)
                    case .end:
                        controlCircle("phone.down.fill", label: "End call", endCall: true, size: size,
                                      identifier: "call-end", action: { end(stage) })
                            .transition(controlTransition)
                    case .route, .camera:
                        routeButton(controls?.audioRoute, video: false, size: size, enabled: true)
                            .transition(controlTransition)
                    }
                }
            }
            .frame(height: size)
            .animation(controlAnimation, value: row)
        }
    }

    private func end(_ stage: Stage) {
        switch stage.phase {
        case .starting: callKit.cancelStarting()
        case .calling: Task { await controller.cancelOutgoing() }
        default: Task { await controller.hangup() }
        }
    }

    /// While asking the peer for video, our camera floats in a corner instead
    /// of being inserted into the layout.
    @ViewBuilder
    private func upgradePreview(_ controls: ConnectedState?, size: CGSize, delta: Int) -> some View {
        if let c = controls, c.videoMode == .audio, c.localVideoEnabled {
            let preview = CallLayoutPolicy.previewSize(availableWidth: size.width, availableHeight: size.height)
            CompensatedLocalVideoView(isFrontFacing: c.localCameraIsFrontFacing, delta: delta)
                .opacity(c.localCameraState == .on ? 1 : 0)
                .frame(width: preview.width * 0.8, height: preview.height * 0.8)
                .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo))
                .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo).stroke(UnyriColors.outlineSecondary, lineWidth: 1))
                .padding(.trailing, UnyriSpacing.lg)
                .padding(.top, Self.topBarHeight + UnyriSpacing.sm)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private func failedLayout(_ reason: CallFailureReason, _ snap: Snapshot) -> some View {
        VStack(spacing: UnyriSpacing.lg) {
            Spacer()
            CallRingIndicatorView(phase: snap.ringPhase ?? (reason == .busy ? .busy : .failed),
                                  connectedAt: nil, ringbackStartedAt: nil)
            Text(LocalizedStringKey(reason.textKey))
                .font(reason == .relayUnavailable ? UnyriTypography.body : UnyriTypography.screenTitle)
                .foregroundColor(UnyriColors.primaryText)
                .multilineTextAlignment(.center)
            if reason == .busy {
                Text("The line is busy. Try calling again later.").foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(UnyriSpacing.xxxl)
    }

    // MARK: - Video

    /// Every phase of a video call in one layout: the top bar's room, the
    /// identity (the same large avatar and name as the audio stage) and a
    /// four-slot tray, all at fixed sizes that follow from the width and
    /// height alone. The tray shows only the usable controls, centred, and
    /// re-centres when one is added (B-067, owner 20 Nov). With no live picture (both cameras off, or before any
    /// frame) taps do nothing; with one, a tap hides or shows the chrome.
    private func videoStage(_ stage: Stage, _ snap: Snapshot, size: CGSize, chromeHidden: Bool) -> some View {
        let c = snap.controls
        let chromeHidable = snap.videoLive && stage.inCall
        let count = CallLayoutPolicy.videoControlCount
        let rows = CallLayoutPolicy.controlRows(availableWidth: size.width, controlCount: count)
        let landscape = CallLayoutPolicy.isLandscape(availableWidth: size.width, availableHeight: size.height)
        let control = landscape ? CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height).control : CallLayoutPolicy.controlSize(availableWidth: size.width, controlCount: count)
        let spacing = landscape ? CallLayoutPolicy.landscape(availableWidth: size.width, availableHeight: size.height).controlSpacing : CallLayoutPolicy.controlSpacing(availableWidth: size.width, controlCount: count)
        // Both heights follow from the width alone: nothing is measured, so no
        // second layout pass and no cascade into the preview or the identity.
        let trayHeight = CGFloat(rows) * control + CGFloat(rows - 1) * UnyriSpacing.md
        let controlsHeight = trayHeight + 2 * UnyriSpacing.md + UnyriSpacing.lg
        let header = Self.topBarHeight
        let preview = previewSize(size)
        let remoteVisible = c.map(remoteVideoVisible) ?? false
        let top = header + 8
        let identityHeight = max(0, size.height - top - controlsHeight - 8)
        let identityCenter = UnitPoint(x: 0.5, y: size.height > 0 ? (top + identityHeight / 2) / size.height : 0.5)
        return ZStack {
            // This touch plane belongs only to the remote/background area.
            // Preview gestures and controls above it retain their own touches.
            Color.clear.contentShape(Rectangle())
                .gesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1))
                    .onEnded { value in
                        guard stage.inCall else { return }
                        switch value {
                        case .first:
                            // Live, not the render's copy: a kept gesture must not act on old state.
                            if controller.audioControls.map({ $0.call.callId == stage.call.callId && $0.remoteVideoState == .on }) == true {
                                toggleRemoteFit()
                            }
                        case .second:
                            guard liveVideoNow(stage.call.callId) else { return }
                            videoChromeHidden.toggle()
                        }
                    })
                .accessibilityIdentifier("call-video-background")
                .accessibilityLabel(UnyriL10n.string("Call video"))
                .accessibilityAction(named: Text(UnyriL10n.string("Fit or fill remote video"))) { toggleRemoteFit() }
                .accessibilityActions {
                    if chromeHidable {
                        Button(UnyriL10n.string("Show or hide call controls")) {
                            if liveVideoNow(stage.call.callId) { videoChromeHidden.toggle() }
                        }
                    }
                }
                .accessibilityHidden(!snap.videoLive)
            // Unmounted while the peer's picture covers it, which also stops
            // the halo's polling.
            ZStack {
                if !remoteVisible {
                    videoIdentity(stage, snap, height: identityHeight, size: size, hidden: chromeHidden)
                        .frame(width: size.width, height: identityHeight)
                        .position(x: size.width / 2, y: top + identityHeight / 2)
                        .opacity(chromeHidden ? 0 : 1)
                        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: chromeHidden)
                        .allowsHitTesting(false)
                        .accessibilityHidden(chromeHidden)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96, anchor: identityCenter)))
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : .easeInOut(duration: 0.28), value: remoteVisible)
            VStack(spacing: 0) {
                Color.clear.frame(height: header)
                Spacer(minLength: 0)
                videoControls(stage, c, rows: rows, size: control, spacing: spacing,
                              trayHeight: trayHeight, width: size.width)
            }
            // Retain controls and renderer identity; visibility never changes
            // the video viewport or starts/stops a media track.
            .opacity(chromeHidden ? 0 : 1)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: chromeHidden)
            .allowsHitTesting(!chromeHidden)
            .accessibilityHidden(chromeHidden)
            CallNoticeToast(notice: c?.visibleNotice)
                .padding(.top, header + 8)
                .frame(maxHeight: .infinity, alignment: .top)
                .allowsHitTesting(false)
            if let c, c.localVideoEnabled {
                DraggableCallPreview(width: preview.width, height: preview.height,
                    bottomClearance: chromeHidden ? 12 : controlsHeight + 12,
                    topClearance: chromeHidden ? 8 : header + 8,
                    isFrontFacing: c.localCameraIsFrontFacing,
                    flip: snap.canFlip ? flipCamera(stage.call.callId) : nil,
                    torch: snap.canTorch ? (snap.torchOn, toggleTorch(stage.call.callId)) : nil,
                    chromeHidden: chromeHidden)
                    .environment(\.callSelfViewDelta, snap.selfViewDelta)
                    .opacity(c.localCameraState == .on ? 1 : 0)
                    .allowsHitTesting(c.localCameraState == .on)
                    .accessibilityHidden(c.localCameraState != .on)
            }
        }
    }

    /// The audio stage's identity: a large avatar with the halo over the name
    /// (two lines reserved), so ringing and connecting of a video call look
    /// like an audio call until a picture is live. The halo rests while the
    /// chrome is hidden.
    @ViewBuilder
    private func videoIdentity(_ stage: Stage, _ snap: Snapshot, height: CGFloat, size: CGSize, hidden: Bool) -> some View {
        if CallLayoutPolicy.isLandscape(availableWidth: size.width, availableHeight: size.height) {
            landscapeIdentity(stage, snap, size: size, hidden: hidden)
        } else {
            portraitVideoIdentity(stage, snap, height: height, size: size, hidden: hidden)
        }
    }

    private func portraitVideoIdentity(_ stage: Stage, _ snap: Snapshot, height: CGFloat, size: CGSize, hidden: Bool) -> some View {
        let nameHeight: CGFloat = 68 // two lines of the 28 pt title
        let pathHeight = Self.showsCallPath(stage, snap) ? Self.callPathHeight : 0
        let avatarSize = min(CallLayoutPolicy.avatarSize(availableHeight: size.height, isVideo: false),
                             max(32, height - nameHeight - UnyriSpacing.xl - 16 - pathHeight))
        return VStack(spacing: UnyriSpacing.xl) {
            avatar(stage.call, size: avatarSize, halo: hidden ? .off : haloMode(stage.phase))
            VStack(spacing: 12) {
                nameBlock(stage.call.visibleRemoteName)
                callPath(stage, snap, hidden: hidden)
            }
        }
    }

    /// A picture is live in this call right now: the peer's (a kept frame
    /// included) or our own camera. Read live, for gestures kept by SwiftUI.
    private func liveVideoNow(_ callID: String) -> Bool {
        guard let c = controller.audioControls, c.call.callId == callID, c.videoMode == .video else { return false }
        return c.remoteVideoState == .on || (staleRemoteFrame && c.remoteVideoEnabled)
            || (c.localVideoEnabled && c.localCameraState == .on)
    }

    /// Camera · Mute · End · Output: always four slots, from ringing on, and
    /// the row count follows the width (two and two when it wraps); the flip
    /// sits on the self-view. Camera changes persist through media setup;
    /// ringing in, Decline and Accept take the same tray
    /// height, so answering moves nothing above it.
    private func videoControls(_ stage: Stage, _ c: ConnectedState?, rows: Int, size: CGFloat, spacing: CGFloat,
                               trayHeight: CGFloat, width: CGFloat) -> some View {
        Group {
            if stage.phase == .incoming {
                HStack(spacing: UnyriSpacing.xxxl) {
                    controlCircle("phone.down.fill", label: "Decline", endCall: true, size: size, action: { Task { await controller.rejectIncoming() } })
                    controlCircle("phone.fill", label: "Accept", color: UnyriColors.success, active: true, size: size, action: { callKit.answerIncoming() })
                }
            } else if rows == 1 {
                HStack(spacing: spacing) {
                    videoMediaControls(stage, c, size: size)
                    videoCallControls(stage, c, size: size)
                }
                .animation(controlAnimation, value: videoRow(stage, c))
            } else {
                VStack(spacing: UnyriSpacing.md) {
                    HStack(spacing: spacing) { videoMediaControls(stage, c, size: size) }.frame(height: size)
                    HStack(spacing: spacing) { videoCallControls(stage, c, size: size) }.frame(height: size)
                }
                .animation(controlAnimation, value: videoRow(stage, c))
            }
        }
        .frame(height: trayHeight)
        .padding(.horizontal, CallLayoutPolicy.trayPadding(availableWidth: width))
        .padding(.vertical, UnyriSpacing.md)
        .frame(maxWidth: CallLayoutPolicy.trayWidth(availableWidth: width))
        .padding(.horizontal, CallLayoutPolicy.trayMargin(availableWidth: width))
        .padding(.bottom, UnyriSpacing.lg)
    }

    /// Camera and microphone, white only when off (FaceTime).
    @ViewBuilder
    private func videoMediaControls(_ stage: Stage, _ c: ConnectedState?, size: CGFloat) -> some View {
        // Once the call exists these controls work even before media connects.
        let cameraOn = c?.localVideoEnabled ?? !stage.isEnded
        let muted = c?.isMuted ?? false
        ForEach(CallControlRowPolicy.videoMedia(live: c != nil, ended: stage.isEnded), id: \.self) { control in
            if control == .camera {
                controlCircle(cameraOn ? "video.fill" : "video.slash.fill", label: cameraOn ? "Turn camera off" : "Turn camera on",
                              active: !cameraOn && !stage.isEnded, size: size, identifier: "call-camera",
                              action: { controller.toggleVideo() })
                    .transition(controlTransition)
            } else {
                controlCircle(muted ? "mic.slash.fill" : "mic.fill", label: muted ? "Unmute microphone" : "Mute microphone",
                              active: muted, size: size, identifier: "call-mute",
                              action: { controller.toggleMute() })
                    .transition(controlTransition)
            }
        }
    }

    /// The visible video controls, for the re-centring animation (B-067).
    private func videoRow(_ stage: Stage, _ c: ConnectedState?) -> [CallControlRowPolicy.Control] {
        CallControlRowPolicy.videoMedia(live: c != nil, ended: stage.isEnded) + CallControlRowPolicy.videoCall(live: c != nil, ended: stage.isEnded)
    }

    private var controlTransition: AnyTransition { reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.8)) }
    private var controlAnimation: Animation { reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.32, dampingFraction: 0.86) }

    /// End and the output, white only on the receiver.
    @ViewBuilder
    private func videoCallControls(_ stage: Stage, _ c: ConnectedState?, size: CGFloat) -> some View {
        ForEach(CallControlRowPolicy.videoCall(live: c != nil, ended: stage.isEnded), id: \.self) { control in
            if control == .end {
                controlCircle("phone.down.fill", label: "End call", endCall: true, size: size,
                              identifier: "call-end", action: { end(stage) })
                    .transition(controlTransition)
            } else {
                routeButton(c?.audioRoute, video: true, size: size, enabled: true)
                    .transition(controlTransition)
            }
        }
    }

    /// The self-view's flip. Reads live state on tap: a departing transition
    /// must never flip a stopped or replaced camera.
    private func flipCamera(_ callID: String) -> () -> Void {
        return {
            guard localCameraReady(for: callID) else { return }
            controller.switchCamera()
        }
    }

    private func toggleTorch(_ callID: String) -> () -> Void {
        return {
            guard localCameraReady(for: callID) else { return }
            controller.toggleTorch()
        }
    }

    /// The camera runs (from connecting on) and shows frames for this call.
    private func localCameraReady(for callID: String) -> Bool {
        guard let current = controller.audioControls, current.call.callId == callID,
              current.videoMode == .video else { return false }
        return current.localVideoEnabled && current.localCameraState == .on
    }

    private func previewSize(_ size: CGSize) -> (width: CGFloat, height: CGFloat) {
        let preview = CallLayoutPolicy.previewSize(availableWidth: size.width, availableHeight: size.height)
        return (preview.width, preview.height)
    }

    private func toggleRemoteFit() {
        remoteFraming.toggle(stage: size)
    }

    // MARK: - Shared controls

    /// The output control; routes are read from the system only on tap.
    /// White only where the sound is unusual for the call type
    /// (`CallAudioOutputPolicy`); nil `route` shows the call type's own.
    @ViewBuilder
    private func routeButton(_ route: AudioRouteKind?, video: Bool, size: CGFloat, enabled: Bool) -> some View {
        let controller = self.controller
        CallControlSlot(size: size, enabled: enabled) {
            CallAudioRouteButton(route: route, video: video, size: size, enabled: true,
                                    routes: { controller.availableAudioRoutes() },
                                    current: { controller.currentAudioRoute() },
                                    select: { controller.setAudioRoute($0) })
                .frame(width: size, height: size)
        }
    }

    /// One avatar with its halo. Its URL comes from a per-user cache, so a
    /// recreated avatar keeps the photo instead of flashing initials.
    private func avatar(_ call: CallInfo, size: CGFloat, halo: CallVoiceHalo.Mode) -> some View {
        CallPeerAvatar(userId: call.remoteUserId, name: call.visibleRemoteName, size: size)
            .background {
                CallVoiceHalo(mode: halo, controller: controller)
                    .frame(width: size * 1.9, height: size * 1.9)
            }
    }

    /// Reusable UNYRI call control: the dark glass circle by default, white
    /// (`active`) only for the non-default state (FaceTime: microphone muted,
    /// camera off), `color` for Accept and danger for end call; accessibility
    /// label included and the glyph, not only the colour, says the state.
    @ViewBuilder
    private func controlCircle(
        _ symbol: String,
        label: String? = nil,
        color: Color = UnyriColors.accent,
        active: Bool = false,
        endCall: Bool = false,
        size: CGFloat = 60,
        enabled: Bool = true,
        identifier: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        CallControlSlot(size: size, enabled: enabled) {
            Button(action: action) {
                Image(systemName: symbol)
                    .font(.system(size: size * 0.38, weight: .medium))
                    .foregroundColor(active && !endCall ? UnyriColors.callBackground : .white)
                    .frame(width: size, height: size)
                    .background(
                        Circle().fill(
                            endCall ? UnyriColors.danger
                                : active ? (color == UnyriColors.accent ? .white : color)
                                : Color.white.opacity(0.18)
                        )
                    )
                    .overlay(
                        Circle().stroke(
                            endCall ? UnyriColors.danger.opacity(0.4)
                                : active ? UnyriColors.outlineActive
                                : UnyriColors.outlineSecondary,
                            lineWidth: 1
                        )
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(LocalizedStringKey(label ?? symbol))
            .accessibilityIdentifier(identifier ?? (endCall ? "call-end" : symbol))
        }
    }
}

/// A disabled control leaves a clear, non-interactive square in its original
/// slot. HStack geometry never depends on which actions are currently usable.
struct CallControlSlot<Content: View>: View {
    let size: CGFloat
    let enabled: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            Color.clear
            if enabled { content() }
        }
        .frame(width: size, height: size)
        .allowsHitTesting(enabled)
        .accessibilityHidden(!enabled)
    }
}

/// Our camera in a corner of the video call; it moves independently of the
/// renderer and never covers the call controls. The flip sits on its corner
/// (FaceTime) only while the camera runs and another camera exists, the
/// torch beside it only while a camera with a torch runs; both leave with the
/// chrome.
private struct DraggableCallPreview: View {
    let width: CGFloat
    let height: CGFloat
    let bottomClearance: CGFloat
    let topClearance: CGFloat
    let isFrontFacing: Bool
    /// Nil: no flip button.
    let flip: (() -> Void)?
    /// Nil: no torch button.
    let torch: (isOn: Bool, toggle: () -> Void)?
    let chromeHidden: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.callSelfViewDelta) private var selfViewDelta

    /// The torch and the flip side by side: two 44 pt targets overlapping by
    /// 8 pt, so each 30 pt circle sits 7 pt inside its edge of the tile.
    private static let pairWidth: CGFloat = 80

    var body: some View {
        // A pinched-small tile grows to hold both buttons instead of letting
        // the torch hang past its edge.
        FloatingCallVideo(width: width, height: height, bottomClearance: bottomClearance, topClearance: topClearance,
                          label: "Your camera preview", hint: UnyriL10n.string("Drag to move. Pinch to resize"),
                          minimumWidth: flip != nil && torch != nil ? Self.pairWidth : 64) {
            CompensatedLocalVideoView(isFrontFacing: isFrontFacing, delta: selfViewDelta)
        } accessory: {
            if flip != nil || torch != nil {
                // 44 pt targets overlap by 8 pt: the 30 pt circles keep a 6 pt gap.
                HStack(spacing: -8) {
                    if let torch { CallTorchButton(isOn: torch.isOn, action: torch.toggle) }
                    if let flip { CallFlipCameraButton(action: flip) }
                }
                    .opacity(chromeHidden ? 0 : 1)
                    .allowsHitTesting(!chromeHidden)
                    .accessibilityHidden(chromeHidden)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: chromeHidden)
            }
        }
    }
}

/// The self-view tile, dragged and pinched inside the legal area. The drag
/// is measured in the stationary container, never in the moving video; each
/// new position is kept, and cancelling a gesture never resets its
/// translation. `accessory` (the flip, the torch) sits on the tile's
/// bottom-trailing corner above its gestures, as its own accessibility
/// element; the tile is never narrower than `minimumWidth` while space allows.
struct FloatingCallVideo<Content: View, Accessory: View>: View {
    let width: CGFloat
    let height: CGFloat
    let bottomClearance: CGFloat
    let topClearance: CGFloat
    let label: LocalizedStringKey
    let hint: String
    let minimumWidth: CGFloat
    @ViewBuilder let content: () -> Content
    @ViewBuilder let accessory: () -> Accessory
    @Namespace private var dragSpace
    @State private var dragOrigin: CGPoint?
    @GestureState private var dragging = false
    @GestureState private var magnifying = false
    @State private var previewScale: CGFloat = 1
    @State private var scaleOrigin: CGFloat?
    @State private var fixedOrigin: CGPoint?
    @State private var moved = false

    var body: some View {
        GeometryReader { geometry in
            let margin: CGFloat = 12
            let availableWidth = geometry.size.width - margin * 2
            let availableHeight = geometry.size.height - bottomClearance - topClearance - margin
            let maximumScale = max(0.1, min(240 / width, min(availableWidth / width, availableHeight / height)))
            let minimumScale = min(maximumScale, max(0.6, minimumWidth / width))
            let scale = min(maximumScale, max(minimumScale, previewScale))
            let fitted = CallLayoutPolicy.fittedPreviewSize(width: width * scale, height: height * scale,
                availableWidth: availableWidth, availableHeight: availableHeight)
            // A temporarily tiny viewport must not distort or recreate the renderer.
            let w = fitted?.width ?? width
            let h = fitted?.height ?? height
            let rangeX = max(0, geometry.size.width - w - margin * 2)
            let rangeY = max(0, geometry.size.height - h - bottomClearance - topClearance - margin)
            let bounds = CGRect(x: margin, y: topClearance, width: rangeX, height: rangeY)
            // A point in stationary container coordinates: enlarging the legal
            // area when the chrome hides cannot move the tile.
            let x = moved ? min(bounds.maxX, max(bounds.minX, fixedOrigin?.x ?? bounds.maxX)) : bounds.maxX
            let y = min(bounds.maxY, max(bounds.minY, fixedOrigin?.y ?? bounds.minY))
            let drag = DragGesture(minimumDistance: 4, coordinateSpace: .named(dragSpace))
                .updating($dragging) { _, state, _ in state = true }
                .onChanged { value in
                    moved = true
                    let origin = dragOrigin ?? CGPoint(x: x, y: y)
                    dragOrigin = origin
                    fixedOrigin = clamped(CGPoint(x: origin.x + value.translation.width,
                                                  y: origin.y + value.translation.height), to: bounds)
                }
                .onEnded { value in
                    if let origin = dragOrigin {
                        fixedOrigin = clamped(CGPoint(x: origin.x + value.translation.width,
                                                      y: origin.y + value.translation.height), to: bounds)
                    }
                    dragOrigin = nil
                }
            let pinch = MagnificationGesture()
                .updating($magnifying) { _, state, _ in state = true }
                .onChanged { value in
                    moved = true
                    let origin = scaleOrigin ?? scale
                    scaleOrigin = origin
                    previewScale = min(maximumScale, max(minimumScale, origin * value))
                }
                .onEnded { _ in scaleOrigin = nil }
            content()
                .allowsHitTesting(false)
                .frame(width: w, height: h)
                .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo))
                .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo).stroke(.white.opacity(0.5), lineWidth: 1))
                .overlay { Color.clear.contentShape(Rectangle()).gesture(drag.simultaneously(with: pinch)) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
                .accessibilityHint(hint)
                .overlay(alignment: .bottomTrailing) { accessory() }
                .onAppear { if fixedOrigin == nil { fixedOrigin = CGPoint(x: x, y: y) } }
                .onChange(of: bounds) { next in
                    if let origin = fixedOrigin {
                        fixedOrigin = clamped(origin, to: next)
                        previewScale = scale
                    }
                }
                .offset(x: fitted == nil ? -20_000 : x, y: y)
                .opacity(fitted == nil ? 0 : 1)
                .allowsHitTesting(fitted != nil)
                .accessibilityHidden(fitted == nil)
                // The tile never moves with a transaction; the accessory's own
                // fade with the chrome still runs inside.
                .transaction { $0.animation = nil }
        }
        .coordinateSpace(name: dragSpace)
        .onChange(of: dragging) { active in if !active { dragOrigin = nil } }
        .onChange(of: magnifying) { active in if !active { scaleOrigin = nil } }
    }

    private func clamped(_ point: CGPoint, to bounds: CGRect) -> CGPoint {
        CGPoint(x: min(bounds.maxX, max(bounds.minX, point.x)),
                y: min(bounds.maxY, max(bounds.minY, point.y)))
    }
}

// MARK: - Audio output

/// The output button's look (owner, 12 Nov). Headphones, AirPods, Bluetooth
/// and wired are normal: the button stays dark and its icon shows the device.
/// White only where the sound is unusual for the call type: the speaker in an
/// audio call, the receiver in a video call. Without a receiver (iPad) the
/// speaker is the only built-in output and stays normal.
enum CallAudioOutputPolicy {
    static func highlighted(_ route: AudioRouteKind, video: Bool, hasReceiver: Bool) -> Bool {
        switch route {
        case .speaker: return !video && hasReceiver
        case .earpiece: return video
        case .bluetooth, .wiredHeadset: return false
        }
    }

    /// The route shown before the engine reports one: the call type's own.
    static func assumed(video: Bool, hasReceiver: Bool) -> AudioRouteKind {
        video || !hasReceiver ? .speaker : .earpiece
    }
}

// MARK: - Mini call window

/// Where the in-app call window rests: a corner, or tucked at that corner's
/// side edge behind a tab, in one of three sizes.
struct CallMiniWindowPlacement: Equatable {
    enum Corner: Equatable {
        case topLeading, topTrailing, bottomLeading, bottomTrailing

        var leading: Bool { self == .topLeading || self == .bottomLeading }
        var top: Bool { self == .topLeading || self == .topTrailing }

        static func at(leading: Bool, top: Bool) -> Corner {
            top ? (leading ? .topLeading : .topTrailing) : (leading ? .bottomLeading : .bottomTrailing)
        }
    }

    enum Size: Int, CaseIterable { case small, medium, large }

    var corner = Corner.bottomTrailing
    var size = Size.medium
    /// Tucked at the corner's side edge: only the tab shows.
    var tucked = false
    /// The tab's place in the free height, 0 at the top to 1 at the bottom.
    var tabFraction: CGFloat = 1
}

/// The mini window's geometry, free of SwiftUI: the corners, the throw, the
/// tuck and the three sizes. Container coordinates, top-left origins.
enum CallMiniWindowPolicy {
    typealias Corner = CallMiniWindowPlacement.Corner
    typealias Size = CallMiniWindowPlacement.Size

    static let margin: CGFloat = 12
    /// Below the call bar (56 pt) and a navigation bar.
    static let topClearance: CGFloat = 112
    /// Above a chat's composer.
    static let bottomClearance: CGFloat = 100
    /// Height over width, as the 120 × 168 window was.
    static let aspect: CGFloat = 1.4
    static let minimumWidth: CGFloat = 64
    /// Seconds of the release velocity a throw carries.
    static let throwProjection: CGFloat = 0.2
    /// The tab's capsule; `tabInset` of it sits past the screen edge.
    static let tabSize = CGSize(width: 30, height: 64)
    static let tabInset: CGFloat = 10

    static func width(_ size: Size) -> CGFloat {
        switch size {
        case .small: return 88
        case .medium: return 120
        case .large: return 168
        }
    }

    /// The smallest window's height.
    static var minimumHeight: CGFloat { (minimumWidth * aspect).rounded() }

    /// The free rectangle the window rests in: below the call and navigation
    /// bars, above a chat's composer and the keyboard. With a keyboard up in
    /// landscape both clearances do not fit: the top one gives way first
    /// (down to `margin`), then the composer's, so the smallest window still
    /// sits above the keyboard.
    static func area(container: CGSize, keyboardOverlap: CGFloat) -> CGRect {
        let free = container.height - max(0, keyboardOverlap)
        var top = topClearance
        var bottom = free - bottomClearance
        if bottom - top < minimumHeight { top = max(margin, bottom - minimumHeight) }
        if bottom - top < minimumHeight { bottom = min(free - margin, top + minimumHeight) }
        return CGRect(x: margin, y: top,
                      width: max(0, container.width - 2 * margin),
                      height: max(0, bottom - top))
    }

    /// A step's size: at most 60 % of the free width, shrunk to the free
    /// height (a keyboard in landscape), never below `minimumWidth`.
    static func windowSize(_ size: Size, area: CGRect) -> CGSize {
        let w = max(minimumWidth, min(width(size), area.width * 0.6, area.height / aspect))
        return CGSize(width: w.rounded(), height: (w * aspect).rounded())
    }

    /// Where the window's top may be. Without room it hangs from the area's
    /// bottom: staying above the keyboard comes first.
    static func verticalRange(window: CGSize, area: CGRect) -> ClosedRange<CGFloat> {
        let lowest = area.maxY - window.height
        return min(area.minY, lowest)...lowest
    }

    static func origin(_ corner: Corner, window: CGSize, area: CGRect) -> CGPoint {
        let range = verticalRange(window: window, area: area)
        return CGPoint(x: corner.leading ? area.minX : max(area.minX, area.maxX - window.width),
                       y: corner.top ? range.lowerBound : range.upperBound)
    }

    /// Off the screen at the tucked side, level with the tab.
    static func tuckedOrigin(leading: Bool, fraction: CGFloat, window: CGSize, container: CGSize,
                             area: CGRect) -> CGPoint {
        let range = verticalRange(window: window, area: area)
        return CGPoint(x: leading ? -window.width - 16 : container.width + 16,
                       y: range.lowerBound + (range.upperBound - range.lowerBound) * min(1, max(0, fraction)))
    }

    /// While dragged the window follows the finger inside the free height and
    /// up to three quarters past a side edge, so a slow push can tuck it.
    static func dragged(_ origin: CGPoint, window: CGSize, container: CGSize, area: CGRect) -> CGPoint {
        let range = verticalRange(window: window, area: area)
        return CGPoint(x: min(container.width - window.width * 0.25, max(-window.width * 0.75, origin.x)),
                       y: min(range.upperBound, max(range.lowerBound, origin.y)))
    }

    enum Rest: Equatable {
        case corner(Corner)
        case tucked(leading: Bool, fraction: CGFloat)
    }

    /// Where a released window goes. Its centre is carried along the release
    /// velocity; a fifth of its width past a side edge (a slow push to the
    /// drag's limit gets there) it tucks, anywhere else it lands in the
    /// corner of that quadrant.
    static func rest(origin: CGPoint, velocity: CGVector, window: CGSize, container: CGSize, area: CGRect) -> Rest {
        let center = CGPoint(x: origin.x + window.width / 2 + velocity.dx * throwProjection,
                             y: origin.y + window.height / 2 + velocity.dy * throwProjection)
        let past = window.width / 5
        if center.x < -past || center.x > container.width + past {
            return .tucked(leading: center.x < 0, fraction: fraction(centerY: center.y, window: window, area: area))
        }
        return .corner(.at(leading: center.x < container.width / 2, top: center.y < area.midY))
    }

    /// A window centre's height as a fraction of the free range.
    static func fraction(centerY: CGFloat, window: CGSize, area: CGRect) -> CGFloat {
        let range = area.height - window.height
        guard range > 0 else { return 0 }
        return min(1, max(0, (centerY - window.height / 2 - area.minY) / range))
    }

    /// The tab's capsule, level with the window's middle.
    static func tabFrame(leading: Bool, fraction: CGFloat, window: CGSize, container: CGSize, area: CGRect) -> CGRect {
        let origin = tuckedOrigin(leading: leading, fraction: fraction, window: window, container: container, area: area)
        return CGRect(x: leading ? -tabInset : container.width - tabSize.width + tabInset,
                      y: origin.y + window.height / 2 - tabSize.height / 2,
                      width: tabSize.width, height: tabSize.height)
    }

    /// The tab's touch target: 44 pt wide, 12 pt above and below the capsule.
    static func tabTarget(_ tab: CGRect) -> CGRect {
        CGRect(x: tab.midX - 22, y: tab.minY - 12, width: 44, height: tab.height + 24)
    }

    /// Back from the tab: the corner on its side nearer the tab.
    static func untucked(leading: Bool, fraction: CGFloat) -> Corner {
        .at(leading: leading, top: fraction < 0.5)
    }

    /// After a pinch: the step whose size in `area` is nearest the pinched
    /// width. Steps that fit to the same size are one, the current step
    /// first, so a pinch that cannot change the window keeps its step.
    static func size(pinchedWidth: CGFloat, current: Size, area: CGRect) -> Size {
        let steps = [current] + Size.allCases.filter { $0 != current }
        return steps.min {
            abs(windowSize($0, area: area).width - pinchedWidth) < abs(windowSize($1, area: area).width - pinchedWidth)
        } ?? current
    }

    /// Double tap: small, medium, large, then small again, skipping a step
    /// that fits to the size the window has (a short area clamps the larger
    /// ones); the current step when every step fits the same.
    static func next(_ size: Size, area: CGRect) -> Size {
        let shown = windowSize(size, area: area)
        for offset in 1..<Size.allCases.count {
            let step = Size(rawValue: (size.rawValue + offset) % Size.allCases.count) ?? .medium
            if windowSize(step, area: area) != shown { return step }
        }
        return size
    }

    /// The release velocity in points per second over the last 100 ms of
    /// touches; zero after a pause or from a single touch.
    static func velocity(_ samples: [(point: CGPoint, time: TimeInterval)]) -> CGVector {
        guard let last = samples.last,
              let first = samples.first(where: { last.time - $0.time <= 0.1 }),
              last.time - first.time > 0.008 else { return .zero }
        let dt = CGFloat(last.time - first.time)
        return CGVector(dx: (last.point.x - first.point.x) / dt, dy: (last.point.y - first.point.y) / dt)
    }

    /// The landing spring's initial velocity: the release speed toward the
    /// target relative to the distance left (SwiftUI's unit), capped.
    static func springVelocity(_ velocity: CGVector, from: CGPoint, to: CGPoint) -> CGFloat {
        let dx = to.x - from.x, dy = to.y - from.y
        let distance = (dx * dx + dy * dy).squareRoot()
        guard distance > 1 else { return 0 }
        let toward = (velocity.dx * dx + velocity.dy * dy) / distance
        return min(12, max(0, toward / distance))
    }
}

/// The drag's recent touches and phase, kept out of view state so recording
/// them re-renders nothing.
final class CallMiniWindowDragTracker {
    private(set) var samples: [(point: CGPoint, time: TimeInterval)] = []
    var dragging = false
    private(set) var pinching = false
    /// This touch sequence included a pinch: it only resizes, never moves or
    /// throws the window.
    private(set) var pinched = false
    private(set) var pinchEndedAt: Date?

    /// A drag recognized just after the pinch ends still belongs to that
    /// touch sequence: the remaining finger can jump past the drag threshold.
    func begin(at date: Date) {
        samples.removeAll()
        dragging = true
        if !pinching && (pinchEndedAt.map { date.timeIntervalSince($0) > 0.3 } ?? true) {
            pinched = false
        }
    }

    func beginPinch() {
        pinching = true
        pinched = true
        pinchEndedAt = nil
    }

    func endPinch(at date: Date) {
        pinching = false
        pinchEndedAt = date
    }

    func add(_ point: CGPoint, at date: Date) {
        let time = date.timeIntervalSinceReferenceDate
        samples.append((point, time))
        samples.removeAll { time - $0.time > 0.15 }
    }
}

/// The mini window's placement for the call on screen, kept across minimizes
/// and the window's re-creation; a new call starts from the default. A
/// reference: writes never re-render the root.
final class CallMiniWindowMemory {
    private var callId: String?
    private var stored = CallMiniWindowPlacement()

    func placement(for callId: String) -> CallMiniWindowPlacement {
        callId == self.callId ? stored : CallMiniWindowPlacement()
    }

    func store(_ placement: CallMiniWindowPlacement, for callId: String) {
        self.callId = callId
        stored = placement
    }
}

/// Where the mini window takes touches, in window coordinates: the window
/// (its × reaches past it) or, tucked, the tab. The root drawer's pan never
/// starts there; a tab tucked at the leading edge sits inside the drawer's
/// opening strip. A reference: writes never re-render anything.
final class CallMiniWindowTouchAreas {
    private(set) var frame = CGRect.null
    /// The window that wrote `frame`; a departing one never clears its successor's.
    private weak var owner: AnyObject?

    func update(_ next: CGRect, owner: AnyObject) {
        self.owner = owner
        frame = next
    }

    func clear(owner: AnyObject) {
        guard self.owner === owner else { return }
        self.owner = nil
        frame = .null
    }

    /// False once the writing window is gone, even without its clear.
    func contains(_ point: CGPoint) -> Bool {
        owner != nil && frame.contains(point)
    }
}

/// The in-app call window after minimize, Telegram-like. Dragged, it lands in
/// the nearest corner; a throw carries it to the corner it was thrown toward,
/// and thrown at a side edge it tucks away behind a small tab that a tap or a
/// drag brings back. A pinch or a double tap switches between three sizes; ×
/// hides it while the call goes on (the call bar stays to return); a tap
/// opens the call with the expand morph. It shows my muted microphone, the
/// peer's avatar and name while their camera is off, dims with
/// "Reconnecting…" during a shown drop, and stays above the keyboard.
/// One-shot springs only (a short ease in Low Power Mode, fades under Reduce
/// Motion); nothing animates at rest. Tucked, the renderer stays mounted off
/// screen, as the self-view keeps it for a tiny viewport, but draws nothing.
struct CallMiniWindow: View {
    let call: ConnectedState
    let quality: CallQualityMonitor
    let memory: CallMiniWindowMemory
    /// Where it takes touches, for the root drawer's pan.
    let touches: CallMiniWindowTouchAreas
    /// Opens the call from the window's frame in global coordinates, or from the bar (nil).
    let onReturn: (CGRect?) -> Void
    let onHide: () -> Void
    let toggleMute: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var space
    @State private var placement: CallMiniWindowPlacement
    @State private var drag: CGSize = .zero
    @State private var pinch: CGFloat = 1
    /// Reduce Motion: out at the old place before it appears at the new one.
    @State private var faded = false
    @State private var fade: Task<Void, Never>?
    @State private var reconnecting = false
    /// The keyboard's top in screen coordinates; infinity while hidden.
    @State private var keyboardTop = CGFloat.infinity
    @State private var tracker = CallMiniWindowDragTracker()
    @GestureState private var dragging = false
    @GestureState private var pinching = false

    init(call: ConnectedState, quality: CallQualityMonitor, memory: CallMiniWindowMemory,
         touches: CallMiniWindowTouchAreas, onReturn: @escaping (CGRect?) -> Void, onHide: @escaping () -> Void,
         toggleMute: @escaping () -> Void) {
        self.call = call
        self.quality = quality
        self.memory = memory
        self.touches = touches
        self.onReturn = onReturn
        self.onHide = onHide
        self.toggleMute = toggleMute
        _placement = State(initialValue: memory.placement(for: call.call.callId))
    }

    private struct Layout {
        let container: CGSize
        let area: CGRect
        let window: CGSize
        /// Where the window rests: its corner, or off screen when tucked.
        let rest: CGPoint
        /// Where it is drawn: `rest` plus the live drag.
        let origin: CGPoint
        /// The container's origin in global coordinates.
        let global: CGPoint
    }

    var body: some View {
        GeometryReader { geometry in
            let container = geometry.size
            let global = geometry.frame(in: .global)
            let area = CallMiniWindowPolicy.area(container: container, keyboardOverlap: max(0, global.maxY - keyboardTop))
            let size = CallMiniWindowPolicy.windowSize(placement.size, area: area)
            let rest = placement.tucked
                ? CallMiniWindowPolicy.tuckedOrigin(leading: placement.corner.leading, fraction: placement.tabFraction,
                                                    window: size, container: container, area: area)
                : CallMiniWindowPolicy.origin(placement.corner, window: size, area: area)
            let origin = placement.tucked ? rest
                : CallMiniWindowPolicy.dragged(CGPoint(x: rest.x + drag.width, y: rest.y + drag.height),
                                               window: size, container: container, area: area)
            let layout = Layout(container: container, area: area, window: size, rest: rest, origin: origin,
                                global: global.origin)
            let _ = touches.update(touchFrame(layout), owner: tracker)
            ZStack(alignment: .topLeading) {
                window(layout)
                if placement.tucked {
                    tab(layout).transition(.opacity)
                }
            }
            .onChange(of: dragging) { active in
                // A cancelled drag lands as a release without speed; one
                // that was part of a pinch never moved the window.
                guard !active, tracker.dragging else { return }
                tracker.dragging = false
                guard !tracker.pinched else { return }
                settle(from: origin, velocity: .zero, layout)
            }
            .onChange(of: pinching) { active in
                guard !active, tracker.pinching else { return }
                tracker.endPinch(at: Date())
                resize(to: CallMiniWindowPolicy.size(pinchedWidth: size.width * pinch, current: placement.size, area: area),
                       area: area)
            }
        }
        .coordinateSpace(name: space)
        .onReceive(quality.$indicator.map(\.isReconnecting).removeDuplicates()) { value in
            if reconnecting != value { reconnecting = value }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { followKeyboard($0) }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { followKeyboard($0, hidden: true) }
        .onDisappear {
            fade?.cancel()
            touches.clear(owner: tracker)
        }
    }

    /// Where it takes touches, in global coordinates: the window with the
    /// reach of its × (6 pt past the corner), or the tab's target while tucked.
    private func touchFrame(_ layout: Layout) -> CGRect {
        let local = placement.tucked
            ? CallMiniWindowPolicy.tabTarget(CallMiniWindowPolicy.tabFrame(
                leading: placement.corner.leading, fraction: placement.tabFraction, window: layout.window,
                container: layout.container, area: layout.area))
            : CGRect(origin: layout.origin, size: layout.window).insetBy(dx: -8, dy: -8)
        return local.offsetBy(dx: layout.global.x, dy: layout.global.y)
    }

    private func window(_ layout: Layout) -> some View {
        let leading = placement.corner.leading
        let tucked = placement.tucked
        let hidden = faded || (tucked && reduceMotion)
        // The badge and × sit in the corners away from the screen edge.
        let badgeCorner: Alignment = leading ? .bottomTrailing : .bottomLeading
        let closeCorner: Alignment = leading ? .topTrailing : .topLeading
        let globalFrame = CGRect(x: layout.global.x + layout.origin.x, y: layout.global.y + layout.origin.y,
                                 width: layout.window.width, height: layout.window.height)
        let shape = RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo, style: .continuous)
        return picture(width: layout.window.width, drawing: !tucked)
            .frame(width: layout.window.width, height: layout.window.height)
            .clipShape(shape)
            .overlay(shape.stroke(Color.white.opacity(0.5), lineWidth: 1))
            .overlay(alignment: badgeCorner) { muteBadge }
            .overlay { Color.clear.contentShape(shape) }
            .gesture(moveAndPinch(layout))
            .gesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { value in
                switch value {
                case .first: resize(to: CallMiniWindowPolicy.next(placement.size, area: layout.area), area: layout.area)
                case .second: onReturn(globalFrame)
                }
            })
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Return to call with \(call.call.visibleRemoteName)"))
            .accessibilityHint(Text("Tap to open the call. Drag to move"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { onReturn(nil) }
            .accessibilityAction(named: Text("Return to call")) { onReturn(nil) }
            .accessibilityAction(named: Text("Hide window")) { onHide() }
            .accessibilityAction(named: Text(LocalizedStringKey(call.isMuted ? "Unmute microphone" : "Mute microphone"))) {
                toggleMute()
            }
            .overlay(alignment: closeCorner) { closeButton(leading: leading) }
            .scaleEffect(pinch, anchor: anchor)
            .offset(x: layout.origin.x, y: layout.origin.y)
            // Under Reduce Motion the geometry never animates: it fades.
            .transaction { transaction in
                if reduceMotion { transaction.animation = nil }
            }
            .opacity(hidden ? 0 : 1)
            .allowsHitTesting(!tucked)
            .accessibilityHidden(tucked)
    }

    /// The peer's picture over the call colour; their avatar and name while
    /// their camera is off; dimmed with words during a shown drop. The
    /// renderer draws only while the window shows (`drawing`: not tucked) and
    /// a picture comes or is coming; otherwise it stays mounted, paused.
    private func picture(width: CGFloat, drawing: Bool) -> some View {
        ZStack {
            UnyriColors.callBackground
            RemoteVideoView(active: drawing && call.remoteVideoState != .off)
            if call.remoteVideoState != .on {
                UnyriColors.callBackground
                VStack(spacing: 6) {
                    CallPeerAvatar(userId: call.call.remoteUserId, name: call.call.visibleRemoteName,
                                   size: (width * 0.46).rounded())
                    Text(call.call.visibleRemoteName)
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(UnyriColors.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, 8)
                }
            }
            if reconnecting {
                Color.black.opacity(0.5)
                Text("Reconnecting…")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(UnyriColors.primaryText)
                    .multilineTextAlignment(.center)
                    .padding(8)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: reconnecting)
        .allowsHitTesting(false)
    }

    @ViewBuilder private var muteBadge: some View {
        if call.isMuted {
            Image(systemName: "mic.slash.fill")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.black.opacity(0.55)))
                .padding(6)
                .allowsHitTesting(false)
        }
    }

    /// × hides the window only; the call and its bar stay.
    private func closeButton(leading: Bool) -> some View {
        Button {
            UnyriHaptics.press()
            onHide()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(UnyriColors.primaryText)
                .frame(width: 24, height: 24)
                .modifier(CallGlassBackground(shape: Circle(), glass: true))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(CallGlassPressStyle())
        // The circle sits 4 pt inside the corner; the target reaches past it.
        .offset(x: leading ? 6 : -6, y: -6)
        .accessibilityLabel("Hide window")
        .accessibilityIdentifier("call-window-hide")
    }

    /// What is left at the edge while tucked: a glass tab with a chevron.
    private func tab(_ layout: Layout) -> some View {
        let leading = placement.corner.leading
        let rect = CallMiniWindowPolicy.tabFrame(leading: leading, fraction: placement.tabFraction,
                                                 window: layout.window, container: layout.container, area: layout.area)
        let target = CallMiniWindowPolicy.tabTarget(rect)
        let chevron = leading ? "chevron.right" : "chevron.left"
        // The chevron centres in the part that shows.
        let visibleSide: Alignment = leading ? .trailing : .leading
        return Image(systemName: chevron)
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(UnyriColors.primaryText)
            .frame(width: rect.width - CallMiniWindowPolicy.tabInset, height: rect.height)
            .frame(width: rect.width, alignment: visibleSide)
            .modifier(CallGlassBackground(shape: Capsule(), glass: true))
            .frame(width: target.width, height: target.height)
            .contentShape(Rectangle())
            .onTapGesture { untuck(fraction: placement.tabFraction) }
            .gesture(DragGesture(minimumDistance: 8, coordinateSpace: .named(space)).onEnded { value in
                untuck(fraction: CallMiniWindowPolicy.fraction(centerY: value.location.y, window: layout.window,
                                                               area: layout.area))
            })
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Show call window"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { untuck(fraction: placement.tabFraction) }
            .accessibilityAction(named: Text("Return to call")) { onReturn(nil) }
            .offset(x: target.minX, y: target.minY)
    }

    /// A touch sequence that includes a pinch only resizes: the drag follows
    /// the pinching fingers and jumps when one lifts, and that speed must not
    /// carry the window to another corner or tuck it.
    private func moveAndPinch(_ layout: Layout) -> some Gesture {
        let move = DragGesture(minimumDistance: 4, coordinateSpace: .named(space))
            .updating($dragging) { _, state, _ in state = true }
            .onChanged { value in
                if !tracker.dragging {
                    tracker.begin(at: value.time)
                    finishFade()
                }
                guard !tracker.pinched else { return }
                tracker.add(value.location, at: value.time)
                drag = value.translation
            }
            .onEnded { value in
                guard tracker.dragging else { return }
                tracker.dragging = false
                // The pinch's own end lands the size; the window keeps its corner.
                guard !tracker.pinched else { return }
                tracker.add(value.location, at: value.time)
                let released = CallMiniWindowPolicy.dragged(
                    CGPoint(x: layout.rest.x + value.translation.width, y: layout.rest.y + value.translation.height),
                    window: layout.window, container: layout.container, area: layout.area)
                settle(from: released, velocity: CallMiniWindowPolicy.velocity(tracker.samples), layout)
            }
        let zoom = MagnificationGesture()
            .updating($pinching) { _, state, _ in state = true }
            .onChanged { value in
                if !tracker.pinching {
                    tracker.beginPinch()
                    finishFade()
                    // Moved before the second finger landed: back to the corner.
                    if drag != .zero { withAnimation(springBack) { drag = .zero } }
                }
                pinch = Self.clampedPinch(value)
            }
            .onEnded { value in
                guard tracker.pinching else { return }
                tracker.endPinch(at: Date())
                resize(to: CallMiniWindowPolicy.size(pinchedWidth: layout.window.width * Self.clampedPinch(value),
                                                     current: placement.size, area: layout.area),
                       area: layout.area)
            }
        return move.simultaneously(with: zoom)
    }

    private static func clampedPinch(_ value: CGFloat) -> CGFloat { min(1.8, max(0.6, value)) }

    /// The pinch, or a drag a pinch took over, springing back.
    private var springBack: Animation? { reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.8) }

    /// The pinch grows and shrinks the window away from its corner.
    private var anchor: UnitPoint {
        switch placement.corner {
        case .topLeading: return .topLeading
        case .topTrailing: return .topTrailing
        case .bottomLeading: return .bottomLeading
        case .bottomTrailing: return .bottomTrailing
        }
    }

    private func settle(from origin: CGPoint, velocity: CGVector, _ layout: Layout) {
        var next = placement
        let target: CGPoint
        switch CallMiniWindowPolicy.rest(origin: origin, velocity: velocity, window: layout.window,
                                         container: layout.container, area: layout.area) {
        case .corner(let corner):
            next.corner = corner
            target = CallMiniWindowPolicy.origin(corner, window: layout.window, area: layout.area)
        case .tucked(let leading, let fraction):
            next.corner = .at(leading: leading, top: fraction < 0.5)
            next.tucked = true
            next.tabFraction = fraction
            target = CallMiniWindowPolicy.tuckedOrigin(leading: leading, fraction: fraction, window: layout.window,
                                                       container: layout.container, area: layout.area)
            UnyriHaptics.selection()
        }
        commit(next, velocity: CallMiniWindowPolicy.springVelocity(velocity, from: origin, to: target))
    }

    private func resize(to size: CallMiniWindowPlacement.Size, area: CGRect) {
        // The same step, or one that fits to the same size here: nothing
        // changes, so no haptic; the pinch springs back.
        guard size != placement.size,
              CallMiniWindowPolicy.windowSize(size, area: area) != CallMiniWindowPolicy.windowSize(placement.size, area: area) else {
            if pinch != 1 { withAnimation(springBack) { pinch = 1 } }
            return
        }
        var next = placement
        next.size = size
        UnyriHaptics.selection()
        commit(next)
    }

    private func untuck(fraction: CGFloat) {
        guard placement.tucked else { return }
        var next = placement
        next.corner = CallMiniWindowPolicy.untucked(leading: placement.corner.leading, fraction: fraction)
        next.tucked = false
        UnyriHaptics.selection()
        commit(next)
    }

    /// One landing per change: a spring carrying the release speed, a short
    /// ease in Low Power Mode, a crossfade under Reduce Motion.
    private func commit(_ next: CallMiniWindowPlacement, velocity: CGFloat = 0) {
        memory.store(next, for: call.call.callId)
        switch CallMorphStyle.current(reduceMotion: reduceMotion) {
        case .full:
            withAnimation(.interpolatingSpring(mass: 1, stiffness: 240, damping: 26, initialVelocity: velocity)) { apply(next) }
        case .lowPower:
            withAnimation(.easeOut(duration: 0.25)) { apply(next) }
        case .fade:
            crossfade(to: next)
        }
    }

    private func apply(_ next: CallMiniWindowPlacement) {
        placement = next
        drag = .zero
        // Never under the fingers: the pinch's end lands it.
        if !tracker.pinching { pinch = 1 }
    }

    /// Reduce Motion: out where it is, in where it lands.
    private func crossfade(to next: CallMiniWindowPlacement) {
        fade?.cancel()
        // From the tab nothing shows yet: the tab fades out as the window fades in.
        guard !placement.tucked else {
            withAnimation(.easeInOut(duration: 0.2)) { apply(next) }
            return
        }
        withAnimation(.easeOut(duration: 0.12)) { faded = true }
        fade = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 120_000_000) } catch { return }
            fade = nil
            withAnimation(.easeIn(duration: 0.16)) {
                apply(next)
                faded = false
            }
        }
    }

    /// A gesture during a Reduce Motion crossfade lands the stored placement at once.
    private func finishFade() {
        guard let pending = fade else { return }
        pending.cancel()
        fade = nil
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            apply(memory.placement(for: call.call.callId))
            faded = false
        }
    }

    /// Keeps the window above the keyboard, moving with it.
    private func followKeyboard(_ note: Notification, hidden: Bool = false) {
        let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .null
        let top: CGFloat = hidden || frame.isEmpty ? .infinity : frame.minY
        guard top != keyboardTop else { return }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        withAnimation(.easeOut(duration: max(0.1, duration))) { keyboardTop = top }
    }
}

/// The peer's photo for call surfaces. The resolved URL is kept per user
/// (`CallPeerAvatarCache`): a phase change, the video identity or the
/// minimized window shows it in the first frame instead of flashing initials,
/// and it is resolved again only once the entry is old (signed URLs expire).
struct CallPeerAvatar: View {
    let userId: String
    let name: String
    let size: CGFloat
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var chats: ChatsRepository
    @State private var url: URL?
    var body: some View {
        UserAvatar(url: url ?? CallPeerAvatarCache.shared.url(userId), name: name, size: size)
            .task(id: userId) {
                if let cached = CallPeerAvatarCache.shared.url(userId), url != cached { url = cached }
                guard CallPeerAvatarCache.shared.needsRefresh(userId) else { return }
                let cachedChats = await chats.loadCachedChatsAsync()
                guard !Task.isCancelled, let peer = cachedChats.first(where: { $0.otherUser?.id == userId })?.otherUser else { return }
                let resolved = await users.avatarURL(id: userId, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion)
                guard !Task.isCancelled else { return }
                CallPeerAvatarCache.shared.set(resolved, for: userId)
                if url != resolved { url = resolved }
            }
    }
}

/// The call stage's size (`CallScreen` measures it beside its content).
private struct CallScreenSizeKey: PreferenceKey {
    static let defaultValue = CGSize.zero
    /// Siblings that write nothing (the overlay while it is still empty) report the
    /// default `.zero`; taking it would reset the measured size, keep the overlay
    /// empty and leave a blank call screen (0.4.12). Only a real size counts.
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        let next = nextValue()
        if next.width > 0, next.height > 0 { value = next }
    }
}

// MARK: - Minimize morph

/// How the minimize morph moves: a spring, a short ease in Low Power Mode, or
/// a fade only under Reduce Motion.
enum CallMorphStyle: Equatable {
    case full, lowPower, fade

    static func current(reduceMotion: Bool) -> CallMorphStyle {
        if reduceMotion { return .fade }
        return ProcessInfo.processInfo.isLowPowerModeEnabled ? .lowPower : .full
    }
}

/// The minimize morph, in UIKit on the render server. Collapsing snapshots the
/// window, dismisses the cover without its slide and shrinks the snapshot into
/// the call bar; expanding grows a dark surface out of the bar (or the mini
/// video window) and then presents the cover without its slide. The live call
/// tree never carries a mask or a transform: a SwiftUI mask would cost an
/// offscreen pass per video frame at rest, and a structural branch would
/// remount the renderer. iOS 17+ only (the cover honours
/// `Transaction.disablesAnimations` from there); iOS 16, VoiceOver and an
/// inactive app keep the system slide. One per app root, shared by the cover
/// and the bar.
@MainActor
final class CallMorphAnchors {
    weak var window: UIWindow?
    /// The call bar's capsule in window coordinates, written by the bar.
    var bannerFrame: CGRect?
    private(set) var busy = false {
        didSet {
            if !busy {
                let completion = settledCallback
                settledCallback = nil
                completion?()
            }
        }
    }
    private var settledCallback: (() -> Void)?

    /// The expand animator (or its safety completion) releases the rotation
    /// gate exactly once, after removing the ghost. Initial covers run at once.
    func whenSettled(_ completion: @escaping () -> Void) {
        if busy { settledCallback = completion } else { completion() }
    }
    func cancelSettledCallback() { settledCallback = nil }
    /// Running animators, held until they finish.
    private var animators: [UIViewPropertyAnimator] = []

    nonisolated init() {}

    private func run(_ animator: UIViewPropertyAnimator, afterDelay delay: TimeInterval = 0) {
        animators.append(animator)
        animator.addCompletion { [weak self, weak animator] _ in
            self?.animators.removeAll { $0 === animator }
        }
        animator.startAnimation(afterDelay: delay)
    }

    private var supported: Bool {
        guard #available(iOS 17, *) else { return false }
        return !busy && !UIAccessibility.isVoiceOverRunning && window != nil
            && UIApplication.shared.applicationState == .active
    }

    /// False when the morph cannot run; the caller then dismisses as before.
    func collapse(style: CallMorphStyle, dismiss: () -> Void) -> Bool {
        guard supported, let window, let snapshot = window.snapshotView(afterScreenUpdates: false) else { return false }
        busy = true
        let container = UIView(frame: window.bounds)
        container.clipsToBounds = true
        container.layer.cornerCurve = .continuous
        container.layer.cornerRadius = Self.screenRadius(window)
        container.isUserInteractionEnabled = false
        container.accessibilityElementsHidden = true
        snapshot.frame = container.bounds
        container.addSubview(snapshot)
        window.addSubview(container)
        // The cover leaves and the bar arrives at once, both under the snapshot.
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { dismiss() }
        // A turn later the bar has its frame.
        DispatchQueue.main.async { [weak self] in
            self?.shrink(container, snapshot: snapshot, in: window, style: style)
        }
        return true
    }

    private func shrink(_ container: UIView, snapshot: UIView, in window: UIWindow, style: CallMorphStyle) {
        let width = window.bounds.width
        let target = barFrame(in: window)
        let scale = width > 0 ? target.width / width : 1
        let finish: () -> Void = { [weak self] in
            // Once per morph; a later morph's container is not this one.
            guard container.superview != nil else { return }
            container.removeFromSuperview()
            self?.busy = false
        }
        let geometry: () -> Void = {
            container.frame = target
            container.layer.cornerRadius = target.height / 2
            snapshot.transform = CGAffineTransform(scaleX: scale, y: scale)
            snapshot.center = CGPoint(x: target.width / 2, y: target.height / 2)
        }
        let fade: UIViewPropertyAnimator
        let fadeDelay: TimeInterval
        switch style {
        case .full:
            run(UIViewPropertyAnimator(duration: 0.44, dampingRatio: 0.9, animations: geometry))
            fade = UIViewPropertyAnimator(duration: 0.26, curve: .easeIn) { container.alpha = 0 }
            fadeDelay = 0.18
        case .lowPower:
            run(UIViewPropertyAnimator(duration: 0.30, curve: .easeOut, animations: geometry))
            fade = UIViewPropertyAnimator(duration: 0.18, curve: .easeIn) { container.alpha = 0 }
            fadeDelay = 0.12
        case .fade:
            fade = UIViewPropertyAnimator(duration: 0.20, curve: .easeOut) { container.alpha = 0 }
            fadeDelay = 0
        }
        fade.addCompletion { _ in finish() }
        run(fade, afterDelay: fadeDelay)
        // Never leave a snapshot over the app.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { finish() }
    }

    /// Grows from `origin` (the mini video window), else from the bar.
    func expand(from origin: CGRect?, style: CallMorphStyle, present: @escaping () -> Void) {
        guard supported, let window else { present(); return }
        busy = true
        let start = origin.flatMap { $0.isEmpty ? nil : $0 } ?? barFrame(in: window)
        let fullRadius = Self.screenRadius(window)
        let ghost = UIView()
        ghost.backgroundColor = UIColor(UnyriColors.callBackground)
        ghost.layer.cornerCurve = .continuous
        ghost.clipsToBounds = true
        ghost.isUserInteractionEnabled = false
        ghost.accessibilityElementsHidden = true
        if style == .fade {
            ghost.frame = window.bounds
            ghost.alpha = 0
        } else {
            ghost.frame = start
            ghost.layer.cornerRadius = min(start.height / 2, 24)
        }
        window.addSubview(ghost)
        let grow: UIViewPropertyAnimator
        switch style {
        case .full:
            grow = UIViewPropertyAnimator(duration: 0.40, dampingRatio: 0.9) {
                ghost.frame = window.bounds
                ghost.layer.cornerRadius = fullRadius
            }
        case .lowPower:
            grow = UIViewPropertyAnimator(duration: 0.28, curve: .easeOut) {
                ghost.frame = window.bounds
                ghost.layer.cornerRadius = fullRadius
            }
        case .fade:
            grow = UIViewPropertyAnimator(duration: 0.18, curve: .easeOut) { ghost.alpha = 1 }
        }
        grow.addCompletion { [weak self] _ in
            // The cover appears at once; its container lands above the ghost.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { present() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                UIView.animate(withDuration: 0.15, animations: { ghost.alpha = 0 }) { _ in
                    guard ghost.superview != nil else { return }
                    ghost.removeFromSuperview()
                    self?.busy = false
                }
            }
        }
        run(grow)
        // Never leave the ghost over the app.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            guard ghost.superview != nil else { return }
            ghost.removeFromSuperview()
            self?.busy = false
        }
    }

    /// The bar's capsule, or where it sits under the status bar.
    private func barFrame(in window: UIWindow) -> CGRect {
        if let frame = bannerFrame, !frame.isEmpty, window.bounds.contains(frame) { return frame }
        let insets = window.safeAreaInsets
        return CGRect(x: insets.left + 10, y: insets.top + 4,
                      width: max(0, window.bounds.width - insets.left - insets.right - 20), height: 48)
    }

    /// Rounded like the display on Face ID iPhones, square elsewhere.
    private static func screenRadius(_ window: UIWindow) -> CGFloat {
        window.safeAreaInsets.bottom > 0 ? 38 : 0
    }
}

/// Hands the hosting window to the morph. Zero-size and invisible to touch
/// and VoiceOver.
struct CallMorphWindowAnchor: UIViewRepresentable {
    let morph: CallMorphAnchors

    func makeUIView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.morph = morph
        return view
    }

    func updateUIView(_ view: AnchorView, context: Context) {
        guard view.morph !== morph else { return }
        view.morph = morph
        if let window = view.window { morph.window = window }
    }

    final class AnchorView: UIView {
        weak var morph: CallMorphAnchors?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
            accessibilityElementsHidden = true
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { morph?.window = window }
        }
    }
}

extension Notification.Name {
    /// Posted with a Bool when the full-screen call appears/disappears over the app.
    static let unyriCallScreenVisibility = Notification.Name("UNYRICallScreenVisibility")
}


/// Three large radial glows drifting slowly over black (CAGradientLayer, render server).
struct UnyriCallAurora: UIViewRepresentable {
    var animated = true
    func makeUIView(context: Context) -> AuroraView { AuroraView() }
    func updateUIView(_ view: AuroraView, context: Context) { view.setAnimated(animated) }
    final class AuroraView: UIView {
        private let blobs: [(UIColor, CGFloat, CGPoint, CGPoint, CFTimeInterval)] = [
            (UIColor(red: 0.20, green: 0.79, blue: 0.91, alpha: 1), 0.30, CGPoint(x: 0.15, y: 0.20), CGPoint(x: 0.55, y: 0.38), 17),
            (UIColor(red: 0.65, green: 0.51, blue: 0.93, alpha: 1), 0.28, CGPoint(x: 0.85, y: 0.70), CGPoint(x: 0.40, y: 0.55), 21),
            (UIColor(red: 0.24, green: 0.36, blue: 0.85, alpha: 1), 0.20, CGPoint(x: 0.50, y: 0.95), CGPoint(x: 0.70, y: 0.25), 26),
        ]
        private var layersMade = false
        private var wantsAnimation = true
        private var laidOutSize: CGSize = .zero
        override init(frame: CGRect) { super.init(frame: frame); isUserInteractionEnabled = false; backgroundColor = .clear }
        required init?(coder: NSCoder) { nil }
        override func layoutSubviews() {
            super.layoutSubviews()
            // Layout passes happen on every SwiftUI update; only a real size
            // change re-lays the glows and restarts their drift.
            guard bounds.width > 0, bounds.size != laidOutSize else { return }
            laidOutSize = bounds.size
            if !layersMade {
                layersMade = true
                for blob in blobs {
                    let layer = CAGradientLayer()
                    layer.type = .radial
                    layer.colors = [blob.0.withAlphaComponent(blob.1).cgColor, blob.0.withAlphaComponent(blob.1 * 0.35).cgColor, UIColor.clear.cgColor]
                    layer.locations = [0, 0.45, 1]
                    layer.startPoint = CGPoint(x: 0.5, y: 0.5)
                    layer.endPoint = CGPoint(x: 1, y: 1)
                    self.layer.addSublayer(layer)
                }
            }
            let side = max(bounds.width, bounds.height) * 1.1
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            for (index, layer) in (layer.sublayers ?? []).enumerated() where index < blobs.count {
                layer.bounds = CGRect(x: 0, y: 0, width: side, height: side)
                let point = blobs[index].2
                layer.position = CGPoint(x: bounds.width * point.x, y: bounds.height * point.y)
            }
            CATransaction.commit()
            restart(force: true)
        }
        func setAnimated(_ on: Bool) {
            guard on != wantsAnimation else { return }
            wantsAnimation = on
            restart(force: true)
        }
        override func didMoveToWindow() { super.didMoveToWindow(); restart(force: false) }
        /// `force` replaces a running drift (new size or switched on/off);
        /// otherwise only a missing drift is added, so nothing jumps back.
        private func restart(force: Bool) {
            guard let sublayers = layer.sublayers else { return }
            for (index, sub) in sublayers.enumerated() where index < blobs.count {
                let running = sub.animation(forKey: "drift") != nil
                let wanted = wantsAnimation && window != nil && bounds.width > 0
                if running && wanted && !force { continue }
                sub.removeAnimation(forKey: "drift")
                guard wanted else { continue }
                let drift = CABasicAnimation(keyPath: "position")
                drift.fromValue = NSValue(cgPoint: CGPoint(x: bounds.width * blobs[index].2.x, y: bounds.height * blobs[index].2.y))
                drift.toValue = NSValue(cgPoint: CGPoint(x: bounds.width * blobs[index].3.x, y: bounds.height * blobs[index].3.y))
                drift.duration = blobs[index].4
                drift.autoreverses = true
                drift.repeatCount = .infinity
                drift.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
                // The only motion at rest: a glow crossing the screen in
                // 17-26 s looks the same at 20 fps, and ProMotion would
                // otherwise recomposite the whole screen at up to 120 Hz (the
                // top bar keeps its flat rest look over it, never glass).
                drift.preferredFrameRateRange = CAFrameRateRange(minimum: 10, maximum: 30, preferred: 20)
                sub.add(drift, forKey: "drift")
            }
        }
    }
}
