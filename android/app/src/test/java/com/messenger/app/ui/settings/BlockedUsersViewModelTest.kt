package com.messenger.app.ui.settings

import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedUsersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()
    private fun user(id: String) = PublicUser(id, "user_$id", "Person $id", null)
    private fun page(vararg ids: String, cursor: String? = null) = BlockedAccountsPage(ids.map { BlockedAccount(user(it), "2026-10-28T00:00:00Z") }, cursor)

    private class Fake : BlockedUsersRepository {
        var owner: String? = "server|owner|1"
        val queries = mutableListOf<Pair<String, String?>>()
        val mutations = mutableListOf<Pair<String, Boolean>>()
        var listResult: suspend (String, String?) -> Result<BlockedAccountsPage> = { _, _ -> Result.success(BlockedAccountsPage(emptyList(), null)) }
        var peopleResult: suspend (String, Long) -> Result<BlockableAccountsPage> = { _, _ -> Result.success(BlockableAccountsPage(emptyList(), null)) }
        var mutationResult: suspend (String, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        override fun ownerKey() = owner
        override suspend fun list(query: String, cursor: String?): Result<BlockedAccountsPage> { queries += query to cursor; return listResult(query, cursor) }
        override suspend fun people(query: String, offset: Long) = peopleResult(query, offset)
        override suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> { mutations += userId to blocked; return mutationResult(userId, blocked) }
    }

    @Test fun `late search ignoring cancellation cannot replace the latest query`() = runTest(dispatcher) {
        val old = CompletableDeferred<Result<BlockedAccountsPage>>()
        val repo = Fake().apply { listResult = { q, _ -> if (q == "old") withContext(NonCancellable) { old.await() } else Result.success(page(q.ifBlank { "initial" })) } }
        val vm = BlockedUsersViewModel(repo); runCurrent()
        vm.search("@old"); advanceTimeBy(251); runCurrent()
        vm.search("@new"); advanceTimeBy(251); runCurrent()
        assertEquals(listOf("new"), vm.state.value.users.map { it.id })
        old.complete(Result.success(page("old"))); runCurrent()
        assertEquals("@new", vm.state.value.query)
        assertEquals(listOf("new"), vm.state.value.users.map { it.id })
        vm.close()
    }

    @Test fun `failed next page retains rows and retries the same cursor without duplicates`() = runTest(dispatcher) {
        var attempts = 0
        val repo = Fake().apply { listResult = { _, cursor ->
            if (cursor == null) Result.success(page("a", cursor = "a"))
            else if (++attempts == 1) Result.failure(IllegalStateException("offline"))
            else Result.success(page("a", "b"))
        } }
        val vm = BlockedUsersViewModel(repo); runCurrent(); vm.loadMore(); runCurrent()
        assertEquals(listOf("a"), vm.state.value.users.map { it.id }); assertTrue(vm.state.value.pageError)
        vm.loadMore(); runCurrent()
        assertEquals(listOf("a", "b"), vm.state.value.users.map { it.id }); assertFalse(vm.state.value.canLoadMore)
        assertEquals(listOf("" to null, "" to "a", "" to "a"), repo.queries)
        vm.close()
    }

    @Test fun `offline unblock preserves row and confirmation until acknowledged retry`() = runTest(dispatcher) {
        val repo = Fake().apply {
            listResult = { _, _ -> Result.success(page("a", "b")) }
            mutationResult = { _, _ -> Result.failure(IllegalStateException("offline")) }
        }
        val vm = BlockedUsersViewModel(repo); runCurrent(); vm.choose(user("a")); vm.confirmAction(); runCurrent()
        assertTrue(vm.state.value.actionError); assertEquals("a", vm.state.value.action?.user?.id)
        assertEquals(listOf("a", "b"), vm.state.value.users.map { it.id })
        repo.mutationResult = { _, _ -> Result.success(Unit) }
        vm.confirmAction(); runCurrent()
        assertEquals(listOf("b"), vm.state.value.users.map { it.id }); assertNull(vm.state.value.action)
        vm.close()
    }

    @Test fun `duplicate taps search and mode changes cannot race an active mutation`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val repo = Fake().apply { listResult = { _, _ -> Result.success(page("a")) }; mutationResult = { _, _ -> gate.await() } }
        val vm = BlockedUsersViewModel(repo); runCurrent(); vm.choose(user("a"))
        vm.confirmAction(); vm.confirmAction(); vm.search("someone"); vm.refresh(); vm.mode(BlockedUsersMode.ADD); vm.dismissAction(); runCurrent()
        assertEquals(listOf("a" to false), repo.mutations)
        assertTrue(vm.state.value.actionBusy); assertEquals("", vm.state.value.query); assertEquals(1, repo.queries.size)
        gate.complete(Result.success(Unit)); runCurrent(); assertTrue(vm.state.value.users.isEmpty())
        vm.close()
    }

    @Test fun `add directory excludes known blocked ids then reloads only the block list`() = runTest(dispatcher) {
        var added = false
        val repo = Fake().apply {
            listResult = { _, _ -> Result.success(if (added) page("a", "b") else page("a")) }
            peopleResult = { _, _ -> Result.success(BlockableAccountsPage(listOf(user("a"), user("b")), null)) }
            mutationResult = { _, block -> added = block; Result.success(Unit) }
        }
        val vm = BlockedUsersViewModel(repo); runCurrent(); vm.mode(BlockedUsersMode.ADD); runCurrent()
        assertEquals(listOf("b"), vm.state.value.users.map { it.id })
        vm.choose(user("b")); vm.confirmAction(); runCurrent()
        assertEquals(listOf("b" to true), repo.mutations); assertEquals(BlockedUsersMode.BLOCKED, vm.state.value.mode)
        assertEquals(listOf("a", "b"), vm.state.value.users.map { it.id })
        vm.close()
    }

    @Test fun `account change clears rows and discards a late old-owner response`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<BlockedAccountsPage>>()
        val repo = Fake().apply { listResult = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val vm = BlockedUsersViewModel(repo); runCurrent(); repo.owner = "server|other|2"
        gate.complete(Result.success(page("private"))); runCurrent()
        assertTrue(vm.state.value.users.isEmpty()); assertFalse(vm.state.value.loading)
        vm.open(); runCurrent(); assertEquals(1, repo.queries.size)
        vm.close()
    }

    @Test fun `leaving clears rows and reopening the same owner reloads instead of retaining data`() = runTest(dispatcher) {
        val repo = Fake().apply { listResult = { _, _ -> Result.success(page("private")) } }
        val vm = BlockedUsersViewModel(repo); runCurrent(); assertEquals(1, vm.state.value.users.size)
        vm.close(); assertTrue(vm.state.value.users.isEmpty())
        vm.open(); runCurrent(); assertEquals(2, repo.queries.size); assertEquals(1, vm.state.value.users.size)
        vm.close()
    }

    @Test fun `throwing transport leaves retryable state instead of a stuck busy action`() = runTest(dispatcher) {
        val repo = Fake().apply { listResult = { _, _ -> Result.success(page("a")) }; mutationResult = { _, _ -> throw IllegalStateException("offline") } }
        val vm = BlockedUsersViewModel(repo); runCurrent(); vm.choose(user("a")); vm.confirmAction(); runCurrent()
        assertFalse(vm.state.value.actionBusy); assertTrue(vm.state.value.actionError); assertEquals(1, vm.state.value.users.size)
        vm.close()
    }
}
