package com.messenger.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryBackupToggleInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun longBackupLabelAtDoubleFontScaleKeepsSwitchVisibleAndFunctional() {
        val label="Automatically create an encrypted backup of messages and attachments every day"
        val checked=mutableStateOf(false)
        val enabled=mutableStateOf(true)
        compose.setContent {CocoTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.width(240.dp)) {
                    HistoryBackupToggle(label,checked.value,enabled.value,{checked.value=it},
                        Modifier.testTag("backup-toggle"))
                }
            }
        }}
        val caption=compose.onNodeWithText(label)
        val control=compose.onNodeWithContentDescription(label)
        caption.assertIsDisplayed();control.assertIsDisplayed()
        val captionBounds=caption.fetchSemanticsNode().boundsInRoot
        val switchBounds=control.fetchSemanticsNode().boundsInRoot
        val row=compose.onNodeWithTag("backup-toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(captionBounds.width>0f)
        assertTrue(captionBounds.right<=switchBounds.left+1f)
        assertTrue(switchBounds.right<=row.right+1f)
        assertTrue("The row grows instead of clipping the wrapped caption",row.height>56*compose.activity.resources.displayMetrics.density)
        control.performClick()
        compose.runOnIdle {assertTrue(checked.value);enabled.value=false}
        control.assertIsNotEnabled()
    }
}
