package com.messenger.app.ui.search

import com.messenger.app.data.websocket.PeerPresence
import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContactsUpdatePolicyTest {
    @Test fun exactDiscoveryKeepsAtPrefixAndSupportsShortAliasesWithoutChangingLegacyGate() {
        assertTrue(contactsCanSearchDirectory("1", legacyLogins = false))
        assertFalse(contactsCanSearchDirectory("1", legacyLogins = true))
        assertFalse(contactsCanSearchDirectory(" ", legacyLogins = false))
        assertTrue(contactsCanSearchDirectory("@alice", legacyLogins = true))
        val fresh = listOf(PublicUser("new", "private", "Person", null, publicNumber = "123456789", publicUsername = "alice"))
        val state = SearchUiState.Loaded(fresh, false, query = "@alice")
        assertEquals(fresh, contactsDirectoryPage(state, " @alice ", emptyList()))
        assertEquals(ContactsServerFeedback.BLANK, contactsServerFeedback(state, true, false, "@alice"))
    }

    @Test fun scannedInviteCredentialsNeverFallThroughToPeopleSearch() {
        val link = com.messenger.app.data.identity.IdentityPolicy.link("123456789", "https://example.invalid")!!
        assertEquals(link, contactsScannedQuery(link))
        assertEquals("@alice", contactsScannedQuery("@alice"))
        assertEquals("123456789", contactsScannedQuery("123456789"))
        for (rejected in listOf("https://example.invalid/#server=x&invite_code=secret",
            "https://example.invalid/?invite_code=secret", "invite_code=secret", "https://example.invalid", "bad#secret", "bad?secret")) {
            assertNull(contactsScannedQuery(rejected), rejected)
        }
    }

    @Test fun directoryKeepsPreviousPageThroughQueryEditsAndCacheMissButClearsAuthorizationFailures() {
        val previous = listOf(PublicUser("old", "old", "Old", null))
        val fresh = listOf(PublicUser("new", "new", "New", null))
        assertSame(previous, contactsDirectoryPage(SearchUiState.Idle, "new", previous))
        assertSame(previous, contactsDirectoryPage(SearchUiState.Loading, "new", previous))
        assertSame(previous, contactsDirectoryPage(SearchUiState.Loaded(emptyList(), false, query = "old"), "new", previous))
        assertSame(previous, contactsDirectoryPage(SearchUiState.Error("network", query = "new"), "new", previous))
        assertEquals(fresh, contactsDirectoryPage(SearchUiState.Loaded(fresh, false, query = "new"), "new", previous))
        assertTrue(contactsDirectoryPage(SearchUiState.Loaded(emptyList(), false, query = "new"), "new", previous).isEmpty())
        assertTrue(contactsDirectoryPage(SearchUiState.Error("forbidden", clearResults = true, query = "new"), "new", previous).isEmpty())
        assertTrue(contactsDirectoryPage(SearchUiState.Idle, "ab", previous).isEmpty())
    }
    @Test fun dragAndDecelerationKeepRenderedSectionsThenFlushOnlyNewestClassification() {
        val policy = ContactsClassificationPolicy(setOf("a"))
        assertEquals(setOf("a"), policy.target(setOf("b"), scrolling = true))
        assertEquals(setOf("a"), policy.target(setOf("c"), scrolling = true))
        val settled = policy.target(setOf("c"), scrolling = false)
        assertEquals(setOf("c"), settled)
        assertEquals(setOf("a", "c"), policy.changed(settled))
        policy.commit(settled)
        assertEquals(emptySet(), policy.changed(policy.target(setOf("c"), false)))
        assertEquals(setOf("c"), policy.target(emptySet(), true))
        assertEquals(emptySet(), policy.target(emptySet(), false))
    }

    @Test fun anchorExcludesChangedAndRemovedPeopleAndKeepsExactPixelOffset() {
        val visible = listOf(ContactsVisiblePerson("section:online", -25), ContactsVisiblePerson("person:changed", 4),
            ContactsVisiblePerson("person:removed", 81), ContactsVisiblePerson("person:stable", 158))
        val anchor = ContactsAnchorPolicy.capture(visible, setOf("changed"), setOf("person:changed", "person:stable"))
        assertEquals(ContactsVisiblePerson("person:stable", 158), anchor)
        assertNull(ContactsAnchorPolicy.capture(visible.take(2), setOf("changed"), setOf("person:changed")))
    }

    @Test fun feedbackSlotUsesLoadingThenErrorThenConnectionThenCompletedEmpty() {
        fun feedback(state: SearchUiState, connected: Boolean = true, empty: Boolean = true) =
            contactsServerFeedback(state, connected, empty, "alice")
        val loaded = SearchUiState.Loaded(emptyList(), false, query = "alice")
        assertEquals(ContactsServerFeedback.LOADING, feedback(SearchUiState.Idle))
        assertEquals(ContactsServerFeedback.LOADING, feedback(SearchUiState.Loading))
        assertEquals(ContactsServerFeedback.LOADING, feedback(loaded.copy(refreshing = true)))
        assertEquals(ContactsServerFeedback.LOADING, feedback(loaded.copy(loadingMore = true, pageError = true)))
        assertEquals(ContactsServerFeedback.ERROR, feedback(SearchUiState.Error("failure", query = "alice"), connected = false))
        assertEquals(ContactsServerFeedback.ERROR, feedback(loaded.copy(pageError = true)))
        assertEquals(ContactsServerFeedback.CONNECT, feedback(loaded.copy(cached = true), connected = false))
        assertEquals(ContactsServerFeedback.EMPTY, feedback(loaded))
        assertEquals(ContactsServerFeedback.BLANK, feedback(loaded, empty = false))
        // A query edit cannot flash the old completed-empty response before the VM starts.
        assertEquals(ContactsServerFeedback.LOADING, feedback(loaded.copy(query = "previous")))
        assertEquals(ContactsServerFeedback.LOADING, feedback(SearchUiState.Error("old", query = "previous")))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun presenceChangesEmitOnlyToTheChangedPersonAndHeartbeatsEmitNothing() = runTest {
        val reader = ContactsPresenceReader()
        val a = mutableListOf<ContactPresence>(); val b = mutableListOf<ContactPresence>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.row("a").collect { a += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.row("b").collect { b += it } }
        reader.update(mapOf("a" to PeerPresence(true, null, 100)), emptyMap(), setOf("a", "b"), 10)
        reader.update(mapOf("a" to PeerPresence(true, null, 200)), emptyMap(), setOf("a", "b"), 20)
        assertEquals(2, a.size)
        assertEquals(1, b.size)
        reader.update(mapOf("a" to PeerPresence(false, null, 300)), emptyMap(), setOf("a", "b"), 30)
        assertEquals(3, a.size)
        assertEquals(1, b.size)
    }
}
