import Foundation
import Combine

/// Every media bubble observes this, so it publishes only when a viewer opens or closes.
/// Frame/image retries belong to the visible consumers of `UnyriAttachmentImages`.
/// Activation only wakes affected failed attachments; successful rows stay idle.
@MainActor
final class UnyriMediaWorkState: ObservableObject {
    static let shared = UnyriMediaWorkState()
    /// Some full-screen viewer is open. Every viewer root and the UIKit presenter hold a
    /// token, so the gallery and draft viewers (fullScreenCover) count as well.
    @Published private(set) var viewerOpen = false
    private var tokens = Set<UUID>()
    private init() {}
    func enter(_ token: UUID) { tokens.insert(token); publish() }
    func leave(_ token: UUID) { tokens.remove(token); publish() }
    private func publish() {
        let open = !tokens.isEmpty
        if viewerOpen != open { viewerOpen = open }
    }
}
