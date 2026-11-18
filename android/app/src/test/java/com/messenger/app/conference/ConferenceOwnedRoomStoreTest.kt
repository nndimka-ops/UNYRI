package com.messenger.app.conference

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ConferenceOwnedRoomStoreTest {
    @Test fun ordinaryLeaveRetainsCapabilityWhileExplicitRemovalRetiresAcrossRestartAndReplacementUsesFreshKey() {
        val invite = ConferenceInvite.fresh(origin)
        assertTrue(save(store(), invite))
        val reopened = store(); assertFalse(reopened.isRetired(invite.roomId))
        assertTrue(reopened.invitation(dto(invite))!!.sameCapability(invite))
        reopened.remove(invite.roomId)
        assertTrue(store().isRetired(invite.roomId)); assertFalse(save(store(), invite))
        val replacement = ConferenceInvite.fresh(origin)
        assertNotEquals(invite.mediaKey, replacement.mediaKey); assertNotEquals(invite.secret, replacement.secret)
        assertTrue(save(store(), replacement)); assertNotNull(store().invitation(dto(replacement)))
    }
    private val origin = "https://coco.example"
    private val user = UUID.randomUUID().toString()
    private val device = UUID.randomUUID().toString()
    private var now = 1_793_347_200L
    private var owner: ConferenceRoomOwner? = ConferenceRoomOwner(origin, user, device, "first-login")
    private var persisted: String? = null
    private var writes = 0
    private fun store() = ConferenceOwnedRoomStore({ owner }, { expected -> persisted.takeIf { owner == expected } }, { expected, value ->
        if (owner != expected) false else { persisted = value; writes++; true }
    }, { now })
    private fun dto(invite: ConferenceInvite, expires: Long = now + 10_000, userId: String = user, state: String = "active") =
        ConferenceDto(invite.roomId, "Room", userId, 30, state, expiresAt = Instant.ofEpochSecond(expires).toString())
    private fun save(store: ConferenceOwnedRoomStore, invite: ConferenceInvite, expires: Long = now + 10_000) =
        store.remember(requireNotNull(owner), invite, Instant.ofEpochSecond(expires).toString(), user)

    @Test fun durableOwnerCapabilitySurvivesControllerRecreationWithoutChangingEitherKey() {
        val invite = ConferenceInvite.fresh(origin)
        assertTrue(save(store(), invite))
        val restored = store().invitation(dto(invite))!!
        assertTrue(restored.sameCapability(invite))
        assertFalse(persisted!!.contains("password"))
    }
    @Test fun aDifferentLoginOfSameAccountAndDeviceCannotReuseTheOldRecord() {
        val invite = ConferenceInvite.fresh(origin); val store = store()
        assertTrue(save(store, invite))
        owner = owner!!.copy(loginNonce = "second-login")
        // Adapter would remove it on login; even a wrongly retained value must
        // not become authoritative for a new binding.
        assertNull(store.invitation(dto(invite)))
    }
    @Test fun logoutInvalidatesMemoryAndAStaleWriter() {
        val invite = ConferenceInvite.fresh(origin); val store = store(); val old = owner!!
        assertTrue(save(store, invite)); owner = null; persisted = null
        assertNull(store.invitation(dto(invite)))
        assertFalse(store.remember(old, invite, Instant.ofEpochSecond(now + 100).toString(), user))
        assertNull(persisted)
    }
    @Test fun anotherAccountCannotUseTheOwnersRoom() {
        val invite = ConferenceInvite.fresh(origin); val store = store()
        assertTrue(save(store, invite)); owner = owner!!.copy(userId = UUID.randomUUID().toString())
        assertNull(store.invitation(dto(invite)))
    }
    @Test fun anotherDeviceCannotUseThisDevicesStoredCapability() {
        val invite = ConferenceInvite.fresh(origin); val store = store()
        assertTrue(save(store, invite)); owner = owner!!.copy(deviceId = UUID.randomUUID().toString())
        assertNull(store.invitation(dto(invite)))
    }
    @Test fun anotherServerCannotUseTheSameRoomIdentifierOrStaleCallback() {
        val invite = ConferenceInvite.fresh(origin); val store = store(); val old = owner!!
        assertTrue(save(store, invite)); owner = owner!!.copy(origin = "https://elsewhere.example")
        assertNull(store.invitation(dto(invite)))
        assertFalse(store.remember(old, invite, Instant.ofEpochSecond(now + 100).toString(), user))
    }
    @Test fun accountChangeDuringWriteDoesNotExposeThePendingEntryFromMemory() {
        val invite = ConferenceInvite.fresh(origin)
        val store = ConferenceOwnedRoomStore({ owner }, { null }, { _, _ -> owner = null; false }, { now })
        assertFalse(save(store, invite)); assertNull(store.invitation(dto(invite)))
    }
    @Test fun rejectedPersistenceDoesNotPretendThatReturnIsAvailable() {
        val invite = ConferenceInvite.fresh(origin)
        val store = ConferenceOwnedRoomStore({ owner }, { null }, { _, _ -> false }, { now })
        assertFalse(save(store, invite)); assertNull(store.invitation(dto(invite)))
    }
    @Test fun expiryRemovesAvailabilityAndCannotBeExtendedByServerMetadata() {
        val invite = ConferenceInvite.fresh(origin); val store = store()
        assertTrue(save(store, invite, now + 10))
        assertNull(store.invitation(dto(invite, now + 500)))
        now += 11
        assertNull(store.invitation(dto(invite, now - 1)))
    }
    @Test fun fullDayRoomRemainsRecoverableBeyondTheOldFourHourLimit() {
        val invite = ConferenceInvite.fresh(origin)
        val deadline = now + 86_400
        assertTrue(save(store(), invite, deadline))
        now += 5 * 60 * 60
        val restored = store().invitation(dto(invite, deadline))
        assertNotNull(restored)
        assertTrue(invite.sameCapability(requireNotNull(restored)))
        now = deadline
        assertNull(store().invitation(dto(invite, deadline)))
    }
    @Test fun endedAndExplicitlyRemovedRoomsDoNotOfferReturn() {
        val a = ConferenceInvite.fresh(origin); val b = ConferenceInvite.fresh(origin); val store = store()
        assertTrue(save(store, a)); assertTrue(save(store, b))
        assertNull(store.invitation(dto(a, state = "ended")))
        store.remove(b.roomId)
        assertNull(store().invitation(dto(a))); assertNull(store().invitation(dto(b)))
    }
    @Test fun noAuthorityIsInventedForUnknownRoomOrMissingInvitation() {
        assertNull(store().invitation(dto(ConferenceInvite.fresh(origin))))
    }
    @Test fun explicitEndBeforeALateConnectWriterCannotRestoreTheCapability() {
        val invite = ConferenceInvite.fresh(origin); val store = store()
        store.remove(invite.roomId)
        assertFalse(save(store, invite))
        assertNull(store.invitation(dto(invite)))
    }
    @Test fun onlyThreeLiveRoomsAreKeptAndTheNewKeyIsNotRecreated() {
        val rooms = (0..3).map { ConferenceInvite.fresh(origin) }; val store = store()
        rooms.forEach { assertTrue(save(store, it)) }
        val restored = store()
        assertNull(restored.invitation(dto(rooms[0])))
        rooms.drop(1).forEach { assertTrue(restored.invitation(dto(it))!!.sameCapability(it)) }
    }
    @Test fun foreignInviteNonOwnerAndUnboundedExpiryAreNotPersisted() {
        val store = store(); val invite = ConferenceInvite.fresh(origin)
        assertFalse(save(store, ConferenceInvite.fresh("https://elsewhere.example")))
        assertFalse(store.remember(owner!!, invite, Instant.ofEpochSecond(now + 100).toString(), "someone-else"))
        assertFalse(save(store, invite, now + 100_000)); assertFalse(save(store, invite, now))
        assertEquals(0, writes)
    }
    @Test fun malformedOrOversizedStorageFailsClosedWithoutChangingAccountData() {
        val invite = ConferenceInvite.fresh(origin)
        for (raw in listOf("not json", "x".repeat(12_289), "{\"version\":999,\"entries\":[]}")) {
            persisted = raw
            assertNull(store().invitation(dto(invite)))
            assertEquals(raw, persisted)
        }
    }
}
