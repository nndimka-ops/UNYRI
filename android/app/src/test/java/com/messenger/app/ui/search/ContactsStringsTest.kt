package com.messenger.app.ui.search

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ContactsStringsTest {
    private val root = File("src/main/res")
    private val required = setOf("contacts_invite_unyri", "contacts_load_failed", "contacts_empty_book",
        "contacts_short_query", "contacts_connect_search", "contacts_no_users", "contacts_search_failed", "contacts_scan_qr")

    @Test fun contactsStatesExistInEnglishRussianUkrainianAndEveryDrawerLocale() {
        val locales = root.listFiles()!!.filter { it.isDirectory && File(it, "drawer_contacts.xml").exists() }
        assertTrue(locales.map { it.name }.containsAll(listOf("values", "values-ru", "values-uk")))
        for (locale in locales) {
            val values = strings(locale)
            assertTrue(values.keys.containsAll(required), "${locale.name}: missing ${required - values.keys}")
            required.forEach { assertTrue(values.getValue(it).isNotBlank(), "${locale.name}/$it") }
            if (locale.name in setOf("values", "values-ru", "values-uk")) {
                setOf("sidebar_qr_title", "search_no_results", "retry").forEach {
                    assertTrue(!values[it].isNullOrBlank(), "${locale.name}/$it")
                }
            }
        }
        val english = strings(File(root, "values"))
        assertEquals("No results.", english["search_no_results"])
        assertEquals("No users found.", english["contacts_no_users"])
        assertEquals("Your contacts will appear here. Add someone by @username or invite them to UNYRI.", english["contacts_empty_book"])
    }

    private fun strings(folder: File): Map<String, String> = buildMap {
        folder.listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }
}
