package com.messenger.app.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.R
import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.*
import com.messenger.app.ui.theme.CocoTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

@RunWith(AndroidJUnit4::class)
class InlineSearchInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepFixtureAwake() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent 224", "wm dismiss-keyguard")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        instrumentation.runOnMainSync { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private class FakeProfileRepository : ProfileRepository {
        var result: suspend (String, Long) -> Result<SearchResult> = { _, _ -> Result.success(SearchResult(emptyList(), null)) }
        val queries = mutableListOf<Pair<String, Long>>()
        override suspend fun getMe(): Result<UserProfile> = Result.failure(IllegalStateException())
        override suspend fun updateProfile(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate): Result<UserProfile> =
            Result.failure(IllegalStateException())
        override suspend fun searchUsers(query: String, limit: Int, offset: Long): Result<SearchResult> {
            queries += query to offset
            return result(query, offset)
        }
        override suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto> = Result.failure(IllegalStateException())
        override suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit> = Result.failure(IllegalStateException())
        override suspend fun completeAvatarUpload(objectKey: String): Result<UserProfile> = Result.failure(IllegalStateException())
        override suspend fun removeAvatar(): Result<UserProfile> = Result.failure(IllegalStateException())
        override suspend fun getAvatarDownloadUrl(userId: String): Result<com.messenger.app.data.api.AvatarDownloadDto> =
            Result.failure(IllegalStateException())
        override suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray> =
            Result.failure(IllegalStateException())
    }

    private class FakeChatRepository : ChatRepository {
        var chatCalls = mutableListOf<String>()
        var result: suspend (String) -> Result<ChatSummary> = { id -> Result.success(ChatSummary(chatId = "chat-$id", type = "direct", otherUser = null, createdAt = "", lastMessageAt = null)) }
        override suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary> {
            chatCalls += otherUserId
            return result(otherUserId)
        }
        override val chats: kotlinx.coroutines.flow.Flow<List<ChatSummary>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override fun observeChat(chatId: String): kotlinx.coroutines.flow.Flow<ChatSummary?> = kotlinx.coroutines.flow.flowOf(null)
        override suspend fun refreshChats(): Result<Unit> = Result.success(Unit)
        override suspend fun getChat(chatId: String): Result<ChatSummary> = Result.failure(IllegalStateException())
        override suspend fun getOrCreateSavedChat(): Result<ChatSummary> = Result.failure(IllegalStateException())
        override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> = error("Moderation is outside this test")
        override suspend fun clearMute(chatId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun unblockUser(chatId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun reportChat(chatId: String, reason: String, reportedUserId: String?, description: String?): Result<Unit> = error("Moderation is outside this test")
        override suspend fun markLocallyRead(chatId: String, sequence: Long) {}
    }


    @Test fun narrowScreenLargeTextKeepsSearchActionsAndNavigationUsable() {
        val profile = FakeProfileRepository()
        profile.result = { _, _ -> Result.success(SearchResult(listOf(PublicUser("qa-person", "alice", "Alice QA", null)), null)) }
        val chats = FakeChatRepository()
        val model = SearchViewModel(profile, chats)
        val opened = AtomicReference<String?>(null)
        val closed = AtomicBoolean(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                CocoTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        InlineChatSearch(model, onOpenChat = { opened.set(it) }, onClose = { closed.set(true) }, onOpenMessage = {})
                    }
                }
            }
        }
        val clear = compose.activity.getString(R.string.search_clear)
        compose.onNodeWithTag("search-close").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Alice")
        compose.onNode(hasSetTextAction()).assertTextContains("Alice")
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "inline-search-ui.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Exercise the host's one-shot navigation with injected repositories, independent of socket state.
        compose.runOnIdle { model.message(PublicUser("qa-person", "alice", "Alice QA", null)) }
        compose.waitUntil(5000) { opened.get() == "chat-qa-person" }
        compose.onNodeWithContentDescription(clear).performClick()
        assertEquals("", compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("search-close").performClick()
        assertTrue(closed.get())
    }

    @Test fun contactsEntryKeepsQrSearchAndBackReachable() {
        val profile = FakeProfileRepository()
        profile.result = { _, _ -> Result.success(SearchResult(listOf(PublicUser("qa-person", "alice", "Alice QA", null)), null)) }
        val model = SearchViewModel(profile, FakeChatRepository())
        var qrTaps = 0; var closeTaps = 0
        val opened = AtomicReference<String?>(null)
        compose.setContent { CocoTheme {
            Box(Modifier.width(320.dp).fillMaxHeight()) {
                ContactsScreen(model, onOpenChat = { opened.set(it) }, onBack = { closeTaps++ }, onQr = { qrTaps++ }, onEdit = {})
            }
        } }
        compose.onNodeWithTag("contacts-query").assertIsNotFocused()
        compose.onNodeWithTag("contacts-qr").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, qrTaps); assertTrue(profile.queries.isEmpty()) }
        compose.onNodeWithTag("contacts-query").performTextInput("@alice")
        compose.onNodeWithTag("contacts-query").assertTextContains("@alice")
        compose.onNodeWithTag("contacts-scan-qr").assertIsDisplayed()
        compose.onNodeWithTag("contacts-server-feedback").assertExists()
        // The directory fixture uses only the fake transport, even when the device route is offline.
        compose.runOnIdle { model.search("@alice", directory = true, force = true, online = true) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Alice QA").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("contacts-list").performScrollToNode(hasText("Alice QA"))
        compose.onNodeWithText("Alice QA").performClick()
        compose.waitUntil(5000) { opened.get() == "chat-qa-person" }
        compose.runOnIdle { assertTrue(profile.queries.all { it.first == "@alice" }) }
        compose.onNodeWithTag("contacts-back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closeTaps) }
    }

    @Test fun compactDrawerSearchAndPeopleDispatchSeparateActions() {
        var searches = 0; var people = 0
        compose.setContent { CocoTheme {
            Box(Modifier.width(190.dp)) { com.messenger.app.ui.DrawerSearchActions({ searches++ }, { people++ }) }
        } }
        val search = compose.onNodeWithText(compose.activity.getString(R.string.chats_search)).assertIsDisplayed()
        val directory = compose.onNodeWithText(compose.activity.getString(R.string.search_scope_people)).assertIsDisplayed()
        search.performTouchInput { click() }; directory.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, searches); assertEquals(1, people) }
        val first = search.fetchSemanticsNode().boundsInRoot; val second = directory.fetchSemanticsNode().boundsInRoot
        assertTrue(first.right <= second.left); assertEquals(first.top, second.top, 1f)
    }
}
