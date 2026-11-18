package com.messenger.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class SelfHostingLocalesTest {
    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }
    @Test fun `all self hosting string keys and format arguments exist in every locale`() {
        val res = File("src/main/res")
        val base = strings(File(res, "values/strings_selfhost.xml"))
        assertTrue(base.size > 70)
        val format = Regex("%[1-9]\\$[sd]")
        val locales = res.listFiles()!!.filter { it.isDirectory && File(it, "strings.xml").exists() }
        assertEquals(20, locales.size)
        locales.forEach { folder ->
            val values = strings(File(folder, "strings_selfhost.xml"))
            assertEquals(base.keys, values.keys, folder.name)
            if (folder.name != "values") assertNotEquals(base["sh_invite_code"], values["sh_invite_code"], folder.name)
            values.forEach { (key, value) ->
                assertTrue(value.isNotBlank(), "${folder.name}:$key")
                assertEquals(format.findAll(base.getValue(key)).map { it.value }.toList(), format.findAll(value).map { it.value }.toList(), "${folder.name}:$key")
            }
        }
    }
}
