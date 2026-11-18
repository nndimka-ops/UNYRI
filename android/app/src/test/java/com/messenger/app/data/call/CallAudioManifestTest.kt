package com.messenger.app.data.call

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** AudioService silently ignores setMode without this install-time permission.
 * Pure route-policy tests cannot detect its absence from the packaged app. */
class CallAudioManifestTest {
    @Test
    fun `communication audio routing permission is declared without an API cutoff`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
        assertNotNull(manifest, "Read the app's real main manifest")
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val declarations = parser.newDocumentBuilder().parse(manifest).getElementsByTagName("uses-permission")
        val android = "http://schemas.android.com/apk/res/android"
        val declaration = (0 until declarations.length)
            .map { declarations.item(it) as org.w3c.dom.Element }
            .singleOrNull { it.getAttributeNS(android, "name") == "android.permission.MODIFY_AUDIO_SETTINGS" }
        assertNotNull(declaration, "Call mode/private routing requires MODIFY_AUDIO_SETTINGS on the installed app")
        assertEquals("", declaration.getAttributeNS(android, "maxSdkVersion"), "Routing is needed on modern Android too")
        assertEquals("", declaration.getAttributeNS("http://schemas.android.com/tools", "node"), "Do not remove the permission during manifest merging")
    }
}
