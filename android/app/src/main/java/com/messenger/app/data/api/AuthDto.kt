package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class RegisterRequestDto(
    val username: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("device_platform") val devicePlatform: String = "android",
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("installation_credential") val installationCredential: String? = null,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class LoginRequestDto(
    val username: String,
    val password: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("device_platform") val devicePlatform: String = "android",
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("installation_credential") val installationCredential: String? = null,
)

@Serializable
data class InstallationCredentialDto(@SerialName("installation_credential") val credential: String)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class AuthResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_id") val deviceId: String,
    val user: UserDto,
    @SerialName("history_before_sequence") val historyBeforeSequence: Long? = null,
)

@Serializable
data class RefreshResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class ApiErrorDto(val error: ApiErrorDetailDto)

@Serializable
data class ApiErrorDetailDto(val code: String, val message: String)

@Serializable
data class HistoryContextDto(
    @SerialName("before_sequence") val beforeSequence: Long,
    @SerialName("before_date") val beforeDate: String,
)
