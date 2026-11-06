package com.messenger.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.messenger.app.data.attachment.withAttachmentResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** REST client for the storage-authorization side of attachments (Этап H) -
 *  upload-intent/complete/download-URL only. The actual encrypted bytes
 *  never pass through this client or through our own backend at all; see
 *  `AttachmentManager` for the direct-to-storage PUT/GET. */
class AttachmentsApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    suspend fun multipart(token: String, id: String): MultipartUploadDto = json.decodeFromString(execute(
        authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/attachments/$id/multipart", token)
            .post("".toRequestBody(JSON_MEDIA_TYPE)).build()))

    suspend fun acknowledgePart(token: String, id: String, number: Int, etag: String) {
        execute(authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/attachments/$id/parts/$number", token)
            .post(json.encodeToString(MultipartReceiptDto.serializer(), MultipartReceiptDto(etag)).toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun create(accessToken: String, clientAttachmentId: String, encryptedSize: Long): CreateAttachmentResponseDto {
        val requestBody = json.encodeToString(
            CreateAttachmentRequestDto.serializer(),
            CreateAttachmentRequestDto(clientAttachmentId, encryptedSize),
        )
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/attachments", accessToken)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    suspend fun complete(accessToken: String, attachmentId: String): CompleteAttachmentResponseDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/attachments/$attachmentId/complete", accessToken)
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    suspend fun download(accessToken: String, attachmentId: String): DownloadAttachmentResponseDto {
        val request = authorized("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/attachments/$attachmentId/download", accessToken).get().build()
        return json.decodeFromString(execute(request))
    }

    private fun authorized(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try { client.newCall(request).withAttachmentResponse {
            val bodyString = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw parseApiException(json, it.code, bodyString)
            }
            bodyString
        } } catch (e: java.io.IOException) { throw AuthApiException.Network(e.message ?: "Network error") }
    }
}
