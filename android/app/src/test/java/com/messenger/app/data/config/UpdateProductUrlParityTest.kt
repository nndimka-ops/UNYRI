package com.messenger.app.data.config

import kotlin.test.*

class UpdateProductUrlParityTest {
    private val app = "com.messenger.fixture"
    private val own = "https://play.google.com/store/apps/details?id=$app"
    private fun trusted(raw: String?, fallback: String = "", id: String = app) = AppVersion.trustedUpdateUrl(raw, id, fallback)

    @Test fun `only exact build package in play or market is admitted`() {
        for (valid in listOf(own, "$own&hl=uk", "https://PLAY.GOOGLE.COM/store/apps/details?hl=zh-CN&id=$app", "market://details?id=$app")) {
            assertEquals(valid, trusted(valid))
        }
        assertNull(trusted(own, id = "$app.debug"))
        assertEquals("$own.debug", trusted("$own.debug", id = "$app.debug"))
    }

    @Test fun `other products navigation ambiguous or encoded URLs use publisher fallback`() {
        val invalid = listOf(null, "", "$own.other", "$own&id=$app", "$own&referrer=evil", "$own&hl=en&x=y",
            "$own&hl=", "$own&hl=en%26x=y", "$own#x", "$own&", "$own;referrer=evil",
            own.replace("play.google.com", "play.google.com.evil.invalid"), own.replace("play.google.com", "play.google.com."),
            own.replace("https:", "http:"), own.replace("play.google.com", "user@play.google.com"),
            own.replace("play.google.com", "play.google.com:443"), own.replace("/details?", "/details/extra?"),
            own.replace("/details?", "/%64etails?"), own.replace("$app", "com%2Emessenger.fixture"),
            "https://play.google.com/store/apps", "market://details?id=other", "market://details/?id=$app",
            "market://details?id=$app&hl=en", "market:details?id=$app", "market://other?id=$app", "javascript:alert(1)", own + "x".repeat(512))
        for (raw in invalid) {
            assertNull(trusted(raw), raw)
            assertEquals(own, trusted(raw, own), raw)
        }
        assertNull(trusted(null, "https://play.google.com/store/apps/details?id=another.app"))
        assertNull(trusted(null)); assertEquals(own, trusted(null, own))
    }
}
