package com.messenger.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.R
import com.messenger.app.ui.search.CompactSearchField
import com.messenger.app.ui.search.SearchScope
import com.messenger.app.ui.search.SearchScopeControl
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.profile.SettingsHomeHeader
import com.messenger.app.ui.profile.SettingsHomeServerCard
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline presentation fixtures only. Phase 2 owns execution and device captures. */
@RunWith(AndroidJUnit4::class)
class AuxiliaryScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disabledDailyToggleStaysVisibleAndCannotMutate() {
        var changes = 0
        compose.setContent { CocoTheme { HistoryBackupToggle("Daily", false, false, { changes++ }) } }
        compose.onNodeWithContentDescription("Daily").assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun scopeAndClearControlsChangeActualInputState() {
        var query by mutableStateOf("Привет")
        var scope by mutableStateOf(SearchScope.ALL)
        compose.setContent { CocoTheme { Column {
            CompactSearchField(query, { query = it }, { query = "" }, {})
            SearchScopeControl(scope) { scope = it }
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.search_scope_messages)).performClick().assertIsSelected()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.search_clear)).performClick()
        compose.runOnIdle { assertEquals(SearchScope.MESSAGES, scope); assertEquals("", query) }
    }

    @Test fun transferBusyStateAndDoneRemainUsableAtLargeFont() {
        var receiving = 0
        var closed = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                CocoTheme { HistoryTransferPreparationScreen("qa", "example.test", true,
                    { error("Busy sender must be disabled") }, { receiving++ }, { closed++ }) }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.history_transfer_choose_old)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.history_transfer_choose_new)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.runOnIdle { assertEquals(1, receiving); assertEquals(1, closed) }
    }

    @Test fun retryCannotInterceptMenuTaps() = checkToolbarHitAreas(LayoutDirection.Ltr)

    @Test fun retryCannotInterceptMenuTapsInRtl() = checkToolbarHitAreas(LayoutDirection.Rtl)

    private fun checkToolbarHitAreas(direction: LayoutDirection) {
        var menus = 0
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides Density(density, 1.6f)) {
                CocoTheme { Box(Modifier.width(280.dp)) {
                    AuxiliaryConnectionToolbar(menu = {
                        IconButton(onClick = { menus++ }, modifier = Modifier.testTag("menu-hit")) {
                            Icon(Icons.Filled.Menu, "Menu")
                        }
                    }, connection = {
                        Box(Modifier.fillMaxWidth().height(44.dp).clickable { retries++ }.testTag("retry-hit")) {
                            Text("Соединение недоступно", maxLines = 1)
                        }
                    })
                } }
            }
        }
        val menu = compose.onNodeWithTag("menu-hit").fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithTag("retry-hit").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.right <= retry.left || retry.right <= menu.left)
        compose.onNodeWithTag("menu-hit").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(0, retries) }
        compose.onNodeWithTag("retry-hit").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(1, retries) }
    }

    @Test fun russianScopesScrollWithoutWrappingAtLargeFont() = checkIntrinsicScopes("ru")

    @Test fun ukrainianScopesScrollWithoutWrappingAtLargeFont() = checkIntrinsicScopes("uk")

    private fun checkIntrinsicScopes(language: String) {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val context = compose.activity.createConfigurationContext(configuration)
        val all = context.getString(R.string.chats_filter_all)
        val messages = context.getString(R.string.search_scope_messages)
        var selected by mutableStateOf(SearchScope.ALL)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density, 1.8f)) {
                CocoTheme { Box(Modifier.width(280.dp)) { SearchScopeControl(selected) { selected = it } } }
            }
        }
        val lineHeight = compose.onNodeWithText(all, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText(messages).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        val messageHeight = compose.onNodeWithText(messages, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertEquals(lineHeight, messageHeight, 1f)
        compose.runOnIdle { assertEquals(SearchScope.MESSAGES, selected) }
    }

    @Test fun settingsTitleIsAVisibleHeadingWithoutAnEmptyPageTitle() {
        compose.setContent { CocoTheme { SettingsHomeHeader {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.nav_settings)).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onAllNodesWithTag("coco-page-title").assertCountEquals(0)
    }

    @Test fun serverIdentitySurvivesLongStatusAtLargeFont() {
        var opened = 0
        val host = "example.test"
        val status = "Соединение установлено"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                CocoTheme { Box(Modifier.width(280.dp)) { SettingsHomeServerCard(host, status, true) { opened++ } } }
            }
        }
        val identity = compose.onNodeWithText(host, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val connection = compose.onNodeWithText(status, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(identity.width > 0f && connection.width > 0f)
        assertTrue(identity.right <= connection.left)
        compose.onNodeWithText(host, useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
