package com.messenger.app.ui.search

import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.model.SearchHistory
import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InlineSearchContractsTest {
    @Test fun identityServersNeverMatchLegacyPrivateHandlesButKeepPublicIdentityAndDisplayNameMatches() {
        val user = PublicUser("peer", "secret_login", "Visible Name", null, publicNumber = "123456789", publicUsername = "alice")
        val people = listOf(SearchPerson(user.id, user.visibleName, user.username, user))
        assertTrue(InlineSearchPolicy.rank(people, "secret", legacyLogins = false).isEmpty())
        assertTrue(InlineSearchPolicy.rank(people, "secret", legacyLogins = true).isEmpty())
        assertEquals(people, InlineSearchPolicy.rank(people, "123456789", legacyLogins = false))
        assertEquals(people, InlineSearchPolicy.rank(people, "@alice", legacyLogins = false))
        assertTrue(InlineSearchPolicy.rank(people, "ali", legacyLogins = false).isEmpty())
        assertEquals(people, InlineSearchPolicy.rank(people, "visible", legacyLogins = false))
        assertEquals(listOf("global"), InlineSearchPolicy.sections(InlineSnapshot("1"), directoryAllowed = true))
    }

    private fun hit(id: String, text: String = "text", media: String? = null, mime: String? = null) =
        MessageSearchResult(id, "chat", "peer", 1, "2026-11-17", text, media, mime)

    @Test fun `exact then prefix then substring with stable identity ties and canonical section order`() {
        val people = listOf(SearchPerson("z", "Cat"), SearchPerson("b", "Caterpillar"), SearchPerson("a", "Bob", "cat"), SearchPerson("c", "A cat"), SearchPerson("x", "Dog"))
        val ranked = InlineSearchPolicy.rank(people, "cat")
        assertEquals(listOf("z", "b", "c"), ranked.map { it.id })
        assertEquals(listOf("chats", "global", "messages"), InlineSearchPolicy.sections(InlineSnapshot("cat", ranked, listOf(hit("m")))))
        assertEquals(listOf("messages"), InlineSearchPolicy.sections(InlineSnapshot("ca", messages = listOf(hit("m")))))
        assertEquals(listOf("accent"), InlineSearchPolicy.rank(listOf(SearchPerson("accent", "Ánna")), "anna").map { it.id })
        assertTrue(InlineSearchPolicy.rank(listOf(SearchPerson("empty", "Name")), "").isEmpty())
        assertFalse(InlineSearchPolicy.canSearchDirectory("ab"))
        assertTrue(InlineSearchPolicy.canSearchDirectory("abc"))
        assertFalse(InlineSearchPolicy.canSearchDirectory("😀😀"))
        assertEquals(30, InlineSearchPolicy.DIRECTORY_PAGE)
        assertEquals("борис", InlineSearchPolicy.query("  @БОРИС  "))
    }
    @Test fun `rapid edits debounce once empty input scans nothing and caps the atomic snapshot`() = runTest {
        val calls = mutableListOf<String>()
        val model = InlineSearchModel(backgroundScope, StandardTestDispatcher(testScheduler)) { query ->
            calls += query
            InlineSnapshot(query, listOf(SearchPerson(query, query)), (1..150).map { hit("$it") })
        }
        model.search(""); advanceTimeBy(1000); runCurrent(); assertTrue(calls.isEmpty())
        model.search("a"); advanceTimeBy(150); model.search("ab"); advanceTimeBy(299); runCurrent()
        assertTrue(calls.isEmpty()); advanceTimeBy(1); runCurrent()
        assertEquals(listOf("ab"), calls)
        assertEquals(100, model.snapshot.value.messages.size)
        model.search("abc"); assertEquals("ab", model.snapshot.value.query)
        model.cancel(); advanceTimeBy(1000); runCurrent(); assertEquals(listOf("ab"), calls)
    }
    @Test fun `late noncooperative result cannot publish after query changes`() = runTest {
        val model = InlineSearchModel(backgroundScope, StandardTestDispatcher(testScheduler)) { query ->
            if (query == "old") withContext(NonCancellable) { delay(1000) }
            InlineSnapshot(query, listOf(SearchPerson(query, query)))
        }
        model.search("old"); advanceTimeBy(300); runCurrent()
        model.search("new"); advanceTimeBy(300); runCurrent(); assertEquals("new", model.snapshot.value.query)
        advanceTimeBy(1000); runCurrent(); assertEquals("new", model.snapshot.value.query)
    }
    @Test fun `category tabs partition a single message snapshot`() {
        assertEquals(setOf(InlineCategory.CHATS, InlineCategory.MEDIA, InlineCategory.LINKS), InlineSearchPolicy.categories(hit("photo", "www.example.com", "image")))
        assertEquals(setOf(InlineCategory.CHATS, InlineCategory.MEDIA), InlineSearchPolicy.categories(hit("video", media = "file", mime = "video/mp4")))
        assertEquals(setOf(InlineCategory.CHATS, InlineCategory.VOICE), InlineSearchPolicy.categories(hit("voice", media = "voice")))
        assertEquals(setOf(InlineCategory.CHATS, InlineCategory.FILES), InlineSearchPolicy.categories(hit("file", media = "file")))
    }
    @Test fun `cancel restores row identity and exact pixel offset even when a preceding row moves`() {
        val memory = SearchScrollMemory()
        memory.capture("b", 1, 37); memory.capture("c", 2, 0)
        assertEquals(2 to 37, memory.restore(listOf("new", "a", "b", "c")))
        assertNull(memory.restore(listOf("a")))
        memory.capture("removed", 9, 6); assertEquals(0 to 6, memory.restore(listOf("a")))
    }
    @Test fun `repository history is bounded account local and clearable`() {
        val history = SearchHistory(); history.bind("account-one")
        repeat(20) { history.remember("q$it", PublicUser("$it", "u$it", "Person", null)) }
        assertEquals(8, history.queries.value.size); assertEquals(10, history.people.value.size)
        history.bind("account-one"); assertEquals(8, history.queries.value.size)
        history.bind(null); assertTrue(history.queries.value.isEmpty()); assertTrue(history.people.value.isEmpty())
    }
}
