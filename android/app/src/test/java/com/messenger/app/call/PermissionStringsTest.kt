package com.messenger.app.call

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class PermissionStringsTest {
    private val res = File("src/main/res")
    private val keys = setOf(
        "call_permission_microphone", "call_permission_camera", "conference_permission_microphone",
        "call_device_restricted", "error_camera_denied", "gallery_access", "gallery_access_hint",
        "chat_enable_notifications", "camera_audio_required", "history_qr_camera_denied", "history_qr_open_settings",
        "voice_microphone_required", "voice_open_app_settings", "notification_settings_system_on",
        "notification_settings_system_off", "notification_settings_system_detail", "notification_settings_allow",
        "notification_settings_system_open", "local_mode_notifications",
        "viewer_action_failed", // Also shown when legacy gallery-save permission is denied.
    )

    private fun strings(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index) as Element
            node.getAttribute("name") to node.textContent
        }
    }

    private fun permissionKeys(): Set<String> = keys + res.resolve("values").listFiles()!!
        .filter { it.extension == "xml" }.flatMap { strings(it).entries }
        .filter { (key, value) -> key.contains("permission") || key.endsWith("_denied") &&
            Regex("camera|microphone|notification|photo|media|bluetooth|nearby|network", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("(permission|access).*(microphone|camera|photos)|(?:microphone|camera|photo|media|bluetooth|nearby|local network).*permission", RegexOption.IGNORE_CASE).containsMatchIn(value) }
        .map { it.key }

    @Test fun everyPermissionMessageHasRussianAndUkrainianText() {
        for (locale in listOf("values-ru", "values-uk")) {
            val localized = strings(res.resolve("$locale/strings.xml"))
            for (key in permissionKeys()) assertFalse("$locale/$key is missing", localized[key].isNullOrBlank())
        }
    }

    @Test fun everyExistingLocaleDefinesPermissionMessagesInStringsXml() {
        val directories = res.listFiles()!!.filter { it.isDirectory && it.resolve("strings.xml").isFile }
        assertTrue(directories.size >= 20)
        for (directory in directories) {
            val localized = strings(directory.resolve("strings.xml"))
            for (key in permissionKeys()) assertFalse("${directory.name}/$key is missing", localized[key].isNullOrBlank())
        }
    }

    @Test fun permissionMessagesHaveNoDuplicateDefinitionsAcrossResourceFiles() {
        for (directory in res.listFiles()!!.filter { it.resolve("strings.xml").isFile }) {
            val definitions = directory.listFiles()!!.filter { it.extension == "xml" }.flatMap { strings(it).keys }
            for (key in permissionKeys()) assertEquals("${directory.name}/$key", 1, definitions.count { it == key })
        }
    }

    @Test fun conferenceDenialsAndSettingsActionsUseResources() {
        val source = File("src/main/java/com/messenger/app/conference/ConferenceUi.kt").readText()
        val permissionFlow = source.substringAfter("internal fun rememberConferencePermissions()").substringBefore("fun ConferenceOverlay")
        assertFalse(Regex("Text\\(\\s*\"").containsMatchIn(permissionFlow))
        assertTrue(permissionFlow.contains("R.string.conference_permission_microphone"))
        assertTrue(permissionFlow.contains("R.string.call_permission_camera"))
        assertTrue(permissionFlow.contains("R.string.voice_open_app_settings"))
    }
}
