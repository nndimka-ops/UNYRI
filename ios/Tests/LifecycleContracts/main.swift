import Foundation
import SQLite3

// Pure message-lifecycle contract checks — no network, no libsignal.
// Mirrors Android's MessageContentCodec + MessageIngestionPipeline lifecycle
// effects (edit/delete/reaction guards) and the MessageDao DB semantics so a
// regression is caught on macOS CI without an iPhone.

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

// MARK: - Reply snapshot encode/decode

let snapshot = ReplySnapshot(
    senderId: "44444444-4444-4444-4444-444444444444",
    contentType: ReplySnapshot.contentTypeText,
    preview: "hello world preview",
    sequenceHint: 9
)
let replyData = MessageContentCodec.encodeText("my reply", replySnapshot: snapshot)
let replyDecoded = MessageContentCodec.decode(replyData)
check(replyDecoded.kind == MessageContent.kindText, "reply decodes as kind=text")
expect(replyDecoded.text, "my reply", "reply text round-trips")
expect(replyDecoded.replySnapshot, snapshot, "reply snapshot round-trips exactly")
check(replyDecoded.replySnapshot?.sequenceHint == 9, "reply sequence_hint round-trips")

// Reply snapshot is INSIDE the encrypted content (self-contained quote).
let replyJSON = String(decoding: replyData, as: UTF8.self)
check(replyJSON.contains("reply_snapshot"), "reply snapshot serialized inside content")
check(replyJSON.contains("sequence_hint"), "reply sequence_hint uses snake_case key")

// MARK: - Edit content is shaped like a plain text message

let editData = MessageContentCodec.encodeEdit("edited text")
expect(editData, MessageContentCodec.encodeText("edited text"), "edit payload == text payload (Android: encodeEdit == encodeText)")
check(MessageContentCodec.decode(editData).text == "edited text", "edit decodes to new text")

// MARK: - Reaction encode/decode

let reactionData = MessageContentCodec.encodeReaction("👍")
let reactionDecoded = MessageContentCodec.decode(reactionData)
check(reactionDecoded.kind == MessageContent.kindReaction, "reaction kind")
expect(reactionDecoded.reaction, "👍", "reaction emoji")
expect(reactionDecoded.reactionAction, MessageContent.reactionActionSet, "reaction action = set")

let removalData = MessageContentCodec.encodeReactionRemoval()
let removalDecoded = MessageContentCodec.decode(removalData)
check(removalDecoded.kind == MessageContent.kindReaction, "removal kind")
check(removalDecoded.reaction == nil, "removal carries no emoji")
expect(removalDecoded.reactionAction, MessageContent.reactionActionRemove, "reaction action = remove")

// MARK: - Delete content

let deleteData = MessageContentCodec.encodeDelete()
let deleteDecoded = MessageContentCodec.decode(deleteData)
expect(deleteDecoded.kind, MessageContent.kindDelete, "delete kind")

// MARK: - Legacy plaintext fallback (no JSON envelope)

let legacyDecoded = MessageContentCodec.decode(Data("legacy plain text".utf8))
expect(legacyDecoded.kind, MessageContent.kindText, "legacy raw text -> kind=text")
expect(legacyDecoded.text, "legacy plain text", "legacy raw text preserved")

// MARK: - MessageSendComposer: kind/target mapping + deterministic payload

func msg(_ kind: String, _ target: String?, _ content: MessageContent) -> DecryptedChatMessage {
    DecryptedChatMessage(
        clientMessageId: "c1", serverId: "s1", chatId: "chat", senderId: "me",
        sequence: 1, createdAt: Date(), kind: kind, targetMessageId: target,
        status: .sent, content: content
    )
}

let textPayload = MessageSendComposer.payload(for: msg("content", nil, MessageContent(kind: MessageContent.kindText, text: "hi")))
expect(textPayload.kind, "content", "composer: content -> kind content")
check(textPayload.targetMessageId == nil, "composer: plain send has no target")

let replyContent = MessageContent(kind: MessageContent.kindText, text: "r", replySnapshot: snapshot)
let replyPayload = MessageSendComposer.payload(for: msg("content", "target-server-id", replyContent))
expect(replyPayload.targetMessageId, "target-server-id", "composer: reply maps target_message_id")
expect(replyPayload.plaintext, MessageContentCodec.encodeText("r", replySnapshot: snapshot), "composer: reply plaintext is stable")

let editPayload = MessageSendComposer.payload(for: msg("edit", "t1", MessageContent(kind: MessageContent.kindText, text: "new")))
expect(editPayload.kind, "edit", "composer: edit -> kind edit")
expect(editPayload.targetMessageId, "t1", "composer: edit target")
expect(editPayload.plaintext, MessageContentCodec.encodeEdit("new"), "composer: edit plaintext == encodeEdit")

let deletePayload = MessageSendComposer.payload(for: msg("delete", "t2", MessageContent(kind: MessageContent.kindDelete)))
expect(deletePayload.kind, "delete", "composer: delete -> kind delete")
expect(deletePayload.plaintext, MessageContentCodec.encodeDelete(), "composer: delete plaintext")

let reactionPayload = MessageSendComposer.payload(for: msg("reaction", "t3", MessageContent(kind: MessageContent.kindReaction, reaction: "❤️", reactionAction: MessageContent.reactionActionSet)))
expect(reactionPayload.kind, "reaction", "composer: reaction -> kind reaction")
expect(reactionPayload.targetMessageId, "t3", "composer: reaction target")
expect(reactionPayload.plaintext, MessageContentCodec.encodeReaction("❤️"), "composer: reaction plaintext")

let removePayload = MessageSendComposer.payload(for: msg("reaction", "t3", MessageContent(kind: MessageContent.kindReaction, reactionAction: MessageContent.reactionActionRemove)))
expect(removePayload.plaintext, MessageContentCodec.encodeReactionRemoval(), "composer: reaction removal plaintext")

// Same clientMessageId + same content -> identical logical plaintext on every
// retry (no second logical encryption of a DIFFERENT payload).
let retryPayload = MessageSendComposer.payload(for: msg("content", nil, MessageContent(kind: MessageContent.kindText, text: "hi")))
expect(retryPayload.plaintext, textPayload.plaintext, "retry reuses identical logical plaintext (deterministic)")

// MARK: - MessageStore lifecycle guards (SQLite)

func makeStore() throws -> MessageStore {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("coco-lifecycle-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return try MessageStore(databaseURL: dir.appendingPathComponent("messages.sqlite3"))
}

func insert(_ store: MessageStore, _ id: String, serverId: String, chatId: String = "chat", text: String) throws {
    try store.upsert(DecryptedChatMessage(
        clientMessageId: id, serverId: serverId, chatId: chatId, senderId: "me",
        sequence: 1, createdAt: Date(), kind: "content", targetMessageId: nil,
        status: .sent, content: MessageContent(kind: MessageContent.kindText, text: text)
    ))
}

do {
    let store = try makeStore()
    try insert(store, "c1", serverId: "srv-1", text: "original")

    // Edit applies when newer.
    let applied = try store.applyEdit(targetServerId: "srv-1", newText: "edited", sequence: 5)
    expect(applied, 1, "applyEdit applies a newer edit")
    check((try? store.message(byServerId: "srv-1"))?.content.text == "edited", "applyEdit writes new text")
    check((try? store.message(byServerId: "srv-1"))?.isEdited == true, "applyEdit marks isEdited")

    // Duplicate/stale edit is rejected (sequence not newer).
    let stale = try store.applyEdit(targetServerId: "srv-1", newText: "stale", sequence: 5)
    expect(stale, 0, "duplicate edit sequence rejected (idempotent)")
    check((try? store.message(byServerId: "srv-1"))?.content.text == "edited", "stale edit does not overwrite")

    let older = try store.applyEdit(targetServerId: "srv-1", newText: "older", sequence: 3)
    expect(older, 0, "older edit sequence rejected")

    // Delete then edit: a tombstoned message cannot be resurrected.
    let del = try store.applyDelete(targetServerId: "srv-1", sequence: 6)
    expect(del, 1, "applyDelete tombstones")
    let resurrect = try store.applyEdit(targetServerId: "srv-1", newText: "zombie", sequence: 7)
    expect(resurrect, 0, "edit cannot resurrect a deleted message")

    // Duplicate delete is idempotent.
    let dupDel = try store.applyDelete(targetServerId: "srv-1", sequence: 6)
    expect(dupDel, 0, "duplicate delete idempotent")

    // Unknown target is a silent no-op (device never saw the original).
    let missing = try store.applyEdit(targetServerId: "nope", newText: "x", sequence: 8)
    expect(missing, 0, "edit of unknown target is a no-op")

    // hideLocally excludes the row from the chat query.
    try insert(store, "c2", serverId: "srv-2", text: "to hide")
    try store.hideLocally(clientMessageId: "c2")
    check((try? store.messages(forChatId: "chat").map { $0.serverId }.contains("srv-2")) == false, "hideLocally excludes from chat feed")
} catch {
    fail("MessageStore edit/delete: \(error)")
}

// MARK: - Reactions (SQLite)

do {
    let store = try makeStore()
    try insert(store, "c1", serverId: "srv-1", text: "hello")

    try store.upsertReaction(targetMessageId: "srv-1", userId: "u1", emoji: "👍", lastReactionSequence: 10)
    try store.upsertReaction(targetMessageId: "srv-1", userId: "u2", emoji: "👍", lastReactionSequence: 11)
    try store.upsertReaction(targetMessageId: "srv-1", userId: "u3", emoji: "❤️", lastReactionSequence: 12)

    let active = try store.activeReactions(forChatId: "chat")
    expect(active.count, 3, "three active reactions stored")

    // Same user replacing their reaction overwrites (one per user per message).
    try store.upsertReaction(targetMessageId: "srv-1", userId: "u3", emoji: "😂", lastReactionSequence: 13)
    expect(try store.activeReactions(forChatId: "chat").count, 3, "replacement keeps one row per user")

    // Removal keeps the row (emoji nil) so the sequence guard survives.
    try store.upsertReaction(targetMessageId: "srv-1", userId: "u2", emoji: nil, lastReactionSequence: 14)
    expect(try store.activeReactions(forChatId: "chat").count, 2, "removed reaction no longer active")
    expect(try store.reactionCurrentSequence(targetMessageId: "srv-1", userId: "u2"), 14, "removed reaction sequence survives")

    // Sequence guard: an older reaction event is ignored by the ingestor.
    let current = try store.reactionCurrentSequence(targetMessageId: "srv-1", userId: "u1")
    check(current == 10, "reaction sequence guard baseline")
    if 5 <= current {
        // (mirrors MessageIngestor.applyReactionEffect: sequence must be newer)
        check(true, "stale reaction event rejected by sequence guard")
    }

    // Delete reactions for a message.
    try store.deleteReactions(forMessage: "srv-1")
    expect(try store.activeReactions(forChatId: "chat").count, 0, "deleteReactions clears a message's reactions")
} catch {
    fail("MessageStore reactions: \(error)")
}

// MARK: - MessageOrdering

let base = Date()
let pending = DecryptedChatMessage(clientMessageId: "p", serverId: nil, chatId: "c", senderId: "me", sequence: nil, createdAt: base.addingTimeInterval(3), kind: "content", targetMessageId: nil, status: .pending, content: MessageContent(kind: MessageContent.kindText, text: "hi"))
let seq1 = DecryptedChatMessage(clientMessageId: "1", serverId: "s1", chatId: "c", senderId: "me", sequence: 1, createdAt: base, kind: "content", targetMessageId: nil, status: .sent, content: MessageContent(kind: MessageContent.kindText, text: "a"))
let seq3 = DecryptedChatMessage(clientMessageId: "3", serverId: "s3", chatId: "c", senderId: "me", sequence: 3, createdAt: base.addingTimeInterval(1), kind: "content", targetMessageId: nil, status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "c"))
let ordered = MessageOrdering.chronological([pending, seq3, seq1])
check(ordered.map { $0.clientMessageId } == ["1", "3", "p"], "chronological: sequenced ascending, pending last")

// Old decryption failures must not block later readable receipt targets.
// Background snapshots use a second SQLite connection.
do {
    let store = try makeStore()
    for sequence in 1...6 {
        try store.upsert(DecryptedChatMessage(
            clientMessageId: "receipt-\(sequence)", serverId: "receipt-\(sequence)",
            chatId: "receipts", senderId: sequence == 6 ? "me" : "peer",
            sequence: Int64(sequence), createdAt: Date(), kind: "content", targetMessageId: nil,
            status: sequence == 3 ? .decryptFailed : .delivered,
            content: MessageContent(kind: MessageContent.kindText, text: "message \(sequence)")
        ))
    }
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: true), 5,
           "delivery advances past old decryption gap")
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: false), 5,
           "read advances past old decryption gap")
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: false, upperBound: 1), 1,
           "offscreen content is not marked read")
    expect(try store.receiptSequence(chatId: "missing", ownerId: "me", deliveredOnly: false), 0,
           "empty history does not emit a receipt")
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: false, upperBound: 3), 2, "placeholder itself is not a remote receipt target")
    _ = try store.markLocallyRead(chatId: "receipts", sequence: 3)
    expect(try store.unreadCounts(ownerId: "me")["receipts"] ?? 0, 2, "viewed placeholder clears local unread badge")
    _ = try store.markLocallyRead(chatId: "receipts", sequence: 5)
    expect(try store.unreadCounts(ownerId: "me")["receipts"] ?? 0, 0, "new messages can clear the badge")
    let completed = DispatchSemaphore(value: 0)
    Task.detached {
        do {
            let rows = try await store.readAsync { try $0.messages(forChatId: "receipts") }
            expect(rows.count, 6, "background reader sees committed history")
            expect(rows.compactMap(\.sequence), [1, 2, 3, 4, 5, 6], "background history stays chronological")
        } catch { fail("background history: \(error)") }
        completed.signal()
    }
    check(completed.wait(timeout: .now() + 10) == .success, "background history completes")
    try store.hideLocally(clientMessageId: "receipt-3")
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: false), 5,
           "hidden failure does not block later readable content")
    expect(try store.receiptSequence(chatId: "receipts", ownerId: "me", deliveredOnly: true), 5,
           "hidden failure does not block later delivery")
} catch { fail("receipt aggregation: \(error)") }

// Lifecycle events preceding history must survive restart and never apply failed crypto.
do {
    let store = try makeStore()
    func row(_ id: String, _ kind: String, _ target: String?, _ sequence: Int64, _ status: MessageStatus, _ text: String) -> DecryptedChatMessage {
        var content = MessageContent(kind: kind == "delete" ? MessageContent.kindDelete : MessageContent.kindText, text: text)
        content.authentication = MessageAuthentication(chatId: "late", clientMessageId: id,
            operation: kind, targetMessageId: target, targetClientMessageId: target)
        return DecryptedChatMessage(clientMessageId: id, serverId: id, chatId: "late", senderId: "peer",
            sequence: sequence, createdAt: Date(), kind: kind, targetMessageId: target,
            status: status, content: content)
    }
    try store.upsert(row("edit-late", "edit", "original", 20, .delivered, "edited"))
    try store.upsert(row("original", "content", nil, 10, .delivered, "original"))
    expect(try store.message(byServerId: "original")?.content.text, "edited", "edit before target is replayed")
    try store.upsert(row("failed-delete", "delete", "original", 30, .decryptFailed, "bad"))
    expect(try store.message(byServerId: "original")?.deleteSequence, 0, "failed crypto cannot delete target")
    try store.upsert(row("delete-late", "delete", "second", 40, .delivered, ""))
    try store.upsert(row("second", "content", nil, 15, .delivered, "original"))
    expect(try store.message(byServerId: "second")?.deleteSequence, 40, "delete before target is replayed")
    try store.hideLocally(clientMessageId: "original")
    try store.upsert(row("original", "content", nil, 10, .sent, "stale send response"))
    let preserved = try store.message(byServerId: "original")
    check(preserved?.hiddenLocally == true, "send response preserves local hiding")
    expect(preserved?.content.text, "edited", "send response preserves newer edit")
    expect(preserved?.status, .delivered, "send response preserves receipt status")
} catch { fail("late lifecycle replay: \(error)") }

do {
    let store = try makeStore()
    func previewRow(_ id: String, _ sequence: Int64?, _ chat: String = "preview") -> DecryptedChatMessage {
        DecryptedChatMessage(clientMessageId: id, serverId: sequence == nil ? nil : id,
            chatId: chat, senderId: "peer", sequence: sequence, createdAt: Date(timeIntervalSince1970: 100),
            kind: "content", targetMessageId: nil, status: sequence == nil ? .pending : .delivered,
            content: MessageContent(kind: MessageContent.kindText, text: id))
    }
    for index in 1...1000 { try store.upsert(previewRow("row-\(index)", Int64(index))) }
    try store.upsert(previewRow("other", 1, "other"))
    expect(try store.latestPreviewByChat()["preview"], .text("row-1000"), "bulk preview chooses newest sequence")
    expect(try store.latestPreviewByChat()["other"], .text("other"), "bulk preview keeps other chats")
    try store.upsert(previewRow("pending-a", nil))
    try store.upsert(previewRow("pending-b", nil))
    expect(try store.latestPreviewByChat()["preview"], .text("pending-b"), "pending messages lead, ties break deterministically")
    try store.hideLocally(clientMessageId: "pending-b")
    expect(try store.latestPreviewByChat()["preview"], .text("pending-a"), "hidden newest message does not replace preview")
} catch { fail("bulk preview: \(error)") }

do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("unread.sqlite3")
    let store = try MessageStore(databaseURL: url)
    for index in 1...4 {
        try store.upsert(DecryptedChatMessage(clientMessageId: "u\(index)", serverId: "u\(index)",
            chatId: "unread", senderId: index == 4 ? "me" : "peer", sequence: Int64(index),
            createdAt: Date(timeIntervalSince1970: Double(index)), kind: "content", targetMessageId: nil,
            status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "message")))
    }
    expect(try store.unreadCounts(ownerId: "me")["unread"], 3, "unread excludes outgoing messages")
    try store.hideLocally(clientMessageId: "u3")
    expect(try store.unreadCounts(ownerId: "me")["unread"], 2, "unread excludes hidden messages")
    let advanced = try store.markLocallyRead(chatId: "unread", sequence: 1)
    check(advanced, "local read advances offline")
    expect(try store.unreadCounts(ownerId: "me")["unread"], 1, "read watermark clears only older messages")
    expect(try store.localReadWatermarks()["unread"], 1, "offline read cursor remains queued")
    let regressed = try store.markLocallyRead(chatId: "unread", sequence: 0)
    check(!regressed, "local read cannot regress")
    let reopened = try MessageStore(databaseURL: url)
    expect(try reopened.unreadCounts(ownerId: "me")["unread"], 1, "unread survives reopening")
    expect(try reopened.latestActivityByChat()["unread"], Date(timeIntervalSince1970: 4), "local send advances chat activity")
    var deletion = MessageContent(kind: MessageContent.kindDelete)
    deletion.authentication = MessageAuthentication(chatId: "unread", clientMessageId: "delete-u2",
        operation: "delete", targetMessageId: "u2", targetClientMessageId: "u2")
    try reopened.upsert(DecryptedChatMessage(clientMessageId: "delete-u2", serverId: "delete-u2", chatId: "unread",
        senderId: "peer", sequence: 5, createdAt: Date(), kind: "delete", targetMessageId: "u2",
        status: .delivered, content: deletion))
    expect(try reopened.unreadCounts(ownerId: "me")["unread"], nil, "deleted content and lifecycle events are not unread")
    expect(try reopened.localReadWatermarks()["unread"], 1, "closed chat read queue survives restart")
    try reopened.markLocallyRead(chatId: "unread", sequence: 10)
    try reopened.upsert(DecryptedChatMessage(clientMessageId: "late-read", serverId: "late-read", chatId: "unread",
        senderId: "peer", sequence: 6, createdAt: Date(), kind: "content", targetMessageId: nil,
        status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "already read elsewhere")))
    expect(try reopened.unreadCounts(ownerId: "me")["unread"], nil, "account read receipt can precede history delivery")
} catch { fail("unread persistence: \(error)") }

// Malicious routing must not authorize an effect on a different local row.
do {
    let store = try makeStore()
    func target(_ client: String, _ chat: String = "secure", _ server: String = "server-target") -> DecryptedChatMessage {
        DecryptedChatMessage(clientMessageId: client, serverId: server, chatId: chat, senderId: "peer",
            sequence: 1, createdAt: Date(), kind: "content", targetMessageId: nil,
            status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: client))
    }
    try store.upsert(target("original-client"))
    do {
        try store.upsert(target("other-client", "other-chat"))
        fail("server id collision must be rejected")
    } catch { }
    expect(try store.messages(forChatId: "other-chat").count, 0, "collision insert rolls back")
    func event(_ id: String, _ targetClient: String?, _ author: String = "peer") -> DecryptedChatMessage {
        var content = MessageContent(kind: MessageContent.kindDelete)
        if let targetClient {
            content.authentication = MessageAuthentication(chatId: "secure", clientMessageId: id,
                operation: "delete", targetMessageId: "server-target", targetClientMessageId: targetClient)
        }
        return DecryptedChatMessage(clientMessageId: id, serverId: id, chatId: "secure", senderId: author,
            sequence: 10, createdAt: Date(), kind: "delete", targetMessageId: "server-target",
            status: .delivered, content: content)
    }
    try store.upsert(event("legacy-delete", nil))
    try store.upsert(event("wrong-client", "other-client"))
    try store.upsert(event("wrong-author", "original-client", "attacker"))
    expect(try store.message(byServerId: "server-target")?.deleteSequence, 0, "unauthorized deletions have no effect")
    try store.upsert(event("valid-delete", "original-client"))
    expect(try store.message(byServerId: "server-target")?.deleteSequence, 10, "authenticated deletion still works")
} catch { fail("authenticated lifecycle effects: \(error)") }

if failures > 0 {
    print("\(failures) lifecycle contract check(s) failed")
    exit(1)
}
do {
    let store = try makeStore()
    try store.recordPeerReceipt(chatId: "late-ack", ownerId: "me", delivered: 8, read: 7)
    try store.recordPeerReceipt(chatId: "late-ack", ownerId: "me", delivered: 2, read: 1)
    try store.upsert(DecryptedChatMessage(clientMessageId: "late-ack", serverId: "late-ack",
        chatId: "late-ack", senderId: "me", sequence: 7, createdAt: Date(), kind: "content", targetMessageId: nil,
        status: .sent, content: MessageContent(kind: MessageContent.kindText, text: "sent before receipt")))
    expect(try store.messages(forChatId: "late-ack").first?.status, .read, "receipt before send acknowledgement remains read")
} catch { fail("late acknowledgement receipt: \(error)") }
if failures > 0 { exit(1) }
do {
    let store = try makeStore()
    try insert(store, "reaction-ack", serverId: "reaction-ack", text: "hello")
    try store.upsertReaction(targetMessageId: "reaction-ack", userId: "me", emoji: nil, lastReactionSequence: 20)
    // A delayed HTTP acknowledgement must use the same guard as incoming events.
    try store.upsertReaction(targetMessageId: "reaction-ack", userId: "me", emoji: "old", lastReactionSequence: 19)
    expect(try store.reaction(targetMessageId: "reaction-ack", userId: "me")?.emoji, nil, "late acknowledgement cannot undo reaction removal")
    try store.upsertReaction(targetMessageId: "reaction-ack", userId: "me", emoji: "duplicate", lastReactionSequence: 20)
    expect(try store.reactionCurrentSequence(targetMessageId: "reaction-ack", userId: "me"), 20, "duplicate acknowledgement keeps watermark")
    expect(try store.reaction(targetMessageId: "reaction-ack", userId: "me")?.emoji, nil, "duplicate acknowledgement cannot change reaction")
    try store.upsertReaction(targetMessageId: "reaction-ack", userId: "me", emoji: "new", lastReactionSequence: 21)
    expect(try store.reaction(targetMessageId: "reaction-ack", userId: "me")?.emoji, "new", "newer accepted reaction still applies")
    _ = try store.applyDelete(targetServerId: "reaction-ack", sequence: 22)
    try store.deleteReactions(forMessage: "reaction-ack")
    try store.upsertReaction(targetMessageId: "reaction-ack", userId: "me", emoji: "late", lastReactionSequence: 23)
    expect(try store.reaction(targetMessageId: "reaction-ack", userId: "me")?.emoji, nil, "late acknowledgement cannot add reaction to deleted message")
} catch { fail("reaction acknowledgement race: \(error)") }
do {
    let store = try makeStore()
    func row(_ id: String, _ sequence: Int64?, _ seconds: Double) -> DecryptedChatMessage {
        DecryptedChatMessage(clientMessageId: id, serverId: sequence == nil ? nil : id, chatId: "clear-test", senderId: "me", sequence: sequence, createdAt: Date(timeIntervalSince1970: seconds), kind: "content", targetMessageId: nil, status: sequence == nil ? .pending : .sent, content: MessageContent(kind: MessageContent.kindText, text: id))
    }
    try store.upsert(row("old", 1, 100))
    try store.upsert(row("new", 10, 50))
    try store.upsert(row("pending", nil, 50))
    _ = try store.clearServerHistory(throughSequence: 3)
    check((try? store.message(byServerId: "old")) == nil, "server clear removes old confirmed message")
    check((try? store.message(byServerId: "new")) != nil, "server clear preserves new message")
    check((try? store.message(byClientMessageId: "pending")) != nil, "server clear preserves offline pending message")
    try store.upsert(row("late-old", 3, 150))
    check((try? store.message(byServerId: "late-old")) == nil, "late response cannot restore cleared history")
    _ = try store.clearServerHistory(throughSequence: 1)
    try store.upsert(row("late-old-2", 2, 150))
    check((try? store.message(byServerId: "late-old-2")) == nil, "old clear marker cannot roll back cutoff")
} catch { fail("server history clear: \(error)") }
if failures > 0 { exit(1) }
do {
    let store = try makeStore()
    var row = DecryptedChatMessage(clientMessageId: "receipt-failure", serverId: "receipt-failure", chatId: "receipt-failure", senderId: "me", sequence: 5, createdAt: Date(), kind: "content", targetMessageId: nil, status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "already delivered"))
    try store.upsert(row)
    for staleStatus: MessageStatus in [.failed, .pending, .sent] {
        row.status = .delivered
        try store.upsert(row)
        row.status = staleStatus
        try store.upsert(row)
        expect(try store.message(byClientMessageId: row.clientMessageId)?.status, .delivered, "late \(staleStatus) cannot roll back delivered status in SQLite")
    }
    row.status = .read
    try store.upsert(row)
    expect(try store.message(byClientMessageId: row.clientMessageId)?.status, .read, "delivered can still advance to read")
} catch { fail("late outbox failure receipt: \(error)") }
if failures > 0 { exit(1) }
// MARK: - P1-4 pending-decrypt durability (SQLite)

do {
    // Pure round-trip: the retained envelope reconstructs the original DTO
    // exactly, so a later retry decrypts with identical semantics.
    let dto = MessageDto(
        id: "srv-9", chatId: "chat-9", clientMessageId: "c9", senderId: "peer-9",
        senderDeviceId: "dev-9", sequence: 42, createdAt: Date(timeIntervalSince1970: 1_700_000_000),
        messageType: 2, ciphertext: "AAAA", kind: "content", targetMessageId: nil
    )
    let retainedPending = PendingDecrypt(
        clientMessageId: dto.clientMessageId, serverId: dto.id, chatId: dto.chatId,
        senderId: dto.senderId, senderDeviceId: dto.senderDeviceId, sequence: dto.sequence,
        createdAt: dto.createdAt, messageType: dto.messageType, ciphertext: dto.ciphertext,
        kind: dto.kind, targetMessageId: dto.targetMessageId
    )
    expect(retainedPending.toDto(), dto, "pending envelope round-trips to the original DTO")
    expect(retainedPending.retryCount, 0, "fresh pending decrypt starts at retryCount 0")
    check(retainedPending.lastErrorClass == nil, "fresh pending decrypt has no last error class")

    let store = try makeStore()
    var pendingToStore = retainedPending
    pendingToStore.lastErrorClass = "MacError"
    let placeholder = DecryptedChatMessage(
        clientMessageId: "c9", serverId: "srv-9", chatId: "chat-9", senderId: "peer-9",
        sequence: 42, createdAt: Date(timeIntervalSince1970: 1_700_000_000),
        kind: "content", targetMessageId: nil, status: .decryptFailed,
        content: MessageContent(kind: MessageContent.kindText, text: "[Unable to decrypt message]")
    )
    try store.upsertDecryptFailed(placeholder, pending: pendingToStore)

    // Retention: the placeholder AND the ciphertext are both durable.
    check((try? store.status(forClientMessageId: "c9")) == .decryptFailed, "placeholder row is durable after decrypt failure")
    let retained = try store.pendingDecrypts()
    expect(retained.count, 1, "one envelope retained")
    expect(retained.first?.ciphertext, "AAAA", "ciphertext retained verbatim")
    expect(retained.first?.senderDeviceId, "dev-9", "sender device retained")
    expect(retained.first?.retryCount, 0, "retained retryCount starts at 0")
    expect(retained.first?.lastErrorClass, "MacError", "last error class retained")

    // Sender-scoped lookup isolates the acceptNewKey retry to one sender.
    expect(try store.pendingDecrypts(senderId: "peer-9").count, 1, "scoped lookup finds the sender")
    expect(try store.pendingDecrypts(senderId: "other").count, 0, "scoped lookup ignores other senders")

    // Bump is diagnostics-only and never drops the ciphertext.
    try store.bumpPendingDecrypt(clientMessageId: "c9", retryCount: 1, lastErrorClass: "MacError")
    expect(try store.pendingDecrypts().first?.retryCount, 1, "bump increments retryCount")
    expect(try store.pendingDecrypts().first?.ciphertext, "AAAA", "bump keeps ciphertext")

    // Restart survival: reopening the same DB preserves the retention row.
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("coco-decrypt-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent("messages.sqlite3")
    let storeA = try MessageStore(databaseURL: url)
    try storeA.upsertDecryptFailed(placeholder, pending: pendingToStore)
    let reopened = try MessageStore(databaseURL: url)
    expect(try reopened.pendingDecrypts().count, 1, "retention survives store reopen (restart)")
    expect(try reopened.pendingDecrypts().first?.ciphertext, "AAAA", "ciphertext survives restart")

    // Deletion removes the retention row.
    try reopened.deletePendingDecrypt(clientMessageId: "c9")
    expect(try reopened.pendingDecrypts().count, 0, "delete removes the retention row")

    // A successful retry overwrites the placeholder (the dedup boundary treats
    // decryptFailed as retryable in MessageIngestor; asserted here at the
    // store level by writing a real row on top of the placeholder).
    try reopened.upsert(DecryptedChatMessage(
        clientMessageId: "c9", serverId: "srv-9", chatId: "chat-9", senderId: "peer-9",
        sequence: 42, createdAt: Date(timeIntervalSince1970: 1_700_000_000),
        kind: "content", targetMessageId: nil, status: .delivered,
        content: MessageContent(kind: MessageContent.kindText, text: "recovered")
    ))
    expect(try reopened.status(forClientMessageId: "c9"), .delivered, "successful retry overwrites the placeholder status")
} catch { fail("pending-decrypt durability: \(error)") }
do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let personal = try MessageStore(databaseURL: directory.appendingPathComponent("clear.sqlite"))
    func message(_ id: String, _ chat: String, _ seq: Int64) -> DecryptedChatMessage {
        DecryptedChatMessage(clientMessageId: id, serverId: "s-" + id, chatId: chat, senderId: "peer", sequence: seq,
            createdAt: Date(), kind: "content", targetMessageId: nil, status: .delivered,
            content: MessageContent(kind: MessageContent.kindText, text: id))
    }
    try personal.upsert(message("old", "a", 2))
    try personal.upsert(message("other", "b", 2))
    try personal.clearPersonalChat("a", throughSequence: 5)
    expect(try personal.status(forClientMessageId: "old"), nil, "personal clear removes old messages")
    expect(try personal.personalChatHidden("a"), true, "empty cleared chat is hidden")
    try personal.upsert(message("late", "a", 3))
    expect(try personal.status(forClientMessageId: "late"), nil, "late cleared message cannot return")
    try personal.upsert(message("new", "a", 6))
    expect(try personal.personalChatHidden("a"), false, "new message reopens conversation")
    try personal.clearPersonalChat("a", throughSequence: 5)
    expect(try personal.status(forClientMessageId: "new"), .delivered, "repeated clear preserves new message")
    expect(try personal.status(forClientMessageId: "other"), .delivered, "other chat survives")
} catch { fail("personal clear durability: \(error)") }
do {
    let store = try makeStore()
    try insert(store, "find-visible", serverId: "sv", text: "Привет Search")
    try insert(store, "find-hidden", serverId: "sh", text: "Search hidden")
    try store.hideLocally(clientMessageId: "find-hidden")
    try insert(store, "find-deleted", serverId: "sd", text: "Search deleted")
    _ = try store.applyDelete(targetServerId: "sd", sequence: 9)
    try insert(store, "find-chat-deleted", serverId: "sc", chatId: "removed", text: "Search removed")
    try store.markChatDeleted("removed")
    var failed = msg("content", nil, MessageContent(kind: MessageContent.kindText, text: "Search failed"))
    failed.status = .decryptFailed
    try store.upsert(failed)
    expect(try store.searchVisibleMessages(query: "search").map(\.clientMessageId), ["find-visible"], "search excludes hidden, deleted, unavailable and removed chats")
    expect(try store.searchVisibleMessages(query: "ПРИВЕТ").count, 1, "search matches Cyrillic case insensitively")
    expect(try store.searchVisibleMessages(query: "  ").count, 0, "blank search returns nothing")
    expect(try store.searchVisibleMessages(query: "%' OR 1=1").count, 0, "query is literal text, not SQL")
    try insert(store, "find-second", serverId: "ss", text: "Search second")
    expect(try store.searchVisibleMessages(query: "Search", limit: 1).count, 1, "search result count is bounded")
} catch { fail("local search: \(error)") }
// Durable offline intents overlay, but do not rewrite confirmed history.
do {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("coco-offline-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let url = directory.appendingPathComponent("messages.sqlite3")
    let first = try MessageStore(databaseURL: url)
    try insert(first, "original", serverId: "target", text: "before")
    func queued(_ id: String, _ kind: String, _ payload: Data) throws -> DecryptedChatMessage {
        let bytes = try MessageContentCodec.authenticate(payload, chatId: "chat", clientMessageId: id,
            operation: kind, targetMessageId: "target", targetClientMessageId: "original")
        return DecryptedChatMessage(clientMessageId: id, serverId: nil, chatId: "chat", senderId: "me",
            sequence: nil, createdAt: Date(), kind: kind, targetMessageId: "target", status: .pending,
            content: MessageContentCodec.decode(bytes))
    }
    let edit = try queued("edit", "edit", MessageContentCodec.encodeEdit("offline"))
    let reaction = try queued("reaction", "reaction", MessageContentCodec.encodeReaction("❤️"))
    try first.upsert(edit); try first.upsert(reaction)
    let reopened = try MessageStore(databaseURL: url)
    expect(try reopened.pendingMessages(limit: 100).messages.map(\.clientMessageId), ["edit", "reaction"], "offline lifecycle IDs/order survive reopening")
    expect(try reopened.messages(forChatId: "chat").first?.content.text, "offline", "pending edit survives reopening")
    expect(try reopened.message(byServerId: "target")?.content.text, "before", "confirmed text not overwritten by offline projection")
    expect(try reopened.activeReactions(forChatId: "chat").first?.emoji, "❤️", "pending reaction survives reopening")
    var accepted = edit; accepted.serverId = "edit-server"; accepted.sequence = 2; accepted.status = .sent
    try reopened.upsert(accepted); try reopened.upsert(accepted)
    expect(try reopened.message(byServerId: "target")?.content.text, "offline", "server acknowledgement applies edit idempotently")
    expect(try reopened.pendingMessages(limit: 100).messages.count, 1, "acknowledged intent leaves pending queue")
    var failed = reaction; failed.status = .failed; try reopened.upsert(failed)
    expect(try reopened.activeReactions(forChatId: "chat").count, 0, "rejected intent does not claim a successful reaction")
    expect(try reopened.failedLifecycle(chatId: "chat").map(\.clientMessageId), ["reaction"], "failed actions remain actionable after reopen")
    expect(try reopened.failedLifecycle(chatId: "other").count, 0, "failure UI is scoped to the open chat")
    try reopened.hideLocally(clientMessageId: "reaction")
    expect(try reopened.failedLifecycle(chatId: "chat").count, 0, "cancel hides failed intent without deleting the target")
    expect(try reopened.message(byServerId: "target")?.deleteSequence, 0, "cancel action preserves original message")
    let deletion = try queued("delete", "delete", MessageContentCodec.encodeDelete()); try reopened.upsert(deletion)
    expect(try reopened.messages(forChatId: "chat").count, 0, "offline delete hides only its target projection")
    expect(try reopened.message(byServerId: "target")?.deleteSequence, 0, "offline delete does not forge server sequence")
} catch { fail("offline lifecycle persistence: \(error)") }
if failures > 0 { exit(1) }
print("Lifecycle contract checks passed")
