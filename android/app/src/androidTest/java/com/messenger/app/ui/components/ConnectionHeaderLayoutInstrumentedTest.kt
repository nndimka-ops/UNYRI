package com.messenger.app.ui.components

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Source-only B-063 geometry contract. No account, server, preference writes or retries. */
class ConnectionHeaderLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Example(val locale: String, val width: Int, val fontScale: Float)

    @Test fun pausedStatusAndPingWrapWithoutTruncatingAtLargeText() {
        val examples = listOf("ru", "en", "uk").flatMap { locale ->
            listOf(96, 144).flatMap { width -> listOf(1f, 1.3f, 2f).map { Example(locale, width, it) } }
        }
        val current = mutableStateOf(examples.first())
        compose.setContent {
            val item = current.value
            val density = LocalDensity.current
            val localized = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(item.locale))
            })
            CompositionLocalProvider(LocalContext provides localized,
                LocalDensity provides Density(density.density, item.fontScale)) {
                CocoTheme {
                    Box(Modifier.width(item.width.dp)) {
                        ChatConnectionHeaderContent(HeaderConnectionStatus.SYNC_FAILED, connected = true, busy = false,
                            showPing = true, visible = true, onRetry = {}, measurePing = { 55 })
                    }
                }
            }
        }
        for (item in examples) {
            compose.runOnIdle { current.value = item }
            compose.waitForIdle()
            val localized = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(item.locale))
            })
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(localized.getString(R.string.connection_header_sync_failed), substring = true), useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertFalse("$item: status and ping fit", layouts.single().hasVisualOverflow)
            assertTrue("$item: ping remains present", layouts.single().layoutInput.text.text.contains("55"))
        }
    }
}
