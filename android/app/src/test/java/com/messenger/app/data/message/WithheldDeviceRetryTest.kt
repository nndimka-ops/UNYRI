package com.messenger.app.data.message

import com.messenger.app.data.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WithheldDeviceRetryTest {
    @Test fun `stale own-device validation refreshes once and second validation stops`() = runBlocking {
        var attempts = 0
        assertEquals("fresh",retryWithheldOnce({true}) { if (++attempts == 1) throw AuthApiException.Api("validation_error","stale own ID"); "fresh" })
        assertEquals(2,attempts)
        attempts = 0
        assertFailsWith<AuthApiException.Api> { retryWithheldOnce({true}) { attempts++; throw AuthApiException.Api("validation_error","stale again") } }
        assertEquals(2,attempts)
        attempts = 0
        assertFailsWith<AuthApiException.Api> { retryWithheldOnce({false}) { attempts++; throw AuthApiException.Api("validation_error","invalid peer") } }
        assertEquals(1,attempts)
        val wire = Json.encodeToJsonElement(SendMessageRequestDto.serializer(),SendMessageRequestDto("message",emptyList(),withheldDeviceIds=listOf("own-pending"))).jsonObject
        assertEquals(listOf("own-pending"),wire.getValue("withheld_device_ids").jsonArray.map { it.jsonPrimitive.content })
    }
}
