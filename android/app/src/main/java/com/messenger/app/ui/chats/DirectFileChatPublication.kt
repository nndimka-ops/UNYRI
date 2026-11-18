package com.messenger.app.ui.chats

import com.messenger.app.data.directtransfer.DirectFilePresentation
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** UI publication only. The existing repository retains all canonical/history,
 * sender/offer/authentication guards and durable ACK writes without modification. */
internal fun directFileMessageStructure(messages: List<Message>?): List<Message>? = messages?.map { message ->
    val value = message.directFile ?: return@map message
    val structure = value.copy(offset = 0, ackSampleId = 0, speedBytesPerSecond = 0)
    if (structure == value) message else message.copy(directFile = structure)
}

internal fun Flow<List<Message>?>.structuralDirectFileMessages(): Flow<List<Message>?> =
    map(::directFileMessageStructure).distinctUntilChanged().flowOn(Dispatchers.Default)

/** Select only the exact visible authenticated message/offer from that guarded
 * snapshot. Null on scope/removal/identity mismatch means no actionable card. */
internal fun visibleDirectFilePresentation(
    messages: List<Message>?, expected: Message, chat: String, sessionCurrent: Boolean,
): DirectFilePresentation? {
    if (!sessionCurrent || expected.chatId != chat) return null
    val offer = expected.directFile?.offer ?: return null
    fun eligible(value: Message) = value.chatId == chat && value.senderId == offer.senderUserId &&
        !value.forwarded && !value.isDeletedForEveryone &&
        value.status !in setOf(MessageStatus.DECRYPT_FAILED, MessageStatus.IDENTITY_CHANGED)
    if (offer.offerClientMessageId != expected.clientMessageId || !eligible(expected)) return null
    val latest = messages?.firstOrNull { it.clientMessageId == expected.clientMessageId } ?: return null
    if (!eligible(latest)) return null
    return latest.directFile?.takeIf { it.offer == offer }
}

internal fun Flow<List<Message>?>.visibleDirectFilePresentation(
    expected: Message, chat: String, sessionCurrent: Flow<Boolean>,
): Flow<DirectFilePresentation?> = combine(sessionCurrent) { messages, current ->
    visibleDirectFilePresentation(messages, expected, chat, current)
}.distinctUntilChanged()
