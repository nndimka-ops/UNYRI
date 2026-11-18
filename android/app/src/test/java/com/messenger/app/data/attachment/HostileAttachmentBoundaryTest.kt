package com.messenger.app.data.attachment

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HostileAttachmentBoundaryTest {
    @Test fun `only Android app data aliases are accepted among symbolic ancestors`() {
        assertTrue(AttachmentBoundary.androidSystemAlias("/data/data", "/data/user/0"))
        assertTrue(AttachmentBoundary.androidSystemAlias("/data/user/0", "/data/data"))
        assertFalse(AttachmentBoundary.androidSystemAlias("/data/user/0/app/cache/link", "/data/data"))
        assertFalse(AttachmentBoundary.androidSystemAlias("/data/user/0", "/storage/other-account"))
    }
    @Test fun `opaque identifiers reject every path spelling before construction`() {
        listOf("", ".", "..", "a/b", "a\\b", "/absolute", "x:y", "%2e%2e", "é", "a".repeat(129), "a\u0000b").forEach { assertFailsWith<IllegalArgumentException> { AttachmentBoundary.requireId(it) } }
        listOf("a", "A_0-z", "a".repeat(128)).forEach { assertEquals(it, AttachmentBoundary.requireId(it)) }
    }
    @Test fun `canonical cache containment preserves real recording and share names without aliases`() {
        val base = Files.createTempDirectory("attachment-boundary").toFile()
        try {
            val root = File(base, "account").apply { mkdirs() }
            listOf("voice_170000000.m4a", "video-note-170000000.mp4", "share-123/original фото.jpg", "draft/source.mp4").forEach {
                assertTrue(AttachmentBoundary.contained(root, File(root, it)))
            }
            assertFalse(AttachmentBoundary.contained(root, File(base, "account-other/canary")))
            assertFalse(AttachmentBoundary.contained(root, File(root, "../canary")))
            assertFalse(AttachmentBoundary.contained(root, File(root, "draft/../voice.m4a")))
            assertFailsWith<IllegalArgumentException> { AttachmentBoundary.child(root, "../../canary") }
        } finally { base.deleteRecursively() }
    }
}
