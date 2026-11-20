import Foundation

@main
struct WorkspaceContracts {
    final class RecentCopyGate: @unchecked Sendable {
        let gate = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var began = false
        private var ended = false
        var started: Bool { lock.lock(); defer { lock.unlock() }; return began }
        var finished: Bool { lock.lock(); defer { lock.unlock() }; return ended }
        func begin() { lock.lock(); began = true; lock.unlock() }
        func finish() { lock.lock(); ended = true; lock.unlock() }
    }

    @MainActor static func verifyRecentCopyOwnership(in root: URL) async throws {
        let fm = FileManager.default
        let directory = root.appendingPathComponent("recent-copy-ownership", isDirectory: true)
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        for cancelled in [false, true] {
            let source = store.draftFileDestination(URL(fileURLWithPath: "pasted-original.mov"))
            try fm.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([1, 2, 3]).write(to: source)
            let draft = String(decoding: try JSONEncoder().encode(["url": source]), as: UTF8.self)
            try store.saveChatValue(chatId: "removed-chat", name: "fileDraft", value: draft)
            let gate = RecentCopyGate()
            var reference: String?
            let job = Task { @MainActor in
                try await ChatComposerRecentFileCopy.perform(on: DispatchQueue(label: "workspace.recent-copy"), acquire: {
                    reference = try store.beginRecentFileCopy(sources: [source])
                }, release: {
                    precondition(gate.finished, "Reference retired before IO acknowledged completion/cancellation")
                    try! store.finishRecentFileCopy(name: reference!)
                    try! store.removeUnownedDraftSource(source, attachmentReferenced: false)
                }, copy: { cancellation in
                    precondition(!Thread.isMainThread)
                    gate.begin(); gate.gate.wait()
                    defer { gate.finish() }
                    try cancellation.check()
                    let bytes = try Data(contentsOf: source)
                    precondition(bytes == Data([1, 2, 3]), "Source retired while the copy held its reference")
                })
            }
            while !gate.started { await Task.yield() }
            if cancelled { job.cancel() }
            // Simulate tray removal, pending-batch cancellation and conversation purge.
            // The copy's independent reference must remain the sole live owner.
            _ = try store.purgeDeletedChat("removed-chat")
            try store.removeUnownedDraftSource(source, attachmentReferenced: false)
            precondition(fm.fileExists(atPath: source.path), "Retirement raced a recent-file read")
            let held = try store.workspaceReferences(source)
            precondition(held, "Recent-file reader has no production ownership reference")
            gate.gate.signal()
            if cancelled {
                do { try await job.value; preconditionFailure("Cancellation ignored") } catch is CancellationError {}
            } else { try await job.value }
            precondition(!fm.fileExists(atPath: source.path), "Completed/cancelled recent-file copy leaked an unowned source")
        }
        let abandoned = store.draftFileDestination(URL(fileURLWithPath: "interrupted-original.gif"))
        try fm.createDirectory(at: abandoned.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([8]).write(to: abandoned)
        let stale = try store.beginRecentFileCopy(sources: [abandoned])
        let reopened = try MessageStore(databaseURL: database)
        try await reopened.startDraftSourceRecovery(attachmentReferences: { [] }).value
        precondition(!fm.fileExists(atPath: abandoned.path), "A prior-process recent-copy reference prevented orphan recovery")
        let retired = try reopened.chatValue(chatId: "__recent_file_copies__", name: stale)
        precondition(retired == nil, "A prior-process reader reference survived restart")
        let overlapping = reopened.draftFileDestination(URL(fileURLWithPath: "shared-original.mov"))
        try fm.createDirectory(at: overlapping.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([9]).write(to: overlapping)
        let first = try reopened.beginRecentFileCopy(sources: [overlapping])
        let second = try reopened.beginRecentFileCopy(sources: [overlapping])
        try reopened.finishRecentFileCopy(name: first)
        try reopened.removeUnownedDraftSource(overlapping, attachmentReferenced: false)
        precondition(fm.fileExists(atPath: overlapping.path), "An older copy released a newer reader's ownership")
        try reopened.finishRecentFileCopy(name: second)
        try reopened.removeUnownedDraftSource(overlapping, attachmentReferenced: false)
        precondition(!fm.fileExists(atPath: overlapping.path), "The last reader did not release its source")
        print("PASS: production recent-copy references protect source through IO completion/cancellation and chat purge; restart retires abandoned readers")
    }

    static func verifyDraftRecovery(in root: URL) async throws {
        let directory = root.appendingPathComponent("launch-recovery")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
        func staged(_ name: String) throws -> URL {
            let url = store.draftFileDestination(URL(fileURLWithPath: name))
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([1, 2, 3]).write(to: url)
            return url
        }
        func encoded<T: Encodable>(_ value: T) throws -> String { String(decoding: try JSONEncoder().encode(value), as: UTF8.self) }
        func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }
        func oldContainer(_ url: URL) -> URL {
            URL(fileURLWithPath: "/old-container/draft-media")
                .appendingPathComponent(url.deletingLastPathComponent().lastPathComponent)
                .appendingPathComponent(url.lastPathComponent)
        }
        let journaled = try staged("never-reopened.pdf"), deleted = try staged("deleted-chat.pdf")
        let interrupted = try staged("crash-before-journal.pdf"), live = try staged("live-tray.pdf")
        let draft = try staged("draft.jpg"), upload = try staged("upload.bin"), voice = try staged("voice.m4a")
        let cached = try staged("cached.jpg"), moved = try staged("space % unicode-ї.bin")
        func shared(_ name: String) throws -> URL {
            let url = directory.appendingPathComponent("draft-media/share-" + UUID().uuidString.lowercased())
                .appendingPathComponent(name)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([4, 5, 6]).write(to: url)
            return url
        }
        let sharedUpload = try shared("shared-source.heic"), sharedBeforeAdmission = try shared("unadmitted.mov")
        let pending = try staged("pending.mov"), prepared = try staged("prepared.mp4"), accepted = try staged("accepted.mov")
        try store.saveChatValue(chatId: "never-reopened", name: MessageStore.trayFilesKey, value: encoded([oldContainer(journaled).absoluteString]))
        try store.saveChatValue(chatId: "deleted", name: MessageStore.trayFilesKey, value: encoded([store.draftSourceRelativePath(deleted)!]))
        let deletedFiles = try store.purgeDeletedChat("deleted")
        precondition(deletedFiles.contains(deleted), "Deleting an unopened chat must collect its relative tray journal")
        // Simulate termination after the row was deleted but before file cleanup.
        try store.saveChatValue(chatId: "durable", name: "mediaDrafts", value: encoded([draft]))
        try store.saveChatValue(chatId: "durable", name: "upload:retry", value: encoded(["url": upload]))
        try store.saveChatValue(chatId: "durable", name: "voiceDraft", value: encoded(["path": voice.path]))
        try store.saveChatValue(chatId: "moved", name: "fileDraft", value: encoded(["url": oldContainer(moved)]))
        try store.saveChatValue(chatId: "shared", name: "upload:shared", value: encoded(["url": oldContainer(sharedUpload)]))
        let pendingID = UUID().uuidString, acceptedID = UUID().uuidString
        let batch: [String: Any] = ["version": 1, "acceptedIDs": [acceptedID], "members": [
            ["id": pendingID, "source": ["owned": true, "url": oldContainer(pending).absoluteString], "prepared": ["url": prepared.absoluteString]],
            ["id": acceptedID, "source": ["owned": true, "url": accepted.absoluteString]]
        ]]
        try store.saveChatValue(chatId: "batch", name: "composerMediaBatchV1",
            value: String(decoding: JSONSerialization.data(withJSONObject: batch), as: UTF8.self))
        try attachments.upsert(AttachmentRecord(attachmentId: "cached", clientMessageId: "cached", chatId: "durable",
            mediaType: "image", mimeType: "image/jpeg", filename: nil, size: 3, width: nil, height: nil,
            durationMs: nil, thumbnailBase64: nil, localPath: oldContainer(cached).path, downloadState: "downloaded",
            uploadState: "uploaded", progress: 100, objectKey: "cached", encryptedSize: 3, encryptionVersion: 1,
            keyBase64: nil, nonceBase64: nil, keysetBase64: nil, sha256Base64: "hash"))
        let snapshot = attachments.draftSourceReferences()
        let outside = directory.appendingPathComponent("outside.bin")
        try Data([7]).write(to: outside)
        #if !os(Windows)
        try FileManager.default.createSymbolicLink(at: directory.appendingPathComponent("draft-media/outside-link"), withDestinationURL: directory)
        #endif
        // Cross several bounded enumeration turns, including copies with no journal.
        var extra: [URL] = []
        for index in 0..<260 { extra.append(try staged("orphan-\(index).bin")) }
        let snapshotReady = AsyncStream<Void>.makeStream()
        let snapshotGate = DispatchSemaphore(value: 0)
        let recovery = store.startDraftSourceRecovery {
            precondition(!Thread.isMainThread, "Attachment ownership must be read off the main thread")
            snapshotReady.continuation.yield(()); snapshotGate.wait()
            return try await snapshot()
        }
        for await _ in snapshotReady.stream { break }
        // The workspace snapshot is already taken; a camera draft now moves to a
        // live tray before its coalesced journal write. It must survive this pass.
        store.protectDraftSourcesDuringRecovery([live])
        snapshotGate.signal()
        try await recovery.value
        precondition(exists(outside), "Recovery escaped staging or followed a symbolic link")
        for url in [journaled, deleted, interrupted, accepted, sharedBeforeAdmission] + extra {
            precondition(!exists(url), "Launch leaked an unowned staged copy: \(url.lastPathComponent)")
        }
        for url in [live, draft, upload, voice, cached, moved, pending, prepared, sharedUpload] {
            precondition(exists(url), "Launch deleted an owned source: \(url.lastPathComponent)")
        }
        let expired = try store.chatValue(chatId: "never-reopened", name: MessageStore.trayFilesKey)
        precondition(expired == nil, "Previous-process tray journal was not retired")
        try store.removeUnownedDraftSource(oldContainer(moved), attachmentReferenced: false)
        precondition(exists(moved), "Legacy absolute URL lost its durable ownership")
        try store.saveChatValue(chatId: "moved", name: "fileDraft", value: nil)
        try store.removeUnownedDraftSource(oldContainer(moved), attachmentReferenced: false)
        precondition(!exists(moved), "Legacy cleanup did not rebase to the current account directory")
        try store.removeUnownedDraftSource(oldContainer(sharedUpload), attachmentReferenced: false)
        precondition(exists(sharedUpload), "A moved share upload lost its durable owner during cleanup")
        try store.saveChatValue(chatId: "shared", name: "upload:shared", value: nil)
        try store.removeUnownedDraftSource(oldContainer(sharedUpload), attachmentReferenced: false)
        precondition(!exists(sharedUpload), "Unowned share cleanup did not rebase to the current account directory")
        let reopened = try MessageStore(databaseURL: database)
        try await reopened.startDraftSourceRecovery(attachmentReferences: snapshot).value
        precondition(!exists(live) && exists(pending), "Live protection must expire with its process, preserving durable owners")
        let failed = try staged("corrupt-owner.bin")
        try reopened.saveChatValue(chatId: "corrupt", name: "fileDraft", value: "broken json")
        do {
            try await reopened.startDraftSourceRecovery(attachmentReferences: snapshot).value
            fatalError("Corrupt ownership must fail closed")
        } catch {}
        precondition(exists(failed), "Cleanup removed a file without a complete ownership snapshot")
        try reopened.saveChatValue(chatId: "corrupt", name: "fileDraft", value: nil)
        let entered = AsyncStream<Void>.makeStream()
        let gate = DispatchSemaphore(value: 0)
        let cancelled = reopened.startDraftSourceRecovery {
            entered.continuation.yield(()); gate.wait()
            return try await snapshot()
        }
        for await _ in entered.stream { break }
        cancelled.cancel(); gate.signal()
        do { try await cancelled.value; fatalError("Cancelled recovery completed") } catch is CancellationError {}
        precondition(exists(failed), "Session retirement must cancel recovery before filesystem work")
        print("PASS: production launch sweep: all durable owners, live handoff, abandoned/deleted/unrecorded copies, relocation, bounded turns, fail-closed metadata, cancellation")
    }

    static func verifyPastedOriginalRecovery(in root: URL) async throws {
        let fm = FileManager.default
        let directory = root.appendingPathComponent("pasted-original-recovery", isDirectory: true)
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        for (filename, direct) in [("pasted-animation.gif", true), ("pasted-original.mov", false)] {
            let plan = ChatComposerPastePolicy.filePlan(origin: .provider, original: true, video: filename.hasSuffix("mov"),
                retainExternalDocument: true, ordinaryLimit: 100 * 1024 * 1024, videoLimit: 500 * 1024 * 1024,
                originalLimit: 64 * 1024 * 1024 * 1024)
            precondition(plan.owned, "Pasted originals cannot retain a temporary document as their persisted source")
            let temporary = directory.appendingPathComponent("provider-" + filename)
            try Data([1, 2, 3]).write(to: temporary)
            let durable = store.draftFileDestination(URL(fileURLWithPath: filename))
            try fm.createDirectory(at: durable.deletingLastPathComponent(), withIntermediateDirectories: true)
            do {
                let provider = AttachmentFileCopy.DocumentSource(temporary, cleanup: { try? fm.removeItem(at: temporary) })
                try AttachmentFileCopy.DocumentRead().copy(from: provider.url, to: durable, maxBytes: plan.maximumBytes)
                withExtendedLifetime(provider) {}
            }
            precondition(!fm.fileExists(atPath: temporary.path), "The provider bridge must release its temporary copy")
            let source: [String: Any] = ["owned": plan.owned, "url": durable.absoluteString, "name": filename,
                                         "isVideo": filename.hasSuffix("mov"), "bytes": 3]
            let member: [String: Any] = ["id": UUID().uuidString, "sourceID": UUID().uuidString, "index": 0, "kind": "file", "source": source]
            let intent: [String: Any] = ["mode": "file", "highQuality": false, "caption": "pasted", "albumId": UUID().uuidString]
            let batch: [String: Any] = ["version": 1, "acceptedIDs": [String](), "members": [member],
                "intent": intent,
                "directSend": direct, "autoSend": direct, "directChat": Data("{}".utf8).base64EncodedString()]
            let encoded = String(decoding: try JSONSerialization.data(withJSONObject: batch), as: UTF8.self)
            try store.saveChatValue(chatId: filename, name: "composerMediaBatchV1", value: encoded)
            let journal = String(decoding: try JSONEncoder().encode([store.draftSourceRelativePath(durable)!]), as: UTF8.self)
            try store.saveChatValue(chatId: filename, name: MessageStore.trayFilesKey, value: journal)
            // Account teardown clears the in-memory tray/journal and releases the
            // provider grant, but the persisted pending batch is still authoritative.
            try store.saveChatValue(chatId: filename, name: MessageStore.trayFilesKey, value: nil)
            try store.removeUnownedDraftSource(durable, attachmentReferenced: false)
            precondition(fm.fileExists(atPath: durable.path), "Account teardown deleted a pending pasted original")
            let reopened = try MessageStore(databaseURL: database)
            try await reopened.startDraftSourceRecovery(attachmentReferences: { [] }).value
            precondition(fm.fileExists(atPath: durable.path), "Returning to an account swept a pending GIF/original source")
            let restored = try reopened.chatValue(chatId: filename, name: "composerMediaBatchV1")
            precondition(restored == encoded, "The queued paste batch did not survive account reopen")
            try reopened.saveChatValue(chatId: filename, name: "composerMediaBatchV1", value: nil)
            try reopened.removeUnownedDraftSource(durable, attachmentReferenced: false)
            precondition(!fm.fileExists(atPath: durable.path), "A retired pasted original leaked its owned draft")
        }
        print("PASS: pasted GIF/original provider release, pending batch ownership, account teardown/reopen, recovery and retirement")
    }

    static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try await verifyDraftRecovery(in: root)
        try await verifyPastedOriginalRecovery(in: root)
        try await verifyRecentCopyOwnership(in: root)
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
        precondition(lateAckRow?.content.text == nil && lateAckRow?.content.attachment == nil && lateAckRow?.content.replySnapshot == nil &&
            lateAckRow?.payloadText == nil && lateAckRow?.pendingEditText == nil && lateAckRow?.pendingEditRevision == 0,
            "A late ACK above the clear boundary must preserve complete cancellation erasure")
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
