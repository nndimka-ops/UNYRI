package com.messenger.app.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.settings.SettingsScaffold
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real shared heading geometry; no account, navigation route, preferences, or network. */
@OptIn(ExperimentalTestApi::class)
class CocoPageHeaderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Example(
        val id: String,
        val width: Int = 320,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val title: String = "Внешний вид",
        val actions: Int = 0,
    )

    @Test fun reportedAppearanceShellUsesCenteredTitleAndOneWorkingBack() {
        var back = 0
        compose.setContent {
            CocoTheme {
                Box(Modifier.requiredSize(320.dp, 660.dp).testTag("header-fixture")) {
                    SettingsScaffold("Внешний вид", onBack = { back++ }) { Text("Settings body") }
                }
            }
        }
        val fixture = bounds("header-fixture")
        val title = bounds("coco-page-title")
        assertEquals(fixture.center.x, title.center.x, 1.1f)
        val backLabel = compose.activity.getString(R.string.back)
        compose.onAllNodesWithContentDescription(backLabel).assertCountEquals(1)
        compose.onNodeWithContentDescription(backLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, back) }
        screenshot("appearance-settings-shell-320")
    }

    @Test fun pageTitlesStayCenteredAt320LargeTextRtlAndWithUnequalActions() {
        val examples = listOf(
            Example("appearance-320"),
            Example("appearance-320-large", fontScale = 2f),
            Example("privacy-320-large", fontScale = 2f, title = "Конфиденциальность и безопасность"),
            Example("save-320", actions = 1, title = "Редактировать профиль"),
            Example("save-320-large", fontScale = 2f, actions = 1, title = "Редактировать профиль"),
            Example("selection-320", actions = 2, title = "Выбрано: 3"),
            Example("selection-device-width", width = compose.activity.resources.configuration.screenWidthDp.coerceAtMost(480), actions = 2, title = "Выбрано: 3"),
            Example("rtl-320", rtl = true, title = "المظهر"),
            Example("rtl-320-large-actions", rtl = true, fontScale = 2f, actions = 2, title = "الخصوصية والأمان"),
        )
        val current = mutableStateOf(examples.first())
        var back = 0
        var saves = 0
        var deletes = 0
        var done = 0
        compose.setContent {
            val item = current.value
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, item.fontScale),
                LocalLayoutDirection provides if (item.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                CocoTheme {
                    Box(Modifier.requiredSize(item.width.dp, 660.dp).testTag("header-fixture")) {
                        CocoPageHeader(
                            title = item.title,
                            modifier = Modifier.testTag("page-header").padding(horizontal = 16.dp),
                            start = { CocoBackButton(onClick = { back++ }, modifier = Modifier.testTag("page-back")) },
                            end = {
                                if (item.actions == 1) TextButton(onClick = { saves++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("page-save")) { Text("Сохранить") }
                                if (item.actions == 2) {
                                    TextButton(onClick = { deletes++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("page-delete")) { Text("Удалить") }
                                    TextButton(onClick = { done++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("page-done")) { Text("Готово") }
                                }
                            },
                        )
                    }
                }
            }
        }
        for (example in examples) {
            compose.runOnIdle { current.value = example }
            compose.waitForIdle()
            val heading = bounds("coco-page-title")
            val page = bounds("page-header")
            val backBounds = bounds("page-back")
            val physicalDensity = compose.activity.resources.displayMetrics.density
            assertEquals("${example.id}: title center follows the whole page", page.center.x, heading.center.x, 1.1f)
            assertTrue("${example.id}: title remains within header", contains(page, heading))
            assertFalse("${example.id}: title does not cover Back", overlaps(heading, backBounds))
            assertTrue("${example.id}: Back keeps 48dp target", backBounds.width >= 48f * physicalDensity - 1f && backBounds.height >= 48f * physicalDensity - 1f)
            assertTrue("${example.id}: Back follows RTL", if (example.rtl) backBounds.center.x > page.center.x else backBounds.center.x < page.center.x)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("coco-page-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("${example.id}: heading wraps completely instead of clipping", layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("page-back").performClick()
            val actions = when (example.actions) { 1 -> listOf("page-save"); 2 -> listOf("page-delete", "page-done"); else -> emptyList() }
            for (action in actions) {
                val actionBounds = bounds(action)
                assertTrue("${example.id}: $action within header", contains(page, actionBounds))
                assertFalse("${example.id}: $action does not overlap title", overlaps(heading, actionBounds))
                assertFalse("${example.id}: $action does not overlap Back", overlaps(backBounds, actionBounds))
                assertTrue("${example.id}: $action keeps a 48dp target", actionBounds.width >= 48f * physicalDensity - 1f && actionBounds.height >= 48f * physicalDensity - 1f)
                compose.onNodeWithTag(action).assertIsDisplayed().performClick()
            }
            screenshot(example.id)
        }
        compose.runOnIdle {
            assertEquals(examples.size, back)
            assertEquals(examples.count { it.actions == 1 }, saves)
            assertEquals(examples.count { it.actions == 2 }, deletes)
            assertEquals(examples.count { it.actions == 2 }, done)
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun contains(outer: Rect, inner: Rect): Boolean = inner.left >= outer.left - 1f && inner.right <= outer.right + 1f && inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f
    private fun overlaps(a: Rect, b: Rect): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    private fun screenshot(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "qa-screen-headers").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { output ->
            compose.onNodeWithTag("header-fixture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
