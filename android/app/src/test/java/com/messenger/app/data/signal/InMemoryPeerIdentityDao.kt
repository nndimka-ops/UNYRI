package com.messenger.app.data.signal

import com.messenger.app.data.local.signal.PeerIdentityDao
import com.messenger.app.data.local.signal.PeerIdentityEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared in-memory [PeerIdentityDao] so the real [IdentityVerificationManager]
 *  and [SignalSessionManager] identity flows can be exercised on the JVM (no
 *  Room, no Android). */
internal class InMemoryPeerIdentityDao : PeerIdentityDao {
    private val map = mutableMapOf<String, PeerIdentityEntity>()
    private val state = MutableStateFlow(mapOf<String, PeerIdentityEntity>())

    private fun key(userId: String, signalDeviceId: Int) = "$userId:$signalDeviceId"

    private fun publish() {
        state.value = map.toMap()
    }

    override suspend fun upsert(entity: PeerIdentityEntity) {
        map[key(entity.userId, entity.signalDeviceId)] = entity
        publish()
    }

    override suspend fun get(userId: String, signalDeviceId: Int): PeerIdentityEntity? = map[key(userId, signalDeviceId)]

    override suspend fun all(): List<PeerIdentityEntity> = map.values.toList()

    override fun observeStatus(userId: String, signalDeviceId: Int): Flow<String?> =
        MutableStateFlow(map[key(userId, signalDeviceId)]?.status)

    override fun observeForUser(userId: String): Flow<List<PeerIdentityEntity>> =
        MutableStateFlow(map.values.filter { it.userId == userId }.sortedBy { it.signalDeviceId })

    override suspend fun changedForUser(userId: String): List<PeerIdentityEntity> =
        map.values.filter { it.userId == userId && it.status == IDENTITY_CHANGED }.sortedBy { it.signalDeviceId }

    override suspend fun anyChangedForUser(userId: String): Int =
        map.values.count { it.userId == userId && it.status == IDENTITY_CHANGED }
}
