import AVFoundation
import LiveKit
import SwiftUI
import UIKit

struct ConferenceLobby: View {
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var text = ""
    @State private var password = ""
    @State private var preview: ConferenceInvite?
    @State private var parsingError: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("Join by invitation") {
                    SecureField("Paste complete invitation", text: $text)
                        .textContentType(.oneTimeCode).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onChange(of: text) { _ in preview = nil; parsingError = nil }
                        .accessibilityIdentifier("conference-invite")
                    Button("Preview invitation") {
                        do { preview = try ConferenceInvite.parse(text, currentServer: controller.api.server); parsingError = nil }
                        catch { preview = nil; parsingError = error.localizedDescription }
                    }.disabled(controller.isBusy || text.isEmpty).frame(minHeight: 44)
                }
                if let invite = preview {
                    Section {
                        LabeledContent("Server", value: invite.server.absoluteString)
                        LabeledContent("Room", value: String(invite.roomID.uuidString.prefix(8)))
                        Label("End-to-end encrypted media", systemImage: "lock.shield")
                        Label("Camera and microphone off on entry", systemImage: "mic.slash")
                        if controller.accessSettingsAvailable {
                            SecureField("Room password (if required)", text: $password)
                                .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                        Button("Confirm and join") {
                            UnyriHaptics.selection()
                            let accessPassword = password.isEmpty ? nil : password
                            controller.join(invite, password: accessPassword)
                            password = ""
                            if controller.isBusy {
                                text = ""; preview = nil; controller.offeredInvite = nil
                                dismiss()
                            }
                        }.disabled(controller.isBusy).frame(minHeight: 44).accessibilityIdentifier("conference-join-confirm")
                    }
                }
                if let parsingError { Section { Text(LocalizedStringKey(parsingError)).foregroundStyle(UnyriColors.danger) } }
                if let error = controller.error { Section { Text(LocalizedStringKey(error)).foregroundStyle(UnyriColors.danger) } }
            }.scrollContentBackground(.hidden).background(UnyriColors.background)
                .scrollDismissesKeyboard(.interactively).tint(colors.role(.categoryBlue))
                .navigationTitle("Join by invitation").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { password = ""; controller.offeredInvite = nil; dismiss() } } }
                .task { await controller.refreshCapabilities() }
                .onAppear { preview = controller.offeredInvite }
                .onChange(of: controller.offeredInvite) { value in
                    if controller.showLobby, !controller.isBusy, let value { preview = value }
                }
                // A rejected password may reopen the next lobby before this
                // instance finishes disappearing. Only clear local entry state;
                // the presenting owner clears unconsumed invitations on cancel.
                .onDisappear { text = ""; password = ""; preview = nil }
        }
    }
}

struct ConferenceTile: View {
    let tile: ConferenceController.Tile
    @ObservedObject private var participant: Participant
    @Environment(\.unyriAppColorStyle) private var colors
    let pinned: Bool
    let compact: Bool
    var renderVideo = true
    var locallyMuted = false
    var globallyMuted = false
    let pin: () -> Void
    init(tile: ConferenceController.Tile, pinned: Bool, compact: Bool, renderVideo: Bool = true, locallyMuted: Bool = false, globallyMuted: Bool = false, pin: @escaping () -> Void) {
        self.tile = tile; self.pinned = pinned; self.compact = compact; self.renderVideo = renderVideo; self.locallyMuted = locallyMuted; self.globallyMuted = globallyMuted; self.pin = pin
        _participant = ObservedObject(wrappedValue: tile.participant)
    }
    var body: some View {
        Button(action: pin) {
            ZStack(alignment: .bottomLeading) {
                colors.material(hex: 0x191B21)
                if renderVideo, let publication = participant.videoTracks.first(where: { $0.source == .camera }), !publication.isMuted,
                   let track = publication.track as? VideoTrack {
                    ConferenceVideo(track: track, isLocal: tile.isLocal)
                } else {
                    VStack(spacing: 6) {
                        Text(String(tile.name.prefix(1)).uppercased()).font(.system(size: 30, weight: .medium))
                            .foregroundStyle(UnyriColors.primaryText).frame(width: 64, height: 64)
                            .background(colors.role(.categoryBlue).opacity(0.13), in: Circle())
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(8)
                        .padding(.bottom, compact ? 56 : 0)
                }
                HStack(spacing: 4) {
                    if !participant.isMicrophoneEnabled() || globallyMuted { Image(systemName: "mic.slash.fill") }
                    if locallyMuted { Image(systemName: "speaker.slash.fill") }
                    if !renderVideo { Image(systemName: "video.slash.fill") }
                    Text(tile.isLocal ? UnyriL10n.string("You") : tile.name).lineLimit(1)
                    if pinned { Image(systemName: "pin.fill") }
                }.font(.system(size: 12)).foregroundStyle(.white).padding(.horizontal, 8).padding(.vertical, 6)
                    .background(.black.opacity(0.46), in: Capsule()).padding(6)
            }
            .clipShape(RoundedRectangle(cornerRadius: ConferenceRoomMetrics.tileRadius))
            .overlay(RoundedRectangle(cornerRadius: ConferenceRoomMetrics.tileRadius).stroke(participant.isSpeaking && !locallyMuted && !globallyMuted ? colors.role(.categoryBlue) : UnyriColors.outlineSubtle, lineWidth: participant.isSpeaking && !locallyMuted && !globallyMuted ? 2 : 0.5))
        }.buttonStyle(.plain)
            .accessibilityLabel(tile.isLocal ? UnyriL10n.string("You")
                : "\(tile.name), \(participant.isSpeaking ? "speaking" : "participant"). \(pinned ? "Unpin" : "Pin") video")
    }
}
struct ConferenceVideo: UIViewRepresentable {
    let track: VideoTrack
    var isLocal = false
    func makeUIView(context: Context) -> VideoView {
        let view = VideoView(); view.layoutMode = .fill; view.mirrorMode = isLocal ? .auto : .off; view.track = track; return view
    }
    func updateUIView(_ view: VideoView, context: Context) {
        // SDK auto follows front/back switches without changing encoded media.
        // Remote tracks must never inherit the own preview's mirror setting.
        view.mirrorMode = isLocal ? .auto : .off; view.track = track
    }
    static func dismantleUIView(_ view: VideoView, coordinator: ()) { view.track = nil }
}

/// One return surface for both kinds of conversation, with native text sizing.
struct ConversationReturnBanner<Subtitle: View, Accessory: View>: View {
    let title: String
    let systemImage: String
    let onReturn: () -> Void
    let subtitle: Subtitle
    let accessory: Accessory
    @Environment(\.unyriAppColorStyle) private var colors
    init(title: String, systemImage: String, onReturn: @escaping () -> Void,
         @ViewBuilder subtitle: () -> Subtitle, @ViewBuilder accessory: () -> Accessory) {
        self.title = title; self.systemImage = systemImage; self.onReturn = onReturn
        self.subtitle = subtitle(); self.accessory = accessory()
    }
    var body: some View {
        Button(action: onReturn) {
            HStack(spacing: 10) {
                Image(systemName: systemImage).font(.system(size: 20)).foregroundStyle(colors.role(.categoryBlue))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    subtitle.font(.caption2).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                }.frame(maxWidth: .infinity, alignment: .leading)
                accessory
                Image(systemName: "chevron.up").font(.system(size: 14, weight: .semibold))
            }.foregroundStyle(UnyriColors.primaryText).padding(.horizontal, 12).frame(minHeight: 48)
                .background(colors.material(hex: 0x191B21).opacity(0.94), in: RoundedRectangle(cornerRadius: 20))
        }.buttonStyle(.plain).padding(.horizontal, 8).padding(.vertical, 4)
    }
}

/// Lives at the authenticated root, so minimizing preserves the same room and
/// the shared idle-timer lease. No sheet owns audio or camera lifetimes.
struct ConferencePresentation: ViewModifier {
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var wakeOwner = UUID()
    @State private var lobbyDismissed = true
    func body(content: Content) -> some View {
        // A NavigationStack inside TabView can keep its navigation bar at the
        // window's original safe-area origin despite an outer safeAreaInset.
        // Allocate a real sibling row so the whole destination moves below it.
        VStack(spacing: 0) {
                if controller.isBusy && controller.minimized {
                    ConversationReturnBanner(title: controller.conference?.title ?? UnyriL10n.string("Conference"),
                        systemImage: UnyriSymbols.rooms, onReturn: { controller.minimized = false }, subtitle: {
                            Text(LocalizedStringKey(controller.presentationStatus))
                        }, accessory: {
                            Text("\(controller.totalParticipants)").font(.caption).monospacedDigit()
                            Image(systemName: controller.microphoneEnabled ? "mic.fill" : "mic.slash.fill")
                                .foregroundStyle(controller.microphoneEnabled ? colors.role(.categoryBlue) : UnyriColors.secondaryText)
                        })
                        .accessibilityLabel(UnyriL10n.string("Return to conference") + ". " + UnyriL10n.string(controller.presentationStatus)
                            + ". " + UnyriL10n.string(controller.microphoneEnabled ? "Microphone on" : "Microphone off"))
                        .accessibilityIdentifier("conference-return")
                }
            content
        }
            .sheet(isPresented: $controller.showLobby, onDismiss: {
                lobbyDismissed = true
                if !controller.showLobby { controller.offeredInvite = nil }
            }) { ConferenceLobby().environmentObject(controller) }
            .fullScreenCover(isPresented: Binding(get: {
                controller.isBusy && !controller.minimized && !controller.showLobby && lobbyDismissed
            }, set: { if !$0 && controller.isBusy { controller.minimized = true } })) {
                ConferenceScreen().environmentObject(controller)
            }
            .onReceive(NotificationCenter.default.publisher(for: ConferenceController.invitationNotification)) { event in
                if let url = event.object as? URL { controller.receive(url) }
            }
            .onChange(of: controller.showLobby) { if $0 { lobbyDismissed = false } }
            .onAppear {
                controller.setForeground(scenePhase == .active)
                CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: controller.shouldKeepScreenOn && scenePhase == .active, owner: wakeOwner)
            }
            .onChange(of: scenePhase) { controller.setForeground($0 == .active) }
            .onChange(of: controller.shouldKeepScreenOn && scenePhase == .active) { wanted in
                CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: wanted, owner: wakeOwner)
            }
            .onDisappear { CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: false, owner: wakeOwner) }
            .alert("Conference", isPresented: Binding(get: { controller.error != nil && (!controller.isBusy || controller.minimized) && !controller.showLobby },
                set: { if !$0 { controller.error = nil } })) {
                    if controller.error == ConferenceInvite.Failure.ownerLimit.localizedDescription {
                        Button("My rooms") { UnyriHaptics.selection(); controller.error = nil; controller.requestedOwnedRooms = true }
                    }
                    Button("OK") { controller.error = nil }
                }
                message: { Text(LocalizedStringKey(controller.error ?? "")) }
    }
}
