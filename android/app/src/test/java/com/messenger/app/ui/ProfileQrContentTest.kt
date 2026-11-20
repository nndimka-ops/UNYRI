package com.messenger.app.ui

import com.messenger.app.data.identity.IdentityPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileQrContentTest {
    @Test fun captionUsesTheIdentityOriginCarriedByTheUnchangedNumberLink() {
        val number = "604743993"
        for (origin in listOf("https://identity.example:8443", "http://server01:8080")) {
            val content = profileQrContent(number, origin)
            assertEquals(origin, content.origin)
            assertEquals(IdentityPolicy.link(number, origin), content.link)
            assertEquals(origin, IdentityPolicy.parse(content.link!!)?.origin)
            assertEquals(number, IdentityPolicy.parse(content.link!!)?.target?.value)
        }
    }

    @Test fun changedIdentityOriginUpdatesCaptionAndPayloadTogether() {
        val old = profileQrContent("604743993", "https://old.example")
        val current = profileQrContent("604743993", "https://new.example")
        assertEquals("https://old.example", old.origin)
        assertEquals("https://new.example", current.origin)
        assertEquals("https://new.example", IdentityPolicy.parse(current.link!!)?.origin)
    }

    @Test fun withoutAPublicNumberThereIsNoQrAndNoCaption() {
        // B-033: a legacy username may be the private login, so it never becomes a QR.
        assertEquals(ProfileQrContent(null, null), profileQrContent(null, "https://stale.example"))
        assertEquals(ProfileQrContent(null, null), profileQrContent(null, null))
    }
}
