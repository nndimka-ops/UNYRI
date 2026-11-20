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
    func callChatVisibilitySnapshot(owner: String) throws -> (visibleCallIDs: Set<String>, deletedChatIDs: Set<String>, cutoffs: [String: Date]) { ([], [], [:]) }
    func callUnread(owner: String, id: String) throws -> Bool { false }
    func unreadCallIDs(owner: String) throws -> Set<String> { [] }
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
    var currentDeviceId: UUID? = nil
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

func check(_ condition: @autoclosure () -> Bool, _ description: String, file: StaticString = #file, line: UInt = #line) {
    guard condition() else { fatalError(description, file: file, line: line) }
}

let ringing = try decoder.decode(CallDTO.self, from: Data(ringingJSON.utf8))
check(ringing.chatId == nil && ringing.chatVisible == nil && !ringing.unread, "Legacy call fields default without inventing visibility or unread")
check(ringing.peer == nil, "Older servers omit peer identity without failing call history")
var peerPayload = try JSONSerialization.jsonObject(with:Data(ringingJSON.utf8)) as! [String:Any]
peerPayload["peer"] = ["id":ringing.calleeUserId.uuidString,"username":"fixture_peer","display_name":"Fixture Peer",
    "profile_version":1,"avatar_version":0,"has_avatar":false]
let namedCall = try decoder.decode(CallDTO.self,from:JSONSerialization.data(withJSONObject:peerPayload))
check(namedCall.validatedPeer(owner:ringing.callerUserId)?.displayName == "Fixture Peer", "Calls receive peer name independently of local chat list")
check(namedCall.peer?.username.isEmpty == true, "Legacy call peer credential is discarded on decode")
let cleanCallBytes = try JSONEncoder().encode(namedCall)
check(!String(decoding: cleanCallBytes, as: UTF8.self).contains("fixture_peer"), "New call cache writes exclude legacy login")
check(namedCall.validatedPeer(owner:ringing.calleeUserId) == nil && namedCall.validatedPeer(owner:UUID()) == nil, "Cached peer identity must match the current owner's other participant")
check(CallActivityPolicy.merged(namedCall,ringing).peer == namedCall.peer, "Lifecycle endpoints without peer retain the validated cached public profile")
check(CallPeerTitlePolicy.title(displayName:"  Fixture Peer  ",publicIdentity:"@fixture_peer",unknown:"Unknown user") == "Fixture Peer", "Title uses trimmed display name")
check(CallPeerTitlePolicy.title(displayName:"  ",publicIdentity:"@fixture_peer",unknown:"Unknown user") == "@fixture_peer", "Empty display name falls back to public identity")
check(CallPeerTitlePolicy.title(displayName:nil,publicIdentity:nil,unknown:"Unknown user") == "Unknown user", "Unknown title does not duplicate a call outcome")
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
// B-033 upgrades both historical file shapes without losing call rows or peers.
var oldCall = try JSONSerialization.jsonObject(with: JSONEncoder().encode(namedCall)) as! [String: Any]
var oldPeer = oldCall["peer"] as! [String: Any]
oldPeer["username"] = "private_login_b033_fixture"; oldCall["peer"] = oldPeer
let legacySnapshot: [String: Any] = ["rows": [oldCall], "restoredIds": [String]()]
let legacyFiles: [(String, Any)] = [("array", [oldCall]), ("snapshot", legacySnapshot)]
for (name, object) in legacyFiles {
    let file = directory.appendingPathComponent("b033-\(name).json")
    try JSONSerialization.data(withJSONObject: object).write(to: file)
    let legacyStore = CallHistoryStore(file: file)
    let migratedRows = try await legacyStore.load()
    check(migratedRows.first?.peer?.displayName == "Fixture Peer" && migratedRows.first?.peer?.username.isEmpty == true,
          "Call cache upgrade preserves public name and removes a stored login")
    let clean = try Data(contentsOf: file)
    check(!String(decoding: clean, as: UTF8.self).contains("private_login_b033_fixture"), "Call cache upgrade removes login bytes from disk")
    let marker = try JSONSerialization.jsonObject(with: clean) as! [String: Any]
    check(marker["peerPrivacyVersion"] as? Int == 1, "Call cache upgrade records one-time privacy migration")
}
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

// Call screen: DTLS fingerprints, the verification code and the status capsule.
let fingerprintHex = (0..<32).map { String(format: "%02x", $0) }.joined(separator: ":")
let fingerprintLocal = "sha-256 " + fingerprintHex
let fingerprintRemote = "sha-256 " + (0..<32).map { String(format: "%02x", 255 - $0) }.joined(separator: ":")
let fingerprintSdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=fingerprint:SHA-256 \(fingerprintHex.uppercased())\r\n"
    + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 \(fingerprintHex)\r\n"
check(CallDtlsFingerprints.fingerprint(inSdp: fingerprintSdp) == fingerprintLocal, "CRLF SDP yields one lowercased fingerprint")
check(CallDtlsFingerprints.fingerprint(inSdp: fingerprintSdp.replacingOccurrences(of: "\r\n", with: "\n")) == fingerprintLocal, "LF SDP parses the same")
check(CallDtlsFingerprints.fingerprint(inSdp: fingerprintSdp + "a=fingerprint:\(fingerprintRemote)\r\n") == nil, "Disagreeing fingerprint lines fail closed")
check(CallDtlsFingerprints.fingerprint(inSdp: fingerprintSdp + "a=fingerprint:sha-256\r\n") == nil, "A fingerprint line without a value fails closed")
check(CallDtlsFingerprints.fingerprint(inSdp: "v=0\r\na=fingerprint:sha-256 zz:01:02\r\n") == nil, "Malformed fingerprint fails closed")
check(CallDtlsFingerprints.fingerprint(inSdp: "v=0\r\na=fingerprint:sha-256 00:01:02:03:04:05:06:07\r\n") == nil, "Too short a fingerprint fails closed")
check(CallDtlsFingerprints.fingerprint(inSdp: "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n") == nil, "No fingerprint, no code")
// The commitment exchange (SAS-2): the offerer commits in the offer, the answerer
// sends its nonce in the answer, the offerer reveals after it.
let offererNonce = (0..<32).map { String(format: "%02x", 0x10 + $0) }.joined()
let answererNonce = (0..<32).map { String(format: "%02x", 0xa0 + $0) }.joined()
let offererCommitment = "bdcf0e319bf9584572e077d90d8da79ee4087657ed80570628d62b49118e1610"
check(CallVerificationCode.commitment(fingerprint: fingerprintLocal, nonce: offererNonce) == offererCommitment, "Commitment matches the shared test vector")
check(CallVerificationCode.commitment(fingerprint: fingerprintLocal, nonce: "xyz") == nil, "A malformed nonce has no commitment")
func transcript(local: String, remote: String, offerer: Bool, commitment: String = offererCommitment,
                offererNonce: String = offererNonce, answererNonce: String = answererNonce) -> CallVerificationTranscript {
    CallVerificationTranscript(fingerprints: CallDtlsFingerprints(local: local, remote: remote), localIsOfferer: offerer,
                               offererCommitment: commitment, offererNonce: offererNonce, answererNonce: answererNonce)
}
let offererSide = transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: true)
let answererSide = transcript(local: fingerprintRemote, remote: fingerprintLocal, offerer: false)
check(offererSide == answererSide, "Both phones build the same transcript")
check(CallVerificationCode.indices(offererSide) == [35, 39, 50, 43], "Verification code matches the shared test vector")
check(CallVerificationCode.indices(answererSide) == [35, 39, 50, 43], "Both phones derive the same code")
check(CallVerificationCode.indices(offererSide)?.count == CallVerificationCode.length, "The code has four symbols")
check(CallVerificationCode.indices(transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: true,
                                              offererNonce: answererNonce)) == nil, "A reveal that does not open the commitment, no code")
check(CallVerificationCode.indices(transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: false)) == nil,
      "The commitment binds the offerer's certificate")
check(CallVerificationCode.indices(transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: true,
                                              answererNonce: String(answererNonce.dropLast()))) == nil, "A short answerer nonce, no code")
check(CallVerificationCode.indices(transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: true,
                                              answererNonce: answererNonce.uppercased())) == nil, "Nonces are lowercase hex")
check(CallVerificationCode.indices(transcript(local: fingerprintLocal, remote: fingerprintRemote, offerer: true,
                                              answererNonce: String(answererNonce.dropLast()) + "0")) == [29, 46, 50, 58],
      "The answerer's nonce changes the code")
let freshNonce = CallVerificationCode.newNonce()
check(CallVerificationCode.isHex32(freshNonce) && freshNonce != CallVerificationCode.newNonce(), "Fresh nonces are 32 random bytes")
check(CallVerificationCode.symbols.count == 64, "64 code symbols")
check(Set(CallVerificationCode.symbols.map { $0.symbol }).count == 64, "Code symbols are distinct")
check(Set(CallVerificationCode.symbols.map { $0.nameKey }).count == 64, "Code names are distinct")
check(CallVerificationCode.symbols.allSatisfy { $0.nameKey.hasPrefix("call.code.") }, "Code names are namespaced")
check(CallVerificationCode.symbols[51].symbol == "map.fill" && CallVerificationCode.symbols[19].nameKey == "call.code.umbrella", "Code list order is the shared one")

func capsuleKind(_ phase: CallStatusPhase?, shown: Bool = false, waiting: Bool = false, offline: Bool = false,
                 duration: Int? = nil) -> CallStatusCapsuleKind {
    CallStatusCapsulePolicy.kind(phase: phase, ring: nil, reconnectingShown: shown, waitingForNetwork: waiting,
                                 offline: offline, endedDuration: duration)
}
check(capsuleKind(nil) == .hidden, "No call, no capsule")
check(capsuleKind(.starting) == .calling && capsuleKind(.calling) == .calling, "Outgoing says Calling… until it connects")
check(CallStatusCapsulePolicy.kind(phase: .calling, ring: .ringing, reconnectingShown: false, waitingForNetwork: false,
                                   offline: false, endedDuration: nil) == .peerRinging, "A ringing ack says Ringing…")
check(capsuleKind(.connecting).titleKey == "Connecting call…", "Connecting has its own words")
check(capsuleKind(.calling, offline: true) == .waitingForNetwork(inCall: false), "Offline while calling waits for network")
check(capsuleKind(.connecting, offline: true) == .waitingForNetwork(inCall: false), "Offline while connecting waits for network")
check(capsuleKind(.connected, offline: true) == .encrypted, "The pre-connect offline flag never covers a connected call")
check(capsuleKind(.reconnecting) == .encrypted, "A drop shorter than the debounce keeps Encrypted and the timer")
check(capsuleKind(.reconnecting, waiting: true) == .encrypted, "An unshown drop ignores the network")
check(capsuleKind(.reconnecting, shown: true) == .reconnecting, "A shown drop says Reconnecting…")
check(capsuleKind(.reconnecting, shown: true, waiting: true) == .waitingForNetwork(inCall: true), "In-call waiting for network comes from a shown drop")
check(capsuleKind(.connected, shown: true, waiting: true) == .encrypted, "In-call waiting for network only while reconnecting")
check(capsuleKind(.incoming(video: true)).titleKey == "Incoming video call", "Incoming video words")
check(capsuleKind(.ended(.hangup), duration: 151) == .ended(.hangup, duration: 151), "Hang-up keeps the duration")
check(capsuleKind(.ended(.remoteEnded), duration: 9) == .ended(.remoteEnded, duration: 9), "Remote end keeps the duration")
check(capsuleKind(.ended(.rejected), duration: 151) == .ended(.rejected, duration: nil), "Declined has no duration")
check(capsuleKind(.ended(.answeredElsewhere)).titleKey == "Answered on another device", "Ended reason words")
check(CallStatusCapsuleKind.encrypted.showsTimer && !CallStatusCapsuleKind.reconnecting.showsTimer, "Only Encrypted shows the timer")
check(CallStatusCapsuleKind.reconnecting.isAlert && CallStatusCapsuleKind.waitingForNetwork(inCall: false).isAlert
      && !CallStatusCapsuleKind.encrypted.isAlert, "Alerts are the drop states")
check(CallStatusCapsuleKind.waitingForNetwork(inCall: true).isSecured && !CallStatusCapsuleKind.waitingForNetwork(inCall: false).isSecured
      && !CallStatusCapsuleKind.calling.isSecured, "The lock is lit only once media ran")
check(CallStatusCapsuleKind.reconnecting.showsBars && !CallStatusCapsuleKind.connecting.showsBars, "Bars only in call")

func capsuleSubtitle(_ kind: CallStatusCapsuleKind = .encrypted, relay: Bool?, privateRelay: Bool = false,
                     caption: String? = nil, expired: Bool = false) -> String? {
    CallStatusCapsulePolicy.subtitleKey(kind: kind, routeIsRelay: relay, privateRelay: privateRelay, caption: caption, weakExpired: expired)
}
check(capsuleSubtitle(relay: false, caption: "Poor connection", expired: true) == "Poor connection", "Poor beats the route while it lasts")
check(capsuleSubtitle(relay: false, caption: "Weak connection") == "Weak connection", "Weak shows first")
// Owner, 14 Nov: the route lives in the details sheet, the line speaks only for problems.
check(capsuleSubtitle(relay: false, caption: "Weak connection", expired: true) == nil, "Weak gives way to one line")
check(capsuleSubtitle(relay: true, privateRelay: true) == nil, "No route words on the call screen")
check(capsuleSubtitle(relay: true) == nil && capsuleSubtitle(relay: false) == nil, "No route words on the call screen")
check(!CallStatusCapsulePolicy.showsBars(kind: .encrypted, linkHasProblem: false)
      && CallStatusCapsulePolicy.showsBars(kind: .encrypted, linkHasProblem: true)
      && !CallStatusCapsulePolicy.showsBars(kind: .calling, linkHasProblem: true),
      "Bars only when the link is weak, poor or down")
check(capsuleSubtitle(relay: nil) == nil, "Unknown route, one line")
check(capsuleSubtitle(.reconnecting, relay: false) == nil, "Only Encrypted has a second line")
check(CallStatusCapsulePolicy.routeGlyph(kind: .encrypted, routeIsRelay: false) == .direct
      && CallStatusCapsulePolicy.routeGlyph(kind: .encrypted, routeIsRelay: true) == .relay
      && CallStatusCapsulePolicy.routeGlyph(kind: .encrypted, routeIsRelay: nil) == nil
      && CallStatusCapsulePolicy.routeGlyph(kind: .calling, routeIsRelay: false) == nil,
      "Route glyph: two phones direct, a server between them via the server, only once encrypted")
print("Call screen contract checks passed")
