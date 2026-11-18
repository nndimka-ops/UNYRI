package com.messenger.app.data.attachment

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class LocalMediaFilesTest {
    @Test fun onlyOwnedCopiesCanBeRestoredAsDurableComposerDrafts() {
        val root = Files.createTempDirectory("composer-durable-boundary").toFile()
        try {
            val copies = File(root, "media-drafts")
            val copy = File(copies, "${java.util.UUID.randomUUID()}/video.mp4")
            val retainedFile = File(root, "external-original.mp4")
            val references = listOf(copy.toURI().toString(), retainedFile.toURI().toString(), "content://documents/original")
            assertEquals(listOf(copy.toURI().toString()), references.filter { isOwnedDraftReference(copies, it) })
        } finally { root.deleteRecursively() }
    }
    @Test fun startupScanDeletesAbandonedBytesAndProtectsCommittedDraftAndOutboxSources() {
        val root = Files.createTempDirectory("composer-recovery").toFile()
        try {
            fun source() = File(root, "${java.util.UUID.randomUUID()}/source.pdf").apply { parentFile.mkdirs(); writeBytes(ByteArray(1024 * 1024)) }
            val draft = source(); val upload = source(); val abandoned = source()
            val unrelated = File(root, "other-feature/source.pdf").apply { parentFile.mkdirs(); writeText("keep") }
            val references = mediaDraftPaths("fileDrafts", JsonArray(listOf(JsonPrimitive(draft.toURI().toString()))).toString()) +
                mediaDraftPaths("upload:pending", buildJsonObject { put("uri", upload.toURI().toString()) }.toString())
            val candidates = ownedDraftCandidates(root)
            assertEquals(setOf(draft.absolutePath, upload.absolutePath, abandoned.absolutePath), candidates)
            candidates.forEach { discardUnreferencedDraft(root, File(it), references) }
            assertTrue(draft.exists()); assertTrue(upload.exists()); assertTrue(unrelated.exists())
            assertFalse(abandoned.exists()); assertFalse(abandoned.parentFile.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptOwnershipFailsClosed() {
        for (name in listOf("mediaDrafts", "fileDrafts", "voiceDraft", "cameraSendDraft", "upload:retry")) {
            assertNull(mediaDraftPathsOrNull(name, "{invalid"), name)
            assertNull(mediaDraftPathsOrNull(name, "{}"), name)
        }
        assertEquals(emptySet(), mediaDraftPathsOrNull("text", "{invalid"))
    }

    @Test fun stagedFileKeepsItsOriginalNameAndRealSize() {
        val root = Files.createTempDirectory("staged-metadata").toFile()
        try {
            val document = File(root, "report with spaces.docx").apply { writeBytes(ByteArray(513)) }
            assertEquals(document.name to 513L, localFileMetadata("file", document.path))
            assertNull(localFileMetadata("content", document.path))
        } finally { root.deleteRecursively() }
    }

    @Test fun allDurableOwnersProtectTheirFilesUntilRemoved() {
        val root = Files.createTempDirectory("draft-owners").toFile()
        try {
            val file = File(root, "id/photo.png").apply { parentFile.mkdirs(); writeText("image") }
            val uri = file.toURI().toString()
            val owners = listOf(
                "fileDraftUri" to uri,
                "mediaDrafts" to JsonArray(listOf(JsonPrimitive(uri))).toString(),
                "fileDrafts" to JsonArray(listOf(JsonPrimitive(uri))).toString(),
                "cameraSendDraft" to buildJsonObject { put("uri", uri) }.toString(),
                "voiceDraft" to buildJsonObject { put("path", file.path) }.toString(),
                "upload:pending-id" to buildJsonObject { put("uri", uri) }.toString(),
            )
            for ((name, value) in owners) {
                assertFalse(discardUnreferencedDraft(root, file, mediaDraftPaths(name, value)), name)
                assertTrue(file.exists())
            }
            assertTrue(mediaDraftPaths("draft", uri).isEmpty())
            assertTrue(mediaDraftPaths("mediaDrafts", "malformed").isEmpty())
            assertTrue(discardUnreferencedDraft(root, file, emptySet()))
            assertFalse(file.parentFile.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun cleanupRejectsPathsOutsideOwnedDraftDirectories() {
        val root = Files.createTempDirectory("draft-containment").toFile()
        try {
            val drafts = File(root, "drafts").apply { mkdirs() }
            val external = File(root, "private.txt").apply { writeText("keep") }
            val immediate = File(drafts, "metadata.txt").apply { writeText("keep") }
            assertFalse(discardUnreferencedDraft(drafts, external, emptySet()))
            assertFalse(discardUnreferencedDraft(drafts, immediate, emptySet()))
            assertFalse(discardUnreferencedDraft(drafts, drafts, emptySet()))
            assertTrue(external.exists()); assertTrue(immediate.exists())
        } finally { root.deleteRecursively() }
    }
}
