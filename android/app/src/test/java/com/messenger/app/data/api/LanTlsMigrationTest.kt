package com.messenger.app.data.api

import org.junit.Assert.*
import org.junit.Test

class LanTlsMigrationTest {
    private val old = "http://192.168.0.106:8080"
    private val tls = "https://192.168.0.106:8443"
    @Test fun upgradeIsRestrictedToBundledKnownServer() {
        assertEquals(tls, ServerEndpoint.migrationTarget(old, old, true))
        assertNull(ServerEndpoint.migrationTarget(old, old, false))
        assertNull(ServerEndpoint.migrationTarget("http://192.168.0.107:8080", old, true))
        assertNull(ServerEndpoint.migrationTarget(old, "https://example.com", true))
        assertNull(ServerEndpoint.migrationTarget(tls, old, true))
    }
    @Test fun onlyRecordedUpgradeRetainsOriginalStorage() {
        assertEquals(old, ServerEndpoint.storageIdentity(tls, old))
        assertEquals(tls, ServerEndpoint.storageIdentity(tls, null))
        assertEquals("https://example.com", ServerEndpoint.storageIdentity("https://example.com", old))
    }
}
