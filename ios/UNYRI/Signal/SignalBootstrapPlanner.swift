import Foundation

/// The decision the bootstrap planner reaches. Pure data — no crypto.
struct SignalBootstrapPlan: Equatable {
    var needsIdentityGeneration: Bool
    var needsInitialUpload: Bool
    var needsPreKeyReplenishment: Bool
    var replenishmentBatchSize: Int

    var isReady: Bool {
        !needsIdentityGeneration && !needsInitialUpload && !needsPreKeyReplenishment
    }
}

/// Deterministic bootstrap planning, mirroring Android's
/// `SignalSessionManager.bootstrapKeysIfNeeded`. This layer only decides
/// WHAT the future crypto manager must do — it never performs crypto.
enum SignalBootstrapPlanner {
    static let preKeyLowWatermark = 5
    static let replenishmentBatchSize = 20

    static func plan(
        hasLocalIdentity: Bool,
        initialUploadCompleted: Bool,
        localOneTimePreKeyCount: Int,
        serverOneTimePreKeyCount: Int
    ) -> SignalBootstrapPlan {
        let needsIdentity = !hasLocalIdentity
        let needsUpload = !needsIdentity && !initialUploadCompleted
        // The LOCAL pool is authoritative for decryptability; the server pool
        // is authoritative for what peers can fetch. Top up when either side
        // is running low (mirrors Android).
        let preKeysLow = serverOneTimePreKeyCount < preKeyLowWatermark
            || localOneTimePreKeyCount < preKeyLowWatermark
        let needsReplenishment = !needsIdentity && !needsUpload && preKeysLow

        return SignalBootstrapPlan(
            needsIdentityGeneration: needsIdentity,
            needsInitialUpload: needsUpload,
            needsPreKeyReplenishment: needsReplenishment,
            replenishmentBatchSize: replenishmentBatchSize
        )
    }
}
