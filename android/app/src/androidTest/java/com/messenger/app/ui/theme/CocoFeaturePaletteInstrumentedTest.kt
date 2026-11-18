package com.messenger.app.ui.theme

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.auth.AuthBrandHeader
import com.messenger.app.ui.auth.authPrimaryActionStyle
import com.messenger.app.ui.chats.AttachmentPanelButton
import com.messenger.app.ui.chats.AttachmentPanelHeader
import com.messenger.app.ui.chats.CapsuleChatStyle
import com.messenger.app.ui.chats.capsuleChatPalette
import com.messenger.app.ui.profile.SettingsHomeHistory
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual production UI, isolated composition-local palette, no account or preference writes. */
class CocoFeaturePaletteInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        compose.activity.runOnUiThread {
            @Suppress("DEPRECATION")
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun capture(name: String): ImageBitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag("feature-palette").captureToImage().also { bitmap ->
            val folder = File(compose.activity.getExternalFilesDir(null), "feature-palette-uiqa").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use {
                bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun pixelsNear(bitmap: ImageBitmap, target: Color): Int {
        val map = bitmap.toPixelMap()
        var matches = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val p = map[x, y]
            if (abs(p.red-target.red) < .035f && abs(p.green-target.green) < .035f && abs(p.blue-target.blue) < .035f) matches++
        }
        return matches
    }

    private fun chromaticPixels(bitmap: ImageBitmap): Int {
        val map = bitmap.toPixelMap()
        var matches = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val p = map[x, y]
            if (maxOf(p.red,p.green,p.blue)-minOf(p.red,p.green,p.blue) > .07f) matches++
        }
        return matches
    }

    @Test fun historyActionsChangePaletteWithoutChangingCallbacksOrContent() {
        val palette = mutableStateOf(CocoColorfulColors)
        var transfers = 0
        var backups = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCocoColors provides palette.value) {
                Box(Modifier.width(320.dp).background(palette.value.background).testTag("feature-palette")) {
                    SettingsHomeHistory(onTransfer = { transfers++ }, onBackup = { backups++ })
                }
            }
        } }
        val colorful = capture("history-colorful")
        assertTrue("Actual transfer label/icon is blue", pixelsNear(colorful, CocoColorfulColors.categoryBlue) > 12)
        assertTrue("Actual backup label/icon is amber", pixelsNear(colorful, CocoColorfulColors.categoryAmber) > 12)
        assertTrue("History identity remains violet", pixelsNear(colorful, CocoColorfulColors.categoryViolet) > 8)
        compose.onNodeWithText(compose.activity.getString(R.string.settings_home_transfer)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_home_backup)).performClick()
        compose.runOnIdle { assertEquals(1, transfers); assertEquals(1, backups); palette.value = CocoMonochromeColors }
        val monochrome = capture("history-monochrome")
        assertEquals("The same UI responds to palette changes without leftover decorative colors", 0, chromaticPixels(monochrome))
        compose.onNodeWithText(compose.activity.getString(R.string.settings_home_transfer)).performClick()
        compose.runOnIdle { assertEquals(2, transfers); assertEquals(1, backups) }
    }

    @Test fun authBrandUsesActivePaletteWithoutChangingItsSizeOrLabel() {
        val palette = mutableStateOf(CocoColorfulColors)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCocoColors provides palette.value) {
                Box(Modifier.width(320.dp).background(palette.value.background).testTag("feature-palette")) { AuthBrandHeader() }
            }
        } }
        val colorful = capture("auth-brand-colorful")
        assertTrue(pixelsNear(colorful, CocoColorfulColors.categoryViolet) > 12)
        compose.runOnIdle { palette.value = CocoMonochromeColors }
        val monochrome = capture("auth-brand-monochrome")
        assertEquals(0, chromaticPixels(monochrome))
        assertEquals(colorful.width, monochrome.width)
        assertEquals(colorful.height, monochrome.height)
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertExists()
    }

    @Test fun attachmentActionsUseAppRolesInsideTheSavedWhiteChatPalette() {
        val palette = mutableStateOf(CocoColorfulColors)
        var sends = 0
        compose.setContent { MaterialTheme {
            val chat = capsuleChatPalette(palette.value)
            CompositionLocalProvider(LocalCocoColors provides chat) {
                Box(Modifier.width(320.dp).background(chat.background).testTag("feature-palette")) {
                    Column {
                        AttachmentPanelHeader("Attachments", {})
                        AttachmentPanelButton("Palette send", CocoIcons.Send, chat.categoryBlue, { sends++ }, Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        } }
        val colorful = capture("attachments-colorful")
        assertTrue("Attachment header keeps its violet role inside chat", pixelsNear(colorful, CocoColorfulColors.categoryViolet) > 12)
        assertTrue("Primary send uses its explicit blue role, not the saved white composer", pixelsNear(colorful, CocoColorfulColors.categoryBlue) > 100)
        compose.onNodeWithText("Palette send").performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals(CapsuleChatStyle.accent, capsuleChatPalette(palette.value).accent)
            palette.value = CocoMonochromeColors
        }
        assertEquals(0, chromaticPixels(capture("attachments-monochrome")))
        compose.onNodeWithText("Palette send").performClick()
        compose.runOnIdle { assertEquals(2, sends) }
    }

    @Test fun authActionUsesIosGradientAndMonochromeKeepsTheSameHitTarget() {
        val palette = mutableStateOf(CocoColorfulColors)
        var submits = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCocoColors provides palette.value) {
                val shape = RoundedCornerShape(20.dp)
                val style = authPrimaryActionStyle(enabled = true, shape = shape)
                Button(onClick = { submits++ }, shape = shape, colors = style.colors,
                    modifier = Modifier.width(320.dp).height(56.dp).then(style.background).testTag("feature-palette")) {
                    Text("Palette submit")
                }
            }
        } }
        val colorful = capture("auth-action-colorful")
        val map = colorful.toPixelMap()
        assertTrue("Gradient runs from the iOS violet towards blue", map[map.width / 10, map.height / 2].red >
            map[map.width * 9 / 10, map.height / 2].red + .09f)
        assertTrue("Colorful sign-in retains white ink", pixelsNear(colorful, Color.White) > 12)
        compose.onNodeWithText("Palette submit").performClick()
        compose.runOnIdle { assertEquals(1, submits); palette.value = CocoMonochromeColors }
        val monochrome = capture("auth-action-monochrome")
        assertEquals(0, chromaticPixels(monochrome))
        assertEquals(colorful.width, monochrome.width)
        assertEquals(colorful.height, monochrome.height)
        compose.onNodeWithText("Palette submit").performClick()
        compose.runOnIdle { assertEquals(2, submits) }
    }
}
