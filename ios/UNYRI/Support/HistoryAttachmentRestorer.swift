import Foundation

/// Crash recovery between the message DB commit and attachment metadata DB commit.
/// This restores metadata only: backing up/downloading the encrypted media bytes
/// remains the archive transport coordinator's responsibility.
enum HistoryAttachmentRestorer {
    static func resume(messages: MessageStore, attachments: AttachmentStore,
                       applyDeletedChat: (String) throws -> Void,
                       removeCachedFile: (String) throws -> Void,
                       assertCurrent: () throws -> Void) throws {
        for job in try messages.historyCleanupJobs() {
            try assertCurrent()
            if let old = try attachments.attachment(byClientMessageId:job.client), let path = old.localPath { try removeCachedFile(path) }
            try attachments.deleteRecords([job.client])
            try messages.finishHistoryRestoreJob(chat:job.chat,name:"restore_cleanup:" + job.client,payload:job.payload)
        }
        for job in try messages.historyDeletedChatJobs() {
            try assertCurrent()
            for row in try attachments.attachments(chatId:job.client) {
                if let path = row.localPath { try removeCachedFile(path) }
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
            let cached = same && old?.localPath.map { FileManager.default.fileExists(atPath:$0) } == true
            let record = AttachmentRecord(attachmentId:a.attachmentId,clientMessageId:job.client,chatId:job.chat,
                mediaType:a.mediaType,mimeType:a.mimeType,filename:a.filename,size:a.size,width:a.image?.width,height:a.image?.height,
                durationMs:a.video?.durationMs ?? a.voice?.durationMs,thumbnailBase64:a.thumbnail,
                localPath:cached ? old?.localPath : nil,downloadState:cached ? (old?.downloadState ?? "not_downloaded") : "not_downloaded",
                uploadState:"uploaded",progress:cached ? (old?.progress ?? 0) : 0,objectKey:a.objectKey,encryptedSize:a.encryptedSize,encryptionVersion:a.encryption.version,
                keyBase64:a.encryption.key,nonceBase64:a.encryption.nonce,keysetBase64:a.encryption.keyset,sha256Base64:a.encryption.sha256)
            try attachments.upsert(record)
            // An attachment-store tombstone can reject a write. Do not consume
            // the journal or announce success if no matching row was persisted.
            guard try attachments.attachment(byId:a.attachmentId) == record else { throw HistoryArchive.Failure.conflict }
            try assertCurrent()
            try messages.finishHistoryAttachmentJob(chat:job.chat,client:job.client,payload:job.payload)
        }
    }
}
