package com.messenger.app.ui.selfhost

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pixel comparison of the actual invite component in the auth form's spaced column.
 * No API or repository is used by this fixture. Run on an isolated offline emulator. */
@OptIn(ExperimentalTestApi::class)
class InviteFieldPixelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun openServerHasExactlyTheSamePixelsAndRequiredServerAddsTheField() {
        var insert by mutableStateOf(false)
        var required by mutableStateOf(false)
        compose.setContent {
            CocoTheme(mode = ThemeMode.DARK) {
                Column(Modifier.width(320.dp).background(LocalCocoColors.current.background).testTag("form"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField("Fixture", {}, singleLine = true)
                    if (insert) InviteCodeField(required, "", true) {}
                    OutlinedTextField("Fixture", {}, singleLine = true)
                }
            }
        }
        val original = compose.onNodeWithTag("form").captureToImage().asAndroidBitmap()
        compose.runOnIdle { insert = true }
        compose.onNodeWithTag("signup-invite").assertDoesNotExist()
        val open = compose.onNodeWithTag("form").captureToImage().asAndroidBitmap()
        assertTrue("An open server must add no pixels or spacing", original.sameAs(open))
        compose.runOnIdle { required = true }
        compose.onNodeWithTag("signup-invite").assertExists().assertIsDisplayed()
        val invited = compose.onNodeWithTag("form").captureToImage().asAndroidBitmap()
        assertTrue(invited.height > open.height)
    }
}
