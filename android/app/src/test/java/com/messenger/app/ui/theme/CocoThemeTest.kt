package com.messenger.app.ui.theme

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CocoThemeTest {
    @Test fun `legacy modes cannot enable light rendering regardless of system`() {
        for (mode in ThemeMode.entries) for (systemDark in listOf(false, true)) {
            assertTrue(themeModeIsDark(mode, systemDark), "$mode with systemDark=$systemDark")
        }
    }

    @Test fun `old persisted light and system preferences are migrated and remain dark`() {
        for (oldValue in listOf(null, "light", "system", "dark", "unknown")) {
            val storage = FakeThemePreferences(oldValue)
            val preference = ThemePreference(storage.proxy)
            assertEquals(ThemeMode.DARK, preference.current())
            assertEquals("dark", storage.value)
            // Simulate a later launch reading the same persisted preference.
            assertEquals(ThemeMode.DARK, ThemePreference(storage.proxy).current())
            for (legacyRequest in ThemeMode.entries) {
                preference.set(legacyRequest)
                assertEquals("dark", storage.value)
                assertEquals(ThemeMode.DARK, preference.current())
            }
        }
    }

    private class FakeThemePreferences(var value: String?) {
        private var staged: String? = null
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { instance, method, args ->
            when (method.name) {
                "putString" -> { assertEquals("theme_mode", args!![0]); staged = args[1] as String?; instance }
                "apply" -> { value = staged; null }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val proxy: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> { assertEquals("theme_mode", args!![0]); value ?: args[1] }
                "edit" -> editor
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }
}
