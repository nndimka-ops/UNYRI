import Foundation

/// Crash recovery between the message DB commit and attachment metadata DB commit.
/// This restores metadata only: backing up/downloading the encrypted media bytes
/// remains the archive transport coordinator's responsibility.
enum HistoryAttachmentRestorer {
    static func resume(messages: MessageStore, attachments: AttachmentStore,
                       applyDeletedChat: (String) throws -> Void,
                       removeCachedFile: (String) throws -> Void,
                       removeTranscript: (String) throws -> Void = { _ in },
                       assertCurrent: () throws -> Void) throws {
        func removeUpload(_ id: String) throws {
            let root = attachments.databaseDirectory.appendingPathComponent("attachments")
            let encoded = Data(id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "=", with: "")
            try removeCachedFile(root.appendingPathComponent("upload-v2-" + encoded + ".enc").path)
            if !id.isEmpty, id.utf8.count <= 128, id.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95 }) {
                try removeCachedFile(root.appendingPathComponent("upload-" + id + ".enc").path)
            }
        }
        for job in try messages.historyCleanupJobs() {
            try assertCurrent()
            if let old = try attachments.attachment(byClientMessageId:job.client) {
                if let path = old.localPath { try removeCachedFile(path) }
                try removeUpload(old.attachmentId)
                try removeTranscript(old.attachmentId)
            }
            try attachments.deleteRecords([job.client], chatId: job.chat)
            try messages.finishHistoryRestoreJob(chat:job.chat,name:"restore_cleanup:" + job.client,payload:job.payload)
            try attachments.reclaimConsumedFence(job.client, chatId: job.chat)
        }
        for job in try messages.historyDeletedChatJobs() {
            try assertCurrent()
            for row in try attachments.attachments(chatId:job.client) {
                if let path = row.localPath { try removeCachedFile(path) }
                try removeUpload(row.attachmentId)
                try removeTranscript(row.attachmentId)
            }
            try applyDeletedChat(job.client)
            try attachments.deleteChat(job.client)
            try messages.finishHistoryRestoreJob(chat:job.chat,name:"restore_deleted_chat:" + job.client,payload:job.payload)
        }
        for job in try messages.historyAttachmentJobs() {
            try assertCurrent()
            guard let message = try messages.message(byClientMessageId: job.client),
                  message.chatId == job.chat, !message.hiddenLocally, !message.isDeletedForEveryone,
                  try !messages.isChatDeleted(job.chat), let a = message.content.attachment else {
                try messages.finishHistoryAttachmentJob(chat:job.chat,client:job.client,payload:job.payload)
                try attachments.reclaimConsumedFence(job.client, chatId: job.chat)
                continue
            }
            if let sequence = message.sequence,
               try !messages.attachmentMessageIsVisible(chatId: job.chat, clientMessageId: job.client, attachmentId: a.attachmentId, sequence: sequence) {
                try attachments.deleteRecords([job.client], chatId: job.chat)
                try messages.finishHistoryAttachmentJob(chat: job.chat, client: job.client, payload: job.payload)
                try attachments.reclaimConsumedFence(job.client, chatId: job.chat)
                continue
            }
            // Use the current message projection, not an older journal payload.
            let old = try attachments.attachment(byId:a.attachmentId)
            if let old {
                guard old.clientMessageId == job.client, old.chatId == job.chat else { throw HistoryArchive.Failure.conflict }
            }
            let same = old.map {
                $0.objectKey == a.objectKey && $0.encryptedSize == a.encryptedSize && $0.sha256Base64 == a.encryption.sha256 &&
                $0.encryptionVersion == a.encryption.version && $0.keyBase64 == a.encryption.key &&
                $0.nonceBase64 == a.encryption.nonce && $0.keysetBase64 == a.encryption.keyset
            } ?? false
            // Journal replay stays synchronous and never waits for a full draft
            // pass. Repair this one known identity before deciding to discard its
            // local reference, including a voice original in a moved container.
            let localPath = old?.localPath.map { attachments.normalizedDraftPath($0) }
            let cached = same && localPath.map { FileManager.default.fileExists(atPath:$0) } == true
            let record = AttachmentRecord(attachmentId:a.attachmentId,clientMessageId:job.client,chatId:job.chat,
                mediaType:a.mediaType,mimeType:a.mimeType,filename:a.filename,size:a.size,width:a.image?.width,height:a.image?.height,
                durationMs:a.video?.durationMs ?? a.voice?.durationMs,thumbnailBase64:a.thumbnail,
                localPath:cached ? localPath : nil,downloadState:cached ? (old?.downloadState ?? "not_downloaded") : "not_downloaded",
                uploadState:"uploaded",progress:cached ? (old?.progress ?? 0) : 0,objectKey:a.objectKey,encryptedSize:a.encryptedSize,encryptionVersion:a.encryption.version,
                keyBase64:a.encryption.key,nonceBase64:a.encryption.nonce,keysetBase64:a.encryption.keyset,sha256Base64:a.encryption.sha256)
            try attachments.upsert(record, sourceSequence: message.sequence, requiresJournal: true)
            // An attachment-store tombstone can reject a write. Do not consume
            // the journal or announce success if no matching row was persisted.
            guard try attachments.attachment(byId:a.attachmentId) == record else { throw HistoryArchive.Failure.conflict }
            try assertCurrent()
            try messages.finishHistoryAttachmentJob(chat:job.chat,client:job.client,payload:job.payload)
            try attachments.reclaimConsumedFence(job.client, chatId: job.chat)
        }
    }
}
