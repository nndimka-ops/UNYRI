package com.messenger.app.push

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.ActiveChatTracker
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual cold launch intents, not onNewIntent substitutes; uses only the owner's test pair. */
@RunWith(AndroidJUnit4::class)
class ExternalNavigationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun wakeDisplay() = wakeRoutingTestDisplay()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<MessengerApp>()
    private data class Fixture(val target: NotificationTapTarget, val text: String)
    private fun fixture() = runBlocking {
        val owner = withTimeout(15_000) { app.authRepository.authState.filterIsInstance<AuthSessionState.LoggedIn>().first() }.user
        assumeTrue(owner.username in setOf("test", "Dima2"))
        val peer = if (owner.username == "test") "Dima2" else "test"
        val chat = withTimeout(15_000) { app.chatRepository.chats.first { rows -> rows.any { it.otherUser?.username == peer } } }
            .first { it.otherUser?.username == peer }
        val db = MessengerDatabase.create(app)
        val row = try { db.messageDao().observeMessages(chat.chatId).first().lastOrNull {
            it.serverId != null && it.kind == "content" && it.text.startsWith("parity-") && !it.hiddenLocally
        } } finally { db.close() }
        assumeTrue(row != null)
        Fixture(NotificationTapTarget(app.accountWorkspaces.activeKey, owner.id, app.authRepository.currentDeviceId()!!,
            chat.chatId, row!!.serverId!!), row.text)
    }
    private fun attack(target: NotificationTapTarget, uppercase: Boolean = false): Intent {
        val prefix = if (uppercase) "ANDROID-APP://ANDROIDX.NAVIGATION/CHAT/" else "android-app://androidx.navigation/chat/"
        return Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("$prefix${target.chat}?targetMessageId=${target.message}"))
    }
    private fun launch(intent: Intent): MainActivity {
        val activity = instrumentation.startActivitySync(intent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.runOnMainSync { keepRoutingTestDisplay(activity) }
        return activity
    }
    private fun finish(activity: MainActivity) { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    private fun assertRoot(activity: MainActivity) {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(app.getString(R.string.navigation_menu)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertNull("an external deep link opened a chat", ActiveChatTracker.activeChatId.value)
        instrumentation.runOnMainSync { assertNull(activity.intent.data); assertFalse(activity.intent.hasExtra("android-support-nav:controller:deepLinkIds")) }
    }
    private fun assertTarget(fixture: Fixture) {
        compose.waitUntil(15_000) { ActiveChatTracker.activeChatId.value == fixture.target.chat }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(fixture.text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(fixture.text, substring = true)[0].assertIsDisplayed()
    }
    private fun profileIntent(): Intent {
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
        assumeTrue(owner?.username in setOf("test", "Dima2"))
        val username = if (owner!!.username == "test") "Dima2" else "test"
        return Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("unyri://user/$username"))
    }
    private fun forgedProfileExtras(raw: Intent): Intent = raw
        .putExtra("notification_workspace", app.accountWorkspaces.accounts().firstOrNull {
            it.key != app.accountWorkspaces.activeKey }?.key ?: "other-account")
        .putExtra("notification_owner_id", "11111111-1111-4111-8111-111111111111")
        .putExtra("notification_device_id", "22222222-2222-4222-8222-222222222222")
        .putExtra("notification_chat_id", "33333333-3333-4333-8333-333333333333")
        .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, 42))
        .putExtra("android-support-nav:controller:deepLinkExtras", Bundle().apply { putString("chatId", "attack") })

    private fun assertProfile(activity: MainActivity, workspace: String, owner: String?) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("profile-link-card").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(workspace, app.accountWorkspaces.activeKey)
        assertEquals(owner, app.privacyRepository.ownerKey())
        assertNull("a profile link must never open a chat", ActiveChatTracker.activeChatId.value)
        instrumentation.runOnMainSync {
            assertNull(activity.intent.data)
            assertTrue(activity.intent.extras == null || activity.intent.extras!!.isEmpty)
        }
    }

    @Test fun coldProfileLinkWithForgedRoutingExtrasOpensOnlyTheCurrentAccountProfileCard() {
        val input = forgedProfileExtras(profileIntent())
        val workspace = app.accountWorkspaces.activeKey
        val owner = app.privacyRepository.ownerKey()
        val activity = launch(input)
        try { assertProfile(activity, workspace, owner) } finally { finish(activity) }
    }

    @Test fun warmProfileLinkOpensOnceAndDismissedCardDoesNotReplayOnRecreation() {
        val input = forgedProfileExtras(profileIntent())
        val workspace = app.accountWorkspaces.activeKey
        val owner = app.privacyRepository.ownerKey()
        var activity = launch(Intent(app, MainActivity::class.java))
        try {
            assertRoot(activity)
            app.startActivity(input.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            assertProfile(activity, workspace, owner)
            compose.onNodeWithText(app.getString(R.string.dismiss)).performClick()
            compose.waitUntil { compose.onAllNodesWithTag("profile-link-card").fetchSemanticsNodes().isEmpty() }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as MainActivity
                instrumentation.runOnMainSync { keepRoutingTestDisplay(activity) }
            } finally { instrumentation.removeMonitor(monitor) }
            assertRoot(activity)
            assertTrue(compose.onAllNodesWithTag("profile-link-card").fetchSemanticsNodes().isEmpty())
            assertEquals(workspace, app.accountWorkspaces.activeKey)
            assertEquals(owner, app.privacyRepository.ownerKey())
        } finally { finish(activity) }
    }

    @Test fun craftedProfileQueryCannotOpenAChatOrSwitchAccount() {
        val input = profileIntent().apply { data = Uri.parse("${data}?chatId=attack&account=other") }
        val workspace = app.accountWorkspaces.activeKey
        val owner = app.privacyRepository.ownerKey()
        val activity = launch(forgedProfileExtras(input))
        try {
            assertRoot(activity)
            assertTrue(compose.onAllNodesWithTag("profile-link-card").fetchSemanticsNodes().isEmpty())
            assertEquals(workspace, app.accountWorkspaces.activeKey)
            assertEquals(owner, app.privacyRepository.ownerKey())
        } finally { finish(activity) }
    }
    @Test fun exactSyntheticChatDeepLinkCannotColdNavigate() {
        val f = fixture(); val activity = launch(attack(f.target))
        try { assertRoot(activity) } finally { finish(activity) }
    }
    @Test fun uppercaseSyntheticChatDeepLinkCannotColdNavigate() {
        val f = fixture(); val activity = launch(attack(f.target, uppercase = true))
        try { assertRoot(activity) } finally { finish(activity) }
    }
    @Test fun frameworkIdsAndBothArgumentRepresentationsCannotColdNavigate() {
        val f = fixture()
        val routeId = "android-app://androidx.navigation/chat/{chatId}?targetMessageId={targetMessageId}".hashCode()
        val args = Bundle().apply { putString("chatId", f.target.chat); putString("targetMessageId", f.target.message) }
        val raw = Intent(app, MainActivity::class.java)
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, routeId))
            .putExtra("android-support-nav:controller:deepLinkArgs", arrayOf(Bundle(), args))
            .putExtra("android-support-nav:controller:deepLinkExtras", args)
            .putExtra("android-support-nav:controller:deepLinkIntent", attack(f.target))
        val activity = launch(raw)
        try { assertRoot(activity) } finally { finish(activity) }
    }
    @Test fun verifiedNotificationWinsOverHostileFrameworkData() {
        val f = fixture()
        val raw = NotificationIntents.open(app, f.target)
            .setData(Uri.parse("android-app://androidx.navigation/chat/55555555-5555-4555-8555-555555555555"))
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, 42))
        val activity = launch(raw)
        try { assertTarget(f); instrumentation.runOnMainSync { assertNull(activity.intent.data) } }
        finally { finish(activity) }
    }
    @Test fun hostileWarmLaunchAndRecreationPreserveOnlyTheLegitimateBackStack() {
        val f = fixture(); var activity = launch(NotificationIntents.open(app, f.target))
        try {
            assertTarget(f)
            val previous = activity.intent
            app.startActivity(attack(f.target.copy(chat = "55555555-5555-4555-8555-555555555555"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            compose.waitUntil(15_000) {
                var delivered = false
                instrumentation.runOnMainSync { delivered = activity.intent !== previous }
                delivered
            }
            compose.waitForIdle(); assertTarget(f)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as MainActivity
                instrumentation.runOnMainSync { keepRoutingTestDisplay(activity) }
            } finally { instrumentation.removeMonitor(monitor) }
            compose.waitForIdle(); assertTarget(f)
        } finally { finish(activity) }
    }
}
