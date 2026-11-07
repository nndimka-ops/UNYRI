import Foundation

// Pure messaging/realtime/sync/outbox contract checks — no network, no libsignal.
// Mirrors the backend wire shapes so a DTO/event/classification regression is
// caught on macOS CI without an iPhone.

var failures = 0

func fail(_ description: String) {
    failures += 1
    let line = "FAIL: \(description)\n"
    FileHandle.standardError.write(Data(line.utf8))
}

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    if condition() {
        print("PASS: \(description)")
    } else {
        fail(description)
    }
}

func expect<T: Equatable>(_ actual: T, _ expected: T, _ description: String) {
    if actual == expected {
        print("PASS: \(description)")
    } else {
        fail("\(description) (expected \(expected), got \(actual))")
    }
}

// MARK: - Media admission notification scope

var admissionBatch = MediaAdmissionBatch()
expect(admissionBatch.accepted(chatId: "outside"), Set(["outside"]), "single admission publishes immediately")
admissionBatch.begin()
expect(admissionBatch.accepted(chatId: "chat"), nil, "batch defers accepted admission")
admissionBatch.begin()
expect(admissionBatch.accepted(chatId: "chat"), nil, "nested admission defers")
expect(admissionBatch.accepted(chatId: "other"), nil, "batch accumulates touched chats")
expect(admissionBatch.end(), [], "inner end does not publish")
expect(admissionBatch.end(), Set(["chat", "other"]), "outer end publishes confirmed subset once")
admissionBatch.begin()
expect(admissionBatch.end(), [], "empty batch publishes nothing")
admissionBatch.begin()
expect(admissionBatch.accepted(chatId: "next"), nil, "independent next batch defers")
expect(admissionBatch.end(), Set(["next"]), "next batch excludes previous touched chats")
expect(admissionBatch.end(), [], "extra end cannot replay notifications")

// MARK: - OutboxPolicy.classify

expect(OutboxPolicy.classify(APIError.network("down")), .transient, "network failure is transient")
expect(OutboxPolicy.classify(APIError.decoding("bad")), .transient, "decode failure is transient (idempotent retry)")
for status in [408, 429, 500, 502, 503, 504] {
    expect(OutboxPolicy.classify(APIError.http(status)), .transient, "temporary HTTP failure retries: \(status)")
}
for status in [400, 403, 404, 422] {
    expect(OutboxPolicy.classify(APIError.http(status)), .permanent, "permanent HTTP failure: \(status)")
}
expect(OutboxPolicy.classify(APIError.invalidURL), .permanent, "invalid URL is permanent")
expect(OutboxPolicy.classify(APIError.server(code: "rate_limited", message: "slow down")), .transient, "rate limit is transient")
expect(OutboxPolicy.classify(APIError.server(code: "internal_error", message: "boom")), .transient, "internal error is transient")
expect(OutboxPolicy.classify(APIError.server(code: "invalid_refresh_token", message: "x")), .authTerminal, "invalid refresh token is auth terminal")
expect(OutboxPolicy.classify(APIError.server(code: "session_revoked", message: "x")), .authTerminal, "session revoked is auth terminal")
expect(OutboxPolicy.classify(APIError.server(code: "unauthorized", message: "x")), .authTerminal, "unauthorized is auth terminal")
expect(OutboxPolicy.classify(APIError.server(code: "validation_error", message: "Every other active device")), .transient, "own-device coverage is transient")
expect(OutboxPolicy.classify(APIError.server(code: "validation_error", message: "bad input")), .permanent, "other validation error is permanent")
expect(OutboxPolicy.classify(APIError.server(code: "conflict", message: "x")), .permanent, "unknown API code is permanent")
expect(OutboxPolicy.classify(APIError.missingSession), .authTerminal, "missing session is auth terminal")
expect(OutboxPolicy.classify(OutboxError.duplicateRecipientDevice), .permanent, "duplicate recipient device is permanent")
expect(OutboxPolicy.classify(OutboxError.identityChanged), .permanent, "changed identity requires explicit user action")
expect(OutboxPolicy.classify(CocoaError(.fileReadNoSuchFile)), .permanent, "missing attachment requires reselection")
expect(OutboxPolicy.classify(CocoaError(.fileNoSuchFile)), .permanent, "missing local file is not a network retry")
expect(OutboxPolicy.classify(CocoaError(.fileReadNoPermission)), .transient, "temporarily inaccessible protected file can recover")
expect(OutboxPolicy.classify(NSError(domain: "t", code: 1)), .transient, "unknown error is transient by default")

// MARK: - OutboxPolicy.baseBackoffMs

expect(OutboxPolicy.baseBackoffMs(0), 1_000, "backoff 0 -> 1s")
expect(OutboxPolicy.baseBackoffMs(1), 2_000, "backoff 1 -> 2s")
expect(OutboxPolicy.baseBackoffMs(2), 4_000, "backoff 2 -> 4s")
expect(OutboxPolicy.baseBackoffMs(3), 8_000, "backoff 3 -> 8s")
expect(OutboxPolicy.baseBackoffMs(4), 15_000, "backoff 4 -> 15s")
expect(OutboxPolicy.baseBackoffMs(5), 30_000, "backoff 5 -> 30s")
expect(OutboxPolicy.baseBackoffMs(6), 60_000, "backoff 6 -> 60s cap")
expect(OutboxPolicy.baseBackoffMs(100), 60_000, "backoff cap holds")
expect(OutboxPolicy.baseBackoffMs(-1), 1_000, "negative retry count clamps to 1s")

// MARK: - RealtimeEventParser

let messageJSON = """
{
 "type":"message.new",
 "message":{
   "id":"11111111-1111-1111-1111-111111111111",
   "chat_id":"22222222-2222-2222-2222-222222222222",
   "client_message_id":"33333333-3333-3333-3333-333333333333",
   "sender_id":"44444444-4444-4444-4444-444444444444",
   "sender_device_id":"55555555-5555-5555-5555-555555555555",
   "sequence":7,
   "created_at":"2026-09-08T20:00:00Z",
   "message_type":2,
   "ciphertext":"QUJD",
   "kind":"content",
   "target_message_id":null
 }
}
"""

if case .messageNew(let dto) = RealtimeEventParser.parse(Data(messageJSON.utf8)) {
    expect(dto.sequence, 7, "message.new decodes sequence")
    expect(dto.chatId, "22222222-2222-2222-2222-222222222222", "message.new decodes chat_id (snake_case)")
    expect(dto.messageType, 2, "message.new decodes message_type")
} else {
    fail("message.new event parsed")
}

let sentJSON = messageJSON.replacingOccurrences(of: "\"message.new\"", with: "\"message.sent\"")
if case .messageSent(let dto) = RealtimeEventParser.parse(Data(sentJSON.utf8)) {
    expect(dto.senderId, "44444444-4444-4444-4444-444444444444", "message.sent decodes sender_id")
} else {
    fail("message.sent event parsed")
}

let receiptJSON = """
{"type":"receipt.updated","chat_id":"22222222-2222-2222-2222-222222222222",
 "user_id":"44444444-4444-4444-4444-444444444444","delivered_sequence":5,"read_sequence":3}
"""
if case .receiptUpdated(let chatId, let userId, let delivered, let read) = RealtimeEventParser.parse(Data(receiptJSON.utf8)) {
    expect(chatId, "22222222-2222-2222-2222-222222222222", "receipt.updated chat_id")
    expect(delivered, 5, "receipt.updated delivered_sequence")
    expect(read, 3, "receipt.updated read_sequence")
    expect(userId, "44444444-4444-4444-4444-444444444444", "receipt.updated user_id")
} else {
    fail("receipt.updated event parsed")
}

let revokedJSON = #"{"type":"session.revoked","reason":"revoked elsewhere"}"#
if case .sessionRevoked(let reason) = RealtimeEventParser.parse(Data(revokedJSON.utf8)) {
    expect(reason, "revoked elsewhere", "session.revoked reason")
} else {
    fail("session.revoked event parsed")
}

check(RealtimeEventParser.parse(Data(#"{"type":"PONG"}"#.utf8)) == nil, "PONG is protocol noise (nil)")
check(RealtimeEventParser.parse(Data(#"{"type":"call.ringing"}"#.utf8)) == nil, "call events are not messaging (nil)")
check(RealtimeEventParser.parse(Data("not json".utf8)) == nil, "malformed frame returns nil, never crashes")

// MARK: - SyncResponse decoding

let syncJSON = """
{"messages":[],"next_sequence":42,"has_more":true}
"""
let syncDecoder = JSONDecoder()
syncDecoder.keyDecodingStrategy = .convertFromSnakeCase
syncDecoder.dateDecodingStrategy = .custom { decoder in
    let container = try decoder.singleValueContainer()
    let string = try container.decode(String.self)
    guard let date = ISO8601Cocoa.parse(string) else { throw DecodingError.dataCorruptedError(in: container, debugDescription: "bad date") }
    return date
}
if let sync = try? syncDecoder.decode(SyncResponse.self, from: Data(syncJSON.utf8)) {
    expect(sync.nextSequence, 42, "sync next_sequence")
    expect(sync.hasMore, true, "sync has_more")
    expect(sync.messages.count, 0, "sync empty page")
} else {
    fail("SyncResponse decodes")
}

// MARK: - MessageOrdering

let base = Date()
let pending = DecryptedChatMessage(clientMessageId: "p", serverId: nil, chatId: "c", senderId: "me", sequence: nil, createdAt: base.addingTimeInterval(3), kind: "content", targetMessageId: nil, status: .pending, content: MessageContent(kind: MessageContent.kindText, text: "hi"))
let seq1 = DecryptedChatMessage(clientMessageId: "1", serverId: "s1", chatId: "c", senderId: "me", sequence: 1, createdAt: base, kind: "content", targetMessageId: nil, status: .sent, content: MessageContent(kind: MessageContent.kindText, text: "a"))
let seq3 = DecryptedChatMessage(clientMessageId: "3", serverId: "s3", chatId: "c", senderId: "me", sequence: 3, createdAt: base.addingTimeInterval(1), kind: "content", targetMessageId: nil, status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "c"))
let ordered = MessageOrdering.chronological([pending, seq3, seq1])
check(ordered.map { $0.clientMessageId } == ["1", "3", "p"], "chronological: sequenced ascending, pending last")
check(MessageOrdering.oldestSequence([pending, seq3, seq1]) == 1, "oldest sequence ignores pending")

if failures > 0 {
    print("\(failures) contract check(s) failed")
    exit(1)
}
print("Messaging contract checks passed")
