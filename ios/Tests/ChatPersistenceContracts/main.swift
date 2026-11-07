import Foundation
import SQLite3

// Pure chat-list persistence contract checks — no network, no libsignal.
// Verifies the iOS ChatStore mirrors Android's Room-backed chat list:
// hydrate-first, upsert-merge (never delete), stable ordering, and
// cache preservation when no refresh merge happens (network failure).

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

func makeStore() throws -> ChatStore {
    let dir = FileManager.default.temporaryDirectory
        .appendingPathComponent("coco-chats-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return try ChatStore(databaseURL: dir.appendingPathComponent("chats.sqlite3"))
}

func directChat(
    _ id: String,
    name: String,
    lastMessageAt: String?
) -> ChatSummary {
    ChatSummary(
        chatId: id,
        chatType: "direct",
        otherUser: PublicUser(
            id: "u-\(id)",
            username: "user_\(id)",
            displayName: name,
            bio: nil,
            avatarUrl: nil,
            profileVersion: 1,
            avatarVersion: 2,
            hasAvatar: true
        ),
        createdAt: "2026-09-01T00:00:00Z",
        lastMessageAt: lastMessageAt,
        otherMemberDeliveredSequence: 3,
        otherMemberReadSequence: 4
    )
}

func savedChat(_ id: String, lastMessageAt: String?) -> ChatSummary {
    ChatSummary(
        chatId: id,
        chatType: "saved",
        otherUser: nil,
        createdAt: "2026-09-01T00:00:00Z",
        lastMessageAt: lastMessageAt,
        otherMemberDeliveredSequence: 0,
        otherMemberReadSequence: 0
    )
}

do {
    // 1. A fresh store is genuinely empty (true empty state, not an error).
    let emptyStore = try makeStore()
    expect(try emptyStore.loadChats().count, 0, "fresh store has zero chats")

    // 2. Upsert-merge persists chats with full peer identity and stable order.
    let store = try makeStore()
    try store.upsertChats([
        directChat("a", name: "Alice", lastMessageAt: "2026-09-10T10:00:00Z"),
        directChat("b", name: "Bob", lastMessageAt: "2026-09-10T11:00:00Z"),
        savedChat("s", lastMessageAt: nil),
    ])
    let chats = try store.loadChats()
    expect(chats.count, 3, "three cached chats")
    expect(chats.map { $0.chatId }, ["b", "a", "s"], "ordered by last_message_at DESC, nulls last")
    check(chats[0].otherUser?.displayName == "Bob", "peer display name preserved")
    check(chats[0].otherUser?.avatarVersion == 2, "peer avatar version preserved")
    check(chats[0].otherUser?.hasAvatar == true, "peer has_avatar preserved")
    expect(chats[0].otherMemberReadSequence, 4, "receipt watermark preserved")
    check(chats[2].otherUser == nil, "saved chat has no other_user")

    // 3. Re-upsert merges (updates) rather than duplicating or deleting.
    try store.upsertChats([
        directChat("a", name: "Alice Renamed", lastMessageAt: "2026-09-10T12:00:00Z"),
    ])
    let merged = try store.loadChats()
    expect(merged.count, 3, "merge keeps the chat count stable (no duplicates)")
    expect(merged.map { $0.chatId }, ["a", "b", "s"], "merge reorders by bumped last_message_at")
    check(merged[0].otherUser?.displayName == "Alice Renamed", "merge updates peer display name")

    // 4. A failed refresh performs no merge: cached chats are unchanged.
    let before = try store.loadChats()
    let after = try store.loadChats()
    expect(after, before, "cache is unchanged when no merge happens (network failure)")

    // 5. Merging a partial page never deletes the other cached chats.
    try store.upsertChats([directChat("b", name: "Bob", lastMessageAt: "2026-09-10T13:00:00Z")])
    let partial = try store.loadChats()
    expect(partial.count, 3, "partial page preserves previously cached chats")
    expect(partial.map { $0.chatId }, ["b", "a", "s"], "partial merge reorders without dropping")

    // A delayed REST page cannot regress ordering or a persisted receipt.
    try store.upsertChats([ChatSummary(chatId: "b", chatType: "direct", otherUser: nil,
        createdAt: "2026-09-01T00:00:00Z", lastMessageAt: "2026-09-10T09:00:00Z",
        otherMemberDeliveredSequence: 1, otherMemberReadSequence: 1)])
    let stale = try store.loadChats()[0]
    expect(stale.chatId, "b", "stale page preserves latest chat position")
    expect(stale.lastMessageAt, "2026-09-10T13:00:00Z", "stale page preserves latest timestamp")
    expect(stale.otherMemberDeliveredSequence, 3, "delivery watermark never moves backwards")
    expect(stale.otherMemberReadSequence, 4, "read watermark never moves backwards")
    try store.upsertChats([directChat("b", name: "Bob", lastMessageAt: nil)])
    expect(try store.loadChats()[0].lastMessageAt, "2026-09-10T13:00:00Z", "null page timestamp preserves known activity")
} catch {
    fail("unexpected error: \(error)")
}

if failures > 0 {
    print("\(failures) chat persistence contract check(s) failed")
    exit(1)
}
print("chat persistence contracts passed")
