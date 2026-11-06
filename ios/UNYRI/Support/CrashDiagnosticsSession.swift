import SwiftUI
import MetricKit

/// Uses Apple's post-event diagnostic delivery. No signal handler is installed
/// and a force-quit/unclean-exit marker is never called a crash.
@MainActor final class UnyriCrashDiagnostics: ObservableObject {
    static let shared = UnyriCrashDiagnostics()
    @Published private(set) var pending: UnyriCrashRecord?
    @Published private(set) var suggestion: UnyriCrashRecord?
    private var owner: String?
    private var epoch = UUID()
    private var ticket: UnyriCrashArchive.Ticket?
    private var subscriber: UnyriCrashSubscriber?
    private let archive: UnyriCrashArchive

    private init() {
        let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("COCO/Diagnostics/crashes-v1.json")
        archive = UnyriCrashArchive(load: {
            do { return try Data(contentsOf: file) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile { return nil }
        }, save: { data in
            guard let data else {
                if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
                return
            }
            var directory = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        })
    }
    func bind(owner value: String?) async {
        guard owner != value || ticket == nil else { return }
        epoch = UUID(); let current = epoch
        owner = value; ticket = nil; pending = nil; suggestion = nil
        do {
            let bound = try await archive.bind(owner: value)
            guard epoch == current, owner == value else { return }
            ticket = bound
            if value != nil, subscriber == nil {
                let target = UnyriCrashSubscriber()
                subscriber = target; MXMetricManager.shared.add(target)
            }
            await refresh()
        } catch { /* Optional diagnostics must never block login or recovery. */ }
    }
    func eraseForLogout() async {
        epoch = UUID(); owner = nil; ticket = nil; pending = nil; suggestion = nil
        _ = try? await archive.bind(owner: nil)
    }
    fileprivate func receive(_ records: [UnyriCrashRecord]) async {
        guard let ticket else { return }
        for record in records { try? await archive.ingest(record, ticket: ticket) }
        await refresh()
    }
    func markOffered(_ id: String, owner expectedOwner: String) async {
        guard owner == expectedOwner, let ticket else { return }
        try? await archive.offered(id, ticket: ticket); await refresh()
    }
    func submitted(_ id: String, owner expectedOwner: String) async {
        guard owner == expectedOwner, let ticket else { return }
        try? await archive.remove(id, ticket: ticket); await refresh()
    }
    private func refresh() async {
        guard let ticket else { return }; let current = epoch
        let recent = await archive.pending(ticket: ticket, onlyUnoffered: false)
        let unoffered = await archive.pending(ticket: ticket, onlyUnoffered: true)
        guard self.ticket == ticket, epoch == current else { return }
        pending = recent; suggestion = unoffered
    }
}

private final class UnyriCrashSubscriber: NSObject, MXMetricManagerSubscriber {
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        var records: [UnyriCrashRecord] = []
        for payload in payloads.prefix(4) {
            for diagnostic in (payload.crashDiagnostics ?? []).prefix(4) {
                let value = UnyriCrashRecord(from: payload.timeStampBegin, until: payload.timeStampEnd,
                    version: diagnostic.applicationVersion, build: diagnostic.metaData.applicationBuildVersion,
                    exception: diagnostic.exceptionType?.intValue, signal: diagnostic.signal?.intValue,
                    frames: UnyriCrashRecord.frames(from: diagnostic.callStackTree.jsonRepresentation()))
                if value.valid { records.append(value) }
            }
        }
        let captured = records
        Task { @MainActor in await UnyriCrashDiagnostics.shared.receive(captured) }
    }
}

/// Separate presentation boundary avoids adding another large conditional
/// generic tree to LoggedInView and never interrupts an active conversation.
struct UnyriCrashPresentation: ViewModifier {
    let canPrompt: Bool
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var phase
    @ObservedObject private var diagnostics = UnyriCrashDiagnostics.shared
    @State private var showing = false
    @State private var proposed: UnyriCrashRecord?
    @State private var report: UnyriCrashRecord?
    @State private var offered = Set<String>()

    private var owner: String {
        auth.currentUser.map { auth.authenticatedServerURL.absoluteString + "|" + $0.id } ?? ""
    }
    private var ready: Bool {
        canPrompt && phase == .active && !owner.isEmpty && report == nil &&
            diagnostics.suggestion.map { !offered.contains($0.id) } == true
    }
    func body(content: Content) -> some View {
        content
            .onAppear { if ready { proposed = diagnostics.suggestion; showing = true } }
            .task(id: owner) {
                if owner.isEmpty {
                    // Bootstrap is not logout. Keep the durable scope until
                    // authentication has made a definite logged-out decision.
                    if case .loggedOut = auth.state { await diagnostics.bind(owner: nil) }
                } else { await diagnostics.bind(owner: owner) }
            }
            .onChange(of: ready) { value in
                if value { proposed = diagnostics.suggestion; showing = true }
                else if !canPrompt || phase != .active { showing = false }
            }
            .onChange(of: owner) { _ in showing = false; proposed = nil; report = nil; offered.removeAll() }
            .alert("UNYRI closed unexpectedly", isPresented: $showing) {
                Button("Report a problem") {
                    guard let proposed else { return }
                    offered.insert(proposed.id); report = proposed
                    let expectedOwner = owner
                    Task { await diagnostics.markOffered(proposed.id, owner: expectedOwner) }
                }
                Button("Later", role: .cancel) {
                    guard let proposed else { return }
                    offered.insert(proposed.id)
                    let expectedOwner = owner
                    Task { await diagnostics.markOffered(proposed.id, owner: expectedOwner) }
                }
            } message: {
                Text("iOS provided a crash diagnostic. You can review it before sending. Messages, files and passwords are not included.")
            }
            .sheet(item: $report) { record in
                NavigationStack {
                    BugReportView(crash: record)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { report = nil } } }
                }
            }
    }
}
