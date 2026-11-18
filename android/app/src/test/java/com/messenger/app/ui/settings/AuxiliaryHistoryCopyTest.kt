package com.messenger.app.ui.settings

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuxiliaryHistoryCopyTest {
    @Test fun liveTransferInstructionsFollowTheActionLabelInEveryBatchLocale() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for (locale in listOf("values", "values-ru", "values-uk")) {
            val settings = strings(File(resources, "$locale/b12_settings.xml"))
            val transfer = strings(File(resources, "$locale/history_transfer.xml"))
            // Renaming the CTA must flow into both live instruction contexts, without editing their prose.
            val renamedAction = "Renamed: " + settings.getValue("history_backup_send")
            for (template in listOf(settings.getValue("history_move_step_one"), transfer.getValue("history_transfer_receive_intro"))) {
                val rendered = String.format(Locale.ROOT, template, renamedAction)
                assertTrue(rendered.contains(renamedAction), "$locale instruction does not use the action label")
                assertFalse(rendered.contains("%1\$s"), "$locale has an unresolved placeholder")
            }
        }
    }

    private fun strings(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }
}
