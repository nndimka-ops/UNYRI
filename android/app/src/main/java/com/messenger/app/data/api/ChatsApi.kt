package com.messenger.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for the 1:1 chat endpoints. Message send/receive endpoints
 *  don't exist yet - that's the next stage. */
class ChatsApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    @kotlinx.serialization.Serializable
    data class Pins(val chat_ids: List<String>)
    @kotlinx.serialization.Serializable
    data class PinRequest(val chat_ids: List<String>, val pinned: Boolean)
    @kotlinx.serialization.Serializable
    data class ArchiveRequest(val chat_ids: List<String>, val archived: Boolean)
    @kotlinx.serialization.Serializable
    data class ClearHistoryRequest(val request_id: String, val for_everyone: Boolean, val through_sequence: Long, val calls_before: String? = null)
    suspend fun clearHistory(token: String, chatId: String, request: ClearHistoryRequest) {
        require(runCatching { java.util.UUID.fromString(chatId) }.isSuccess)
        execute(authorized("${ServerEndpoint.resolve(baseUrl)}/chats/$chatId/clear-history", token)
            .post(json.encodeToString(ClearHistoryRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)).build())
    }
    suspend fun deleteForBoth(token: String, chatId: String, forEveryone: Boolean = true, requestId: String = java.util.UUID.randomUUID().toString()) {
        execute(authorized("${ServerEndpoint.resolve(baseUrl)}/chats/$chatId/delete", token)
            .post("{\"for_everyone\":$forEveryone,\"request_id\":\"$requestId\"}".toRequestBody(JSON_MEDIA_TYPE)).build())
    }
    @kotlinx.serialization.Serializable
    data class PersonalClear(val chat_id: String, val through_sequence: Long, val calls_before: String? = null)
    suspend fun personalClears(token: String): List<PersonalClear> = json.decodeFromString(execute(
        authorized("${ServerEndpoint.resolve(baseUrl)}/chats/cleared", token).get().build()))
    suspend fun pins(token: String): Set<String> = json.decodeFromString<Pins>(execute(
        authorized("${ServerEndpoint.resolve(baseUrl)}/chats/pins", token).get().build())).chat_ids.toSet()
    suspend fun setPins(token: String, ids: Set<String>, pinned: Boolean): Set<String> = json.decodeFromString<Pins>(execute(
        authorized("${ServerEndpoint.resolve(baseUrl)}/chats/pins", token)
            .post(json.encodeToString(PinRequest.serializer(), PinRequest(ids.toList(), pinned)).toRequestBody(JSON_MEDIA_TYPE)).build())).chat_ids.toSet()

    suspend fun archives(token: String): Set<String> = json.decodeFromString<Pins>(execute(
        authorized("${ServerEndpoint.resolve(baseUrl)}/chats/archives", token).get().build())).chat_ids.toSet()
    suspend fun setArchived(token: String, ids: Set<String>, archived: Boolean): Set<String> {
        require(ids.isNotEmpty() && ids.size <= 100)
        return json.decodeFromString<Pins>(execute(
            authorized("${ServerEndpoint.resolve(baseUrl)}/chats/archives", token)
                .put(json.encodeToString(ArchiveRequest.serializer(), ArchiveRequest(ids.sorted(), archived)).toRequestBody(JSON_MEDIA_TYPE))
                .build())).chat_ids.toSet()
    }

    suspend fun createDirect(accessToken: String, otherUserId: String): ChatSummaryDto {
        val requestBody = json.encodeToString(CreateDirectChatRequestDto.serializer(), CreateDirectChatRequestDto(otherUserId))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/direct", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    suspend fun listKnownChats(accessToken: String, chatIds: List<String>): ChatListResponseDto {
        require(chatIds.isNotEmpty() && chatIds.size <= 50)
        val url = "${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/known".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "50")
            .addQueryParameter("chat_ids", chatIds.joinToString(","))
            .build()
        val request = authorized(url.toString(), accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    @kotlinx.serialization.Serializable
    data class Presence(val online: Boolean)
    suspend fun presence(token: String, chatId: String): Boolean = json.decodeFromString<Presence>(execute(
        authorized("${ServerEndpoint.resolve(baseUrl)}/chats/$chatId/presence", token).get().build())).online

    suspend fun getChat(accessToken: String, chatId: String): ChatSummaryDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    suspend fun getPeerChat(accessToken: String, userId: String): ChatSummaryDto? {
        val request = authorized("${ServerEndpoint.resolve(baseUrl)}/chats/peer/$userId", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    /** Idempotent get-or-create of the owner's private Saved Messages chat (M.35). */
    suspend fun getOrCreateSaved(accessToken: String): ChatSummaryDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/saved", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    suspend fun setMute(accessToken: String, chatId: String, mutedUntil: String?) {
        val requestBody = json.encodeToString(MuteChatRequestDto.serializer(), MuteChatRequestDto(mutedUntil))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/mute", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    suspend fun clearMute(accessToken: String, chatId: String) {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/unmute", accessToken)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    suspend fun blockUser(accessToken: String, chatId: String, blockedUserId: String) {
        val requestBody = json.encodeToString(BlockChatRequestDto.serializer(), BlockChatRequestDto(blockedUserId))
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/block", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    suspend fun unblockUser(accessToken: String, chatId: String) {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/unblock", accessToken)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    suspend fun reportChat(
        accessToken: String,
        chatId: String,
        reason: String,
        reportedUserId: String?,
        description: String?,
    ) {
        val requestBody = json.encodeToString(
            ReportChatRequestDto.serializer(),
            ReportChatRequestDto(reason = reason, reportedUserId = reportedUserId, description = description?.trim()?.takeIf { it.isNotEmpty() }),
        )
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/chats/$chatId/report", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request)
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = client.executeCancellableApi(request, json)
}
