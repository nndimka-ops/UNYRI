import Foundation

@main
struct WorkspaceContracts {
    static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let database = root.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        // Dispatch a batch and immediately enqueue transactionally. The transaction
        // must drain before taking the SQLite writer lock, and must remain atomic.
        for index in 0..<30 {
            let batch = store.startWorkspaceWrite(["chat": ["draft": "old", "mediaCaption": "caption"]])
            let message = DecryptedChatMessage(clientMessageId: "m\(index)", serverId: nil,
                chatId: "chat", senderId: "me", sequence: nil, createdAt: Date(), kind: "content",
                targetMessageId: nil, status: .pending, content: MessageContent(kind: "text", text: "queued"))
            try store.upsert(message, mediaJob: "{}", scheduledAt: "1234")
            try store.saveChatValue(chatId: "chat", name: "draft", value: "")
            try await batch.value
            let reopened = try MessageStore(databaseURL: database, readOnly: true)
            let value = try reopened.chatValue(chatId: "chat", name: "draft")
            precondition(value == "", "Older batch overwrote explicit send/reset")
            let schedule = try reopened.chatValue(chatId: "chat", name: "schedule:m\(index)")
            precondition(schedule == "1234", "Transactional enqueue lost schedule")
        }
        let directory = root.appendingPathComponent("draft-media/owned")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let source = directory.appendingPathComponent("movie.mov")
        try Data(repeating: 5, count: 1024).write(to: source)
        let destination = root.appendingPathComponent("too-large.mov")
        do {
            try AttachmentFileCopy.copy(from: source, to: destination, maxBytes: 512)
            fatalError("Oversized source accepted")
        } catch is AttachmentFileCopy.CopyError {}
        precondition(!FileManager.default.fileExists(atPath: destination.path))
        let encoded = String(decoding: try JSONEncoder().encode(["url": source]), as: UTF8.self)
        try store.saveChatValue(chatId: "chat", name: "fileDraft", value: encoded)
        try store.removeUnownedDraftSource(source, attachmentReferenced: false)
        precondition(FileManager.default.fileExists(atPath: source.path), "Live draft deleted")
        try store.saveChatValue(chatId: "chat", name: "fileDraft", value: nil)
        try store.saveChatValue(chatId: "chat", name: "upload:retry", value: encoded)
        try store.removeUnownedDraftSource(source, attachmentReferenced: false)
        precondition(FileManager.default.fileExists(atPath: source.path), "Retry source deleted")
        try store.saveChatValue(chatId: "chat", name: "upload:retry", value: nil)
        let voice = String(decoding: try JSONEncoder().encode(["path": source.path]), as: UTF8.self)
        try store.saveChatValue(chatId: "chat", name: "voiceDraft", value: voice)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-172800)], ofItemAtPath: source.path)
        try store.removeUnownedDraftSource(source, attachmentReferenced: false)
        precondition(FileManager.default.fileExists(atPath: source.path), "Old voice draft with a raw path deleted")
        try store.saveChatValue(chatId: "chat", name: "voiceDraft", value: nil)
        try store.removeUnownedDraftSource(source, attachmentReferenced: true)
        precondition(FileManager.default.fileExists(atPath: source.path), "Attachment source deleted")
        try store.saveChatValue(chatId: "chat", name: "fileDraft", value: "broken json")
        do {
            try store.removeUnownedDraftSource(source, attachmentReferenced: false)
            fatalError("Invalid ownership metadata did not fail closed")
        } catch {}
        precondition(FileManager.default.fileExists(atPath: source.path))
        try store.saveChatValue(chatId: "chat", name: "fileDraft", value: nil)
        try store.removeUnownedDraftSource(source, attachmentReferenced: false)
        precondition(!FileManager.default.fileExists(atPath: directory.path), "Unowned source/directory leaked")
        let outside = root.appendingPathComponent("outside.mov")
        try Data([1]).write(to: outside)
        try store.removeUnownedDraftSource(outside, attachmentReferenced: false)
        precondition(FileManager.default.fileExists(atPath: outside.path), "Cleanup escaped draft root")
        let deletedChat = "deleted-chat"
        let late = DecryptedChatMessage(clientMessageId: "late", serverId: nil, chatId: deletedChat,
            senderId: "me", sequence: nil, createdAt: Date(), kind: "content", targetMessageId: nil,
            status: .pending, content: MessageContent(kind: "text", text: "must not return"))
        try store.upsert(late, mediaJob: "{}")
        try store.markChatDeleted(deletedChat)
        _ = try store.purgeDeletedChat(deletedChat)
        try store.upsert(late)
        let lateDraft = store.startWorkspaceWrite([deletedChat: ["draft": "late draft"]])
        try await lateDraft.value
        let deletedMessages = try store.messages(forChatId: deletedChat)
        let deletedDraft = try store.chatValue(chatId: deletedChat, name: "draft")
        precondition(deletedMessages.isEmpty && deletedDraft == nil, "Tombstone permitted delayed message/draft resurrection")
        let surviving = try store.messages(forChatId: "chat")
        precondition(surviving.count == 30, "Chat deletion touched another chat")
        let owner = UUID().uuidString.lowercased(), otherOwner = UUID().uuidString.lowercased()
        let callChat = UUID().uuidString.lowercased(), peer = UUID().uuidString.lowercased()
        let firstCall = UUID().uuidString.lowercased(), secondCall = UUID().uuidString.lowercased()
        func project(_ id: String, at: Double, unread: Bool = true, visible: Bool = true) throws {
            try store.projectCallActivity(CallActivityProjection(callId: id, chatId: callChat, peerId: peer,
                createdAt: Date(timeIntervalSince1970: at), mediaKind: "video", outcome: "ended:missed",
                visible: visible, unread: unread), owner: owner)
        }
        try project(firstCall, at: 100)
        let unchanged = try store.projectCallActivity(CallActivityProjection(callId: firstCall, chatId: callChat, peerId: peer,
            createdAt: Date(timeIntervalSince1970: 100), mediaKind: "video", outcome: "ended:missed", visible: true, unread: true), owner: owner)
        precondition(!unchanged, "Identical projection must not dirty the chat list")
        let visible = try store.visibleChatIDs(historyCutoff: 999, ownerId: owner)
        let foreign = try store.visibleChatIDs(historyCutoff: 999, ownerId: otherOwner)
        precondition(visible.contains(callChat) && !foreign.contains(callChat), "Call-only admission leaked across owners")
        let counts = try store.unreadCounts(ownerId: owner, historyCutoff: 999)
        precondition(counts[callChat] == 1, "Missed call must count without a fake message")
        try store.markCallsViewed(owner: owner, ids: [firstCall])
        try project(firstCall, at: 100) // stale server true cannot undo local read
        let read = try store.callUnread(owner: owner, id: firstCall)
        let pending = try store.pendingCallReads(owner: owner, since: .distantPast)
        precondition(!read && pending == [firstCall], "Read acknowledgement must survive stale refresh until POST succeeds")
        let reopenedCalls = try MessageStore(databaseURL: database)
        let durable = try reopenedCalls.pendingCallReads(owner: owner, since: .distantPast)
        precondition(durable == [firstCall], "Offline read queue must survive process restart")
        try store.completeCallRead(owner: owner, id: firstCall)
        try project(firstCall, at: 100)
        let afterAck = try store.callUnread(owner: owner, id: firstCall)
        precondition(!afterAck, "Delayed server unread must not resurrect acknowledged badge")
        let firstClear = try store.clearCallActivity(chat: callChat, before: Date(timeIntervalSince1970: 100))
        let repeatedClear = try store.clearCallActivity(chat: callChat, before: Date(timeIntervalSince1970: 100))
        precondition(firstClear && !repeatedClear, "Repeated authoritative cutoff must not redraw the chat list")
        try project(firstCall, at: 100)
        let clearedVisible = try store.callVisible(owner: owner, id: firstCall)
        precondition(!clearedVisible, "Cached page outside refresh cannot bypass durable personal cutoff")
        try project(secondCall, at: 101)
        let newVisible = try store.callVisible(owner: owner, id: secondCall)
        precondition(newVisible, "Fresh call after personal clear must admit canonical chat")
        try store.markChatDeleted(callChat)
        try project(secondCall, at: 101)
        let deletedVisible = try store.callVisible(owner: owner, id: secondCall)
        precondition(!deletedVisible, "Late call must not revive physically deleted chat")
        let deletionId = UUID().uuidString.lowercased(), server = "https://fixture.invalid"
        try store.projectCallActivity(CallActivityProjection(callId: deletionId, chatId: UUID().uuidString.lowercased(), peerId: peer,
            createdAt: Date(timeIntervalSince1970: 200), mediaKind: "audio", outcome: "ended:missed", visible: true, unread: true), owner: owner)
        try store.enqueueCallDeletion(owner: owner, server: server, id: deletionId, everyone: false)
        let deletionVisible = try store.callVisible(owner: owner, id: deletionId)
        let deletionQueue = try store.pendingCallDeletions(owner: owner, server: server)
        precondition(!deletionVisible && deletionQueue == [PendingCallDeletion(callId: deletionId, everyone: false)], "Deletion intent and local hide must commit together")
        let restartedDeletes = try MessageStore(databaseURL: database)
        let restartQueue = try restartedDeletes.pendingCallDeletions(owner: owner, server: server)
        precondition(restartQueue == deletionQueue, "Offline deletion must survive restart")
        let foreignOwnerQueue = try store.pendingCallDeletions(owner: otherOwner, server: server)
        let foreignServerQueue = try store.pendingCallDeletions(owner: owner, server: "https://other.invalid")
        precondition(foreignOwnerQueue.isEmpty && foreignServerQueue.isEmpty, "Deletion commands must not cross owner or server")
        try store.enqueueCallDeletion(owner: owner, server: server, id: deletionId, everyone: false)
        let duplicateQueue = try store.pendingCallDeletions(owner: owner, server: server)
        precondition(duplicateQueue == deletionQueue, "Duplicate deletion must retain one stable call ID")
        try store.enqueueCallDeletion(owner: owner, server: server, id: deletionId, everyone: true)
        try store.completeCallDeletion(owner: owner, server: server, id: deletionId, everyone: false)
        let upgradedQueue = try store.pendingCallDeletions(owner: owner, server: server)
        precondition(upgradedQueue == [PendingCallDeletion(callId: deletionId, everyone: true)], "Late for-me response must not acknowledge newer for-everyone intent")
        try store.completeCallDeletion(owner: owner, server: server, id: deletionId, everyone: true)
        let completedQueue = try store.pendingCallDeletions(owner: owner, server: server)
        let deletionTombstones = try store.callDeletionIDs(owner: owner, server: server)
        precondition(completedQueue.isEmpty && deletionTombstones.contains(deletionId), "Completion removes pending command but retains replay fence")
        try store.enqueueCallDeletion(owner: owner, server: server, id: deletionId, everyone: true)
        let completedDuplicate = try store.pendingCallDeletions(owner: owner, server: server)
        precondition(completedDuplicate.isEmpty, "Acknowledged duplicate must not enqueue another HTTP operation")
        try store.projectCallActivity(CallActivityProjection(callId: deletionId, chatId: UUID().uuidString.lowercased(), peerId: peer,
            createdAt: Date(timeIntervalSince1970: 200), mediaKind: "audio", outcome: "ended:missed", visible: true, unread: true), owner: owner)
        let lateDeletionVisible = try store.callVisible(owner: owner, id: deletionId)
        let lateDeletionUnread = try store.callUnread(owner: owner, id: deletionId)
        precondition(!lateDeletionVisible && !lateDeletionUnread, "Late cached projection must not resurrect deleted call or unread")
        let boundedChat = UUID().uuidString.lowercased(), boundedRequest = UUID().uuidString.lowercased()
        func boundedMessage(_ id: String, sequence: Int64?) -> DecryptedChatMessage {
            DecryptedChatMessage(clientMessageId:id,serverId:sequence == nil ? nil : UUID().uuidString.lowercased(),
                chatId:boundedChat,senderId:owner,sequence:sequence,createdAt:Date(timeIntervalSince1970:10),kind:"content",
                targetMessageId:nil,status:sequence == nil ? .pending : .sent,content:MessageContent(kind:"text",text:id))
        }
        try store.upsert(boundedMessage("bounded-old",sequence:10))
        try store.upsert(boundedMessage("bounded-unsent",sequence:nil))
        try store.saveChatValue(chatId:boundedChat,name:"draft",value:"old draft")
        let endedId = UUID().uuidString.lowercased(), ringingId = UUID().uuidString.lowercased()
        try store.projectCallActivity(CallActivityProjection(callId:endedId,chatId:boundedChat,peerId:peer,createdAt:Date(timeIntervalSince1970:100.123),
            mediaKind:"audio",outcome:"ended:missed",visible:true,unread:true),owner:owner)
        try store.projectCallActivity(CallActivityProjection(callId:ringingId,chatId:boundedChat,peerId:peer,createdAt:Date(timeIntervalSince1970:200),
            mediaKind:"audio",outcome:"ringing",visible:true,unread:false),owner:owner)
        let bounded = try store.captureChatClear(owner:owner,chat:boundedChat,requestId:boundedRequest,everyone:true,minimumSequence:12)
        precondition(bounded.throughSequence == 12 && bounded.callsBefore! < Date(timeIntervalSince1970:101), "Boundary must use known sequence/ended calls, excluding ringing and phone clock")
        precondition(bounded.callsBeforeISO!.contains(".124000Z"), "Wire boundary must include parsed millisecond call with fractional precision guard")
        let cancelled = try store.enqueueChatClear(owner:owner,server:server,command:bounded)
        precondition(cancelled == ["bounded-unsent"], "Only unsents present at local commit are cancelled")
        let endedTombstones = try store.callDeletionIDs(owner:owner,server:server)
        precondition(endedTombstones.contains(endedId) && !endedTombstones.contains(ringingId), "Bounded clear must hide ended Calls-tab history without deleting active calls")
        let reopenedBounded = try MessageStore(databaseURL:database)
        let restartedBounded = try reopenedBounded.chatClearCommand(owner:owner,server:server,requestId:boundedRequest)
        precondition(restartedBounded == bounded, "Immutable bounded payload must survive restart")
        // The POST may have left the process before clear was accepted locally.
        // Even a late server acknowledgement above the frozen boundary must
        // preserve the captured unsent's local cancellation fence.
        try store.upsert(boundedMessage("bounded-unsent",sequence:14))
        let lateAckRow = try store.message(byClientMessageId:"bounded-unsent")
        let beforeFutureRows = try store.messages(forChatId:boundedChat)
        let beforeFuturePreview = try store.latestPreviewByChat()[boundedChat]
        precondition(lateAckRow?.sequence == 14 && lateAckRow?.hiddenLocally == true && beforeFutureRows.isEmpty && beforeFuturePreview == nil,
            "Already-dispatched POST acknowledgement above bound must not resurrect captured pending message or preview")
        try store.upsert(boundedMessage("bounded-future",sequence:15))
        try store.upsert(boundedMessage("bounded-new-unsent",sequence:nil))
        try store.saveChatValue(chatId:boundedChat,name:"draft",value:"new draft")
        _ = try store.enqueueChatClear(owner:owner,server:server,command:bounded)
        let retained = try store.messages(forChatId:boundedChat).map(\.clientMessageId)
        let newDraft = try store.chatValue(chatId:boundedChat,name:"draft")
        precondition(retained.contains("bounded-future") && retained.contains("bounded-new-unsent") && newDraft == "new draft", "Retry must preserve messages, unsents and drafts newer than frozen intent")
        do {
            _ = try store.enqueueChatClear(owner:owner,server:server,command:ChatClearCommand(requestId:boundedRequest,chatId:boundedChat,forEveryone:false,throughSequence:12,callsBefore:bounded.callsBefore))
            fatalError("Request ID mode collision accepted")
        } catch {}
        do {
            _ = try store.enqueueChatClear(owner:owner,server:server,command:ChatClearCommand(requestId:boundedRequest,chatId:boundedChat,forEveryone:true,throughSequence:13,callsBefore:bounded.callsBefore))
            fatalError("Request ID bound collision accepted")
        } catch {}
        let boundedForeign = try store.pendingChatClears(owner:otherOwner,server:server)
        let boundedOtherServer = try store.pendingChatClears(owner:owner,server:"https://other.invalid")
        precondition(boundedForeign.isEmpty && boundedOtherServer.isEmpty, "Bounded replay must remain owner/server scoped")
        print("Workspace contracts passed: overlapping batch/enqueue, explicit ordering, reopen, ownership, bounded import")
    }
}
