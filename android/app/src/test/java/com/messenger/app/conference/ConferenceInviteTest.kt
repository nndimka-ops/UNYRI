package com.messenger.app.conference

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class ConferenceInviteTest {
    @Test fun `active room reuse requires exact capability independent of link encoding`() {
        val current = ConferenceInvite.fresh("https://coco.example")
        val other = ConferenceInvite.fresh(current.server, current.roomId)
        assertFalse(current.sameCapability(ConferenceInvite(current.server, current.roomId, other.secret, current.mediaKey)))
        assertFalse(current.sameCapability(ConferenceInvite(current.server, current.roomId, current.secret, other.mediaKey)))
        assertFalse(current.sameCapability(ConferenceInvite("https://different.example", current.roomId, current.secret, current.mediaKey)))
        assertTrue(current.sameCapability(ConferenceInvite.parse(current.legacyLink())))
        assertTrue(current.sameCapability(ConferenceInvite.parse(current.link())))
    }
    @Test fun `SDK reconnect cannot recreate enabled tracks without a new encrypted admission`() {
        assertNull(ConferenceReconnectPolicy.getNextRetryDelay(io.livekit.android.room.network.ReconnectContext(0, kotlin.time.Duration.ZERO)))
        assertNull(ConferenceReconnectPolicy.getNextRetryDelay(io.livekit.android.room.network.ReconnectContext(4, kotlin.time.Duration.parse("30s"))))
    }
    @Test fun `invite round trips independent client capabilities`() {
        val invitation = ConferenceInvite.fresh("https://coco.example:443/")
        val parsed = ConferenceInvite.parse(invitation.link())
        assertEquals("https://coco.example", parsed.server)
        assertEquals(invitation.roomId, parsed.roomId)
        assertEquals(invitation.secret, parsed.secret)
        assertEquals(invitation.mediaKey, parsed.mediaKey)
        assertNotEquals(parsed.secret, parsed.mediaKey)
        assertFalse(invitation.toString().contains(invitation.secret))
        assertFalse(invitation.toString().contains(invitation.mediaKey))
    }
    @Test fun `hash is over raw bytes rather than key text`() {
        val bytes = ByteArray(32) { it.toByte() }
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, ConferenceInvite.secretHash(text))
        assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd", ConferenceInvite.secretHash(text))
    }
    @Test fun `rejects ambiguous duplicate unsupported or unencrypted links`() {
        val link = ConferenceInvite.fresh("https://coco.example").legacyLink()
        listOf(
            link.replace("https%3A", "http%3A"), link.replace("#v=1", "#v=2"),
            "$link&i=${ConferenceInvite.fresh("https://coco.example").secret}",
            link.substringBefore("#"), link.replace("coco://conference", "https://conference"),
            link.replace("room=", "extra=x&room="), link.replace("k=", "k=%3D"),
            link.replace("https%3A%2F%2Fcoco.example", "https%3A%2F%2Fuser%40coco.example"),
        ).forEach { assertFailsWith<IllegalArgumentException>(it) { ConferenceInvite.parse(it) } }
    }
    @Test fun `canonical encoding excludes non canonical trailing key bits`() {
        val invitation = ConferenceInvite.fresh("https://coco.example")
        val malformed = invitation.link().substringBeforeLast("k=") + "k=" + "A".repeat(42) + "B"
        assertFailsWith<IllegalArgumentException> { ConferenceInvite.parse(malformed) }
    }
    @Test fun `thirty participants are paginated without duplicates or hidden page overflow`() {
        val ids = (1..30).map(Int::toString)
        for (landscape in listOf(false, true)) {
            val size = ConferenceLayoutPolicy.pageSize(landscape)
            assertEquals(if (landscape) 9 else 6, size)
            val pages = (0 until (ids.size + size - 1) / size).map { ConferenceLayoutPolicy.visible(ids, it, landscape) }
            assertEquals(ids, pages.flatten())
            assertTrue(pages.all { it.size <= size })
        }
    }
    @Test fun `large text preserves bounded pages with fewer rows`() {
        assertEquals(2, ConferenceLayoutPolicy.pageSize(false, 1.8f))
        assertEquals(3, ConferenceLayoutPolicy.pageSize(true, 1.8f))
        assertEquals(6, ConferenceLayoutPolicy.pageSize(false, 1f))
        assertEquals(9, ConferenceLayoutPolicy.pageSize(true, 1f))
        // A 132dp landscape grid budget and a 36dp large-text caption must
        // leave at least 80dp of video, rather than the old 27dp sliver.
        val landscapeTile = ConferenceLayoutPolicy.tileHeightDp(132f, ConferenceLayoutPolicy.pageSize(true, 1.8f), true)
        assertTrue(landscapeTile - 36f >= 80f)
        val portraitTile = ConferenceLayoutPolicy.tileHeightDp(320f, ConferenceLayoutPolicy.pageSize(false, 1.8f), false)
        assertTrue(portraitTile - 36f >= 160f)
    }
}
