import SwiftUI

private struct UnyriSettingsPickerField<Content: View>: View {
    let title: String
    let content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringKey(title)).font(.subheadline).foregroundStyle(UnyriColors.primaryText)
            content.pickerStyle(.menu).labelsHidden().frame(maxWidth: .infinity, alignment: .trailing)
        }.padding(14)
    }
}

struct UnyriPrivacySettingsView: View {
    @EnvironmentObject private var privacy: UserPrivacyRepository
    var body: some View {
        UnyriSettingsPage("Online status & read receipts") {
            if let value = privacy.value {
                UnyriSettingsGroup("Who can see my activity") {
                    visibility("Online", current: value.onlineVisibility) { next in var copy = value; copy.onlineVisibility = next; Task { await privacy.save(copy) } }
                    UnyriSettingsDivider()
                    visibility("Last seen", current: value.lastSeenVisibility) { next in var copy = value; copy.lastSeenVisibility = next; Task { await privacy.save(copy) } }
                }
                UnyriSettingsGroup("Exceptions") {
                    NavigationLink { UnyriPrivacyExceptionsView(allow: true) } label: {
                        UnyriSettingsRow(title: "Always allow", icon: "person.badge.plus", tint: UnyriColors.success, subtitle: "\(value.alwaysAllow.count)")
                    }.accessibilityIdentifier("privacy-always-allow")
                    UnyriSettingsDivider()
                    NavigationLink { UnyriPrivacyExceptionsView(allow: false) } label: {
                        UnyriSettingsRow(title: "Never allow", icon: "person.badge.minus", tint: UnyriColors.danger, subtitle: "\(value.neverAllow.count)")
                    }.accessibilityIdentifier("privacy-never-allow")
                }
                UnyriSettingsNote("Exceptions apply to online and last seen. Never allow and blocked accounts take priority.")
                UnyriSettingsGroup("Read receipts") {
                    Toggle("Send read receipts", isOn: Binding(get: { value.sendReadReceipts }, set: { next in
                        var copy = value; copy.sendReadReceipts = next; Task { await privacy.save(copy) }
                    })).padding(14).accessibilityIdentifier("privacy-read-receipts")
                }
                UnyriSettingsNote("When off, others do not see new read receipts. Your own unread count still updates. Previous receipts remain visible.")
            } else if privacy.busy { ProgressView().frame(maxWidth: .infinity) }
            if let error = privacy.error { Text(error).font(.footnote).foregroundStyle(UnyriColors.danger) }
            Button("Refresh") { Task { await privacy.refresh() } }.frame(minHeight: 44)
        }.disabled(privacy.busy).task { await privacy.refresh() }.refreshable { await privacy.refresh() }
    }
    private func visibility(_ title: String, current: PrivacyVisibility, change: @escaping (PrivacyVisibility) -> Void) -> some View {
        UnyriSettingsPickerField(title) {
            Picker(LocalizedStringKey(title), selection: Binding(get: { current }, set: change)) {
                Text("Everyone").tag(PrivacyVisibility.everyone)
                Text("Nobody").tag(PrivacyVisibility.nobody)
            }.accessibilityIdentifier("privacy-" + title)
        }
    }
}

private struct UnyriPrivacyExceptionsView: View {
    let allow: Bool
    @EnvironmentObject private var privacy: UserPrivacyRepository
    @EnvironmentObject private var users: UsersRepository
    @EnvironmentObject private var auth: AuthRepository
    @State private var query = ""
    @State private var results: [PublicUser] = []
    @State private var names: [String: String] = [:]
    @State private var searching = false
    @State private var searchError = false
    private var selected: [String] { (allow ? privacy.value?.alwaysAllow : privacy.value?.neverAllow) ?? [] }
    var body: some View {
        UnyriSettingsPage(allow ? "Always allow" : "Never allow") {
            UnyriSettingsNote("Choose up to 256 accounts in each list.")
            UnyriSettingsGroup {
                TextField("Search by name or username", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled().padding(14)
            }
            if searching { ProgressView().frame(maxWidth: .infinity) }
            if searchError { UnyriSettingsNote("Search unavailable. Try again.") }
            UnyriSettingsGroup {
                ForEach(results.filter { $0.id != auth.currentUser?.id }) { user in
                    Button { toggle(user.id); names[user.id] = user.displayName } label: {
                        HStack {
                            VStack(alignment: .leading) { Text(user.displayName); Text("@" + user.username).font(.caption).foregroundStyle(UnyriColors.secondaryText) }
                            Spacer(); Image(systemName: selected.contains(user.id) ? "checkmark.circle.fill" : "circle")
                        }.padding(14).frame(minHeight: 52).contentShape(Rectangle())
                    }.disabled(privacy.busy || (!selected.contains(user.id) && selected.count >= 256))
                }
            }
            if !selected.isEmpty {
                UnyriSettingsGroup("Selected accounts") {
                    ForEach(selected, id: \.self) { id in
                        HStack {
                            Text(names[id] ?? id).font(.subheadline).lineLimit(2)
                            Spacer()
                            Button("Remove", role: .destructive) { toggle(id) }.frame(minHeight: 44).disabled(privacy.busy)
                        }.padding(.horizontal, 14)
                    }
                }
            }
            if let error = privacy.error { Text(error).font(.footnote).foregroundStyle(UnyriColors.danger) }
        }.task(id: query) {
            guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { results = []; return }
            let generation = auth.sessionGeneration; let owner = auth.currentUser?.id
            searching = true; searchError = false
            do {
                try await Task.sleep(nanoseconds: 300_000_000)
                let response = try await users.search(query: query)
                guard !Task.isCancelled, generation == auth.sessionGeneration, owner == auth.currentUser?.id else { return }
                results = response.items
                for user in results { names[user.id] = user.displayName }
            } catch { if !Task.isCancelled { searchError = true } }
            if !Task.isCancelled { searching = false }
        }
    }
    private func toggle(_ id: String) {
        guard var value = privacy.value else { return }
        var ids = Set(selected)
        if ids.contains(id) { ids.remove(id) } else if ids.count < 256 { ids.insert(id) }
        if allow { value.alwaysAllow = ids.sorted() } else { value.neverAllow = ids.sorted() }
        Task { await privacy.save(value) }
    }
}

struct UnyriAppLockSettingsView: View {
    @ObservedObject private var lock = UnyriAppLock.shared
    @State private var authorized = false
    @State private var working = false
    var body: some View {
        UnyriSettingsPage("App lock") {
            if authorized || !lock.enabled {
                UnyriSettingsGroup {
                    Toggle("App lock", isOn: Binding(get: { lock.enabled }, set: { enabled in configure(enabled: enabled, timeout: lock.timeout) })).padding(14)
                    if lock.enabled {
                        UnyriSettingsDivider()
                        UnyriSettingsPickerField("Lock after background") {
                            Picker("Lock after background", selection: Binding(get: { lock.timeout }, set: { configure(enabled: true, timeout: $0) })) {
                                Text("Immediately").tag(0); Text("1 minute").tag(60); Text("5 minutes").tag(300)
                            }
                        }
                    }
                }
                UnyriSettingsNote("Use Face ID, Touch ID or your iPhone system passcode. UNYRI does not store a separate PIN. Calls and rooms continue while locked.")
            } else {
                Button("Unlock settings") { Task { authorized = await lock.authenticate() } }.frame(minHeight: 44)
            }
            if working || lock.authenticating { ProgressView().frame(maxWidth: .infinity) }
            if let error = lock.error { Text(error).font(.footnote).foregroundStyle(UnyriColors.danger) }
        }.disabled(working).task { if lock.enabled { authorized = await lock.authenticate() } }
    }
    private func configure(enabled: Bool, timeout: Int) {
        working = true
        Task { if await lock.configure(enabled: enabled, timeout: timeout) { authorized = true }; working = false }
    }
}

struct UnyriAutomaticDownloadsView: View {
    @ObservedObject private var settings = UnyriDeviceSettings.shared
    var body: some View {
        UnyriSettingsPage("Automatic downloads") {
            ForEach([true, false], id: \.self) { wifi in
                UnyriSettingsGroup(wifi ? "Wi-Fi" : "Mobile data") {
                    ForEach(AutomaticMediaKind.allCases, id: \.self) { kind in
                        VStack(alignment: .leading, spacing: 8) {
                            Toggle(LocalizedStringKey(kind.title), isOn: binding(wifi, kind, \.enabled))
                                .accessibilityIdentifier("download-" + (wifi ? "wifi-" : "cellular-") + kind.rawValue)
                            Picker("Size limit", selection: binding(wifi, kind, \.maxMiB)) {
                                ForEach([1, 5, 10, 25, 50, 100], id: \.self) { Text(verbatim: String(format: UnyriL10n.string("%d MB"), $0)).tag($0) }
                            }.pickerStyle(.menu).disabled(!rule(wifi, kind).enabled)
                        }.padding(14)
                        if kind != .file { UnyriSettingsDivider() }
                    }
                }
            }
            UnyriSettingsNote("Files with unknown size are not downloaded automatically. Tap any attachment to download it manually.")
        }
    }
    private func rule(_ wifi: Bool, _ kind: AutomaticMediaKind) -> AutomaticMediaRule {
        (wifi ? settings.downloads.wifi : settings.downloads.cellular)[kind.rawValue] ?? AutomaticMediaRule(enabled: false, maxMiB: wifi ? 50 : 5)
    }
    private func binding<T>(_ wifi: Bool, _ kind: AutomaticMediaKind, _ key: WritableKeyPath<AutomaticMediaRule, T>) -> Binding<T> {
        Binding(get: { rule(wifi, kind)[keyPath: key] }, set: { next in
            var value = settings.downloads; var item = rule(wifi, kind); item[keyPath: key] = next
            if wifi { value.wifi[kind.rawValue] = item } else { value.cellular[kind.rawValue] = item }
            settings.downloads = value; UnyriHaptics.selection()
        })
    }
}

struct UnyriCachePolicyView: View {
    @ObservedObject private var settings = UnyriDeviceSettings.shared
    @EnvironmentObject private var storage: StorageService
    @EnvironmentObject private var chats: ChatsRepository
    @State private var usage: [ChatCacheUsage] = []
    @State private var busy = false
    @State private var selection: ChatCacheUsage?
    @State private var failed = false
    var body: some View {
        UnyriSettingsPage("Cache limits & chats") {
            UnyriSettingsGroup("Media cache") {
                UnyriSettingsPickerField("Maximum cache size") {
                    Picker("Maximum cache size", selection: $settings.cache.maximumGiB) {
                        Text("Unlimited").tag(0); ForEach([1, 2, 5, 10, 20], id: \.self) { Text(verbatim: String(format: UnyriL10n.string("%d GB"), $0)).tag($0) }
                    }.accessibilityIdentifier("cache-maximum")
                }
                UnyriSettingsDivider()
                UnyriSettingsPickerField("Keep cached media") {
                    Picker("Keep cached media", selection: $settings.cache.retentionDays) {
                        Text("Forever").tag(0); Text("3 days").tag(3); Text("7 days").tag(7); Text("30 days").tag(30)
                    }.accessibilityIdentifier("cache-retention")
                }
            }
            UnyriSettingsNote("Only downloaded media cache is removed. History, restored originals, drafts and files in use are protected. Cleanup runs in small batches.")
            UnyriSettingsGroup("Cache by chat") {
                ForEach(usage) { item in
                    Button { selection = item } label: {
                        HStack {
                            Text(chatName(item.id)).font(.subheadline).lineLimit(2)
                            Spacer(); Text(FileSizeFormatter.format(item.bytes)).font(.caption).foregroundStyle(UnyriColors.secondaryText)
                            Image(systemName: "trash").foregroundStyle(UnyriColors.danger)
                        }.padding(14).frame(minHeight: 52).contentShape(Rectangle())
                    }.disabled(busy).accessibilityIdentifier("cache-chat-" + item.id)
                }
            }
            if usage.isEmpty && !busy { UnyriSettingsNote("No cached media") }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if failed { UnyriSettingsNote("Some cached files could not be removed.") }
        }.task { await reload() }.refreshable { await reload() }
        .alert("Clear cached media?", isPresented: Binding(get: { selection != nil }, set: { if !$0 { selection = nil } })) {
            Button("Cancel", role: .cancel) { selection = nil }
            Button("Clear", role: .destructive) {
                guard let item = selection else { return }; selection = nil; busy = true
                Task { let result = await storage.clear(chatID: item.id); failed = result.failedFiles > 0; await reload() }
            }
        } message: { Text("Messages and conversations will not be deleted. Media can be downloaded again when needed.") }
    }
    private func chatName(_ id: String) -> String {
        chats.preferenceCache[id]?.otherUser?.displayName ?? (chats.preferenceCache[id]?.chatType == "saved" ? UnyriL10n.string("Saved Messages") : id)
    }
    private func reload() async { busy = true; usage = await storage.usageByChat(); busy = false }
}
