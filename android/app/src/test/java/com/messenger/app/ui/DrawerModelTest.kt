package com.messenger.app.ui

import android.content.SharedPreferences
import com.messenger.app.data.auth.SavedLocalAccount
import com.messenger.app.ui.theme.DrawerChoice
import com.messenger.app.ui.theme.DrawerChoicePreference
import com.messenger.app.ui.theme.DRAWER_CHOICE_KEY
import java.lang.reflect.Proxy
import kotlin.test.*

class DrawerModelTest {
    @Test fun defaultAndPersistenceAreDeviceOwnedAndDoNotRewriteOtherAppearance() {
        val values = mutableMapOf<String, String>("sidebar-appearance-v1" to "existing-draft")
        var writes = 0
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { self, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; self }
                "commit" -> { writes++; true }
                else -> null
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
        assertEquals(DrawerChoice.A, DrawerChoicePreference(preferences).load())
        assertEquals(0, writes)
        assertTrue(DrawerChoicePreference(preferences).save(DrawerChoice.C))
        assertEquals(DrawerChoice.C, DrawerChoicePreference(preferences).load())
        assertEquals("existing-draft", values["sidebar-appearance-v1"])
        assertEquals(1, writes)
        values[DRAWER_CHOICE_KEY] = "future-variant"
        assertEquals(DrawerChoice.A, DrawerChoicePreference(preferences).load())
        assertTrue(DrawerChoicePreference(preferences).save(DrawerChoice.A))
        assertEquals(DrawerChoice.A, DrawerChoicePreference(preferences).load())
    }

    @Test fun accountsAreExclusiveAndNeverRepeatTheCurrentAccount() {
        val me = SavedLocalAccount("me", "https://example.test", "me", "Me")
        val other = me.copy(key = "other", username = "other", displayName = "Other")
        assertEquals(DrawerAccountAction.Add, drawerAccountAction(listOf(me), "me", true))
        assertEquals(DrawerAccountAction.None, drawerAccountAction(listOf(me), "me", false))
        assertEquals(DrawerAccountAction.Switch(other), drawerAccountAction(listOf(me, other), "me", true))
        assertEquals(DrawerAccountAction.Switch(me), drawerAccountAction(listOf(me, other), "other", false))
        assertEquals(DrawerAccountAction.Add, drawerAccountAction(listOf(me, me), "me", true))
    }

    @Test fun badgesHideZeroAndCapAtNinetyNine() {
        assertNull(drawerBadge(-1)); assertNull(drawerBadge(0))
        assertEquals("1", drawerBadge(1)); assertEquals("99", drawerBadge(99))
        assertEquals("99+", drawerBadge(100)); assertEquals("99+", drawerBadge(Int.MAX_VALUE))
    }

    @Test fun choicePreferenceHasTheSameNameInBothAccountWorkspaces() {
        val layout = com.messenger.app.data.auth.AccountWorkspaceLayout
        assertEquals("coco-drawer", layout.preferenceName(layout.LEGACY, "coco-drawer"))
        assertEquals("coco-drawer", layout.preferenceName("00000000-0000-4000-8000-000000000000", "coco-drawer"))
    }

    @org.junit.Test fun longGeneratedHostKeepsTheDomain() {
        org.junit.Assert.assertEquals("8c86b74c….pub.instances.scw.cloud",
            drawerHostLabel("8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud"))
        org.junit.Assert.assertEquals("home.unyri.ru", drawerHostLabel("home.unyri.ru"))
        org.junit.Assert.assertEquals("192.168.0.106", drawerHostLabel("192.168.0.106"))
        // Narrow drawer A keeps the instance prefix and the registrable domain.
        org.junit.Assert.assertEquals("8c86b74c…scw.cloud",
            drawerHostLabel("8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud", narrow = true))
        org.junit.Assert.assertEquals("home.unyri.ru", drawerHostLabel("home.unyri.ru", narrow = true))
    }

    @org.junit.Test fun drawerATakesFortyPercentAndLargerTextWidensIt() {
        org.junit.Assert.assertEquals(164.4f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 411f, 1f), .01f)
        org.junit.Assert.assertEquals(156f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 390f, 1f), .01f)
        // Small phones keep a usable minimum; larger text widens up to 1.5x.
        org.junit.Assert.assertEquals(150f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 320f, 1f), .01f)
        org.junit.Assert.assertEquals(213.72f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 411f, 1.3f), .01f)
        org.junit.Assert.assertEquals(246.6f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 411f, 1.55f), .01f)
        // Very large text: the full-width large layout; C is unchanged.
        org.junit.Assert.assertEquals(387f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.A, 411f, 1.6f), .01f)
        org.junit.Assert.assertEquals(326f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.C, 411f, 1f), .01f)
        org.junit.Assert.assertEquals(296f, com.messenger.app.ui.theme.drawerWidthDp(DrawerChoice.C, 360f, 1f), .01f)
    }
}
