import SwiftUI
import UIKit

/// UIApplication's idle timer is app-wide, so independent scene/root views
/// share ownership. A late release removes only its own lease and the last
/// release restores the value captured before the first lease.
@MainActor
final class CallDisplayIdleTimerLeaseRegistry {
    static let shared = CallDisplayIdleTimerLeaseRegistry(application: .shared)
    private let application: UIApplication
    private var owners = Set<UUID>()
    private var previousDisabled: Bool?
    var activeLeaseCount: Int { owners.count }

    init(application: UIApplication) { self.application = application }

    func set(wanted: Bool, owner: UUID) {
        if wanted {
            guard owners.insert(owner).inserted else { return }
            if owners.count == 1 {
                previousDisabled = application.isIdleTimerDisabled
                if !application.isIdleTimerDisabled { application.isIdleTimerDisabled = true }
            }
        } else {
            guard owners.remove(owner) != nil else { return }
            if owners.isEmpty {
                let previous = previousDisabled
                previousDisabled = nil
                if let previous, application.isIdleTimerDisabled != previous {
                    application.isIdleTimerDisabled = previous
                }
            }
        }
    }
}

@MainActor
private struct UnyriCallDisplayAwakeModifier: ViewModifier {
    let state: CallUiState
    let mediaState: ConnectedState?
    @Environment(\.scenePhase) private var scenePhase
    @State private var owner = UUID()
    @State private var visible = false

    private var wantsAwake: Bool {
        CallDisplayWakePolicy.keepsAwake(state: state, appActive: scenePhase == .active, mediaState: mediaState)
    }

    func body(content: Content) -> some View {
        content
            .onAppear {
                visible = true
                CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: wantsAwake, owner: owner)
            }
            .onChange(of: wantsAwake) { wanted in
                guard visible else { return }
                CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: wanted, owner: owner)
            }
            .onDisappear {
                visible = false
                CallDisplayIdleTimerLeaseRegistry.shared.set(wanted: false, owner: owner)
            }
    }
}

extension View {
    /// Attach to the authenticated root rather than the call sheet: minimizing
    /// a foreground video call must not relinquish the root's display lease.
    @MainActor
    func unyriCallDisplayAwake(state: CallUiState, mediaState: ConnectedState? = nil) -> some View {
        modifier(UnyriCallDisplayAwakeModifier(state: state, mediaState: mediaState))
    }
}
