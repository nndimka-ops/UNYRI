package com.messenger.app.ui.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CameraLaunchIntentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val intent = CameraLaunchIntent("nonce", "alice", "https://server.example", "phone", "chat", "content://camera/photo", "/camera/photo.jpg")

    @Test fun persistedConfirmationRetainsCompleteIdentity() {
        val confirmed = intent.copy(confirmed = true)
        val restored = Json.decodeFromString(CameraLaunchIntent.serializer(), Json.encodeToString(CameraLaunchIntent.serializer(), confirmed))
        assertEquals(confirmed, restored)
        assertTrue(restored.belongsTo("alice", "https://server.example", "phone", "chat"))
    }

    @Test fun scopeRequiresAccountServerDeviceAndChatEvenWhenEpochMayRepeat() {
        assertFalse(intent.belongsTo("bob", intent.server, intent.device, intent.chat))
        assertFalse(intent.belongsTo(intent.owner, "https://another.example", intent.device, intent.chat))
        assertFalse(intent.belongsTo(intent.owner, intent.server, "other-phone", intent.chat))
        assertFalse(intent.belongsTo(intent.owner, intent.server, intent.device, "other-chat"))
        assertFalse(intent.belongsTo(null, intent.server, intent.device, intent.chat))
        assertFalse(intent.belongsTo(intent.owner, intent.server, null, intent.chat))
    }

    @Test fun canonicalPrivateAliasMatchesAfterUnconfirmedCancelDeletedTheFile() {
        val dir = temporary.newFolder("camera")
        File(dir, "alias").mkdir()
        val actual = File(dir, "capture_nonce.jpg").apply { writeText("owned fixture") }
        val pending = intent.copy(source = File(dir, "alias/../capture_nonce.jpg").absolutePath)
        assertFalse(pending.source == actual.path)
        assertTrue(pending.matchesSource(actual))
        assertTrue(actual.delete())
        assertTrue(pending.matchesSource(actual))
    }

    @Test fun canonicalMatchRejectsDifferentFileSameNameDifferentRootAndRelativePath() {
        val dir = temporary.newFolder("expected")
        val other = temporary.newFolder("other")
        val expected = File(dir, "capture_nonce.jpg")
        val pending = intent.copy(source = expected.absolutePath)
        assertTrue(pending.matchesSource(expected))
        assertFalse(pending.matchesSource(File(dir, "different.jpg")))
        assertFalse(pending.matchesSource(File(other, expected.name)))
        assertFalse(intent.copy(source = "relative.jpg").matchesSource(File("relative.jpg")))
        assertFalse(intent.copy(source = "").matchesSource(expected))
    }

    @Test fun canonicalAliasDoesNotOverrideThePersistedAccountServerDeviceAndChatScope() {
        val dir = temporary.newFolder("scope")
        File(dir, "alias").mkdir()
        val file = File(dir, "capture_nonce.jpg")
        val pending = intent.copy(source = File(dir, "alias/../capture_nonce.jpg").absolutePath)
        assertTrue(pending.matchesSource(file))
        assertFalse(pending.belongsTo("other", pending.server, pending.device, pending.chat))
        assertFalse(pending.belongsTo(pending.owner, "https://other.example", pending.device, pending.chat))
        assertFalse(pending.belongsTo(pending.owner, pending.server, "other-device", pending.chat))
        assertFalse(pending.belongsTo(pending.owner, pending.server, pending.device, "other-chat"))
    }

}
