import SwiftUI
import UIKit
import UniformTypeIdentifiers

private struct SignalVerificationManagerKey: EnvironmentKey {
    static let defaultValue: SignalSessionManager? = nil
}
extension EnvironmentValues {
    var signalVerificationManager: SignalSessionManager? {
        get { self[SignalVerificationManagerKey.self] }
        set { self[SignalVerificationManagerKey.self] = newValue }
    }
}

@MainActor
struct IdentityVerificationView: View {
    let peer: String
    var ownPeer: String? = nil
    var exactDevice: SignalDeviceTrustSnapshot? = nil
    var onAccepted: (() -> Void)? = nil
    var resumingMessage = false
    @State private var reviewOwn = false
    @State private var showAll = false
    private var selectedPeer: String { reviewOwn ? (ownPeer ?? peer) : peer }
    private var visibleDevices: [UnyriVerifiedDevice] {
        let changed = devices.filter { $0.pending != nil }
        return (showAll ? devices : changed).sorted {
            if ($0.pending != nil) != ($1.pending != nil) { return $0.pending != nil }
            return $0.id < $1.id
        }
    }
    @Environment(\.signalVerificationManager) private var manager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var devices: [UnyriVerifiedDevice] = []
    @State private var hasError = false
    @State private var confirmation: UnyriVerifiedDevice?

    var body: some View {
        NavigationStack {
            UnyriSettingsPage("Verify security keys") {
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 12) {
                    Text("A security key is your device’s digital ID, not a password. It can change after reinstalling UNYRI or changing phones. Never send anyone your passwords or recovery keys.")
                    }.font(.subheadline).foregroundStyle(UnyriColors.secondaryText).padding(14)
                }
                UnyriSettingsGroup {
                    VStack(alignment: .leading, spacing: 12) {
                    if resumingMessage {
                        Text("Confirm the device change, then close this screen to retry your selected message. Cancelling without confirmation will not send it.")
                    } else {
                        Text("New and changed devices receive nothing until you approve them. Compare the code through a trusted channel first. Known devices can still receive messages.")
                    }
                    if ownPeer != nil && ownPeer != peer {
                        Toggle("My other devices", isOn: $reviewOwn).frame(minHeight: 44).accessibilityIdentifier("identity-own-devices").onChange(of: reviewOwn) { _ in confirmation = nil; showAll = false; refresh() }
                    }
                    Toggle("Show all known devices", isOn: $showAll).frame(minHeight: 44).accessibilityIdentifier("identity-show-all")
                    if !devices.contains(where: { $0.pending != nil }) && !devices.isEmpty {
                        Text("No changed keys in this list. If sending is still paused, check My other devices, then retry the message.")
                    }
                    }.font(.subheadline).padding(14)
                }
                if hasError {
                    UnyriSettingsGroup {
                        VStack(alignment: .leading, spacing: 12) {
                        Text("Could not load or save verification. Reopen this screen and compare the current code again.")
                        Button("Retry") { refresh() }
                            .buttonStyle(UnyriSettingsMenuActionStyle()).accessibilityIdentifier("identity-retry")
                        }.font(.subheadline).padding(14)
                    }
                } else if devices.isEmpty {
                    UnyriSettingsNote("No device keys discovered yet. Try sending a message or reviewing a room key request, then reopen this screen.")
                }
                ForEach(visibleDevices) { device in
                    UnyriSettingsGroup {
                        VStack(alignment: .leading, spacing: 14) {
                        (Text("Device") + Text(" \(device.id)"))
                            .font(.caption.weight(.semibold)).foregroundStyle(UnyriColors.secondaryText)
                        Label(LocalizedStringKey(device.pending != nil ? "Security key changed" : device.verified ? "Verified" : "Not verified"),
                              systemImage: device.pending != nil ? "exclamationmark.shield" : device.verified ? "checkmark.shield" : "shield")
                        DisclosureGroup("Security code details") {
                        Text(grouped(device.number)).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                        Button("Copy safety number") {
                            UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: device.number]],
                                options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
                        }.buttonStyle(UnyriSettingsMenuActionStyle())
                            .accessibilityIdentifier("identity-copy-\(device.id)")
                        }.tint(Color(hex: 0x7BC9EC))
                        Button(LocalizedStringKey(device.pending != nil ? "Review device change" : "I compared the codes")) { confirmation = device }
                            .disabled(device.verified || hasError)
                            .buttonStyle(UnyriSettingsMenuActionStyle())
                            .accessibilityIdentifier("identity-review-\(device.id)")
                        }.font(.subheadline).padding(14)
                    }
                }
            }
            .tint(Color(hex: 0x7BC9EC))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() }.accessibilityIdentifier("identity-close") } }
            .onAppear {
                if let ownPeer, ownPeer != peer,
                   let pending = try? manager?.pendingIdentityPeers(among: [peer, ownPeer]),
                   !pending.contains(peer), pending.contains(ownPeer) { reviewOwn = true }
                refresh()
            }
            .onChange(of: scenePhase) { phase in if phase == .active { refresh() } }
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("COCOIdentityChanged"))) { _ in refresh() }
            .confirmationDialog("Verify security keys", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }), titleVisibility: .visible) {
                if let snapshot = confirmation {
                    Button(LocalizedStringKey(snapshot.pending != nil ? "I confirmed the change" : "Mark as verified")) { apply(snapshot) }
                    Button("Cancel", role: .cancel) { confirmation = nil }
                }
            } message: {
                Text(LocalizedStringKey(confirmation?.pending != nil
                    ? "A new or changed device could belong to an attacker. Compare its security code through a trusted channel before approving. For your own account, confirm only a device you added yourself."
                    : "Only confirm if every digit matches on both phones. This mark is saved only on this device."))
            }
        }
    }

    private func grouped(_ number: String) -> String {
        let digits = Array(number.filter(\.isNumber))
        return stride(from: 0, to: digits.count, by: 5).map { String(digits[$0..<min($0 + 5, digits.count)]) }.joined(separator: " ")
    }
    private func refresh() {
        do {
            guard let manager else { throw CancellationError() }
            devices = try manager.verificationDevices(peer: selectedPeer).filter { device in
                guard let exactDevice else { return true }
                return exactDevice.userID == selectedPeer && device.id == exactDevice.signalID &&
                    (device.pending ?? device.trusted) == exactDevice.identity
            }
            hasError = false
        } catch { devices = []; hasError = true }
    }
    private func apply(_ snapshot: UnyriVerifiedDevice) {
        confirmation = nil
        do {
            guard let manager else { throw CancellationError() }
            try manager.updateVerification(peer: selectedPeer, expected: snapshot, acceptChange: snapshot.pending != nil)
            refresh()
            if snapshot.pending != nil { onAccepted?() }
        } catch { devices = []; hasError = true }
    }
}

/// Utility-menu actions retain their complete label as a 44pt hit target.
/// Visual feedback is owned by the native Button; no overlay gestures intercept taps.
struct UnyriSettingsMenuActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        let ink = configuration.role == .destructive ? UnyriColors.danger : Color(hex: 0x7BC9EC)
        return UnyriQuietButtonStyle().makeBody(configuration: configuration)
            .font(.subheadline.weight(.semibold)).foregroundStyle(ink)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 12)
            .background(ink.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle()).opacity(enabled ? 1 : 0.4)
    }
}
