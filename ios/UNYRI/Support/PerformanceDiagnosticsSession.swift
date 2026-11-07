import SwiftUI

/// The task belongs to one authenticated origin and generation. Diagnostics
/// never follow a changed server setting with credentials from an old server.
@MainActor
private struct PerformanceDiagnosticsSession: ViewModifier {
    @EnvironmentObject private var auth: AuthRepository
    @Environment(\.scenePhase) private var phase
    @AppStorage(PerformanceRecorder.enabledKey) private var enabled = false
    @AppStorage(PerformanceRecorder.onlineKey) private var online = false
    private var identity: String {
        "\(enabled)|\(online)|\(phase)|\(auth.sessionGeneration)|\(auth.currentUser?.id ?? "")|\(auth.authenticatedServerURL.absoluteString)"
    }
    func body(content: Content) -> some View {
        content.task(id: identity) { @MainActor in
            let monitor = PerformanceMonitor.shared
            monitor.configure(enabled: enabled, active: phase == .active)
            guard enabled else { return }
            guard let user = auth.currentUser else { return }
            let generation = auth.sessionGeneration
            let server = auth.authenticatedServerURL
            monitor.bind(owner: server.absoluteString + "|" + user.id)
            guard phase == .active, online else {
                monitor.setOnlineStatus("Diagnostics are local only."); return
            }
            let client = APIClient(baseURL: server, preventRedirects: true)
            var delay: UInt64 = 5
            while !Task.isCancelled {
                guard auth.sessionGeneration == generation, auth.currentUser?.id == user.id,
                      auth.authenticatedServerURL == server else { return }
                let snapshot = await Task.detached(priority: .utility) {
                    PerformanceRecorder.shared.snapshot(online: true, maximumBytes: 32 * 1024)
                }.value
                if let snapshot {
                    do {
                        let token = try await auth.currentAccessToken()
                        try Task.checkCancellation()
                        guard auth.sessionGeneration == generation, auth.authenticatedServerURL == server else { return }
                        try await client.postNoContent("/performance", bearerToken: token, body: snapshot)
                        try Task.checkCancellation()
                        guard auth.sessionGeneration == generation else { return }
                        PerformanceRecorder.shared.acknowledge(snapshot)
                        monitor.setOnlineStatus("Live diagnostics are connected."); delay = 5
                    } catch is CancellationError { return }
                    catch {
                        guard !Task.isCancelled, auth.sessionGeneration == generation else { return }
                        monitor.setOnlineStatus("Live diagnostics are unavailable. Recording continues on this phone.")
                        delay = min(60, delay * 2)
                    }
                }
                do { try await Task.sleep(nanoseconds: delay * 1_000_000_000) } catch { return }
            }
        }
    }
}
extension View {
    func performanceDiagnosticsSession() -> some View { modifier(PerformanceDiagnosticsSession()) }
}
