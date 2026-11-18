package com.messenger.app.ui.theme

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.messenger.app.ui.chats.CapsuleChatStyle
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppColorStylePreferenceTest {
    @Test fun `new and legacy values resolve to monochrome without writing preferences`() {
        for (old in listOf(null, "light", "dark", "system", "unknown", "COLORFUL")) {
            val storage = FakePreferences(old?.let { mapOf(APP_COLOR_STYLE_KEY to it) }.orEmpty())
            val before = storage.values.toMap()
            assertEquals(AppColorStyle.MONOCHROME, AppColorStylePreference(storage.proxy).current())
            assertEquals(before, storage.values)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `both styles persist independently of custom chat and unrelated settings`() {
        val initial = mapOf<String, Any>("chat-text-size" to 21, "subtle-pattern" to true,
            "chat-appearance-overrides-v1" to "custom bubble colors", "unrelated" to "keep")
        val storage = FakePreferences(initial)
        for (choice in AppColorStyle.entries) {
            AppColorStylePreference(storage.proxy).set(choice)
            assertEquals(choice, AppColorStylePreference(storage.proxy).current())
            assertEquals(choice.preferenceValue, storage.values[APP_COLOR_STYLE_KEY])
            assertEquals(initial, storage.values.filterKeys { it != APP_COLOR_STYLE_KEY })
        }
        assertEquals(2, storage.writes)
    }

    @Test fun `malformed preference type falls back without destroying stored values`() {
        val storage = FakePreferences(mapOf(APP_COLOR_STYLE_KEY to true, "unrelated" to 9))
        assertEquals(AppColorStyle.MONOCHROME, AppColorStylePreference(storage.proxy).current())
        assertTrue(storage.values[APP_COLOR_STYLE_KEY] as Boolean)
        assertEquals(0, storage.writes)
        AppColorStylePreference(storage.proxy).set(AppColorStyle.MONOCHROME)
        assertEquals(AppColorStyle.MONOCHROME, AppColorStylePreference(storage.proxy).current())
        assertEquals(9, storage.values["unrelated"])
    }

    @Test fun `colorful RGB is exact and monochrome uses agreed shared luminance`() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x003D4D, 0xAFA4F7)) {
            assertEquals(rgb, AppColorStyle.COLORFUL.materialRgb(rgb))
        }
        for ((rgb, grey) in listOf(0x000000 to 0x000000, 0xFFFFFF to 0xFFFFFF,
            0xFF0000 to 0x363636, 0x00FF00 to 0xB6B6B6, 0x0000FF to 0x121212)) {
            assertEquals(grey, AppColorStyle.MONOCHROME.materialRgb(rgb))
        }
    }

    @Test fun `default outgoing exception covers all bodies and survives style and draft round trips`() {
        val defaults = defaultChatAppearanceSettings()
        val original = resolveChatAppearance(defaults, AppColorStyle.COLORFUL)
        val mono = resolveChatAppearance(defaults, AppColorStyle.MONOCHROME)
        outgoingSurfaces(mono).forEach { assertEquals(0xFF363636.toInt(), it.toArgb()) }
        assertEquals(original.incoming, mono.incoming)
        assertEquals(original.voiceIncoming, mono.voiceIncoming)
        assertEquals(original.fileIncoming, mono.fileIncoming)
        assertEquals(original, mono.copy(outgoing = original.outgoing,
            voiceOutgoing = original.voiceOutgoing, fileOutgoing = original.fileOutgoing))

        // Non-color customizations still use the default monochrome outgoing exception.
        val draft = defaults.copy(fontSize = 22, fontFamily = "serif", cornerRadius = 12f,
            density = "compact", transparency = 37)
        outgoingSurfaces(resolveChatAppearance(draft, AppColorStyle.MONOCHROME)).forEach {
            assertEquals(0xA1363636.toInt(), it.toArgb())
        }
        assertEquals(CapsuleChatStyle.outgoing, original.outgoing)
        assertEquals(CapsuleChatStyle.voiceOutgoing, original.voiceOutgoing)
        assertEquals(CapsuleChatStyle.fileOutgoing, original.fileOutgoing)
        assertEquals(original, resolveChatAppearance(defaults, AppColorStyle.COLORFUL))
        assertEquals(defaultChatAppearanceSettings(), defaults)
    }

    @Test fun `explicit outgoing tints retain their hue and alpha after reversible monochrome rendering`() {
        for (id in listOf("blue", "violet", "green", "graphite")) {
            val chosen = defaultChatAppearanceSettings().copy(outgoingColor = id, transparency = 37)
            val before = chosen.copy()
            val rgb = chatAppearanceOutgoingTint(id).toArgb() and 0xFFFFFF
            for (mode in listOf(AppColorStyle.COLORFUL, AppColorStyle.MONOCHROME, AppColorStyle.COLORFUL)) {
                val resolved = resolveChatAppearance(chosen, mode)
                outgoingSurfaces(resolved).forEach {
                    assertEquals((161 shl 24) or mode.materialRgb(rgb), it.toArgb(), "$id in $mode")
                }
                assertEquals(0xA1202020.toInt(), resolved.incoming.toArgb())
                assertEquals(resolved.incoming, resolved.voiceIncoming)
                assertEquals(resolved.incoming, resolved.fileIncoming)
            }
            assertEquals(before, chosen)
        }
    }

    @Test fun `surface transparency is quantized once for every body and sanitized at the boundary`() {
        for (requested in -1..86) for (mode in AppColorStyle.entries) {
            val clamped = requested.coerceIn(0, 85)
            // Integer percentage oracle, independent of the rendering Float/Double path.
            val alphaByte = (255 * (100 - clamped) + 50) / 100
            val resolved = resolveChatAppearance(defaultChatAppearanceSettings().copy(transparency = requested), mode)
            (outgoingSurfaces(resolved) + listOf(resolved.incoming, resolved.voiceIncoming, resolved.fileIncoming)).forEach {
                assertEquals(alphaByte, it.toArgb() ushr 24, "$requested in $mode")
                assertEquals(alphaByte / 255f, it.alpha, "Unquantized alpha at $requested in $mode")
            }
        }
    }

    private fun outgoingSurfaces(style: ResolvedChatAppearance): List<Color> =
        listOf(style.outgoing, style.voiceBackground(true), style.fileBackground(true))

    private class FakePreferences(initial: Map<String, Any>) {
        val values = initial.toMutableMap()
        var writes = 0
        private val pending = mutableMapOf<String, String?>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { instance, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; instance }
                "apply" -> { pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; pending.clear(); writes++; null }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val proxy = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val value = values[args!![0] as String]
                    if (value != null && value !is String) throw ClassCastException("Not a string")
                    value ?: args[1]
                }
                "edit" -> editor
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }
}
