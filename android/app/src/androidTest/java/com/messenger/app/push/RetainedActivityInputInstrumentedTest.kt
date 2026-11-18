package com.messenger.app.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.MainActivity
import com.messenger.app.security.RetainedActivityInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetainedActivityInputInstrumentedTest {
    private val target = NotificationTapTarget("legacy", "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444")
    @Test fun rawUrisAndEveryFrameworkRoutingRepresentationAreAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (uri in listOf("android-app://androidx.navigation/chat/${target.chat}?targetMessageId=${target.message}",
            "ANDROID-APP://ANDROIDX.NAVIGATION/CHAT/${target.chat}?targetMessageId=${target.message}")) {
            val raw = Intent(context, MainActivity::class.java).setData(Uri.parse(uri))
                .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, 42))
                .putExtra("android-support-nav:controller:deepLinkArgs", arrayOf(Bundle()))
                .putExtra("android-support-nav:controller:deepLinkExtras", Bundle().apply { putString("chatId", target.chat) })
                .putExtra("android-support-nav:controller:deepLinkIntent", Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                .putExtra("android-support-nav:controller:deepLinkHandled", false)
            val retained = RetainedActivityInput.create(context, raw)
            assertNull(retained.data); assertNull(retained.selector); assertNull(retained.clipData)
            assertTrue(retained.extras == null || retained.extras!!.isEmpty)
        }
    }
    @Test fun pendingVerifiedProofSurvivesCanonicalRetentionAndUnverifiedWarmInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = NotificationIntents.open(context, target).putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, 1))
        val retained = RetainedActivityInput.create(context, raw)
        assertEquals(target, NotificationIntents.read(context, retained))
        assertNull(retained.data); assertFalse(retained.hasExtra("android-support-nav:controller:deepLinkIds"))
        val warm = RetainedActivityInput.create(context, Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/chat/attack")), retained)
        assertEquals(target, NotificationIntents.read(context, warm))
        assertNull(warm.data)
    }
    @Test fun profileUriHasOnlyATypedProfileDestinationAndCannotSmuggleAccountOrChatExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("unyri://user/boris"))
            .putExtra("notification_workspace", "other-account")
            .putExtra("notification_owner_id", target.owner)
            .putExtra("notification_device_id", target.device)
            .putExtra("notification_chat_id", target.chat)
            .putExtra("notification_message_id", target.message)
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, 42))
            .putExtra("android-support-nav:controller:deepLinkIntent",
                Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/chat/${target.chat}")))
        val input = RetainedActivityInput.capture(context, raw)
        assertEquals("boris", input.profile?.username)
        assertNull(input.retained.data)
        assertTrue(input.retained.extras == null || input.retained.extras!!.isEmpty)
        assertNull(NotificationIntents.read(context, input.retained))
    }
    @Test fun profileLinksWithChatOrAccountPayloadsHaveNoAllowedDestination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (uri in listOf("unyri://user/boris?chatId=${target.chat}&account=other",
            "unyri://user/boris#account=other", "unyri://user/boris/chat/${target.chat}")) {
            val raw = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).putExtra("notification_workspace", "other")
                .putExtra("notification_chat_id", target.chat)
            val input = RetainedActivityInput.capture(context, raw)
            assertNull(input.profile); assertNull(input.retained.data)
            assertTrue(input.retained.extras == null || input.retained.extras!!.isEmpty)
            assertNull(NotificationIntents.read(context, input.retained))
        }
    }
    @Test fun safeSharedStreamsSurviveWithoutFrameworkStateOrUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stream = Uri.parse("content://com.messenger.app.test/shared/image")
        val raw = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, stream)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setDataAndType(Uri.parse("android-app://androidx.navigation/chat/attack"), "image/png")
            .putExtra("android-support-nav:controller:deepLinkExtras", Bundle())
        val retained = RetainedActivityInput.create(context, raw)
        assertEquals(Intent.ACTION_SEND, retained.action); assertEquals("image/png", retained.type)
        @Suppress("DEPRECATION") val actual = retained.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(stream, actual); assertNull(retained.data)
        assertFalse(retained.hasExtra("android-support-nav:controller:deepLinkExtras"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, retained.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
