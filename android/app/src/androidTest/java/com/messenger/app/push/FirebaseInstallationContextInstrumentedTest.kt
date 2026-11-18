package com.messenger.app.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.messenger.app.MessengerApp
import com.messenger.app.data.auth.AccountWorkspaceContext
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FirebaseInstallationContextInstrumentedTest {
    @Test fun firebaseStorageNeverFollowsTheSelectedWorkspaceOrItsApplicationContext() {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val base = app.baseContext
        val fixture = "firebase-storage-fixture-${UUID.randomUUID()}"
        try {
            val installation = FirebaseInstallationContext(app)
            assertSame(installation, installation.applicationContext)
            val shared = installation.getSharedPreferences(fixture, Context.MODE_PRIVATE)
            assertTrue(shared.edit().putString("value", fixture).commit())
            for (key in listOf("legacy", UUID.randomUUID().toString(), UUID.randomUUID().toString())) {
                val workspace = AccountWorkspaceContext(base, key)
                val firebase = FirebaseInstallationContext(workspace).applicationContext
                assertEquals(base.filesDir, firebase.filesDir)
                assertEquals(base.noBackupFilesDir, firebase.noBackupFilesDir)
                assertEquals(base.cacheDir, firebase.cacheDir)
                assertSame(shared, firebase.getSharedPreferences(fixture, Context.MODE_PRIVATE))
                assertEquals(fixture, firebase.getSharedPreferences(fixture, Context.MODE_PRIVATE).getString("value", null))
                if (key != "legacy") assertNull(workspace.getSharedPreferences(fixture, Context.MODE_PRIVATE).getString("value", null))
                base.deleteSharedPreferences("account_${key}_$fixture")
            }
        } finally { base.deleteSharedPreferences(fixture) }
    }

    @Test fun configuredDefaultFirebaseRetainsAnInstallationContext() {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        assumeTrue(FirebaseAvailability.isConfigured(app))
        val context = FirebaseApp.getInstance().applicationContext
        assertTrue(context is FirebaseInstallationContext)
        assertSame(context, context.applicationContext)
        assertEquals(app.baseContext.filesDir, context.filesDir)
        assertEquals(app.baseContext.noBackupFilesDir, context.noBackupFilesDir)
    }
}
