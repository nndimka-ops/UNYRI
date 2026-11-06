import Foundation

enum DirectManualFailure: Error { case invalid, revisionExhausted }

/// Pure state only. A snapshot does not grant consent or verify a peer.
struct DirectManualSnapshot: Codable, Equatable, Sendable {
    var pauseRevision: Int64
    var paused: Bool
    var ackPauseRevision: Int64
    private enum CodingKeys: String, CodingKey {
        case pauseRevision = "pause_revision", paused, ackPauseRevision = "ack_pause_revision"
    }
    func validated() throws -> Self {
        guard pauseRevision >= 0, ackPauseRevision >= 0, pauseRevision != 0 || !paused else { throw DirectManualFailure.invalid }
        return self
    }
}

struct DirectManualPauseState: Codable, Equatable, Sendable {
    var localRevision: Int64 = 0
    var localPaused = false
    var peerRevision: Int64 = 0
    var peerPaused = false
    var acknowledgedLocalRevision: Int64 = 0
    /// Highest own revision durably frozen with a ciphertext, not proof of delivery.
    var publishedLocalRevision: Int64 = 0
    var pendingResponseTo: String?
    var syncNeeded = false
    var syncProbeCount = 0
    var nextSyncAt: Int64 = 0
    var syncCycleStartedAt: Int64 = 0
    private enum CodingKeys: String, CodingKey {
        case localRevision, localPaused, peerRevision, peerPaused, acknowledgedLocalRevision, publishedLocalRevision
        case pendingResponseTo, syncNeeded, syncProbeCount, nextSyncAt, syncCycleStartedAt
    }

    func validated() throws -> Self {
        guard localRevision >= 0, peerRevision >= 0, acknowledgedLocalRevision >= 0,
              acknowledgedLocalRevision <= localRevision,
              publishedLocalRevision >= 0, publishedLocalRevision <= localRevision,
              localRevision != 0 || !localPaused, peerRevision != 0 || !peerPaused,
              pendingResponseTo == nil || Self.canonicalUUID(pendingResponseTo!),
              (0...DirectManualTiming.maximumProbes).contains(syncProbeCount), nextSyncAt >= 0, syncCycleStartedAt >= 0 else {
            throw DirectManualFailure.invalid
        }
        return self
    }
    var manualByteEligible: Bool { (try? validated()) != nil && !localPaused && !peerPaused && acknowledgedLocalRevision == localRevision }
    var syncLimitReached: Bool { syncProbeCount >= DirectManualTiming.maximumProbes }
    func snapshot() -> DirectManualSnapshot { DirectManualSnapshot(pauseRevision: localRevision, paused: localPaused, ackPauseRevision: peerRevision) }
    func changingLocal(paused: Bool, terminal: Bool = false, verifying: Bool = false) throws -> Self {
        _ = try validated()
        guard !terminal, !verifying, paused != localPaused else { return self }
        guard localRevision < Int64.max else { throw DirectManualFailure.revisionExhausted }
        var next = self; next.localRevision += 1; next.localPaused = paused; next.syncNeeded = true
        return next
    }
    func applying(snapshot: DirectManualSnapshot, requestAck: Bool = false, terminal: Bool = false) throws -> DirectManualReduction {
        _ = try validated()
        if terminal { return DirectManualReduction(state: self, peerChanged: false, ackAdvanced: false, needsResponse: false) }
        _ = try snapshot.validated()
        guard snapshot.ackPauseRevision <= localRevision,
              snapshot.pauseRevision != peerRevision || snapshot.paused == peerPaused else { throw DirectManualFailure.invalid }
        let peerChanged = snapshot.pauseRevision > peerRevision
        let ackAdvanced = snapshot.ackPauseRevision > acknowledgedLocalRevision
        var next = self
        if peerChanged { next.peerRevision = snapshot.pauseRevision; next.peerPaused = snapshot.paused }
        next.acknowledgedLocalRevision = max(acknowledgedLocalRevision, snapshot.ackPauseRevision)
        return DirectManualReduction(state: next, peerChanged: peerChanged, ackAdvanced: ackAdvanced, needsResponse: requestAck || peerChanged)
    }
    private static func canonicalUUID(_ value: String) -> Bool { UUID(uuidString: value)?.uuidString.lowercased() == value }
}

struct DirectManualReduction: Equatable, Sendable {
    var state: DirectManualPauseState
    var peerChanged: Bool
    var ackAdvanced: Bool
    var needsResponse: Bool
}

extension DirectManualPauseState {
    init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        self.init(localRevision: try value.decode(Int64.self, forKey: .localRevision),
                  localPaused: try value.decode(Bool.self, forKey: .localPaused),
                  peerRevision: try value.decode(Int64.self, forKey: .peerRevision),
                  peerPaused: try value.decode(Bool.self, forKey: .peerPaused),
                  acknowledgedLocalRevision: try value.decode(Int64.self, forKey: .acknowledgedLocalRevision),
                  publishedLocalRevision: try value.decodeIfPresent(Int64.self, forKey: .publishedLocalRevision) ?? 0,
                  pendingResponseTo: try value.decodeIfPresent(String.self, forKey: .pendingResponseTo),
                  syncNeeded: try value.decodeIfPresent(Bool.self, forKey: .syncNeeded) ?? false,
                  syncProbeCount: try value.decodeIfPresent(Int.self, forKey: .syncProbeCount) ?? 0,
                  nextSyncAt: try value.decodeIfPresent(Int64.self, forKey: .nextSyncAt) ?? 0,
                  syncCycleStartedAt: try value.decodeIfPresent(Int64.self, forKey: .syncCycleStartedAt) ?? 0)
        _ = try validated()
    }
}

enum DirectManualTiming {
    static let maximumProbes = 6
    static let resetCooldownMillis: Int64 = 30_000
    private static let delays: [Int64] = [0, 2_000, 4_000, 8_000, 16_000, 32_000]
    static func probeDelayMillis(index: Int) throws -> Int64 {
        guard delays.indices.contains(index) else { throw DirectManualFailure.invalid }; return delays[index]
    }
    static func canResetCycle(nowMillis: Int64, startedAtMillis: Int64) -> Bool {
        nowMillis >= 0 && startedAtMillis >= 0 && nowMillis >= startedAtMillis &&
            (startedAtMillis == 0 || nowMillis - startedAtMillis >= resetCooldownMillis)
    }
}

enum DirectManualWire {
    static func validate(type: String, revision: Int64?, paused: Bool?, ack: Int64?, requestAck: Bool?) throws {
        let count = (revision == nil ? 0 : 1) + (paused == nil ? 0 : 1) + (ack == nil ? 0 : 1)
        if type == "pause_state" { guard count == 3, requestAck != nil else { throw DirectManualFailure.invalid } }
        else if ["accept", "resume", "offer", "answer"].contains(type) {
            guard (count == 0 || count == 3), requestAck == nil else { throw DirectManualFailure.invalid }
        } else { guard count == 0, requestAck == nil else { throw DirectManualFailure.invalid } }
        if let revision, let paused, let ack { _ = try DirectManualSnapshot(pauseRevision: revision, paused: paused, ackPauseRevision: ack).validated() }
    }
}

struct DirectSignalIdentity: Codable, Equatable, Hashable, Sendable {
    var senderDeviceId: String
    var clientSignalId: String
    func validated() throws -> Self {
        guard [senderDeviceId, clientSignalId].allSatisfy({ UUID(uuidString: $0)?.uuidString.lowercased() == $0 }) else { throw DirectManualFailure.invalid }
        return self
    }
}

/// GLOBAL mailbox sequence is intentionally absent: it is a cursor, not capacity.
struct DirectSignalBudget: Codable, Equatable, Sendable {
    static let maximumIdentities = 256
    static let optionalLimit = 224
    static let maximumPending = 8
    static let optionalPendingLimit = 6
    var known: Bool
    var identities: [DirectSignalIdentity]
    var overflowed = false
    private enum CodingKeys: String, CodingKey { case known, identities, overflowed }
    static func fresh() -> Self { Self(known: true, identities: []) }
    static func unknown() -> Self { Self(known: false, identities: []) }
    func validated() throws -> Self {
        guard identities.count <= Self.maximumIdentities, Set(identities).count == identities.count else { throw DirectManualFailure.invalid }
        for value in identities { _ = try value.validated() }; return self
    }
    var debits: Int { identities.count }
    func recording(identity: DirectSignalIdentity) throws -> Self {
        _ = try validated(); _ = try identity.validated()
        if identities.contains(identity) { return self }
        var next = self
        if identities.count < Self.maximumIdentities { next.identities.append(identity) } else { next.overflowed = true }
        return next
    }
    func mayFreezeOptional(pendingCount: Int) -> Bool { (try? validated()) != nil && known && !overflowed && debits < Self.optionalLimit && (0..<Self.optionalPendingLimit).contains(pendingCount) }
    func mayFreezeTerminal(pendingCount: Int) -> Bool { (0..<Self.maximumPending).contains(pendingCount) }
}

extension DirectSignalBudget {
    init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        self.init(known: try value.decode(Bool.self, forKey: .known),
                  identities: try value.decode([DirectSignalIdentity].self, forKey: .identities),
                  overflowed: try value.decodeIfPresent(Bool.self, forKey: .overflowed) ?? false)
        _ = try validated()
    }
}
