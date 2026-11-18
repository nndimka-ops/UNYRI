package com.messenger.app.ui.chats

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalCocoColors
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual production menu; every action is an isolated counter, never an app/repository call. */
@OptIn(ExperimentalTestApi::class)
class MessageActionsMenuInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun anAlbumNeedsOneConfirmationAndSingleDeletionIsExplicit() {
        val open = mutableStateOf(true)
        var grouped = 0
        var individual = 0
        val fixture = Message("album-action", "album-server", "fixture-chat", "me", "", 1,
            "2026-11-01T12:00:00Z", MessageStatus.SENT, caption = "Фото из поездки", albumId = "fixture-album")
        compose.setContent { CocoTheme {
            if (open.value) MessageActionsSheet(Rect(0f, 150f, 320f, 450f), null, fixture,
                isOwnMessage = true, hasAttachment = true, isPinned = false, actionGroupCount = 3,
                onDismiss = { open.value = false }, onReply = {}, onSelect = {}, onPin = {}, onRemind = {}, onCopy = {},
                onEdit = {}, onForward = {}, onSave = {}, onReact = {},
                onDeleteForMe = { grouped++; open.value = false }, onDeleteForEveryone = { grouped++; open.value = false },
                onDeleteSingleForMe = { individual++; open.value = false }, onDeleteSingleForEveryone = { individual++; open.value = false })
        } }
        val album = compose.activity.getString(R.string.chat_delete_album)
        val delete = compose.activity.getString(R.string.delete)
        val cancel = compose.activity.getString(R.string.cancel)
        compose.onNodeWithText(album).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_selected_count, 3)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, grouped + individual) }
        saveDialog("album-delete-whole-confirmation.png")
        compose.onNodeWithText(cancel).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, grouped + individual); open.value = true }
        compose.onNodeWithText(album).performScrollTo().performClick()
        compose.onNodeWithText(delete).performScrollTo().performClick()
        compose.onNodeWithTag("message-menu-group").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, grouped); assertEquals(0, individual); open.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.message_menu_more)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_delete_album_item)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.delete_message)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, individual) }
        compose.onNodeWithText(delete).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, grouped); assertEquals(1, individual) }
    }

    @Test fun landscapeLargeTextKeepsMenuInsideWindowAndRequiresDeleteConfirmation() =
        checkMenu(Configuration.ORIENTATION_LANDSCAPE,1.6f,deleteEveryone=false)

    @Test fun portraitVeryLargeTextKeepsMenuInsideWindowAndCanConfirmDeleteForEveryone() =
        checkMenu(Configuration.ORIENTATION_PORTRAIT,2f,deleteEveryone=true)

    private fun checkMenu(orientation:Int,fontScale:Float,deleteEveryone:Boolean) {
        // Rotate the real activity/window; changing only LocalConfiguration would
        // leave the Dialog in portrait and would not exercise the reported failure.
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation=if(orientation==Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {compose.activity.resources.configuration.orientation==orientation}
        assertEquals(orientation,compose.activity.resources.configuration.orientation)

        var deletedForMe=0
        var deletedForEveryone=0
        val reactions=mutableListOf<String>()
        val fixture=Message("menu-layout-fixture","server-fixture","isolated-chat","test-sender",
            "A synthetic message with all ordinary message actions available.",1,
            "2026-10-26T12:00:00Z",MessageStatus.READ)
        val prefix=if(orientation==Configuration.ORIENTATION_LANDSCAPE)"landscape-160" else "portrait-200"
        compose.setContent {
            val realDensity=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(realDensity.density,fontScale)) {
                CocoTheme {
                    val colors=LocalCocoColors.current
                    var open by remember {mutableStateOf(false)}
                    val bitmap=remember {Bitmap.createBitmap(300,180,Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.rgb(210,210,210))
                    }}
                    DisposableEffect(bitmap) {onDispose {bitmap.recycle()}}
                    Box(Modifier.fillMaxSize().background(colors.background)) {
                        Button(onClick={open=true},modifier=Modifier.testTag("qa-open-message-actions")) {Text("Open test menu")}
                    }
                    if(open) {
                        val configuration=LocalConfiguration.current
                        val density=LocalDensity.current
                        val anchor=with(density) {
                            val right=(configuration.screenWidthDp.dp-24.dp).toPx()
                            val top=(configuration.screenHeightDp.dp*.45f).toPx()
                            Rect(right-200.dp.toPx(),top,right,top+120.dp.toPx())
                        }
                        MessageActionsSheet(anchor,bitmap,fixture,isOwnMessage=true,hasAttachment=false,isPinned=false,
                            onDismiss={open=false},onReply={},onSelect={},onPin={},onRemind={},onCopy={},
                            onEdit={},onForward={},onSave={},
                            onDeleteForMe={deletedForMe++;open=false},
                            onDeleteForEveryone={deletedForEveryone++;open=false},
                            onReact={reactions.add(it)})
                    }
                }
            }
        }

        val delete=compose.activity.getString(R.string.delete)
        val cancel=compose.activity.getString(R.string.cancel)
        fun open() {
            compose.onNodeWithTag("qa-open-message-actions").performClick()
            compose.onNodeWithTag("message-menu-group").assertIsDisplayed()
            assertMenuInsideWindow(prefix)
        }
        fun startDeletion() {
            compose.onNodeWithText(delete).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.delete_message)).assertIsDisplayed()
            compose.runOnIdle {assertEquals(0,deletedForMe);assertEquals(0,deletedForEveryone)}
            assertMenuInsideWindow(prefix)
        }

        open()
        saveDialog("message-actions-$prefix.png")
        startDeletion()
        compose.onNodeWithText(cancel).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("message-menu-group").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0,deletedForMe);assertEquals(0,deletedForEveryone)}

        open()
        startDeletion()
        if(deleteEveryone) {
            compose.onNodeWithText(compose.activity.getString(R.string.delete_also_peer))
                .performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText(delete).performScrollTo().assertIsDisplayed()
        assertMenuInsideWindow(prefix)
        saveDialog("message-actions-$prefix-delete-confirmation.png")
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithTag("message-menu-group").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(if(deleteEveryone)0 else 1,deletedForMe)
            assertEquals(if(deleteEveryone)1 else 0,deletedForEveryone)
        }

        open()
        val reactionLabel = compose.activity.getString(R.string.react_with, "👍")
        if (compose.onAllNodesWithContentDescription(reactionLabel).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription(reactionLabel).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("message-menu-group").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf("👍"), reactions) }
        } else {
            // Very short windows use the reachable menu shortcut, not the inline strip.
            compose.onNodeWithText(compose.activity.getString(R.string.message_reaction_action))
                .performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("message-menu-reaction-picker").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertExists()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("message-menu-group").assertIsDisplayed()
            compose.runOnIdle { assertTrue(reactions.isEmpty()) }
        }
        compose.runOnIdle {
            assertEquals(1, deletedForMe + deletedForEveryone)
        }
    }

    private fun assertMenuInsideWindow(label:String) {
        compose.waitForIdle()
        val window=compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val group=compose.onNodeWithTag("message-menu-group").fetchSemanticsNode().boundsInRoot
        val actions=compose.onNodeWithTag("message-menu-actions").fetchSemanticsNode().boundsInRoot
        fun inside(bounds:Rect)=bounds.width>0f&&bounds.height>0f&&
            bounds.left>=window.left-1f&&bounds.top>=window.top-1f&&
            bounds.right<=window.right+1f&&bounds.bottom<=window.bottom+1f
        android.util.Log.i("MessageActionsUIQA","$label screen=$window group=$group actions=$actions")
        assertTrue("Group must remain in the actual Dialog window: $group within $window",inside(group))
        assertTrue("Scrollable actions must remain in the actual Dialog window: $actions within $window",inside(actions))
    }

    private fun saveDialog(name:String) {
        val image=compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val folder=compose.activity.getExternalFilesDir("capsules-uiqa")!!
        folder.mkdirs()
        File(folder,name).outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
}
