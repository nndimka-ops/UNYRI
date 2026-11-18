package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AttachmentMediaType
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.ChatSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class ForwardPreviewKind { TEXT, PHOTOS, VIDEO, VOICE, FILES, MIXED }
internal data class ForwardPickerPreview(
    val count: Int = 0,
    val kind: ForwardPreviewKind = ForwardPreviewKind.TEXT,
    val snippets: List<String> = emptyList(),
)

internal data class ForwardPickerSource(val message: Message, val attachment: Attachment?, val resolved: Boolean)

private fun Attachment.hasCompletedUpload(): Boolean = uploadState in setOf(
    com.messenger.app.domain.model.AttachmentUploadState.UPLOADED,
    com.messenger.app.domain.model.AttachmentUploadState.SENDING,
    com.messenger.app.domain.model.AttachmentUploadState.SENT,
)

/** Content is captured once per picker session. Live rows are admission checks only. */
internal class ForwardPickerSources(ids: List<String>, rows: List<Message>, media: List<Attachment>) {
    private val captured = run {
        val rowsById = rows.associateBy { it.clientMessageId }
        val mediaById = media.associateBy { it.clientMessageId }
        ids.distinct().mapNotNull { id -> rowsById[id]?.let { row ->
            val persistent = mediaById[id]
            val resolved = if (persistent == null) row.queuedAttachment == null
                else persistent.hasCompletedUpload()
            ForwardPickerSource(row.copy(reactions = row.reactions.toList()), (persistent ?: row.queuedAttachment)?.copy(), resolved)
        } }.associateBy { it.message.clientMessageId }
    }

    operator fun get(id: String): ForwardPickerSource? = captured[id]
    /** A queued preview (or unfinished upload row) is not a durable source asset. */
    val resolved = captured.size == ids.distinct().size && captured.values.all { it.resolved }
    val preview = forwardPickerPreview(captured.values.map { it.message }, captured.values.mapNotNull { it.attachment })

    fun available(ids: List<String>, liveRows: List<Message>, liveMedia: List<Attachment>): Boolean {
        val rows = liveRows.associateBy { it.clientMessageId }
        val media = liveMedia.associateBy { it.clientMessageId }
        return ids.all { id ->
            val source = captured[id] ?: return@all false
            val live = rows[id] ?: return@all false
            source.resolved && live.chatId == source.message.chatId && live.senderId == source.message.senderId &&
                !live.isDeletedForEveryone && live.directFile == null && live.status !in setOf(
                    com.messenger.app.domain.model.MessageStatus.DECRYPT_FAILED,
                    com.messenger.app.domain.model.MessageStatus.IDENTITY_CHANGED,
                ) && if (source.attachment == null) media[id] == null && live.queuedAttachment == null
                else media[id]?.let { it.attachmentId == source.attachment.attachmentId && it.hasCompletedUpload() } == true
        }
    }

    fun validContent(ids: List<String>): Boolean = try {
        ids.all { id ->
            val source = captured[id] ?: return@all false
            if (source.attachment != null) {
                com.messenger.app.data.message.MessageTextPolicy.caption(source.message.caption)
                com.messenger.app.data.message.MessageTextPolicy.received(source.attachment.filename)
            } else if (source.message.conferenceInvitation == null) {
                com.messenger.app.data.message.MessageTextPolicy.outgoing(source.message.text)
            }
            true
        }
    } catch (_: com.messenger.app.data.message.MessageTextPolicyException) { false }
}

/** One production payload adapter for text and media. Reopening/retrying keeps this instance. */
internal class ForwardPickerPayloadAdapter(
    val sources: ForwardPickerSources,
    private val repository: com.messenger.app.domain.repository.MessageRepository,
    private val expectedEpoch: Int,
    private val liveRows: () -> List<Message>,
    private val liveMedia: () -> List<Attachment>,
    private val requireCurrentSession: () -> Unit,
    private val prepareAttachment: suspend (Attachment, String, String) -> com.messenger.app.data.attachment.AttachmentMetadataDto,
) {
    private val ids = mutableMapOf<Pair<String, String>, String>()
    private val prepared = mutableMapOf<String, com.messenger.app.data.attachment.AttachmentMetadataDto>()

    fun validate(ids: List<String>): Boolean = sources.available(ids, liveRows(), liveMedia()) && sources.validContent(ids)

    suspend fun forward(sourceId: String, destination: String, album: String?): Boolean {
        requireCurrentSession()
        if (!validate(listOf(sourceId))) return false
        val source = sources[sourceId] ?: return false
        val id = ids.getOrPut(destination to sourceId) { java.util.UUID.randomUUID().toString() }
        return try {
            val result = if (source.attachment == null) repository.forwardMessage(source.message, destination, id, expectedEpoch)
            else {
                val metadata = prepared[id] ?: prepareAttachment(source.attachment, destination, id).also { prepared[id] = it }
                requireCurrentSession()
                // Upload can suspend while the original is deleted. Admission stays live; payload stays frozen.
                if (!validate(listOf(sourceId))) return false
                repository.sendAttachmentMessage(destination, id, metadata, forwarded = true,
                    caption = source.message.caption, albumId = album)
            }
            requireCurrentSession()
            result.onFailure { if (it is CancellationException) throw it }.isSuccess
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { requireCurrentSession(); false }
    }
}

/** Summarize the captured batch, including every album member, rather than its first file. */
internal fun forwardPickerPreview(rows: List<Message>, media: List<Attachment>): ForwardPickerPreview {
    val attachments = media.associateBy { it.clientMessageId }
    val kinds = rows.map { row ->
        val item = attachments[row.clientMessageId] ?: row.queuedAttachment
        when {
            item == null -> ForwardPreviewKind.TEXT
            item.mimeType.startsWith("video/") -> ForwardPreviewKind.VIDEO
            item.mediaType == AttachmentMediaType.IMAGE -> ForwardPreviewKind.PHOTOS
            item.mediaType == AttachmentMediaType.VOICE -> ForwardPreviewKind.VOICE
            else -> ForwardPreviewKind.FILES
        }
    }.distinct()
    val snippets = rows.asSequence().mapNotNull { row ->
        val item = attachments[row.clientMessageId] ?: row.queuedAttachment
        (if (item == null) row.filePreview?.projected() ?: row.directFile?.offer?.let { com.messenger.app.domain.model.FilePreviewText.Preview(it.filename, com.messenger.app.data.directtransfer.DirectFileOffer.caption(row.text)).projected() } ?: row.text else row.caption?.takeIf { it.isNotBlank() } ?: item.filename)
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                text.substring(0, text.offsetByCodePoints(0, minOf(160, text.codePointCount(0, text.length))))
            }
    }.distinct().take(3).toList()
    return ForwardPickerPreview(rows.size, kinds.singleOrNull() ?: ForwardPreviewKind.MIXED, snippets)
}

internal enum class ForwardPickerFailure { SOURCE, SEND, SHARE }
internal data class ForwardPickerState(
    val visible: Boolean = false,
    val sourceIds: List<String> = emptyList(),
    val preview: ForwardPickerPreview = ForwardPickerPreview(),
    val selectedIds: List<String> = emptyList(),
    val targetSnapshots: Map<String, ChatSummary> = emptyMap(),
    val query: String = "",
    val comment: String = "",
    val loading: Boolean = false,
    val prepareFailed: Boolean = false,
    val sending: Boolean = false,
    val sharing: Boolean = false,
    val commentLocked: Boolean = false,
    val selectionLimitReached: Boolean = false,
    val failure: ForwardPickerFailure? = null,
    val completedTargets: Int = 0,
    val completedSourceIds: List<String> = emptyList(),
) {
    val busy get() = sending || sharing
}

/** ViewModel-owned draft and finite dispatch journal. All calls are on the main dispatcher.
 * Success means durable acceptance by the existing outbox, not remote delivery.
 * Closing the sheet does not cancel accepted work or discard its retry journal. */
internal class ForwardPickerSession {
    private val mutable = MutableStateFlow(ForwardPickerState())
    val state: StateFlow<ForwardPickerState> = mutable
    private val delivered = mutableSetOf<Pair<String, String>>()
    private val commented = mutableSetOf<String>()
    private var albumIds: Map<String, String> = emptyMap()
    private var sourceRejected = false
    private var dispatchStarted = false

    fun open(ids: List<String>, preview: ForwardPickerPreview, albums: Map<String, String>, sourcesResolved: Boolean = true): Boolean {
        val sources = ids.distinct()
        val old = mutable.value
        if (old.busy) {
            mutable.value = old.copy(visible = true)
            return false
        }
        val same = old.sourceIds == sources && old.completedSourceIds.isEmpty()
        if (!sourcesResolved) {
            if (!same) {
                resetProgress(albums)
                mutable.value = ForwardPickerState(sourceIds = sources, preview = preview, failure = ForwardPickerFailure.SOURCE)
            } else mutable.value = old.copy(visible = false, failure = ForwardPickerFailure.SOURCE)
            sourceRejected = true
            return false
        }
        // Safe to replace a rejected capture only before any possibly accepted write.
        if (same && !(sourceRejected && !dispatchStarted)) {
            mutable.value = old.copy(visible = true)
            return false
        }
        resetProgress(albums)
        mutable.value = if (same) old.copy(visible = true, preview = preview, failure = null, completedTargets = 0)
            else ForwardPickerState(visible = true, sourceIds = sources, preview = preview)
        return true
    }

    private fun resetProgress(albums: Map<String, String>) {
        delivered.clear(); commented.clear(); albumIds = albums
        sourceRejected = false; dispatchStarted = false
    }

    fun dismiss() { mutable.value = mutable.value.copy(visible = false) }
    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun comment(value: String) {
        if (!mutable.value.busy && !mutable.value.commentLocked) mutable.value = mutable.value.copy(comment = value)
    }
    fun toggle(id: String, target: ChatSummary? = null) {
        val old = mutable.value
        if (old.busy) return
        if (id !in old.selectedIds && old.selectedIds.size >= 10) {
            mutable.value = old.copy(selectionLimitReached = true)
            return
        }
        val ids = if (id in old.selectedIds) old.selectedIds - id else old.selectedIds + id
        mutable.value = old.copy(selectedIds = ids, selectionLimitReached = false,
            targetSnapshots = if (id !in ids) old.targetSnapshots - id else target?.let { old.targetSnapshots + (id to it) } ?: old.targetSnapshots,
            completedTargets = ids.count { target -> old.sourceIds.all { target to it in delivered } })
    }
    fun loading(value: Boolean, failed: Boolean = false) {
        mutable.value = mutable.value.copy(loading = value, prepareFailed = failed)
    }
    fun beginShare(): Boolean {
        if (mutable.value.busy) return false
        mutable.value = mutable.value.copy(sharing = true, failure = null)
        return true
    }
    fun endShare(failed: Boolean) {
        mutable.value = mutable.value.copy(sharing = false, failure = if (failed) ForwardPickerFailure.SHARE else null)
    }

    suspend fun send(
        isCurrent: () -> Boolean,
        validate: (List<String>) -> Boolean,
        sendComment: suspend (String, String) -> Boolean,
        forward: suspend (source: String, destination: String, album: String?) -> Boolean,
    ) {
        val draft = mutable.value
        if (!draft.visible || draft.busy || draft.selectedIds.isEmpty() || draft.sourceIds.isEmpty() || draft.completedSourceIds.isNotEmpty()) return
        mutable.value = draft.copy(sending = true, failure = null)
        fun checkOwner() {
            if (!isCurrent()) throw CancellationException("Forward picker owner changed")
        }
        try {
            checkOwner()
            val remaining = draft.sourceIds.filter { id -> draft.selectedIds.any { it to id !in delivered } }
            if (!validate(remaining)) {
                sourceRejected = true
                mutable.value = mutable.value.copy(failure = ForwardPickerFailure.SOURCE)
                return
            }
            for (destination in draft.selectedIds) {
                currentCoroutineContext().ensureActive(); checkOwner()
                try {
                    val text = draft.comment.trim()
                    // A failed comment must never let its forwarded content overtake it.
                    if (text.isNotEmpty() && destination !in commented) {
                        dispatchStarted = true
                        if (!sendComment(destination, text)) continue
                        commented += destination
                        mutable.value = mutable.value.copy(commentLocked = true)
                    }
                    for (id in draft.sourceIds) {
                        currentCoroutineContext().ensureActive(); checkOwner()
                        if (destination to id in delivered) continue
                        val album = albumIds[id]?.let { java.util.UUID.nameUUIDFromBytes("$it:$destination".toByteArray()).toString() }
                        dispatchStarted = true
                        if (!forward(id, destination, album)) break
                        delivered += destination to id
                        mutable.value = mutable.value.copy(commentLocked = true)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { checkOwner() }
            }
            checkOwner()
            val completed = draft.selectedIds.count { target ->
                (draft.comment.isBlank() || target in commented) && draft.sourceIds.all { target to it in delivered }
            }
            if (completed == draft.selectedIds.size) {
                mutable.value = mutable.value.copy(visible = false, selectedIds = emptyList(), query = "", comment = "",
                    completedTargets = completed, completedSourceIds = draft.sourceIds, failure = null)
            } else {
                mutable.value = mutable.value.copy(completedTargets = completed, failure = ForwardPickerFailure.SEND)
            }
        } finally {
            mutable.value = mutable.value.copy(sending = false)
        }
    }
}
