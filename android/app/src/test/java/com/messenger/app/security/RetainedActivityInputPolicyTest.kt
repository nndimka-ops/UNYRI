package com.messenger.app.security

import kotlin.test.*

class RetainedActivityInputPolicyTest {
    @Test fun `unverified launch never keeps route or framework keys`() {
        for (action in listOf(null, "android.intent.action.VIEW", "androidx.navigation.action.DEEP_LINK", "android.intent.action.MAIN")) {
            assertTrue(RetainedActivityInputPolicy.keys(false, action).isEmpty())
        }
    }
    @Test fun `verified route keeps exact canonical fields only`() {
        val allowed = RetainedActivityInputPolicy.keys(true, "android.intent.action.VIEW")
        assertEquals(RetainedActivityInputPolicy.routeKeys, allowed)
        for (key in listOf("deepLinkIds", "deepLinkArgs", "deepLinkExtras", "deepLinkIntent", "deepLinkHandled")) {
            assertFalse("android-support-nav:controller:$key" in allowed)
        }
        assertFalse("reminder_message_id" in allowed)
    }
    @Test fun `share streams survive independently but cannot add navigation keys`() {
        for (action in listOf(RetainedActivityInputPolicy.SEND, RetainedActivityInputPolicy.SEND_MULTIPLE)) {
            assertEquals(setOf(RetainedActivityInputPolicy.STREAM), RetainedActivityInputPolicy.keys(false, action))
            assertEquals(RetainedActivityInputPolicy.routeKeys + RetainedActivityInputPolicy.STREAM,
                RetainedActivityInputPolicy.keys(true, action))
        }
    }
}
