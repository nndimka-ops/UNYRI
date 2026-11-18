package com.messenger.app.push

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.ActiveChatTracker
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the owner's signed-in test device; reads existing rows, sends nothing. */
@RunWith(AndroidJUnit4::class)
class NotificationChatLandingInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun wakeDisplay() = wakeRoutingTestDisplay()
    @Test fun tapLandsInTheAddressedChatAndResolvesServerMessageId() = verifyLanding(false)
    @Test fun warmTapReplacesAnotherChatsViewModel() = verifyLanding(true)
    @Test fun forgedWarmIntentsCannotNavigateBeforeLegitimateTap() = verifyLanding(true, checkForgery = true)
    @Test fun immutableNotificationTapIgnoresExternalFillInExtras() = verifyLanding(true, pendingTap = true)
    @Test fun sameChatNotificationReplacesRestoredHistoryPosition() = verifyLanding(true, pendingTap = true, sameChat = true)
    @Test fun repeatedSameMessagePendingIntentJumpsAgainAfterUserScrollsAway() = verifyLanding(false, repeatTarget = true)
    @Test fun notificationRearmsAnAlreadyVisitedClientIdWhenItsServerIdResolvesToTheSameMessage() =
        verifyLanding(false, repeatTarget = true, initialClientTarget = true)

    private fun verifyLanding(warm: Boolean, checkForgery: Boolean = false, pendingTap: Boolean = false,
        sameChat: Boolean = false, repeatTarget: Boolean = false, initialClientTarget: Boolean = false) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        val device = app.authRepository.currentDeviceId()
        assumeTrue(owner != null && device != null)
        val db = MessengerDatabase.create(app)
        val fixtures = try {
            val candidates = mutableListOf<List<com.messenger.app.data.local.message.MessageEntity>>()
            for (chat in db.messageDao().incomingChatIds(owner!!)) {
                val projected = app.messageRepository.observeMessages(chat).first()
                    .filter { it.directFile == null && it.queuedAttachment == null && it.conferenceInvitation == null }
                    .associate { it.clientMessageId to it.text }
                val rows = db.messageDao().observeMessages(chat).first().filter {
                    projected[it.clientMessageId] == it.text && isPlainTextFixture(it) &&
                        db.attachmentDao().findByClientMessageId(it.clientMessageId) == null
                }
                if (rows.isNotEmpty()) candidates.add(rows)
            }
            candidates.sortedWith(compareByDescending<List<com.messenger.app.data.local.message.MessageEntity>> {
                it.count { row -> isAcceptanceFixture(row) }
            }.thenByDescending { it.size })
        } finally { db.close() }
        val row = fixtures.firstOrNull()?.let(::preferAcceptanceFixture)
        assumeTrue(row != null)
        val target = NotificationTapTarget(app.accountWorkspaces.activeKey, owner!!, device!!, row!!.chatId, row.serverId!!)
        var initialText = row.text
        val initial = if (warm) {
            val other = fixtures.firstOrNull { rows ->
                if (sameChat) rows.first().chatId == target.chat else rows.first().chatId != target.chat
            }?.filter { it.clientMessageId != row.clientMessageId && it.text != row.text }?.let(::preferAcceptanceFixture)
            assumeTrue(other != null)
            initialText = other!!.text
            NotificationIntents.open(app, target.copy(chat = other!!.chatId, message = other.serverId!!))
        } else NotificationIntents.open(app, if (initialClientTarget) target.copy(message = row.clientMessageId) else target)
        // The activity consumes/removes routing extras once navigation completes.
        // The retained ingress deliberately has no URI. Keep ActivityScenario's
        // lifecycle filter neutral too; separate cold attack tests use the exact URI.
        initial.data = null
        // Own the initial task: SINGLE_TOP can otherwise reuse a previous test's
        // Activity after that test's Compose rule has disposed its recomposer.
        // Subsequent notification taps retain their real production launch flags.
        initial.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        val initialChat = initial.getStringExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID)
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            scenario.onActivity(::keepRoutingTestDisplay)
            if (warm) {
                compose.waitUntil(15_000) { ActiveChatTracker.activeChatId.value == initialChat }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat-timeline").fetchSemanticsNodes().isNotEmpty() }
                if (sameChat) compose.waitUntil(15_000) { compose.onAllNodesWithText(initialText, substring = true).fetchSemanticsNodes().isNotEmpty() }
                else compose.onNodeWithTag("chat-timeline").assertIsDisplayed()
                if (sameChat) {
                    scenario.recreate()
                    scenario.onActivity(::keepRoutingTestDisplay)
                    compose.waitUntil(15_000) { compose.onAllNodesWithText(initialText, substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
                if (checkForgery) for (legacy in listOf(false, true)) {
                    val forged = NotificationIntents.open(app, target).apply { removeExtra(NotificationIntents.EXTRA_PROOF) }
                    if (legacy) {
                        forged.removeExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID)
                        forged.putExtra("reminder_message_id", target.message)
                        forged.putExtra(ChatReminderReceiver.EXTRA_WORKSPACE, target.workspace)
                    }
                    var activity: MainActivity? = null
                    var previous: android.content.Intent? = null
                    scenario.onActivity { activity = it; previous = it.intent; app.startActivity(forged) }
                    compose.waitUntil(15_000) {
                        var delivered = false
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            delivered = activity!!.intent !== previous
                        }
                        delivered
                    }
                    compose.waitForIdle()
                    // This tracker is read visibility, not navigation state: a private
                    // content gate may clear it while the same chat remains displayed.
                    compose.onNodeWithTag("chat-timeline").assertIsDisplayed()
                    org.junit.Assert.assertTrue(ActiveChatTracker.activeChatId.value in setOf(null, initialChat))
                    org.junit.Assert.assertEquals(target.workspace, app.accountWorkspaces.activeKey)
                }
                if (pendingTap) {
                    val pending = android.app.PendingIntent.getActivity(app, 271,
                        NotificationIntents.open(app, target), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    try { pending.send(app, 0, android.content.Intent()
                        .putExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID, initialChat)
                        .putExtra(MessagePushHandlerImpl.EXTRA_MESSAGE_ID, "55555555-5555-4555-8555-555555555555")) }
                    finally { pending.cancel() }
                } else scenario.onActivity {
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .callActivityOnNewIntent(it, NotificationIntents.open(app, target))
                }
            }
            compose.waitUntil(15_000) { ActiveChatTracker.activeChatId.value == target.chat }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(row.text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(row.text, substring = true)[0].assertIsDisplayed()
            if (repeatTarget) {
                // Reuse the exact immutable PendingIntent: freshness must be assigned
                // on verified receipt, rather than when the notification was created.
                val pending = android.app.PendingIntent.getActivity(app, 272, NotificationIntents.open(app, target),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                try {
                    repeat(2) {
                        compose.waitForIdle()
                        compose.onNodeWithTag("chat-timeline").performScrollToIndex(0)
                        compose.onNodeWithText(row.text, substring = true).assertIsNotDisplayed()
                        pending.send()
                        compose.waitUntil(15_000) { compose.onAllNodesWithText(row.text, substring = true).fetchSemanticsNodes().isNotEmpty() }
                        compose.onAllNodesWithText(row.text, substring = true)[0].assertIsDisplayed()
                    }
                } finally { pending.cancel() }
            }
            if (warm || repeatTarget) scenario.onActivity {
                // Keep the same neutral harness identity during cleanup.
                it.intent = initial
            }
        }
        Unit
    }

    // A media/call protocol placeholder is not rendered as its raw database text.
    // Prefer the recent owner-authorized QA traffic over old imported history.
    private fun isAcceptanceFixture(row: com.messenger.app.data.local.message.MessageEntity): Boolean =
        row.text.startsWith("ACCEPTD2")

    private fun preferAcceptanceFixture(rows: List<com.messenger.app.data.local.message.MessageEntity>) =
        rows.lastOrNull(::isAcceptanceFixture) ?: rows.lastOrNull()

    private fun isPlainTextFixture(row: com.messenger.app.data.local.message.MessageEntity): Boolean {
        val presentation = com.messenger.app.data.message.MessageTextPolicy.presentationResult(row.text)
        return presentation.kind == com.messenger.app.data.message.MessageTextPolicy.PresentationKind.TEXT &&
            !presentation.truncated && presentation.text == row.text &&
            row.serverId != null && row.kind == "content" && row.text.isNotBlank() && row.text.length <= 240 &&
            row.caption == null && row.albumId == null && row.conferenceInviteJson == null && !row.hiddenLocally && !row.isDeletedForEveryone &&
            !row.text.trimStart().startsWith("{") && !row.text.startsWith("coco://") &&
            row.text != "Direct file transfer" && row.status in setOf("sent", "delivered", "read")
    }
}
