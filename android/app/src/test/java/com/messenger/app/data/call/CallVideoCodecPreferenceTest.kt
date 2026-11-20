package com.messenger.app.data.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** B-047: hardware H.264 first in both directions; the same vectors as iOS CallNetworkContracts. */
class CallVideoCodecPreferenceTest {
    @Test fun `video codec order matches the shared vectors`() {
        val root = Json.parseToJsonElement(File("../../shared/test-vectors/call-video-codec-order.json").readText()).jsonObject
        val cases = root.getValue("cases").jsonArray
        assertTrue(cases.size >= 8, "all vector cases present")
        for (case in cases) {
            val item = case.jsonObject
            val name = item.getValue("name").jsonPrimitive.content
            val codecs = item.getValue("codecs").jsonArray.map { pair ->
                val values = pair.jsonArray
                values[0].jsonPrimitive.content to values.getOrNull(1)?.jsonPrimitive?.contentOrNull
            }
            val expected = item.getValue("expected").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(expected, CallVideoCodecPreference.order(codecs), "video codec order: $name")
        }
    }

    @Test fun `an Android offer list gets H264 ahead of software VP8`() {
        val androidDefault = listOf("VP8" to null, "VP9" to null, "H264" to "640c1f", "H264" to "42e01f", "rtx" to null)
        val order = CallVideoCodecPreference.order(androidDefault).map { androidDefault[it].first }
        assertEquals(listOf("H264", "H264", "VP8", "VP9", "rtx"), order)
    }
}
