package com.messenger.app.data.profile

import com.messenger.app.domain.model.PublicUser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ContactsOrderingTest {
    private fun person(id: String, name: String, username: String = id) = ContactEntry(PublicUser(id, username, name, null))

    @Test fun `names and usernames use numeric ordering with stable equal-value ties`() {
        val rows = listOf(
            person("ten", "Anna10"), person("two-z", "Anna2", "user10"),
            person("two-b", "Anna02", "user2"), person("two-a", "Anna٢", "user2"),
            person("longer", "Anna1000000000000000000000000000000000000000000000000000"),
            person("long", "Anna999999999999999999999999999999999999999999999999999"),
        )
        assertEquals(listOf("two-a", "two-b", "two-z", "ten", "long", "longer"),
            ContactsOrdering.sorted(rows, Locale.ENGLISH).map { it.id })
    }

    @Test fun `ordering respects locale while filtering keys remain diacritic insensitive`() {
        val rows = listOf(person("a", "Ångström"), person("z", "Zoe"), person("b", "Bob"))
        assertEquals(listOf("a", "b", "z"), ContactsOrdering.sorted(rows, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("b", "z", "a"), ContactsOrdering.sorted(rows, Locale.forLanguageTag("sv-SE")).map { it.id })
        assertEquals("angstrom", ContactsStore.fold("Ångström"))
    }
}
