package com.messenger.app.data.auth

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class PendingLogoutInstrumentedTest {
    @Test fun journalSurvivesSessionCleanupAndAcknowledgesOnlyOneOriginalServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-logout-" + java.util.UUID.randomUUID()
        val tokenName = "$name-session"
        try {
            val journal = PendingLogoutJournal(context, name)
            val first = journal.save("https://old.example", "test-secret-one")
            val second = journal.save("https://second.example", "test-secret-two")
            com.messenger.app.data.secure.SecureTokenStore(context, tokenName).clearSession()
            val restarted = PendingLogoutJournal(context, name)
            assertEquals(setOf(first, second), restarted.ids())
            assertEquals("https://old.example", org.json.JSONObject(restarted.read(first)!!).getString("origin"))
            assertEquals("test-secret-one", org.json.JSONObject(restarted.read(first)!!).getString("token"))
            restarted.remove(first)
            assertEquals(setOf(second), PendingLogoutJournal(context, name).ids())
            val raw = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
            assertFalse(raw.contains("test-secret")); assertFalse(raw.contains("old.example"))
        } finally { context.deleteSharedPreferences(name); context.deleteSharedPreferences(tokenName) }
    }
}
