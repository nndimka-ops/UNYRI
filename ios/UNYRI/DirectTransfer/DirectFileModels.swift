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
    static func importRoute(size: Int64?, supportsDirect: Bool, ordinaryLimit: Int64) -> ImportRoute {
        if let size, size > ordinaryLimit { return supportsDirect ? .direct : .unsupportedLarge }
        return size == nil && supportsDirect ? .direct : .ordinary
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
        case version, filename, size, sha256
        case transferId = "transfer_id", offerClientMessageId = "offer_client_message_id"
        case senderUserId = "sender_user_id", senderDeviceId = "sender_device_id"
        case recipientUserId = "recipient_user_id", mimeType = "mime_type", expiresAt = "expires_at"
        case offerNonce = "offer_nonce"
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
    enum Kind: String, Codable { case accept, offer, answer, resume, pause, pauseState = "pause_state", cancel, reject, complete, completeAck = "complete_ack" }
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

    private enum CodingKeys: String, CodingKey {
        case version, type, sdp, offset, reason
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
            ? [.accept, .resume, .answer, .pause, .pauseState, .cancel, .reject, .complete]
            : [.offer, .pause, .pauseState, .cancel, .completeAck]
        guard allowed.contains(type), offset == nil || offset! <= offer.size else { return false }
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
}
