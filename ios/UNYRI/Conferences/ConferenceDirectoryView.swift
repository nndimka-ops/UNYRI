import SwiftUI

/// A normal root destination. Browsing and waiting for a key own no media lease.
struct ConferenceDirectoryView: View {
    enum SectionChoice: String, CaseIterable { case server, mine }
    @EnvironmentObject private var controller: ConferenceController
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var selected = SectionChoice.server
    @State private var query = ""
    @State private var rooms: [ConferenceDirectoryRoom] = []
    @State private var owned: [ConferenceDTO] = []
    @State private var nextCursor: String?
    @State private var loading = false
    @State private var failed = false
    @State private var loadedOwned = false
    @State private var capabilitiesReady = false
    @State private var loadOwner = UUID()
    @State private var pagination: Task<Void, Never>?
    @State private var create = false
    @State private var joining: ConferenceDirectoryRoom?
    @State private var invitationAfterCatalog = false
    @State private var ending: ConferenceDTO?
    @State private var busyEnd: UUID?
    @State private var visible = false
    private var filteredOwned: [ConferenceDTO] {
        owned.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || ($0.description ?? "").localizedCaseInsensitiveContains(query) }
    }
    private func openDirectoryRoom(_ room: ConferenceDirectoryRoom) {
        UnyriHaptics.selection()
        if let currentID = controller.conference?.id, currentID == room.id, controller.isBusy {
            controller.minimized = false
        } else if controller.allowNewRoomAction() {
            if !controller.rejoinSavedDirectoryRoom(room) { joining = room }
        }
    }
    private var endConfirmationPresented: Binding<Bool> {
        Binding<Bool>(get: { self.ending != nil }, set: { (presented: Bool) in
            if !presented { self.ending = nil }
        })
    }
    private var baseRoomList: some View {
        roomList
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden).background(UnyriColors.background)
            .navigationTitle("Rooms").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Search rooms")
            .onChange(of: query) { query = String($0.prefix(80)) }
    }
    private var navigationRoomList: some View {
        baseRoomList
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { UnyriMenuButton() }
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 0) {
                        Button { UnyriHaptics.selection(); if controller.allowNewRoomAction() { controller.offeredInvite = nil; controller.showLobby = true } } label: {
                            Image(systemName: "link").frame(width: 44, height: 44)
                        }.accessibilityLabel("Join by invitation").accessibilityIdentifier("rooms-join-invitation")
                        Button { UnyriHaptics.selection(); if controller.allowNewRoomAction() { create = true } } label: {
                            Image(systemName: "plus").frame(width: 44, height: 44)
                        }.accessibilityLabel("Create room").accessibilityIdentifier("rooms-create")
                    }
                        .disabled(!capabilitiesReady)
                }
            }
            .tint(colors.role(.categoryBlue))
            .refreshable { await load(reset: true) }
    }
    private var loadingRoomList: some View {
        navigationRoomList
            .task {
                visible = true
                await controller.refreshCapabilities()
                guard !Task.isCancelled, controller.api.isCurrentSession else { return }
                capabilitiesReady = true
                if !controller.directoryAvailable || controller.requestedOwnedRooms { selected = .mine; controller.requestedOwnedRooms = false }
            }
            .task(id: selected.rawValue + ":" + query + ":" + String(capabilitiesReady)) {
                pagination?.cancel(); loadOwner = UUID()
                if selected == .server { rooms = []; nextCursor = nil }
                do { try await Task.sleep(nanoseconds: 300_000_000) } catch { return }
                guard visible, capabilitiesReady, !Task.isCancelled else { return }
                if selected == .server || !loadedOwned { await load(reset: true) }
            }
            .onDisappear { visible = false; loadOwner = UUID(); pagination?.cancel(); pagination = nil; controller.keyHandoff.cancel() }
            .onChange(of: auth.sessionGeneration) { _ in rooms = []; owned = []; nextCursor = nil; loadOwner = UUID(); pagination?.cancel(); controller.keyHandoff.cancel() }
    }
    private var sessionRoomList: some View {
        loadingRoomList
            .onChange(of: controller.requestedOwnedRooms) { value in
                if value { selected = .mine; loadedOwned = false; controller.requestedOwnedRooms = false; Task { await load(reset: true) } }
            }
            .onChange(of: controller.phase) { phase in
                conferencePhaseChanged(phase)
            }
            .sheet(isPresented: $create) { ConferenceConfigurationEditor() }
            .sheet(item: $joining, onDismiss: {
                guard invitationAfterCatalog else { return }
                invitationAfterCatalog = false
                guard controller.allowNewRoomAction() else { return }
                controller.offeredInvite = nil; controller.showLobby = true
            }) { room in
                ConferenceCatalogJoinSheet(room: room) {
                    invitationAfterCatalog = true; joining = nil
                }
            }
    }
    var body: some View {
        NavigationStack {
            sessionRoomList
            .confirmationDialog("End this room for everyone?", isPresented: endConfirmationPresented, titleVisibility: .visible) {
                if let room = ending {
                    Button("End for everyone", role: .destructive) { ending = nil; Task { await finish(room) } }
                }
                Button("Cancel", role: .cancel) { ending = nil }
            } message: { Text("Everyone in this room will be disconnected. This cannot be undone.") }
        }
        .unyriBlocksRootDrawer(create || joining != nil || ending != nil)
    }
    private func conferencePhaseChanged(_ phase: ConferenceController.Phase) {
        guard phase == ConferenceController.Phase.idle else { return }
        guard visible else { return }
        loadedOwned = false
        // Creation, leaving and ending can change the currently displayed
        // public directory too. Refresh that tab without requiring a tab flip.
        Task { await load(reset: true) }
    }
    private func directoryButton(_ room: ConferenceDirectoryRoom) -> some View {
        Button { openDirectoryRoom(room) } label: {
            ConferenceDirectoryRow(room: room)
        }
        .buttonStyle(.plain)
        .listRowBackground(UnyriColors.elevatedBackground.opacity(0.8))
        .accessibilityIdentifier("room-directory-" + room.id.uuidString.lowercased())
    }
    private var roomList: some View {
        List {
            Section {
                Picker("Rooms", selection: $selected) {
                    Text("On this server").tag(SectionChoice.server)
                    Text("Mine").tag(SectionChoice.mine)
                }.pickerStyle(.segmented)
                    .accessibilityIdentifier("rooms-directory-tabs")
            }.listRowBackground(Color.clear)
            if controller.isBusy {
                Button { UnyriHaptics.selection(); controller.minimized = false } label: {
                    Label("Return to conference", systemImage: "arrow.up.right.video").frame(minHeight: 44)
                }
                Text("You are already in a room. Leave it before starting another conversation.")
                    .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
            }
            ConferenceHandoffStatus(model: controller.keyHandoff) { joining = $0 }
            if loading && rooms.isEmpty && (selected == .server || owned.isEmpty) {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
            if failed {
                Section {
                    Text("Could not load rooms. Try again.").foregroundStyle(UnyriColors.secondaryText)
                    Button("Retry") { UnyriHaptics.selection(); Task { await load(reset: true) } }.frame(minHeight: 44)
                }
            }
            if selected == .server {
                if capabilitiesReady && !controller.directoryAvailable {
                    Label("Rooms directory is unavailable on this server.", systemImage: "server.rack")
                        .foregroundStyle(UnyriColors.secondaryText)
                } else {
                    ForEach(rooms) { room in
                        directoryButton(room)
                    }
                    if !loading && !failed && rooms.isEmpty && controller.directoryAvailable {
                        emptyRooms(mine: false)
                    }
                    if nextCursor != nil {
                        Button("Load more") {
                            UnyriHaptics.selection(); pagination?.cancel()
                            pagination = Task { await load(reset: false) }
                        }.frame(minHeight: 44).disabled(loading)
                    }
                }
            } else {
                Section {
                    if capabilitiesReady && !controller.ownedRoomsAvailable {
                        Text("Create a private room or join using a complete invitation link.").font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                    }
                    ForEach(filteredOwned) { room in ownedRow(room) }
                    if loadedOwned && filteredOwned.isEmpty && controller.ownedRoomsAvailable {
                        emptyRooms(mine: true)
                    }
                } footer: { Text("You can have three active rooms. Rooms close automatically after 24 hours.") }
            }

        }
    }
    private func emptyRooms(mine: Bool) -> some View {
        VStack(spacing: 12) {
            Image(systemName: UnyriSymbols.rooms).font(.system(size: 28, weight: .regular))
                .foregroundStyle(colors.role(.categoryBlue)).frame(width: 56, height: 56)
                .background(colors.role(.categoryBlue).opacity(0.10), in: RoundedRectangle(cornerRadius: 17))
            Text(LocalizedStringKey(mine ? "You have no active rooms" : query.isEmpty ? "Nothing listed yet" : "No rooms found")).font(.headline)
            Text(LocalizedStringKey(mine ? "Your rooms stay here for 24 hours. Leaving does not end a room." : query.isEmpty
                ? "Rooms shared on this server appear here. Create one or use an invitation." : "Try another search or create a room."))
                .font(.subheadline).foregroundStyle(UnyriColors.secondaryText).multilineTextAlignment(.center)
            Button { UnyriHaptics.selection(); if controller.allowNewRoomAction() { create = true } } label: {
                Label("Create room", systemImage: "plus").frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.borderedProminent).tint(colors.role(.categoryBlue)).foregroundStyle(Color(hex: 0x101820))
            Button { UnyriHaptics.selection(); if controller.allowNewRoomAction() { controller.offeredInvite = nil; controller.showLobby = true } } label: {
                Label("Join by invitation", systemImage: "link").frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.bordered).tint(colors.role(.categoryBlue))
        }.padding(18).frame(maxWidth: .infinity)
            .background(UnyriColors.elevatedBackground.opacity(0.65), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(UnyriColors.outlineSubtle, lineWidth: 0.5))
            .padding(.vertical, 4).listRowBackground(Color.clear)
    }
    @ViewBuilder private func ownedRow(_ room: ConferenceDTO) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: room.listed == true ? UnyriSymbols.rooms : "eye.slash").foregroundStyle(UnyriColors.secondaryText)
                VStack(alignment: .leading, spacing: 4) {
                    Text(room.title).font(.body.weight(.medium)).lineLimit(2)
                    if let description = room.description, !description.isEmpty { Text(description).font(.caption).foregroundStyle(UnyriColors.secondaryText).lineLimit(2) }
                    HStack(spacing: 6) {
                        Text("\(room.participants.count) / \(room.maxParticipants)").monospacedDigit()
                        Image(systemName: ConferenceAccessMode(rawValue: room.accessMode ?? "invite")?.icon ?? "exclamationmark.lock")
                        Image(systemName: "clock"); Text(room.expiresAt, format: .dateTime.day().month(.twoDigits).hour().minute())
                    }.font(.caption).foregroundStyle(UnyriColors.secondaryText)
                }.frame(maxWidth: .infinity, alignment: .leading)
                if busyEnd == room.id { ProgressView().frame(width: 44, height: 44) }
                else {
                    Button(role: .destructive) { UnyriHaptics.selection(); ending = room } label: {
                        Image(systemName: "phone.down.circle").font(.system(size: 23)).frame(width: 44, height: 44)
                    }.buttonStyle(.borderless).disabled(busyEnd != nil).accessibilityLabel(UnyriL10n.string("End room") + ". " + room.title)
                }
            }
            if controller.conference?.id == room.id && controller.isBusy {
                Button { UnyriHaptics.selection(); controller.minimized = false } label: { Label("Return to conference", systemImage: "arrow.up.right.video") }
                    .frame(minHeight: 44).buttonStyle(.bordered)
            } else {
                Button { UnyriHaptics.selection(); if controller.allowNewRoomAction() { controller.rejoinOwnedRoom(room) } } label: {
                    Label(LocalizedStringKey(controller.hasSavedAccess(to: room) ? "Join again" : "Join by invitation"), systemImage: controller.hasSavedAccess(to: room) ? "door.left.hand.open" : "link")
                }.frame(minHeight: 44).buttonStyle(.bordered).disabled(busyEnd != nil)
                    .accessibilityIdentifier("room-owned-rejoin-" + room.id.uuidString.lowercased())
                if !controller.hasSavedAccess(to: room) {
                    Text("The re-entry key is not on this device. Use an earlier invitation; the server does not store media keys.")
                        .font(.caption).foregroundStyle(UnyriColors.secondaryText)
                }
            }
        }.padding(.vertical, 4)
    }
    @MainActor private func load(reset: Bool) async {
        guard visible, controller.api.isCurrentSession else { return }
        let ticket = UUID(); loadOwner = ticket
        let tab = selected, search = query, cursor = reset ? nil : nextCursor
        loading = true; failed = false
        defer { if loadOwner == ticket { loading = false } }
        do {
            if tab == .mine {
                guard controller.ownedRoomsAvailable else { owned = []; loadedOwned = true; return }
                let value = try await controller.api.ownedRooms()
                guard visible, loadOwner == ticket, selected == tab, !Task.isCancelled, controller.api.isCurrentSession else { return }
                owned = value; loadedOwned = true
            } else {
                guard controller.directoryAvailable else { return }
                let page = try await controller.api.directory(query: search, cursor: cursor)
                guard visible, loadOwner == ticket, selected == tab, query == search, !Task.isCancelled, controller.api.isCurrentSession else { return }
                if reset { rooms = page.items }
                else {
                    let known = Set(rooms.map(\.id)); rooms.append(contentsOf: page.items.filter { !known.contains($0.id) })
                }
                nextCursor = page.nextCursor == cursor ? nil : page.nextCursor
            }
        } catch {
            guard visible, loadOwner == ticket, !Task.isCancelled, controller.api.isCurrentSession else { return }
            failed = true
        }
    }
    @MainActor private func finish(_ room: ConferenceDTO) async {
        guard busyEnd == nil else { return }; busyEnd = room.id
        defer { busyEnd = nil }
        do {
            try await controller.api.finish(room.id, end: true)
            guard controller.api.isCurrentSession, !Task.isCancelled else { return }
            controller.forgetOwnedRoom(room.id)
            owned.removeAll { $0.id == room.id }; UnyriHaptics.selection()
        } catch { if controller.api.isCurrentSession { failed = true } }
    }
}

private struct ConferenceDirectoryRow: View {
    let room: ConferenceDirectoryRoom
    @Environment(\.unyriAppColorStyle) private var colors
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: UnyriSymbols.rooms).font(.system(size: 21))
                .foregroundStyle(colors.role(.categoryBlue)).frame(width: 42, height: 42)
                .background(colors.role(.categoryBlue).opacity(0.10), in: RoundedRectangle(cornerRadius: 13))
            VStack(alignment: .leading, spacing: 5) {
                Text(room.title).font(.body.weight(.semibold)).foregroundStyle(UnyriColors.primaryText).lineLimit(2)
                if !room.description.isEmpty { Text(room.description).font(.subheadline).foregroundStyle(UnyriColors.secondaryText).lineLimit(1) }
                Text(room.ownerDisplayName).font(.caption).foregroundStyle(UnyriColors.secondaryText).lineLimit(1)
                HStack(spacing: 8) {
                    Label("\(room.participantCount) / \(room.maxParticipants)", systemImage: UnyriSymbols.rooms).monospacedDigit()
                    Spacer(minLength: 4)
                    Label(LocalizedStringKey(room.access?.title ?? "Room access is unavailable"), systemImage: room.access?.icon ?? "exclamationmark.lock")
                        .lineLimit(1)
                }.font(.caption).foregroundStyle(UnyriColors.secondaryText)
                if room.admissionLocked { Text("The creator paused admission").font(.caption).foregroundStyle(UnyriColors.secondaryText) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(UnyriColors.secondaryText).padding(.top, 12)
        }.padding(.vertical, 6).frame(minHeight: 60).contentShape(Rectangle())
    }
}

private struct ConferenceCatalogJoinSheet: View {
    let room: ConferenceDirectoryRoom
    let onInvitation: () -> Void
    @EnvironmentObject private var controller: ConferenceController
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(room.title).font(.headline)
                    if !room.description.isEmpty { Text(room.description).foregroundStyle(.secondary) }
                    Label("\(room.participantCount) / \(room.maxParticipants)", systemImage: UnyriSymbols.rooms)
                    HStack { Text("Until"); Text(room.expiresAt, format: .dateTime.day().month(.twoDigits).hour().minute()) }
                }
                if room.access?.needsInvitation == true {
                    Section {
                        Text("An invitation is required. Ask a participant for the complete invitation link.")
                        Button(action: onInvitation) { Label("Join by invitation", systemImage: "link") }
                            .frame(minHeight: 44)
                    }
                } else if room.access == nil {
                    Section { Text("Room access is unavailable") }
                } else {
                    if room.access?.needsPassword == true {
                        Section("Password required") {
                            SecureField("Room password", text: $password).textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never)
                        }
                    }
                    Section {
                        Label("End-to-end encrypted media", systemImage: "lock.shield")
                        Text("Camera and microphone stay off when you enter. Your connection is end-to-end encrypted.")
                            .font(.footnote).foregroundStyle(.secondary)
                        if !room.keyAvailable {
                            Text("Nobody can connect you right now. Wait for a participant to come online, or use an invitation.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        Button(LocalizedStringKey(room.keyAvailable ? "Join room" : "Wait for a participant")) {
                            UnyriHaptics.selection()
                            guard controller.allowNewRoomAction() else { return }
                            let value = room.access?.needsPassword == true ? password : nil
                            password = ""; controller.keyHandoff.start(room: room, password: value); dismiss()
                        }.disabled(!room.canRequestKey || (room.access?.needsPassword == true && password.isEmpty))
                            .frame(minHeight: 44).accessibilityIdentifier("room-catalog-request-key")
                        Button(action: onInvitation) { Label("Join by invitation", systemImage: "link") }
                            .frame(minHeight: 44)
                    }
                }
            }.scrollContentBackground(.hidden).background(UnyriColors.background)
                .scrollDismissesKeyboard(.interactively)
                .navigationTitle("Join room").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { password = ""; dismiss() } } }
                .onDisappear { password = "" }
        }
    }
}

private struct ConferenceHandoffStatus: View {
    @ObservedObject var model: ConferenceKeyHandoff
    let retry: (ConferenceDirectoryRoom) -> Void
    var body: some View {
        if model.phase != .idle && model.phase != .ready && model.phase != .cancelled {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(model.roomTitle).font(.subheadline.weight(.medium)).lineLimit(2)
                    if model.isWaiting {
                        HStack { ProgressView(); Text(LocalizedStringKey(model.phase == .waitingApproval ? "Waiting for the room creator…" : "Connecting to the room through an online participant…")) }
                        Button("Cancel request", role: .cancel) { UnyriHaptics.selection(); model.cancel() }.frame(minHeight: 44)
                    } else {
                        Label(LocalizedStringKey(model.error ?? "The encrypted room key could not be verified."), systemImage: "info.circle")
                            .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                        HStack {
                            if let room = model.retryRoom {
                                Button("Retry") { UnyriHaptics.selection(); model.dismissStatus(); retry(room) }
                                    .frame(minHeight: 44).disabled(!room.canRequestKey)
                            }
                            Button("Close") { UnyriHaptics.selection(); model.dismissStatus() }.frame(minHeight: 44)
                        }
                    }
                }.padding(.vertical, 4)
            }
        }
    }
}

struct ConferenceConfigurationEditor: View {
    var existing: ConferenceDTO? = nil
    @EnvironmentObject private var controller: ConferenceController
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.dismiss) private var dismiss
    @Environment(\.unyriAppColorStyle) private var colors
    @State private var configuration = ConferenceRoomConfiguration(title: "")
    @State private var limit = 30
    @State private var password = ""
    @State private var expectedRevision: Int64 = 1
    @State private var initialMode: ConferenceAccessMode = .invite
    @State private var initialConfiguration: ConferenceRoomConfiguration?
    @State private var saving = false
    @State private var shownError: String?
    private var draft: ConferenceRoomConfiguration {
        var value = configuration
        if value.accessMode.needsPassword {
            value.passwordAction = password.isEmpty && existing != nil && initialMode.needsPassword ? .keep : .set
            value.password = value.passwordAction == .set ? password : nil
        } else { value.passwordAction = initialMode.needsPassword ? .remove : .keep; value.password = nil }
        return value
    }
    private var validDraft: Bool { (2...30).contains(limit) && (try? draft.validate(creating: existing == nil)) != nil }
    private var dirty: Bool { existing == nil || initialConfiguration.map { configuration != $0 } == true || !password.isEmpty }
    var body: some View {
        NavigationStack {
            Form {
                Section("Room") {
                    TextField("Title", text: $configuration.title).textInputAutocapitalization(.sentences)
                    if !configuration.title.isEmpty && !ConferenceRoomConfiguration.validText(configuration.title.trimmingCharacters(in: .whitespacesAndNewlines), scalars: 1...80, bytes: 320) {
                        Text("Use a room name of 1–80 characters.").font(.caption).foregroundStyle(UnyriColors.danger)
                    }
                    if controller.accessSettingsAvailable {
                        TextField("Description", text: $configuration.description).lineLimit(1).submitLabel(.done)
                        if !ConferenceRoomConfiguration.validText(configuration.description, scalars: 0...240, bytes: 960) {
                            Text("Use up to 240 characters without line breaks.").font(.caption).foregroundStyle(UnyriColors.danger)
                        }
                    }
                    if existing == nil {
                        VStack(spacing: 6) {
                            HStack {
                                Text("Participants")
                                Spacer()
                                Text("\(limit)").monospacedDigit().font(.headline)
                                    .foregroundStyle(colors.role(.categoryBlue))
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(colors.role(.categoryBlue).opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                            }
                            Slider(value: Binding(get: { Double(limit) }, set: { limit = min(30, max(2, Int($0.rounded()))) }),
                                   in: 2...30, step: 1, onEditingChanged: { editing in if !editing { UnyriHaptics.selection() } })
                                .accessibilityLabel("Participants").accessibilityValue("\(limit)")
                                .accessibilityIdentifier("conference-room-capacity")
                            HStack { Text("2"); Spacer(); Text("30") }.font(.caption).foregroundStyle(UnyriColors.secondaryText)
                        }
                    }
                }
                if controller.accessSettingsAvailable {
                    Section {
                        Toggle(isOn: $configuration.listed) { Label("Show in server directory", systemImage: configuration.listed ? "eye" : "eye.slash") }
                    } footer: { Text("Hidden rooms are absent from the directory. Hiding does not revoke existing invitations.") }
                    Section("Who can enter") {
                        Picker("Room access", selection: $configuration.accessMode) {
                            ForEach(ConferenceAccessMode.allCases, id: \.self) { mode in Label(LocalizedStringKey(mode.title), systemImage: mode.icon).tag(mode) }
                        }.pickerStyle(.menu)
                        if configuration.accessMode.needsPassword {
                            SecureField(existing == nil || !initialMode.needsPassword ? "Room password" : "New password (optional)", text: $password)
                                .textContentType(.newPassword).autocorrectionDisabled().textInputAutocapitalization(.never)
                            Text("Use 6–128 characters. Spaces are preserved. The password is separate from the encryption key.")
                                .font(.footnote).foregroundStyle(.secondary)
                            if !password.isEmpty && !ConferenceRoomConfiguration.validText(password, scalars: 6...128, bytes: 512) {
                                Text("Use a password of 6–128 characters. Spaces are preserved.").font(.caption).foregroundStyle(UnyriColors.danger)
                            }
                        }
                    }
                }
                if controller.joinApprovalAvailable {
                    Section {
                        Toggle(isOn: Binding(get: { configuration.approvalRequired == true }, set: { configuration.approvalRequired = $0 })) {
                            Label("Approve entry", systemImage: "checkmark.shield")
                        }.accessibilityIdentifier("room-approve-entry")
                    } footer: { Text("New participants wait for your approval. After a rejection: 1 minute before retrying, then 10 minutes after another rejection.") }
                }
                if existing != nil {
                    Section {
                        Toggle(isOn: $configuration.admissionLocked) { Label("Pause new entry", systemImage: "hand.raised") }
                    } footer: { Text("Current participants stay connected. Settings never enable anyone's microphone or camera.") }
                } else {
                    Section {
                        Label("Camera and microphone off on entry", systemImage: "mic.slash")
                        Text("You can have three active rooms. Rooms close automatically after 24 hours.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if let shownError {
                    Section {
                        Text(LocalizedStringKey(shownError)).foregroundStyle(UnyriColors.danger)
                        if shownError == ConferenceDirectoryFailure.changed.localizedDescription {
                            Text("Your draft is kept. Reload to use the latest room settings.").font(.footnote).foregroundStyle(.secondary)
                            Button("Reload room settings") {
                                if let latest = controller.conference { UnyriHaptics.selection(); apply(latest); self.shownError = nil }
                            }.frame(minHeight: 44)
                        }
                    }
                }
            }.scrollContentBackground(.hidden).background(UnyriColors.background).scrollDismissesKeyboard(.interactively)
                .navigationTitle(existing == nil ? "Create room" : "Room settings").navigationBarTitleDisplayMode(.inline)
                .tint(colors.role(.categoryBlue))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { password = ""; dismiss() }.disabled(saving) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(LocalizedStringKey(existing == nil ? "Create" : "Save")) { Task { await save() } }
                            .disabled(saving || !validDraft || !dirty || controller.isBusy && existing == nil ||
                                (existing != nil && (!controller.isOwner || !controller.hostControlsAvailable || controller.phase != .connected || controller.changingRoomSettings || controller.moderatingParticipantID != nil)))
                    }
                }
                .interactiveDismissDisabled(saving)
                .onAppear { if let existing { apply(existing) } }
                .onDisappear { password = "" }
                .onChange(of: auth.sessionGeneration) { _ in password = ""; dismiss() }
        }
    }
    private func apply(_ value: ConferenceDTO) {
        guard let mode = ConferenceAccessMode(rawValue: value.accessMode ?? "invite") else { shownError = "Room access is unavailable"; return }
        configuration = ConferenceRoomConfiguration(title: value.title, description: value.description ?? "", listed: value.listed ?? false,
            accessMode: mode, admissionLocked: value.admissionLocked ?? false, approvalRequired: value.approvalRequired)
        initialConfiguration = configuration
        initialMode = mode; expectedRevision = value.settingsRevision ?? 1; password = ""
    }
    @MainActor private func save() async {
        guard !saving else { return }; shownError = nil
        let value = draft
        do { try value.validate(creating: existing == nil) } catch { shownError = error.localizedDescription; return }
        UnyriHaptics.selection()
        if existing == nil {
            controller.create(configuration: value, limit: limit)
            if controller.isBusy { password = ""; dismiss() } else { shownError = controller.error }
            return
        }
        saving = true; defer { saving = false }
        let success: Bool
        if controller.accessSettingsAvailable { success = await controller.updateRoomSettings(configuration: value, expectedRevision: expectedRevision) }
        else { success = await controller.updateRoomSettings(title: value.title, admissionLocked: value.admissionLocked) }
        guard controller.api.isCurrentSession else { password = ""; dismiss(); return }
        if success { UnyriHaptics.selection(); password = ""; dismiss() }
        else {
            shownError = controller.error
        }
    }
}
