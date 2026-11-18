import Foundation
import SQLite3
private func check(_ value: Bool) { precondition(value) }
private func rejects(_ body: () throws -> Void) {
    do { try body(); preconditionFailure("Expected frozen/rejected binding") } catch {}
}
private func chat(_ id: String, peer: String?, type: String = "direct") -> ChatSummary {
    ChatSummary(chatId: id, chatType: type,
        otherUser: peer.map { PublicUser(id: $0, username: $0, displayName: "Familiar name", bio: nil, avatarUrl: nil,
            profileVersion: 1, avatarVersion: 0, hasAvatar: false) },
        createdAt: "2026-11-14T00:00:00Z", lastMessageAt: nil,
        otherMemberDeliveredSequence: 0, otherMemberReadSequence: 0)
}
let directory = FileManager.default.temporaryDirectory.appendingPathComponent("hostile-binding-" + UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let url = directory.appendingPathComponent("chats.sqlite3")
do {
    let store = try ChatStore(databaseURL: url)
    try store.validateBinding(chat("good", peer: "alice"), requestedPeer: "alice")
    try store.upsertChats([chat("good", peer: "alice"), chat("saved", peer: nil, type: "saved")])
    rejects { try store.validateBinding(chat("wrong-create", peer: "mallory"), requestedPeer: "alice") }
    try store.upsertChats([chat("redacted", peer: "alice")])
    var blocked = chat("redacted", peer: nil)
    blocked.isBlocked = true
    try store.upsertChats([blocked])
    check(try !store.bindingIsFrozen("redacted"))
    check(try store.pinnedDirectPeer("redacted") == "alice")
    check(try store.loadChats().first(where: { $0.chatId == "redacted" })?.otherUser?.id == "alice")
    check(try store.loadChats().first(where: { $0.chatId == "redacted" })?.isBlocked == true)
    rejects { try store.validateBinding(chat("absent", peer: nil), requestedPeer: "alice") }
    check(try !store.bindingIsFrozen("absent"))
    check(try store.loadChats().count == 3)
    rejects { try store.upsertChats([chat("good", peer: "mallory")]) }
    check(try store.bindingIsFrozen("good"))
    check(try store.loadChats().first(where: { $0.chatId == "good" })?.otherUser?.id == "alice")
    rejects { try store.validateBinding(chat("saved", peer: "mallory")) }
    try store.upsertUnboundHistoryShell(chat("legacy-shell", peer: nil, type: "group"))
    check(try store.incomingPeerMatches(chatID: "legacy-shell", senderID: "mallory", ownerID: "me") == nil)
}
do {
    let relaunched = try ChatStore(databaseURL: url)
    check(try !relaunched.bindingIsFrozen("redacted"))
    check(try relaunched.pinnedDirectPeer("redacted") == "alice")
    check(try relaunched.loadChats().first(where: { $0.chatId == "redacted" })?.isBlocked == true)
    var unblocked = chat("redacted", peer: "alice")
    unblocked.isBlocked = false
    try relaunched.upsertChats([unblocked])
    check(try !relaunched.bindingIsFrozen("redacted"))
    check(try relaunched.loadChats().first(where: { $0.chatId == "redacted" })?.isBlocked == false)
    check(try relaunched.unboundHistoryChatIDs().contains("legacy-shell"))
    try relaunched.upsertChats([chat("legacy-shell", peer: "alice")])
    // A later restore cannot turn an established direct pin into a shell.
    try relaunched.upsertUnboundHistoryShell(chat("legacy-shell", peer: nil, type: "group"))
    check(try relaunched.loadChats().first(where: { $0.chatId == "legacy-shell" })?.otherUser?.id == "alice")
    rejects { try relaunched.upsertChats([chat("legacy-shell", peer: "mallory")]) }
    try relaunched.upsertChats([chat("redacted", peer: nil)])
    check(try !relaunched.bindingIsFrozen("redacted"))
    try relaunched.upsertChats([chat("redacted", peer: "alice")])
    check(try !relaunched.bindingIsFrozen("redacted"))
    rejects { try relaunched.upsertChats([chat("redacted", peer: "mallory")]) }
    check(try relaunched.bindingIsFrozen("redacted"))
    check(try relaunched.bindingIsFrozen("good"))
    rejects { try relaunched.validateBinding(chat("good", peer: "alice")) }
    check(try relaunched.incomingPeerMatches(chatID: "good", senderID: "alice", ownerID: "me") == true)
    check(try relaunched.incomingPeerMatches(chatID: "good", senderID: "mallory", ownerID: "me") == false)
    check(try relaunched.incomingPeerMatches(chatID: "good", senderID: "me", ownerID: "me") == true)
    check(try relaunched.incomingPeerMatches(chatID: "saved", senderID: "mallory", ownerID: "me") == false)
    rejects { try relaunched.validateBinding(chat("saved", peer: nil, type: "saved")) }
    try relaunched.validateBinding(chat("case", peer: "alice"))
    rejects { try relaunched.validateBinding(chat("CASE", peer: "mallory")) }
    check(try relaunched.bindingIsFrozen("case"))
    try relaunched.validateBinding(chat("fresh", peer: "bob"), requestedPeer: "bob")
    rejects { try relaunched.validateBinding(chat("fresh", peer: nil, type: "saved")) }
    check(try relaunched.bindingIsFrozen("fresh"))
}
// Pre-security-binding cache: this device first saw these chats while the
// honest server redacted the peer. No current upsert helper may seed this
// fixture, because that would already run the policy being migrated.
let legacyURL = directory.appendingPathComponent("legacy-redacted.sqlite3")
do {
    var database: OpaquePointer?
    check(sqlite3_open(legacyURL.path, &database) == SQLITE_OK)
    guard let database else { preconditionFailure("Legacy SQLite fixture did not open") }
    defer { sqlite3_close(database) }
    let schema = """
        CREATE TABLE chats (
            chat_id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL,
            created_at TEXT NOT NULL, last_message_at TEXT,
            other_delivered_sequence INTEGER NOT NULL DEFAULT 0,
            other_read_sequence INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE chat_members (
            chat_id TEXT NOT NULL, user_id TEXT NOT NULL,
            PRIMARY KEY (chat_id, user_id)
        );
        INSERT INTO chats (chat_id, type, created_at) VALUES
            ('legacy-redacted', 'direct', '2026-11-14T00:00:00Z'),
            ('legacy-type', 'direct', '2026-11-14T00:00:00Z'),
            ('legacy-multi', 'direct', '2026-11-14T00:00:00Z');
        INSERT INTO chat_members (chat_id, user_id) VALUES
            ('legacy-multi', 'alice'), ('legacy-multi', 'mallory');
        """
    check(sqlite3_exec(database, schema, nil, nil, nil) == SQLITE_OK)
}
do {
    let migrated = try ChatStore(databaseURL: legacyURL)
    check(try !migrated.bindingIsFrozen("legacy-redacted"))
    check(try migrated.pinnedDirectPeer("legacy-redacted") == nil)
    check(try migrated.incomingPeerMatches(chatID: "legacy-redacted", senderID: "mallory", ownerID: "me") == nil)
    rejects { try migrated.validateBinding(chat("legacy-redacted", peer: nil), requestedPeer: "alice") }
    check(try !migrated.bindingIsFrozen("legacy-redacted"))
    rejects { try migrated.validateBinding(chat("legacy-type", peer: nil, type: "saved")) }
    check(try migrated.bindingIsFrozen("legacy-type"))
    check(try migrated.bindingIsFrozen("legacy-multi"))
}
do {
    let reopened = try ChatStore(databaseURL: legacyURL)
    check(try !reopened.bindingIsFrozen("legacy-redacted"))
    check(try reopened.pinnedDirectPeer("legacy-redacted") == nil)
    try reopened.validateBinding(chat("legacy-redacted", peer: "alice"), requestedPeer: "alice")
    try reopened.upsertChats([chat("legacy-redacted", peer: "alice")])
    check(try reopened.pinnedDirectPeer("legacy-redacted") == "alice")
    check(try !reopened.bindingIsFrozen("legacy-redacted"))
    rejects { try reopened.validateBinding(chat("legacy-redacted", peer: "mallory")) }
    check(try reopened.bindingIsFrozen("legacy-redacted"))
    // Neither migration on reopen nor first-peer promotion may clear a freeze.
    check(try reopened.bindingIsFrozen("legacy-type"))
    rejects { try reopened.validateBinding(chat("legacy-type", peer: "alice")) }
    check(try reopened.bindingIsFrozen("legacy-multi"))
}
print("Hostile direct creation, peer/type substitution, durable freeze and legitimate chat contracts passed")
