package com.messenger.app.data.auth

import java.time.Instant
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DeviceAccessExpiryTest {
    private fun token(issued: Long, expires: Long): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"iat\":$issued,\"exp\":$expires}".toByteArray(Charsets.UTF_8)) + ".signature"

    @Test fun `phone clock skew cannot turn refresh into a request loop`() {
        val serverIssued = 1795089600L
        for (skew in listOf(-86400L, -600L, 0L, 600L, 86400L)) {
            val received = Instant.ofEpochSecond(serverIssued + skew)
            assertEquals(received.plusSeconds(300), DeviceAccessExpiry.localExpiry(token(serverIssued, serverIssued + 300), received))
        }
    }

    @Test fun `oversized access lifetime is locally capped and short lifetime is preserved`() {
        val received = Instant.ofEpochSecond(1795089600L)
        assertEquals(received.plusSeconds(300), DeviceAccessExpiry.localExpiry(token(1, 901), received))
        assertEquals(received.plusSeconds(10), DeviceAccessExpiry.localExpiry(token(1, 11), received))
    }

    @Test fun `invalid lifetime is rejected before changing saved credentials`() {
        for (value in listOf("invalid", token(-1, 60), token(100, 99), token(1, Long.MAX_VALUE))) {
            try { DeviceAccessExpiry.localExpiry(value); fail("Invalid lifetime accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
