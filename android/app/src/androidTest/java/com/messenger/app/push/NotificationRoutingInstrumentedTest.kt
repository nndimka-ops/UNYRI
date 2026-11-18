package com.messenger.app.push

import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.content.Intent
import android.os.Parcel
import com.messenger.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationRoutingInstrumentedTest {
    private val target = NotificationTapTarget("legacy", "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444")
    @Test fun realIntentRoundTripRetainsExactMessageDeviceAndAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = NotificationIntents.open(context, target)
        assertEquals(target, NotificationIntents.read(context, intent))
        intent.putExtra(MessagePushHandlerImpl.EXTRA_DEVICE_ID, "wrong-device")
        assertNull(NotificationIntents.read(context, intent))
    }
    @Test fun forgedStructuralLegacyAndWrongTypedExtrasAreRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val forged = NotificationIntents.open(context, target).apply { removeExtra(NotificationIntents.EXTRA_PROOF) }
        assertNull(NotificationIntents.read(context, forged))
        forged.removeExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID)
        forged.putExtra("reminder_message_id", target.message)
        forged.putExtra(ChatReminderReceiver.EXTRA_WORKSPACE, target.workspace)
        assertNull(NotificationIntents.read(context, forged))
        forged.putExtra(NotificationIntents.EXTRA_PROOF, "not a capability")
        assertNull(NotificationIntents.read(context, forged))
        assertTrue(context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0).exported)
        assertFalse(context.packageManager.getReceiverInfo(android.content.ComponentName(context, NotificationRouteProofReceiver::class.java), 0).exported)
    }
    @Test fun capabilityBindsEveryCanonicalTargetField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val other = "55555555-5555-4555-8555-555555555555"
        for (extra in listOf(MessagePushHandlerImpl.EXTRA_WORKSPACE, MessagePushHandlerImpl.EXTRA_OWNER_ID,
            MessagePushHandlerImpl.EXTRA_DEVICE_ID, MessagePushHandlerImpl.EXTRA_CHAT_ID, MessagePushHandlerImpl.EXTRA_MESSAGE_ID)) {
            val modified = NotificationIntents.open(context, target).putExtra(extra, other)
            assertNull(extra, NotificationIntents.read(context, modified))
        }
    }
    @Test fun unrelatedAndCanceledCapabilitiesFailWithoutMintingNewProof() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = NotificationIntents.open(context, target)
        @Suppress("DEPRECATION") val proof = intent.getParcelableExtra<PendingIntent>(NotificationIntents.EXTRA_PROOF)!!
        val unrelated = PendingIntent.getActivity(context, 117, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            assertNull(NotificationIntents.read(context, Intent(intent).putExtra(NotificationIntents.EXTRA_PROOF, unrelated)))
            proof.cancel()
            assertNull(NotificationIntents.read(context, intent))
            assertNull(NotificationIntents.read(context, intent))
        } finally { unrelated.cancel() }
    }
    @Test fun binderCapabilitySurvivesParcelAndAccountSwitchExtrasHandoffWithoutOuterUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initial = NotificationIntents.open(context, target)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(initial.extras)
            parcel.setDataPosition(0)
            val handoff = Intent(context, MainActivity::class.java).putExtras(parcel.readBundle(PendingIntent::class.java.classLoader)!!)
            assertNull(handoff.data)
            assertEquals(target, NotificationIntents.read(context, handoff))
            assertEquals(target, NotificationIntents.read(context, Intent(handoff)))
            handoff.data = android.net.Uri.parse(ChatReminderWorkspacePolicy.uri(target.workspace, target.owner, target.chat, target.message))
            assertEquals(target, NotificationIntents.read(context, handoff))
        } finally { parcel.recycle() }
    }
    @Test fun twoOutstandingMessagesKeepIndependentProofWhileOuterIntentUpdatesLatest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = NotificationIntents.open(context, target)
        val nextTarget = target.copy(message = "55555555-5555-4555-8555-555555555555")
        val second = NotificationIntents.open(context, nextTarget)
        assertEquals(target, NotificationIntents.read(context, first))
        assertEquals(nextTarget, NotificationIntents.read(context, second))
        assertTrue(first.filterEquals(second))
    }
    @Test fun accountAndChatPendingIntentsAreIsolatedWhileSameChatUpdatesLatestTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun pending(value: NotificationTapTarget) = PendingIntent.getActivity(context, 0,
            NotificationIntents.open(context, value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val first = pending(target)
        val updated = pending(target.copy(message = "55555555-5555-4555-8555-555555555555"))
        val other = pending(target.copy(workspace = "66666666-6666-4666-8666-666666666666"))
        try { assertEquals(first, updated); assertNotEquals(first, other) }
        finally { first.cancel(); other.cancel() }
    }
}
