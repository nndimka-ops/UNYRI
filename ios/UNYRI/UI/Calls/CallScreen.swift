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
    @EnvironmentObject private var users: UsersRepository
    @State private var avatarURL: URL?
    private var title: String {
        CallPeerTitlePolicy.title(displayName:name,username:peer?.username,unknown:UnyriL10n.string("Unknown user"))
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
                        Text(call.createdAt.formatted(date: .numeric, time: .shortened))
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
        }.padding(.vertical, 5)
        }
    }
    private var callSurface: some View {
        HStack(spacing: 12) {
            Image(systemName: call.mediaKind == .video ? "video.fill" : "phone.fill")
                .foregroundColor(call.historyLabel(owner: owner) == "Missed call" ? UnyriColors.danger : UnyriColors.accent)
            VStack(alignment: .leading, spacing: 4) {
                if earlierHistory { Text("Earlier call — before this sign-in").font(.caption2).foregroundColor(UnyriColors.secondaryText) }
                if let name { Text(name).font(.subheadline.weight(.semibold)).lineLimit(1) }
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

/// Functional call screen mirroring Android's call UI (outgoing/incoming/
/// connecting/connected). The single `CallController.state` is the source of
/// truth; this view only renders it and forwards user actions.
struct CallScreen: View {
    var onMinimize: () -> Void = {}
    var onOpenChat: (String) -> Void = { _ in }
    @EnvironmentObject private var pictureInPicture: CallPictureInPicture
    @EnvironmentObject private var controller: CallController
    @EnvironmentObject private var callKit: CallKitCoordinator
    @EnvironmentObject private var bannerController: ForegroundMessageBannerController
    @EnvironmentObject private var messagesRepository: MessagesRepository
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var availableSize = CGSize(width: 320, height: 568)
    @State private var videoControlsHeight: CGFloat = 0
    @State private var videoHeaderHeight: CGFloat = 0
    @State private var videoChromeHidden = false
    @State private var remoteVideoFills = false

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let c = controller.videoPresentation {
                    connectedVideo(c)
                } else {
                    ScrollView {
                        content.frame(minHeight: geometry.size.height)
                    }
                }
            }
            .onAppear { availableSize = geometry.size }
            .onChange(of: geometry.size) { availableSize = $0 }
        }
        .background(CallPipSource(pip: pictureInPicture))
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                if controller.videoPresentation == nil, let banner = bannerController.banner {
                    ForegroundMessageBannerView(banner: banner) {
                        bannerController.dismiss()
                        onOpenChat(banner.chatId)
                    }
                }
            if controller.videoPresentation == nil, let minimizeCallID = CallMinimizePolicy.callID(controller.state) {
                HStack {
                Button(action: { minimize(callID: minimizeCallID) }) {
                    Image(systemName: "chevron.down")
                        .font(UnyriTypography.buttonLabel)
                        .foregroundColor(UnyriColors.primaryText)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .background(Capsule().fill(UnyriColors.glassFloating))
                        .overlay(Capsule().stroke(UnyriColors.outlineSubtle, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Minimize call")
                .accessibilityIdentifier("call-minimize")
                .padding(16)
                // Keep the pan recognizer off the button. A parent pan can
                // consume the button's tap on recent UIKit/SwiftUI runtimes.
                Color.clear.frame(maxWidth: .infinity).frame(height: 76)
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 16).onEnded { value in
                        if CallMinimizePolicy.isDownwardSwipe(x: value.translation.width, y: value.translation.height) {
                            minimize(callID: minimizeCallID)
                        }
                    })
                    .accessibilityHidden(true)
                }
                // Replace only the gesture owner when a different call starts.
                // A held drag from the preceding call must never collapse it.
                .id(minimizeCallID)
            }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(videoChromeHidden && controller.videoPresentation != nil ? 0 : 1)
            .allowsHitTesting(!videoChromeHidden || controller.videoPresentation == nil)
            .accessibilityHidden(videoChromeHidden && controller.videoPresentation != nil)
        }
        .background {
            // Keep media outside the controls' safe-area inset. Only buttons,
            // captions and the draggable preview reserve space for system UI.
            ZStack {
                UnyriColors.callBackground
                atmosphere
                if let c = controller.videoPresentation {
                    // Always mount the renderer so it can report a fresh frame.
                    RemoteVideoView(fillsBounds: remoteVideoFills)
                    if c.remoteVideoState != .on { UnyriConversationBackground() }
                }
            }
            .ignoresSafeArea(.container)
        }
        .onChange(of: controller.videoPresentation?.call.callId) { _ in
            videoChromeHidden = false
            remoteVideoFills = false
        }
        .performanceScreen(.call)
        .onAppear {
            bannerController.isCallExpanded = true
            messagesRepository.isCallExpanded = true
        }
        .onDisappear {
            bannerController.isCallExpanded = false
            messagesRepository.isCallExpanded = false
        }
        .alert("Call error", isPresented: Binding(
            get: { callKit.actionError != nil },
            set: { if !$0 { callKit.actionError = nil } }
        )) {
            Button("OK") { callKit.actionError = nil }
        } message: { Text(callKit.actionError ?? "") }
    }

    private func minimize(callID: String) {
        guard CallMinimizePolicy.canComplete(requested: callID, state: controller.state) else { return }
        onMinimize()
    }

    // MARK: - Responsive layout policy (Phase 4 §41)

    private var screenWidth: CGFloat { availableSize.width }
    private var screenHeight: CGFloat { availableSize.height }
    private var visibleControlCount: Int {
        if let c = controller.videoPresentation {
            return c.localVideoEnabled && c.localCameraState == .on && controller.hasMultipleCameras() ? 5 : 4
        }
        switch controller.state {
        case .connected: return 4
        case .incomingRinging: return 2
        default: return 3
        }
    }
    private var controlSize: CGFloat {
        CallLayoutPolicy.controlSize(availableWidth: screenWidth, controlCount: visibleControlCount)
    }
    private var controlSpacing: CGFloat {
        CallLayoutPolicy.controlSpacing(availableWidth: screenWidth, controlCount: visibleControlCount)
    }
    private var previewSize: (width: CGFloat, height: CGFloat) {
        let regular = CallLayoutPolicy.localPreviewSize(availableWidth: screenWidth)
        let height = min(regular.height, max(40, screenHeight * 0.30))
        return (height / CallLayoutPolicy.previewAspect, height)
    }
    private var trayWidth: CGFloat {
        CallLayoutPolicy.trayWidth(availableWidth: screenWidth)
    }
    private var trayPadding: CGFloat {
        CallLayoutPolicy.trayPadding(availableWidth: screenWidth)
    }

    /// Shared stationary wallpaper behind the in-call identity and remote video.
    private var atmosphere: some View {
        UnyriConversationBackground().ignoresSafeArea()
    }

    private var durationText: some View {
        Group {
            if let start = controller.connectedAt {
                TimelineView(.periodic(from: start, by: 1)) { context in
                    Text(formatCallDuration(context.date.timeIntervalSince(start)))
                        .font(UnyriTypography.callDuration)
                        .foregroundColor(UnyriColors.primaryText)
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch controller.state {
        case .idle:
            EmptyView()
        case .outgoingRinging(let call):
            audioStateLayout(call, status: "Calling…") {
                HStack(spacing: controlSpacing) {
                    pendingAudioButtons
                    endButton { Task { await controller.cancelOutgoing() } }
                }
            }
        case .incomingRinging(let call):
            incomingLayout(call)
        case .connecting(let call):
            audioStateLayout(call, status: "Connecting…", spinner: true) {
                HStack(spacing: controlSpacing) {
                    pendingAudioButtons
                    endButton { Task { await controller.hangup() } }
                }
            }
        case .reconnecting(let call):
            audioStateLayout(call, status: "Reconnecting…", spinner: true) {
                HStack(spacing: controlSpacing) {
                    pendingAudioButtons
                    endButton { Task { await controller.hangup() } }
                }
            }
        case .connected(let c):
            ZStack {
                if c.videoMode == .video {
                    connectedVideo(c)
                } else {
                    connectedAudio(c)
                }
            }
        case .ended(let call, let reason):
            endedLayout(call, reason)
        case .failed(_, let reason):
            failedLayout(reason)
        }
    }

    @ViewBuilder
    private var pendingAudioButtons: some View {
        if let c = controller.audioControls {
            audioOutputButton(c, labelColor: UnyriColors.secondaryText)
            controlCircle(c.isMuted ? "mic.slash.fill" : "mic.fill", label: c.isMuted ? "Unmute microphone" : "Mute microphone", active: !c.isMuted, size: controlSize, caption: "Mute", action: { controller.toggleMute() })
        }
    }

    // MARK: - Connected (audio / video)

    private func connectedAudio(_ c: ConnectedState) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)
            avatar(c.call, size: CallLayoutPolicy.avatarSize(availableHeight: screenHeight, isVideo: false))
            Spacer().frame(height: UnyriSpacing.xl)
            nameText(c.call)
            Spacer().frame(height: UnyriSpacing.md)
            statusPill("Connected")
            Spacer().frame(height: UnyriSpacing.sm)
            durationText
            noticeLabel(c.notice)
            if c.localVideoEnabled {
                Spacer().frame(height: UnyriSpacing.md)
                LocalVideoView(isFrontFacing: c.localCameraIsFrontFacing)
                    .opacity(c.localCameraState == .on ? 1 : 0)
                    .frame(width: previewSize.width, height: previewSize.height)
                    .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo))
                    .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo).stroke(UnyriColors.outlineSecondary, lineWidth: 1))
            }
            Spacer(minLength: 8)
            // Runtime Correction C: canonical compact tray with SHORT captions.
            // Audio order: Mute · Speaker · Video-upgrade · End.
            HStack(spacing: controlSpacing) {
                audioOutputButton(c, labelColor: UnyriColors.secondaryText)
                controlCircle(c.isMuted ? "mic.slash.fill" : "mic.fill", label: c.isMuted ? "Unmute microphone" : "Mute microphone", active: !c.isMuted, size: controlSize, caption: "Mute", action: { controller.toggleMute() })
                controlCircle("video.fill", label: "Start video", size: controlSize, caption: "Video", enabled: c.videoUpgrade == .idle, identifier: "call-video", action: { controller.requestVideoUpgrade() })
                controlCircle("phone.down.fill", label: "End call", endCall: true, size: controlSize, caption: "End", action: { Task { await controller.hangup() } })
            }
            .padding(.horizontal, trayPadding)
            .padding(.vertical, UnyriSpacing.md)

            .frame(maxWidth: trayWidth)
            Spacer().frame(height: UnyriSpacing.xxxl)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: screenHeight)
        .padding(.horizontal, CallLayoutPolicy.trayMargin(availableWidth: screenWidth))
    }

    private func connectedVideo(_ c: ConnectedState) -> some View {
        ZStack {
            // This touch plane belongs only to the remote/background area.
            // Preview gestures and controls above it retain their own touches.
            Color.clear.contentShape(Rectangle())
                .gesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1))
                    .onEnded { value in
                        switch value {
                        case .first:
                            if c.remoteVideoState == .on { remoteVideoFills.toggle() }
                        case .second:
                            videoChromeHidden.toggle()
                        }
                    })
                .accessibilityIdentifier("call-video-background")
                .accessibilityLabel(UnyriL10n.string("Call video"))
                .accessibilityAction(named: Text(UnyriL10n.string("Show or hide call controls"))) { videoChromeHidden.toggle() }
                .accessibilityAction(named: Text(UnyriL10n.string("Fit or fill remote video"))) { remoteVideoFills.toggle() }
            if c.remoteVideoState != .on {
                GeometryReader { geometry in
                    let top = max(videoHeaderHeight, 32) + 8
                    let bottom = max(videoControlsHeight, controlSize + 64) + 8
                    let identityHeight = max(0, geometry.size.height - top - bottom)
                    // Keep identity above the measured controls, including in
                    // short landscape. Only identity may scroll; actions stay put.
                    ScrollView {
                        VStack(spacing: UnyriSpacing.md) {
                            avatar(c.call, size: min(96, max(32, identityHeight - 80)))
                            Text(c.call.remoteDisplayName)
                                .font(UnyriTypography.screenTitle)
                                .foregroundColor(UnyriColors.primaryText)
                                .lineLimit(CallLayoutPolicy.callNameMaxLines)
                                .truncationMode(.tail)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, UnyriSpacing.xl)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: identityHeight)
                    }
                    .frame(height: identityHeight)
                    .offset(y: top)
                }
                .opacity(videoChromeHidden ? 0 : 1)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: videoChromeHidden)
                .allowsHitTesting(false)
                .accessibilityHidden(videoChromeHidden)
            }
            VStack {
                VStack(spacing: 0) {
                if let banner = bannerController.banner {
                    ForegroundMessageBannerView(banner: banner) {
                        bannerController.dismiss()
                        onOpenChat(banner.chatId)
                    }
                }
                ZStack {
                    compactVideoDuration
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(CallTimerSurface())
                        .padding(.horizontal, 56)
                    HStack {
                        Button(action: { minimize(callID: c.call.callId) }) {
                            Image(systemName: "chevron.down")
                                .font(UnyriTypography.buttonLabel)
                                .foregroundColor(UnyriColors.primaryText)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(UnyriColors.glassFloating))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Minimize call")
                        .accessibilityIdentifier("call-minimize")
                        Spacer(minLength: 0)
                        // The swipe owner stays separate from the button/video.
                        Color.clear.frame(width: 44, height: 44).contentShape(Rectangle())
                            .gesture(DragGesture(minimumDistance: 16).onEnded { value in
                                if CallMinimizePolicy.isDownwardSwipe(x: value.translation.width, y: value.translation.height) {
                                    minimize(callID: c.call.callId)
                                }
                            })
                            .accessibilityHidden(true)
                    }
                }
                .frame(minHeight: 44)
                .padding(.horizontal, 12)
                .padding(.top, 4)
                noticeLabel(c.notice)
                }
                .background(GeometryReader { geometry in
                    Color.clear.preference(key: CallHeaderHeightKey.self, value: geometry.size.height)
                })
                .onPreferenceChange(CallHeaderHeightKey.self) { height in
                    if abs(videoHeaderHeight - height) > 0.5 { videoHeaderHeight = height }
                }
                Spacer()
                videoControls(c)
            }
            // Retain controls and renderer identity; visibility never changes
            // the video viewport or starts/stops a media track.
            .opacity(videoChromeHidden ? 0 : 1)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: videoChromeHidden)
            .allowsHitTesting(!videoChromeHidden)
            .accessibilityHidden(videoChromeHidden)
            if c.localVideoEnabled {
                DraggableCallPreview(width: previewSize.width, height: previewSize.height,
                    bottomClearance: videoChromeHidden ? 12 : max(controlSize + 64, videoControlsHeight + 12),
                    topClearance: videoChromeHidden ? 8 : max(56, videoHeaderHeight + 8),
                    isFrontFacing: c.localCameraIsFrontFacing)
                    .opacity(c.localCameraState == .on ? 1 : 0)
                    .allowsHitTesting(c.localCameraState == .on)
                    .accessibilityHidden(c.localCameraState != .on)
            }
        }
    }

    private var compactVideoDuration: some View {
        Group {
            if let start = controller.connectedAt {
                TimelineView(.periodic(from: start, by: 1)) { context in
                    Text(formatCallDuration(context.date.timeIntervalSince(start)))
                        .font(.system(size: 13, weight: .semibold).monospacedDigit())
                        .foregroundColor(UnyriColors.primaryText)
                        .lineLimit(1)
                }
            }
        }
        .accessibilityIdentifier("call-video-duration")
    }

    private func videoControls(_ c: ConnectedState) -> some View {
        // Camera · Mute · End · Speaker, with Flip only for our running camera.
        // Calculate capability and layout once; four and five controls both center.
        let supportsFlip = controller.hasMultipleCameras()
        let flipVisible = supportsFlip && c.localVideoEnabled && c.localCameraState == .on
        let count = flipVisible ? 5 : 4
        let size = CallLayoutPolicy.controlSize(availableWidth: screenWidth, controlCount: count)
        let spacing = CallLayoutPolicy.controlSpacing(availableWidth: screenWidth, controlCount: count)
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: spacing) {
                videoPrimaryControls(c, size: size)
                videoSecondaryControls(c, size: size, flipVisible: flipVisible)
            }
            VStack(spacing: UnyriSpacing.md) {
                HStack(spacing: spacing) { videoPrimaryControls(c, size: size) }
                HStack(spacing: spacing) { videoSecondaryControls(c, size: size, flipVisible: flipVisible) }
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: flipVisible)
        .padding(.horizontal, trayPadding)
        .padding(.vertical, UnyriSpacing.md)

        .frame(maxWidth: trayWidth)
        .padding(.horizontal, CallLayoutPolicy.trayMargin(availableWidth: screenWidth))
        .padding(.bottom, UnyriSpacing.lg)
        .background(GeometryReader { geometry in
            Color.clear.preference(key: CallControlsHeightKey.self, value: geometry.size.height)
        })
        .onPreferenceChange(CallControlsHeightKey.self) { height in
            if abs(videoControlsHeight - height) > 0.5 { videoControlsHeight = height }
        }
    }

    @ViewBuilder
    private func videoPrimaryControls(_ c: ConnectedState, size: CGFloat) -> some View {
        controlCircle(c.localVideoEnabled ? "video.fill" : "video.slash.fill", label: c.localVideoEnabled ? "Turn camera off" : "Turn camera on", active: c.localVideoEnabled, size: size, caption: "Camera", identifier: "call-camera", action: { controller.toggleVideo() })
        controlCircle(c.isMuted ? "mic.slash.fill" : "mic.fill", label: c.isMuted ? "Unmute microphone" : "Mute microphone", active: !c.isMuted, size: size, caption: "Mute", identifier: "call-mute", action: { controller.toggleMute() })
        controlCircle("phone.down.fill", label: "End call", endCall: true, size: size, caption: "End", identifier: "call-end", action: { Task { await controller.hangup() } })
    }

    @ViewBuilder
    private func videoSecondaryControls(_ c: ConnectedState, size: CGFloat, flipVisible: Bool) -> some View {
        audioOutputButton(c, labelColor: UnyriColors.secondaryText, size: size)
        if flipVisible {
            controlCircle("arrow.triangle.2.circlepath.camera.fill", label: "Switch camera", size: size, caption: "Flip", enabled: localCameraReady(for: c.call.callId), identifier: "call-flip", action: {
                // A departing transition must never flip a stopped/replaced camera.
                guard localCameraReady(for: c.call.callId) else { return }
                controller.switchCamera()
            })
                .allowsHitTesting(localCameraReady(for: c.call.callId))
                .accessibilityHidden(!localCameraReady(for: c.call.callId))
                .transition(reduceMotion ? .identity : .opacity.combined(with: .scale(scale: 0.9)))
        }
    }

    private func localCameraReady(for callID: String) -> Bool {
        guard let current = controller.videoPresentation, current.call.callId == callID else { return false }
        return current.localVideoEnabled && current.localCameraState == .on
    }

    // MARK: - Layout helpers

    private func audioStateLayout<Controls: View>(
        _ call: CallInfo,
        status: String,
        spinner: Bool = false,
        @ViewBuilder controls: () -> Controls
    ) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)
            avatar(call, size: CallLayoutPolicy.avatarSize(availableHeight: screenHeight, isVideo: false))
            Spacer().frame(height: UnyriSpacing.xl)
            nameText(call)
            Spacer().frame(height: UnyriSpacing.md)
            statusPill(status, spinner: spinner)
            Spacer(minLength: 8)
            controls()
            Spacer().frame(height: UnyriSpacing.xl)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, UnyriSpacing.xl)
    }

    private func incomingLayout(_ call: CallInfo) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)
            avatar(call, size: CallLayoutPolicy.avatarSize(availableHeight: screenHeight, isVideo: false))
            Spacer().frame(height: UnyriSpacing.xl)
            nameText(call)
            Spacer().frame(height: UnyriSpacing.md)
            statusPill(call.mediaKind == .video ? "Incoming video call" : "Incoming audio call")
            Spacer(minLength: 8)
            HStack(spacing: UnyriSpacing.xxxl) {
                controlCircle("phone.down.fill", label: "Decline", endCall: true, size: controlSize, action: { Task { await controller.rejectIncoming() } })
                controlCircle("phone.fill", label: "Accept", color: UnyriColors.success, active: true, size: controlSize, action: { callKit.answerIncoming() })
            }
            Spacer().frame(height: UnyriSpacing.xl)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, UnyriSpacing.xl)
    }

    private func endedLayout(_ call: CallInfo, _ reason: CallEndReason) -> some View {
        VStack(spacing: UnyriSpacing.lg) {
            Spacer()
            avatar(call, size: 96)
            Text(LocalizedStringKey(reasonText(reason)))
                .font(UnyriTypography.screenTitle)
                .foregroundColor(UnyriColors.primaryText)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(UnyriSpacing.xxxl)
    }

    private func failedLayout(_ reason: CallFailureReason) -> some View {
        VStack(spacing: UnyriSpacing.lg) {
            Spacer()
            Image(systemName: reason == .busy ? "phone.down.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundColor(UnyriColors.warning)
            Text(LocalizedStringKey(reason == .busy ? "Line busy" : reason == .network ? "Network error" : "Call failed"))
                .font(UnyriTypography.screenTitle)
                .foregroundColor(UnyriColors.primaryText)
            if reason == .busy {
                Text("The line is busy. Try calling again later.").foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(UnyriSpacing.xxxl)
    }

    private func nameText(_ call: CallInfo) -> some View {
        Text(call.remoteDisplayName)
            .font(UnyriTypography.screenTitle)
            .foregroundColor(UnyriColors.primaryText)
            .lineLimit(CallLayoutPolicy.callNameMaxLines)
            .truncationMode(.tail)
            .multilineTextAlignment(.center)
            .padding(.horizontal, UnyriSpacing.xl)
    }

    private func statusPill(_ text: String, spinner: Bool = false, warning: Bool = false) -> some View {
        HStack(spacing: 6) {
            if spinner {
                ProgressView().controlSize(.small).tint(UnyriColors.accentSoft)
            }
            Text(LocalizedStringKey(text))
                .font(UnyriTypography.status)
                .foregroundColor(warning ? UnyriColors.warning : UnyriColors.secondaryText)
                .lineLimit(1)
        }
        .padding(.horizontal, 14).padding(.vertical, 5)
        .background(Capsule().fill(UnyriColors.glassSecondary))
        .overlay(Capsule().stroke(UnyriColors.outlineSubtle, lineWidth: 1))
    }

    // MARK: - Shared controls

    /// Audio-output picker: icon + label always reflect the current output,
    /// and tapping lists the routes the device actually supports.
    @ViewBuilder
    private func audioOutputButton(_ c: ConnectedState, labelColor: Color, showLabel: Bool = false, size: CGFloat? = nil) -> some View {
        let routes = controller.availableAudioRoutes()
        Button {
            let target = c.audioRoute == .speaker
                ? (routes.first { $0.kind == .bluetooth || $0.kind == .wiredHeadset }
                    ?? routes.first { $0.kind == .earpiece })
                : routes.first { $0.kind == .speaker }
            if let target { controller.setAudioRoute(target) }
        } label: {
            audioOutputLabel(c, labelColor: labelColor, showLabel: showLabel, size: size ?? controlSize)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Speaker")
        .accessibilityValue(c.audioRoute == .speaker ? "On" : "Off")
        .accessibilityIdentifier("call-output")
    }

    private func audioOutputLabel(_ c: ConnectedState, labelColor: Color, showLabel: Bool, size: CGFloat) -> some View {
        VStack(spacing: UnyriSpacing.xs) {
            Image(systemName: "speaker.wave.3.fill")
                .font(.system(size: 22))
                .foregroundColor(c.audioRoute == .speaker ? UnyriColors.callBackground : .white)
                .frame(width: size, height: size)
                .background(Circle().fill(c.audioRoute == .speaker ? Color.white : Color.white.opacity(0.18)))
            if showLabel {
                Text(UnyriL10n.string("Speaker", comment: ""))
                    .font(UnyriTypography.caption)
                    .foregroundColor(labelColor)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(width: size)
            }
        }
    }

    private func audioRouteLabel(_ kind: AudioRouteKind) -> String {
        controller.availableAudioRoutes().first(where: { $0.kind == kind })?.label
            ?? Self.defaultAudioRouteLabel(kind)
    }

    private static func defaultAudioRouteLabel(_ kind: AudioRouteKind) -> String {
        switch kind {
        case .speaker: return "Speaker"
        case .earpiece: return "Phone"
        case .bluetooth: return "Bluetooth"
        case .wiredHeadset: return "Headphones"
        }
    }

    private static func audioRouteSymbol(_ kind: AudioRouteKind) -> String {
        switch kind {
        case .speaker: return "speaker.wave.3.fill"
        case .earpiece: return "phone.fill"
        case .bluetooth: return "earbuds"
        case .wiredHeadset: return "headphones"
        }
    }

    @ViewBuilder
    private func noticeLabel(_ notice: CallNotice?) -> some View {
        if let notice {
            Text(LocalizedStringKey(noticeText(notice)))
                .font(UnyriTypography.caption)
                .foregroundColor(UnyriColors.secondaryText)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(UnyriColors.glassSecondary))
                .overlay(Capsule().stroke(UnyriColors.outlineSubtle, lineWidth: 1))
                .padding(.top, UnyriSpacing.sm)
        }
    }

    private func noticeText(_ notice: CallNotice) -> String {
        switch notice {
        case .videoRequestDeclined: return "Video request declined"
        case .videoRequestTimeout: return "Video request timed out"
        case .videoFailed: return "Video unavailable"
        case .cameraDenied: return "Camera permission denied"
        case .cameraFailed: return "Camera unavailable"
        }
    }

    private func avatar(_ call: CallInfo, size: CGFloat = 96) -> some View {
        CallPeerAvatar(userId: call.remoteUserId, name: call.remoteDisplayName, size: size)
    }

    /// Reusable UNYRI call control (Phase 4 §6/§7): glass when inactive,
    /// accent + glow when active, danger for end call; accessibility label
    /// included and color is never the only state cue.
    private func controlCircle(
        _ symbol: String,
        label: String? = nil,
        color: Color = UnyriColors.accent,
        active: Bool = false,
        endCall: Bool = false,
        size: CGFloat = 60,
        showLabel: Bool = false,
        caption: String? = nil,
        enabled: Bool = true,
        identifier: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: UnyriSpacing.xs) {
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
                if showLabel, let visible = caption ?? label {
                    Text(LocalizedStringKey(visible))
                        .font(UnyriTypography.caption)
                        .foregroundColor(UnyriColors.secondaryText)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(width: size)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityLabel(label ?? symbol)
        .accessibilityIdentifier(identifier ?? (endCall ? "call-end" : symbol))
    }

    private func endButton(action: @escaping () -> Void) -> some View {
        controlCircle("phone.down.fill", label: "End call", endCall: true, size: controlSize, action: action)
    }

    private func reasonText(_ reason: CallEndReason) -> String {
        switch reason {
        case .hangup: return "Call ended"
        case .rejected: return "Declined"
        case .cancelled: return "Cancelled"
        case .missed: return "Missed"
        case .timeout: return "No answer"
        case .failed: return "Failed"
        case .answeredElsewhere: return "Answered on another device"
        case .remoteEnded: return "Call ended"
        }
    }
}

private struct CallControlsHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct CallHeaderHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private extension CallUiState {
    var isOver: Bool {
        switch self {
        case .ended, .failed: return true
        default: return false
        }
    }

}


/// The preview moves independently of the renderer and never covers call controls.
private struct DraggableCallPreview: View {
    let width: CGFloat
    let height: CGFloat
    let bottomClearance: CGFloat
    let topClearance: CGFloat
    let isFrontFacing: Bool
    var body: some View {
        FloatingCallVideo(width: width, height: height, bottomClearance: bottomClearance,
                          topClearance: topClearance, resizable: true) {
            LocalVideoView(isFrontFacing: isFrontFacing)
        }
        .accessibilityLabel("Your camera preview")
        .accessibilityHint(UnyriL10n.string("Drag to move. Pinch to resize"))
    }
}

/// The drag is measured in the stationary container, never in the moving video.
/// Persist each new position; cancelling a gesture must not reset its translation.
struct FloatingCallVideo<Content: View>: View {
    let width: CGFloat
    let height: CGFloat
    let bottomClearance: CGFloat
    // Defaults preserve the existing minimized/inline call behavior.
    var topClearance: CGFloat = 56
    var resizable = false
    @ViewBuilder let content: () -> Content
    var onTap: (() -> Void)? = nil
    @Namespace private var dragSpace
    @State private var location = CGPoint(x: 1, y: 1)
    @State private var dragOrigin: CGPoint?
    @GestureState private var dragging = false
    @GestureState private var magnifying = false
    @State private var previewScale: CGFloat = 1
    @State private var scaleOrigin: CGFloat?
    @State private var fixedOrigin: CGPoint?

    var body: some View {
        GeometryReader { geometry in
            let margin: CGFloat = 12
            let availableWidth = geometry.size.width - margin * 2
            let availableHeight = geometry.size.height - bottomClearance - topClearance - margin
            let maximumScale = max(0.1, min(240 / width, min(availableWidth / width, availableHeight / height)))
            let minimumScale = min(maximumScale, max(0.6, 64 / width))
            let scale = resizable ? min(maximumScale, max(minimumScale, previewScale)) : 1
            let fitted = CallLayoutPolicy.fittedPreviewSize(width: width * scale, height: height * scale,
                availableWidth: availableWidth, availableHeight: availableHeight)
            // A temporarily tiny viewport must not distort or recreate the renderer.
            let w = fitted?.width ?? width
            let h = fitted?.height ?? height
            let rangeX = max(0, geometry.size.width - w - margin * 2)
            let rangeY = max(0, geometry.size.height - h - bottomClearance - topClearance - margin)
            let bounds = CGRect(x: margin, y: topClearance, width: rangeX, height: rangeY)
            // Ordinary calls keep a point in stationary container coordinates.
            // Enlarging the legal area when chrome hides cannot move the PiP.
            let x = resizable ? min(bounds.maxX, max(bounds.minX, fixedOrigin?.x ?? bounds.maxX)) : margin + location.x * rangeX
            let y = resizable ? min(bounds.maxY, max(bounds.minY, fixedOrigin?.y ?? bounds.minY)) : topClearance + location.y * rangeY
            let previewDrag = DragGesture(minimumDistance: 4, coordinateSpace: .named(dragSpace))
                .updating($dragging) { _, state, _ in state = true }
                .onChanged { value in
                    if resizable {
                        let origin = dragOrigin ?? CGPoint(x: x, y: y)
                        dragOrigin = origin
                        fixedOrigin = clamped(CGPoint(x: origin.x + value.translation.width,
                                                      y: origin.y + value.translation.height), to: bounds)
                    } else {
                        let origin = dragOrigin ?? location
                        dragOrigin = origin
                        location = CGPoint(
                            x: FloatingVideoPosition.normalized(origin: origin.x, translation: value.translation.width, range: rangeX),
                            y: FloatingVideoPosition.normalized(origin: origin.y, translation: value.translation.height, range: rangeY))
                    }
                }
                .onEnded { value in
                    if resizable, let origin = dragOrigin {
                        fixedOrigin = clamped(CGPoint(x: origin.x + value.translation.width,
                                                      y: origin.y + value.translation.height), to: bounds)
                    } else if let origin = dragOrigin {
                        location = CGPoint(
                            x: FloatingVideoPosition.normalized(origin: origin.x, translation: value.translation.width, range: rangeX),
                            y: FloatingVideoPosition.normalized(origin: origin.y, translation: value.translation.height, range: rangeY))
                    }
                    dragOrigin = nil
                }
            content()
                .allowsHitTesting(false)
                .frame(width: w, height: h)
                .clipShape(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo))
                .overlay(RoundedRectangle(cornerRadius: UnyriShapes.radiusLocalVideo).stroke(.white.opacity(0.5), lineWidth: 1))
                .overlay {
                    if resizable {
                        Color.clear.contentShape(Rectangle()).gesture(
                            previewDrag.simultaneously(with: MagnificationGesture()
                                .updating($magnifying) { _, state, _ in state = true }
                                .onChanged { value in
                                    let origin = scaleOrigin ?? scale
                                    scaleOrigin = origin
                                    previewScale = min(maximumScale, max(minimumScale, origin * value))
                                }
                                .onEnded { _ in scaleOrigin = nil })
                        )
                    } else {
                        // Shared inline callers retain their original drag-only
                        // recognizer; no pinch recognizer is installed here.
                        Color.clear.contentShape(Rectangle()).gesture(previewDrag)
                    }
                }
                .onAppear { if resizable && fixedOrigin == nil { fixedOrigin = CGPoint(x: x, y: y) } }
                .onChange(of: bounds) { next in
                    if resizable, let origin = fixedOrigin {
                        fixedOrigin = clamped(origin, to: next)
                        previewScale = scale
                    }
                }
                .onTapGesture { onTap?() }
                .offset(x: fitted == nil ? -20_000 : x, y: y)
                .opacity(fitted == nil ? 0 : 1)
                .allowsHitTesting(fitted != nil)
                .accessibilityHidden(fitted == nil)
                .transaction { $0.animation = nil; $0.disablesAnimations = true }
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


struct CallPeerAvatar: View {
    let userId: String
    let name: String
    let size: CGFloat
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var chats: ChatsRepository
    @State private var url: URL?
    var body: some View {
        UserAvatar(url: url, name: name, size: size)
            .task(id: userId) {
                url = nil
                let cached = await chats.loadCachedChatsAsync()
                guard !Task.isCancelled, let peer = cached.first(where: { $0.otherUser?.id == userId })?.otherUser else { return }
                let resolved = await users.avatarURL(id: userId, avatarUrl: peer.avatarUrl, avatarVersion: peer.avatarVersion)
                guard !Task.isCancelled else { return }
                url = resolved
            }
    }
}

/// One native glass surface for the ordinary video timer; no video snapshots.
private struct CallTimerSurface: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        if reduceTransparency || contrast == .increased {
            Capsule().fill(UnyriColors.callBackground)
                .overlay(Capsule().stroke(UnyriColors.outlineSecondary, lineWidth: 1))
        } else if #available(iOS 26.0, *) {
            Capsule().fill(.clear).glassEffect(.regular, in: Capsule())
        } else {
            Capsule().fill(.thinMaterial)
        }
    }
}
