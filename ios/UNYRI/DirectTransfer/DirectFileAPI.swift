import Foundation
import os

struct DirectFileMailbox: Decodable, Equatable {
    var id: String
    var chatId: String
    var offerClientMessageId: String
    var senderUserId: String
    var senderDeviceId: String
    var recipientUserId: String
    var recipientDeviceId: String?
    var expiresAt: Int64
    var status: String

    func binds(_ record: DirectFileRecord) -> Bool {
        id == record.id && chatId == record.chatId && offerClientMessageId == record.offer.offerClientMessageId &&
            senderUserId == record.offer.senderUserId && senderDeviceId == record.offer.senderDeviceId &&
            recipientUserId == record.offer.recipientUserId && expiresAt == record.offer.expiresAt &&
            ["active", "closed"].contains(status) &&
            (recipientDeviceId == nil || UUID(uuidString: recipientDeviceId!)?.uuidString.lowercased() == recipientDeviceId)
    }
}

struct DirectFileSignalsPage: Decodable {
    var transfer: DirectFileMailbox
    var signals: [DirectFileSignalEnvelope]
    var hasMore: Bool
}

struct DirectFileAPI {
    let client: APIClient
    private let httpGate: DirectFileHTTPGate
    init(client: APIClient, httpGate: DirectFileHTTPGate? = nil) {
        self.client = client; self.httpGate = httpGate ?? client.directHTTPGate
    }
    struct Context {
        let key: String?
        let priority: DirectFileHTTPGate.Priority
        let current: @MainActor () -> Bool
        let token: @MainActor () async throws -> String
        var timingID: String? = nil
        var timingOutgoing = false
    }
    func promote(_ key: String) -> UUID { httpGate.promote(key) }
    func demote(_ key: String, lease: UUID) { httpGate.demote(key, lease: lease) }
    func cancelRequests(ownerPrefix: String) { httpGate.cancelScope(ownerPrefix) }
    enum RemoteFailure { case notFound, closed, claimedElsewhere }
    static func classify(_ error: Error) -> RemoteFailure? {
        switch error {
        case APIError.http(404), APIError.server(code: "not_found", message: _),
             APIError.server(code: "chat_not_found", message: _): return .notFound
        case APIError.http(410), APIError.server(code: "gone", message: _): return .closed
        case APIError.http(409), APIError.server(code: "conflict", message: _): return .claimedElsewhere
        default: return nil
        }
    }
    struct Capabilities: Decodable { let version: Int; let maxFileBytes: Int64; let directOnly: Bool }
    private struct Register: Encodable {
        var chatId: String
        var offerClientMessageId: String
        var recipientUserId: String
        var expiresAt: Int64
    }
    private func encode<T: Encodable>(_ body: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        // Base64 '/' needs no JSON escape. Keep a maximal valid ciphertext
        // below the direct endpoint's unchanged 96 KiB request body limit.
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return try encoder.encode(body)
    }
    private struct Empty: Encodable {}
    private struct Posted: Decodable { var sequence: Int64; var clientSignalId: String }
    /// Local journal receipts never become additional server envelope fields.
    private struct SignalBody: Encodable {
        let clientSignalId: String
        let recipientDeviceId: String
        let messageType: Int
        let ciphertext: String
        init(_ signal: DirectFileFrozenSignal) {
            clientSignalId = signal.clientSignalId; recipientDeviceId = signal.recipientDeviceId
            messageType = signal.messageType; ciphertext = signal.ciphertext
        }
    }

    private func request<Response: Decodable>(_ path: String, method: String, context: Context,
        query: [URLQueryItem] = [], body: Data? = nil,
        beforeAttempt: (@MainActor () throws -> Void)? = nil, as: Response.Type) async throws -> Response {
        let stage: DirectFileTiming.Stage = path.hasSuffix("/capabilities") ? .httpCapabilities :
            path.hasSuffix("/claim") ? .httpClaim : path.hasSuffix("/close") ? .httpClose :
            path.hasSuffix("/signals") ? (method == "GET" ? .httpInbox : .httpPost) :
            (method == "GET" ? .httpMetadata : .httpRegister)
        let timing = DirectFileTiming.request(stage, id: context.timingID, outgoing: context.timingOutgoing)
        var timingSuccess = false
        defer { timing?.finish(success: timingSuccess) }
        let response: Response = try await httpGate.withPermit(context: .init(key: context.key, priority: context.priority, current: context.current),
            read: method == "GET", prepare: {
                let span = DirectFileTiming.begin(.httpPrepare, id: context.timingID, outgoing: context.timingOutgoing, status: stage.rawValue)
                var success = false
                defer { DirectFileTiming.end(span, success: success) }
                let token = try await context.token()
                try Task.checkCancellation()
                let prepared = try client.prepareBoundedDirectJSON(path, method: method, accessToken: token,
                    query: query, body: body, observed429: { httpGate.observe429($0) }, as: Response.self)
                success = true
                return prepared
            }, beforeStart: {
                guard context.current() else { throw CancellationError() }
                try beforeAttempt?()
                timing?.resuming()
            })
        timingSuccess = true
        return response
    }

    func capabilities(context: Context) async throws -> Capabilities {
        try await request("/direct-transfers/capabilities", method: "GET", context: context, as: Capabilities.self)
    }

    func register(_ record: DirectFileRecord, context: Context) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(record.id)", method: "PUT", context: context,
            body: encode(Register(chatId: record.chatId, offerClientMessageId: record.offer.offerClientMessageId,
                recipientUserId: record.offer.recipientUserId, expiresAt: record.offer.expiresAt)), as: DirectFileMailbox.self)
    }
    func metadata(_ id: String, context: Context) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(id)", method: "GET", context: context, as: DirectFileMailbox.self)
    }
    func claim(_ id: String, context: Context, beforeAttempt: (@MainActor () throws -> Void)? = nil) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(id)/claim", method: "POST", context: context, body: encode(Empty()), beforeAttempt: beforeAttempt, as: DirectFileMailbox.self)
    }
    func send(_ id: String, signal: DirectFileFrozenSignal, context: Context, beforeAttempt: (@MainActor () throws -> Void)? = nil) async throws {
        let reply = try await request("/direct-transfers/\(id)/signals", method: "POST", context: context,
            body: encode(SignalBody(signal)), beforeAttempt: beforeAttempt, as: Posted.self)
        guard reply.clientSignalId == signal.clientSignalId, reply.sequence > 0 else { throw APIError.decoding("Invalid transfer signal acknowledgement") }
    }
    func signals(_ id: String, after: Int64, context: Context) async throws -> DirectFileSignalsPage {
        try await request("/direct-transfers/\(id)/signals", method: "GET", context: context,
            query: [URLQueryItem(name: "after", value: String(after)), URLQueryItem(name: "limit", value: "32")],
            as: DirectFileSignalsPage.self)
    }
    func close(_ id: String, context: Context) async throws {
        let _: DirectFileMailbox = try await request("/direct-transfers/\(id)/close", method: "POST", context: context,
            body: encode(Empty()), as: DirectFileMailbox.self)
    }
}


/// Diagnostic-only numeric events in the existing opt-in bounded ring.
/// No UUID, path, filename, message, address, SDP or cryptographic bytes export.
/// operation 400...439: direct stages; phase0/1 span; phase2 milestone.
/// Local RTC enqueue, decoded receive and appended file bytes are distinct.
enum DirectFileTiming {
    enum Stage: Int {
        case offerPreparation = 400, sourceCopy = 401, sourceHash = 402, sourceFsync = 403
        case optionalPreview = 404, offerEnqueued = 405, ordinaryACKObserved = 406, offerReceived = 407
        case acceptAction = 408, resumeAction = 409, receiverPreparation = 410, sourcePrefix = 411
        case controlEncrypt = 412, controlFrozen = 413, controlConsumed = 414, requestReceived = 415
        case iceServers = 416, peerCreated = 417, sdpOffer = 418, sdpAnswer = 419, remoteDescription = 420
        case iceGatheringState = 421, iceConnectionState = 422, channelOpen = 423, directVerified = 424
        case dtlsStateObserved = 425, firstSourceRead = 426, firstPayloadEnqueued = 427
        case firstPayloadDecoded = 428, firstDiskAppend = 429, finalReceiverHash = 430
        case httpCapabilities = 431, httpRegister = 432, httpClaim = 433, httpInbox = 434
        case httpPost = 435, httpClose = 436, httpPrepare = 437, httpResponse = 438, httpMetadata = 439
        case admission = 440, composerPreparation = 441
    }
    struct Span {
        #if os(iOS)
        fileprivate let value: PerformanceRecorder.Span?
        #endif
    }
    static func begin(_ stage: Stage, id: String?, outgoing: Bool, bytes: Int64 = 0, status: Int = 0) -> Span? {
        #if os(iOS)
        guard let id, PerformanceRecorder.shared.isEnabled else { return nil }
        return Span(value: PerformanceRecorder.shared.begin(.queue, ["operation": Double(stage.rawValue),
            "trace": PerformanceRecorder.shared.operation(id), "direction": outgoing ? 1 : 0,
            "bytes": Double(max(0, bytes)), "status": Double(max(0, status))]))
        #else
        return nil
        #endif
    }
    static func end(_ span: Span?, success: Bool) {
        #if os(iOS)
        PerformanceRecorder.shared.end(span?.value, success: success)
        #endif
    }
    static func mark(_ stage: Stage, id: String?, outgoing: Bool, bytes: Int64 = 0, status: Int = 0, count: Double = 0) {
        #if os(iOS)
        guard let id, PerformanceRecorder.shared.isEnabled else { return }
        PerformanceRecorder.shared.record(.queue, ["operation": Double(stage.rawValue), "phase": 2,
            "trace": PerformanceRecorder.shared.operation(id), "direction": outgoing ? 1 : 0,
            "bytes": Double(max(0, bytes)), "status": Double(max(0, status)), "count": max(0, count)])
        #endif
    }
    static func relatedOperation(_ id: String) -> Double {
        #if os(iOS)
        return PerformanceRecorder.shared.operation(id)
        #else
        return 0
        #endif
    }
    static func measure<Value>(_ stage: Stage, id: String?, outgoing: Bool, bytes: Int64 = 0,
                               operation: () throws -> Value) rethrows -> Value {
        let span = begin(stage, id: id, outgoing: outgoing, bytes: bytes)
        do { let value = try operation(); end(span, success: true); return value }
        catch { end(span, success: false); throw error }
    }
    @MainActor static func measureAsync<Value>(_ stage: Stage, id: String?, outgoing: Bool,
                           operation: @MainActor () async throws -> Value) async rethrows -> Value {
        let span = begin(stage, id: id, outgoing: outgoing)
        do { let value = try await operation(); end(span, success: true); return value }
        catch { end(span, success: false); throw error }
    }
    static func control(_ value: DirectFileControl.Kind) -> Int {
        switch value {
        case .accept: return 1; case .resume: return 2; case .offer: return 3; case .answer: return 4
        case .pause: return 5; case .pauseState: return 6; case .iceCandidates: return 7
        case .complete: return 8; case .completeAck: return 9; case .cancel: return 10; case .reject: return 11
        }
    }
    static func dtls(_ value: String?) -> Int {
        switch value { case "new": return 1; case "connecting": return 2; case "connected": return 3
        case "closed": return 4; case "failed": return 5; default: return 0 }
    }
    /// Total includes gate+token preparation; response starts at URLSession's
    /// existing final resume guard. This is not a network-only RTT.
    final class Request: @unchecked Sendable {
        private let lock = NSLock()
        private let id: String?
        private let outgoing: Bool
        private let stage: Stage
        private let total: Span?
        private var response: Span?
        init(_ stage: Stage, id: String?, outgoing: Bool) {
            self.stage = stage; self.id = id; self.outgoing = outgoing
            total = DirectFileTiming.begin(stage, id: id, outgoing: outgoing)
        }
        func resuming() {
            lock.lock(); defer { lock.unlock() }
            if response == nil { response = DirectFileTiming.begin(.httpResponse, id: id, outgoing: outgoing, status: stage.rawValue) }
        }
        func finish(success: Bool) {
            lock.lock(); let value = response; response = nil; lock.unlock()
            DirectFileTiming.end(value, success: success); DirectFileTiming.end(total, success: success)
        }
    }
    static func request(_ stage: Stage, id: String?, outgoing: Bool) -> Request? {
        #if os(iOS)
        guard id != nil, PerformanceRecorder.shared.isEnabled else { return nil }
        return Request(stage, id: id, outgoing: outgoing)
        #else
        return nil
        #endif
    }
}

/// Steps of a direct transfer in the device log (subsystem com.unyri.direct,
/// category steps): short id prefix, numbers and fixed words only, never names or
/// paths. For debugging on a simulator or a tethered phone.
enum DirectFileTrace {
    private static let log = Logger(subsystem: "com.unyri.direct", category: "steps")
    static func note(_ id: String, _ step: String) {
        log.info("\(String(id.prefix(8)), privacy: .public) \(step, privacy: .public)")
    }
}

/// The real cause behind a direct-transfer error. The user sees a short sentence;
/// diagnostics get a numeric family and case (never names, paths or ids), and the
/// device log gets the full error for local debugging. Uses the existing `queue`
/// event kind (phase 3) so older servers accept the report.
enum DirectFileFailureDiag {
    private static let log = Logger(subsystem: "com.unyri.direct", category: "failure")

    static func record(_ error: Error, stage: DirectFileTiming.Stage? = nil) {
        let code = code(for: error)
        log.error("direct failure stage=\(stage?.rawValue ?? 0, privacy: .public) code=\(Int(code), privacy: .public) \(String(reflecting: error), privacy: .private)")
        #if os(iOS)
        guard PerformanceRecorder.shared.isEnabled else { return }
        var metrics: [String: Double] = ["phase": 3, "code": code]
        if let stage { metrics["operation"] = Double(stage.rawValue) }
        if let status = status(for: error) { metrics["status"] = status }
        PerformanceRecorder.shared.record(.queue, metrics)
        #endif
    }

    /// The HTTP status or the system error number, when there is one.
    private static func status(for error: Error) -> Double? {
        if case APIError.http(let status) = error { return Double(status) }
        if let url = error as? URLError { return Double(abs(url.code.rawValue)) }
        if let cocoa = error as? CocoaError { return Double(abs(cocoa.code.rawValue)) }
        return nil
    }

    /// Family × 100 + case: 1 transfer, 2 disk, 3 queue ledger, 4 file copy,
    /// 5 preparation, 6 API, 7 URL, 8 Cocoa, 9 cancelled, 10 other.
    static func code(for error: Error) -> Double {
        switch error {
        case let failure as DirectFileTransfers.Failure:
            let cases: [DirectFileTransfers.Failure] = [.session, .invalid, .busy, .callBusy, .unavailable, .unsupported, .source, .integrity, .claimedElsewhere]
            return 100 + Double((cases.firstIndex(of: failure) ?? 98) + 1)
        case let failure as DirectFileDisk.Failure:
            switch failure {
            case .invalid: return 201
            case .noSpace: return 202
            case .changed: return 203
            case .missing: return 204
            case .corrupt: return 205
            case .tooMany: return 206
            case .offset: return 207
            case .admissionUnavailable: return 208
            }
        case let failure as DirectFileAllocationLedger.Failure:
            switch failure {
            case .invalid: return 301
            case .full: return 302
            case .space: return 303
            case .exhausted: return 304
            case .retired: return 305
            }
        case let failure as AttachmentFileCopy.CopyError: return 400 + Double(failure.errorCode)
        case let failure as DirectComposerOriginal.PreparationFailure:
            switch failure {
            case .unavailable: return 501
            case .tooLarge: return 502
            case .requiresDirect: return 503
            }
        case let failure as APIError:
            switch failure {
            case .network: return 601
            case .http: return 602
            case .server(let code, _):
                return ["rate_limited": 604, "forbidden": 605, "not_found": 606, "conflict": 607,
                        "validation_error": 608, "unauthorized": 609, "gone": 610][code] ?? 603
            default: return 699
            }
        case is URLError: return 701
        case is CocoaError: return 801
        case is CancellationError: return 900
        default: return 1000
        }
    }
}
