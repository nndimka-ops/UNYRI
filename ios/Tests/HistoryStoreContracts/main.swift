import Foundation
import SQLite3

@main
struct HistoryStoreContracts {
    static func id(_ n: Int) -> String { String(format: "00000000-0000-4000-8000-%012d", n) }
    @MainActor static func verifySenderAcknowledgements(in directory: URL) throws {
        let database = directory.appendingPathComponent("sender-lifecycle-acks.sqlite3")
        let store = try MessageStore(databaseURL: database)
        let chat = id(700), owner = id(701), targetClient = id(702), targetServer = id(703)
        let target = DecryptedChatMessage(clientMessageId: targetClient, serverId: targetServer,
            chatId: chat, senderId: owner, sequence: 100, createdAt: Date(timeIntervalSince1970: 1),
            kind: "content", targetMessageId: nil, status: .sent, content: MessageContent(kind: "text", text: "original"))
        try store.upsert(target)

        func lifecycle(_ n: Int, kind: String = "reaction", emoji: String? = nil, text: String = "edited",
                       targetClient: String = id(702), targetServer: String = id(703), chat: String = id(700)) throws -> DecryptedChatMessage {
            let plaintext: Data
            switch kind {
            case "edit": plaintext = MessageContentCodec.encodeEdit(text)
            case "delete": plaintext = MessageContentCodec.encodeDelete()
            default: plaintext = emoji.map(MessageContentCodec.encodeReaction) ?? MessageContentCodec.encodeReactionRemoval()
            }
            let authenticated = try MessageContentCodec.authenticate(plaintext, chatId: chat,
                clientMessageId: id(n), operation: kind, targetMessageId: targetServer, targetClientMessageId: targetClient)
            return DecryptedChatMessage(clientMessageId: id(n), serverId: nil, chatId: chat,
                senderId: owner, sequence: nil, createdAt: Date(timeIntervalSince1970: Double(n)),
                kind: kind, targetMessageId: targetServer, status: .pending, content: MessageContentCodec.decode(authenticated))
        }
        func ack(_ value: DecryptedChatMessage, sequence: Int64) throws {
            var sent = value
            sent.serverId = id(2000 + Int(sequence)); sent.sequence = sequence; sent.status = .sent
            // This is the production sender ACK transaction. No ingestion,
            // self-device echo or second store.upsert is allowed to repair it.
            try store.upsertSentWithPendingEdit(sent, editRow: nil)
        }
        func active() throws -> [ReactionRow] {
            try store.activeReactions(forChatId: chat).filter { $0.targetMessageId == targetServer && $0.userId == owner }
        }
        let first = try lifecycle(710, emoji: "👍")
        try store.upsert(first)
        let beforeAck = try active()
        precondition(beforeAck.count == 1 && beforeAck[0].emoji == "👍")
        try ack(first, sequence: 101)
        let afterAck = try active()
        precondition(afterAck.count == 1 && afterAck[0].emoji == "👍",
                     "The first reaction must remain visible after ACK without an own-device echo")

        let removal = try lifecycle(711)
        try store.upsert(removal)
        let pendingRemoval = try active()
        precondition(pendingRemoval.isEmpty)
        try ack(removal, sequence: 102)
        let acknowledgedRemoval = try active()
        precondition(acknowledgedRemoval.isEmpty, "A removal must stay removed when pending overlay becomes confirmed")
        let removed = try store.reaction(targetMessageId: targetServer, userId: owner)
        precondition(removed?.emoji == nil && removed?.lastReactionSequence == 102)

        let heart = try lifecycle(712, emoji: "❤️")
        try store.upsert(heart); try ack(heart, sequence: 103)
        // Late/duplicate older ACKs cannot overwrite a newer confirmed intent.
        try ack(first, sequence: 101); try ack(removal, sequence: 102); try ack(heart, sequence: 103)
        let newest = try active()
        precondition(newest.count == 1 && newest[0].emoji == "❤️")
        let rapidChoices: [String?] = ["👍", nil, "❤️", nil, "👍"]
        let rapid = try rapidChoices.enumerated().map { offset, emoji in try lifecycle(720 + offset, emoji: emoji) }
        for event in rapid { try store.upsert(event) }
        for (offset, event) in rapid.enumerated() {
            try ack(event, sequence: 104 + Int64(offset))
            let visible = try active()
            precondition(visible.count == 1 && visible[0].emoji == "👍", "Pending latest intent must survive every earlier ACK without duplicates")
        }
        let confirmed = try store.reaction(targetMessageId: targetServer, userId: owner)
        precondition(confirmed?.emoji == "👍" && confirmed?.lastReactionSequence == 108)

        let edit = try lifecycle(730, kind: "edit", text: "edited by sender ACK")
        try store.upsert(edit); try ack(edit, sequence: 109)
        let edited = try store.message(byServerId: targetServer)
        precondition(edited?.content.text == "edited by sender ACK" && edited?.lastEditSequence == 109,
                     "Sender edit ACK must commit the target projection without an echo")
        let deletion = try lifecycle(731, kind: "delete")
        try store.upsert(deletion); try ack(deletion, sequence: 110)
        let deleted = try store.message(byServerId: targetServer)
        let deletedReactions = try active()
        precondition(deleted?.deleteSequence == 110 && deletedReactions.isEmpty,
                     "Sender delete ACK must commit its tombstone and erase reactions")
        try ack(first, sequence: 101)
        let afterLateAck = try active()
        precondition(afterLateAck.isEmpty, "An old reaction ACK must never resurrect a deleted target")

        // Force the newly replayed SQLite side effect to fail after writing
        // the sent content, inserting its deferred edit and clearing intent.
        // The production transaction must roll ALL of them back together.
        let rollbackChat = id(740), rollbackClient = id(741), rollbackServer = id(742)
        let pending = DecryptedChatMessage(clientMessageId: rollbackClient, serverId: nil,
            chatId: rollbackChat, senderId: owner, sequence: nil, createdAt: Date(), kind: "content",
            targetMessageId: nil, status: .pending, content: MessageContent(kind: "text", text: "frozen original"))
        try store.upsert(pending)
        try store.beginSend(clientMessageId: rollbackClient, fallbackText: "frozen original")
        let accepted = try store.setPendingEdit(clientMessageId: rollbackClient,
            content: MessageContent(kind: "text", text: "latest intent"), revision: 7)
        precondition(accepted)
        var waiting = try lifecycle(743, emoji: "👍", targetClient: rollbackClient, targetServer: rollbackServer, chat: rollbackChat)
        waiting.serverId = id(744); waiting.sequence = 201; waiting.status = .sent
        try store.upsert(waiting)
        var acknowledged = try store.message(byClientMessageId: rollbackClient)!
        acknowledged.serverId = rollbackServer; acknowledged.sequence = 200; acknowledged.status = .sent
        let deferred = try lifecycle(745, kind: "edit", text: "latest intent", targetClient: rollbackClient, targetServer: rollbackServer, chat: rollbackChat)
        func sql(_ statement: String) {
            var handle: OpaquePointer?
            precondition(sqlite3_open(database.path, &handle) == SQLITE_OK)
            defer { sqlite3_close(handle) }
            precondition(sqlite3_exec(handle, statement, nil, nil, nil) == SQLITE_OK)
        }
        sql("CREATE TRIGGER reject_ack_replay BEFORE INSERT ON reactions BEGIN SELECT RAISE(ABORT, 'fixture replay failure'); END;")
        var rejected = false
        do { try store.upsertSentWithPendingEdit(acknowledged, editRow: deferred) }
        catch { rejected = true }
        precondition(rejected, "A failed lifecycle replay must fail the ACK transaction")
        let preserved = try store.message(byClientMessageId: rollbackClient)
        precondition(preserved?.serverId == nil && preserved?.status == .pending && preserved?.pendingEditText == "latest intent" && preserved?.pendingEditRevision == 7 && preserved?.payloadText == "frozen original")
        let rolledBackEdit = try store.message(byClientMessageId: deferred.clientMessageId)
        precondition(rolledBackEdit == nil,
                     "Rollback must not leave a deferred edit detached from its pending target")
        sql("DROP TRIGGER reject_ack_replay;")
        try store.upsertSentWithPendingEdit(acknowledged, editRow: deferred)
        let retried = try store.message(byClientMessageId: rollbackClient)
        precondition(retried?.serverId == rollbackServer && retried?.pendingEditText == nil)
        let retriedEdit = try store.message(byClientMessageId: deferred.clientMessageId)
        precondition(retriedEdit != nil)
        let retriedReaction = try store.reaction(targetMessageId: rollbackServer, userId: owner)
        precondition(retriedReaction?.emoji == "👍" && retriedReaction?.lastReactionSequence == 201)
        print("Native sender ACK lifecycle contracts passed: no-echo set/remove/edit/delete, rapid intents, stale/duplicate ACKs and atomic rollback/retry")
    }
    @MainActor static func verifyLegacyProjectionRepair(in directory: URL) async throws {
        func require(_ value: Bool, _ message: String = "") { precondition(value, message) }
        let path = directory.appendingPathComponent("legacy-lifecycle.sqlite3")
        let store = try MessageStore(databaseURL: path)
        let chat = id(4000), owner = id(4001), client = id(4002), server = id(4003)
        let deletedClient = id(4004), deletedServer = id(4005)
        func target(_ client: String, _ server: String, hidden: Bool = false) throws {
            try store.upsert(DecryptedChatMessage(clientMessageId: client, serverId: server,
                chatId: chat, senderId: owner, sequence: 1, createdAt: Date(timeIntervalSince1970: 1),
                kind: "content", targetMessageId: nil, status: .sent,
                content: MessageContent(kind: "text", text: "old private text"), hiddenLocally: hidden))
        }
        try target(client, server); try target(deletedClient, deletedServer)
        let hiddenClient = id(4006), hiddenServer = id(4007)
        try target(hiddenClient, hiddenServer, hidden: true)
        var db: OpaquePointer?
        precondition(sqlite3_open(path.path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        func sql(_ statement: String) {
            precondition(sqlite3_exec(db, statement, nil, nil, nil) == SQLITE_OK, statement)
        }
        func oldACK(_ number: Int, sequence: Int64, kind: String, targetClient: String = id(4002),
                    targetServer: String = id(4003), sender: String = id(4001), validAuth: Bool = true,
                    status: String = "sent", emoji: String? = "👍") throws {
            let raw: Data
            switch kind {
            case "edit": raw = MessageContentCodec.encodeEdit("new private text \(sequence)")
            case "delete": raw = MessageContentCodec.encodeDelete()
            default: raw = emoji.map(MessageContentCodec.encodeReaction) ?? MessageContentCodec.encodeReactionRemoval()
            }
            let payload = try MessageContentCodec.authenticate(raw, chatId: chat, clientMessageId: id(number),
                operation: kind, targetMessageId: targetServer,
                targetClientMessageId: validAuth ? targetClient : id(4999))
            let json = String(decoding: try JSONEncoder().encode(MessageContentCodec.decode(payload)), as: UTF8.self)
                .replacingOccurrences(of: "'", with: "''")
            // Reproduce OLD production ACK: persist an event but do not replay.
            // A normal new store.upsert would repair previous confirmed events.
            sql("INSERT INTO messages(client_message_id,server_id,chat_id,sender_id,sequence,created_at,kind,target_message_id,status,content_json) VALUES('\(id(number))','\(id(number + 10000))','\(chat)','\(sender)',\(sequence),\(number),'\(kind)','\(targetServer)','\(status)','\(json)');")
        }
        // Deliberately insert out of sequence order, including invalid and
        // cancelled events. Repair must authenticate and sort actual rows.
        try oldACK(4100, sequence: 10, kind: "edit")
        try oldACK(4101, sequence: 20, kind: "reaction")
        try oldACK(4102, sequence: 30, kind: "reaction", emoji: nil)
        try oldACK(4103, sequence: 40, kind: "delete", targetClient: deletedClient, targetServer: deletedServer)
        try oldACK(4104, sequence: 50, kind: "reaction", targetClient: deletedClient, targetServer: deletedServer)
        try oldACK(4105, sequence: 15, kind: "edit", targetClient: hiddenClient, targetServer: hiddenServer)
        try oldACK(4106, sequence: 60, kind: "delete", validAuth: false)
        try oldACK(4107, sequence: 70, kind: "delete", sender: id(4998))
        try oldACK(4108, sequence: 80, kind: "delete", status: "failed")
        for number in 0..<65 {
            try oldACK(4200 + number, sequence: 100 + Int64(number), kind: "reaction", emoji: number % 2 == 0 ? "❤️" : nil)
        }
        let oldVisible = try store.messages(forChatId: chat)
        precondition(oldVisible.count == 2 && oldVisible.allSatisfy { $0.content.text == "old private text" })
        require(try !store.legacyLifecycleRepairIsComplete(chatId: chat))

        // A thrown cancellation after effects and cursor write, immediately
        // before COMMIT, rolls back both. An interrupted launch can retry.
        do {
            _ = try store.repairLegacyLifecycleBatch(chatId: chat) {
                if try store.chatValue(chatId: chat, name: "lifecycle_projection_v1") != nil { throw CancellationError() }
            }
            preconditionFailure("Expected cancellation before COMMIT")
        } catch is CancellationError {}
        require(try store.chatValue(chatId: chat, name: "lifecycle_projection_v1") == nil)
        require(try store.message(byServerId: server)?.content.text == "old private text")
        require(try store.message(byServerId: deletedServer)?.deleteSequence == 0)

        // Real SQLite failure after the earlier edit also rolls the batch and
        // watermark back. This is independent of task cancellation.
        sql("CREATE TRIGGER reject_legacy_reaction BEFORE INSERT ON reactions BEGIN SELECT RAISE(ABORT,'fixture'); END;")
        do { _ = try store.repairLegacyLifecycleBatch(chatId: chat); preconditionFailure("Expected SQLite failure") }
        catch {}
        require(try store.message(byServerId: server)?.content.text == "old private text")
        require(try store.chatValue(chatId: chat, name: "lifecycle_projection_v1") == nil)
        sql("DROP TRIGGER reject_legacy_reaction;")
        let firstComplete = try store.repairLegacyLifecycleBatch(chatId: chat)
        precondition(!firstComplete)
        let checkpoint = try store.chatValue(chatId: chat, name: "lifecycle_projection_v1")!
        let fields = try JSONSerialization.jsonObject(with: Data(checkpoint.utf8)) as! [String: Any]
        precondition((fields["sequence"] as? Int) == 123, "Exactly 32 sorted events belong to the first transaction")
        require(try store.message(byServerId: deletedServer)?.deleteSequence == 40)

        // Reopen with a persisted partial cursor; async production path must
        // finish remaining chunks before the history/preview gate opens.
        let reopened = try MessageStore(databaseURL: path)
        try await reopened.repairLegacyLifecycle(chatId: chat)
        require(try reopened.legacyLifecycleRepairIsComplete(chatId: chat))
        let visible = try reopened.messages(forChatId: chat)
        precondition(visible.count == 1 && visible[0].clientMessageId == client)
        precondition(visible[0].content.text == "new private text 10" && visible[0].lastEditSequence == 10)
        require(try reopened.message(byServerId: hiddenServer)?.hiddenLocally == true)
        require(try reopened.message(byServerId: hiddenServer)?.content.text == "new private text 15")
        require(try reopened.reaction(targetMessageId: server, userId: owner)?.lastReactionSequence == 164)
        require(try reopened.activeReactions(forChatId: chat).allSatisfy { $0.targetMessageId != deletedServer })
        let finalMarker = try reopened.chatValue(chatId: chat, name: "lifecycle_projection_v1")
        sql("CREATE TRIGGER reject_repair_write BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT,'second call must be read only'); END;")
        try await reopened.repairLegacyLifecycle(chatId: chat)
        require(try reopened.chatValue(chatId: chat, name: "lifecycle_projection_v1") == finalMarker)
        sql("DROP TRIGGER reject_repair_write;")

        // Legacy databases need not have a unique sequence index. A full
        // equal-sequence group spans batches without being skipped or looped.
        let tiedChat = id(5001)
        sql("INSERT INTO messages(client_message_id,chat_id,sender_id,sequence,created_at,kind,target_message_id,status,content_json) SELECT 'tied-' || n,'\(tiedChat)','\(owner)',1,1,'reaction','absent','sent','{}' FROM (WITH RECURSIVE n(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM n WHERE n<64) SELECT n FROM n);")
        let tiedFirst = try reopened.repairLegacyLifecycleBatch(chatId: tiedChat)
        let tiedSecond = try reopened.repairLegacyLifecycleBatch(chatId: tiedChat)
        let tiedThird = try reopened.repairLegacyLifecycleBatch(chatId: tiedChat)
        precondition(!tiedFirst && !tiedSecond && tiedThird,
                     "All 64 rows sharing a sequence must traverse two full chunks and the terminal probe")

        // Measure a large legacy batch with the production implementation.
        // This reports wall time only, never a physical-device FPS claim.
        let largeChat = id(5000)
        sql("INSERT INTO messages(client_message_id,server_id,chat_id,sender_id,sequence,created_at,kind,target_message_id,status,content_json) SELECT 'large-' || n,NULL,'\(largeChat)','\(owner)',n,1,'reaction','absent','sent','{}' FROM (WITH RECURSIVE n(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM n WHERE n<4096) SELECT n FROM n);")
        let start = Date()
        try await reopened.repairLegacyLifecycle(chatId: largeChat)
        require(try reopened.legacyLifecycleRepairIsComplete(chatId: largeChat))
        print("PASS: bounded legacy projection repair: 4096 events, 129 transactions, \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        await reopened.finishLegacyLifecycleRepairs()
        do { try await reopened.repairLegacyLifecycle(chatId: chat); preconditionFailure("Teardown must fence later repair") }
        catch is CancellationError {}
    }

    @MainActor static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("coco-history-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try verifySenderAcknowledgements(in: directory)
        try await verifyLegacyProjectionRepair(in: directory)
        do {
            let store = try MessageStore(databaseURL: directory.appendingPathComponent("visibility.sqlite3"))
            func put(_ n: Int, _ sequence: Int64?, _ status: MessageStatus, kind: String = "content", hidden: Bool = false) throws {
                try store.upsert(DecryptedChatMessage(clientMessageId:id(n),serverId:sequence == nil ? nil : id(n + 100),
                    chatId:id(n + 200),senderId:id(2),sequence:sequence,createdAt:Date(),kind:kind,targetMessageId:nil,
                    status:status,content:MessageContent(kind:"text",text:"fixture"),hiddenLocally:hidden))
            }
            let empty = try store.visibleChatIDs(historyCutoff:100)
            precondition(empty.isEmpty)
            try put(50,99,.decryptFailed)
            try put(51,100,.identityChanged)
            try put(52,101,.decryptFailed)
            try put(53,nil,.pending)
            try put(54,80,.sent)
            try put(55,102,.sent,hidden:true)
            try put(56,103,.sent,kind:"reaction")
            try store.saveChatValue(chatId:id(260),name:"opened_on_device",value:"1")
            let visible = try store.visibleChatIDs(historyCutoff:100)
            precondition(visible == Set([id(252),id(253),id(254),id(260)]))
            try put(50,99,.sent)
            let restored = try store.visibleChatIDs(historyCutoff:100)
            precondition(restored.contains(id(250)))
            let reopened = try MessageStore(databaseURL:directory.appendingPathComponent("visibility.sqlite3"))
            let persisted = try reopened.visibleChatIDs(historyCutoff:100)
            precondition(persisted == restored)
        }
        do {
        let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
        let payload = "{\"kind\":\"text\",\"text\":\"saved\"}"
        let row = HistoryArchiveMessage(client:id(4),id:id(5),chat:id(6),sender:id(2),sequence:10,createdAtMs:1000,content:payload)
        let archive = HistoryArchive(server:id(1),owner:id(2),snapshot:id(3),messages:[row])
        func restore(_ value: HistoryArchive) throws {
            _ = try store.importHistoryArchive(value,server:id(1),owner:id(2),assertCurrentAndQuiescent:{})
        }
        func rejected(_ action: () throws -> Void) {
            do { try action(); fatalError("Expected failure") } catch {}
        }
        // A backed-up readable message replaces a retained decryption placeholder.
        try store.upsert(DecryptedChatMessage(clientMessageId:row.client,serverId:row.id,chatId:row.chat,senderId:row.sender,
            sequence:row.sequence,createdAt:Date(timeIntervalSince1970:1),kind:"content",targetMessageId:nil,
            status:.decryptFailed,content:MessageContent(kind:"text",text:"unreadable")))
        try restore(archive)
        try restore(archive)
        let restored = try store.messages(forChatId: row.chat)
        precondition(restored.count == 1 && restored[0].content.text == "saved" && restored[0].status == .sent)
        _ = try store.applyEdit(targetServerId:row.id,newText:"newer",sequence:12)
        try restore(archive)
        let newer = try store.message(byServerId:row.id)
        precondition(newer?.content.text == "newer")
        try store.upsertReaction(targetMessageId:row.id,userId:id(8),emoji:nil,lastReactionSequence:20)
        var reacted = archive
        reacted.reactions = [HistoryArchiveReaction(target:row.id,user:id(8),sequence:15,emoji:"👍")]
        try restore(reacted)
        let reaction = try store.reaction(targetMessageId:row.id,userId:id(8))
        precondition(reaction?.lastReactionSequence == 20 && reaction?.emoji == nil)
        // An account change detected just before COMMIT rolls back all writes.
        var additional = row; additional.client=id(9); additional.id=id(10); additional.sequence=30
        let extra = HistoryArchive(server:id(1),owner:id(2),snapshot:id(3),messages:[additional])
        var checks = 0
        rejected {
            _ = try store.importHistoryArchive(extra,server:id(1),owner:id(2),assertCurrentAndQuiescent:{
                checks += 1
                if checks == 2 { throw HistoryArchive.Failure.conflict }
            })
        }
        let rolledBack = try store.message(byServerId:additional.id)
        precondition(rolledBack == nil)
        _ = try store.applyDelete(targetServerId:row.id,sequence:25)
        try restore(archive)
        let deleted = try store.message(byServerId:row.id)
        precondition(deleted?.deleteSequence == 25)
        let erasedReaction = try store.reaction(targetMessageId:row.id,userId:id(8))
        precondition(erasedReaction == nil)
        let exported = try store.exportHistoryArchive(server:id(1),owner:id(2),snapshot:id(3),assertCurrentAndQuiescent:{})
        precondition(exported.messages[0].content == nil)
        let attachments = try AttachmentStore(databaseURL:directory.appendingPathComponent("attachments.sqlite3"))
        let metadata = AttachmentMetadata(attachmentId:id(18),mediaType:"file",objectKey:"attachments/test",size:3,encryptedSize:31,mimeType:"application/octet-stream",
            encryption:EncryptionInfo(key:Data(repeating:1,count:32).base64EncodedString(),nonce:Data(repeating:2,count:12).base64EncodedString(),sha256:Data(repeating:3,count:32).base64EncodedString()),
            filename:"example.bin",image:nil,voice:nil,thumbnail:nil)
        let mediaPayload = String(decoding:try JSONEncoder().encode(MessageContent(kind:"attachment",attachment:metadata)),as:UTF8.self)
        let media = HistoryArchiveMessage(client:id(14),id:id(15),chat:id(16),sender:id(2),sequence:50,createdAtMs:1000,content:mediaPayload,replyTo:row.id)
        try restore(HistoryArchive(server:id(1),owner:id(2),snapshot:id(3),messages:[media]))
        var journal = try store.historyAttachmentJobs()
        precondition(journal.count == 1)
        let reply = try store.message(byServerId:media.id)
        precondition(reply?.targetMessageId == row.id)
        var deletedChats = Set<String>()
        rejected {
            try HistoryAttachmentRestorer.resume(messages:store,attachments:attachments,
                applyDeletedChat:{ deletedChats.insert($0) },removeCachedFile:{ _ in },assertCurrent:{
                if try attachments.attachment(byId:metadata.attachmentId) != nil { throw HistoryArchive.Failure.conflict }
            })
        }
        journal = try store.historyAttachmentJobs()
        precondition(journal.count == 1)
        try HistoryAttachmentRestorer.resume(messages:store,attachments:attachments,
            applyDeletedChat:{ deletedChats.insert($0) },removeCachedFile:{ _ in },assertCurrent:{})
        journal = try store.historyAttachmentJobs()
        precondition(journal.isEmpty)
        let hydrated = try attachments.attachment(byId:metadata.attachmentId)
        precondition(hydrated?.clientMessageId == media.client && hydrated?.localPath == nil && hydrated?.downloadState == "not_downloaded")
        try store.markChatDeleted(row.chat)
        try restore(archive)
        let empty = try store.messages(forChatId:row.chat)
        precondition(empty.isEmpty)
        let final = try store.exportHistoryArchive(server:id(1),owner:id(2),snapshot:id(3),assertCurrentAndQuiescent:{})
        precondition(final.deletedChats == [row.chat] && final.messages.map { $0.client } == [media.client])
        try HistoryAttachmentRestorer.resume(messages:store,attachments:attachments,
            applyDeletedChat:{ deletedChats.insert($0) },removeCachedFile:{ _ in },assertCurrent:{})
        precondition(deletedChats == [row.chat])
        try store.upsert(DecryptedChatMessage(clientMessageId:id(30),serverId:nil,chatId:id(16),senderId:id(2),sequence:nil,
            createdAt:Date(),kind:"content",targetMessageId:nil,status:.pending,content:MessageContent(kind:"text",text:"unsent")))
        try restore(archive)
        let pending = try store.message(byClientMessageId:id(30))
        precondition(pending?.content.text == "unsent" && pending?.status == .pending)
        try store.hideLocally(clientMessageId:id(30))
        try restore(archive)

        let editId = id(31)
        try store.upsert(DecryptedChatMessage(clientMessageId:editId,serverId:nil,chatId:id(16),senderId:id(2),
            sequence:nil,createdAt:Date(),kind:"content",targetMessageId:nil,status:.pending,
            content:MessageContent(kind:"text",text:"old")))
        let direct = try store.updateTextBeforeSend(clientMessageId:editId,
            content:MessageContent(kind:"text",text:"edited"))
        precondition(direct)
        let afterDirect = try store.message(byClientMessageId:editId)
        precondition(afterDirect?.content.text == "edited" && afterDirect?.payloadText == "edited")
        try store.beginSend(clientMessageId:editId,fallbackText:"edited")
        let tooLate = try store.updateTextBeforeSend(clientMessageId:editId,
            content:MessageContent(kind:"text",text:"too late"))
        precondition(tooLate == false)
        let intent = try store.setPendingEdit(clientMessageId:editId,
            content:MessageContent(kind:"text",text:"newer"),revision:1)
        precondition(intent)
        let frozen = try store.message(byClientMessageId:editId)
        precondition(frozen?.content.text == "newer" && frozen?.payloadText == "edited" &&
            frozen?.pendingEditText == "newer" && frozen?.sendAttempted == true)
        var sent = frozen!
        sent.serverId = id(32); sent.sequence = 9; sent.status = .sent
        let editRow = DecryptedChatMessage(clientMessageId:id(33),serverId:nil,chatId:id(16),senderId:id(2),
            sequence:nil,createdAt:Date(),kind:"edit",targetMessageId:id(32),status:.pending,
            content:MessageContent(kind:"text",text:"newer"))
        try store.upsertSentWithPendingEdit(sent,editRow:editRow)
        let committed = try store.message(byClientMessageId:editId)
        precondition(committed?.pendingEditText == nil && committed?.payloadText == "edited" &&
            committed?.serverId == id(32))
        let queuedEdit = try store.message(byClientMessageId:id(33))
        precondition(queuedEdit?.targetMessageId == id(32))

        // Bounded outbox read: 600 rows are decoded in pages, never all at once.
        let pageStore = try MessageStore(databaseURL:directory.appendingPathComponent("outbox-paging.sqlite3"))
        for i in 0..<600 {
            try pageStore.upsert(DecryptedChatMessage(clientMessageId:id(1000 + i),serverId:nil,
                chatId:id(40 + (i % 3)),senderId:id(2),sequence:nil,
                createdAt:Date(timeIntervalSince1970:Double(i)),kind:"content",
                targetMessageId:nil,status:.pending,
                content:MessageContent(kind:"text",text:"p\(i)")))
        }
        let page1 = try pageStore.pendingMessages(limit:256)
        precondition(page1.messages.count == 256 && page1.hasMore && page1.next != nil)
        let page2 = try pageStore.pendingMessages(limit:256, after:page1.next)
        precondition(page2.messages.count == 256 && page2.hasMore && page2.next != nil)
        let page3 = try pageStore.pendingMessages(limit:256, after:page2.next)
        precondition(page3.messages.count == 88 && page3.hasMore == false)
        let all = (page1.messages + page2.messages + page3.messages).map(\.clientMessageId)
        precondition(Set(all).count == 600, "paging has no duplicates or gaps")
        precondition(page1.messages.map(\.clientMessageId) == Array(0..<256).map { id(1000 + $0) },
                     "paging preserves oldest-first order")
        print("Native SQLite history restore contracts passed")
        }
    }
}
