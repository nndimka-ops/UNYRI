package com.messenger.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.R
import com.messenger.app.ui.settings.AppColorStyleSelector
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real root/selector/preference changes with isolated storage, never the user's appearance file. */
class AppColorStyleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Sample(val style: AppColorStyle, val primary: Int, val blue: Int, val mint: Int,
        val danger: Int, val success: Int, val size: Int, val family: String)

    @Composable private fun SamplePalette(seen: AtomicReference<Sample?>) {
        val c = LocalCocoColors.current
        val mode = LocalAppColorStyle.current
        val primary = MaterialTheme.colorScheme.primary
        val appearance = LocalChatAppearanceSettings.current
        SideEffect { seen.set(Sample(mode, primary.toArgb(), c.categoryBlue.toArgb(), c.categoryMint.toArgb(),
            c.danger.toArgb(), c.success.toArgb(), appearance.fontSize, appearance.fontFamily)) }
        Text("Palette probe", color = c.primaryText)
    }

    private inline fun isolated(block: (Context, SharedPreferences) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "coco-color-style-test-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(requestedName: String, mode: Int): SharedPreferences =
                if (requestedName == "coco-appearance") prefs else super.getSharedPreferences(requestedName, mode)
        }
        try { block(context, prefs) } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun actualSelectorUpdatesThemeAndKeepsCustomChat() = isolated { context, prefs ->
        val seen = AtomicReference<Sample?>()
        val chosenChat = defaultChatAppearanceSettings().copy(fontSize = 21, fontFamily = "monospace", outgoingColor = "green")
        prefs.edit().putBoolean("capsule-app-style-adopted", true)
            .putInt("capsule-pattern-spec-version", CocoSavedPattern.revision).apply()
        saveChatAppearanceSettings(prefs, chosenChat)
        val before = prefs.all.toMap()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme {
                    Column {
                        SamplePalette(seen)
                        AppColorStyleSelector(LocalAppColorStyle.current, onSelected = { AppColorStylePreference(prefs).set(it) })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(AppColorStyle.MONOCHROME, seen.get()!!.style)
        assertEquals(CocoMonochromeColors.accent.toArgb(), seen.get()!!.primary)
        assertEquals(seen.get()!!.blue, seen.get()!!.mint)
        compose.onNodeWithTag("app-color-style-colorful").performClick().assertIsSelected()
        compose.waitForIdle()
        assertEquals(AppColorStyle.COLORFUL, seen.get()!!.style)
        assertNotEquals(seen.get()!!.blue, seen.get()!!.mint)
        compose.onNodeWithTag("app-color-style-monochrome").performClick().assertIsSelected()
        compose.waitForIdle()
        val mono = seen.get()!!
        assertEquals(AppColorStyle.MONOCHROME, mono.style)
        assertEquals(CocoMonochromeColors.accent.toArgb(), mono.primary)
        assertEquals(mono.blue, mono.mint)
        assertEquals(CocoDarkColors.danger.toArgb(), mono.danger)
        assertEquals(CocoDarkColors.success.toArgb(), mono.success)
        assertEquals(21, mono.size); assertEquals("monospace", mono.family)
        assertEquals(before, prefs.all.filterKeys { it != APP_COLOR_STYLE_KEY })
        assertEquals(AppColorStyle.MONOCHROME, AppColorStylePreference(prefs).current())
        compose.onNodeWithTag("app-color-style-colorful").performClick().assertIsSelected()
        compose.waitForIdle()
        assertEquals(CocoColorfulColors.accent.toArgb(), seen.get()!!.primary)
        assertEquals(chosenChat.sanitized(), readChatAppearanceSettings(prefs))
    }

    @Test fun selectorLargeTextKeepsBothOptionsCompleteAndTappable() {
        compose.setContent {
            CocoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.requiredSize(280.dp, 390.dp)) {
                        AppColorStyleSelector(AppColorStyle.COLORFUL, onSelected = {})
                    }
                }
            }
        }
        for (mode in AppColorStyle.entries) {
            val tag = "app-color-style-${mode.preferenceValue}"
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            assertTrue(node.fetchSemanticsNode().boundsInRoot.height >= 44f)
            val label = compose.activity.getString(if (mode == AppColorStyle.COLORFUL)
                R.string.app_color_style_colorful else R.string.app_color_style_monochrome)
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(1, results.size)
            assertFalse("Theme labels must wrap instead of clipping", results.single().hasVisualOverflow)
        }
    }
}
