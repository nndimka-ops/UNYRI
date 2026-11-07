import Foundation

enum DirectFileDestinationPolicy {
    enum ImportRoute { case ordinary, direct, unsupportedLarge }
    static let unavailable = "Direct files can be sent only to another person. Files up to 100 MB can still be sent normally."
    static func issue(chatType: String, recipient: String?, owner: String, blocked: Bool) -> String? {
        if blocked { return "Unblock this conversation before sending a direct file." }
        guard chatType == "direct", let recipient, recipient != owner,
              UUID(uuidString: recipient)?.uuidString.lowercased() == recipient else { return unavailable }
        return nil
    }
    static func importRoute(size: Int64?, supportsDirect: Bool, ordinaryLimit: Int64, prefersDirect: Bool = false) -> ImportRoute {
        if prefersDirect { return supportsDirect ? .direct : .unsupportedLarge }
        if let size, size > ordinaryLimit { return supportsDirect ? .direct : .unsupportedLarge }
        return size == nil && supportsDirect ? .direct : .ordinary
    }
    static func directSourceIssue(size: Int64?) -> String? {
        guard let size else { return nil }
        if size == 0 { return "Empty files cannot be transferred directly." }
        if size < 0 { return "Could not read this file. Keep it selected and try again." }
        if size > DirectFileOffer.maximumBytes { return "This file exceeds the 64 GB direct-transfer limit." }
        return nil
    }
}

/// Metadata is carried inside the existing Signal-encrypted chat message. It
/// contains no URL, upload identifier or reusable download capability.
struct DirectFileOffer: Codable, Equatable {
    static let maximumBytes: Int64 = 64 * 1024 * 1024 * 1024
    static let lifetimeSeconds: Int64 = 7 * 86400
    var version: Int = 1
    var transferId: String
    var offerClientMessageId: String
    var senderUserId: String
    var senderDeviceId: String
    var recipientUserId: String
    var offerNonce: String
    var filename: String
    var mimeType: String
    var size: Int64
    var sha256: String
    var expiresAt: Int64
    var iceCandidatesVersion: Int? = nil
    var preview: DirectFileMediaPreview? = nil

    static let fallbackText = "Direct file transfer"
    static func caption(in text: String?) -> String? {
        guard let text else { return nil }
        for prefix in [fallbackText, "Передача файла напрямую"] where text.hasPrefix(prefix + "\n\n") {
            let value = String(text.dropFirst(prefix.count + 2))
            return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : value
        }
        return nil
    }
    private enum CodingKeys: String, CodingKey {
        case version, filename, size, sha256, preview
        case transferId = "transfer_id", offerClientMessageId = "offer_client_message_id"
        case senderUserId = "sender_user_id", senderDeviceId = "sender_device_id"
        case recipientUserId = "recipient_user_id", mimeType = "mime_type", expiresAt = "expires_at"
        case offerNonce = "offer_nonce"
        case iceCandidatesVersion = "ice_candidates_version"
    }

    var hasValidShape: Bool {
        let identifiers = [transferId, offerClientMessageId, senderUserId, senderDeviceId, recipientUserId]
        return version == 1 && Self.nonceBytes(offerNonce) != nil && identifiers.allSatisfy { UUID(uuidString: $0)?.uuidString.lowercased() == $0 } &&
            senderUserId != recipientUserId && size > 0 && size <= Self.maximumBytes &&
            !filename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && filename.utf8.count <= 255 &&
            filename != "." && filename != ".." &&
            !filename.contains(where: { $0 == "/" || $0 == "\\" || $0.unicodeScalars.contains(where: Self.unsafeScalar) }) &&
            mimeType.utf8.count <= 127 && mimeType.range(of: "^[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+$", options: .regularExpression) != nil &&
            sha256.utf8.count == 64 && sha256.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) } &&
            expiresAt > 0 && expiresAt <= 253_402_300_799
    }

    static func nonceBytes(_ value: String) -> Data? {
        guard value.utf8.count == 44, let bytes = Data(base64Encoded: value), bytes.count == 32,
              bytes.base64EncodedString() == value else { return nil }
        return bytes
    }
    func matchesNonce(_ value: String) -> Bool {
        guard let expected = Self.nonceBytes(offerNonce), let actual = Self.nonceBytes(value) else { return false }
        return zip(expected, actual).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    private static func unsafeScalar(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value < 32 || (127...159).contains(scalar.value) || scalar.value == 0x200E || scalar.value == 0x200F ||
            (0x202A...0x202E).contains(scalar.value) || (0x2066...0x2069).contains(scalar.value)
    }

    static func safeFilename(_ source: String) -> String {
        let name = source.replacingOccurrences(of: "\\", with: "/").split(separator: "/").last.map(String.init) ?? "File"
        var clean = String(String.UnicodeScalarView(name.unicodeScalars.filter { !unsafeScalar($0) }))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while clean.utf8.count > 255 { clean.removeLast() }
        return clean.isEmpty || clean == "." || clean == ".." ? "File" : clean
    }
}

enum DirectFilePacket {
    enum Failure: Error { case invalid }
    enum Kind: UInt8 { case chunk = 1, durableAck = 2, finish = 3, completeAck = 4 }
    struct Frame: Equatable {
        let kind: Kind
        let offset: Int64
        let body: Data
    }
    static let chunkBytes = 16 * 1024
    static let headerBytes = 14
    struct AckStats: Equatable, Sendable {
        let sample: UInt64
        let speedBytesPerSecond: UInt64
        var encoded: Data {
            var bytes = Data()
            for value in [sample, speedBytesPerSecond] {
                for shift in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: value >> shift)) }
            }
            return bytes
        }
        static func decode(_ data: Data) -> Self? {
            guard data.count == 16 else { return nil }
            let raw = Array(data); var values: [UInt64] = []
            for begin in [0, 8] {
                var number: UInt64 = 0
                for index in begin..<begin + 8 { number = (number << 8) | UInt64(raw[index]) }
                values.append(number)
            }
            guard values[0] > 0, values[0] <= UInt64(Int64.max),
                  values[1] <= UInt64(DirectFileOffer.maximumBytes) else { return nil }
            return Self(sample: values[0], speedBytesPerSecond: values[1])
        }
    }

    static func encode(_ frame: Frame) throws -> Data {
        guard valid(frame) else { throw Failure.invalid }
        var result = Data([1, frame.kind.rawValue])
        let offset = UInt64(frame.offset)
        for shift in stride(from: 56, through: 0, by: -8) { result.append(UInt8(truncatingIfNeeded: offset >> shift)) }
        let length = UInt32(frame.body.count)
        for shift in stride(from: 24, through: 0, by: -8) { result.append(UInt8(truncatingIfNeeded: length >> shift)) }
        result.append(frame.body)
        return result
    }

    static func decode(_ bytes: Data) throws -> Frame {
        guard bytes.count >= headerBytes, bytes.count <= 32 * 1024 else { throw Failure.invalid }
        let raw = [UInt8](bytes)
        guard raw[0] == 1, let kind = Kind(rawValue: raw[1]) else { throw Failure.invalid }
        var offset: UInt64 = 0
        for index in 2..<10 { offset = (offset << 8) | UInt64(raw[index]) }
        var length: UInt32 = 0
        for index in 10..<14 { length = (length << 8) | UInt32(raw[index]) }
        guard offset <= UInt64(Int64.max), Int(length) == bytes.count - headerBytes else { throw Failure.invalid }
        let frame = Frame(kind: kind, offset: Int64(offset), body: Data(raw.dropFirst(headerBytes)))
        guard valid(frame) else { throw Failure.invalid }
        return frame
    }

    private static func valid(_ frame: Frame) -> Bool {
        guard frame.offset >= 0 else { return false }
        switch frame.kind {
        case .chunk: return !frame.body.isEmpty && frame.body.count <= chunkBytes && frame.offset <= Int64.max - Int64(frame.body.count)
        case .durableAck: return frame.body.isEmpty || AckStats.decode(frame.body) != nil
        case .finish, .completeAck: return frame.body.count == 32
        }
    }
}

struct DirectFileProgress: Equatable, Sendable {
    var offset: Int64
    var sample: UInt64 = 0
    var speedBytesPerSecond: UInt64 = 0
    func remainingSeconds(total: Int64) -> Int64? {
        guard offset >= 0, total >= offset, total <= DirectFileOffer.maximumBytes,
              speedBytesPerSecond > 0, speedBytesPerSecond <= UInt64(DirectFileOffer.maximumBytes) else { return nil }
        let remaining = UInt64(total - offset)
        return Int64(remaining / speedBytesPerSecond + (remaining % speedBytesPerSecond == 0 ? 0 : 1))
    }
}

struct DirectFileRateSampler: Sendable {
    private var baselineOffset: Int64
    private var baselineTime: TimeInterval
    private var measured = false
    private var last = DirectFileProgress(offset: 0)
    init(offset: Int64, uptime: TimeInterval) {
        baselineOffset = offset; baselineTime = uptime; last.offset = offset
    }
    mutating func acknowledge(offset: Int64, uptime: TimeInterval) throws -> DirectFileProgress {
        guard offset >= last.offset, offset <= DirectFileOffer.maximumBytes,
              uptime.isFinite, uptime >= baselineTime, last.sample < UInt64(Int64.max) else { throw DirectFilePacket.Failure.invalid }
        let elapsed = uptime - baselineTime
        if elapsed >= 0.25 {
            let instantaneous = UInt64(min(Double(DirectFileOffer.maximumBytes), max(0, Double(offset - baselineOffset) / elapsed)))
            last.speedBytesPerSecond = measured ? (last.speedBytesPerSecond * 3 + instantaneous) / 4 : instantaneous
            measured = true; baselineTime = uptime; baselineOffset = offset
        }
        last.offset = offset; last.sample += 1
        return last
    }
    /// An idle receiver explicitly reports a stopped data stream, rather than
    /// leaving an old MB/s and an invented ETA visible for the whole timeout.
    mutating func idle(uptime: TimeInterval) throws -> DirectFileProgress {
        guard uptime.isFinite, uptime >= baselineTime else { throw DirectFilePacket.Failure.invalid }
        if uptime - baselineTime >= 1 {
            last.speedBytesPerSecond = 0; measured = false
            baselineTime = uptime; baselineOffset = last.offset
        }
        guard last.sample < UInt64(Int64.max) else { throw DirectFilePacket.Failure.invalid }
        last.sample += 1
        return last
    }
}

enum DirectFilePhase: String, Codable {
    case offered, awaitingAcceptance, accepted, connecting, transferring, paused, verifying, completed, rejected, cancelled, expired, failed
    var terminal: Bool { [.completed, .rejected, .cancelled, .expired].contains(self) }
    var label: String {
        switch self {
        case .offered, .awaitingAcceptance: return "Waiting for recipient"
        case .accepted, .connecting: return "Connecting directly"
        case .transferring: return "Transferring"
        case .paused: return "Transfer paused"
        case .verifying: return "Verifying file"
        case .completed: return "Received"
        case .rejected: return "Declined"
        case .cancelled: return "Cancelled"
        case .expired: return "Offer expired"
        case .failed: return "Could not connect directly"
        }
    }
}

struct DirectFileRecord: Codable, Equatable, Identifiable {
    var id: String { offer.transferId }
    var chatId: String
    var offer: DirectFileOffer
    var outgoing: Bool
    var phase: DirectFilePhase
    var peerDeviceId: String?
    var durableOffset: Int64 = 0
    var attemptId: String?
    var errorCode: String?
    var updatedAt: Int64
    var mailboxRegistered = false
    var signalCursor: Int64 = 0
    var seenSignals: [String] = []
    var incomingControls: [DirectFileControl] = []
    var outgoingSignals: [DirectFileFrozenSignal] = []
    var consentGiven = false
    var localDeviceId: String = ""
    var mailboxClosed = false
    var usedAttempts: [String] = []
    var completionControlQueued = false
    var completionAckQueued = false
    var completionReceived = false
    var completionRecoveryCount = 0
    var completionRecoveryNextAt: Int64?
    var pendingInbound: DirectFileSignalEnvelope?
    var pausedForLocalCall = false
    var completionRecoveryTriggers: [String] = []
    var peerVerified = false
    var senderRequest: DirectFileControl?
    var requestAcknowledged = false
    var requestStartedAt: Int64?
    var requestRetryCount = 0
    var requestNextRetryAt: Int64?
    var requestAckSent = false
    var manualPause: DirectManualPauseState?
    var signalBudget: DirectSignalBudget?
    var queue: DirectFileQueueState?
    var continuedProcessing: DirectFileContinuationMarker?
    var ice: DirectFileIceState?
    var iceLegacyOnly: Bool?
}

struct DirectFileSignalEnvelope: Codable, Equatable, Sendable {
    var sequence: Int64
    var clientSignalId: String
    var senderUserId: String
    var senderDeviceId: String
    var recipientDeviceId: String
    var messageType: Int
    var ciphertext: String
}

struct DirectFileControl: Codable, Equatable {
    enum Kind: String, Codable { case accept, offer, answer, resume, pause, pauseState = "pause_state", cancel, reject, complete, completeAck = "complete_ack", iceCandidates = "ice_candidates" }
    var version = 1
    var transferId: String
    var chatId: String
    var offerClientMessageId: String
    var offerNonce: String
    var clientSignalId: String
    var senderUserId: String
    var senderDeviceId: String
    var recipientUserId: String
    var recipientDeviceId: String
    var attemptId: String
    var type: Kind
    var sdp: String?
    var offset: Int64?
    var prefixSHA256: String?
    var reason: String?
    var pauseRevision: Int64?
    var paused: Bool?
    var ackPauseRevision: Int64?
    var requestAck: Bool?
    var iceCandidatesVersion: Int? = nil
    var iceBatch: DirectFileIceBatch? = nil

    private enum CodingKeys: String, CodingKey {
        case version, type, sdp, offset, reason
        case iceCandidatesVersion = "ice_candidates_version", iceBatch = "ice"
        case transferId = "transfer_id", chatId = "chat_id", offerClientMessageId = "offer_client_message_id"
        case clientSignalId = "client_signal_id", senderUserId = "sender_user_id", senderDeviceId = "sender_device_id"
        case offerNonce = "offer_nonce"
        case recipientUserId = "recipient_user_id", recipientDeviceId = "recipient_device_id"
        case attemptId = "attempt_id", prefixSHA256 = "prefix_sha256"
        case pauseRevision = "pause_revision", paused, ackPauseRevision = "ack_pause_revision", requestAck = "request_ack"
    }

    var validShape: Bool {
        guard (try? DirectManualWire.validate(type: type.rawValue, revision: pauseRevision, paused: paused,
                                              ack: ackPauseRevision, requestAck: requestAck)) != nil,
            version == 1, DirectFileOffer.nonceBytes(offerNonce) != nil,
            [transferId, chatId, offerClientMessageId, clientSignalId, senderUserId,
            senderDeviceId, recipientUserId, recipientDeviceId, attemptId].allSatisfy({ UUID(uuidString: $0)?.uuidString.lowercased() == $0 }),
            senderUserId != recipientUserId, offset == nil || (0...DirectFileOffer.maximumBytes).contains(offset!),
            sdp == nil || sdp!.utf8.count <= 32 * 1024,
            reason == nil || Self.reasons.contains(reason!) else { return false }
        if let prefixSHA256 { guard prefixSHA256.utf8.count == 64 && prefixSHA256.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else { return false } }
        if type == .iceCandidates {
            return iceCandidatesVersion == 1 && iceBatch?.valid == true && sdp == nil && offset == nil && prefixSHA256 == nil && reason == nil
        }
        guard iceBatch == nil,
              iceCandidatesVersion == nil || [.accept, .resume, .offer, .answer].contains(type) && iceCandidatesVersion == 1 else { return false }
        switch type {
        case .pauseState: return sdp == nil && offset == nil && prefixSHA256 == nil && reason == nil
        case .accept, .resume: return offset != nil && prefixSHA256 != nil && sdp == nil
        case .offer, .answer: return sdp != nil && offset == nil && prefixSHA256 == nil
        case .complete, .completeAck: return offset != nil && prefixSHA256 != nil && sdp == nil
        default: return sdp == nil && offset == nil && prefixSHA256 == nil
        }
    }

    static let reasons: Set<String> = ["network_waiting", "connection_lost", "direct_unavailable", "prefix_mismatch", "source_missing", "source_changed", "disk_full", "integrity_failed", "busy", "queued", "call_in_progress", "reconnect_required", "security_error", "storage_error", "user_cancelled", "user_declined", "background", "disconnected", "no_direct_route", "integrity_mismatch", "expired", "retry", "verification_failed", "unsupported", "account_changed", "permission_denied"]

    func validatedManualSnapshot() throws -> DirectManualSnapshot? {
        try DirectManualWire.validate(type: type.rawValue, revision: pauseRevision, paused: paused,
                                      ack: ackPauseRevision, requestAck: requestAck)
        guard let pauseRevision, let paused, let ackPauseRevision else { return nil }
        return DirectManualSnapshot(pauseRevision: pauseRevision, paused: paused, ackPauseRevision: ackPauseRevision)
    }

    /// Additional pure authorization gate; caller still validates envelope, nonce and exact context.
    func allowsManualContext(record: DirectFileRecord) -> Bool {
        guard let snapshot = try? validatedManualSnapshot() else {
            // Absent piggyback is no observation, not an unpause.
            return pauseRevision == nil && paused == nil && ackPauseRevision == nil && requestAck == nil && type != .pauseState
        }
        guard (try? (record.manualPause ?? DirectManualPauseState()).applying(snapshot: snapshot, terminal: record.phase.terminal)) != nil else { return false }
        if type == .pauseState {
            return record.peerDeviceId == senderDeviceId && (record.outgoing ? record.peerVerified :
                record.consentGiven && record.mailboxRegistered && record.localDeviceId == recipientDeviceId && senderDeviceId == record.offer.senderDeviceId)
        }
        return true
    }

    /// Validate direction and offer-sized metadata before the durable event
    /// can authorize peer pinning, source access or any connection effect.
    func allowsContext(offer: DirectFileOffer, outgoing: Bool) -> Bool {
        let allowed: Set<Kind> = outgoing
            ? [.accept, .resume, .answer, .pause, .pauseState, .cancel, .reject, .complete, .iceCandidates]
            : [.offer, .pause, .pauseState, .cancel, .completeAck, .iceCandidates]
        guard allowed.contains(type), offset == nil || offset! <= offer.size else { return false }
        if type == .iceCandidates { return offer.iceCandidatesVersion == 1 && iceCandidatesVersion == 1 && iceBatch?.valid == true }
        if type == .complete || type == .completeAck {
            return offset == offer.size && prefixSHA256 == offer.sha256
        }
        return true
    }
}

struct DirectFileFrozenSignal: Codable, Equatable {
    var clientSignalId: String
    var recipientDeviceId: String
    var messageType: Int
    var ciphertext: String
    var iceAttemptId: String? = nil
    var iceOrdinal: Int? = nil
    var iceSDPAttemptId: String? = nil
}

enum DirectFileIceFailure: Error { case invalid, capacity, stale }
enum DirectFileIceMode: Int, Codable { case legacy = 0, awaitingConfirmation = 1, trickle = 2 }

struct DirectFileIceCandidate: Codable, Equatable, Hashable {
    var sdp: String
    var sdpMid: String?
    var sdpMLineIndex: Int
    var ufrag: String?
    private enum CodingKeys: String, CodingKey {
        case sdp, ufrag
        case sdpMid = "sdp_mid", sdpMLineIndex = "sdp_mline_index"
    }
    var textBytes: Int { sdp.utf8.count + (sdpMid?.utf8.count ?? 0) + (ufrag?.utf8.count ?? 0) }
    var validBase: Bool {
        sdpMLineIndex == 0 && DirectFilePeerPolicy.directCandidate(sdp) &&
            (sdpMid == nil || Self.validMid(sdpMid!))
    }
    var valid: Bool {
        guard validBase, let ufrag, Self.validFragment(ufrag) else { return false }
        let fields = sdp.split(whereSeparator: { $0 == " " || $0 == "\t" })
        for index in stride(from: 8, to: fields.count, by: 2) where fields[index] == "ufrag" {
            guard String(fields[index + 1]) == ufrag else { return false }
        }
        return true
    }
    static func validFragment(_ value: String) -> Bool {
        (4...256).contains(value.utf8.count) && value.unicodeScalars.allSatisfy {
            (65...90).contains($0.value) || (97...122).contains($0.value) ||
            (48...57).contains($0.value) || $0.value == 43 || $0.value == 47
        }
    }
    static func validMid(_ value: String) -> Bool {
        (1...64).contains(value.utf8.count) && value.unicodeScalars.allSatisfy { (33...126).contains($0.value) }
    }
}

struct DirectFileIceBatch: Codable, Equatable {
    var version = 1
    var ordinal: Int
    var candidates: [DirectFileIceCandidate]
    var complete: Bool
    var textBytes: Int { candidates.reduce(0) { $0 + $1.textBytes } }
    var valid: Bool {
        version == 1 && (0..<8).contains(ordinal) && candidates.count <= 8 &&
            (!candidates.isEmpty || complete) && Set(candidates).count == candidates.count &&
            candidates.allSatisfy(\.valid) && textBytes <= 34 * 1024
    }
}

struct DirectFileIceAdmission: Codable, Equatable {
    var clientSignalId: String
    var batch: DirectFileIceBatch
    var valid: Bool { DirectFileIceState.uuid(clientSignalId) && batch.valid }
}
struct DirectFileIceReceipt: Codable, Equatable {
    var clientSignalId: String
    var attemptId: String
    var ordinal: Int
    var valid: Bool { DirectFileIceState.uuid(clientSignalId) && DirectFileIceState.uuid(attemptId) && (0..<8).contains(ordinal) }
}

/// Private bounded journal state. Candidates have no authority to create a peer,
/// grant consent, change a manual hold, select a relay or acknowledge file bytes.
struct DirectFileIceState: Codable, Equatable {
    var attemptId: String
    var mode: DirectFileIceMode
    var requested = false
    var localReady = false
    var remoteReady = false
    var localFragment: String?
    var remoteFragment: String?
    var remoteMid: String?
    var localComplete = false
    var localClosed = false
    var nextOrdinal = 0
    var localSeen: [DirectFileIceCandidate] = []
    var localPending: [DirectFileIceCandidate] = []
    var remoteSeen: [DirectFileIceCandidate] = []
    var remotePending: [DirectFileIceCandidate] = []
    var remoteBatches: [DirectFileIceBatch] = []
    var admission: DirectFileIceAdmission?
    var receipts: [DirectFileIceReceipt] = []

    static func uuid(_ value: String) -> Bool { UUID(uuidString: value)?.uuidString.lowercased() == value }
    static func canRequest(offerVersion: Int?, budget: DirectSignalBudget?, pendingCount: Int, legacyOnly: Bool) -> Bool {
        offerVersion == 1 && !legacyOnly && budget?.known == true && budget?.overflowed == false &&
            (budget?.debits ?? Int.max) <= DirectSignalBudget.optionalLimit - 24 && pendingCount < 3
    }
    static func sender(attempt: String, offerVersion: Int?, acceptedVersion: Int?, canUse: Bool) -> Self {
        Self(attemptId: attempt, mode: offerVersion == 1 && acceptedVersion == 1 && canUse ? .trickle : .legacy)
    }
    static func receiver(attempt: String, requested: Bool) -> Self {
        Self(attemptId: attempt, mode: requested ? .awaitingConfirmation : .legacy, requested: requested)
    }
    var valid: Bool {
        guard Self.uuid(attemptId), (0...8).contains(nextOrdinal), receipts.count == nextOrdinal,
              receipts.allSatisfy({ $0.valid && $0.attemptId == attemptId }),
              receipts.map(\.ordinal) == Array(0..<nextOrdinal), Set(receipts.map(\.clientSignalId)).count == receipts.count,
              localSeen.count <= 32, remoteSeen.count <= 32, localPending.count <= 32, remotePending.count <= 32,
              Set(localSeen).count == localSeen.count, Set(remoteSeen).count == remoteSeen.count,
              Set(localPending).count == localPending.count, Set(remotePending).count == remotePending.count,
              localSeen.allSatisfy(\.valid), remoteSeen.allSatisfy(\.valid),
              localSeen.reduce(0, { $0 + $1.textBytes }) <= 64 * 1024,
              remoteSeen.reduce(0, { $0 + $1.textBytes }) <= 64 * 1024,
              localPending.allSatisfy({ localSeen.contains($0) }), remotePending.allSatisfy({ remoteSeen.contains($0) }),
              remoteBatches.count <= 8, Set(remoteBatches.map(\.ordinal)).count == remoteBatches.count,
              remoteBatches.allSatisfy(\.valid), remoteBatches.reduce(0, { $0 + $1.textBytes }) <= 128 * 1024,
              localFragment == nil || DirectFileIceCandidate.validFragment(localFragment!),
              remoteFragment == nil || DirectFileIceCandidate.validFragment(remoteFragment!),
              remoteMid == nil || DirectFileIceCandidate.validMid(remoteMid!) else { return false }
        if let admission {
            guard admission.valid, admission.batch.ordinal == nextOrdinal,
                  Array(localPending.prefix(admission.batch.candidates.count)) == admission.batch.candidates,
                  !receipts.contains(where: { $0.clientSignalId == admission.clientSignalId }) else { return false }
        }
        return (!localReady || localFragment != nil) && (!remoteReady || remoteFragment != nil)
    }
    mutating func confirm(_ version: Int?) throws {
        if mode == .awaitingConfirmation {
            guard version == nil || version == 1 else { throw DirectFileIceFailure.invalid }
            mode = version == 1 && requested ? .trickle : .legacy
            if mode == .legacy { remotePending = []; remoteSeen = []; remoteBatches = [] }
        } else {
            guard (mode == .trickle && version == 1) || (mode == .legacy && version == nil) else { throw DirectFileIceFailure.stale }
        }
    }
    mutating func queueLocal(_ candidate: DirectFileIceCandidate) throws {
        guard mode == .trickle, candidate.valid, !localClosed else { throw DirectFileIceFailure.stale }
        if localSeen.contains(candidate) { return }
        guard localSeen.count < 32, localSeen.reduce(0, { $0 + $1.textBytes }) + candidate.textBytes <= 64 * 1024 else { throw DirectFileIceFailure.capacity }
        localSeen.append(candidate); localPending.append(candidate)
    }
    mutating func receive(_ batch: DirectFileIceBatch) throws {
        guard mode == .trickle || mode == .awaitingConfirmation, batch.valid else { throw DirectFileIceFailure.stale }
        if let prior = remoteBatches.first(where: { $0.ordinal == batch.ordinal }) {
            guard prior == batch else { throw DirectFileIceFailure.invalid }; return
        }
        if let last = remoteBatches.first(where: \.complete) {
            guard batch.ordinal < last.ordinal else { throw DirectFileIceFailure.invalid }
        }
        if batch.complete, remoteBatches.contains(where: { $0.ordinal > batch.ordinal }) { throw DirectFileIceFailure.invalid }
        var next = self
        next.remoteBatches.append(batch)
        for candidate in batch.candidates where !next.remoteSeen.contains(candidate) {
            next.remoteSeen.append(candidate)
            if !remoteReady || candidate.ufrag == remoteFragment && (candidate.sdpMid == nil || candidate.sdpMid == remoteMid) {
                next.remotePending.append(candidate)
            }
        }
        guard next.valid else { throw DirectFileIceFailure.capacity }
        self = next
    }
    mutating func installRemote(fragment: String, mid: String?) throws {
        guard mode == .trickle, DirectFileIceCandidate.validFragment(fragment),
              mid == nil || DirectFileIceCandidate.validMid(mid!) else { throw DirectFileIceFailure.invalid }
        if remoteReady { guard remoteFragment == fragment && remoteMid == mid else { throw DirectFileIceFailure.stale }; return }
        remoteFragment = fragment; remoteMid = mid; remoteReady = true
        remotePending.removeAll { $0.ufrag != fragment || $0.sdpMid != nil && $0.sdpMid != mid }
    }
    mutating func prepareAdmission(clientSignalId: String) throws -> DirectFileIceAdmission? {
        guard mode == .trickle, localReady, valid else { throw DirectFileIceFailure.stale }
        if let admission { return admission }
        guard !localClosed, nextOrdinal < 8, !localPending.isEmpty || localComplete else { return nil }
        if nextOrdinal >= 4 && !localComplete { return nil } // reserve four final bounded batches
        let candidates = Array(localPending.prefix(8))
        let ticket = DirectFileIceAdmission(clientSignalId: clientSignalId,
            batch: DirectFileIceBatch(ordinal: nextOrdinal, candidates: candidates, complete: localComplete && localPending.count <= 8))
        guard ticket.valid, !receipts.contains(where: { $0.clientSignalId == clientSignalId }) else { throw DirectFileIceFailure.invalid }
        admission = ticket; return ticket
    }
    mutating func commit(_ ticket: DirectFileIceAdmission) throws -> DirectFileIceReceipt {
        if let receipt = receipts.first(where: { $0.clientSignalId == ticket.clientSignalId }) { return receipt }
        guard admission == ticket, ticket.batch.ordinal == nextOrdinal,
              Array(localPending.prefix(ticket.batch.candidates.count)) == ticket.batch.candidates else { throw DirectFileIceFailure.stale }
        localPending.removeFirst(ticket.batch.candidates.count)
        let receipt = DirectFileIceReceipt(clientSignalId: ticket.clientSignalId, attemptId: attemptId, ordinal: nextOrdinal)
        receipts.append(receipt); nextOrdinal += 1; localClosed = ticket.batch.complete; admission = nil
        guard valid else { throw DirectFileIceFailure.invalid }; return receipt
    }
    static func credentials(_ sdp: String) throws -> (fragment: String, mid: String?) {
        guard DirectFilePeerPolicy.dataOnlyDescription(sdp) else { throw DirectFileIceFailure.invalid }
        let lines = sdp.components(separatedBy: .newlines).filter { !$0.isEmpty }
        let fragments = Set(lines.filter { $0.hasPrefix("a=ice-ufrag:") }.map { String($0.dropFirst(12)) })
        let mids = Set(lines.filter { $0.hasPrefix("a=mid:") }.map { String($0.dropFirst(6)) })
        guard fragments.count == 1, let fragment = fragments.first, DirectFileIceCandidate.validFragment(fragment),
              mids.count <= 1, mids.first == nil || DirectFileIceCandidate.validMid(mids.first!) else { throw DirectFileIceFailure.invalid }
        return (fragment, mids.first)
    }
}

extension DirectFileRecord {
    /// Called before journaling a candidate effect. Invalid/stale candidate
    /// packets are consumed without pausing an otherwise healthy file stream.
    func acceptsIceControl(_ control: DirectFileControl, now: Int64, hasCurrentPeer: Bool) -> Bool {
        guard control.type == .iceCandidates, control.validShape, offer.hasValidShape,
              offer.iceCandidatesVersion == 1, offer.expiresAt > now,
              !phase.terminal, !mailboxClosed, let ice, ice.valid,
              attemptId == control.attemptId, ice.attemptId == control.attemptId,
              control.transferId == id, control.chatId == chatId,
              control.offerClientMessageId == offer.offerClientMessageId, offer.matchesNonce(control.offerNonce),
              control.recipientDeviceId == localDeviceId, control.senderDeviceId == peerDeviceId,
              control.senderUserId == (outgoing ? offer.recipientUserId : offer.senderUserId),
              control.recipientUserId == (outgoing ? offer.senderUserId : offer.recipientUserId),
              outgoing ? peerVerified : consentGiven,
              (manualPause ?? DirectManualPauseState()).manualByteEligible,
              continuedProcessing?.requiresContinue != true else { return false }
        if ice.mode == .awaitingConfirmation { return !outgoing && ice.requested && [.accepted, .connecting].contains(phase) }
        return ice.mode == .trickle && hasCurrentPeer && [.connecting, .transferring].contains(phase)
    }
    /// Pure record transformation. The caller commits this reservation once
    /// before encryption; a retry reuses its identity and budget debit.
    func reservingIce(_ clientSignalId: String) throws -> (DirectFileRecord, DirectFileIceAdmission)? {
        guard !phase.terminal, !mailboxClosed, let attemptId, var ice,
              ice.attemptId == attemptId, ice.mode == .trickle, ice.valid,
              outgoing ? peerVerified : consentGiven,
              (manualPause ?? DirectManualPauseState()).manualByteEligible,
              continuedProcessing?.requiresContinue != true else { throw DirectFileIceFailure.stale }
        var next = self
        if let prior = ice.admission { return (next, prior) }
        guard outgoingSignals.count < DirectSignalBudget.optionalPendingLimit,
              var budget = signalBudget, budget.mayFreezeOptional(pendingCount: outgoingSignals.count) else { throw DirectFileIceFailure.capacity }
        guard let ticket = try ice.prepareAdmission(clientSignalId: clientSignalId) else { return nil }
        budget = try budget.recording(identity: DirectSignalIdentity(senderDeviceId: localDeviceId, clientSignalId: ticket.clientSignalId))
        next.ice = ice; next.signalBudget = budget
        return (next, ticket)
    }
    /// One journal write contains BOTH immutable ciphertext and the matching
    /// queue/ordinal receipt. Failed POST never calls this with a new identity.
    func admittingIce(_ ticket: DirectFileIceAdmission, ciphertext: String, messageType: Int) throws -> (DirectFileRecord, DirectFileIceReceipt) {
        guard !phase.terminal, !mailboxClosed, let attemptId, var ice,
              ice.attemptId == attemptId, ice.mode == .trickle, ice.localReady, ice.valid, ticket.valid,
              outgoing ? peerVerified : consentGiven,
              (manualPause ?? DirectManualPauseState()).manualByteEligible,
              continuedProcessing?.requiresContinue != true,
              let peerDeviceId, DirectFileIceState.uuid(peerDeviceId) else { throw DirectFileIceFailure.stale }
        if let receipt = ice.receipts.first(where: { $0.clientSignalId == ticket.clientSignalId }) { return (self, receipt) }
        guard ice.admission == ticket, outgoingSignals.count < DirectSignalBudget.optionalPendingLimit,
              signalBudget?.identities.contains(DirectSignalIdentity(senderDeviceId: localDeviceId, clientSignalId: ticket.clientSignalId)) == true,
              [1,2].contains(messageType), let bytes = Data(base64Encoded: ciphertext), !bytes.isEmpty, bytes.count <= 65536 else { throw DirectFileIceFailure.invalid }
        var next = self
        let receipt = try ice.commit(ticket)
        next.outgoingSignals.append(DirectFileFrozenSignal(clientSignalId: ticket.clientSignalId, recipientDeviceId: peerDeviceId,
            messageType: messageType, ciphertext: ciphertext, iceAttemptId: attemptId, iceOrdinal: ticket.batch.ordinal))
        next.ice = ice
        return (next, receipt)
    }
}


/// Optional poster only. The original is never embedded, uploaded or decoded
/// from this field. Invalid optional previews leave the file offer usable.
struct DirectFileMediaPreview: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable { case image, video }
    static let maximumBytes = 16 * 1024
    static let maximumBase64Bytes = ((maximumBytes + 2) / 3) * 4
    static let maximumPixelEdge = 512
    var kind: Kind
    var thumbnail: String
    var width: Int
    var height: Int
    var durationMs: Int64? = nil
    private enum CodingKeys: String, CodingKey {
        case kind, thumbnail, width, height
        case durationMs = "duration_ms"
    }
    var valid: Bool {
        guard (1...Self.maximumPixelEdge).contains(width), (1...Self.maximumPixelEdge).contains(height),
              durationMs == nil || kind == .video && (0...31_536_000_000).contains(durationMs!),
              let bytes = jpegData else { return false }
        guard let dimensions = Self.jpegDimensions(bytes) else { return false }
        return width == dimensions.width && height == dimensions.height
    }
    var jpegData: Data? {
        guard !thumbnail.isEmpty, thumbnail.utf8.count <= Self.maximumBase64Bytes,
              let bytes = Data(base64Encoded: thumbnail), bytes.count <= Self.maximumBytes,
              bytes.base64EncodedString() == thumbnail, Self.jpegDimensions(bytes) != nil else { return nil }
        return bytes
    }
    /// Parse small JPEG headers before giving untrusted pixels to ImageIO.
    /// The receiver never trusts dimensions declared by a peer alone.
    static func jpegDimensions(_ data: Data) -> (width: Int, height: Int)? {
        guard (16...maximumBytes).contains(data.count) else { return nil }
        let bytes = Array(data)
        guard bytes[0] == 0xff, bytes[1] == 0xd8,
              bytes[bytes.count - 2] == 0xff, bytes[bytes.count - 1] == 0xd9 else { return nil }
        var index = 2, dimensions: (width: Int, height: Int)?
        while index < bytes.count - 2 {
            guard bytes[index] == 0xff else { return nil }
            while index < bytes.count, bytes[index] == 0xff { index += 1 }
            guard index < bytes.count else { return nil }
            let marker = bytes[index]; index += 1
            guard marker != 0, marker != 0xd8, marker != 0xd9,
                  !(0xd0...0xd7).contains(marker), marker != 1,
                  index + 1 < bytes.count else { return nil }
            let length = Int(bytes[index]) * 256 + Int(bytes[index + 1])
            guard length >= 2, length <= bytes.count - index else { return nil }
            if marker == 0xda { return dimensions }
            if [0xc0, 0xc1, 0xc2].contains(marker) {
                guard dimensions == nil, length >= 11, bytes[index + 2] == 8 else { return nil }
                let height = Int(bytes[index + 3]) * 256 + Int(bytes[index + 4])
                let width = Int(bytes[index + 5]) * 256 + Int(bytes[index + 6])
                let components = Int(bytes[index + 7])
                guard [1, 3].contains(components), length == 8 + 3 * components,
                      (1...maximumPixelEdge).contains(width), (1...maximumPixelEdge).contains(height) else { return nil }
                dimensions = (width, height)
            } else if (0xc0...0xcf).contains(marker), ![0xc4, 0xc8, 0xcc].contains(marker) { return nil }
            index += length
        }
        return nil
    }
}

extension DirectFileOffer {
    /// Malformed/unknown optional posters cannot discard an otherwise valid
    /// transfer. Older clients ignore the entire optional field as before.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(Int.self, forKey: .version)
        transferId = try c.decode(String.self, forKey: .transferId)
        offerClientMessageId = try c.decode(String.self, forKey: .offerClientMessageId)
        senderUserId = try c.decode(String.self, forKey: .senderUserId)
        senderDeviceId = try c.decode(String.self, forKey: .senderDeviceId)
        recipientUserId = try c.decode(String.self, forKey: .recipientUserId)
        offerNonce = try c.decode(String.self, forKey: .offerNonce)
        filename = try c.decode(String.self, forKey: .filename)
        mimeType = try c.decode(String.self, forKey: .mimeType)
        size = try c.decode(Int64.self, forKey: .size)
        sha256 = try c.decode(String.self, forKey: .sha256)
        expiresAt = try c.decode(Int64.self, forKey: .expiresAt)
        iceCandidatesVersion = try c.decodeIfPresent(Int.self, forKey: .iceCandidatesVersion)
        if let value = try? c.decode(DirectFileMediaPreview.self, forKey: .preview), value.valid { preview = value }
        else { preview = nil }
    }
    var safeMediaPreview: DirectFileMediaPreview? {
        guard let preview, preview.valid,
              preview.kind == .image && mimeType.hasPrefix("image/") || preview.kind == .video && mimeType.hasPrefix("video/") else { return nil }
        return preview
    }
}
