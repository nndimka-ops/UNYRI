package com.messenger.app.ui.search

import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test fun offlineDirectoryUsesCacheAndReconnectRequestsThirtyPeople() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val vm = SearchViewModel(repository, FakeChatRepository())
        vm.search("boris", directory = true, online = false); advanceTimeBy(301)
        assertTrue(repository.queries.isEmpty())
        assertTrue((vm.uiState.value as SearchUiState.Loaded).cached)
        vm.search("boris", directory = true, online = true); advanceTimeBy(301)
        assertEquals(listOf("boris" to 0L), repository.queries)
        assertEquals(listOf(30), repository.limits)
        vm.close()
    }
    @Test fun authorizationFailureRemovesPreviousDirectoryIdentities() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        repository.result = { _, _ -> Result.success(SearchResult(listOf(user("peer", "Person")), 30L)) }
        val vm = SearchViewModel(repository, FakeChatRepository())
        vm.search("person", directory = true); advanceTimeBy(301)
        assertTrue(vm.uiState.value is SearchUiState.Loaded)
        repository.result = { _, _ -> Result.failure(com.messenger.app.data.api.AuthApiException.Api("forbidden", "Forbidden", 403)) }
        vm.loadMore(); advanceTimeBy(1)
        assertTrue(vm.uiState.value is SearchUiState.Error)
        vm.close()
    }

    @Test fun `explicit directory supports empty query and pagination while normal empty search stays local`() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        repository.result = { _, offset -> Result.success(SearchResult(listOf(user("$offset", "Person")), if (offset == 0L) 30L else null)) }
        val vm = SearchViewModel(repository, FakeChatRepository())
        vm.search(""); advanceTimeBy(301); assertTrue(repository.queries.isEmpty())
        vm.search("", directory = true); advanceTimeBy(301)
        assertEquals(listOf("" to 0L), repository.queries)
        vm.loadMore(); advanceTimeBy(1)
        assertEquals(listOf("" to 0L, "" to 30L), repository.queries)
        vm.close()
    }

    @Test fun exactUsernameRetainsAtPrefixAcrossCacheDebounceRetryAndShortNumberLookup() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val cacheQueries = mutableListOf<String>()
        repository.cache = { query -> cacheQueries += query; Result.success(SearchResult(emptyList(), null, cached = true)) }
        val vm = SearchViewModel(repository, FakeChatRepository())
        vm.search(" @Alice_1 ", directory = true)
        advanceTimeBy(1)
        assertEquals(listOf("@Alice_1"), cacheQueries)
        assertTrue(repository.queries.isEmpty())
        advanceTimeBy(300)
        assertEquals(listOf("@Alice_1" to 0L), repository.queries)
        assertEquals("@Alice_1", (vm.uiState.value as SearchUiState.Loaded).query)
        vm.retry(); advanceTimeBy(301)
        assertEquals(listOf("@Alice_1" to 0L, "@Alice_1" to 0L), repository.queries)
        vm.search("1"); advanceTimeBy(301)
        assertEquals("1" to 0L, repository.queries.last())
        vm.close()
    }

    private fun user(id: String, name: String) = PublicUser(
        id = id,
        username = "user_$id",
        displayName = name,
        avatarUrl = null,
    )

    private class FakeProfileRepository : ProfileRepository {
        var cache: suspend (String) -> Result<SearchResult> = { Result.success(SearchResult(emptyList(), null, cached = true)) }
        var result: suspend (String, Long) -> Result<SearchResult> = { _, _ -> Result.success(SearchResult(emptyList(), null)) }
        val queries = mutableListOf<Pair<String, Long>>()
        val limits = mutableListOf<Int>()
        override suspend fun cachedDirectory(query: String, limit: Int, offset: Long) = cache(query)
        override suspend fun getMe(): Result<UserProfile> = Result.failure(IllegalStateException())
        override suspend fun updateProfile(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate): Result<UserProfile> =
            Result.failure(IllegalStateException())
        override suspend fun searchUsers(query: String, limit: Int, offset: Long): Result<SearchResult> {
            queries += query to offset
            limits += limit
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

    @Test fun `directory cache arrives before debounce and network replaces it in one page`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.cache = { Result.success(SearchResult(listOf(user("cached", "Cached")), 30, cached = true)) }
        profile.result = { _, _ -> Result.success(SearchResult(listOf(user("fresh", "Fresh")), null)) }
        val model = vm(profile, FakeChatRepository())
        model.search("person", directory = true)
        advanceTimeBy(1)
        val cached = model.uiState.value as SearchUiState.Loaded
        assertEquals(listOf("cached"), cached.items.map { it.id })
        assertTrue(cached.refreshing)
        assertTrue(profile.queries.isEmpty())
        model.loadMore(); advanceTimeBy(301)
        assertEquals(listOf("person" to 0L), profile.queries)
        val fresh = model.uiState.value as SearchUiState.Loaded
        assertEquals(listOf("fresh"), fresh.items.map { it.id })
        assertTrue(!fresh.refreshing)
        model.close()
    }

    @Test fun `first page failure with cached people retries zero instead of cached next offset`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.cache = { Result.success(SearchResult(listOf(user("cache", "Cache")), 30, cached = true)) }
        profile.result = { _, _ -> Result.failure(IllegalStateException("offline")) }
        val model = vm(profile, FakeChatRepository())
        model.search("person", directory = true); advanceTimeBy(301)
        assertTrue((model.uiState.value as SearchUiState.Loaded).pageError)
        model.retry(); advanceTimeBy(301)
        assertEquals(listOf(0L, 0L), profile.queries.map { it.second })
        model.close()
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

    private fun vm(profile: ProfileRepository, chat: ChatRepository) = SearchViewModel(profile, chat)

    @Test
    fun `debounce collapses rapid keystrokes into one request and latest query wins`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        repo.result = { q, _ -> Result.success(SearchResult(listOf(user("1", "One")), null)) }
        val viewModel = vm(repo, FakeChatRepository())

        viewModel.search("a")
        viewModel.search("ab")
        viewModel.search("abc")
        advanceTimeBy(299)
        assertTrue("no request before debounce", repo.queries.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, repo.queries.size)
        assertEquals("abc", repo.queries.single().first)
        val state = viewModel.uiState.value as? SearchUiState.Loaded
        assertNotNull("debounced search loads", state)
        assertEquals("One", state?.items?.single()?.displayName)
    }

    @Test
    fun `stale response does not overwrite newer query`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        // The FIRST query never returns; only the latest should land.
        repo.result = { q, _ -> Result.success(SearchResult(listOf(user(q, q)), null)) }
        val viewModel = vm(repo, FakeChatRepository())

        viewModel.search("old")
        advanceTimeBy(301)
        viewModel.search("new")
        advanceTimeBy(301)

        val state = viewModel.uiState.value as? SearchUiState.Loaded
        assertEquals("new", state?.items?.single()?.displayName)
        assertEquals("new", repo.queries.last().first)
    }

    @Test
    fun `message opens direct chat with the correct user id exactly once`() = runTest(dispatcher) {
        val chat = FakeChatRepository()
        val viewModel = vm(FakeProfileRepository(), chat)
        viewModel.message(user("u-42", "Alice"))
        advanceTimeBy(10)
        assertEquals(listOf("u-42"), chat.chatCalls)
        assertEquals("chat-u-42", viewModel.openChatId.value)
    }

    @Test
    fun `repeated taps are guarded to a single request`() = runTest(dispatcher) {
        val chat = FakeChatRepository()
        val viewModel = vm(FakeProfileRepository(), chat)
        viewModel.message(user("u-7", "Bob"))
        viewModel.message(user("u-7", "Bob"))
        viewModel.message(user("u-7", "Bob"))
        advanceTimeBy(10)
        assertEquals(1, chat.chatCalls.size)
    }

    @Test
    fun `direct chat failure stays on search with an error state`() = runTest(dispatcher) {
        val chat = FakeChatRepository()
        chat.result = { Result.failure(RuntimeException("boom")) }
        val viewModel = vm(FakeProfileRepository(), chat)
        viewModel.message(user("u-9", "Carol"))
        advanceTimeBy(10)
        assertTrue(viewModel.openError.value)
        assertEquals(null, viewModel.openChatId.value)
    }
    @Test fun `overlapping pages contain each user once`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        repo.result = { _, offset -> Result.success(if (offset == 0L)
            SearchResult(listOf(user("1", "One"), user("2", "Two")), 2L)
            else SearchResult(listOf(user("2", "Two"), user("3", "Three")), null)) }
        val model = vm(repo, FakeChatRepository())
        model.search("ab"); advanceTimeBy(301)
        model.loadMore(); advanceTimeBy(1)
        assertEquals(listOf("1", "2", "3"), (model.uiState.value as SearchUiState.Loaded).items.map { it.id })
    }
    @Test fun `failed page preserves results and retries the same offset`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        var fail = true
        repo.result = { _, offset -> if (offset == 0L) Result.success(SearchResult(listOf(user("1", "One")), 1L))
            else if (fail) Result.failure(RuntimeException("offline"))
            else Result.success(SearchResult(listOf(user("2", "Two")), null)) }
        val model = vm(repo, FakeChatRepository())
        model.search("ab"); advanceTimeBy(301)
        model.loadMore(); advanceTimeBy(1)
        assertEquals(listOf("1"), (model.uiState.value as SearchUiState.Loaded).items.map { it.id })
        fail = false; model.loadMore(); advanceTimeBy(1)
        assertEquals(listOf(0L, 1L, 1L), repo.queries.map { it.second })
        assertEquals(2, (model.uiState.value as SearchUiState.Loaded).items.size)
    }
    @Test fun `double load more reserves just one page`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        repo.result = { _, offset -> Result.success(SearchResult(listOf(user(offset.toString(), "User")), offset + 1)) }
        val model = vm(repo, FakeChatRepository())
        model.search("ab"); advanceTimeBy(301)
        model.loadMore(); model.loadMore(); advanceTimeBy(1)
        assertEquals(listOf(0L, 1L), repo.queries.map { it.second })
    }
    @Test fun `old page cannot attach to a repeated query generation`() = runTest(dispatcher) {
        val repo = FakeProfileRepository()
        repo.result = { _, offset ->
            if (offset > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(800) }
            Result.success(SearchResult(listOf(user(if (offset == 0L) "fresh" else "stale", "User")), if (offset == 0L) 1L else null))
        }
        val model = vm(repo, FakeChatRepository())
        model.search("aa"); advanceTimeBy(301)
        model.loadMore(); advanceTimeBy(1)
        model.search("bb"); model.search("aa"); advanceTimeBy(1000)
        assertEquals(listOf("fresh"), (model.uiState.value as SearchUiState.Loaded).items.map { it.id })
    }

    @Test fun `closing search prevents a late chat from opening`() = runTest(dispatcher) {
        val chat = FakeChatRepository()
        chat.result = { id ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(800) }
            Result.success(ChatSummary(chatId = id, type = "direct", otherUser = null, createdAt = "", lastMessageAt = null))
        }
        val model = vm(FakeProfileRepository(), chat)
        model.message(user("late", "Late")); advanceTimeBy(1)
        model.close(); advanceTimeBy(1000)
        assertEquals(null, model.openChatId.value)
        assertEquals(null, model.openingUserId.value)
        assertTrue(!model.openError.value)
    }
    @Test fun `opening failure preserves people and pagination and can be retried`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.result = { _, _ -> Result.success(SearchResult(listOf(user("u", "User")), 1L)) }
        val chat = FakeChatRepository()
        chat.result = { Result.failure(RuntimeException("offline")) }
        val model = vm(profile, chat)
        model.search("user"); advanceTimeBy(301)
        val found = model.uiState.value
        model.message(user("u", "User")); advanceTimeBy(1)
        assertEquals(found, model.uiState.value)
        assertTrue(model.openError.value)
        chat.result = { Result.success(ChatSummary(chatId = "opened", type = "direct", otherUser = null, createdAt = "", lastMessageAt = null)) }
        model.message(user("u", "User")); advanceTimeBy(1)
        assertEquals("opened", model.openChatId.value)
        assertTrue(!model.openError.value)
    }
    @Test fun `closing search rejects a non cancellable people response`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.result = { _, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(600) }
            Result.success(SearchResult(listOf(user("late", "Late")), null))
        }
        val model = vm(profile, FakeChatRepository())
        model.search("old"); advanceTimeBy(301); model.close(); advanceTimeBy(1000)
        assertEquals(SearchUiState.Idle, model.uiState.value)
    }
    @Test fun `non advancing page cursor cannot make an infinite load loop`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.result = { _, _ -> Result.success(SearchResult(listOf(user("a", "A")), 0L)) }
        val model = vm(profile, FakeChatRepository())
        model.search("aa"); advanceTimeBy(301); model.loadMore(); advanceTimeBy(1)
        assertEquals(1, profile.queries.size)
        assertTrue(!(model.uiState.value as SearchUiState.Loaded).canLoadMore)
    }

    @Test fun `return from a chat keeps search pages without another request`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.result = { _, offset -> Result.success(SearchResult(listOf(user(offset.toString(), "User")), offset + 1)) }
        val model = vm(profile, FakeChatRepository())
        model.search("user"); advanceTimeBy(301); model.loadMore(); advanceTimeBy(1)
        model.rememberQuery("user")
        model.pause(); model.search("user"); advanceTimeBy(301)
        assertEquals(2, profile.queries.size)
        assertEquals(2, (model.uiState.value as SearchUiState.Loaded).items.size)
        assertEquals(listOf("user"), model.recentQueries.value)
        model.close(); model.search("user"); advanceTimeBy(301)
        assertEquals(3, profile.queries.size)
    }
    @Test fun `changing query cancels a pending conversation open`() = runTest(dispatcher) {
        val chat = FakeChatRepository()
        chat.result = { id ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(800) }
            Result.success(ChatSummary(chatId = id, type = "direct", otherUser = null, createdAt = "", lastMessageAt = null))
        }
        val model = vm(FakeProfileRepository(), chat)
        model.search("old"); advanceTimeBy(301)
        model.message(user("old", "Old")); advanceTimeBy(1)
        model.search("new"); advanceTimeBy(1000)
        assertEquals(null, model.openChatId.value)
    }

    @Test fun `cached directory retries unchanged query and clears cache hint after network recovery`() = runTest(dispatcher) {
        val profile = FakeProfileRepository()
        profile.result = { _, _ -> Result.success(SearchResult(listOf(user("cached", "Cached")), null, cached = true)) }
        val model = vm(profile, FakeChatRepository())
        model.search("", directory = true); advanceTimeBy(301)
        assertTrue((model.uiState.value as SearchUiState.Loaded).cached)
        profile.result = { _, _ -> Result.success(SearchResult(listOf(user("fresh", "Fresh")), null)) }
        model.search("", directory = true); advanceTimeBy(301)
        val state = model.uiState.value as SearchUiState.Loaded
        assertTrue(!state.cached); assertEquals(listOf("fresh"), state.items.map { it.id })
    }

}
