import Foundation

// Production CallsRepository depends on the account database; this transport
// harness supplies only that boundary, while WorkspaceContracts tests SQLite.
final class MessageStore {
    func suspendCallActivity(owner: String) throws {}
    func clearCallActivity(before: Date) throws {}
    func clearCallActivity(chat: String, before: Date) throws {}
    func exportCallChatClears() throws -> [String: Int64] { [:] }
    func isChatDeleted(_ id: String) throws -> Bool { false }
    func callChatCutoff(_ id: String) throws -> Date { .distantPast }
    func callVisible(owner: String, id: String) throws -> Bool { false }
    func callUnread(owner: String, id: String) throws -> Bool { false }
    func markCallsViewed(owner: String, ids: Set<String>) throws {}
    func pendingCallReads(owner: String, since: Date) throws -> [String] { [] }
    func completeCallRead(owner: String, id: String) throws {}
    func removeCallActivity(owner: String, ids: Set<String>) throws {}
    func callDeletionIDs(owner: String, server: String) throws -> Set<String> { [] }
    func pendingCallDeletions(owner: String, server: String) throws -> [PendingCallDeletion] { [] }
    func completeCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws {}
    func enqueueCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws {}
}
struct PendingCallDeletion { let callId: String; let everyone: Bool }

// This transport-contract executable does not sign in or call a live server.
@MainActor
final class AuthRepository {
    var sessionGeneration: UInt64 = 0
    var historyContext: HistoryContext? = nil
    func cachedScreen<T: Decodable>(_ key: String, as type: T.Type) -> T? { nil }
    func saveScreen<T: Encodable>(_ key: String, generation: UInt64, value: T) {}
    func currentAccessToken() async throws -> String { "unused" }
}

let decoder = JSONDecoder()
decoder.keyDecodingStrategy = .convertFromSnakeCase
decoder.dateDecodingStrategy = .iso8601

let ringingJSON = """
{"id":"11111111-1111-1111-1111-111111111111",
 "caller_user_id":"22222222-2222-2222-2222-222222222222",
 "callee_user_id":"33333333-3333-3333-3333-333333333333",
 "media_kind":"video","state":"ringing","end_reason":null,
 "accepted_device_id":null,"created_at":"2026-09-08T20:00:00Z",
 "accepted_at":null,"connected_at":null,"ended_at":null}
"""

func check(_ condition: @autoclosure () -> Bool, _ description: String) {
    guard condition() else { fatalError(description) }
}

let ringing = try decoder.decode(CallDTO.self, from: Data(ringingJSON.utf8))
check(ringing.chatId == nil && ringing.chatVisible == nil && !ringing.unread, "Legacy call fields default without inventing visibility or unread")
check(ringing.peer == nil, "Older servers omit peer identity without failing call history")
var peerPayload = try JSONSerialization.jsonObject(with:Data(ringingJSON.utf8)) as! [String:Any]
peerPayload["peer"] = ["id":ringing.calleeUserId.uuidString,"username":"fixture_peer","display_name":"Fixture Peer",
    "profile_version":1,"avatar_version":0,"has_avatar":false]
let namedCall = try decoder.decode(CallDTO.self,from:JSONSerialization.data(withJSONObject:peerPayload))
check(namedCall.validatedPeer(owner:ringing.callerUserId)?.displayName == "Fixture Peer", "Calls receive peer name independently of local chat list")
check(namedCall.validatedPeer(owner:ringing.calleeUserId) == nil && namedCall.validatedPeer(owner:UUID()) == nil, "Cached peer identity must match the current owner's other participant")
check(CallActivityPolicy.merged(namedCall,ringing).peer == namedCall.peer, "Lifecycle endpoints without peer retain the validated cached public profile")
check(CallPeerTitlePolicy.title(displayName:"  Fixture Peer  ",username:"fixture_peer",unknown:"Unknown user") == "Fixture Peer", "Title uses trimmed display name")
check(CallPeerTitlePolicy.title(displayName:"  ",username:"fixture_peer",unknown:"Unknown user") == "@fixture_peer", "Empty display name falls back to handle")
check(CallPeerTitlePolicy.title(displayName:nil,username:nil,unknown:"Unknown user") == "Unknown user", "Unknown title does not duplicate a call outcome")
check(!CallActivityPolicy.eligible(ringing, owner: ringing.callerUserId, since: .distantFuture), "Unknown device history boundary fails closed")
check(!CallActivityPolicy.eligible(ringing, owner: UUID(), since: .distantPast), "Other account cannot project call activity")
var canonical = ringing
canonical.chatId = UUID(); canonical.chatVisible = true
check(CallActivityPolicy.matchesChat(canonical, chatId: canonical.chatId!.uuidString), "Canonical chat receives its call")
check(!CallActivityPolicy.matchesChat(canonical, chatId: UUID().uuidString), "Recreated chat cannot inherit an old call by peer")
var hidden = canonical; hidden.chatVisible = false
check(!CallActivityPolicy.matchesChat(hidden, chatId: canonical.chatId!.uuidString), "Explicit server denial forbids projection")
check(CallActivityPolicy.merged(canonical, hidden).chatVisible == false, "Server denial overrides cached visible hint")
check(ringing.mediaKind == .video, "Video media kind must match the server")
check(!ringing.isEnded, "Ringing is not a terminal call")
check(ringing.acceptedDeviceId == nil, "Unanswered calls have no accepted device")

let endedJSON = ringingJSON.replacingOccurrences(of: "\"ringing\"", with: "\"ended\"")
let ended = try decoder.decode(CallDTO.self, from: Data(endedJSON.utf8))
check(ended.isEnded, "Ended state must stop the call even without ended_at")

let noCall = try decoder.decode(Optional<CallDTO>.self, from: Data("null".utf8))
check(noCall == nil, "GET /calls/active returns null when idle")

let audioJSON = ringingJSON.replacingOccurrences(of: "\"video\"", with: "\"audio\"")
let audio = try decoder.decode(CallDTO.self, from: Data(audioJSON.utf8))
check(audio.mediaKind == .audio, "Audio must not require video")

let stun = try decoder.decode(CallICEServer.self, from: Data("{\"urls\":[\"stun:localhost:3478\"]}".utf8))
check(stun.username == nil && stun.credential == nil, "STUN needs no TURN credentials")
let turn = try decoder.decode(CallICEServer.self, from: Data("{\"urls\":[\"turn:localhost:3478\"],\"username\":\"temporary\",\"credential\":\"test-only\"}".utf8))
check(turn.username == "temporary", "Decode ephemeral TURN username")
check(turn.credential == "test-only", "Decode ephemeral TURN credential")
let missedJSON = endedJSON.replacingOccurrences(of: "\"end_reason\":null", with: "\"end_reason\":\"missed\"")
let missed = try decoder.decode(CallDTO.self, from: Data(missedJSON.utf8))
check(missed.historyLabel(owner: missed.calleeUserId) == "Missed call", "callee sees missed call")
check(missed.historyLabel(owner: missed.callerUserId) == "No answer", "caller sees no answer")
check(missed.durationSeconds == nil, "ringing time is not conversation duration")
let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
defer { try? FileManager.default.removeItem(at: directory) }
let history = CallHistoryStore(file: directory.appendingPathComponent("calls.json"))
let saved = try await history.merge([missed, missed])
check(saved.count == 1, "repeated pages deduplicate by call ID")
let reopened = CallHistoryStore(file: directory.appendingPathComponent("calls.json"))
let restored = try await reopened.load()
check(restored == saved, "call history survives restart")
let deletionStore = CallHistoryStore(file: directory.appendingPathComponent("deleted-calls.json"))
_ = try await deletionStore.merge([missed])
_ = try await deletionStore.remove(ids: [missed.id], cursor: 10)
let deletedReload = try await deletionStore.merge([missed])
check(deletedReload.isEmpty, "stale call page cannot resurrect deleted history")
let deletionRestart = CallHistoryStore(file: directory.appendingPathComponent("deleted-calls.json"))
let stateAfterRestart = try await deletionRestart.deletions()
check(stateAfterRestart.ids.contains(missed.id) && stateAfterRestart.cursor == 10, "deletion and sync cursor survive restart")
_ = try await deletionRestart.remove(ids: [], cursor: 5)
let monotonic = try await deletionRestart.deletions()
check(monotonic.cursor == 10, "out-of-order response cannot rewind deletion cursor")

let cutoffMs = Int64((missed.createdAt.timeIntervalSince1970 + 1) * 1000)
let cleared = try await reopened.clear(beforeMs: cutoffMs)
check(cleared.isEmpty, "old calls are removed")
let stalePage = try await reopened.merge([missed])
check(stalePage.isEmpty, "late page cannot restore cleared call")
let afterClearRestart = CallHistoryStore(file: directory.appendingPathComponent("calls.json"))
let afterRestart = try await afterClearRestart.merge([missed])
check(afterRestart.isEmpty, "call clear marker survives restart")
print("Call contract checks passed")

let callIndex = ChatCallActivity.latestByPeer([ringing, missed], owner: ringing.callerUserId)
check(callIndex.count == 1, "Repeated call versions do not duplicate peer activity")
check(ChatCallActivity.latestByPeer([ringing], owner: UUID()).isEmpty, "Other accounts cannot affect chat ordering")
check(ChatCallActivity.latestDate(message: nil, call: ringing) == ringing.createdAt, "Call promotes a chat without messages")
check(ChatCallActivity.latestDate(message: ringing.createdAt.addingTimeInterval(1), call: ringing) > ringing.createdAt, "Newer messages remain above calls")
check(ChatCallActivity.latestDate(message: ringing.createdAt.addingTimeInterval(-1), call: ringing) == ringing.createdAt, "A newer call promotes a chat with older messages")
