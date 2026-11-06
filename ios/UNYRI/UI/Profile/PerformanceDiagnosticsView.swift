import SwiftUI

struct PerformanceDiagnosticsView: View {
    @AppStorage(PerformanceRecorder.enabledKey) private var enabled = false
    @AppStorage(PerformanceRecorder.onlineKey) private var online = false
    @ObservedObject private var monitor = PerformanceMonitor.shared
    @State private var marked = false
    var body: some View {
        UnyriSettingsPage("Performance diagnostics") {
            UnyriSettingsGroup("Recording") {
                Toggle("Record performance diagnostics", isOn: $enabled)
                    .accessibilityIdentifier("performance-recording-toggle")
                    .font(.subheadline).unyriDecorativeTint(Color(hex: 0x7BC9EC))
                    .padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 52)
            }
            UnyriSettingsNote("Records timings, frame delays, memory, network state and technical stages while UNYRI is open. No messages, passwords, keys, typed text or screen recording.")
            if enabled {
                UnyriSettingsGroup("Live sharing") {
                    Toggle("Share live diagnostics with my server", isOn: $online)
                        .accessibilityIdentifier("performance-online-toggle")
                        .font(.subheadline).unyriDecorativeTint(Color(hex: 0xAFA4F7))
                        .padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 52)
                    UnyriSettingsDivider()
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "antenna.radiowaves.left.and.right").font(.system(size: 15))
                            .unyriDecorativeForeground(Color(hex: 0x7BC9EC)).accessibilityHidden(true)
                        Text(UnyriL10n.string(monitor.onlineStatus)).font(.footnote).foregroundColor(UnyriColors.secondaryText)
                            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                    }.padding(14)
                }
                UnyriSettingsNote("Optional. Sends small batches to your signed-in server. Only its owner can view them. Live batches expire within 24 hours.")
                UnyriSettingsGroup("When a problem happens") {
                    Button {
                        PerformanceRecorder.shared.record(.markProblem)
                        UnyriHaptics.press(); marked = true
                    } label: {
                        UnyriSettingsRow(title: marked ? "Problem marked" : "Mark a stutter now",
                            icon: marked ? "checkmark.circle" : "flag", tint: Color(hex: 0x7BC9EC), showsChevron: false)
                    }
                    .buttonStyle(.plain) // The existing action already emits its single haptic.
                    .accessibilityLabel(Text(LocalizedStringKey(marked ? "Problem marked" : "Mark a stutter now")))
                    .accessibilityIdentifier("performance-mark-problem")
                    UnyriSettingsDivider()
                    NavigationLink { BugReportView() } label: {
                        UnyriSettingsRow(title: "Report a problem", icon: "bubble.left.and.exclamationmark.bubble.right", tint: Color(hex: 0xAFA4F7))
                    }.accessibilityLabel("Report a problem").accessibilityIdentifier("performance-report-problem")
                }
                UnyriSettingsNote("Mark the problem, continue using UNYRI briefly, then send a report. A small window before and after the mark is preserved. Collection pauses in the background.")
                UnyriSettingsGroup {
                    Button(role: .destructive) { monitor.clear(); marked = false } label: {
                        UnyriSettingsRow(title: "Clear diagnostics", icon: "trash", tint: UnyriColors.danger, showsChevron: false)
                    }.accessibilityLabel("Clear diagnostics").accessibilityIdentifier("performance-clear")
                }
            }
        }
        .performanceScreen(.settings)
        .onChange(of: enabled) { value in if !value { online = false } }
    }
}
