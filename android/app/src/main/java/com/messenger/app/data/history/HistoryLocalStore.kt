package com.messenger.app.data.history

import androidx.room.withTransaction
import com.messenger.app.data.attachment.*
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.ReactionEntity
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import com.messenger.app.data.directtransfer.*

/** Native transactional adapter. Caller must authenticate the encrypted archive
 * and verify its media package BEFORE import. This does not claim server-side
 * history pagination is complete or that attachment bytes have been backed up.
 */
class HistoryLocalStore(private val db: MessengerDatabase, private val auth: AuthRepository) {
    private val json = Json { ignoreUnknownKeys = false }
    private val readable = setOf("sent", "delivered", "read")

    private fun checkOwner(owner: String, epoch: Int) {
        check(auth.currentSessionEpoch() == epoch &&
            (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == owner) { "History account changed" }
    }

    suspend fun export(server: String, owner: String, snapshot: String, epoch: Int): HistoryArchive =
        auth.withSessionWrite(epoch) {
            checkOwner(owner, epoch)
            db.withTransaction {
                if (db.messageDao().historyImportBlocked()) throw HistorySourceNotReady()
                val archive = read(server, owner, snapshot, false)
                currentCoroutineContext().ensureActive(); checkOwner(owner, epoch)
                archive
            }
        } ?: error("History account changed")

    suspend fun import(archive: HistoryArchive, expectedServer: String, owner: String, epoch: Int, verifiedFiles: Map<String, java.io.File> = emptyMap(), beforeCommit: suspend () -> Unit = {}): Set<String> {
        archive.validate(expectedServer, owner)
        // Validate every payload before any writes. No generic codec fallback to text.
        archive.messages.forEach { decodeContent(it, owner) }
        return auth.withSessionWrite(epoch) {
            checkOwner(owner, epoch)
            db.withTransaction {
                // Unrelated ordinary outbox rows are not part of the portable
                // snapshot and remain untouched. Pending lifecycle operations
                // must settle before applying an older final-state projection.
                if (db.messageDao().historyRestoreBlocked()) throw HistorySourceNotReady()
                val local = read(expectedServer, owner, archive.snapshot, true)
                val merged = HistoryArchiveMerge.merge(local, archive)
                val touched = (local.messages + merged.messages).map { it.chat }.toMutableSet()
                touched.addAll(merged.deletedChats); touched.addAll(merged.chatClears.keys)
                for (chat in archive.chats.orEmpty()) {
                    if (chat.id in merged.deletedChats || db.chatDao().cachedChat(chat.id) != null) continue
                    db.chatDao().upsertChatMetadata(chat.id, chat.type, Instant.ofEpochMilli(chat.createdAtMs).toString(), null)
                    chat.peer?.let { peer ->
                        if (db.chatDao().cachedParticipant(peer.id) == null) db.chatDao().upsertParticipant(com.messenger.app.data.local.chat.ChatParticipantEntity(peer.id, peer.username, peer.displayName, null))
                        db.chatDao().upsertMember(com.messenger.app.data.local.chat.ChatMemberEntity(chat.id, peer.id, "member"))
                    }
                }
                // Restore deletions through existing cleanup/trigger paths first.
                for ((chat, cutoff) in merged.chatClears) db.deletedChatDao().clearPersonal(chat, cutoff)
                db.messageDao().applyHistoryClear(merged.clearedThrough)
                for (chat in merged.deletedChats) db.deletedChatDao().tombstone(chat)
                for (row in merged.messages) {
                    currentCoroutineContext().ensureActive()
                    val old = db.messageDao().findByClientMessageId(row.client)
                    val serverCollision = db.messageDao().findByServerId(row.id)
                    check(serverCollision == null || serverCollision.clientMessageId == row.client)
                    // Unreadable content and lifecycle rows are absent from the
                    // portable projection. REPLACE must not erase their identity
                    // through the unique (chatId, sequence) index.
                    val sequenceCollision = db.messageDao().findByChatSequence(row.chat, row.sequence)
                    check(sequenceCollision == null || sequenceCollision.clientMessageId == row.client)
                    if (old != null) {
                        check(old.serverId == row.id && old.chatId == row.chat && old.senderId == row.sender &&
                            old.sequence == row.sequence && old.kind == "content" && old.targetMessageId == row.replyTo)
                        check(old.status !in setOf("pending", "failed"))
                    }
                    val content = decodeContent(row, owner)
                    val metadata = content?.attachment
                    val text = if (metadata == null) content?.text.orEmpty() else content.text.orEmpty()
                    db.messageDao().upsert(MessageEntity(
                        clientMessageId=row.client, serverId=row.id, chatId=row.chat, senderId=row.sender,
                        text=text, sequence=row.sequence, createdAt=Instant.ofEpochMilli(row.createdAtMs).toString(),
                        status=old?.status?.takeIf { it in readable } ?: "sent", targetMessageId=row.replyTo,
                        replySnapshotJson=content?.replySnapshot?.let { json.encodeToString(it) },
                        isEdited=row.editedThrough > 0, lastEditSequence=row.editedThrough,
                        isDeletedForEveryone=row.deletedThrough > 0, deleteSequence=row.deletedThrough,
                        hiddenLocally=row.hidden, forwarded=content?.forwarded ?: false,
                        caption=if (metadata != null) content.text else null, albumId=content?.albumId,
                        conferenceInviteJson=com.messenger.app.conference.ConferenceInvitation.decode(content?.conferenceInvite)?.stored(),
                    ))
                    content?.directFile?.let { raw ->
                        val offer = requireNotNull(DirectFileOffer.decode(raw))
                        DirectFileHistory.validate(offer, row.client, row.sender, owner)
                        val existing = db.workspaceDao().get(row.chat, DirectTransferCoordinator.key(offer.transferId))
                            ?.let(DirectTransferCoordinator::decodeRecord)
                        check(existing == null || existing.chatId == row.chat && existing.offer == offer)
                        val key = DirectFileHistory.key(row.client)
                        val old = db.workspaceDao().get(row.chat, key)?.let(DirectFileOffer::decodeStored)
                        check(old == null || old == offer)
                        // No consent, authenticated control, path, completion or active record is imported.
                        db.workspaceDao().put(com.messenger.app.data.local.chat.ChatWorkspaceValue(row.chat, key, offer.stored()))
                    }
                    if (metadata != null) {
                        val existing = db.attachmentDao().findByAttachmentId(metadata.attachmentId)
                        check(existing == null || (existing.clientMessageId == row.client && existing.chatId == row.chat))
                        val next = toAttachment(row, metadata)
                        // Never accept a restored local path; retain a local file only
                        // when the complete ciphertext identity still matches.
                        val same = existing?.let { it.objectKey == next.objectKey && it.sha256Base64 == next.sha256Base64 &&
                            it.encryptedSize == next.encryptedSize && it.encryptionVersion == next.encryptionVersion &&
                            it.keyBase64 == next.keyBase64 && it.nonceBase64 == next.nonceBase64 && it.keysetBase64 == next.keysetBase64 } == true
                        val cached = same && existing?.localPath?.let { java.io.File(it).isFile } == true
                        val restored = verifiedFiles[metadata.attachmentId]
                        check(restored == null || (restored.isFile && restored.length() == metadata.size))
                        db.attachmentDao().upsert(if (restored != null) next.copy(localPath=restored.absolutePath, downloadState="downloaded",progress=100) else if (cached) next.copy(localPath=existing?.localPath,
                            downloadState=existing?.downloadState ?: "not_downloaded",progress=existing?.progress ?: 0) else next)
                    }
                    db.messageDao().deletePendingDecrypt(row.client)
                    db.chatDao().touchLastMessageAt(row.chat, Instant.ofEpochMilli(row.createdAtMs).toString())
                }
                for (row in merged.reactions) db.reactionDao().upsert(ReactionEntity(row.target,row.user,row.emoji,row.sequence))
                for (row in merged.messages) replayLocalEvents(row)
                currentCoroutineContext().ensureActive(); checkOwner(owner, epoch)
                beforeCommit()
                currentCoroutineContext().ensureActive(); checkOwner(owner, epoch)
                touched
            }
        } ?: error("History account changed")
    }

    private suspend fun replayLocalEvents(row: HistoryArchiveMessage) {
        for (event in db.messageDao().lifecycleEvents(row.id)) {
            val sequence = event.sequence ?: continue
            if (event.chatId != row.chat) continue
            val content = MessageContentCodec.decode(event.text.toByteArray(Charsets.UTF_8))
            if (!MessageContentCodec.authenticates(content,event.chatId,event.clientMessageId,event.kind,row.id) ||
                content.authentication?.targetClientMessageId != row.client) continue
            when (event.kind) {
                "edit" -> if (event.senderId == row.sender && content.text != null && event.text != "[edit]")
                    db.messageDao().applyEdit(row.id,content.text,sequence)
                "delete" -> if (event.senderId == row.sender) db.messageDao().applyDelete(row.id,sequence)
                "reaction" -> {
                    if (content.kind != "reaction" || content.reactionAction !in setOf("set","remove")) continue
                    val target = db.messageDao().findByServerId(row.id) ?: continue
                    if (!target.hiddenLocally && !target.isDeletedForEveryone && sequence > (db.reactionDao().currentSequence(row.id,event.senderId) ?: 0)) {
                        db.reactionDao().upsert(ReactionEntity(row.id,event.senderId,
                            if (content.reactionAction == "remove") null else content.reaction,sequence))
                    }
                }
            }
        }
        val target = db.messageDao().findByServerId(row.id)
        if (target?.hiddenLocally == true || target?.isDeletedForEveryone == true) db.reactionDao().deleteAllForMessage(row.id)
    }

    private suspend fun read(server: String, owner: String, snapshot: String, allowUnreadable: Boolean): HistoryArchive {
        val values = db.workspaceDao().historyArchiveValues()
        check(values.size <= 10_001)
        val cleared = values.firstOrNull { it.chatId == "__server_history__" && it.name == "cleared_through_sequence" }?.value?.toLong() ?: 0
        val clears = values.filter { it.name == "personal_clear" }.associate { it.chatId to it.value.toLong() }
        val deleted = db.deletedChatDao().historyArchiveIds().toSet()
        // Unlike the UI's recent-record window, portable export cannot drop old
        // descriptors. Stream bounded protocol pages, retaining only inert offers.
        val direct = mutableMapOf<String, DirectFileOffer>()
        var directOffset = 0
        var directBudget = 0L
        while (true) {
            val page = db.workspaceDao().historyDirectTransferPage(directOffset)
            if (page.isEmpty()) break
            directOffset += page.size
            check(directOffset <= 50_000)
            for (value in page) {
                val record = DirectTransferCoordinator.decodeRecord(value.value) ?: continue
                if (record.chatId != value.chatId || value.name != DirectTransferCoordinator.key(record.offer.transferId)) continue
                val row = db.messageDao().findByClientMessageId(record.offer.offerClientMessageId) ?: continue
                if (row.chatId != record.chatId || row.kind != "content" || row.sequence == null ||
                    row.hiddenLocally || row.isDeletedForEveryone || row.chatId in deleted ||
                    row.sequence <= maxOf(cleared, clears[row.chatId] ?: 0)) continue
                val offer = DirectFileHistory.validate(record.offer, row.clientMessageId, row.senderId, owner)
                val old = direct.put(row.clientMessageId, offer)
                check(old == null || old == offer)
                if (old == null) {
                    directBudget += 512L + 6L * offer.stored().toByteArray(Charsets.UTF_8).size
                    check(directBudget <= HistoryArchive.MAX_BYTES)
                }
            }
        }
        val messages = ArrayList<HistoryArchiveMessage>()
        var after = Long.MIN_VALUE; var afterClient = ""; var count = 0
        var budget = 1024L + clears.size * 80L + deleted.size * 40L
        while (true) {
          val source = db.messageDao().historyArchivePage(after, afterClient)
          if (source.isEmpty()) break
          for (row in source) {
            after = requireNotNull(row.sequence); afterClient = row.clientMessageId
            check(after > 0)
            check(++count <= 50_000)
            if (row.chatId in deleted || requireNotNull(row.sequence) <= maxOf(cleared, clears[row.chatId] ?: 0)) continue
            if (row.status !in readable && !row.hiddenLocally && !row.isDeletedForEveryone) {
                if (!allowUnreadable) throw HistorySourceNotReady(); continue
            }
            check(!row.isDeletedForEveryone || row.deleteSequence > 0) { "Wait for pending deletion" }
            val attachment = db.attachmentDao().findByClientMessageId(row.clientMessageId)
            val content = if (row.hiddenLocally || row.isDeletedForEveryone) null else {
                val metadata = attachment?.let(::toMetadata)
                val trusted = direct[row.clientMessageId]
                val inert = db.workspaceDao().get(row.chatId, DirectFileHistory.key(row.clientMessageId))?.let { requireNotNull(DirectFileOffer.decodeStored(it)) }
                check(trusted == null || inert == null || trusted == inert)
                val offer = (trusted ?: inert)?.let { DirectFileHistory.validate(it, row.clientMessageId, row.senderId, owner) }
                check(offer == null || metadata == null && !row.forwarded && row.conferenceInviteJson == null)
                MessageContentDto(kind=if (metadata == null) "text" else "attachment",
                    text=if (metadata == null) row.text else row.caption, attachment=metadata,
                    replySnapshot=row.replySnapshotJson?.let { json.decodeFromString<ReplySnapshotDto>(it) },
                    forwarded=row.forwarded, albumId=row.albumId,
                    conferenceInvite=com.messenger.app.conference.ConferenceInvitation.decodeStored(row.conferenceInviteJson)?.json(), directFile=offer?.json())
            }
            val payload = content?.let { json.encodeToString(it) }
            budget += 512L + 6L * (payload?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            check(budget <= HistoryArchive.MAX_BYTES)
            messages.add(HistoryArchiveMessage(row.clientMessageId, requireNotNull(row.serverId), row.chatId,
                row.senderId, row.sequence, Instant.parse(row.createdAt).toEpochMilli(),
                payload, row.lastEditSequence, row.deleteSequence, row.hiddenLocally, row.targetMessageId))
          }
        }
        val live = messages.filter { !it.hidden && it.deletedThrough == 0L }.map { it.id }.toSet()
        val reactions = db.reactionDao().historyArchiveRows()
        check(reactions.size <= 50_000)
        return HistoryArchive(server=server, owner=owner, snapshot=snapshot, messages=messages,
            clearedThrough=cleared, chatClears=clears, deletedChats=deleted,
            reactions=reactions.filter { it.targetMessageId in live }.map { HistoryArchiveReaction(it.targetMessageId,it.userId,it.lastReactionSequence,it.emoji) })
            .also { it.validate(server,owner); it.messages.forEach { row -> decodeContent(row, owner) } }
    }

    private fun decodeContent(row: HistoryArchiveMessage, owner: String): MessageContentDto? {
        val text = row.content ?: return null
        val content = json.decodeFromString<MessageContentDto>(text)
        require(content.version == 1 && content.kind in setOf("text","attachment") && content.authentication == null)
        require(if (content.kind == "text") content.text != null && content.attachment == null else content.attachment != null)
        val rawObject = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        require(rawObject?.containsKey("direct_file") != true || content.directFile != null)
        content.directFile?.let { raw ->
            require(content.kind == "text" && !content.forwarded && content.conferenceInvite == null)
            DirectFileHistory.validate(requireNotNull(DirectFileOffer.decode(raw)), row.client, row.sender, owner)
        }
        content.attachment?.let { metadata ->
            requireHistoryId(metadata.attachmentId)
            require(metadata.size >= 0 && metadata.encryptedSize > 0 && metadata.objectKey.length in 1..1024)
            require(metadata.encryption.version in 1..2 && metadata.mimeType.length in 1..255)
            metadata.image?.let { require(it.width in 1..32768 && it.height in 1..32768) }
            metadata.voice?.let { require(it.durationMs in 0..Int.MAX_VALUE.toLong()) }
            metadata.video?.let { require(it.durationMs in 0..Int.MAX_VALUE.toLong()) }
            require(java.util.Base64.getDecoder().decode(metadata.encryption.sha256).size == 32)
            if (metadata.encryption.version == 1) {
                require(java.util.Base64.getDecoder().decode(requireNotNull(metadata.encryption.key)).size == 32)
                require(java.util.Base64.getDecoder().decode(requireNotNull(metadata.encryption.nonce)).size == 12)
            } else require(java.util.Base64.getDecoder().decode(requireNotNull(metadata.encryption.keyset)).size in 1..4096)
        }
        return if (rawObject?.containsKey("conference_invite") == true) content.copy(text = com.messenger.app.conference.ConferenceInvitation.FALLBACK,
            conferenceInvite = if (content.kind == "text") com.messenger.app.conference.ConferenceInvitation.decode(content.conferenceInvite)?.json() else null) else content
    }
    private fun toMetadata(a: AttachmentEntity) = AttachmentMetadataDto(a.attachmentId,a.mediaType,a.objectKey,a.size,a.encryptedSize,a.mimeType,
        EncryptionInfoDto(a.encryptionVersion,a.keyBase64,a.nonceBase64,a.keysetBase64,a.sha256Base64),a.filename,
        if (a.width != null && a.height != null) ImageInfoDto(a.width,a.height) else null,
        if (a.mediaType == "voice") a.durationMs?.let(::VoiceInfoDto) else null,a.thumbnailBase64,
        if (a.mimeType.startsWith("video/")) a.durationMs?.let(::VideoInfoDto) else null)
    private fun toAttachment(row: HistoryArchiveMessage, a: AttachmentMetadataDto) = AttachmentEntity(
        a.attachmentId,row.client,row.chat,a.mediaType,a.mimeType,a.filename,a.size,a.image?.width,a.image?.height,
        a.video?.durationMs ?: a.voice?.durationMs,a.thumbnail,null,"not_downloaded","sent",0,a.objectKey,a.encryptedSize,
        a.encryption.version,a.encryption.key,a.encryption.nonce,a.encryption.keyset,a.encryption.sha256)
}
