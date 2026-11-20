package com.messenger.app.ui.search

import com.messenger.app.data.profile.ContactEntry
import com.messenger.app.data.websocket.PeerPresence
import com.messenger.app.domain.model.PublicUser
import java.util.Locale
import kotlin.test.*

class ContactsPresentationTest {
    private fun entry(id: String, name: String, username: String = id) = ContactEntry(PublicUser(id, username, name, null))
    @Test fun directorySearchDoesNotReserveBookActionsOrEmptyBookNoticesAbovePeople() {
        assertTrue(ContactsPresentation.showsActions(""))
        assertTrue(ContactsPresentation.showsActions("  "))
        for (query in listOf("alice", "@alice", "123456789")) {
            assertFalse(ContactsPresentation.showsActions(query), query)
        }
        assertFalse(ContactsPresentation.showsBookPlaceholder(searchesDirectory = true))
        assertTrue(ContactsPresentation.showsBookPlaceholder(searchesDirectory = false))
    }
    @Test fun exactIdentityResultRemainsVisibleForContactWithoutPublicMetadata() {
        val contact = entry("alice-id", "Alice", "hidden_login")
        val matches = ContactsPresentation.rows(listOf(contact), "123456789", emptySet(), legacyLogins = false)
        assertTrue(matches.isEmpty())
        val resolved = contact.user.copy(username = "alice", publicNumber = "123456789", publicUsername = "alice")
        val allContacts = setOf(contact.id)
        assertEquals(listOf(resolved), ContactsPresentation.directory(listOf(resolved), allContacts,
            matches.map { it.entry.id }.toSet()))
        assertTrue(ContactsPresentation.directory(listOf(resolved), allContacts, setOf(contact.id)).isEmpty())
        assertTrue(ContactsPresentation.directory(listOf(resolved), allContacts).isEmpty()) // Legacy behavior.
    }
    @Test fun freshOnlineComesFirstThenCyrillicIncludingYoThenLatinThenOther() {
        val entries = listOf(entry("z", "Zoe"), entry("yo", "Ёлка"), entry("e", "Ева"), entry("ru", "Борис"), entry("a", "Alice"), entry("hash", "123"))
        val rows = ContactsPresentation.rows(entries, "", setOf("z"), Locale.ENGLISH)
        assertEquals(listOf("z", "ru", "e", "yo", "a", "hash"), rows.map { it.entry.id })
        assertEquals(listOf("online", "Б", "Е", "Ё", "A", "#"), rows.map { it.section })
        assertTrue(rows.all { it.startsSection })
        assertEquals("z", ContactsPresentation.rows(entries, "", emptySet(), Locale.ENGLISH).last { it.section != "#" }.entry.id)
    }
    @Test fun filtersNamesAndHandlesWithNormalizationAndDeduplicatesServerPages() {
        val boris = ContactEntry(entry("boris", "Борис", "private_login").user.copy(publicUsername = "boris_qa"))
        val entries = listOf(boris, entry("anna", "Анна"), entry("accent", "José"), boris)
        assertEquals(listOf("boris"), ContactsPresentation.rows(entries, "  @BORIS_qa ", emptySet()).map { it.entry.id })
        assertEquals(listOf("boris"), ContactsPresentation.rows(entries, "бор", emptySet()).map { it.entry.id })
        assertEquals(listOf("accent"), ContactsPresentation.rows(entries, "jose", emptySet()).map { it.entry.id })
        assertEquals(3, ContactsPresentation.rows(entries, "", emptySet()).size)
        val server = listOf(boris.user, entries[1].user, entries[1].user)
        assertEquals(listOf("anna"), ContactsPresentation.directory(server, setOf("boris")).map { it.id })
    }
    @Test fun oneHeaderPerSectionAndPersonKeysSurviveOnlinePromotion() {
        val entries = listOf(entry("a1", "Alice"), entry("a2", "Anna"))
        val before = ContactsPresentation.rows(entries, "", emptySet())
        val after = ContactsPresentation.rows(entries, "", setOf("a2"))
        assertEquals(listOf(true, false), before.map { it.startsSection })
        assertEquals(before.map { it.entry.id }.toSet(), after.map { it.entry.id }.toSet())
        assertEquals(listOf("a2", "a1"), after.map { it.entry.id })
    }
    @Test fun heartbeatDoesNotChangePresentationAndExpiredOrHiddenCannotReviveOnline() {
        val reader = ContactsPresenceReader()
        val row = reader.row("a")
        reader.update(mapOf("a" to PeerPresence(true, null, 100)), emptyMap(), setOf("a"), 10)
        val initial = row.value
        reader.update(mapOf("a" to PeerPresence(true, "2026-11-14T12:00:00Z", 200)), emptyMap(), setOf("a"), 20)
        assertEquals(initial, row.value)
        assertEquals(setOf("a"), reader.online.value)
        reader.update(mapOf("a" to PeerPresence(true, null, 200)), emptyMap(), setOf("a"), 201)
        assertFalse(row.value.online); assertEquals(emptySet(), reader.online.value)
        reader.update(mapOf("a" to PeerPresence(false, null, 300)), emptyMap(), setOf("a"), 210)
        assertTrue(row.value.hidden); assertNull(row.value.lastSeen)
    }

    @Test fun expiryAlsoInvalidatesAPrefetchedRowsOldOnlinePresentation() {
        val reader = ContactsPresenceReader()
        val row = reader.row("a")
        reader.update(mapOf("a" to PeerPresence(true, null, 100)), emptyMap(), setOf("a"), 10)
        reader.update(emptyMap(), emptyMap(), emptySet(), 101)
        assertFalse(row.value.online)
    }

    @Test fun highlightingPreservesTheFullIdentityAndMatchesFoldedAccents() {
        val text = "José " + "long ".repeat(80)
        assertEquals(listOf(0..3), ContactsPresentation.matchRanges(text, "jose"))
        assertEquals(listOf(1..4), ContactsPresentation.matchRanges("@Boris", "@bori"))
        assertEquals(emptyList(), ContactsPresentation.matchRanges(text, ""))
    }
}
