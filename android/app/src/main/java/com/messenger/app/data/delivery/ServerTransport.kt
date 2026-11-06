package com.messenger.app.data.delivery

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.EnvelopeDto
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.domain.delivery.DeliveryAcceptance
import com.messenger.app.domain.delivery.DeliveryContext
import com.messenger.app.domain.delivery.MessageTransport
import com.messenger.app.domain.delivery.PreparedMessage
import com.messenger.app.domain.delivery.ServerReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Auth is resolved before acquiring the account write gate, never beneath it. */
internal class PreparedServerAuth(val token: String, val epoch: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PreparedServerAuth>
}

internal suspend fun <T> withPreparedServerAuth(token: String, epoch: Int, block: suspend () -> T): T =
    withContext(PreparedServerAuth(token, epoch)) { block() }

/** Compatibility adapter to the EXISTING server API. Bearer auth stays in data.
 * The callback may refresh a token, but must not silently switch the sending account. */
class ServerTransport(
    private val api: MessagesApi,
    private val currentAccessToken: suspend () -> String?,
    private val currentSessionEpoch: () -> Int,
) : MessageTransport {
    override suspend fun send(message: PreparedMessage, context: DeliveryContext): DeliveryAcceptance {
        checkSession(context)
        val prepared = coroutineContext[PreparedServerAuth]
        if (prepared != null && prepared.epoch != context.sessionEpoch) {
            throw CancellationException("Prepared auth session changed")
        }
        val token = prepared?.token ?: currentAccessToken()
        checkSession(context)
        if (token == null) throw AuthApiException.Network("no access token (transient refresh failure)")
        val response = api.sendMessage(
            accessToken = token,
            chatId = message.conversationId,
            clientMessageId = message.clientMessageId,
            envelopes = message.envelopes.map { EnvelopeDto(it.recipientDeviceId, it.messageType, it.ciphertext) },
            attachmentIds = message.attachmentIds,
            kind = message.kind,
            targetMessageId = message.targetMessageId,
        )
        checkSession(context)
        return DeliveryAcceptance.ServerAccepted(
            ServerReceipt(response.id, response.chatId, response.clientMessageId,
                response.senderId, response.sequence, response.createdAt, response.status),
        )
    }

    private fun checkSession(context: DeliveryContext) {
        if (currentSessionEpoch() != context.sessionEpoch) {
            throw CancellationException("Delivery session changed")
        }
    }
}
