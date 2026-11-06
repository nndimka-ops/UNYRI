package com.messenger.app.data.devices

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.DevicesApi
import com.messenger.app.data.api.DeviceDto
import com.messenger.app.data.api.SecurityEventDto
import com.messenger.app.domain.model.DeviceInfo
import com.messenger.app.domain.model.SecurityEventInfo
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.DevicesRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DevicesRepositoryImpl(
    private val api: DevicesApi,
    private val authRepository: AuthRepository,
    private val tokenStore: com.messenger.app.data.secure.SecureTokenStore? = null,
) : DevicesRepository {

    override fun cachedDevices(): List<DeviceInfo>? = runCatching {
        Json.decodeFromString<List<DeviceInfo>>(tokenStore?.offlineScreen("devices") ?: return null)
    }.getOrNull()

    override suspend fun listDevices(): Result<List<DeviceInfo>> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        val owner = tokenStore?.offlineScreenOwner()
        val token = accessToken()
        val devices = api.listDevices(token).devices.map { it.toDomain() }
        check(epoch == authRepository.currentSessionEpoch())
        runCatching { tokenStore?.saveOfflineScreen("devices", owner,
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DeviceInfo.serializer()), devices)) }
        devices
    }

    override suspend fun renameDevice(deviceId: String, displayName: String): Result<Unit> = runCatching {
        api.renameDevice(accessToken(), deviceId, displayName)
        Unit
    }

    override suspend fun revokeDevice(deviceId: String): Result<Unit> = runCatching {
        api.revokeDevice(accessToken(), deviceId)
        Unit
    }

    override suspend fun revokeOtherDevices(): Result<Int> = runCatching {
        api.revokeOtherDevices(accessToken()).revokedCount.toInt()
    }

    override suspend fun securityEvents(limit: Int, cursor: Long?): Result<Pair<List<SecurityEventInfo>, Long?>> = runCatching {
        val response = api.securityEvents(accessToken(), limit, cursor)
        response.events.map { it.toDomain() } to response.nextCursor
    }

    private suspend fun accessToken(): String =
        authRepository.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")

    private fun DeviceDto.toDomain() = DeviceInfo(
        id = id,
        displayName = displayName,
        platform = platform,
        osVersion = osVersion,
        appVersion = appVersion,
        lastActiveAt = lastActiveAt,
        isCurrent = isCurrent,
    )

    private fun SecurityEventDto.toDomain(): SecurityEventInfo {
        val metadata = metadata?.jsonObject
        val metadataName = metadata?.get("display_name")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val metadataCount = metadata?.get("count")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val description = when (eventType) {
            "new_device" -> "New device added" + (metadataName?.let { ": $it" } ?: "")
            "device_revoked" -> "Device revoked" + (metadataName?.let { ": $it" } ?: "")
            "all_other_devices_revoked" -> "All other devices revoked" + (metadataCount?.let { " ($it)" } ?: "")
            else -> eventType
        }
        return SecurityEventInfo(
            id = id,
            eventType = eventType,
            actorDeviceId = actorDeviceId,
            subjectDeviceId = subjectDeviceId,
            description = description,
            createdAt = createdAt,
        )
    }
}
