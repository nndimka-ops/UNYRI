package com.messenger.app.conference

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ConferenceDiscoveryTest {
    private val vectors = Json.parseToJsonElement(listOf(File("../shared/conference-discovery-v1-vectors.json"), File("../../shared/conference-discovery-v1-vectors.json")).first { it.isFile }.readText(Charsets.UTF_8)).jsonObject
    private fun binding(context: JsonObject): ConferenceKeyBinding {
        fun text(key: String) = context.getValue(key).jsonPrimitive.content
        return ConferenceKeyBinding(text("origin"), ConferenceKeyRequest(text("request_id"), text("room_id"), text("requester_user_id"), text("requester_device_id"),
            text("provider_user_id"), text("provider_device_id"), text("provider_identity"), text("assignment_revision").toLong(), text("nonce"), text("access_revision").toLong(), text("request_expires_at_rfc3339"), "ready"), text("now_epoch_seconds").toLong())
    }
    @Test fun `shared strict payload vectors exercise the production decoder including duplicate JSON fields`() {
        for (item in vectors.getValue("cases").jsonArray) {
            val c = item.jsonObject
            val context = JsonObject(vectors.getValue("context").jsonObject + (c["context_overrides"]?.jsonObject ?: emptyMap()))
            val result = runCatching { ConferenceKeyPayload.decode(c.getValue("payload_json").jsonPrimitive.content, binding(context)) }
            assertEquals(c.getValue("valid").jsonPrimitive.boolean, result.isSuccess, c.getValue("name").jsonPrimitive.content)
        }
    }
    @Test fun `provider plaintext contains media key only and exactly bound fields`() {
        val context = vectors.getValue("context").jsonObject; val bound = binding(context)
        val valid = vectors.getValue("cases").jsonArray.first().jsonObject.getValue("payload_json").jsonPrimitive.content
        val key = ConferenceKeyPayload.decode(valid, bound)
        val encoded = ConferenceKeyPayload.encode(bound, key).toString(Charsets.UTF_8)
        assertEquals(key, ConferenceKeyPayload.decode(encoded, bound)); assertFalse(encoded.contains("invite_secret"))
        assertFalse(bound.toString().contains(key))
        val catalog = ConferenceAdmission.Catalog(bound.origin, bound.request.roomId, bound.request.id, key)
        assertFalse(catalog.toString().contains(key)); assertFalse(catalog.toString().contains(bound.request.id))
    }
    @Test fun `password bytes are preserved and malformed scalars or control separators are rejected`() {
        for (password in listOf("  pass  ", "🔒🔒🔒🔒🔒🔒", "a".repeat(128))) {
            val options = ConferenceCreateOptions("Room", access = ConferenceAccess.PASSWORD, password = password)
            options.validate(); assertEquals(password, options.password)
        }
        for (password in listOf("short", "a".repeat(129), "valid\npass", "valid\u2028pass", "valid\u2029pass", "valid\uD800pass")) assertFails { ConferenceDiscoveryPolicy.password(password) }
        assertFails { ConferenceDiscoveryPolicy.description("text\u2028text") }
    }
    @Test fun `unknown access fails closed and edit password is only transmitted with set`() {
        assertNull(ConferenceAccess.parse("future_open"))
        assertFails { ConferenceSettingsEdit("Room", "", true, ConferenceAccess.PASSWORD, false, 1, "remove").validate() }
        assertFails { ConferenceSettingsEdit("Room", "", true, ConferenceAccess.PASSWORD, false, 1, "keep", "secret").validate() }
        assertFails { ConferenceCreateOptions("Room", access = ConferenceAccess.OPEN, password = "secret").validate() }
        ConferenceSettingsEdit("Room", "", true, ConferenceAccess.PASSWORD, false, 1, "keep").validate()
    }
}
