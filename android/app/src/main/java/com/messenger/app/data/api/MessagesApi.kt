package com.messenger.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for the 1:1 message endpoints. Realtime delivery is a
 *  separate concern (see MessagingSocketClient) - this is HTTP send +
 *  history sync only. */
class MessagesApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) : DeliveredReporter {
    suspend fun sendMessage(
        accessToken: String,
        chatId: String,
        clientMessageId: String,
        envelopes: List<EnvelopeDto>,
        attachmentIds: List<String> = emptyList(),
        kind: String = "content",
        targetMessageId: String? = null,
    ): SendMessageResponseDto {
        val requestBody = json.encodeToString(
            SendMessageRequestDto.serializer(),
            SendMessageRequestDto(clientMessageId, envelopes, attachmentIds, kind, targetMessageId),
        )
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/messages", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeApiResponse(execute(request))
    }

    suspend fun listMessages(
        accessToken: String,
        chatId: String,
        beforeSequence: Long? = null,
        limit: Int = 50,
    ): MessageListResponseDto {
        val urlBuilder = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/messages".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
        beforeSequence?.let { urlBuilder.addQueryParameter("before_sequence", it.toString()) }
        val request = authorized(urlBuilder.build().toString(), accessToken).get().build()
        return json.decodeApiResponse(execute(request))
    }

    suspend fun markRead(accessToken: String, chatId: String, sequence: Long): ReadReceiptResponseDto {
        val requestBody = json.encodeToString(ReadReceiptRequestDto.serializer(), ReadReceiptRequestDto(sequence))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/read", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeApiResponse(execute(request))
    }

    /** Client's own explicit "decrypted and durably persisted" signal -
     *  distinct from the passive fetch-implies-delivered side effect of
     *  `/sync`/`listMessages`. See `MessageIngestionPipeline`. */
    override suspend fun markDelivered(accessToken: String, chatId: String, sequence: Long): ReadReceiptResponseDto {
        val requestBody = json.encodeToString(ReadReceiptRequestDto.serializer(), ReadReceiptRequestDto(sequence))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/delivered", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeApiResponse(execute(request))
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = client.executeCancellableApi(request, json)
}
