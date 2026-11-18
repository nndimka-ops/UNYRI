package com.messenger.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.ui.chats.CapsuleChatStyle
import com.messenger.app.ui.chats.SavedChatBubbleShape
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preferences and immutable tokens only: never edits the operator's account or appearance file. */
@RunWith(AndroidJUnit4::class)
class ChatAppearanceSettingsInstrumentedTest {
    private inline fun isolated(block: (SharedPreferences, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "appearance-options-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try { block(prefs, File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")) }
        finally { context.deleteSharedPreferences(name) }
    }

    @Test fun legacyPreferencesRemainAuthoritativeAndReadingNeverWrites() = isolated { prefs, _ ->
        prefs.edit().putInt("chat-text-size", 19).putBoolean("subtle-pattern", false).commit()
        val before = prefs.all.toMap()
        val read = readChatAppearanceSettings(prefs)
        assertEquals(defaultChatAppearanceSettings().copy(fontSize = 19, patterned = false), read)
        assertEquals(before, prefs.all)
        val resolved = resolveChatAppearance(defaultChatAppearanceSettings())
        assertEquals("relaxed", defaultChatAppearanceSettings().density)
        assertEquals(16f, resolved.padX, 0f)
        assertEquals(10f, resolved.padY, 0f)
        assertEquals(7f, resolved.voicePadY, 0f)
        assertEquals(8f, resolved.gap, 0f)
        assertEquals(8f, resolved.groupGap, 0f)
        val standard = resolveChatAppearance(defaultChatAppearanceSettings().copy(density = "default"))
        assertEquals(CapsuleChatStyle.padX, standard.padX, 0f)
        assertEquals(CapsuleChatStyle.padY, standard.padY, 0f)
        assertEquals(CapsuleChatStyle.gap, standard.gap, 0f)
        assertEquals(CapsuleChatStyle.incoming, resolved.incoming)
        assertEquals(CapsuleChatStyle.outgoing, resolved.outgoing)
        assertEquals(CapsuleChatStyle.voiceIncoming, resolved.voiceIncoming)
        assertEquals(CapsuleChatStyle.voiceOutgoing, resolved.voiceOutgoing)
        assertEquals(CapsuleChatStyle.fileIncoming, resolved.fileIncoming)
        assertEquals(CapsuleChatStyle.fileOutgoing, resolved.fileOutgoing)
        assertSame(CapsuleChatStyle.bodyShape(false), resolved.bodyShape(false))
        assertSame(CapsuleChatStyle.bodyShape(true), resolved.bodyShape(true))
        assertSame(CapsuleChatStyle.voiceShape(false), resolved.voiceShape(false))
        assertSame(CapsuleChatStyle.fileShape(true), resolved.fileShape(true))
        assertSame(CapsuleChatStyle.mediaShape, resolved.documentAlbumShape)
    }

    @Test fun savingPublishesOneCompleteSnapshotAndPersistsAcrossReads() = isolated { prefs, disk ->
        val defaults = defaultChatAppearanceSettings()
        val chosen = defaults.copy(fontSize = 20, fontFamily = "monospace", mediumText = !defaults.mediumText,
            lineHeight = 1.55f, density = "compact", cornerRadius = 32f, outgoingColor = "violet",
            transparency = 55, patterned = !defaults.patterned)
        val seen = mutableListOf<ChatAppearanceSettings>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { current, _ ->
            synchronized(seen) { seen += readChatAppearanceSettings(current) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            // Building a draft has no persistence side effects.
            assertTrue(prefs.all.isEmpty())
            resolveChatAppearance(chosen)
            assertTrue(prefs.all.isEmpty())
            saveChatAppearanceSettings(prefs, chosen)
            // commit is a disk-write barrier for preceding apply operations on the same file.
            assertTrue(prefs.edit().commit())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(chosen, readChatAppearanceSettings(prefs))
            assertTrue(disk.isFile)
            assertTrue(disk.readText().contains("monospace"))
            synchronized(seen) {
                assertTrue("At least one preference callback must be delivered", seen.isNotEmpty())
                assertTrue("No listener may observe a half-saved appearance", seen.all { it == chosen })
            }
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun resetRemovesOverridesAndKeepsLaterFontAndWallpaperChoices() = isolated { prefs, _ ->
        saveChatAppearanceSettings(prefs, defaultChatAppearanceSettings().copy(fontSize = 21, density = "relaxed",
            fontFamily = "serif", outgoingColor = "blue", cornerRadius = 16f, transparency = 40))
        assertTrue(prefs.contains(CHAT_APPEARANCE_OVERRIDES_KEY))
        saveChatAppearanceSettings(prefs, defaultChatAppearanceSettings())
        assertFalse(prefs.contains(CHAT_APPEARANCE_OVERRIDES_KEY))
        assertEquals(defaultChatAppearanceSettings(), readChatAppearanceSettings(prefs))
        prefs.edit().putInt("chat-text-size", 22).putBoolean("subtle-pattern", false).commit()
        assertEquals(defaultChatAppearanceSettings().copy(fontSize = 22, patterned = false), readChatAppearanceSettings(prefs))
    }

    @Test fun malformedStoredValuesCannotPoisonTheRenderer() = isolated { prefs, _ ->
        prefs.edit().putInt("chat-text-size", 1000).putBoolean("subtle-pattern", false)
            .putString(CHAT_APPEARANCE_OVERRIDES_KEY,
                """{"version":1,"fontFamily":"downloaded-font","density":"unknown","lineHeight":"NaN","cornerRadius":999,"outgoingColor":"arbitrary","transparency":999}""")
            .commit()
        val read = readChatAppearanceSettings(prefs)
        assertEquals(22, read.fontSize)
        assertEquals(defaultChatAppearanceSettings().fontFamily, read.fontFamily)
        assertEquals(defaultChatAppearanceSettings().lineHeight, read.lineHeight, 0f)
        assertEquals(defaultChatAppearanceSettings().density, read.density)
        assertEquals("default", read.outgoingColor)
        assertEquals(42f, read.cornerRadius!!, 0f)
        assertEquals(85, read.transparency)
        val resolved = resolveChatAppearance(read)
        assertTrue(resolved.lineHeight.isFinite())
        assertEquals(.15f, resolved.outgoing.alpha, .005f)
        prefs.edit().putString(CHAT_APPEARANCE_OVERRIDES_KEY, "not json").commit()
        assertEquals(defaultChatAppearanceSettings().copy(fontSize = 22, patterned = false), readChatAppearanceSettings(prefs))
        prefs.edit().putString(CHAT_APPEARANCE_OVERRIDES_KEY, """{"version":99,"fontFamily":"serif"}""").commit()
        assertEquals(defaultChatAppearanceSettings().fontFamily, readChatAppearanceSettings(prefs).fontFamily)
    }

    @Test fun previewOverridesChangeOnlyRequestedPresentationAndRetainZeroCorners() {
        val defaults = defaultChatAppearanceSettings()
        val chosen = defaults.copy(fontFamily = "serif", mediumText = true, lineHeight = 1.5f,
            density = "compact", cornerRadius = 32f, outgoingColor = "violet", transparency = 40)
        val style = resolveChatAppearance(chosen)
        assertEquals(FontFamily.Serif, style.messageFontFamily)
        assertTrue(style.mediumText)
        assertEquals(1.5f, style.lineHeight, 0f)
        assertEquals(10f, style.padX, 0f)
        assertEquals(6f, style.padY, 0f)
        assertEquals(3f, style.voicePadY, 0f)
        assertEquals(3f, style.gap, 0f)
        assertEquals(3f, style.groupGap, 0f)
        assertEquals(0x002C2655, style.outgoing.toArgb() and 0x00FFFFFF)
        assertEquals(CapsuleChatStyle.incoming.toArgb() and 0x00FFFFFF, style.incoming.toArgb() and 0x00FFFFFF)
        listOf(style.incoming, style.outgoing, style.voiceIncoming, style.voiceOutgoing,
            style.fileIncoming, style.fileOutgoing).forEach { assertEquals(.6f, it.alpha, .005f) }
        for (own in listOf(false, true)) {
            val original = CapsuleChatStyle.bodyShape(own)
            val shape = style.bodyShape(own) as SavedChatBubbleShape
            assertEquals(if (original.topStart.value == 0f) 0f else 32f, shape.topStart.value, 0f)
            assertEquals(if (original.topEnd.value == 0f) 0f else 32f, shape.topEnd.value, 0f)
            assertEquals(if (original.bottomEnd.value == 0f) 0f else 32f, shape.bottomEnd.value, 0f)
            assertEquals(if (original.bottomStart.value == 0f) 0f else 32f, shape.bottomStart.value, 0f)
        }
        val album = style.documentAlbumShape.createOutline(Size(200f, 100f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(32f, album.roundRect.topLeftCornerRadius.x, 0f)
        val relaxed = resolveChatAppearance(defaults.copy(density = "relaxed"))
        assertEquals(16f, relaxed.padX, 0f)
        assertEquals(10f, relaxed.padY, 0f)
        assertEquals(7f, relaxed.voicePadY, 0f)
        assertEquals(8f, relaxed.gap, 0f)
        assertEquals(8f, relaxed.groupGap, 0f)
        // Defaults are immutable. A previous preview never changes the next real chat.
        assertEquals(resolveChatAppearance(defaults), resolveChatAppearance(defaultChatAppearanceSettings()))
    }
}
