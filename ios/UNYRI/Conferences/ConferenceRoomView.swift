import AVFoundation
import LiveKit
import SwiftUI

private struct ConferenceJoinRequestActions: View {
    let request: ConferenceJoinRequest
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(request.displayName).font(.body.weight(.medium)).lineLimit(2)
            Text("@" + request.username).font(.caption).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 12) { decline; accept }
                } else {
                    HStack(spacing: 16) { decline; accept }
                }
            }.disabled(controller.decidingJoinRequestID != nil || controller.phase != .connected)
        }.accessibilityElement(children: .contain)
    }
    private var decline: some View {
        Button(role: .destructive) {
            UnyriHaptics.selection(); controller.decideJoinRequest(request, approve: false)
        } label: {
            Text("Decline").fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : nil)
                .contentShape(Rectangle())
        }.frame(maxWidth: .infinity, minHeight: 44)
    }
    private var accept: some View {
        Button {
            UnyriHaptics.selection(); controller.decideJoinRequest(request, approve: true)
        } label: {
            Text("Accept").fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : nil)
                .contentShape(Rectangle())
        }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity, minHeight: 44)
    }
}

private struct ConferenceJoinRequestsSheet: View {
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var body: some View {
        NavigationStack {
            List {
                if controller.pendingJoinRequests.isEmpty {
                    Text("No pending join requests").foregroundStyle(.secondary)
                }
                ForEach(controller.pendingJoinRequests) { request in
                    ConferenceJoinRequestActions(request: request).padding(.vertical, 6)
                }
            }.scrollContentBackground(.hidden).background(UnyriColors.background)
                .navigationTitle("Join requests").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }.presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
            .presentationDragIndicator(.visible)
    }
}

/// Remote video is page-scoped; directory rows and prefetched pages never
/// attach SDK renderers. Presentation ownership fences delayed disappearance.
struct ConferenceScreen: View {
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dynamicTypeSize) private var dynamicType
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var presentationOwner = UUID()
    @State private var participants = false
    @State private var confirmEnd = false
    @State private var share: ConferenceInvitationMetadata?
    @State private var routeRevision = 0
    @State private var showErrorDetails = false
    @State private var showLayout = false
    @State private var showRoomSettings = false
    @State private var scrolledVideoIDs = Set<String>()
    @State private var scrollRestorationReady = false
    @State private var removalCandidate: String?
    @State private var showJoinRequests = false

    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height
            let size = visibleCount(landscape: landscape)
            let pages = ConferenceGridPolicy.chunks(controller.stageTiles, count: size)
            let page = min(controller.remotePageIndex(size: size), max(0, pages.count - 1))
            VStack(spacing: 0) {
                header.frame(minHeight: 56).padding(.horizontal, 10)
                    .background(colors.material(hex: 0x191B21).opacity(0.92), in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(UnyriColors.outlineSubtle, lineWidth: 0.5))
                    .padding(.horizontal, 10).padding(.top, 4).padding(.bottom, 8)
                if controller.isOwner, let request = controller.pendingJoinRequests.first {
                    VStack(alignment: .leading, spacing: 4) {
                        Button { UnyriHaptics.selection(); showJoinRequests = true } label: {
                            HStack {
                                Label("Join requests", systemImage: "person.badge.plus")
                                Spacer()
                                Text("\(controller.pendingJoinRequests.count)").monospacedDigit()
                            }
                        }.font(.subheadline.weight(.semibold)).frame(minHeight: 44)
                        ConferenceJoinRequestActions(request: request)
                    }.padding(12).background(colors.material(hex: 0x191B21), in: RoundedRectangle(cornerRadius: 18))
                        .padding(.horizontal, 10).padding(.bottom, 8)
                }
                conferenceStage(landscape: landscape, size: size, pages: pages, page: page)
                if pages.count > 1 && controller.selectedVisibleCount == 0 && !controller.fitEveryoneOnScreen {
                    pager(page: page, count: pages.count, size: size).padding(.horizontal, 10)
                }
                if let error = controller.error {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.circle")
                        Button { showErrorDetails = true } label: {
                            Text(LocalizedStringKey(error)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).accessibilityLabel(UnyriL10n.string(error) + ". " + UnyriL10n.string("Show details"))
                        Button { controller.error = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                            .accessibilityLabel("Dismiss")
                    }.font(.caption).foregroundStyle(UnyriColors.danger).padding(.horizontal, 14)
                }
                dock(landscape: landscape).padding(.horizontal, 10).padding(.top, 8).padding(.bottom, 6)
            }
            .onAppear { controller.beginVideoPresentation(presentationOwner); updateVisible(landscape: landscape) }
            .onChange(of: controller.remotePageAnchorID) { _ in updateVisible(landscape: landscape) }
            .onChange(of: controller.pinnedID) { _ in updateVisible(landscape: landscape) }
            .onChange(of: controller.stageTiles.map(\.id)) { _ in updateVisible(landscape: landscape) }
            .onChange(of: landscape) { _ in updateVisible(landscape: landscape) }
            .onChange(of: dynamicType) { _ in updateVisible(landscape: landscape) }
            .onChange(of: controller.selectedVisibleCount) { _ in scrolledVideoIDs = []; updateVisible(landscape: landscape) }
            .onChange(of: controller.fitEveryoneOnScreen) { _ in scrolledVideoIDs = []; updateVisible(landscape: landscape) }
            .onChange(of: scrolledVideoIDs) { _ in updateVisible(landscape: landscape) }
            .onChange(of: showLayout) { _ in updateVisible(landscape: landscape) }
            .onChange(of: showRoomSettings) { _ in updateVisible(landscape: landscape) }
            .onChange(of: participants) { _ in updateVisible(landscape: landscape) }
            .onChange(of: share?.id) { _ in updateVisible(landscape: landscape) }
            .onChange(of: scenePhase) { _ in updateVisible(landscape: landscape) }
            .onDisappear { controller.endVideoPresentation(presentationOwner) }
        }.background(roomBackground.ignoresSafeArea()).foregroundStyle(UnyriColors.primaryText)
            .tint(colors.role(.categoryBlue))
            .interactiveDismissDisabled()
            .sheet(isPresented: $participants) { participantDirectory.presentationDetents([.medium, .large]).presentationDragIndicator(.visible) }
            .sheet(item: $share) { ConferenceInvitationShareSheet(metadata: $0) }
            .sheet(isPresented: $showLayout) { ConferenceLayoutSettings().presentationDetents([.medium, .large]).presentationDragIndicator(.visible) }
            .sheet(isPresented: $showRoomSettings) { ConferenceOwnerSettings() }
            .sheet(isPresented: $showJoinRequests) { ConferenceJoinRequestsSheet() }
            .modifier(ConferenceRemovalConfirmation(candidate: $removalCandidate))
            .confirmationDialog("End conference for everyone?", isPresented: $confirmEnd, titleVisibility: .visible) {
                Button("End for everyone", role: .destructive) { controller.leave(endForEveryone: true) }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Conference", isPresented: $showErrorDetails) { Button("OK") { controller.error = nil } }
                message: { Text(LocalizedStringKey(controller.error ?? "")) }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { _ in routeRevision += 1 }
    }

    private var roomBackground: Color { UnyriColors.callBackground }

    private func conferenceStage(landscape: Bool, size: Int, pages: [[ConferenceController.Tile]], page: Int) -> some View {
        GeometryReader { grid in
            ZStack {
                if controller.approvalWaiting {
                    VStack(spacing: 16) {
                        ProgressView()
                        Text("Waiting for the room creator…").multilineTextAlignment(.center)
                        Button("Cancel request", role: .cancel) { UnyriHaptics.selection(); controller.leave() }
                            .frame(minHeight: 44).accessibilityIdentifier("conference-cancel-approval")
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).padding()
                } else if ConferenceRoomMetrics.showsSinglePlaceholder(total: controller.stageTiles.count, hasOwnTile: controller.ownTile != nil, cameraEnabled: controller.cameraEnabled) {
                    emptyStage
                } else if pages.isEmpty {
                    emptyStage
                } else if controller.fitEveryoneOnScreen {
                    remotePage(controller.stageTiles, visible: true, landscape: landscape, height: grid.size.height)
                        .accessibilityIdentifier("conference-fit-grid")
                } else if controller.selectedVisibleCount > 0 {
                    scrollingGrid(pages: pages, size: size, landscape: landscape, viewport: grid.size)
                } else {
                    TabView(selection: Binding(get: { page }, set: { controller.selectRemotePage($0, size: size) })) {
                        ForEach(pages.indices, id: \.self) { index in
                            remotePage(pages[index], visible: index == page, landscape: landscape, height: grid.size.height).tag(index)
                        }
                    }.tabViewStyle(.page(indexDisplayMode: .never))
                        .accessibilityIdentifier("conference-paged-grid")
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                .allowsHitTesting(controller.pinnedID == nil)
                .accessibilityHidden(controller.pinnedID != nil)
                .overlay {
                    if let pinned = controller.stageTiles.first(where: { $0.id == controller.pinnedID }) {
                        VStack(spacing: 8) {
                            participantTile(pinned, pinned: true, compact: landscape && dynamicType.isAccessibilitySize)
                                .overlay(alignment: .topTrailing) {
                                    Button { UnyriHaptics.selection(); controller.pinnedID = nil } label: {
                                        Label("Back to grid", systemImage: "square.grid.2x2").font(.caption)
                                            .padding(.horizontal, 10).frame(minHeight: 44)
                                            .background(.black.opacity(0.65), in: Capsule())
                                    }.buttonStyle(.plain).padding(6).accessibilityIdentifier("conference-back-to-grid")
                                }
                            if !pinned.isLocal, let own = controller.ownTile {
                                HStack {
                                    Spacer(minLength: 0)
                                    participantTile(own, compact: true)
                                        .frame(width: landscape ? 112 : 124, height: landscape ? 68 : 90)
                                }.accessibilityIdentifier("conference-own-focus-strip")
                            }
                        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(UnyriColors.callBackground)
                    }
                }
                .onAppear { controller.beginVideoPresentation(presentationOwner) }
        }.padding(.horizontal, 10)
    }
    private func remotePage(_ values: [ConferenceController.Tile], visible: Bool, landscape: Bool, height: CGFloat) -> some View {
        let columns = controller.fitEveryoneOnScreen
            ? min(values.count, landscape ? 6 : 4)
            : (landscape ? min(3, values.count) : (values.count <= 2 ? 1 : 2))
        let rows = (values.count + columns - 1) / columns
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: ConferenceRoomMetrics.gridGap), count: columns), spacing: ConferenceRoomMetrics.gridGap) {
            ForEach(values) { tile in
                Group {
                    if visible {
                        if controller.fitEveryoneOnScreen {
                            participantTile(tile, overview: true)
                        } else {
                            participantTile(tile, compact: landscape && dynamicType.isAccessibilitySize)
                        }
                    } else {
                        VStack(spacing: 6) {
                            Image(systemName: "person.crop.circle.fill").font(.system(size: 32))
                            Text(tile.name).font(.caption).lineLimit(1)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(colors.material(hex: 0x191B21), in: RoundedRectangle(cornerRadius: ConferenceRoomMetrics.tileRadius))
                            .accessibilityHidden(true)
                    }
                }.frame(height: max(controller.fitEveryoneOnScreen ? 1 : 44, (height - CGFloat(rows - 1) * ConferenceRoomMetrics.gridGap) / CGFloat(rows))).clipped()
            }
        }
    }

    private func visibleCount(landscape: Bool) -> Int {
        ConferenceGridPolicy.visibleCount(controller.selectedVisibleCount, total: controller.stageTiles.count,
            fitEveryone: controller.fitEveryoneOnScreen, landscape: landscape, accessibility: dynamicType.isAccessibilitySize)
    }

    private func scrollingGrid(pages: [[ConferenceController.Tile]], size: Int, landscape: Bool, viewport: CGSize) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: ConferenceRoomMetrics.gridGap) {
                    ForEach(pages.indices, id: \.self) { index in
                        let values = pages[index]
                        let columns = landscape ? min(size > 9 ? 6 : 3, size) : min(size > 9 ? 4 : 2, size)
                        let rows = (size + columns - 1) / columns
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: ConferenceRoomMetrics.gridGap), count: columns), spacing: ConferenceRoomMetrics.gridGap) {
                            ForEach(values) { tile in
                                Group {
                                    if scrolledVideoIDs.contains(tile.id) {
                                        if size > 9 {
                                            participantTile(tile, overview: true)
                                        } else {
                                            participantTile(tile, compact: dynamicType.isAccessibilitySize)
                                        }
                                    } else { scrollPlaceholder(tile) }
                                }
                                .frame(height: max(1, (viewport.height - CGFloat(rows - 1) * ConferenceRoomMetrics.gridGap) / CGFloat(rows))).clipped()
                                .accessibilityIdentifier("conference-scroll-" + tile.id)
                                .background(GeometryReader { geometry in
                                    Color.clear.preference(key: ConferenceViewportFrames.self,
                                        value: [tile.id: geometry.frame(in: .named("conference-scroll-viewport"))])
                                })
                            }
                        }.frame(height: max(44, viewport.height), alignment: .top).id(index)
                    }
                }
            }.coordinateSpace(name: "conference-scroll-viewport")
                .accessibilityIdentifier("conference-scroll-grid")
                .onPreferenceChange(ConferenceViewportFrames.self) { frames in
                    let ids = ConferenceGridPolicy.visibleIDs(frames, viewport: CGRect(origin: .zero, size: viewport), limit: size)
                    if !ids.isEmpty, ids != scrolledVideoIDs { scrolledVideoIDs = ids }
                    // Record a stable participant rather than a pixel offset.
                    // Minimize/rotation can restore its section on a new view.
                    if scrollRestorationReady { controller.recordScrollAnchor(ids, owner: presentationOwner) }
                }
                .onAppear {
                    scrollRestorationReady = false
                    proxy.scrollTo(controller.remotePageIndex(size: size), anchor: .top)
                    DispatchQueue.main.async { scrollRestorationReady = true }
                }
                .onDisappear { scrollRestorationReady = false }
                .onChange(of: size) { _ in proxy.scrollTo(controller.remotePageIndex(size: size), anchor: .top) }
        }
    }

    private func scrollPlaceholder(_ tile: ConferenceController.Tile) -> some View {
        VStack(spacing: 4) {
            Image(systemName: "person.crop.circle.fill").font(.system(size: 28))
            Text(tile.name).font(.caption).lineLimit(1)
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(colors.material(hex: 0x191B21), in: RoundedRectangle(cornerRadius: ConferenceRoomMetrics.tileRadius))
    }


    private var header: some View {
        HStack(spacing: 8) {
            Button { UnyriHaptics.selection(); controller.minimized = true } label: {
                Image(systemName: "chevron.down").font(.system(size: 19, weight: .medium))
                    .frame(width: 44, height: 44).background(colors.material(hex: 0x292C34), in: Circle())
            }.buttonStyle(UnyriQuietButtonStyle()).accessibilityLabel("Minimize conference")
            VStack(alignment: .leading, spacing: 2) {
                Text(controller.conference?.title ?? UnyriL10n.string("Conference")).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: "lock.shield.fill").foregroundStyle(UnyriColors.secondaryText)
                    Text(LocalizedStringKey(controller.presentationStatus)).lineLimit(1)
                }.font(.system(size: 12)).foregroundStyle(UnyriColors.secondaryText)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button { UnyriHaptics.selection(); participants = true } label: {
                HStack(spacing: 4) { Image(systemName: UnyriSymbols.rooms); Text("\(controller.totalParticipants)").monospacedDigit() }
                    .font(.caption.weight(.semibold)).padding(.horizontal, 10).frame(minWidth: 44, minHeight: 44)
                    .background(colors.material(hex: 0x292C34), in: Capsule())
            }.buttonStyle(.plain).accessibilityLabel("\(controller.totalParticipants) participants")
        }.contentShape(Rectangle()).simultaneousGesture(DragGesture(minimumDistance: 28).onEnded { value in
            if value.translation.height > 60 && value.translation.height > abs(value.translation.width) { controller.minimized = true }
        })
    }

    private var emptyStage: some View {
        ConferenceIdleStage(name: controller.ownTile?.name,
            title: controller.phase == .connected ? "You are in the room" : controller.presentationStatus,
            canInvite: controller.phase == .connected) { UnyriHaptics.selection(); presentInvitation() }
            .accessibilityIdentifier("conference-single-listener-stage")
    }

    private func pager(page: Int, count: Int, size: Int) -> some View {
        HStack(spacing: 8) {
            Button { controller.pinnedID = nil; controller.selectRemotePage(page - 1, size: size) } label: {
                Image(systemName: "chevron.left").frame(width: 44, height: 44).contentShape(Rectangle())
            }.disabled(page == 0).accessibilityLabel("Previous participants")
            VStack(spacing: 0) {
                Text("\(page + 1) / \(count)").font(.caption.weight(.semibold)).monospacedDigit()
                Text("\(page * size + 1)–\(min((page + 1) * size, controller.stageTiles.count)) / \(controller.stageTiles.count)")
                    .font(.system(size: 10)).foregroundStyle(UnyriColors.secondaryText).monospacedDigit()
            }.frame(maxWidth: .infinity).accessibilityElement(children: .combine)
            Button { controller.pinnedID = nil; controller.selectRemotePage(page + 1, size: size) } label: {
                Image(systemName: "chevron.right").frame(width: 44, height: 44).contentShape(Rectangle())
            }.disabled(page + 1 == count).accessibilityLabel("Next participants")
        }.buttonStyle(.plain).frame(minHeight: 44).accessibilityIdentifier("conference-page-controls")
    }

    @ViewBuilder private func participantTile(_ tile: ConferenceController.Tile, pinned: Bool = false,
                                               compact: Bool = false, overview: Bool = false) -> some View {
        let videoVisible = !controller.locallyHiddenParticipantIDs.contains(tile.id)
        let globallyMuted = tile.isLocal ? controller.microphoneBlockedByOwner : controller.isParticipantAudioBlocked(tile.id)
        Group {
            if overview {
                ConferenceOverviewTile(tile: tile, renderVideo: videoVisible,
                                       locallyMuted: controller.locallyMutedParticipantIDs.contains(tile.id), globallyMuted: globallyMuted) {
                    UnyriHaptics.selection(); controller.focusParticipant(tile.id)
                }
            } else {
                ConferenceTile(tile: tile, pinned: pinned, compact: compact, renderVideo: videoVisible,
                               locallyMuted: controller.locallyMutedParticipantIDs.contains(tile.id), globallyMuted: globallyMuted) {
                    UnyriHaptics.selection()
                    if pinned { controller.pinnedID = nil } else { controller.focusParticipant(tile.id) }
                }
            }
        }.contextMenu { ConferenceParticipantActions(tile: tile) { removalCandidate = $0 } }
            .accessibilityIdentifier(tile.isLocal ? "conference-own-grid-tile" : (overview ? "conference-overview-" : "conference-grid-") + tile.id)
    }

    private func dock(landscape: Bool) -> some View {
        let labels = !landscape && !dynamicType.isAccessibilitySize
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: 4) { microphone(labels); camera(labels); audio(labels); invite(labels); more(labels); leave(labels) }
            VStack(spacing: 6) {
                HStack(spacing: 4) { microphone(false); camera(false); audio(false) }
                HStack(spacing: 4) { invite(false); more(false); leave(false) }
            }
        }.padding(8).background(colors.material(hex: 0x191B21).opacity(0.94), in: RoundedRectangle(cornerRadius: ConferenceRoomMetrics.panelRadius))
            .overlay(RoundedRectangle(cornerRadius: ConferenceRoomMetrics.panelRadius).stroke(UnyriColors.outlineSubtle, lineWidth: 0.5))
    }
    private var mediaEnabled: Bool { controller.phase == .connected && !controller.mediaChanging }
    private func microphone(_ labels: Bool) -> some View {
        compactButton(controller.microphoneBlockedByOwner ? "Microphone disabled by the room creator. Tap for details." : controller.microphoneEnabled ? "Mute microphone" : "Enable microphone", label: "Mic", icon: controller.microphoneEnabled ? "mic.fill" : "mic.slash.fill",
            tint: controller.microphoneEnabled ? colors.role(.categoryBlue) : UnyriColors.secondaryText,
            labels: labels, enabled: mediaEnabled || (controller.phase == .reconnecting && controller.microphoneEnabled), locked: controller.microphoneBlockedByOwner) {
                controller.toggleMicrophone()
                if controller.microphoneBlockedByOwner { showErrorDetails = true }
            }
    }
    private func camera(_ labels: Bool) -> some View {
        compactButton(controller.cameraEnabled ? "Disable camera" : "Enable camera", label: "Camera", icon: controller.cameraEnabled ? "video.fill" : "video.slash.fill",
            tint: controller.cameraEnabled ? colors.role(.categoryBlue) : UnyriColors.secondaryText,
            labels: labels, enabled: mediaEnabled || (controller.phase == .reconnecting && controller.cameraEnabled)) { controller.toggleCamera() }
    }
    private func invite(_ labels: Bool) -> some View {
        compactButton("Invite participants", label: "Invite", icon: "person.badge.plus", tint: UnyriColors.secondaryText, labels: labels,
            enabled: controller.phase == .connected) { presentInvitation() }
            .accessibilityIdentifier("conference-invite-participants")
    }
    private func presentInvitation() {
        share = controller.invitationMetadata()
        if share == nil {
            controller.error = "Catalog entry does not include an invitation. Ask an invited participant to share a link."
            showErrorDetails = true
        }
    }
    private func leave(_ labels: Bool) -> some View {
        compactButton("Leave conference", label: "Leave", icon: "phone.down.fill", tint: .white, labels: labels,
            fill: UnyriColors.danger, enabled: controller.phase != .leaving) { controller.leave() }
            .accessibilityIdentifier("conference-leave")
    }
    private func audio(_ labels: Bool) -> some View {
        Menu {
            Button { route(speaker: true, input: nil) } label: { Label("Loudspeaker", systemImage: currentSpeaker ? "checkmark" : "speaker.wave.2") }
            Button { route(speaker: false, input: nil) } label: { Label("Phone earpiece", systemImage: currentReceiver ? "checkmark" : "ear") }
            ForEach(externalAudioInputs, id: \.uid) { input in
                Button { route(speaker: false, input: input) } label: {
                    Label(input.portName, systemImage: AVAudioSession.sharedInstance().currentRoute.inputs.contains(where: { $0.uid == input.uid }) ? "checkmark" : "headphones")
                }
            }
        } label: { dockLabel("Audio", icon: "speaker.wave.2.fill", tint: UnyriColors.secondaryText, labels: labels) }
            .disabled(!mediaEnabled).id(routeRevision)
            .accessibilityLabel(UnyriL10n.string("Audio route") + ": " + AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: ", "))
    }
    private func more(_ labels: Bool) -> some View {
        Menu {
            Section("Conference") {
                Button { UnyriHaptics.selection(); controller.switchCamera() } label: { Label("Switch camera", systemImage: "arrow.triangle.2.circlepath.camera") }
                    .disabled(!mediaEnabled || !controller.cameraEnabled)
                Button { UnyriHaptics.selection(); participants = true } label: { Label("Participants", systemImage: UnyriSymbols.rooms) }
                Button { UnyriHaptics.selection(); showLayout = true } label: { Label("Grid layout", systemImage: "square.grid.2x2") }
                    .accessibilityIdentifier("conference-layout-entry")
            }
            if let link = controller.activeInvite {
                Section("Invitation") {
                    Button { UnyriHaptics.selection(); UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: link.absoluteString]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]) }
                        label: { Label("Copy invitation", systemImage: "link") }
                    if let parsed = try? ConferenceInvite.parse(link.absoluteString, currentServer: controller.api.server) {
                        Button { UnyriHaptics.selection(); UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: parsed.legacyLink.absoluteString]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]) }
                            label: { Label("Copy full invitation", systemImage: "doc.on.doc") }
                    }
                }
            }
            if controller.isOwner {
                Section("Room controls") {
                    if controller.hostControlsAvailable {
                        Button { UnyriHaptics.selection(); showRoomSettings = true } label: { Label("Room settings", systemImage: "slider.horizontal.3") }
                            .disabled(controller.phase != .connected)
                    }
                    Button(role: .destructive) { UnyriHaptics.selection(); confirmEnd = true } label: { Label("End for everyone", systemImage: "phone.down.circle") }
                        .disabled(controller.phase != .connected && controller.phase != .reconnecting)
                }
            }
        } label: { dockLabel("More", icon: "ellipsis", tint: UnyriColors.secondaryText, labels: labels) }
            .accessibilityLabel("More conference actions")
    }
    private func compactButton(_ accessibility: String, label: String = "", icon: String, tint: Color,
                               labels: Bool = false, fill: Color? = nil, enabled: Bool = true, locked: Bool = false, action: @escaping () -> Void) -> some View {
        Button { UnyriHaptics.selection(); action() } label: { dockLabel(label, icon: icon, tint: tint, labels: labels, fill: fill, locked: locked) }
            .buttonStyle(UnyriQuietButtonStyle()).disabled(!enabled).accessibilityLabel(LocalizedStringKey(accessibility))
    }
    private func dockLabel(_ label: String, icon: String, tint: Color, labels: Bool, fill: Color? = nil, locked: Bool = false) -> some View {
        VStack(spacing: 3) {
            Image(systemName: icon).font(.system(size: ConferenceRoomMetrics.controlIcon, weight: .medium)).foregroundStyle(tint)
                .frame(width: ConferenceRoomMetrics.controlSize, height: ConferenceRoomMetrics.controlSize)
                .background(fill ?? colors.material(hex: 0x292C34), in: Circle())
                .overlay(alignment: .topTrailing) {
                    if locked {
                        Image(systemName: "lock.fill").font(.system(size: 10)).foregroundStyle(UnyriColors.secondaryText)
                            .frame(width: 15, height: 15).background(UnyriColors.callBackground, in: Circle())
                            .accessibilityHidden(true).accessibilityIdentifier("conference-microphone-host-lock")
                    }
                }
            if labels { Text(LocalizedStringKey(label)).font(.system(size: 11)).lineLimit(1).minimumScaleFactor(0.8).foregroundStyle(UnyriColors.secondaryText) }
        }.frame(minWidth: ConferenceRoomMetrics.controlSize, maxWidth: .infinity, minHeight: ConferenceRoomMetrics.controlSize).contentShape(Rectangle())
    }
    private var currentSpeaker: Bool { AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .builtInSpeaker } }
    private var currentReceiver: Bool { AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .builtInReceiver } }
    private var externalAudioInputs: [AVAudioSessionPortDescription] {
        // availableInputs also contains the phone's built-in microphone. It
        // is not a third output; only attached communication devices belong here.
        (AVAudioSession.sharedInstance().availableInputs ?? []).filter {
            switch $0.portType {
            case .headsetMic, .bluetoothHFP, .usbAudio, .carAudio: return true
            default: return false
            }
        }
    }
    private func route(speaker: Bool, input: AVAudioSessionPortDescription?) {
        guard controller.phase == .connected else { return }
        do {
            let audio = AVAudioSession.sharedInstance()
            AudioManager.shared.isSpeakerOutputPreferred = speaker
            try audio.setPreferredInput(input ?? audio.availableInputs?.first(where: { $0.portType == .builtInMic }))
            try audio.overrideOutputAudioPort(speaker ? .speaker : .none)
        } catch { controller.error = "Could not change the audio route." }
    }
    private func updateVisible(landscape: Bool) {
        // A room sheet is not a background transition. Keep the displayed page
        // and its native renderers ready beneath sheets and focused video.
        let active = scenePhase == .active
        if controller.selectedVisibleCount > 0 && !controller.fitEveryoneOnScreen {
            controller.presentVideo(ConferenceRoomMetrics.videoDemand(grid: scrolledVideoIDs, focused: controller.pinnedID), owner: presentationOwner, active: active); return
        }
        if controller.fitEveryoneOnScreen {
            controller.presentVideo(ConferenceRoomMetrics.videoDemand(grid: Set(controller.stageTiles.map(\.id)), focused: controller.pinnedID), owner: presentationOwner, active: active); return
        }
        let size = ConferenceGridPolicy.pageSize(landscape: landscape, accessibility: dynamicType.isAccessibilitySize)
        let pages = ConferenceGridPolicy.pages(controller.stageTiles, landscape: landscape, accessibility: dynamicType.isAccessibilitySize)
        let page = controller.remotePageIndex(size: size)
        controller.presentVideo(ConferenceRoomMetrics.videoDemand(grid: pages.indices.contains(page) ? Set(pages[page].map(\.id)) : [], focused: controller.pinnedID), owner: presentationOwner, active: active)
    }
    private var participantDirectory: some View {
        ConferenceParticipantDirectory { id in UnyriHaptics.selection(); controller.focusParticipant(id); participants = false }
    }
}

private struct ConferenceViewportFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue(), uniquingKeysWith: { _, new in new }) }
}

/// Explicit overview uses small tiles; directory remains the large-text and
/// 44-point-target route to every participant. SDK adaptiveStream requests the
/// actual tiny renderer dimensions rather than 29 full-resolution streams.
private struct ConferenceOverviewTile: View {
    let tile: ConferenceController.Tile
    let focus: () -> Void
    let renderVideo: Bool
    let locallyMuted: Bool
    let globallyMuted: Bool
    @ObservedObject private var participant: Participant
    @Environment(\.unyriAppColorStyle) private var colors
    init(tile: ConferenceController.Tile, renderVideo: Bool = true, locallyMuted: Bool = false, globallyMuted: Bool = false, focus: @escaping () -> Void) {
        self.tile = tile; self.renderVideo = renderVideo; self.locallyMuted = locallyMuted; self.globallyMuted = globallyMuted; self.focus = focus; _participant = ObservedObject(wrappedValue: tile.participant)
    }
    var body: some View {
        Button(action: focus) {
            GeometryReader { geometry in
                ZStack(alignment: .bottomLeading) {
                    colors.material(hex: 0x191B21)
                    if renderVideo, let publication = participant.videoTracks.first(where: { $0.source == .camera }), !publication.isMuted,
                       let track = publication.track as? VideoTrack { ConferenceVideo(track: track, isLocal: tile.isLocal) }
                    else { Image(systemName: "person.fill").font(.system(size: min(24, geometry.size.height * 0.5)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity) }
                    if geometry.size.height > 28 {
                        HStack(spacing: 2) {
                            if !participant.isMicrophoneEnabled() || globallyMuted { Image(systemName: "mic.slash.fill") }
                            if locallyMuted { Image(systemName: "speaker.slash.fill") }
                            if !renderVideo { Image(systemName: "video.slash.fill") }
                            Text(tile.isLocal ? UnyriL10n.string("You") : tile.name).lineLimit(1)
                        }.font(.system(size: 9)).padding(3).background(.black.opacity(0.6))
                    }
                }.clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(participant.isSpeaking && !locallyMuted && !globallyMuted ? colors.role(.categoryBlue) : .clear, lineWidth: 1))
            }
        }.buttonStyle(.plain).accessibilityLabel(tile.isLocal ? UnyriL10n.string("You") : tile.name + ". " + UnyriL10n.string("Pin video"))
            .accessibilityIdentifier("conference-overview-" + tile.id)
    }
}

private struct ConferenceLayoutSettings: View {
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Fit everyone on screen", isOn: $controller.fitEveryoneOnScreen)
                        .accessibilityIdentifier("conference-fit-everyone")
                    if !controller.fitEveryoneOnScreen {
                        Toggle("Automatic grid", isOn: Binding(get: { controller.selectedVisibleCount == 0 }, set: { controller.selectedVisibleCount = $0 ? 0 : 6 }))
                            .accessibilityIdentifier("conference-auto-grid")
                        if controller.selectedVisibleCount > 0 {
                            LabeledContent("Visible participants", value: "\(controller.selectedVisibleCount)")
                            Slider(value: Binding(get: { Double(controller.selectedVisibleCount) },
                                set: { controller.selectedVisibleCount = Int($0.rounded()) }), in: 1...30, step: 1)
                                .accessibilityLabel("Visible participants").accessibilityValue("\(controller.selectedVisibleCount)")
                                .accessibilityIdentifier("conference-visible-count")
                            HStack {
                                Button { controller.selectedVisibleCount = max(1, controller.selectedVisibleCount - 1) } label: { Image(systemName: "minus").frame(width: 44, height: 44) }
                                    .accessibilityLabel("Fewer visible participants")
                                Spacer()
                                Button { controller.selectedVisibleCount = min(30, controller.selectedVisibleCount + 1) } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
                                    .accessibilityLabel("More visible participants")
                            }.buttonStyle(.plain)
                        }
                    }
                } footer: {
                    Text(controller.fitEveryoneOnScreen
                        ? "Everyone stays visible in a compact overview. Tap a person to enlarge their video."
                        : "Choose how many people fit in the stage. Scroll down for the others. This only changes your screen.")
                }
            }.scrollContentBackground(.hidden).background(UnyriColors.background)
                .navigationTitle("Grid layout").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct ConferenceOwnerSettings: View {
    @EnvironmentObject private var controller: ConferenceController
    var body: some View { ConferenceConfigurationEditor(existing: controller.conference) }
}

private struct ConferenceParticipantDirectory: View {
    let focus: (String) -> Void
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var query = ""
    @State private var removalCandidate: String?
    private var filtered: [ConferenceController.Tile] { controller.tiles.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) } }
    var body: some View {
        NavigationStack {
            List {
                ForEach(filtered) { tile in
                    ConferenceParticipantRow(tile: tile, own: tile.id == controller.ownTile?.id, focus: focus,
                                             requestRemoval: { removalCandidate = $0 })
                        .listRowBackground(colors.material(hex: 0x171F2D))
                }
            }.scrollContentBackground(.hidden).background(UnyriColors.background).searchable(text: $query, prompt: "Search participants")
                .navigationTitle(UnyriL10n.string("Participants") + " · \(controller.totalParticipants)")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                .modifier(ConferenceRemovalConfirmation(candidate: $removalCandidate))
        }
    }
}

private struct ConferenceParticipantRow: View {
    let tile: ConferenceController.Tile
    let own: Bool
    let focus: (String) -> Void
    let requestRemoval: (String) -> Void
    @Environment(\.unyriAppColorStyle) private var colors
    @EnvironmentObject private var controller: ConferenceController
    private var globallyMuted: Bool { own ? controller.microphoneBlockedByOwner : controller.isParticipantAudioBlocked(tile.id) }
    private var accessibility: String {
        let microphone = UnyriL10n.string(tile.participant.isMicrophoneEnabled() && !globallyMuted ? "Microphone on" : "Microphone off")
        let camera = UnyriL10n.string(tile.participant.isCameraEnabled() ? "Camera on" : "Camera off")
        return tile.name + ", " + microphone + ", " + camera
    }
    var body: some View {
        HStack(spacing: 10) {
            Button { if !own { focus(tile.id) } } label: {
                HStack(spacing: 10) {
                Image(systemName: "person.crop.circle.fill").font(.system(size: 32)).foregroundStyle(UnyriColors.secondaryText)
                VStack(alignment: .leading, spacing: 3) {
                    Text(tile.name).font(.subheadline).foregroundStyle(UnyriColors.primaryText)
                    if own { Text("You").font(.caption).foregroundStyle(UnyriColors.secondaryText) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: tile.participant.isMicrophoneEnabled() && !globallyMuted ? "mic.fill" : "mic.slash.fill")
                    .foregroundStyle(UnyriColors.secondaryText)
                if controller.locallyMutedParticipantIDs.contains(tile.id) { Image(systemName: "speaker.slash.fill").foregroundStyle(UnyriColors.secondaryText) }
                Image(systemName: tile.participant.isCameraEnabled() && !controller.locallyHiddenParticipantIDs.contains(tile.id) ? "video.fill" : "video.slash.fill")
                    .foregroundStyle(UnyriColors.secondaryText)
                }.frame(minHeight: 48).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("conference-participant-" + tile.id)
                .accessibilityLabel(accessibility)
            Menu { ConferenceParticipantActions(tile: tile, requestRemoval: requestRemoval) } label: {
                Image(systemName: "ellipsis").font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(UnyriColors.secondaryText).frame(width: 44, height: 44).contentShape(Rectangle())
            }.accessibilityLabel(UnyriL10n.string("Participant actions") + ". " + tile.name)
                .accessibilityIdentifier("conference-participant-actions-" + tile.id)
        }
    }
}

/// Native menus keep actions discoverable without adding another overlay or
/// changing the participant's actual microphone/camera on another device.
private struct ConferenceParticipantActions: View {
    let tile: ConferenceController.Tile
    let requestRemoval: (String) -> Void
    @EnvironmentObject private var controller: ConferenceController
    var body: some View {
        if tile.isLocal {
            Button { UnyriHaptics.selection(); controller.toggleMicrophone() } label: {
                Label(controller.microphoneEnabled ? "Mute microphone" : "Enable microphone",
                      systemImage: controller.microphoneEnabled ? "mic.slash" : "mic")
            }.disabled(controller.phase != .connected || controller.mediaChanging || controller.microphoneBlockedByOwner)
            Button { UnyriHaptics.selection(); controller.toggleCamera() } label: {
                Label(controller.cameraEnabled ? "Disable camera" : "Enable camera",
                      systemImage: controller.cameraEnabled ? "video.slash" : "video")
            }.disabled(controller.phase != .connected || controller.mediaChanging)
        } else {
            Button {
                UnyriHaptics.selection()
                if controller.pinnedID == tile.id { controller.pinnedID = nil } else { controller.focusParticipant(tile.id) }
            } label: {
                Label(controller.pinnedID == tile.id ? "Unpin video" : "Pin video",
                      systemImage: controller.pinnedID == tile.id ? "pin.slash" : "pin")
            }
            Section("Only for me") {
                Button { UnyriHaptics.selection(); controller.toggleLocalAudio(tile.id) } label: {
                    Label(controller.locallyMutedParticipantIDs.contains(tile.id) ? "Hear participant" : "Mute participant for me",
                          systemImage: controller.locallyMutedParticipantIDs.contains(tile.id) ? "speaker.wave.2" : "speaker.slash")
                }
                Button { UnyriHaptics.selection(); controller.toggleLocalVideo(tile.id) } label: {
                    Label(controller.locallyHiddenParticipantIDs.contains(tile.id) ? "Show participant video" : "Hide participant video",
                          systemImage: controller.locallyHiddenParticipantIDs.contains(tile.id) ? "video" : "video.slash")
                }
            }
            if controller.isOwner, controller.participantModerationAvailable,
               let member = controller.conference?.participants.first(where: { $0.participantIdentity == tile.id }),
               member.userId != controller.conference?.ownerUserId {
                Section("Room controls") {
                    Button {
                        UnyriHaptics.selection()
                        Task { _ = await controller.moderateParticipant(tile.id, audioBlocked: !(member.audioPublishBlocked ?? false)) }
                    } label: {
                        Label(member.audioPublishBlocked == true ? "Allow microphone" : "Mute for everyone",
                              systemImage: member.audioPublishBlocked == true ? "mic" : "mic.slash")
                    }.disabled(controller.phase != .connected || controller.moderatingParticipantID != nil || controller.changingRoomSettings)
                    Button(role: .destructive) { UnyriHaptics.selection(); requestRemoval(tile.id) } label: {
                        Label("Remove from room", systemImage: "person.crop.circle.badge.minus")
                    }.disabled(controller.phase != .connected || controller.moderatingParticipantID != nil || controller.changingRoomSettings)
                }
            }
        }
    }
}

/// A listening-only room still has useful actions. Scroll just this card on
/// compact landscape / large type; the fixed Leave dock remains reachable.
private struct ConferenceIdleStage: View {
    let name: String?
    let title: String
    let canInvite: Bool
    let invite: () -> Void
    @Environment(\.unyriAppColorStyle) private var colors

    var body: some View {
        GeometryReader { viewport in
            ScrollView {
                VStack(spacing: 12) {
                    ZStack {
                        Circle().fill(colors.material(hex: 0x292C34))
                        if let name {
                            Text(String(name.prefix(1)).uppercased()).font(.system(size: 30, weight: .medium))
                        } else { Image(systemName: UnyriSymbols.rooms).font(.system(size: 28)) }
                    }.frame(width: 72, height: 72).foregroundStyle(UnyriColors.primaryText)
                        .overlay(Circle().stroke(UnyriColors.outlineSubtle, lineWidth: 0.5))
                    Text(LocalizedStringKey(title)).font(.title3.weight(.semibold))
                    Text("Invite people from your chats or share an invitation.").font(.subheadline)
                        .foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
                    Button(action: invite) {
                        Label("Invite participants", systemImage: "person.badge.plus").frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.borderedProminent).tint(colors.role(.categoryBlue))
                        .foregroundStyle(Color(hex: 0x101820)).disabled(!canInvite)
                }.padding(22).frame(maxWidth: 360)
                    .background(colors.material(hex: 0x191B21).opacity(0.90), in: RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24).stroke(UnyriColors.outlineSubtle, lineWidth: 0.5))
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, minHeight: viewport.size.height)
            }.scrollIndicators(.hidden)
        }
    }
}

private struct ConferenceRemovalConfirmation: ViewModifier {
    @Binding var candidate: String?
    @EnvironmentObject private var controller: ConferenceController
    func body(content: Content) -> some View {
        content.confirmationDialog("Remove participant from this room?", isPresented: Binding(
            get: { candidate != nil }, set: { if !$0 { candidate = nil } }), titleVisibility: .visible) {
            if let identity = candidate {
                Button("Remove from room", role: .destructive) {
                    candidate = nil
                    Task { _ = await controller.moderateParticipant(identity, audioBlocked: nil) }
                }
            }
                Button("Cancel", role: .cancel) { candidate = nil }
            } message: {
                Text("This account will be disconnected and cannot return to this room with the existing invitation.")
            }
    }
}
