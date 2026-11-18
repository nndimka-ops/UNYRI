package com.messenger.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real CocoTheme/Material3 composition; all preference writes use an isolated file. */
@RunWith(AndroidJUnit4::class)
class CocoThemeIdentityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private inline fun isolated(block: (Context, SharedPreferences) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "coco-theme-identity-test-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(requestedName: String, mode: Int): SharedPreferences =
                if (requestedName == "coco-appearance") prefs else super.getSharedPreferences(requestedName, mode)
        }
        try { block(context, prefs) } finally { app.deleteSharedPreferences(name) }
    }

    @Stable private class Probe {
        val compositions = AtomicInteger()
        val measures = AtomicInteger()
        val primaryArgb = AtomicInteger()
    }

    @Composable private fun ThemeLeaf(probe: Probe) {
        val primary = MaterialTheme.colorScheme.primary
        SideEffect { probe.primaryArgb.set(primary.toArgb()); probe.compositions.incrementAndGet() }
        Text("Unchanged themed leaf", color = primary,
            modifier = Modifier.layout { measurable, constraints ->
                probe.measures.incrementAndGet()
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            })
    }

    // Construct outside composable scope: an inline composable lambda keeps the
    // same wrapper and can invalidate only its own body while CocoTheme skips.
    private fun contentFor(stamp: Int, seenHostTick: AtomicInteger, probe: Probe): @Composable () -> Unit = {
        SideEffect { seenHostTick.set(stamp) }
        ThemeLeaf(probe)
    }

    @Test fun unrelatedContentTicksDoNotInvalidateThemeLeaf() = isolated { context, _ ->
        val tick = mutableIntStateOf(0)
        val seenHostTick = AtomicInteger(-1)
        val probe = Probe()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                val stamp = tick.intValue
                // A genuinely new content instance forces actual CocoTheme to run,
                // while the leaf's data, typography, and colors remain unchanged.
                CocoTheme(content = contentFor(stamp, seenHostTick, probe))
            }
        }
        compose.waitForIdle()
        val initialCompositions = probe.compositions.get()
        val initialMeasures = probe.measures.get()
        val initialColor = probe.primaryArgb.get()
        assertTrue(initialCompositions > 0)
        repeat(3) { index ->
            compose.runOnIdle { tick.intValue = index + 1 }
            compose.waitForIdle()
            assertEquals("The new host content must actually execute", index + 1, seenHostTick.get())
            assertEquals("Unchanged theme must not recompose its dependent leaf", initialCompositions, probe.compositions.get())
            assertEquals("Unchanged leaf must not be remeasured", initialMeasures, probe.measures.get())
            assertEquals(initialColor, probe.primaryArgb.get())
        }
        compose.onNodeWithText("Unchanged themed leaf").assertIsDisplayed()
    }

    private data class AppearanceSample(val size: Int, val outgoingArgb: Int, val family: String)

    @Composable private fun AppearanceLeaf(seen: AtomicReference<AppearanceSample?>) {
        val settings = LocalChatAppearanceSettings.current
        val style = LocalChatAppearanceStyle.current
        SideEffect { seen.set(AppearanceSample(settings.fontSize, style.outgoing.toArgb(), settings.fontFamily)) }
        Text("Size ${settings.fontSize}", fontSize = settings.fontSize.sp,
            color = style.outgoing, fontFamily = style.messageFontFamily)
    }

    @Test fun realAppearanceChangesStillPublishAndReset() = isolated { context, prefs ->
        val seen = AtomicReference<AppearanceSample?>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme { AppearanceLeaf(seen) }
            }
        }
        compose.waitForIdle()
        val original = seen.get()!!
        val chosen = defaultChatAppearanceSettings().copy(fontSize = 22, outgoingColor = "green", fontFamily = "monospace")
        compose.runOnIdle { saveChatAppearanceSettings(prefs, chosen) }
        compose.waitForIdle()
        val expected = resolveChatAppearance(chosen)
        assertEquals(AppearanceSample(22, expected.outgoing.toArgb(), "monospace"), seen.get())
        assertNotEquals(original.outgoingArgb, seen.get()!!.outgoingArgb)
        compose.onNodeWithText("Size 22").assertIsDisplayed()
        compose.runOnIdle { saveChatAppearanceSettings(prefs, defaultChatAppearanceSettings()) }
        compose.waitForIdle()
        assertEquals(original, seen.get())
        compose.onNodeWithText("Size ${original.size}").assertIsDisplayed()
    }
}
