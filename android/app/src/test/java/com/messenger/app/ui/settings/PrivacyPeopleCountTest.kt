package com.messenger.app.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PrivacyPeopleCountTest {
    @Test fun noExceptionsUsesNobodyAndPositiveCountsUseLocalePlurals() {
        assertTrue(PrivacyPeoplePolicy.isNobody(0))
        assertTrue(PrivacyPeoplePolicy.isNobody(-1))
        listOf(1, 2, 5, 11, 14, 21, 22, 25, 101, 111).forEach { assertFalse(PrivacyPeoplePolicy.isNobody(it)) }
    }

    @Test fun russianAndUkrainianPeopleAbbreviationsCoverEverySlavicPluralForm() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for ((locale, suffix) in listOf("ru" to "чел.", "uk" to "ос.")) {
            val plurals = File(resources, "values-$locale").listFiles().orEmpty().filter { it.extension == "xml" }.flatMap { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = document.getElementsByTagName("plurals")
                (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("name") == "privacy_people_count_short" }
            }.single()
            val items = plurals.getElementsByTagName("item")
            val values = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("quantity") to item.textContent
            }
            for (quantity in listOf("one", "few", "many", "other")) assertEquals("%1\$d $suffix", values[quantity])
        }
    }
}
