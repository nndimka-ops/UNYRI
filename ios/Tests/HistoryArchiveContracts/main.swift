import Foundation

func verifyMediaV2() throws {
    let data = try Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-archive-v2.json"))
    let a = try HistoryArchive.decode(data, server: id(1), owner: id(2))
    let roundTrip = try HistoryArchive.decode(a.encoded(), server: id(1), owner: id(2))
    precondition(roundTrip == a)
    var missing = a; missing.media = []
    rejected { _ = try missing.encoded() }
    missing = a; missing.chats = []
    rejected { _ = try missing.encoded() }
    missing = a; missing.format = 1
    rejected { _ = try missing.encoded() }
    var empty = a.withoutMedia(); empty.messages = []
    let merged = try HistoryArchiveMerge.merge(local: empty, restored: a)
    precondition(merged.media == a.media && merged.chats == a.chats)
    var hidden = a.messages[0]; hidden.hidden = true; hidden.content = nil
    var local = a.withoutMedia(); local.messages = [hidden]
    let cleared = try HistoryArchiveMerge.merge(local: local, restored: a)
    precondition(cleared.media?.isEmpty == true)
    let tombstoned = try a.applyingServerDeletions([a.messages[0].id: 99])
    precondition(tombstoned.messages[0].content == nil && tombstoned.messages[0].deletedThrough == 99 && tombstoned.media?.isEmpty == true)
    rejected { _ = try a.applyingServerDeletions([id(99): 100]) }
    rejected { _ = try a.applyingServerDeletions([a.messages[0].id: 1]) }
    let kotlin = URL(fileURLWithPath: "android/app/build/history-archive-v2-kotlin.json")
    if FileManager.default.fileExists(atPath: kotlin.path) {
        let other = try HistoryArchive.decode(Data(contentsOf: kotlin), server: id(1), owner: id(2))
        precondition(other == a)
    }
}
try verifyMediaV2()

func verifyCallHistoryV2() throws {
    var archive = HistoryArchive(server: id(1), owner: id(2), snapshot: id(3), messages: [])
    archive.format = 2; archive.media = []; archive.chats = []
    let row = HistoryArchiveCall(id: id(20), caller: id(2), callee: id(21), mediaKind: "audio", createdAtMs: 1000,
        acceptedAtMs: 1100, connectedAtMs: 1200, endedAtMs: 2000, endReason: "hangup", chat: id(6), chatVisible: true,
        peer: HistoryArchivePeer(id: id(21), username: "peer", displayName: "Peer"))
    archive.calls = [row]
    _ = try archive.encoded() // Previously valid call-only archives remain readable.
    rejected { try archive.validateBackupChatDescriptors() }
    let repaired = try archive.restoringCallChatDescriptors()
    precondition(repaired.chats?.first?.peer == row.peer && repaired.chats?.first?.id == row.chat)
    var noPeer = archive; noPeer.calls![0].peer = nil
    let fallback = try noPeer.restoringCallChatDescriptors()
    precondition(fallback.chats?.first?.peer?.id == id(21) && fallback.chats?.first?.peer?.displayName == "Restored contact")
    let differentPeer = HistoryArchiveCall(id: id(22), caller: id(2), callee: id(23), mediaKind: "video", createdAtMs: 3000,
        acceptedAtMs: nil, connectedAtMs: nil, endedAtMs: 4000, endReason: "missed", chat: id(6), chatVisible: true, peer: nil)
    var conflictingPeers = archive; conflictingPeers.calls!.append(differentPeer)
    rejected { _ = try conflictingPeers.restoringCallChatDescriptors() }
    archive.chats = [HistoryArchiveChat(id: id(6), type: "direct", createdAtMs: 1000, peer: row.peer)]
    let roundtrip = try HistoryArchive.decode(archive.encoded(), server: id(1), owner: id(2))
    precondition(roundtrip == archive)
    precondition(archive.withoutMedia().calls == nil)
    var wrongOwner = archive; wrongOwner.owner = id(99)
    rejected { _ = try wrongOwner.encoded() }
    var incompatible = archive; incompatible.format = 1; incompatible.media = nil; incompatible.chats = nil
    rejected { _ = try incompatible.encoded() }
    var tombstone = archive; tombstone.calls = nil; tombstone.callDeletedIds = [id(20)]
    let deleted = try HistoryArchiveMerge.merge(local: tombstone, restored: archive)
    precondition(deleted.calls == nil && deleted.callDeletedIds == [id(20)])
    let deletedReverse = try HistoryArchiveMerge.merge(local: archive, restored: tombstone)
    precondition(deletedReverse.calls == nil)
    var cleared = archive; cleared.calls = nil; cleared.callClearedBeforeMs = 1500
    let globallyCleared = try HistoryArchiveMerge.merge(local: cleared, restored: archive)
    precondition(globallyCleared.calls == nil)
    cleared.callClearedBeforeMs = nil; cleared.callChatClears = [id(6): 1500]
    let chatCleared = try HistoryArchiveMerge.merge(local: cleared, restored: archive)
    precondition(chatCleared.calls?.count == 1 && chatCleared.calls?.first?.chatVisible == false)
    let data = try archive.encoded()
    let object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
    var forged = object; var rows = forged["calls"] as! [[String: Any]]
    rows[0]["acceptedDeviceId"] = id(10); forged["calls"] = rows
    rejected { _ = try HistoryArchive.decode(JSONSerialization.data(withJSONObject: forged), server: id(1), owner: id(2)) }
    print("Call archive contracts passed: roundtrip, owner admission, format compatibility, tombstones, clear policy, unknown fields")
}
try verifyCallHistoryV2()

func verifyCompleteSharedV2() throws {
    let bytes = try Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-archive-v2-complete.json"))
    let archive = try HistoryArchive.decode(bytes, server: id(1), owner: id(2))
    try archive.validateCompleteMedia()
    precondition(archive.messages.count == 1 && archive.media?.count == 1 && archive.chats?.count == 1)
    precondition(archive.media?.first?.bytes == 3 && archive.reactions.first?.emoji == "❤️")
    precondition(archive.calls?.map(\.mediaKind) == ["audio", "video"])
    precondition(archive.calls?.map(\.endReason) == ["hangup", "missed"])
    precondition(archive.callDeletedIds == [id(12)] && archive.callClearedBeforeMs == 1900)
    precondition(archive.callChatClears == [id(6): 1950] && archive.chatClears == [id(6): 3])
    precondition(archive.deletedChats == [id(13)])
    let encoded = try archive.encoded()
    let decoded = try HistoryArchive.decode(encoded, server: id(1), owner: id(2))
    precondition(decoded == archive)
    let merged = try HistoryArchiveMerge.merge(local: archive, restored: decoded)
    precondition(merged == archive, "A repeated complete snapshot preserves media, reactions, calls and deletion policies")
    let normalized = try archive.restoringCallChatDescriptors()
    precondition(normalized == archive)
    if let path = ProcessInfo.processInfo.environment["UNYRI_HISTORY_COMPLETE_OTHER"] {
        let other = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath: path)), server: id(1), owner: id(2))
        precondition(other == archive, "The independent platform roundtrip must preserve every complete v2 field")
        print("PASS: production Swift decoder accepts independently emitted Kotlin complete v2 archive without field loss")
    }
    if let path = ProcessInfo.processInfo.environment["UNYRI_HISTORY_COMPLETE_OUTPUT"] {
        try encoded.write(to: URL(fileURLWithPath: path), options: .atomic)
    }
    print("PASS: shared complete v2 vector preserves attachment, reaction, both call types and message/call deletion policies")
}
try verifyCompleteSharedV2()

func verifyLegacyCallOnlySharedV2() throws {
    let bytes = try Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-archive-v2-call-only-legacy.json"))
    let archive = try HistoryArchive.decode(bytes, server: id(1), owner: id(2))
    precondition(archive.calls?.count == 3 && archive.chats?.isEmpty == true)
    rejected { try archive.validateBackupChatDescriptors() }
    let restored = try archive.restoringCallChatDescriptors()
    try restored.validateBackupChatDescriptors()
    precondition(restored.chats?.count == 2 && restored.calls == archive.calls)
    precondition(restored.callDeletedIds == archive.callDeletedIds && restored.callChatClears == archive.callChatClears)
    precondition(restored.callClearedBeforeMs == archive.callClearedBeforeMs)
    let second = try restored.restoringCallChatDescriptors()
    precondition(second == restored)
    var reversed = archive; reversed.calls = Array((archive.calls ?? []).reversed())
    let normalizedReverse = try reversed.restoringCallChatDescriptors()
    precondition(normalizedReverse.chats == restored.chats)
    precondition(restored.chats?.last?.peer?.id == id(15) && restored.chats?.last?.peer?.displayName == "Restored contact")
    var wrongPeer = restored
    wrongPeer.chats![0] = HistoryArchiveChat(id: id(6), type: "direct", createdAtMs: 1000,
        peer: HistoryArchivePeer(id: id(99), username: "wrong", displayName: "Wrong"))
    rejected { _ = try wrongPeer.restoringCallChatDescriptors() }
    var hidden = archive
    hidden.calls = hidden.calls?.map { value in var row = value; row.chatVisible = false; return row }
    hidden.deletedChats.formUnion([id(6), id(14)])
    let hiddenResult = try hidden.restoringCallChatDescriptors()
    precondition(hiddenResult.chats?.isEmpty == true && hiddenResult.calls?.count == 3)
    let encoded = try restored.encoded()
    let decoded = try HistoryArchive.decode(encoded, server: id(1), owner: id(2))
    precondition(decoded == restored)
    if let path = ProcessInfo.processInfo.environment["UNYRI_HISTORY_LEGACY_OUTPUT"] {
        try encoded.write(to: URL(fileURLWithPath: path), options: .atomic)
    }
    print("PASS: shared legacy call-only archive remains readable, gains two validated shells, preserves three calls/policies and rejects peer conflicts")
}
try verifyLegacyCallOnlySharedV2()

func verifyLegacyTextV1() throws {
    let bytes = try Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-archive-v1.json"))
    var archive = try HistoryArchive.decode(bytes, server: id(1), owner: id(2))
    archive.clearedThrough = 1; archive.chatClears = [id(6): 3]; archive.deletedChats = [id(13)]
    archive.reactions = [HistoryArchiveReaction(target: id(5), user: id(7), sequence: 20, emoji: "❤️")]
    let descriptor = HistoryArchiveChat(id: id(6), type: "direct", createdAtMs: 1000,
        peer: HistoryArchivePeer(id: id(7), username: "known", displayName: "Known peer"))
    let known = try archive.restoringLegacyTextHistory(chats: [descriptor])
    precondition(known.format == 2 && known.chats == [descriptor] && known.withoutMedia() == archive)
    let unknown = try archive.restoringLegacyTextHistory(chats: [])
    precondition(unknown.chats?.first?.type == "group" && unknown.chats?.first?.peer == nil)
    let media = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath:
        "shared/test-vectors/history-archive-v2-complete.json")), server: id(1), owner: id(2)).withoutMedia()
    rejected { _ = try media.restoringLegacyTextHistory(chats: []) }
    print("PASS: v1 text normalization preserves reactions/clears/deletions, reuses known chats, avoids guessed recipients and refuses manifestless media")
}
try verifyLegacyTextV1()

func id(_ n: Int) -> String { String(format: "00000000-0000-4000-8000-%012d", n) }
func rejected(_ work: () throws -> Void) { do { try work(); fatalError("Expected rejection") } catch {} }
let row = HistoryArchiveMessage(client:id(4), id:id(5), chat:id(6), sender:id(2), sequence:10, createdAtMs:1000, content:"{\"kind\":\"text\",\"text\":\"Привет\"}")
func archive(_ rows: [HistoryArchiveMessage]) -> HistoryArchive {
    HistoryArchive(server:id(1), owner:id(2), snapshot:id(3), messages:rows)
}
let original = archive([row])
let decoded = try HistoryArchive.decode(original.encoded(), server:id(1), owner:id(2))
precondition(decoded == original)
let again = try HistoryArchiveMerge.merge(local:original, restored:original)
precondition(again == original)
var edited = row; edited.content = "new"; edited.editedThrough = 12
let merged = try HistoryArchiveMerge.merge(local:archive([edited]), restored:original)
precondition(merged.messages == [edited])
var deleted = edited; deleted.content = nil; deleted.deletedThrough = 15
for pair in [(archive([deleted]), original), (original, archive([deleted]))] {
    let result = try HistoryArchiveMerge.merge(local:pair.0, restored:pair.1)
    precondition(result.messages[0].content == nil && result.messages[0].deletedThrough == 15)
}
var hidden = row; hidden.hidden = true; hidden.content = nil
let hiddenResult = try HistoryArchiveMerge.merge(local:archive([hidden]), restored:archive([edited]))
precondition(hiddenResult.messages[0].hidden && hiddenResult.messages[0].content == nil)
var cleared = archive([]); cleared.clearedThrough = 10
let clearResult = try HistoryArchiveMerge.merge(local:cleared, restored:original)
precondition(clearResult.messages.isEmpty)
var conflict = row; conflict.chat = id(9)
rejected { _ = try HistoryArchiveMerge.merge(local:original, restored:archive([conflict])) }
conflict = row; conflict.content = "conflict"
rejected { _ = try HistoryArchiveMerge.merge(local:original, restored:archive([conflict])) }
rejected { _ = try archive([row,row]).encoded() }
rejected { _ = try HistoryArchive.decode(original.encoded(), server:id(1), owner:id(9)) }
var invalid = row; invalid.hidden = true
rejected { _ = try archive([invalid]).encoded() }
invalid = row; invalid.sequence = 0
rejected { _ = try archive([invalid]).encoded() }
var unknown = try JSONSerialization.jsonObject(with: original.encoded()) as! [String: Any]
unknown["access_token"] = "must-not-be-a-field"
rejected { _ = try HistoryArchive.decode(JSONSerialization.data(withJSONObject: unknown), server:id(1), owner:id(2)) }
rejected { _ = try HistoryArchive.decode(Data(repeating:0, count:HistoryArchive.maxBytes + 1), server:id(1), owner:id(2)) }
var large = row; large.content = String(repeating:"a", count:1024 * 1024)
var large2 = large; large2.client = id(7); large2.id = id(8); large2.sequence = 11
rejected { _ = try archive([large, large2]).encoded() }
let fixture = try Data(contentsOf: URL(fileURLWithPath: "shared/test-vectors/history-archive-v1.json"))
let fromFixture = try HistoryArchive.decode(fixture, server:id(1), owner:id(2))
precondition(fromFixture == original)
if CommandLine.arguments.count == 2 {
    let input = try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))
    let fromKotlin = try HistoryArchive.decode(input, server:id(1), owner:id(2))
    precondition(fromKotlin == original)
}
print("History archive contracts passed")
