package com.messenger.app.domain.repository

import com.messenger.app.domain.model.DeviceInfo
import com.messenger.app.domain.model.SecurityEventInfo

/** Abstraction over device management + the security-event log (Этап L). */
interface DevicesRepository {
    fun cachedDevices(): List<DeviceInfo>? = null
    suspend fun listDevices(): Result<List<DeviceInfo>>

    suspend fun renameDevice(deviceId: String, displayName: String): Result<Unit>

    suspend fun revokeDevice(deviceId: String): Result<Unit>

    suspend fun revokeOtherDevices(): Result<Int>

    suspend fun securityEvents(limit: Int = 50, cursor: Long? = null): Result<Pair<List<SecurityEventInfo>, Long?>>
}
