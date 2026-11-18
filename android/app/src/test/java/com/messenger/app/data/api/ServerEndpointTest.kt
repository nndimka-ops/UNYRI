package com.messenger.app.data.api
import org.junit.Assert.*
import org.junit.Test
class ServerEndpointTest {
    @Test fun publicConnectionDefaultKeepsExistingQaSignalNamespace() {
        val cloud = "https://cloud.example.invalid"
        val legacyDebugAnchor = "http://192.168.0.106:8080"
        val before = ServerEndpoint.signalNamespacePrefix(cloud, legacyDebugAnchor)
        // The transport default is now cloud, while the compatibility anchor
        // remains the same normal-debug origin used by the installed APK.
        val connectionDefault = cloud
        val after = ServerEndpoint.signalNamespacePrefix(connectionDefault, legacyDebugAnchor)
        assertEquals(before, after)
        assertEquals(com.messenger.app.data.signal.SignalNamespaceResolver.prefix(cloud), after)
        assertNotEquals("", after)
        assertNotEquals(after, ServerEndpoint.signalNamespacePrefix(cloud, cloud))
    }
    @Test fun standardDebugAlphaAndReleaseKeepTheirHistoricalPrefixRules() {
        val debug = "http://192.168.0.106:8080"
        val alpha = "https://192.168.0.106:8443"
        val release = "https://cloud.example.invalid"
        for (anchor in listOf(debug, alpha, release)) {
            assertEquals("", ServerEndpoint.signalNamespacePrefix(anchor, anchor))
            val other = "https://another.example.invalid"
            assertEquals(com.messenger.app.data.signal.SignalNamespaceResolver.prefix(other),
                ServerEndpoint.signalNamespacePrefix(other, anchor))
        }
    }
    @Test fun secureLanTransportMigrationKeepsLegacyStorageIdentityAndPrefix() {
        val legacy = "http://192.168.0.106:8080"
        val secure = "https://192.168.0.106:8443"
        val storage = ServerEndpoint.storageIdentity(secure, legacy)
        assertEquals(legacy, storage)
        assertEquals("", ServerEndpoint.signalNamespacePrefix(storage, legacy))
        assertEquals(com.messenger.app.data.signal.SignalNamespaceResolver.prefix(legacy),
            ServerEndpoint.signalNamespacePrefix(storage, "https://cloud.example.invalid"))
    }
    @Test fun distinctSelectedServersNeverShareHashedPrefixes() {
        val anchor = "http://192.168.0.106:8080"
        assertNotEquals(ServerEndpoint.signalNamespacePrefix("https://a.example.invalid", anchor),
            ServerEndpoint.signalNamespacePrefix("https://b.example.invalid", anchor))
    }
    @Test fun storeBuildOldUnboundStateUsesPublicDefault() {
        val cloud = "https://cloud.example.invalid"
        assertEquals(cloud, ServerEndpoint.initialOrigin(null, cloud, cloud, true, false))
    }
    @Test fun alphaAndDebugKeepOldUnboundLanMigration() {
        val cloud = "https://cloud.example.invalid"
        assertEquals("https://192.168.0.106:8443", ServerEndpoint.initialOrigin(null, cloud, cloud, true, true))
        assertEquals("http://127.0.0.1:8080", ServerEndpoint.initialOrigin(null, "http://127.0.0.1:8080", cloud, true, true))
    }
    @Test fun freshInstallsUseBundledDefaultInReleaseAndAlpha() {
        val cloud = "https://cloud.example.invalid"
        for (legacyEnabled in listOf(false, true)) {
            assertEquals(cloud, ServerEndpoint.initialOrigin(null, cloud, cloud, false, legacyEnabled))
        }
        assertEquals("http://10.0.2.2:8080", ServerEndpoint.initialOrigin(null, "http://10.0.2.2:8080", cloud, false, true))
    }
    @Test fun userAddedPrivateHttpsServersSurviveReleaseAndAlphaUpdates() {
        val cloud = "https://cloud.example.invalid"
        for (value in listOf("https://192.168.0.106:8443", "https://10.0.0.7", "https://localhost:8443",
            "https://[::1]:8443", "https://[fd12::1]:8443", "https://server.local", cloud)) {
            val selected = ServerEndpoint.normalize(value, false)
            for (legacyEnabled in listOf(false, true)) for (hasState in listOf(false, true)) {
                assertEquals(selected, ServerEndpoint.initialOrigin(selected, cloud, cloud, hasState, legacyEnabled))
            }
        }
    }
    @Test fun originsAreNormalized() {
        assertEquals("https://example.com",ServerEndpoint.normalize(" HTTPS://Example.com:443/ ",false))
        assertEquals("https://example.com",ServerEndpoint.normalize("example.com",false))
        assertEquals("http://192.168.0.7:8080",ServerEndpoint.normalize("http://192.168.0.7:8080",true))
        assertEquals("wss://test/ws", ServerEndpoint.websocket("wss://test/ws"))
    }
    @Test fun invalidAndPublicCleartextAreRejected() {
        for (value in listOf("", "https://user:password@host", "https://host/path", "https://host?token=x", "https://host#x", "http://example.com", "http://172.32.0.1", "http://256.0.0.1", "https://host:0", "https://host:65536")) assertTrue(value,runCatching { ServerEndpoint.normalize(value,true) }.isFailure)
        assertTrue(runCatching { ServerEndpoint.normalize("http://192.168.0.7",false) }.isFailure)
        assertEquals("https://[::1]:8443",ServerEndpoint.normalize("https://[::1]:8443",false))
    }
}
