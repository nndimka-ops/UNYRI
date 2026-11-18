import SwiftUI
import UIKit

/// Saved choices override distribution defaults; defaults are never registered
/// as a permanent opt-in when a TestFlight installation becomes App Store.
enum DiagnosticReportPreferences {
    static let automaticKey = "diagnosticReports.automatic.v1"
    static let shakeKey = "diagnosticReports.shake.v1"
    static var buildDefault: Bool {
        #if DEBUG || UNYRI_ALPHA_LAN
        let qa = true
        #else
        let qa = false
        #endif
        return DiagnosticReportPolicy.defaultEnabled(debugOrQA: qa, receiptName: Bundle.main.appStoreReceiptURL?.lastPathComponent)
    }
    static var automatic: Bool {
        UserDefaults.standard.object(forKey: automaticKey) == nil ? buildDefault : UserDefaults.standard.bool(forKey: automaticKey)
    }
}

enum DiagnosticReportEvents {
    static let notification = Notification.Name("UNYRIDiagnosticIncident")
    static func emit(_ incident: DiagnosticIncident) {
        NotificationCenter.default.post(name: notification, object: incident)
    }
}

@MainActor struct DiagnosticReportSettings: View {
    @AppStorage(DiagnosticReportPreferences.automaticKey) private var automatic = DiagnosticReportPreferences.buildDefault
    @AppStorage(DiagnosticReportPreferences.shakeKey) private var shake = DiagnosticReportPreferences.buildDefault
    var body: some View {
        UnyriSettingsGroup {
            VStack(alignment: .leading, spacing: 12) {
                Toggle("Shake to report a problem", isOn: $shake).accessibilityIdentifier("report-shake-toggle")
                Toggle("Send diagnostics automatically", isOn: $automatic).accessibilityIdentifier("report-automatic-toggle")
                Text("Sends performance and call events, device model, OS and app version to your server; never messages, contacts, media or keys.")
                    .font(.caption).foregroundStyle(UnyriColors.secondaryText)
            }.font(.subheadline).padding(14)
        }
    }
}

extension FeedbackRequest {
    @MainActor static func capture(id: String = UUID().uuidString.lowercased(), description: String = "",
                                  screenshots: [Data] = [], performance: PerformanceSnapshot?,
                                  crash: UnyriCrashRecord? = nil, trigger: DiagnosticTrigger? = nil) -> Self {
        Self(id: id, description: description,
             appVersion: AppVersion.display(shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                                            build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String),
             osVersion: UIDevice.current.systemVersion,
             screenshots: screenshots.map { FeedbackScreenshot(dataBase64: $0.base64EncodedString()) },
             performance: performance, diagnostics: crash?.reportDescription,
             automatic: trigger != nil, trigger: trigger)
    }
}

@MainActor final class AutomaticDiagnosticReports {
    static let shared = AutomaticDiagnosticReports()
    private weak var auth: AuthRepository?
    private var active = false
    private var uploading = false
    private var configuration = UUID()
    private var uploadTask: Task<Void, Never>?
    private let outbox: DiagnosticReportOutbox
    private init() {
        // Outside the account-cache purge: quotas survive sign-out/sign-in.
        let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCODiagnosticReports/outbox-v1.json")
        outbox = DiagnosticReportOutbox(load: {
            do { return try Data(contentsOf: file) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile { return nil }
        }, save: { data in
            var directory = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues(); values.isExcludedFromBackup = true; try directory.setResourceValues(values)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        })
    }
    func configure(auth: AuthRepository, active: Bool) async {
        let ticket = UUID(); configuration = ticket
        self.auth = auth; self.active = active
        let previous = uploadTask; previous?.cancel(); await previous?.value
        guard configuration == ticket, !Task.isCancelled else { return }
        if !DiagnosticReportPreferences.automatic { try? await outbox.discard(); return }
        guard active else { return }
        let server = auth.authenticatedServerURL, generation = auth.sessionGeneration
        if let user = auth.currentUser {
            let owner = server.absoluteString + "|" + user.id
            PerformanceMonitor.shared.bind(owner: owner)
            await UnyriCrashDiagnostics.shared.bind(owner: owner)
        }
        guard configuration == ticket, generation == auth.sessionGeneration, !Task.isCancelled else { return }
        if case .cleanupFailed = auth.state { await receive(.cleanupFailure) }
        if auth.currentUser != nil,
           let failures = try? await outbox.pendingFailures(origin: server.absoluteString, now: Date()) {
            for failure in failures {
                guard configuration == ticket, generation == auth.sessionGeneration,
                      server == auth.authenticatedServerURL, !Task.isCancelled else { return }
                await receive(failure.kind == .cleanupFailure ? .cleanupFailure : .signInFailure, performance: failure.performance)
                try? await outbox.clearFailure(origin: server.absoluteString, kind: failure.kind)
            }
        }
        retry()
    }
    func receive(_ incident: DiagnosticIncident, performance: PerformanceSnapshot? = nil,
                 crash: UnyriCrashRecord? = nil) async {
        guard DiagnosticReportPreferences.automatic, let kind = DiagnosticReportPolicy.kind(for: incident), let auth else { return }
        let now = Date(), server = auth.authenticatedServerURL, generation = auth.sessionGeneration
        let owner = auth.currentUser.map { server.absoluteString + "|" + $0.id }
        if let crash { guard let owner, UnyriCrashDiagnostics.shared.owns(crash, owner: owner) else { return } }
        PerformanceMonitor.shared.bind(owner: owner)
        if kind == .signInFailure || kind == .cleanupFailure {
            PerformanceRecorder.shared.record(.errorShown, ["phase": kind == .signInFailure ? 1 : 2])
        }
        let snapshot = performance ?? PerformanceMonitor.shared.reportSnapshot()
        guard let owner else {
            // /feedback requires authentication. Keep only technical failure facts
            // for this origin until successful login; never send anonymously.
            if kind == .signInFailure || kind == .cleanupFailure {
                try? await outbox.holdFailure(origin: server.absoluteString, kind: kind, performance: snapshot, now: now)
            }
            return
        }
        let request = FeedbackRequest.capture(performance: snapshot, crash: crash, trigger: kind)
        guard generation == auth.sessionGeneration else { return }
        let queued = (try? await outbox.enqueue(request, owner: owner, kind: kind, crashID: crash?.id, now: now,
                                              optedIn: DiagnosticReportPreferences.automatic)) == true
        if queued, let crash { await UnyriCrashDiagnostics.shared.markOffered(crash.id, owner: owner) }
        retry()
    }
    private var restricted: Bool {
        UnyriMediaNetwork.shared.constrained || UnyriDeviceSettings.shared.callDataSaving != .never ||
            !UnyriDeviceSettings.shared.downloads.cellular.values.contains(where: { $0.enabled })
    }
    private var networkAllowed: Bool {
        DiagnosticReportPolicy.allowsNetwork(connected: UnyriMediaNetwork.shared.connected,
            cellular: UnyriMediaNetwork.shared.cellular, restricted: restricted)
    }
    func retry() {
        guard !uploading, active, DiagnosticReportPreferences.automatic, networkAllowed,
              let auth, let user = auth.currentUser else { return }
        let server = auth.authenticatedServerURL, generation = auth.sessionGeneration
        let owner = server.absoluteString + "|" + user.id
        uploading = true
        uploadTask = Task {
            defer { uploading = false }
            guard let reports = try? await outbox.due(owner: owner, now: Date()) else { return }
            for pending in reports {
                guard !Task.isCancelled, active, DiagnosticReportPreferences.automatic, networkAllowed,
                      generation == auth.sessionGeneration, server == auth.authenticatedServerURL,
                      auth.currentUser?.id == user.id else { return }
                guard (try? await outbox.admitUpload(id: pending.report.id, owner: owner, now: Date())) == true else { continue }
                let restriction = restricted
                do {
                    _ = try await FeedbackUpload.send(pending.report, auth: auth, server: server,
                                                      generation: generation, restrictCellular: restriction, admission: {
                            self.active && DiagnosticReportPreferences.automatic && self.networkAllowed &&
                                !(self.restricted && !restriction)
                        })
                    guard generation == auth.sessionGeneration, !Task.isCancelled else { return }
                    try await outbox.completed(id: pending.report.id, owner: owner)
                    if let crashID = pending.crashID { await UnyriCrashDiagnostics.shared.submitted(crashID, owner: owner) }
                } catch is CancellationError { return }
                catch {
                    var limited = false
                    if case APIError.http(429) = error { limited = true }
                    if case APIError.server(let code, _) = error { limited = code == "rate_limited" }
                    try? await outbox.failed(id: pending.report.id, owner: owner, now: Date(), rateLimited: limited)
                    return
                }
            }
        }
    }
    func eraseForLogout(owner: String) async {
        uploadTask?.cancel(); try? await outbox.discard(owner: owner)
    }
}

@MainActor private struct AutomaticDiagnosticsSession: ViewModifier {
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var phase
    @AppStorage(DiagnosticReportPreferences.automaticKey) private var automatic = DiagnosticReportPreferences.buildDefault
    @ObservedObject private var network = UnyriMediaNetwork.shared
    @ObservedObject private var crashes = UnyriCrashDiagnostics.shared
    @ObservedObject private var settings = UnyriDeviceSettings.shared
    private var identity: String {
        let hasCellularDownloads: Bool = settings.downloads.cellular.values.contains { $0.enabled }
        let components: [String] = [
            String(auth.sessionGeneration), auth.currentUser?.id ?? "", auth.authenticatedServerURL.absoluteString,
            String(describing: phase), String(automatic), String(network.connected), String(network.cellular),
            String(network.constrained), settings.callDataSaving.rawValue, String(hasCellularDownloads)
        ]
        return components.joined(separator: "|")
    }
    func body(content: Content) -> some View {
        content
            .task(id: identity) {
                await AutomaticDiagnosticReports.shared.configure(auth: auth, active: phase == .active)
                if automatic, let crash = crashes.pending { await AutomaticDiagnosticReports.shared.receive(.crash, crash: crash) }
            }
            .onChange(of: crashes.pending) { crash in
                if automatic, let crash { Task { await AutomaticDiagnosticReports.shared.receive(.crash, crash: crash) } }
            }
            .onReceive(NotificationCenter.default.publisher(for: DiagnosticReportEvents.notification)) { value in
                guard let incident = value.object as? DiagnosticIncident else { return }
                let generation = auth.sessionGeneration, server = auth.authenticatedServerURL
                Task {
                    guard generation == auth.sessionGeneration, server == auth.authenticatedServerURL else { return }
                    await AutomaticDiagnosticReports.shared.receive(incident)
                }
            }
    }
}
extension View {
    func automaticDiagnosticsSession() -> some View { modifier(AutomaticDiagnosticsSession()) }
}
