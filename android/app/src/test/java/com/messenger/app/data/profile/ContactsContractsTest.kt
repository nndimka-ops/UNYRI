package com.messenger.app.data.profile

import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Mirrors ios/Tests/ContactsContracts using controllable suspensions instead of wall-clock sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsContractsTest {
    private fun person(id: String, name: String, username: String = id) = PublicUser(id, username, name, null)
    private val anna = person("a1", "Anna")
    private val boris = person("b2", "boris")
    private val zed = person("z9", "Zed")
    private val own = person("owner", "Me")
    private fun page(vararg people: PublicUser, next: String? = null) = ContactsPage(people.map { ContactEntry(it) }, next)

    private class Service : ContactsService {
        var owner: String? = "session"
        override fun ownerKey() = owner
        override val ownerId = "owner"
        val hidden = mutableSetOf<String>()
        override fun isHidden(userId: String) = userId in hidden
        var rows = emptyList<PublicUser>()
        val script = ArrayDeque<ContactsPage>()
        var reads = 0; var writes = 0; var statusReads = 0
        var failReads = false; var failWrites = false; var failStatus = false
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var statusGate: CompletableDeferred<Unit>? = null
        override suspend fun page(cursor: String?): ContactsPage {
            reads++
            val snapshot = rows
            readGate?.also { readGate = null }?.await()
            check(!failReads)
            return if (script.isEmpty()) ContactsPage(snapshot.map { ContactEntry(it) }) else script.removeFirst()
        }
        override suspend fun status(userId: String): Boolean {
            statusReads++
            val member = rows.any { it.id == userId }
            statusGate?.also { statusGate = null }?.await()
            check(!failStatus)
            return member
        }
        override suspend fun set(userId: String, contact: Boolean) {
            writes++
            writeGate?.also { writeGate = null }?.await()
            check(!failWrites)
            rows = if (contact) rows + PublicUser(userId, userId, userId, null) else rows.filterNot { it.id == userId }
        }
    }
    private fun TestScope.store(service: Service, now: () -> Long = { testScheduler.currentTime }) =
        ContactsStore(service, this, StandardTestDispatcher(testScheduler), now)

    @Test fun `identity servers never match a local private login`() = runTest {
        val service = Service().apply {
            rows = listOf(person("p", "Visible person", "hidden_login"),
                person("q", "Other person", "public_name").copy(publicNumber = "123456789", publicUsername = "public_name"))
        }
        val store = store(service)
        store.ensureLoaded()
        assertTrue(store.entries.value.all { it.user.username.isEmpty() })
        assertTrue(store.matching("hidden_login").isEmpty())
        assertTrue(store.matching("hidden_login", legacyLogins = false).isEmpty())
        assertEquals(listOf("q"), store.matching("@public_name", legacyLogins = false).map { it.id })
        assertEquals(listOf("q"), store.matching("123 456 789", legacyLogins = false).map { it.id })
    }

    @Test fun `load deduplicates sorts excludes self reuses fresh list and expires after five minutes`() = runTest {
        val service = Service().apply { rows = listOf(zed, anna, boris, anna, own) }
        var time = 0L
        val store = store(service) { time }
        store.ensureLoaded()
        assertEquals(ContactsStore.Phase.LOADED, store.phase.value)
        assertEquals(listOf("a1", "b2", "z9"), store.entries.value.map { it.id })
        assertEquals(setOf("a1", "b2", "z9"), store.ids.value)
        store.ensureLoaded(); assertEquals(1, service.reads)
        time = 300_000; store.ensureLoaded(); assertEquals(2, service.reads)
        service.rows = listOf(anna, person("a0", "Anna", "anna_k"))
        store.reload(); assertEquals(listOf("a0", "a1"), store.entries.value.map { it.id })
    }

    @Test fun `cursor pages and malformed repeated failed and over-limit pages preserve displayed rows`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.reload()
        service.script += page(boris, next = "not-a-uuid")
        store.reload(); assertEquals(ContactsStore.Phase.FAILED, store.phase.value)
        assertEquals(listOf("a1"), store.entries.value.map { it.id })
        val cursor = UUID.randomUUID().toString()
        service.script += page(boris, next = cursor); service.script += page(zed, next = cursor.uppercase())
        store.reload(); assertEquals(ContactsStore.Phase.FAILED, store.phase.value)
        assertTrue(service.script.isEmpty())
        service.failReads = true; store.reload()
        assertEquals(listOf("a1"), store.entries.value.map { it.id })
        service.failReads = false; store.ensureLoaded(); assertEquals(ContactsStore.Phase.LOADED, store.phase.value)
        service.script += page(anna, next = cursor); service.script += page(boris)
        store.reload(); assertEquals(listOf("a1", "b2"), store.entries.value.map { it.id })
        repeat(25) { service.script += page(zed, next = UUID.randomUUID().toString()) }
        val before = service.reads; store.reload()
        assertEquals(25, service.reads - before); assertEquals(ContactsStore.Phase.FAILED, store.phase.value)
        assertEquals(listOf("a1", "b2"), store.entries.value.map { it.id })
    }

    @Test fun `writes are confirmed idempotent guarded per person and failed writes leave membership unchanged`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.reload()
        assertTrue(store.set(boris, true)); assertEquals(listOf("a1", "b2"), store.entries.value.map { it.id })
        assertFalse(store.set(own, true))
        service.failWrites = true; assertFalse(store.set(zed, true)); assertFalse("z9" in store.ids.value)
        assertTrue(store.changing.value.isEmpty())
        service.failWrites = false
        val gate = CompletableDeferred<Unit>(); service.writeGate = gate
        val write = async { store.set(anna, false) }; runCurrent()
        assertTrue("a1" in store.changing.value); assertTrue("a1" in store.ids.value)
        assertFalse(store.set(anna, false)); gate.complete(Unit); assertTrue(write.await())
        assertEquals(listOf("b2"), store.entries.value.map { it.id }); assertEquals(3, service.writes)
    }

    @Test fun `page fetched before a write cannot restore removed row and open list reloads`() = runTest {
        val service = Service().apply { rows = listOf(anna, boris) }
        val store = store(service); store.reload(); store.listAppeared()
        val gate = CompletableDeferred<Unit>(); service.readGate = gate
        val load = launch { store.reload() }; runCurrent()
        assertTrue(store.set(anna, false)); assertFalse("a1" in store.ids.value)
        gate.complete(Unit); load.join(); advanceUntilIdle()
        assertEquals(listOf("b2"), store.entries.value.map { it.id }); assertTrue(service.reads >= 3)
    }

    @Test fun `bare hints and unblock only fetch while list is visible and viewer count is clamped`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.markStale(); runCurrent(); assertEquals(0, service.reads)
        store.listAppeared(); store.ensureLoaded(); store.listDisappeared()
        service.rows = listOf(anna, boris); store.receive(null, null); runCurrent(); assertEquals(1, service.reads)
        store.listAppeared(); store.ensureLoaded(); assertEquals(2, service.reads)
        service.rows += zed; store.receive(null, null); advanceUntilIdle()
        assertEquals(listOf("a1", "b2", "z9"), store.entries.value.map { it.id })
        store.listDisappeared(); store.listDisappeared(); store.listAppeared(); store.markStale(); advanceUntilIdle()
        assertEquals(4, service.reads)
        store.hide("a1"); assertFalse("a1" in store.ids.value)
        service.owner = "another-session"; store.ensureLoaded()
        assertTrue(store.entries.value.isEmpty()); assertEquals(ContactsStore.Phase.IDLE, store.phase.value)
        assertFalse(store.set(zed, true))
    }

    @Test fun `named hints patch membership without paging closed book and new row loads on next visit`() = runTest {
        val service = Service().apply { rows = listOf(anna, boris) }
        val store = store(service); store.listAppeared(); store.ensureLoaded(); store.listDisappeared()
        store.receive("a1", false); assertEquals(listOf("b2"), store.entries.value.map { it.id }); assertEquals(1, service.reads)
        store.receive("z9", true); runCurrent(); assertTrue("z9" in store.ids.value); assertEquals(1, service.reads)
        store.receive("owner", true); assertFalse("owner" in store.ids.value)
        service.rows = listOf(boris, zed); store.listAppeared(); store.ensureLoaded()
        assertEquals(2, service.reads); assertEquals(listOf("b2", "z9"), store.entries.value.map { it.id })
    }

    @Test fun `profile asks only one status failed answer stays unknown and newer write wins`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.check(anna)
        assertTrue("a1" in store.ids.value); assertEquals(1, service.statusReads); assertEquals(0, service.reads)
        store.check(boris); assertTrue("b2" in store.nonMembers.value)
        store.check(own); assertEquals(2, service.statusReads)
        service.failStatus = true; store.check(zed)
        assertFalse("z9" in store.ids.value); assertFalse("z9" in store.nonMembers.value); assertTrue(store.checking.value.isEmpty())
        service.failStatus = false
        val gate = CompletableDeferred<Unit>(); service.statusGate = gate
        val asking = launch { store.check(zed) }; runCurrent(); assertTrue("z9" in store.checking.value)
        assertTrue(store.set(zed, true)); gate.complete(Unit); asking.join(); assertTrue("z9" in store.ids.value)
        store.listAppeared(); store.ensureLoaded(); val before = service.statusReads
        store.check(anna); store.check(boris); assertEquals(before, service.statusReads)
        assertTrue("b2" in store.nonMembers.value)
        store.markStale(); advanceUntilIdle(); assertTrue(store.revision.value > 0)
    }

    @Test fun `hint for a person beats older status and bare hint retries once`() = runTest {
        val service = Service()
        val store = store(service)
        val gate = CompletableDeferred<Unit>(); service.statusGate = gate
        val asking = launch { store.check(anna) }; runCurrent()
        store.receive("a1", true); gate.complete(Unit); asking.join(); assertTrue("a1" in store.ids.value)
        val next = CompletableDeferred<Unit>(); service.statusGate = next
        val retry = launch { store.check(boris) }; runCurrent()
        store.markStale(); service.rows = listOf(boris); next.complete(Unit); retry.join()
        assertEquals(3, service.statusReads); assertTrue("b2" in store.ids.value)
    }

    @Test fun `filter keys ignore case diacritics and match exact public username plus newly added row`() = runTest {
        val zoe = person("o5", "Zoë Ångström", "private_login").copy(publicUsername = "zoe_a")
        val service = Service().apply { rows = listOf(anna, boris, zoe) }
        val store = store(service); store.listAppeared(); store.ensureLoaded()
        assertEquals(listOf("a1", "b2", "o5"), store.matching("").map { it.id })
        for (query in listOf("ZOE", "angst", "@zoe_a")) assertEquals(listOf("o5"), store.matching(query).map { it.id })
        assertTrue(store.matching("nobody").isEmpty())
        assertTrue(store.set(person("c3", "Céline"), true)); assertEquals(listOf("c3"), store.matching("celine").map { it.id })
    }

    @Test fun `a confirmed stored add never makes a blocked contact visible`() = runTest {
        val service = Service().apply { rows = listOf(anna, boris) }
        val store = store(service); store.reload()
        service.hidden += anna.id; store.hide(anna.id)
        assertTrue(store.set(anna, true)) // The server confirms stored membership even for a block.
        assertEquals(listOf(boris.id), store.entries.value.map { it.id })
        assertFalse(anna.id in store.ids.value)
        assertTrue(anna.id in store.nonMembers.value)
        store.reload() // A stale/malformed positive page cannot bypass the known block either.
        assertEquals(listOf(boris.id), store.entries.value.map { it.id })
        service.hidden -= anna.id; store.reveal(anna.id)
        store.ensureLoaded()
        assertTrue(anna.id in store.ids.value)
        assertEquals(listOf(anna.id, boris.id), store.entries.value.map { it.id })
    }

    @Test fun `named add hints and positive status answers honour a known block`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.reload()
        service.hidden += anna.id; store.hide(anna.id)
        store.receive(anna.id, true)
        assertFalse(anna.id in store.ids.value)
        assertTrue(store.entries.value.isEmpty())
        store.markStale(); store.check(anna) // Fake status remains true; visibility must still be false.
        assertFalse(anna.id in store.ids.value)
        assertTrue(store.entries.value.isEmpty())
        assertEquals(1, service.statusReads)
        assertEquals(1, service.reads)
    }

    @Test fun `failed status clears a previously loaded contact to unknown and removes stale row`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.reload()
        assertTrue(anna.id in store.ids.value)
        store.markStale(); service.failStatus = true
        store.check(anna)
        assertFalse(anna.id in store.ids.value)
        assertFalse(anna.id in store.nonMembers.value)
        assertTrue(store.entries.value.isEmpty())
        assertTrue(store.matching("Anna").isEmpty())
        assertTrue(store.checking.value.isEmpty())
        assertEquals(1, service.reads) // A profile never reloads the book.
    }

    @Test fun `a delayed failed status cannot erase a newer confirmed write`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.check(anna)
        val gate = CompletableDeferred<Unit>(); service.statusGate = gate; service.failStatus = true
        val asking = launch { store.check(anna) }; runCurrent()
        assertTrue(store.set(anna, true))
        gate.complete(Unit); asking.join()
        assertTrue(anna.id in store.ids.value)
        assertFalse(anna.id in store.nonMembers.value)
    }

    @Test fun `a write begun after a status question fences its failure before confirmation`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val store = store(service); store.check(anna)
        val statusGate = CompletableDeferred<Unit>(); service.statusGate = statusGate; service.failStatus = true
        val asking = launch { store.check(anna) }; runCurrent()
        val writeGate = CompletableDeferred<Unit>(); service.writeGate = writeGate
        val writing = async { store.set(anna, false) }; runCurrent()
        statusGate.complete(Unit); asking.join()
        assertTrue(anna.id in store.ids.value) // The pending write is still non-optimistic.
        service.failWrites = true; writeGate.complete(Unit)
        assertFalse(writing.await())
        assertTrue(anna.id in store.ids.value)
    }

    @Test fun `a delayed failed status cannot erase a newer named add or removal hint`() = runTest {
        for (member in listOf(true, false)) {
            val service = Service().apply { rows = listOf(anna) }
            val store = store(service); store.check(anna)
            val gate = CompletableDeferred<Unit>(); service.statusGate = gate; service.failStatus = true
            val asking = launch { store.check(anna) }; runCurrent()
            store.receive(anna.id, member)
            gate.complete(Unit); asking.join()
            assertEquals(member, anna.id in store.ids.value)
            assertEquals(!member, anna.id in store.nonMembers.value)
        }
    }

    @Test fun `a failed old-owner status cannot clear the replacement store`() = runTest {
        val service = Service().apply { rows = listOf(anna) }
        val oldStore = store(service); oldStore.check(anna)
        val gate = CompletableDeferred<Unit>(); service.statusGate = gate
        val asking = launch { oldStore.check(anna) }; runCurrent()
        service.owner = "new-session"
        val replacement = store(service); replacement.check(anna)
        service.failStatus = true; gate.complete(Unit); asking.join()
        assertTrue(anna.id in replacement.ids.value)
        assertTrue(oldStore.ids.value.isEmpty())
        assertEquals(ContactsStore.Phase.IDLE, oldStore.phase.value)
    }
}
