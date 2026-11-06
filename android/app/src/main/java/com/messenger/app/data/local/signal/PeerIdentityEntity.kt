package com.messenger.app.data.local.signal

import androidx.room.Entity

/**
 * Local trust state for one remote Signal device (Этап L.24-L.41).
 *
 * Status machine (server-independent, purely local):
 *  - `UNVERIFIED`: trust-on-first-use - a session was established and the
 *    identity is pinned, but the user has never compared safety numbers.
 *  - `VERIFIED`: the user manually compared safety numbers out-of-band.
 *  - `CHANGED`: the remote identity key changed since it was pinned
 *    (reinstall / device compromise) - sending is BLOCKED until the user
 *    accepts the new key (-> UNVERIFIED) or keeps it blocked.
 *
 * `safetyNumber` is the cached numeric fingerprint so the UI can display it
 * without recomputing (which requires the remote identity key + a fresh
 * session). `verifiedAt` is null unless the user verified this device.
 */
@Entity(tableName = "peer_identities", primaryKeys = ["userId", "signalDeviceId"])
data class PeerIdentityEntity(
    val userId: String,
    val signalDeviceId: Int,
    val status: String,
    val safetyNumber: String?,
    val identityKeyB64: String?,
    val verifiedAt: String?,
)
