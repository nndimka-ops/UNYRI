package com.messenger.app.data.auth

import com.messenger.app.data.api.RefreshRequestDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMetadataTest {
    @Test fun userFacingNamePrefersGlobalThenSystemThenManufacturerModel() {
        assertEquals("POCO X6", DeviceNamePolicy.choose(" POCO X6 ", "Backup", "Xiaomi", "23122PCD1G"))
        assertEquals("My phone", DeviceNamePolicy.choose(" ", "My phone", "Xiaomi", "23122PCD1G"))
        assertEquals("Xiaomi 23122PCD1G", DeviceNamePolicy.choose(null, null, "Xiaomi", "23122PCD1G"))
        assertEquals("Samsung Galaxy", DeviceNamePolicy.choose(null, null, "samsung", "Samsung Galaxy"))
    }

    @Test fun refreshWireCarriesVersionMetadataAcceptedByTheServer() {
        val body = Json.encodeToString(RefreshRequestDto.serializer(),
            RefreshRequestDto("fixture-token", "fixture-request", appVersion = "0.4.17", osVersion = "16"))
        assertTrue(body.contains("\"app_version\":\"0.4.17\""))
        assertTrue(body.contains("\"os_version\":\"16\""))
        assertTrue(body.contains("\"request_id\":\"fixture-request\""))
    }
}
