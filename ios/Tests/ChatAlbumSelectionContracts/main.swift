import Foundation

typealias Item = UnyriChatAlbumSelection.Item
var checks = 0
func check(_ value: Bool, _ reason: String) {
    checks += 1
    if !value { fputs("FAIL: \(reason)\n", stderr); exit(1) }
}
func item(_ n: Int, chat: String = "chat", sender: String = "sender", album: String? = "batch") -> Item {
    Item(id: "m\(n)", chat: chat, sender: sender, album: album)
}
let pair = [item(1), item(2)]
for target in pair {
    check(UnyriChatAlbumSelection.members(of: target, rows: [pair]) == ["m1", "m2"], "Every tile/caption action resolves the same visible album")
    check(UnyriChatAlbumSelection.members(of: target, rows: [pair], individual: true) == [target.id], "Individual selection requires an explicit action")
}
let ten = (1...10).map { item($0) }
check(UnyriChatAlbumSelection.members(of: ten[7], rows: [ten]).count == 10, "Full visible ten-item batch is one action")
check(UnyriChatAlbumSelection.members(of: item(1), rows: [[item(1),item(2),item(3)]]).count == 3, "Only known materialized members are included")
for foreign in [item(2, chat: "other"), item(2, sender: "other"), item(2, album: "other"), item(2, album: nil)] {
    check(UnyriChatAlbumSelection.members(of: item(1), rows: [[item(1), foreign]]) == ["m1"], "Chat/sender/album boundaries cannot be crossed")
}
check(UnyriChatAlbumSelection.members(of: item(1), rows: [(1...11).map { item($0) }]) == ["m1"], "Unexpected oversized groups are not expanded")
check(UnyriChatAlbumSelection.members(of: item(1), rows: [[item(1), item(1)]]) == ["m1"], "Duplicate rows cannot inflate a deletion count")
check(UnyriChatAlbumSelection.members(of: item(1), rows: []) == ["m1"], "No hidden history lookup is invented")
check(UnyriChatAlbumSelection.members(of: item(1, album: nil), rows: [pair]) == ["m1"], "Independent sends never merge")
let selected = UnyriChatAlbumSelection.toggled(["text"], members: ["m1", "m2"])
check(selected == ["text", "m1", "m2"], "Album tap adds all visible members while preserving unrelated selections")
check(UnyriChatAlbumSelection.toggled(selected, members: ["m1", "m2"]) == ["text"], "Second album tap removes the whole selected unit")
check(UnyriChatAlbumSelection.toggled(["m1"], members: ["m1", "m2"]) == ["m1", "m2"], "Partial selection first completes the visible group")
let confirmed = UnyriChatAlbumSelection.members(of: item(1), rows: [pair])
_ = UnyriChatAlbumSelection.members(of: item(1), rows: [[item(1),item(2),item(3)]])
check(confirmed == ["m1","m2"], "A captured confirmation never silently expands when new members arrive")
let fileIntent = MediaGallerySendChoice.original.intent(caption: "Documents")
check(fileIntent.albumId != nil, "Original files selected together retain an encrypted batch identifier")
let laterIntent = MediaGallerySendChoice.original.intent()
check(laterIntent.albumId != fileIntent.albumId, "Independent file sends have distinct batch identifiers")
check(fileIntent.caption(at: 0) == "Documents" && fileIntent.caption(at: 1).isEmpty, "A grouped document caption is not duplicated")
var nextID = 0
let forwarded = UnyriChatAlbumSelection.forwardingAlbums(pair + [item(3, album: nil)] + [item(4, album: "other"), item(5, album: "other")]) {
    nextID += 1; return "fresh-\(nextID)"
}
check(forwarded["m1"] == "fresh-1" && forwarded["m2"] == "fresh-1", "Forwarded album remains grouped with a fresh identifier")
check(forwarded["m3"] == nil, "Forwarding an independent message does not fabricate an album")
check(forwarded["m4"] == "fresh-2" && forwarded["m5"] == "fresh-2", "Unrelated albums never merge on forwarding")
check(UnyriChatAlbumSelection.forwardingAlbums([item(1)]).isEmpty, "Explicit forwarding of one item stays independent")
let interleavedForward = UnyriChatAlbumSelection.forwardingAlbums([item(1),item(99,album:nil),item(2)]) { "fresh-album" }
check(interleavedForward["m1"] == "fresh-album" && interleavedForward["m2"] == "fresh-album" && interleavedForward["m99"] == nil,
      "Selecting parallel text does not split a forwarded album")
let legacy = try JSONDecoder().decode(MediaSendIntent.self, from: Data("{\"mode\":\"file\",\"highQuality\":false,\"caption\":\"Legacy\"}".utf8))
check(legacy.albumId == nil, "Legacy file intents without a batch are not retroactively grouped")
let mixed = [item(1), item(2), item(3)]
let cancelled = UnyriChatAlbumSelection.pendingMembers(of: mixed[0], rows: [mixed], pendingIDs: ["m1", "m2", "foreign"])
check(cancelled == ["m1", "m2"], "Cancel intersects current pending IDs with the exact displayed group; delivered/foreign rows excluded")
check(UnyriChatAlbumSelection.pendingMembers(of: mixed[0], rows: [mixed], pendingIDs: ["m2"]) == ["m2"], "ACK of tapped tile does not cancel delivered history or block remaining pending members")
_ = UnyriChatAlbumSelection.pendingMembers(of: item(1), rows: [mixed + [item(4)]], pendingIDs: ["m1", "m2", "m4"])
check(cancelled == ["m1", "m2"], "A captured cancellation never expands to a later appended member")
check(UnyriChatAlbumSelection.pendingMembers(of: item(1), rows: [[item(1), item(2, sender: "foreign")]], pendingIDs: ["m1", "m2"]) == ["m1"], "Malformed groups cannot cross the sender boundary")
check(UnyriChatAlbumSelection.pendingMembers(of: item(1), rows: [mixed], pendingIDs: []).isEmpty, "Duplicate or already completed cancel is a no-op")
print("PASS: \(checks) album selection, cancellation, boundary, partial-history, confirmation and original-file batch contracts")
