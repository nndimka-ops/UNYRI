package com.messenger.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class FeedbackConsentInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300) // Let the window transition draw before capture.
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun reviewIsExplicitAndCancelPreservesTextWithKeyboardAndLargeFont() {
        compose.setContent {
            CocoTheme {
                // Match ProfileScreen's measured activity host. The retired
                // full-height Dialog is clipped by MIUI and is no longer used.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.35f)) {
                    Box(Modifier.fillMaxSize()) { BugReportScreen {} }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(com.messenger.app.R.string.feedback_title)).assertIsDisplayed()
        compose.onNodeWithTag("feedback-send").assertIsDisplayed().performClick()
        compose.onNodeWithTag("feedback-confirm-send").assertDoesNotExist()
        val description = compose.onNodeWithTag("feedback-description")
        description.performScrollTo().performClick().performTextInput("Example bug report. Keep this draft.")
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        screenshot("feedback-keyboard.png")
        compose.onNodeWithTag("feedback-send").assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithTag("feedback-confirm-send").assertIsDisplayed().assertIsEnabled()
        screenshot("feedback-review.png")
        compose.onNodeWithTag("feedback-review-back").performTouchInput { click(center) }
        description.performScrollTo().assertTextContains("Example bug report. Keep this draft.")
        compose.onNodeWithTag("feedback-confirm-send").assertDoesNotExist()
        screenshot("feedback-form.png")
        // No network submission: opening the review is not consent.
    }
}
