package com.messenger.app.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** Keep the complete disclosure, including retention and admin limits, aligned with iOS. */
class ServerKnowledgeStringsParityTest {
    @Test fun allFactsAndNotesMatchIosInEnglishRussianAndUkrainian() {
        val ios = File("../../ios/UNYRI")
        val swift = ios.resolve("UI/Profile/ServerKnowledgeView.swift").readText()
        val catalog = Json.parseToJsonElement(ios.resolve("Localizable.xcstrings").readText())
            .jsonObject.getValue("strings").jsonObject
        val keys = Regex("""(?:Fact\(id:|detail:|UnyriSettingsPage\(|UnyriSettingsNote\(|section\()\s*"([^"]+)"""")
            .findAll(swift).map { it.groupValues[1] }.toList()
        assertEquals("26 facts plus title, five sections, intro and footer", 60, keys.size)

        for (locale in listOf("en", "ru", "uk")) {
            val directory = if (locale == "en") "values" else "values-$locale"
            val file = File("src/main/res/$directory/server_knowledge.xml")
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            val actual = (0 until nodes.length).map { index ->
                (nodes.item(index) as Element).textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
            assertFalse("$locale has an empty disclosure", actual.any { it.isBlank() })
            val expected = keys.map { key ->
                val localizations = catalog.getValue(key).jsonObject.getValue("localizations").jsonObject
                val value = localizations[locale]?.jsonObject?.get("stringUnit")?.jsonObject
                    ?.get("value")?.jsonPrimitive?.content
                requireNotNull(value ?: key.takeIf { locale == "en" }) { "Missing iOS $locale/$key" }
                    // Only the provider and OS names differ on Android; all disclosure wording stays shared.
                    .replace("Apple", "Google").replace("iOS", "Android")
            }
            assertEquals("$locale must include every iOS fact and note in source order", expected, actual)
        }
    }
}
