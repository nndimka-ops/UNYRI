package com.messenger.app.ui.theme

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarAppearanceSettingsTest {
    @Test fun strictVersionTypesLimitsAndUnknownFields() {
        val defaults = SidebarAppearanceSettings.defaults
        for (raw in listOf(null, "{", "{}", "[]", "{\"version\":2}", "{\"version\":true}", "{\"version\":\"1\"}", " ".repeat(16_385))) {
            assertEquals(defaults, SidebarAppearanceCodec.decode(raw))
        }
        val chosen = SidebarAppearanceCodec.decode("""{"version":1,"geometryWidth":999,"geometryRowHeight":-20,"materialOpacity":0,"materialGradient":"false","typographyFamily":"serif","unknown":123}""")
        assertEquals(80f, chosen.geometryWidth)
        assertEquals(44f, chosen.geometryRowHeight)
        assertEquals(40f, chosen.materialOpacity)
        assertEquals(defaults.materialGradient, chosen.materialGradient)
        assertEquals("serif", chosen.typographyFamily)
        assertEquals(chosen, SidebarAppearanceCodec.decode(SidebarAppearanceCodec.encode(chosen)))
        assertEquals(defaults, SidebarAppearanceCodec.decode("""{"version":1,"geometryWidth":true,"typographySize":"18","iconsTiles":1}"""))
    }

    @Test fun nonfiniteDraftAndPartialHexAreSafeWithoutSaving() {
        val defaults = SidebarAppearanceSettings.defaults
        val draft = defaults.copy(geometryWidth = Float.NaN, materialShadow = Float.POSITIVE_INFINITY,
            iconsSize = Float.NEGATIVE_INFINITY, materialBackground = "#12", patternKind = "arbitrary")
        assertFalse(draft.hasValidColors)
        val clean = draft.sanitized()
        assertTrue(clean.hasValidColors)
        assertEquals(defaults.geometryWidth, clean.geometryWidth)
        assertEquals(defaults.materialShadow, clean.materialShadow)
        assertEquals(defaults.iconsSize, clean.iconsSize)
        assertEquals(defaults.materialBackground, clean.materialBackground)
        assertEquals(defaults.patternKind, clean.patternKind)
        assertEquals(clean, SidebarAppearanceCodec.decode(SidebarAppearanceCodec.encode(draft)))
    }

    @Test fun itemOverridesAreBoundedMandatoryAndIndependentOfMutableDraft() {
        val order = mutableListOf("report", "chats", "report", "unknown")
        val hidden = mutableSetOf("chats", "settings", "help", "unknown")
        val colors = mutableMapOf("chats" to "#aBcDeF", "calls" to "bad", "unknown" to "#FFFFFF")
        val savedItems = SavedSidebarStyle.items.toList()
        val settings = SidebarAppearanceSettings(itemOrder = order, hiddenItems = hidden, itemColors = colors).sanitized()
        order.clear(); hidden.clear(); colors.clear()
        assertEquals(8, settings.itemOrder.size)
        assertEquals(8, settings.itemOrder.toSet().size)
        assertEquals(listOf("report", "chats"), settings.itemOrder.take(2))
        assertEquals(setOf("help"), settings.hiddenItems)
        assertEquals(mapOf("chats" to "#ABCDEF"), settings.itemColors)
        val style = settings.resolved
        assertEquals("report", style.items.first().id)
        assertTrue(style.items.first { it.id == "chats" }.visible)
        assertTrue(style.items.first { it.id == "settings" }.visible)
        assertFalse(style.items.first { it.id == "help" }.visible)
        assertEquals(0xFFABCDEFL, style.items.first { it.id == "chats" }.color)
        assertEquals(savedItems.map { it.id to it.section }.toMap(), style.items.associate { it.id to it.section })
        assertEquals(savedItems, SavedSidebarStyle.items)
        assertTrue(runCatching { (settings.itemOrder as MutableList<String>).clear() }.isFailure)
        assertTrue(runCatching { (settings.hiddenItems as MutableSet<String>).clear() }.isFailure)
        assertTrue(runCatching { (settings.itemColors as MutableMap<String, String>).clear() }.isFailure)
    }

    @Test fun defaultsRetainSavedMetricsAndLightFixedInksRemainReadable() {
        val defaults = SidebarAppearanceSettings.defaults.resolved
        for (width in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 200f, 320f, 390f, 768f)) {
            assertEquals(SavedSidebarStyle.metrics(width), defaults.metrics(width))
        }
        assertEquals(SavedSidebarStyle.materialOpacity, defaults.materialOpacity)
        assertEquals(SavedSidebarStyle.materialBorderWidth, defaults.materialBorderWidth)
        assertEquals(SavedSidebarStyle.materialShadow, defaults.materialShadow)
        val bright = SidebarAppearanceSettings(selectedFill = "#FFFFFF", selectedOpacity = 100f, selectedBadge = "#FFFFFF").resolved
        assertEquals(0xFF222222L, bright.selectedInk)
        assertEquals(0xFF222222L, bright.selectedBadgeInk)
        assertEquals(SavedSidebarStyle.selectedInk, SidebarAppearanceSettings(selectedFill = "#FFFFFF", selectedOpacity = 49f).resolved.selectedInk)
        assertTrue(SidebarAppearanceSettings(materialBackground = "#abcdef").hasValidColors)
    }

    @Test fun readPreviewAndResetDoNotWriteApplyWritesOneOwnedJsonKey() {
        val boundary = PreferenceBoundary()
        boundary.values["language"] = "ru"
        val preference = SidebarAppearancePreference(boundary.preferences)
        val initial = preference.load()
        initial.copy(geometryWidth = 70f).resolved
        SidebarAppearanceSettings.defaults
        assertEquals(0, boundary.transactions)
        boundary.onApply = { boundary.values["unrelated-diagnostics"] = "fresh" }
        assertTrue(preference.save(initial.copy(geometryWidth = 60f)))
        assertEquals(1, boundary.transactions)
        assertEquals(listOf(SIDEBAR_APPEARANCE_KEY), boundary.writtenKeys)
        assertEquals("ru", boundary.values["language"])
        assertEquals("fresh", boundary.values["unrelated-diagnostics"])
        assertEquals(60f, preference.load().geometryWidth)
        assertEquals(1, boundary.transactions)
        boundary.values[SIDEBAR_APPEARANCE_KEY] = 123
        assertEquals(SidebarAppearanceSettings.defaults, preference.load())
        assertEquals(1, boundary.transactions)
    }

    private class PreferenceBoundary {
        val values = mutableMapOf<String, Any>()
        val writtenKeys = mutableListOf<String>()
        var transactions = 0
        var onApply: (() -> Unit)? = null
        val preferences: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString" -> values[args!![0] as String]?.let { it as String } ?: args!![1]
                    "edit" -> editor()
                    "toString" -> "SidebarPreferenceBoundary"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> error("Unexpected preference access ${method.name}")
                }
            } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            lateinit var proxy: SharedPreferences.Editor
            proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                    when (method.name) {
                        "putString" -> { val key = args!![0] as String; writtenKeys += key; pending[key] = args[1] as String; proxy }
                        "apply" -> { transactions += 1; onApply?.invoke(); values.putAll(pending); null }
                        else -> error("Unexpected preference edit ${method.name}")
                    }
                } as SharedPreferences.Editor
            return proxy
        }
    }
}
