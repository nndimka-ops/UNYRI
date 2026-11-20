package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.PublicUserDto
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CallPeerPrivacyTest {
    @Test fun allCachedAccountsAreScrubbedOnceWithoutChangingHistoryOrPublicFields() {
        val root = Files.createTempDirectory("b033-calls-").toFile()
        try {
            val peer = """{"id":"peer","username":"private_login","display_name":"Alice","public_username":"alice","unyri_number":"123456789"}"""
            val first = File(root, "a".repeat(64) + ".json").apply { writeText("""[{"id":"call","peer":$peer}]""") }
            val second = File(root, "b".repeat(64) + ".json").apply { writeText("""{"rows":[{"id":"other","peer":$peer}],"restoredIds":["other"],"restoredClearedBefore":123}""") }
            val policy = File(root, first.name + ".pending-delete-device").apply { writeText("keep") }
            CallPeerPrivacy.migrate(root)
            for (file in listOf(first, second)) {
                assertFalse(file.readText().contains("private_login"))
                assertTrue(file.readText().contains("alice")); assertTrue(file.readText().contains("123456789"))
            }
            val restored = Json.parseToJsonElement(second.readText()).jsonObject
            assertEquals(123, restored.getValue("restoredClearedBefore").jsonPrimitive.int)
            assertEquals("other", restored.getValue("restoredIds").jsonArray.single().jsonPrimitive.content)
            assertEquals("keep", policy.readText())
            val timestamp = first.lastModified()
            CallPeerPrivacy.migrate(root)
            assertEquals(timestamp, first.lastModified())
        } finally { root.deleteRecursively() }
    }

    @Test fun newPeerSnapshotsKeepOnlyExplicitPublicIdentity() {
        val call = CallDto("call", "owner", "peer", "audio", "ended", createdAt = "now",
            peer = PublicUserDto("peer", "private_login", "Alice", publicNumber = "123456789", publicUsername = "alice"))
        val clean = call.withoutPrivatePeerLogin()
        assertEquals("", clean.peer?.username); assertEquals("alice", clean.peer?.publicUsername)
        assertEquals("123456789", clean.peer?.publicNumber); assertEquals("Alice", clean.peer?.displayName)
        assertEquals("private_login", call.peer?.username)
    }
}
