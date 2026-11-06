import SwiftUI

struct UnyriAccountAutoDeleteSettingsView: View {
    @EnvironmentObject private var policy: AccountAutoDeleteRepository
    @Environment(\.scenePhase) private var phase
    @State private var enabled = false
    @State private var daysText = "365"
    @State private var password = ""
    @State private var confirmation: Confirmation?
    @State private var saved = false
    @FocusState private var focused: Field?
    private enum Field: Hashable { case days, password }

    private struct Confirmation: Identifiable {
        let id = UUID()
        let days: Int?
        let revision: Int64
    }
    private var days: Int? {
        guard !daysText.isEmpty, daysText.allSatisfy({ $0.isASCII && $0.isNumber }),
              let number = Int(daysText), AccountAutoDeleteSettings.allowedDays.contains(number) else { return nil }
        return number
    }
    private var changed: Bool {
        guard let current = policy.value else { return false }
        return enabled ? days != nil && days != current.inactiveDays : current.inactiveDays != nil
    }
    private var canSave: Bool { policy.canEdit && changed && (!enabled || !password.isEmpty) }

    var body: some View {
        UnyriSettingsPage("Account auto-delete") {
            UnyriSettingsNote("Off by default. This setting applies to your account on every device.")
            if let current = policy.value {
                UnyriSettingsGroup("Current server setting") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(current.inactiveDays.map { String(format: UnyriL10n.string("After %d days of inactivity"), $0) } ?? UnyriL10n.string("Off"))
                            .font(.headline).accessibilityIdentifier("auto-delete-current")
                        dateRow("Last account activity", current.lastActiveAt)
                        if let next = current.nextDeletionAt { dateRow("Next deletion if inactive", next) }
                    }.padding(14)
                }
                UnyriSettingsGroup {
                    Toggle("Auto-delete inactive account", isOn: $enabled)
                        .padding(14).accessibilityIdentifier("auto-delete-enabled")
                }.disabled(!policy.canEdit)
                if enabled {
                    UnyriSettingsGroup("Days without opening UNYRI") {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("Choose any whole number from 1 to 1095 days (3 years).")
                                .font(.footnote).foregroundStyle(UnyriColors.secondaryText)
                            TextField("Number of days", text: $daysText).keyboardType(.numberPad)
                                .focused($focused, equals: .days)
                                .textFieldStyle(.roundedBorder).accessibilityIdentifier("auto-delete-days")
                            Slider(value: Binding(get: { Double(days ?? 365) }, set: { daysText = String(Int($0)) }),
                                   in: 1...1095, step: 1).accessibilityLabel("Number of days")
                                .accessibilityIdentifier("auto-delete-slider")
                            Stepper(value: Binding(get: { days ?? 365 }, set: { daysText = String($0) }), in: 1...1095) {
                                Text(verbatim: String(format: UnyriL10n.string("%d days"), days ?? 365))
                            }.accessibilityIdentifier("auto-delete-stepper")
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86))], spacing: 8) {
                                ForEach(AccountAutoDeleteSettings.presets, id: \.self) { preset in
                                    Button { daysText = String(preset); UnyriHaptics.selection() } label: {
                                        Text(verbatim: String(format: UnyriL10n.string("%d days"), preset))
                                            .font(.subheadline).frame(maxWidth: .infinity, minHeight: 44)
                                            .background(days == preset ? UnyriColors.accent.opacity(0.2) : Color.clear)
                                            .clipShape(RoundedRectangle(cornerRadius: 10))
                                    }.accessibilityIdentifier("auto-delete-preset-\(preset)")
                                }
                            }
                            if days == nil {
                                Text("Enter a whole number of days from 1 to 1095.")
                                    .font(.footnote).foregroundStyle(UnyriColors.danger)
                            }
                        }.padding(14)
                    }.disabled(!policy.canEdit)
                    UnyriSettingsGroup("Confirm account password") {
                        SecureField("Current account password", text: $password)
                            .focused($focused, equals: .password)
                            .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .privacySensitive().padding(14).accessibilityIdentifier("auto-delete-password")
                    }.disabled(!policy.canEdit)
                    UnyriSettingsNote("Enter your current account password to enable or change auto-delete.")
                }
                UnyriSettingsNote("Opening UNYRI with a server connection on any device resets the inactivity period. Background notifications and token refresh do not reset it.")
                Button("Save") {
                    guard canSave, let current = policy.value else { return }
                    focused = nil; saved = false; confirmation = Confirmation(days: enabled ? days : nil, revision: current.revision)
                }.unyriProminentAction().disabled(!canSave).accessibilityIdentifier("auto-delete-save")
            }
            if policy.busy { ProgressView().frame(maxWidth: .infinity) }
            if saved && !changed { Text("Account auto-delete saved.").font(.footnote).foregroundStyle(UnyriColors.success) }
            if let error = policy.error { Text(error).font(.footnote).foregroundStyle(UnyriColors.danger) }
            Button("Refresh") { Task { await reload() } }.frame(minHeight: 44).disabled(policy.busy)
        }
        .task(id: phase) { if phase == .active { await reload() } }
        .onChange(of: policy.value) { _ in confirmation = nil; adoptServerSetting() }
        .onChange(of: enabled) { _ in password = "" }
        .onChange(of: daysText) { _ in password = "" }
        .onChange(of: phase) { if $0 != .active { focused = nil; password = ""; confirmation = nil } }
        .onDisappear { password = ""; confirmation = nil }
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focused = nil } } }
        .sheet(item: $confirmation, onDismiss: { password = "" }) { proposed in
            NavigationStack {
                UnyriSettingsPage(proposed.days == nil ? "Disable account auto-delete?" : "Enable account auto-delete?") {
                    if let count = proposed.days {
                        UnyriSettingsNote(String(format: UnyriL10n.string("If you do not open UNYRI with a server connection on any device for %d days, your account will be permanently deleted."), count))
                        UnyriSettingsNote("Deletion removes the account, chats, messages, calls, media and history backups from UNYRI for both sides. There is no recovery. Other devices and your contacts' phones update when they connect.")
                        UnyriSettingsNote("Separately saved copies and screenshots cannot be erased remotely.")
                    } else {
                        UnyriSettingsNote("Your account will no longer be deleted automatically for inactivity.")
                    }
                    Button(proposed.days == nil ? "Disable auto-delete" : "Confirm and save", role: proposed.days == nil ? nil : .destructive) {
                        guard policy.canEdit, policy.value?.revision == proposed.revision else { confirmation = nil; return }
                        let proof = password; password = ""; confirmation = nil
                        Task {
                            saved = await policy.save(inactiveDays: proposed.days, expectedRevision: proposed.revision, password: proof)
                        }
                    }.unyriProminentAction().accessibilityIdentifier("auto-delete-confirm")
                    Button("Cancel", role: .cancel) { confirmation = nil }.frame(minHeight: 44)
                }
            }.preferredColorScheme(.dark)
        }
    }
    private func dateRow(_ title: String, _ date: Date) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(LocalizedStringKey(title)).font(.caption).foregroundStyle(UnyriColors.secondaryText)
            Text(date, format: .dateTime.year().month().day().hour().minute()).font(.subheadline)
        }
    }
    private func adoptServerSetting() {
        guard let current = policy.value else { return }
        enabled = current.inactiveDays != nil; daysText = String(current.inactiveDays ?? 365); password = ""
    }
    private func reload() async {
        saved = false; password = ""; confirmation = nil
        await policy.refresh(); adoptServerSetting()
    }
}
