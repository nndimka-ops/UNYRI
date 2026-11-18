package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerPickerTest {

    @Test
    fun `currentServerProfile extracts host from full url`() {
        val profile = currentServerProfile("https://api.coco.example:8443/v1")
        assertEquals("api.coco.example:8443", profile.host)
        assertEquals("UNYRI Main", profile.displayName)
    }

    @Test
    fun `currentServerProfile handles emulator host`() {
        assertEquals("10.0.2.2:8080", currentServerProfile("http://10.0.2.2:8080").host)
    }

    @Test
    fun `currentServerProfile strips path`() {
        assertEquals("coco.app", currentServerProfile("https://coco.app/").host)
    }
}
