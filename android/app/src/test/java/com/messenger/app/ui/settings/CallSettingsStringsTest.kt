package com.messenger.app.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSettingsStringsTest {
    @Test fun `call screen wording matches iOS in English Russian and Ukrainian`() {
        val catalog = Json.parseToJsonElement(File("../../ios/UNYRI/Localizable.xcstrings").readText()).jsonObject.getValue("strings").jsonObject
        val keys = mapOf(
            "call_settings_title" to "Calls",
            "call_settings_saving_title" to "Use less data for calls",
            "call_settings_saving_never" to "Never",
            "call_settings_saving_cellular" to "On cellular",
            "call_settings_saving_always" to "Always",
            "call_settings_saving_never_detail" to "Up to 720p on Wi-Fi and 540p on cellular. Recommended.",
            "call_settings_saving_cellular_detail" to "On cellular, up to 360p and lighter audio.",
            "call_settings_saving_always_detail" to "Up to 360p and lighter audio on every network.",
            "call_settings_saving_note" to "Calls adapt to the network either way and lower quality on a weak connection and in Low Data Mode. Changes apply at once, also during a call.",
            "call_settings_peer_everyone_detail" to "Connect directly whenever possible. Best quality.",
            "call_settings_peer_nobody_detail" to "Always via the server. Your IP address stays hidden.",
            "call_settings_peer_note" to "A direct connection is usually faster, but the other person's device can see your IP address. Via the server, only the server's address is visible. Applies from the next call.",
        )
        for (language in listOf("en", "ru", "uk")) {
            val directory = if (language == "en") "values" else "values-$language"
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$directory/call_settings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to element.textContent.replace("\\'", "'")
            }
            assertEquals(keys.size + 1, strings.size)
            for ((name, key) in keys) {
                val expected = catalog.getValue(key).jsonObject.getValue("localizations").jsonObject.getValue(language)
                    .jsonObject.getValue("stringUnit").jsonObject.getValue("value").jsonPrimitive.content
                assertEquals(expected, strings[name], "$language/$name")
            }
            // Android's audited membership check is stricter than iOS's stale chat-based description.
            val contactDetail = strings.getValue("call_settings_peer_contacts_detail")
            assertTrue(contactDetail.contains(if (language == "en") "Contacts" else "Контакт"))
            assertFalse(contactDetail.contains(if (language == "en") "chat" else "чат"))
        }
    }
}
