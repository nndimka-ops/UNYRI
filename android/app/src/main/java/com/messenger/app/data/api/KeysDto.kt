package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignedPreKeyUploadDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class KyberPreKeyUploadDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class OneTimePreKeyUploadDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
)

/** All key material is base64 - the server stores it opaquely, never
 *  parsing or verifying it (that happens on-device via libsignal). */
@Serializable
data class UploadKeysRequestDto(
    @SerialName("identity_key") val identityKey: String,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("signed_prekey") val signedPrekey: SignedPreKeyUploadDto,
    @SerialName("kyber_prekey") val kyberPrekey: KyberPreKeyUploadDto,
    @SerialName("one_time_prekeys") val oneTimePrekeys: List<OneTimePreKeyUploadDto> = emptyList(),
)

@Serializable
data class UploadKeysResponseDto(
    @SerialName("one_time_prekey_count") val oneTimePrekeyCount: Long,
)

@Serializable
data class DeviceBundleDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("signal_device_id") val signalDeviceId: Int,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey_id") val signedPrekeyId: Int,
    @SerialName("signed_prekey_public") val signedPrekeyPublic: String,
    @SerialName("signed_prekey_signature") val signedPrekeySignature: String,
    @SerialName("kyber_prekey_id") val kyberPrekeyId: Int,
    @SerialName("kyber_prekey_public") val kyberPrekeyPublic: String,
    @SerialName("kyber_prekey_signature") val kyberPrekeySignature: String,
    @SerialName("one_time_prekey_id") val oneTimePrekeyId: Int? = null,
    @SerialName("one_time_prekey_public") val oneTimePrekeyPublic: String? = null,
)

@Serializable
data class UserBundlesResponseDto(val devices: List<DeviceBundleDto>)

@Serializable
data class OneTimePreKeyCountResponseDto(val count: Long)
