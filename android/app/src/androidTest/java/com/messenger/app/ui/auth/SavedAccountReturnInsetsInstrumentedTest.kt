package com.messenger.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import com.messenger.app.data.auth.SavedLocalAccount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SavedAccountReturnInsetsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returnChipIsBelowTheStatusInsetAndItsCenterIsTappable() {
        var selected: String? = null
        val status = WindowInsets(top = 88)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column(Modifier.fillMaxSize()) {
                    SavedAccountReturnRow(listOf(SavedLocalAccount("legacy", "https://example.test", "test", "Test")),
                        { selected = it }, insets = status)
                    // The sibling destination must consume the inset already provided by the row.
                    Box(Modifier.consumeWindowInsets(status)) {
                        Text("Login", Modifier.windowInsetsPadding(status))
                    }
                }
            }
        }
        val text = compose.onNodeWithText("Test · @test")
        assertTrue(text.fetchSemanticsNode().boundsInRoot.top >= 88f)
        assertTrue("The auth destination must not add the status inset twice",
            compose.onNodeWithText("Login").fetchSemanticsNode().boundsInRoot.top < 200f)
        text.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("legacy", selected) }
    }
}
