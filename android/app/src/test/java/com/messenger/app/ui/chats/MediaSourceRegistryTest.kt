package com.messenger.app.ui.chats
import kotlin.test.*
class MediaSourceRegistryTest {
    private data class Source(val attached: Boolean, val onScreen: Boolean, val image: String)
    @Test fun coveredWorkGateDoesNotInvalidateTheAttachedReturnTarget() {
        val registry = MediaSourceRegistry<Source>({ it.attached && it.onScreen }); val row = Any()
        registry.put("photo", row, Source(true, true, "preview"))
        assertFalse(mediaWorkAllowed(true, true, true))
        assertEquals("preview", registry.get("photo")?.image)
        registry.put("photo", row, Source(true, false, "preview")); assertNull(registry.get("photo"))
        registry.remove("photo", row); assertNull(registry.get("photo"))
    }
    @Test fun recyclingDoesNotRemoveNewOwnerAndRegistryIsBounded() {
        val registry = MediaSourceRegistry<String>({ true }, 2); val old = Any(); val newer = Any()
        registry.put("photo", old, "old"); registry.put("photo", newer, "new"); registry.remove("photo", old)
        assertEquals("new", registry.get("photo")); registry.put("b", Any(), "b"); registry.put("c", Any(), "c"); assertNull(registry.get("photo"))
    }
}
