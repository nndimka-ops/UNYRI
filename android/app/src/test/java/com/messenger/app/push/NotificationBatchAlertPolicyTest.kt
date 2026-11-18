package com.messenger.app.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationBatchAlertPolicyTest {
    private val session = NotificationBatchSession("https://server.example", "owner", 7)
    private fun key(chat: String = "chat", sender: String = "peer", album: String = "album") = NotificationBatchKey(chat, sender, album)
    private fun event(id: String = "server-1") = PushEvent.NewMessage("chat", id, "42", "owner")
    private fun row(id: String = "server-1", album: String? = "album") = NotificationMessageMetadata(
        id, "chat", "peer", 42, album, "content", "delivered", false, false)

    @Test fun tenMembersProduceOnlyOneAlert() {
        val ledger = NotificationBatchAlertLedger()
        repeat(10) { index ->
            assertEquals(index == 0, ledger.shouldAlert("message-$index", key(), session))
            ledger.recordPosted("message-$index", key(), session)
        }
    }
    @Test fun independentMessagesNeverShareBatchSilence() {
        val ledger = NotificationBatchAlertLedger()
        ledger.recordPosted("album-first", key(), session)
        repeat(10) { index ->
            assertTrue(ledger.shouldAlert("text-$index", null, session))
            ledger.recordPosted("text-$index", null, session)
        }
    }
    @Test fun newBatchOtherChatAndOtherSenderRemainIndependent() {
        val ledger = NotificationBatchAlertLedger(); ledger.recordPosted("first", key(), session)
        assertTrue(ledger.shouldAlert("second", key(album = "other"), session))
        assertTrue(ledger.shouldAlert("third", key(chat = "other"), session))
        assertTrue(ledger.shouldAlert("fourth", key(sender = "other"), session))
    }
    @Test fun ownerEpochAndOriginFenceEveryBatch() {
        for (next in listOf(session.copy(owner = "other"), session.copy(epoch = 8), session.copy(server = "https://other.example"))) {
            val ledger = NotificationBatchAlertLedger(); ledger.recordPosted("first", key(), session)
            assertTrue(ledger.shouldAlert("next", key(), next))
        }
    }
    @Test fun aFailedPostDoesNotConsumeTheFirstBatchAlert() {
        val ledger = NotificationBatchAlertLedger()
        assertTrue(ledger.shouldAlert("attempt", key(), session))
        // No recordPosted: permission denied or the OS post failed.
        assertTrue(ledger.shouldAlert("retry", key(), session))
    }
    @Test fun aLateResolvedDuplicateBindsItsPreviouslyGenericWake() {
        val ledger = NotificationBatchAlertLedger()
        ledger.recordPosted("first", null, session)
        ledger.learnResolved("first", key(), session)
        assertFalse(ledger.shouldAlert("second", key(), session))
        assertFalse(ledger.shouldAlert("first", key(), session))
    }
    @Test fun resolvingAnUnpostedMessageDoesNotSilenceFutureMembers() {
        val ledger = NotificationBatchAlertLedger()
        ledger.learnResolved("not-posted", key(), session)
        assertTrue(ledger.shouldAlert("first-visible", key(), session))
    }
    @Test fun storageIsBoundedWithoutTimeBasedBatchGuesses() {
        val ledger = NotificationBatchAlertLedger(capacity = 2)
        for (id in listOf("a", "b", "c")) ledger.recordPosted(id, key(album = id), session)
        assertFalse(ledger.shouldAlert("c2", key(album = "c"), session))
        assertTrue(ledger.shouldAlert("a2", key(album = "a"), session))
        ledger.clear(); assertTrue(ledger.shouldAlert("c3", key(album = "c"), session))
    }
    @Test fun onlyNonblankBoundedAlbumMetadataGroups() {
        assertNull(row(album = null).batch()); assertNull(row(album = "  ").batch())
        assertNull(row(album = "a".repeat(257)).batch())
        assertNotNull(row().batch())
    }
    @Test fun localPersistedRowNeedsNoNetworkOrWait() = runTest {
        var requests = 0
        val resolver = NotificationMetadataResolver({ session }, { row() }, { requests++ })
        assertTrue(resolver.resolve(event(), session) is NotificationMetadataResolution.Readable)
        assertEquals(0, requests); assertEquals(0L, currentTime)
    }
    @Test fun coldWakeUsesOneSharedSyncTriggerWithoutHoldingTheAlertForSync() = runTest {
        var requests = 0
        val resolver = NotificationMetadataResolver({ session }, { if (currentTime >= 200) row() else null }, { requests++ })
        val result = resolver.resolve(event(), session)
        assertEquals(NotificationMetadataResolution.Unresolved, result)
        assertEquals(1, requests); assertEquals(0L, currentTime)
    }
    @Test fun unresolvedColdWakeHasBoundedGenericFallback() = runTest {
        var requests = 0
        val resolver = NotificationMetadataResolver({ session }, { null }, { requests++ })
        assertEquals(NotificationMetadataResolution.Unresolved, resolver.resolve(event(), session))
        assertEquals(0L, currentTime); assertEquals(1, requests)
    }
    @Test fun evenTheInitialDatabaseReadIsInsideTheDeadline() = runTest {
        val resolver = NotificationMetadataResolver({ session }, { delay(10_000); row() }, {})
        assertEquals(NotificationMetadataResolution.Unresolved, resolver.resolve(event(), session))
        assertEquals(2500L, currentTime)
    }
    @Test fun accountSwitchDuringLookupNeverPostsTheOldWake() = runTest {
        var current = session
        val resolver = NotificationMetadataResolver({ current }, { delay(100); current = session.copy(owner = "other"); row() }, {})
        assertEquals(NotificationMetadataResolution.Suppress, resolver.resolve(event(), session))
    }
    @Test fun epochAndOriginChangesAbortTheWaitingLookup() = runTest {
        for (changed in listOf(session.copy(epoch = 8), session.copy(server = "https://other.example"))) {
            var current = session
            val resolver = NotificationMetadataResolver({ current }, { null }, { current = changed })
            assertEquals(NotificationMetadataResolution.Suppress, resolver.resolve(event(), session))
        }
    }
    @Test fun otherRecipientIsSuppressedBeforeAnyDatabaseRead() = runTest {
        var reads = 0
        val resolver = NotificationMetadataResolver({ session }, { reads++; row() }, {})
        assertEquals(NotificationMetadataResolution.Suppress, resolver.resolve(event().copy(userId = "other"), session))
        assertEquals(0, reads)
    }
    @Test fun hiddenDeletedUnreadableOwnAndMismatchedRowsNeverGroupOrAlert() = runTest {
        val invalid = listOf(row().copy(hidden = true), row().copy(deleted = true), row().copy(status = "decrypt_failed"),
            row().copy(status = "identity_changed"), row().copy(senderId = "owner"), row().copy(serverId = "other"),
            row().copy(chatId = "other"), row().copy(sequence = 43), row().copy(kind = "reaction"), row().copy(sequence = null))
        for (candidate in invalid) {
            var requests = 0
            val resolver = NotificationMetadataResolver({ session }, { candidate }, { requests++ })
            assertEquals(NotificationMetadataResolution.Suppress, resolver.resolve(event(), session))
            assertEquals(0, requests)
        }
    }
    @Test fun transientLocalReadFailurePreservesBoundedFallback() = runTest {
        val resolver = NotificationMetadataResolver({ session }, { error("Unavailable local reader") }, {})
        assertEquals(NotificationMetadataResolution.Unresolved, resolver.resolve(event(), session))
        assertEquals(0L, currentTime)
    }
    @Test fun callerCancellationNeverTurnsIntoALateGenericAlert() = runTest {
        var returned = false
        val resolver = NotificationMetadataResolver({ session }, { delay(10_000); null }, {})
        val job = launch { resolver.resolve(event(), session); returned = true }
        delay(200); job.cancelAndJoin(); delay(3000)
        assertFalse(returned)
    }
}
