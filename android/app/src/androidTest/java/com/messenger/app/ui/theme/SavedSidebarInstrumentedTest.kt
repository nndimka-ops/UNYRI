package com.messenger.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.GraphiteDrawer
import com.messenger.app.ui.RootDestination
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual exported sidebar: real measured geometry, retained actions and reversible palette. */
class SavedSidebarInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun savedDrawerKeepsEveryActionAndGeometryWhenChangingColorMode() {
        val app = compose.activity.applicationContext
        val name = "saved-sidebar-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(request: String, mode: Int) =
                if (request == "coco-appearance") prefs else super.getSharedPreferences(request, mode)
        }
        val actions = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                CocoTheme {
                    Box(Modifier.requiredSize(390.dp, 780.dp).background(Color.Black).testTag("saved-sidebar-preview")) {
                        GraphiteDrawer(SavedSidebarStyle.metrics(390f).panelWidth.dp,
                            null, "Dimon", "dimon", 0, false, RootDestination.CHATS, 7, true, true,
                            onClose = { actions += "close" }, onEdit = { actions += "profile" },
                            onSearch = { actions += "search" }, onPeople = { actions += "people" },
                            onDestination = { actions += it.name }, onSaved = { actions += "saved" },
                            onServer = { actions += "server" }, onDevices = { actions += "devices" },
                            onHelp = { actions += "help" }, onReport = { actions += "report" },
                            onLogout = { actions += "logout" }, isOnline = false)
                    }
                }
            }
        }
        fun image(label: String): IntArray {
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("saved-sidebar-preview").captureToImage().asAndroidBitmap()
            val file = File(compose.activity.getExternalFilesDir(null), "sidebar-$label.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        }
        try {
            compose.onNodeWithTag("saved-sidebar").assertWidthIsEqualTo(175.5.dp)
            compose.onNodeWithTag("saved-sidebar-avatar", useUnmergedTree = true).assertWidthIsEqualTo(39.5.dp)
            val colorful = image("colorful")
            compose.runOnIdle { AppColorStylePreference(prefs).set(AppColorStyle.MONOCHROME) }
            compose.onNodeWithTag("saved-sidebar").assertWidthIsEqualTo(175.5.dp)
            compose.onNodeWithTag("saved-sidebar-avatar", useUnmergedTree = true).assertWidthIsEqualTo(39.5.dp)
            val monochrome = image("monochrome")
            assertEquals(colorful.size, monochrome.size)
            assertTrue("Real sidebar material/icons respond to the selected palette", colorful.indices.count { colorful[it] != monochrome[it] } > 100)
            val badge = compose.onNodeWithTag("saved-sidebar-unread-badge", useUnmergedTree = true)
            val badgeImage = badge.captureToImage().asAndroidBitmap()
            val badgePixels = IntArray(badgeImage.width * badgeImage.height)
            badgeImage.getPixels(badgePixels, 0, badgeImage.width, 0, 0, badgeImage.width, badgeImage.height)
            val badgeBackground = Color(0xFFDEDEDE)
            assertTrue("Real monochrome badge must paint the light neutral fill",
                badgePixels.count { it == badgeBackground.toArgb() } > badgePixels.size / 4)
            badgeImage.recycle()
            val badgeLayouts = mutableListOf<TextLayoutResult>()
            badge.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(badgeLayouts) }
            val badgeInk = badgeLayouts.single().layoutInput.style.color
            assertEquals("Actual 10 sp badge text uses dark ink", Color(0xFF222222), badgeInk)
            val badgeContrast = (badgeBackground.luminance() + .05f) / (badgeInk.luminance() + .05f)
            assertTrue("Actual small monochrome badge needs readable contrast; got $badgeContrast", badgeContrast >= 4.5f)
            compose.runOnIdle { AppColorStylePreference(prefs).set(AppColorStyle.COLORFUL) }
            assertArrayEquals("Restoring colorful must restore the approved saved appearance", colorful, image("restored"))
            compose.onNodeWithText(compose.activity.getString(R.string.chats_search)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.search_scope_people)).performClick()
            for (id in listOf("chats", "calls", "saved", "settings", "server", "devices", "help", "report")) {
                compose.onNodeWithTag("saved-sidebar-item-$id").performClick()
            }
            compose.onNodeWithTag("saved-sidebar-profile-link").performClick()
            compose.onNodeWithTag("saved-sidebar-close").performClick()
            compose.onNodeWithTag("saved-sidebar-logout").performClick()
            compose.runOnIdle {
                assertEquals(listOf("search", "people", "CHATS", "CALLS", "saved", "SETTINGS", "server", "devices", "help", "report", "profile", "close", "logout"), actions)
            }
        } finally { app.deleteSharedPreferences(name) }
    }
}
