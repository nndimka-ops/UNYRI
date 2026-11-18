package com.messenger.app.ui.chats

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.components.CocoComposerSurfaceStyle
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Phase 2 runnable fixtures. These tests never launch a camera or send a file. */
class AttachmentFilesParityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortcutCloseAndReopenRetainsDefaultAndOnlyMenuChoicePersists() {
        val preferences = compose.activity.getSharedPreferences("media-quality", android.content.Context.MODE_PRIVATE)
        val saved = preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null)
        val legacy = preferences.getString("send", null)
        val open = mutableStateOf(true)
        val initialFiles = mutableStateOf(true)
        try {
            preferences.edit().putString("send", "image").putString(GALLERY_SEND_MODE_PREFERENCE, "image").commit()
            compose.setContent { MaterialTheme { if (open.value) Box(Modifier.requiredSize(402.dp, 720.dp)) {
                AttachmentGallery({}, {}, {}, {}, {}, emptyList(), {}, false, initialFilesTab = initialFiles.value,
                    onClose = { open.value = false }, recentFilesLoader = { emptyList() })
            } } }
            compose.onNodeWithTag("files-media-as-files").performClick()
            compose.runOnIdle {
                assertEquals("image", preferences.getString("send", null))
                assertEquals("image", preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null))
            }
            compose.onNodeWithTag("attachment-picker-close").performClick()
            compose.onNodeWithTag("attachment-picker").assertDoesNotExist()
            compose.runOnIdle { initialFiles.value = false; open.value = true }
            compose.onNodeWithTag("attachment-picker-send-options").performClick()
            compose.onNodeWithTag("attachment-picker-mode-image").assertIsSelected()
            compose.onNodeWithTag("attachment-picker-mode-image_high").performClick()
            compose.runOnIdle {
                assertEquals("image_high", preferences.getString("send", null))
                assertEquals("image_high", preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null))
            }
        } finally {
            preferences.edit().apply {
                if (saved == null) remove(GALLERY_SEND_MODE_PREFERENCE) else putString(GALLERY_SEND_MODE_PREFERENCE, saved)
                if (legacy == null) remove("send") else putString("send", legacy)
            }.commit()
        }
    }

    @Test fun shortcutAddReturnsOriginalModeWithTheSelectionWithoutSavingIt() {
        val preferences = compose.activity.getSharedPreferences("media-quality", android.content.Context.MODE_PRIVATE)
        val saved = preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null)
        val legacy = preferences.getString("send", null)
        val selected = listOf(Uri.parse("content://media/external/images/media/9999901"))
        var added: List<Uri>? = null
        var quality: String? = null
        try {
            preferences.edit().putString("send", "image_high").putString(GALLERY_SEND_MODE_PREFERENCE, "image_high").commit()
            compose.setContent { MaterialTheme { Box(Modifier.requiredSize(402.dp, 720.dp)) {
                AttachmentGallery({}, {}, {}, {}, { error("Mode-less admission must not be used") }, selected, {}, false,
                    initialFilesTab = true, recentFilesLoader = { emptyList() }, onPickedMedia = { uris, mode, now ->
                        assertFalse(now); added = uris; quality = mode; true
                    })
            } } }
            compose.onNodeWithTag("files-media-as-files").performClick()
            compose.onNodeWithTag("attachment-picker-add").performClick()
            compose.runOnIdle {
                assertEquals(selected, added); assertEquals("file", quality)
                assertEquals("image_high", preferences.getString("send", null))
                assertEquals("image_high", preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null))
            }
        } finally {
            preferences.edit().apply {
                if (saved == null) remove(GALLERY_SEND_MODE_PREFERENCE) else putString(GALLERY_SEND_MODE_PREFERENCE, saved)
                if (legacy == null) remove("send") else putString("send", legacy)
            }.commit()
        }
    }

    @Test fun filesAndGalleryRemainInOneSheetWithIndependentNumberingAndSharedCaption() {
        val caption = mutableStateOf("draft")
        val media = listOf(Uri.parse("content://media/external/images/media/9999901"))
        val selectedFiles = mutableStateOf(emptyList<String>())
        var picked: List<RecentFile>? = null
        var captionAtAdd: String? = null
        var chooses = 0
        val temp = File.createTempFile("recent", ".pdf", compose.activity.cacheDir)
        val a = RecentFile("a", "A.pdf", 32, "2026-11-11T12:00:00Z", temp.path, "file://${temp.path}")
        val b = a.copy(id = "b", name = "B.txt")
        try {
            compose.setContent { MaterialTheme { Box(Modifier.requiredSize(402.dp, 720.dp)) {
                AttachmentGallery({}, {}, {}, { chooses++ }, {}, media, {}, false,
                    caption = caption.value, onCaptionChange = { caption.value = it },
                    onFileSelection = { selectedFiles.value = it },
                    onFilesSelected = { files, immediate -> assertFalse(immediate); picked = files; captionAtAdd = caption.value; true },
                    recentFilesLoader = { listOf(a, b) })
            } } }
            compose.onNodeWithTag("attach-tab-files").performClick().assertIsSelected()
            compose.onNodeWithTag("files-choose").performClick()
            compose.runOnIdle { assertEquals(1, chooses) }
            compose.onNodeWithTag("attachment-picker-files").assertExists()
            compose.onNodeWithTag("files-recent-b").performClick()
            compose.onNodeWithTag("files-recent-a").performClick()
            compose.runOnIdle { assertEquals(listOf("b", "a"), selectedFiles.value) }
            compose.onNodeWithText("2/10").assertExists()
            compose.onNodeWithTag("attach-tab-gallery").performClick().assertIsSelected()
            compose.onNodeWithText("1/10").assertExists()
            compose.onNodeWithTag("attach-tab-files").performClick()
            compose.onNodeWithTag("files-recent-b").assertIsSelected()
            compose.onNodeWithTag("attachment-picker-caption-input").performTextReplacement("Two files")
            compose.activity.runOnUiThread {
                (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            compose.waitUntil(3_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
            }
            compose.onNodeWithTag("attachment-picker-add").performClick()
            compose.waitUntil { picked != null }
            compose.onNodeWithTag("attachment-picker-add").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(listOf(b, a), picked); assertEquals("Two files", captionAtAdd) }
        } finally { temp.delete() }
    }

    @Test fun filesFailureRetriesAndMediaShortcutSelectsOriginalMode() {
        var loads = 0
        val preferences = compose.activity.getSharedPreferences("media-quality", android.content.Context.MODE_PRIVATE)
        val saved = preferences.getString(GALLERY_SEND_MODE_PREFERENCE, null)
        val legacy = preferences.getString("send", null)
        try {
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(402.dp, 720.dp)) {
            AttachmentGallery({}, {}, {}, {}, {}, emptyList(), {}, false, initialFilesTab = true,
                recentFilesLoader = { loads++; if (loads == 1) error("fixture unavailable") else emptyList() })
        } } }
        compose.onNodeWithTag("files-retry").performClick()
        compose.waitUntil { loads == 2 }
        compose.onNodeWithTag("files-media-as-files").performClick()
        compose.onNodeWithTag("attach-tab-gallery").assertIsSelected()
        compose.onNodeWithTag("attachment-picker-send-options").performClick()
        compose.onNodeWithTag("attachment-picker-mode-file").assertIsSelected()
        } finally {
            preferences.edit().apply {
                if (saved == null) remove(GALLERY_SEND_MODE_PREFERENCE) else putString(GALLERY_SEND_MODE_PREFERENCE, saved)
                if (legacy == null) remove("send") else putString("send", legacy)
            }.commit()
        }
    }

    @Test fun closingAndReopeningKeepsFileSelectionTabAndCaptionWithoutSending() {
        val open = mutableStateOf(true)
        val tab = mutableStateOf(false)
        val selection = mutableStateOf(emptyList<String>())
        val caption = mutableStateOf("Kept caption")
        val file = RecentFile("pdf", "Contract.pdf", 32, "2026-11-11T12:00:00Z", "/fixture", "file:///fixture")
        var sends = 0
        compose.setContent { MaterialTheme { if (open.value) Box(Modifier.requiredSize(402.dp, 720.dp)) {
            AttachmentGallery({}, {}, {}, {}, { sends++ }, emptyList(), {}, false,
                caption = caption.value, onCaptionChange = { caption.value = it }, onClose = { open.value = false },
                initialFilesTab = tab.value, onFilesTabChange = { tab.value = it },
                initialFileSelection = selection.value, onFileSelection = { selection.value = it },
                recentFilesLoader = { listOf(file) })
        } } }
        compose.onNodeWithTag("attach-tab-files").performClick()
        compose.onNodeWithTag("files-recent-pdf").performClick()
        compose.onNodeWithTag("attachment-picker-close").performClick()
        compose.onNodeWithTag("attachment-picker").assertDoesNotExist()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithTag("attach-tab-files").assertIsSelected()
        compose.onNodeWithTag("files-recent-pdf").assertIsSelected()
        compose.onNodeWithTag("attachment-picker-caption-input").assertTextEquals("Kept caption")
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun unavailableDirectRouteKeepsInfoAccessibleAndDoesNotDispatch() {
        var sends = 0
        compose.setContent { MaterialTheme {
            ComposerAttachmentMenu(CocoComposerSurfaceStyle(Color(0xFF282828), null), true, {},
                onCamera = {}, onGallery = {}, onFiles = {}, onDirect = { sends++ }, directAvailable = false)
        } }
        compose.onNodeWithTag("composer-attachment-direct").assertIsNotEnabled()
        compose.onNodeWithTag("composer-attachment-direct-info").assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(com.messenger.app.R.string.direct_send_note)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sends) }
    }
}
