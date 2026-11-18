package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatHeaderMenuInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun typingSwitchKeepsMenuOpenAndRecomposesFromTheStoredValue() {
        val stored = mutableStateOf(false)
        var changes = 0
        var dismissals = 0
        compose.setContent { CocoTheme {
            ChatHeaderMenu(listOf(ChatHeaderAction("Typing", CocoIcons.MenuKeyboard, ChatControls.blue, checked = stored.value) {
                changes++; stored.value = !stored.value
            }), onDismiss = { dismissals++ })
        } }
        compose.onNodeWithText("Typing").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("chat-header-menu").assertIsDisplayed()
        compose.onNodeWithText("Typing").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, changes); assertEquals(0, dismissals) }
    }
    @Test fun largeTextMenuScrollsAndDispatchesOnce() {
        val open = mutableStateOf(true)
        var calls = 0
        compose.setContent { CocoTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                if (open.value) ChatHeaderMenu((0..12).map { index ->
                    ChatHeaderAction("Action $index with a longer menu title", CocoIcons.Search, ChatControls.blue, ChatControls.blueBack,
                        group = index / 4, enabled = index != 0, checked = if (index == 1) true else null) { calls++; open.value = false }
                }, onDismiss = { open.value = false }) else Text("Closed")
            }
        } }
        compose.onNodeWithText("Action 0 with a longer menu title").assertIsNotEnabled()
        compose.onNodeWithText("Action 12 with a longer menu title").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Closed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
