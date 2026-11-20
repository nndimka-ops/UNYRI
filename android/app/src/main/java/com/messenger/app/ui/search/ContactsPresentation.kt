package com.messenger.app.ui.search

import com.messenger.app.data.profile.ContactEntry
import com.messenger.app.data.profile.ContactsOrdering
import com.messenger.app.domain.model.PublicUser
import java.text.Normalizer
import java.util.Locale

internal data class ContactRow(val entry: ContactEntry, val section: String, val startsSection: Boolean)
internal sealed interface ContactListItem {
    val key: String
    data class Header(val section: String) : ContactListItem { override val key get() = "section:$section" }
    data class Person(val entry: ContactEntry) : ContactListItem { override val key get() = "person:${entry.id}" }
}

internal object ContactsPresentation {
    const val ONLINE = "online"
    fun showsActions(query: String): Boolean = query.isBlank()
    fun showsBookPlaceholder(searchesDirectory: Boolean): Boolean = !searchesDirectory
    private const val CYRILLIC = "АБВГҐДЕЁЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    private val marks = Regex("\\p{M}+")
    fun normalize(value: String): String = Normalizer.normalize(value.trim().removePrefix("@"), Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT)
    fun matchRanges(text: String, query: String): List<IntRange> {
        val term = normalize(query)
        if (term.isEmpty()) return emptyList()
        val folded = StringBuilder()
        val offsets = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            val part = Normalizer.normalize(String(Character.toChars(scalar)), Normalizer.Form.NFKD).replace(marks, "").lowercase(Locale.ROOT)
            folded.append(part)
            repeat(part.length) { offsets += index }
            index += Character.charCount(scalar)
        }
        val value = folded.toString()
        return buildList {
            var start = value.indexOf(term)
            while (start >= 0) {
                val last = offsets[start + term.length - 1]
                add(offsets[start] until last + Character.charCount(text.codePointAt(last)))
                start = value.indexOf(term, start + term.length)
            }
        }
    }
    fun section(name: String): String {
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        if (first in CYRILLIC) return first.toString()
        val latin = Normalizer.normalize(first.toString(), Normalizer.Form.NFKD).replace(marks, "").firstOrNull()
        return if (latin != null && latin in 'A'..'Z') latin.toString() else "#"
    }
    private fun sectionOrder(section: String): Int = when {
        section == ONLINE -> -1
        section == "#" -> 1000
        section[0] in CYRILLIC -> CYRILLIC.indexOf(section[0])
        else -> 100 + section[0].code
    }
    fun rows(entries: List<ContactEntry>, query: String, online: Set<String>, locale: Locale = Locale.getDefault(), legacyLogins: Boolean = true): List<ContactRow> {
        val term = normalize(query)
        val number = com.messenger.app.data.identity.IdentityPolicy.input(query)?.takeIf { it.parameter == "number" }?.value
        val filtered = entries.distinctBy { it.id }.filter {
            term.isEmpty() || normalize(com.messenger.app.domain.model.DisplayNameSanitizer.display(it.user.displayName)).contains(term) || (number != null && it.user.publicNumber == number) || com.messenger.app.data.identity.IdentityPolicy.input(query)?.let { target -> target.parameter == "username" && it.user.publicUsername.equals(target.value, true) } == true
        }
        val sorted = ContactsOrdering.sorted(filtered, locale).sortedBy { sectionOrder(if (it.id in online) ONLINE else section(it.user.visibleName)) }
        var previous: String? = null
        return sorted.map { entry ->
            val section = if (entry.id in online) ONLINE else section(entry.user.visibleName)
            ContactRow(entry, section, section != previous).also { previous = section }
        }
    }
    fun directory(users: List<PublicUser>, contacts: Set<String>, matchingContacts: Set<String>? = null): List<PublicUser> =
        users.distinctBy { it.id }.filterNot { it.id in (matchingContacts ?: contacts) }
    fun items(rows: List<ContactRow>): List<ContactListItem> = buildList {
        rows.forEach { row ->
            if (row.startsSection) add(ContactListItem.Header(row.section))
            add(ContactListItem.Person(row.entry))
        }
    }
}
