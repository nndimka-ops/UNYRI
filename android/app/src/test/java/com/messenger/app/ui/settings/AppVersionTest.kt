package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionTest {

    @Test
    fun `version formats name and code`() {
        assertEquals("Version 0.1.0 (1)", AppVersion.display("0.1.0", 1))
        assertEquals("Version 1.2.3 (42)", AppVersion.display("1.2.3", 42))
    }
}
