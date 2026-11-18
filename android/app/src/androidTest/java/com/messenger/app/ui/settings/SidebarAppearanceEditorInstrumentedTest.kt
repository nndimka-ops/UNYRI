package com.messenger.app.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual editor + actual drawer preview, isolated preferences and zero account/network actions. */
class SidebarAppearanceEditorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun isolated(block: (Context, SharedPreferences) -> Unit) {
        val app = compose.activity.applicationContext
        val name = "sidebar-editor-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("capsule-app-style-adopted", true)
            .putInt("capsule-pattern-spec-version", CocoSavedPattern.revision).apply()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(requestedName: String, mode: Int): SharedPreferences =
                if (requestedName == "coco-appearance") prefs else super.getSharedPreferences(requestedName, mode)
        }
        try { block(context, prefs) } finally { app.deleteSharedPreferences(name) }
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("sidebar-appearance-controls").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun draftChangesRealPreviewAndCancelKeepsPreferences() = isolated { context, prefs ->
        val saved = SidebarAppearanceSettings.defaults.copy(geometryWidth = 52f)
        SidebarAppearancePreference(prefs).save(saved)
        val before = prefs.all.toMap()
        var applies = 0; var cancels = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme { Box(Modifier.requiredSize(390.dp, 780.dp)) {
                    SidebarAppearanceEditor(saved, { applies++ }, { cancels++ })
                } }
            }
        }
        fun previewPixels(): IntArray {
            val image = compose.onNodeWithTag("sidebar-appearance-preview").captureToImage().asAndroidBitmap()
            return IntArray(image.width * image.height).also {
                image.getPixels(it, 0, image.width, 0, 0, image.width, image.height); image.recycle()
            }
        }
        val original = previewPixels()
        scrollTo("sidebar-appearance-width")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(64f) }
        compose.onNodeWithTag("sidebar-appearance-controls").performScrollToIndex(0)
        compose.waitForIdle()
        val changed = previewPixels()
        assertEquals(original.size, changed.size)
        assertTrue("Draft width must change actual production drawer pixels", original.indices.count { original[it] != changed[it] } > 100)
        assertEquals("Sliders cannot write preferences", before, prefs.all)
        compose.onNodeWithTag("sidebar-appearance-cancel").performClick()
        compose.runOnIdle { assertEquals(0, applies); assertEquals(1, cancels) }
        assertEquals(before, prefs.all)
    }

    @Test fun arbitraryHexAndWidthApplyOneCompleteDraft() = isolated { context, prefs ->
        val initial = SidebarAppearanceSettings.defaults
        var applies = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme { Box(Modifier.requiredSize(390.dp, 780.dp)) {
                    SidebarAppearanceEditor(initial, { applies++; SidebarAppearancePreference(prefs).save(it) }, {})
                } }
            }
        }
        scrollTo("sidebar-appearance-width")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(62f) }
        scrollTo("sidebar-group-colors").performClick()
        scrollTo("sidebar-field-materialBackground").performClick()
        compose.onNodeWithTag("sidebar-color-hex").performTextReplacement("#12")
        compose.onNodeWithTag("sidebar-color-use").assertIsNotEnabled()
        compose.onNodeWithTag("sidebar-color-hex").performTextReplacement("#123ABC")
        compose.onNodeWithTag("sidebar-color-use").performClick()
        assertFalse("Color picker changes only local draft", prefs.contains(SIDEBAR_APPEARANCE_KEY))
        compose.onNodeWithTag("sidebar-appearance-apply").performClick()
        compose.runOnIdle { assertEquals(1, applies) }
        val actual = SidebarAppearancePreference(prefs).load()
        assertEquals(62f, actual.geometryWidth, .001f)
        assertEquals("#123ABC", actual.materialBackground)
    }

    @Test fun protectedItemsStayAvailableAndResetBackDiscards() = isolated { context, prefs ->
        val initial = SidebarAppearanceSettings.defaults.copy(geometryWidth = 58f, patternKind = "diamonds")
        SidebarAppearancePreference(prefs).save(initial)
        val before = prefs.all.toMap()
        var cancels = 0; var applies = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme { Box(Modifier.requiredSize(390.dp, 780.dp)) {
                    SidebarAppearanceEditor(initial, { applies++ }, { cancels++ })
                } }
            }
        }
        scrollTo("sidebar-group-menu").performClick()
        scrollTo("sidebar-item-visible-chats").assertIsNotEnabled().assertIsOn()
        scrollTo("sidebar-item-visible-settings").assertIsNotEnabled().assertIsOn()
        scrollTo("sidebar-item-up-server").assertIsNotEnabled()
        scrollTo("sidebar-appearance-reset").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, cancels); assertEquals(0, applies) }
        assertEquals(before, prefs.all)
    }

    @Test fun largeTextKeepsApplyAndCancelCompleteAndTappable() {
        compose.setContent {
            CocoTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(280.dp, 680.dp)) {
                    SidebarAppearanceEditor(SidebarAppearanceSettings.defaults, {}, {})
                }
            } }
        }
        for ((tag, resource) in listOf("sidebar-appearance-apply" to R.string.appearance_apply,
            "sidebar-appearance-cancel" to R.string.cancel)) {
            val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            assertTrue(button.fetchSemanticsNode().boundsInRoot.height >= 48f)
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(compose.activity.getString(resource), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            val actual = layout.single()
            assertFalse("$tag must fit completely: size=${actual.size}, constraints=${actual.layoutInput.constraints}, lines=${actual.lineCount}",
                actual.hasVisualOverflow)
        }
    }

    @Test fun legacyVisibilitySeedsDraftAndApplyKeepsAliasesTogether() = isolated { context, prefs ->
        var callsEnabled = false; var savedEnabled = true; var backs = 0; var ownedWrites = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SIDEBAR_APPEARANCE_KEY) ownedWrites++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    CocoTheme { Box(Modifier.requiredSize(390.dp, 780.dp)) {
                        SidebarAppearanceSettingsScreen(showCalls = false, showSaved = false,
                            onShowCallsChange = { callsEnabled = it }, onShowSavedChange = { savedEnabled = it },
                            onBack = { backs++ })
                    } }
                }
            }
            scrollTo("sidebar-group-menu").performClick()
            scrollTo("sidebar-item-visible-calls").assertIsOff().performClick()
            scrollTo("sidebar-item-visible-saved").assertIsOff()
            compose.onNodeWithTag("sidebar-appearance-apply").performClick()
            compose.runOnIdle {
                assertTrue(callsEnabled); assertFalse(savedEnabled)
                assertEquals(1, backs); assertEquals("Apply writes the sidebar JSON once", 1, ownedWrites)
            }
            val actual = SidebarAppearancePreference(prefs).load()
            assertFalse("calls" in actual.hiddenItems)
            assertTrue("saved" in actual.hiddenItems)
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun linePatternSizeChangesRealPixelsWithoutChangingDrawerBounds() {
        val draft = mutableStateOf(SidebarAppearanceSettings.defaults.copy(patternKind = "lines", patternOpacity = 12f, patternSize = 8f))
        compose.setContent { CocoTheme {
            SidebarAppearancePreview(draft.value, Modifier.requiredSize(390.dp, 700.dp), fullSize = true)
        } }
        fun pixels(): IntArray {
            val image = compose.onNodeWithTag("sidebar-appearance-preview").captureToImage().asAndroidBitmap()
            return IntArray(image.width * image.height).also {
                image.getPixels(it, 0, image.width, 0, 0, image.width, image.height); image.recycle()
            }
        }
        val bounds = compose.onNodeWithTag("saved-sidebar").fetchSemanticsNode().boundsInRoot
        val small = pixels()
        compose.runOnIdle { draft.value = draft.value.copy(patternSize = 40f) }
        val large = pixels()
        assertEquals(bounds, compose.onNodeWithTag("saved-sidebar").fetchSemanticsNode().boundsInRoot)
        assertEquals(small.size, large.size)
        assertTrue("Line length must use the actual pattern-size control", small.indices.count { small[it] != large[it] } > 100)
    }
}
