package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EmojiPickerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun quickPaletteExpandsAndBackReturnsWithoutSelecting() {
        var selected = ""
        compose.setContent { CocoTheme { EmojiPickerSheet(onDismiss = {}, initiallyExpanded = false, onChoose = { selected = it }) } }
        val all = hasText("Все эмодзи") or hasText("All emoji")
        compose.onNode(all).assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNode(all).assertIsDisplayed()
        compose.runOnIdle { assertEquals("", selected) }
        compose.onNodeWithText("😂").performClick()
        compose.runOnIdle { assertEquals("😂", selected) }
    }

    @Test fun extendedSearchPreservesCompoundEmoji() {
        var selected = ""
        compose.setContent { CocoTheme { EmojiPickerSheet(onDismiss = {}, onChoose = { selected = it }) } }
        compose.onNode(hasSetTextAction()).performTextInput("❤️‍🔥")
        compose.onNode(hasText("❤️‍🔥") and !hasSetTextAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("❤️‍🔥", selected) }
    }

    @Test fun catalogIsBoundedAndHasUniqueKeysWithinCategories() {
        assertTrue(emojiCategories.flatMap { it.emojis }.distinct().size >= 450)
        emojiCategories.forEach { group ->
            assertEquals(group.emojis.size, group.emojis.distinct().size)
            group.emojis.forEach { assertTrue(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 64) }
        }
    }
}
