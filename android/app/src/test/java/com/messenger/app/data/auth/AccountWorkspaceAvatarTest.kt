package com.messenger.app.data.auth

import android.content.SharedPreferences
import com.messenger.app.data.avatar.AccountAvatarStore
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AccountWorkspaceAvatarTest {
    private val second = "12345678-1234-1234-1234-123456789abc"
    private val origin = "https://qa.invalid"
    private val alice = AccountAvatarStore.owner(origin, "alice")
    private val bob = AccountAvatarStore.owner(origin, "bob")

    private class Preferences {
        val values = mutableMapOf<String, String>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { self, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; self }
                "remove" -> { values.remove(args!![0] as String); self }
                "commit" -> true
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
    }

    private fun fixture(test: (Preferences, AccountAvatarStore, AccountWorkspaces) -> Unit) {
        val root = Files.createTempDirectory("workspace-avatar").toFile()
        try {
            val preferences = Preferences()
            val store = AccountAvatarStore(root)
            val primary = AccountWorkspaces(preferences.instance, store, eraseInactiveForTest = { _, _ -> })
            primary.record(AuthSessionState.LoggedIn(AuthUser("alice", "alice", "Alice")), origin)
            preferences.values["selected"] = second
            AccountWorkspaces(preferences.instance, store).record(AuthSessionState.LoggedIn(AuthUser("bob", "bob", "Bob")), origin)
            preferences.values["selected"] = "legacy"
            test(preferences, store, primary)
        } finally { root.deleteRecursively() }
    }

    @Test fun removingInactivePurgesOnlyItsOwnerAndRejectsItsRunningEncoder() = fixture { _, store, primary ->
        val a = store.begin("legacy", alice, "1")
        val b = store.begin(second, bob, "1")
        store.write(byteArrayOf(1), a); store.write(byteArrayOf(2), b)
        kotlinx.coroutines.runBlocking { primary.removeInactive(second) }
        assertEquals(listOf("legacy"), primary.accounts().map { it.key })
        assertArrayEquals(byteArrayOf(1), store.read("legacy", alice)?.jpeg)
        assertNull(store.read(second, bob))
        assertFalse(store.write(byteArrayOf(3), b))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { primary.removeInactive("legacy") } }
    }

    @Test fun loggedOutPurgesActiveButUnknownDoesNotAndTheLastAccountSweepsOrphans() = fixture { preferences, store, primary ->
        val a = store.begin("legacy", alice, "1")
        val b = store.begin(second, bob, "1")
        store.write(byteArrayOf(1), a); store.write(byteArrayOf(2), b)
        primary.record(AuthSessionState.Unknown, origin)
        assertNotNull(store.read("legacy", alice))
        primary.record(AuthSessionState.LoggedOut, origin)
        assertNull(store.read("legacy", alice))
        assertFalse(store.write(byteArrayOf(3), a))
        assertNotNull(store.read(second, bob))
        // Include an orphan outside the live registry in the last-account sweep.
        val orphan = store.begin("legacy", alice, "2")
        store.write(byteArrayOf(4), orphan)
        preferences.values["selected"] = second
        AccountWorkspaces(preferences.instance, store).record(AuthSessionState.LoggedOut, origin)
        assertTrue(primary.accounts().isEmpty())
        assertNull(store.read("legacy", alice))
        assertNull(store.read(second, bob))
        assertFalse(store.write(byteArrayOf(5), orphan))
        assertFalse(store.write(byteArrayOf(5), b))
    }
}
