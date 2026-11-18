package com.messenger.app.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.R
import com.messenger.app.data.call.CallQualityPreferences
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CallQualitySettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun independentSelectionsPersistWithLargeText() {
        val prefs = compose.activity.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().commit()
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    CocoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CallQualitySettings() } }
                }
            }
            compose.onNodeWithText(compose.activity.getString(R.string.call_quality_video), substring = true).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.call_quality_economy)).performScrollTo().performClick()
            compose.runOnIdle { assertEquals("economy", prefs.getString(CallQualityPreferences.VIDEO, null)); assertNull(prefs.getString(CallQualityPreferences.AUDIO, null)) }
            compose.onNodeWithText(compose.activity.getString(R.string.call_quality_audio), substring = true).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.call_quality_high)).performScrollTo().performClick()
            compose.runOnIdle { assertEquals("high", prefs.getString(CallQualityPreferences.AUDIO, null)); assertEquals("economy", prefs.getString(CallQualityPreferences.VIDEO, null)) }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { assertEquals("high", prefs.getString(CallQualityPreferences.AUDIO, null)); assertEquals("economy", prefs.getString(CallQualityPreferences.VIDEO, null)) }
        } finally {
            prefs.edit().clear().apply { saved.forEach { (key, value) -> if (value is String) putString(key, value) } }.commit()
        }
    }
}
