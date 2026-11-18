package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Reproduces the unclipped Box -> header Column hierarchy without app/account dependencies. */
@OptIn(ExperimentalTestApi::class)
class CapsuleChromeScrimInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun topRemainsOpaqueThroughControlsAndFadesBelowWithoutChangingHeaderHeight() {
        var header = Rect.Zero
        var controls = Rect.Zero
        var frame = Rect.Zero
        var density = 1f
        var statusTop = 0
        compose.setContent {
            val currentDensity = LocalDensity.current
            density = currentDensity.density
            statusTop = WindowInsets.statusBars.getTop(currentDensity)
            Box(Modifier.size(280.dp, 260.dp).testTag("chrome-fade-frame")
                .background(Color.White).onGloballyPositioned { frame = it.boundsInRoot() }) {
                // Contrasting content deliberately continues behind the full header.
                Text("MESSAGE MESSAGE MESSAGE MESSAGE", fontSize = 24.sp, color = Color.Red,
                    modifier = Modifier.padding(top = 38.dp).width(200.dp))
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().capsuleChromeScrim(top = true)
                    .onGloballyPositioned { header = it.boundsInRoot() }.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(CapsuleChatChrome.topHeight.dp)
                        .onGloballyPositioned { controls = it.boundsInRoot() }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Name\nLast seen today", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(CapsuleChatChrome.topHeight * density, controls.height, 1f)
        assertEquals(controls.height + statusTop, header.height, 1f)
        assertEquals("The fade must not reserve extra layout height", controls.bottom, header.bottom, 1f)
        assertEquals(260f * density, frame.height, 1f)

        val first = compose.onNodeWithTag("chrome-fade-frame").captureToImage().asAndroidBitmap()
        try {
            val x = (first.width - 6f * density).roundToInt().coerceIn(0, first.width - 1)
            val bottom = header.bottom - frame.top
            fun brightness(y: Float): Int {
                val pixel = first.getPixel(x, y.roundToInt().coerceIn(0, first.height - 1))
                return android.graphics.Color.red(pixel)
            }
            assertTrue("Status-bar area must hide underlying messages", brightness(2f * density) <= 2)
            assertTrue("Controls must have an opaque scrim", brightness(bottom * .5f) <= 2)
            assertTrue("Last header pixels must remain opaque", brightness(bottom - density) <= 2)
            val quarter = brightness(bottom + 5.5f * density)
            val middle = brightness(bottom + 11f * density)
            val threeQuarters = brightness(bottom + 16.5f * density)
            assertTrue("Fade must extend beyond header bounds, without clipping", quarter in 40..90)
            assertTrue("Fade midpoint must be continuous", middle in 105..150)
            assertTrue("Fade must grow gradually more transparent", quarter < middle && middle < threeQuarters)
            assertTrue("Message area below the fade must retain its original color", brightness(bottom + 25f * density) >= 253)
            save(first, "capsule-top-header-static-fade.png")
            compose.mainClock.advanceTimeBy(1000)
            val after = compose.onNodeWithTag("chrome-fade-frame").captureToImage().asAndroidBitmap()
            try { assertTrue("The scrim must remain static", first.sameAs(after)) } finally { after.recycle() }
        } finally { first.recycle() }
    }

    @Test fun bottomRetainsExistingTransparentToOpaqueStops() {
        compose.setContent {
            Box(Modifier.size(200.dp, 100.dp).background(Color.White).testTag("bottom-scrim-frame")) {
                Box(Modifier.fillMaxSize().capsuleChromeScrim(top = false))
            }
        }
        val image = compose.onNodeWithTag("bottom-scrim-frame").captureToImage().asAndroidBitmap()
        try {
            fun red(y: Int) = android.graphics.Color.red(image.getPixel(image.width / 2, y))
            assertTrue(red(0) >= 250)
            assertTrue(red(image.height / 2) in 78..91)
            assertTrue(red(image.height - 1) <= 3)
        } finally { image.recycle() }
    }

    private fun save(image: Bitmap, name: String) {
        val folder = compose.activity.getExternalFilesDir("capsules-uiqa")!!
        folder.mkdirs()
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
