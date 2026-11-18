package com.messenger.app.capture

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DirectCaptureWorkspaceTest {
    private class CacheContext(private val directory: File, private val app: Context? = null) : ContextWrapper(null) {
        override fun getCacheDir(): File = directory
        override fun getApplicationContext(): Context = app ?: this
        override fun getPackageName(): String = "com.messenger.app"
    }

    private val id = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val owner = CaptureOwner("alice", "https://example.invalid", "phone")
    private val uri = "content://com.messenger.app.fileprovider/camera/capture_$id.jpg"
    private fun temporaryDirectory(prefix: String): File {
        val parent = File("build/tmp/capture-workspace-tests").apply { check(mkdirs() || isDirectory) }
        return Files.createTempDirectory(parent.toPath(), prefix).toFile()
    }

    private fun record(root: File) = PendingExternalCapture(id, owner, uri, false,
        directSource = File(root, "capture_$id.jpg").path, destination = "chat")

    @Test fun rawActivityAndProviderContextsUseTheApplicationAccountCache() {
        val root = temporaryDirectory("account-camera")
        try {
            val accountCache = File(root, "cache/accounts/$id")
            val app = CacheContext(accountCache)
            val activity = CacheContext(File(root, "cache"), app)
            val provider = CacheContext(File(root, "cache"), app)
            val camera = File(accountCache, "camera")
            val pending = record(camera)
            assertEquals(camera, captureCacheDirectory(activity, "camera"))
            assertEquals(camera, captureCacheDirectory(provider, "camera"))
            for (context in listOf(activity, provider, app)) {
                val files = DirectCaptureFiles.from(context, pending) { uriContext, file ->
                    assertSame(app, uriContext)
                    assertEquals(File(camera, "capture_$id.jpg").canonicalFile, file)
                    uri
                }
                assertNotNull(files)
                assertEquals(File(camera, "capture_$id.mp4").canonicalFile, files.video)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun anotherWorkspaceAndLegacyRootCannotValidateTheAccountCapture() {
        val root = temporaryDirectory("isolated-camera")
        try {
            val rawCache = File(root, "cache")
            val pending = record(File(rawCache, "accounts/$id/camera"))
            val legacy = CacheContext(rawCache)
            val otherAccount = CacheContext(File(rawCache, "accounts/11111111-1111-4111-8111-111111111111"))
            for (context in listOf(legacy, otherAccount)) {
                assertNull(DirectCaptureFiles.from(context, pending) { _, _ -> uri })
            }
            val legacyRecord = record(File(rawCache, "camera"))
            assertNotNull(DirectCaptureFiles.from(legacy, legacyRecord) { _, _ -> uri })
        } finally { root.deleteRecursively() }
    }
}
