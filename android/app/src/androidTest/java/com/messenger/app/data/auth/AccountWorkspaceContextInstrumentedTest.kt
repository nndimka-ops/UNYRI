package com.messenger.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.app.Instrumentation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountWorkspaceContextInstrumentedTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MessengerApp

    @Test fun applicationKeepsFrameworkBaseAndDelegatesEveryWorkspaceStorageOverride() {
        val application = app
        assertEquals("android.app.ContextImpl", application.baseContext.javaClass.name)
        assertSame(application, application.applicationContext)
        val expected = AccountWorkspaceContext(application.baseContext, application.accountWorkspaces.activeKey)
        assertStorage(application, expected, application.accountWorkspaces.activeKey)
        assertEquals(application.baseContext.noBackupFilesDir, AccountWorkspaceContext.installationDirectory(application))
    }

    @Test fun legacyAndTwoNewWorkspacesKeepStorageIsolatedAndSelectionIsProcessFixed() {
        val base = app.baseContext
        val registry = "workspace-registry-fixture-${UUID.randomUUID()}"
        val preferences = base.getSharedPreferences(registry, Context.MODE_PRIVATE)
        val registryContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "local-accounts") preferences else super.getSharedPreferences(name, mode)
        }
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        try {
            val legacy = AccountWorkspaces(registryContext)
            assertEquals("legacy", legacy.activeKey)
            assertTrue(preferences.edit().putString("selected", first).commit())
            val original = AccountWorkspaces(registryContext)
            val workspace = original.wrap(base)
            val application = Instrumentation.newApplication(MessengerApp::class.java, registryContext) as MessengerApp
            assertSame(registryContext, application.baseContext)
            assertTrue(preferences.edit().putString("selected", second).commit())
            assertEquals(first, original.activeKey)
            assertEquals(second, AccountWorkspaces(registryContext).activeKey)
            assertEquals("legacy", legacy.activeKey)
            assertEquals(first, application.accountWorkspaces.activeKey)
            assertStorage(application, AccountWorkspaceContext(registryContext, first), first)
            assertStorage(workspace, AccountWorkspaceContext(base, first), first)
            assertNotEquals(workspace.filesDir, AccountWorkspaceContext(base, second).filesDir)
            assertEquals(base.filesDir, legacy.wrap(base).filesDir)
            assertEquals(base.getDatabasePath("messenger.db"), legacy.wrap(base).getDatabasePath("messenger.db"))
            assertEquals(base.noBackupFilesDir, AccountWorkspaceContext.installationDirectory(workspace))
        } finally {
            base.deleteSharedPreferences(registry)
            for (root in listOf(base.filesDir, base.noBackupFilesDir, base.cacheDir, base.codeCacheDir)) {
                File(root, "accounts/$first").deleteRecursively()
                File(root, "accounts/$second").deleteRecursively()
            }
        }
    }

    private fun assertStorage(actual: Context, expected: Context, key: String) {
        val fixture = "workspace-fixture-${UUID.randomUUID()}"
        try {
            assertTrue(actual.getSharedPreferences(fixture, Context.MODE_PRIVATE).edit().putString("value", fixture).commit())
            assertEquals(fixture, expected.getSharedPreferences(fixture, Context.MODE_PRIVATE).getString("value", null))
            for (shared in listOf("local-accounts", "installation-identity", "native-app-lock", "coco_language", "coco_theme", "client-config-v1")) {
                assertSame(expected.getSharedPreferences(shared, Context.MODE_PRIVATE), actual.getSharedPreferences(shared, Context.MODE_PRIVATE))
            }
            assertEquals(expected.filesDir, actual.filesDir)
            assertEquals(expected.noBackupFilesDir, actual.noBackupFilesDir)
            assertEquals(expected.cacheDir, actual.cacheDir)
            assertEquals(expected.codeCacheDir, actual.codeCacheDir)
            assertEquals(expected.getDatabasePath(fixture), actual.getDatabasePath(fixture))
            val absolute = File(actual.noBackupFilesDir, "$fixture.db").absolutePath
            assertEquals(File(absolute), actual.getDatabasePath(absolute))
            actual.openOrCreateDatabase(fixture, Context.MODE_PRIVATE, null).use { database ->
                assertEquals(expected.getDatabasePath(fixture).canonicalPath, File(database.path).canonicalPath)
                database.execSQL("CREATE TABLE fixture (value INTEGER)")
            }
            // Preserve ContextWrapper's existing databaseList enumeration (physical names).
            assertTrue(actual.databaseList().contains(expected.getDatabasePath(fixture).name))
            actual.openOrCreateDatabase(fixture, Context.MODE_PRIVATE, null, DatabaseErrorHandler { error("Unexpected corruption") }).use { database ->
                database.rawQuery("SELECT count(*) FROM fixture", null).use { assertTrue(it.moveToFirst()) }
            }
            actual.openOrCreateDatabase(absolute, Context.MODE_PRIVATE, null).use { assertEquals(absolute, it.path) }
            assertTrue(actual.deleteDatabase(absolute))
            assertTrue(actual.deleteDatabase(fixture))
            assertFalse(expected.getDatabasePath(fixture).exists())
        } finally {
            actual.deleteDatabase(fixture)
            actual.deleteDatabase(File(actual.noBackupFilesDir, "$fixture.db").absolutePath)
            // deleteSharedPreferences was never redirected; clean up the physical fixture explicitly.
            app.baseContext.deleteSharedPreferences(AccountWorkspaceLayout.preferenceName(key, fixture))
        }
    }
}
