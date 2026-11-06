package com.messenger.app.data.local.signal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerIdentityEntity)

    @Query("SELECT * FROM peer_identities WHERE userId = :userId AND signalDeviceId = :signalDeviceId")
    suspend fun get(userId: String, signalDeviceId: Int): PeerIdentityEntity?

    /** Every verification row currently in Room (the live UI projection).
     *  Used only by the P1-9.1 backfill, which runs on login before Room can
     *  be cleared; Room is already account-scoped (wiped on logout), so these
     *  rows always belong to the current account. */
    @Query("SELECT * FROM peer_identities")
    suspend fun all(): List<PeerIdentityEntity>

    @Query("SELECT status FROM peer_identities WHERE userId = :userId AND signalDeviceId = :signalDeviceId")
    fun observeStatus(userId: String, signalDeviceId: Int): Flow<String?>

    @Query("SELECT * FROM peer_identities WHERE userId = :userId ORDER BY signalDeviceId ASC")
    fun observeForUser(userId: String): Flow<List<PeerIdentityEntity>>

    @Query("SELECT * FROM peer_identities WHERE userId = :userId AND status = 'CHANGED' ORDER BY signalDeviceId ASC")
    suspend fun changedForUser(userId: String): List<PeerIdentityEntity>

    @Query("SELECT COUNT(*) FROM peer_identities WHERE userId = :userId AND status = 'CHANGED'")
    suspend fun anyChangedForUser(userId: String): Int
}
