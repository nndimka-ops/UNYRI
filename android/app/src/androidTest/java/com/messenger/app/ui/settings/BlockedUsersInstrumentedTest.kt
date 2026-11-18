package com.messenger.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedUsersInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultStyleKeepsBlockedAccountsCompactAndConfirmationSeparate() {
        val users = listOf(PublicUser("fixture-anna", "anna", "Анна", null), PublicUser("fixture-alex", "alex", "Алексей", null))
        compose.setContent { CocoTheme {
            var state by remember { mutableStateOf(BlockedUsersUiState(users = users)) }
            BlockedUsersContent(state, {}, {}, {}, {}, {}, onChoose = { state = state.copy(action = BlockedUserAction(it, false)) }, onConfirm = {}, onDismiss = { state = state.copy(action = null) }, modifier = Modifier.statusBarsPadding())
        } }
        compose.onNodeWithTag("blocked-users-action-${users.first().id}").assertIsDisplayed()
        java.io.File(compose.activity.filesDir, "blocked-users-default.png").outputStream().use {
            compose.onNodeWithTag("blocked-users-screen").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("blocked-users-action-${users.first().id}").performClick()
        compose.onNodeWithTag("blocked-users-confirm").assertIsDisplayed()
        compose.waitForIdle()
        // Android's dialog-window transition is outside Compose's test clock.
        // Capture the settled native result, including the parent's safe inset.
        android.os.SystemClock.sleep(250)
        java.io.File(compose.activity.filesDir, "blocked-users-confirm.png").outputStream().use {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun realRoomProjectionDoesNotCreateChatsOrChangeOtherPeersAndReadState() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.messenger.app.data.local.chat.MessengerDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            dao.upsertChatMetadata("known", "direct", "2026-10-28", null)
            dao.upsertMember(com.messenger.app.data.local.chat.ChatMemberEntity("known", "peer", "member"))
            dao.updateLastReadSequence("known", 100L)
            dao.recordPeerReceipt("known", 110L, 108L)
            dao.updatePreferences("known", true, "later", false, null)
            dao.upsertChatMetadata("other", "direct", "2026-10-28", null)
            dao.upsertMember(com.messenger.app.data.local.chat.ChatMemberEntity("other", "other-peer", "member"))
            dao.upsertChatMetadata("saved", "saved", "2026-10-28", null)
            dao.upsertMember(com.messenger.app.data.local.chat.ChatMemberEntity("saved", "peer", "member"))
            dao.updateKnownPeerBlock("peer", true)
            val known = dao.cachedChat("known")!!
            assertTrue(known.isBlocked); assertEquals("peer", known.blockedUserId)
            assertTrue(known.isMuted); assertEquals("later", known.mutedUntil)
            assertEquals(100L, known.lastReadSequence); assertEquals(110L, known.peerDeliveredSequence); assertEquals(108L, known.peerReadSequence)
            assertFalse(dao.cachedChat("other")!!.isBlocked); assertFalse(dao.cachedChat("saved")!!.isBlocked)
            dao.updateKnownPeerBlock("unknown", true); assertNull(dao.cachedChat("unknown"))
            dao.updateKnownPeerBlock("peer", false); assertFalse(dao.cachedChat("known")!!.isBlocked); assertNull(dao.cachedChat("known")!!.blockedUserId)
        } finally { db.close() }
    }

    @Test fun largeTextNarrowWidthKeepsUnblockConfirmationAndRetryReachable() {
        val user = PublicUser("blocked-ui-fixture", "alice", "Alice — long display name", null)
        var requested = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                CocoTheme {
                    var state by remember { mutableStateOf(BlockedUsersUiState(users = listOf(user))) }
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        BlockedUsersContent(state, onSearch = { state = state.copy(query = it) }, onRefresh = {}, onLoadMore = {}, onAdd = {}, onBack = {},
                            onChoose = { state = state.copy(action = BlockedUserAction(it, false)) },
                            onConfirm = { requested++; state = state.copy(actionError = true) }, onDismiss = { state = state.copy(action = null) })
                    }
                }
            }
        }
        compose.onNodeWithTag("blocked-users-list").performScrollToNode(hasTestTag("blocked-users-action-${user.id}"))
        java.io.File(compose.activity.filesDir, "blocked-users-large-text.png").outputStream().use {
            compose.onNodeWithTag("blocked-users-screen").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("blocked-users-action-${user.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("blocked-users-confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("blocked-users-action-error").assertIsDisplayed()
        assertEquals(1, requested)
        compose.onNodeWithTag("blocked-users-confirm").assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("blocked-users-action-${user.id}").assertExists()
    }

    @Test fun changingAccountContentCannotReusePriorRowsOrConfirmation() {
        var owner by mutableStateOf("first")
        val user = PublicUser("private-blocked-ui-fixture", "alice", "Alice", null)
        compose.setContent { CocoTheme {
            key(owner) {
                val state = if (owner == "first") BlockedUsersUiState(users = listOf(user), action = BlockedUserAction(user, false)) else BlockedUsersUiState()
                BlockedUsersContent(state, {}, {}, {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("blocked-users-confirm").assertExists()
        compose.runOnIdle { owner = "second" }
        compose.onNodeWithTag("blocked-users-confirm").assertDoesNotExist()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithTag("blocked-users-empty-add").assertExists()
    }
}
