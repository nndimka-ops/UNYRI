package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class MultipartUploadDto(@SerialName("part_size") val partSize: Long, val complete: Boolean, val parts: List<MultipartPartDto>)
@Serializable data class MultipartPartDto(val number: Int, val size: Long, @SerialName("upload_url") val uploadUrl: String, val complete: Boolean)
@Serializable data class MultipartReceiptDto(val etag: String)

@Serializable
data class CreateAttachmentRequestDto(
    @SerialName("client_attachment_id") val clientAttachmentId: String,
    @SerialName("encrypted_size") val encryptedSize: Long,
)

@Serializable
data class CreateAttachmentResponseDto(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class CompleteAttachmentResponseDto(
    @SerialName("attachment_id") val attachmentId: String,
    val status: String,
)

@Serializable
data class DownloadAttachmentResponseDto(
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("expires_at") val expiresAt: String,
)
