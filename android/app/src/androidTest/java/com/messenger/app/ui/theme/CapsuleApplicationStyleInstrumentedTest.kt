package com.messenger.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.*
import com.messenger.app.ui.call.CallActionButton
import com.messenger.app.ui.chats.MessageBubble
import com.messenger.app.ui.settings.AppearanceScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real controls and their callbacks; no account logout or live call is performed. */
class CapsuleApplicationStyleInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun appearanceSizeChoiceChangesTheActualMessageTypography() {
        val prefs=compose.activity.getSharedPreferences("coco-appearance",0)
        val old=prefs.all.filterKeys {it in setOf("chat-text-size","subtle-pattern","capsule-app-style-adopted")}
        prefs.edit().putInt("chat-text-size",15).commit()
        var settings by mutableStateOf(true)
        val message=Message("font-sample","font-server","chat","me","Capsule font sample",1,"2026-10-26T12:00:00Z",MessageStatus.READ)
        try {
            compose.setContent {CocoTheme {Column(Modifier.fillMaxSize()) {
                Button(onClick={settings=!settings}) {Text("Switch view")}
                Box(Modifier.weight(1f)) {
                    if(settings)AppearanceScreen(ThemeMode.DARK,{}, {})
                    else MessageBubble(message=message,attachment=null,isOwnMessage=true,playingAttachmentId=null,
                        onRetry={},onDownload={},onTogglePlay={_,_->},onOpenFile={_,_->},onOpenImage={},
                        onLongPress={},onSelectionTap={},onToggleReaction={},onQuickReaction=null,onScrollToReply={})
                }
            }}}
            val label="19 · ${compose.activity.getString(R.string.chat_text_size_largest)}"
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText("Switch view").performClick()
            compose.waitForIdle()
            val results=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Capsule font sample").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(results)}
            assertEquals(19f,results.single().layoutInput.style.fontSize.value,.01f)
        } finally {
            val editor=prefs.edit()
            for(key in listOf("chat-text-size","subtle-pattern","capsule-app-style-adopted")) {
                when(val value=old[key]){is Int->editor.putInt(key,value);is Boolean->editor.putBoolean(key,value);else->editor.remove(key)}
            };editor.commit()
        }
    }

    @Test fun logoutRemainsLabelledRedReachableAndClickableInCompactDrawer() {
        var logout=0
        compose.setContent {CocoTheme {Box(Modifier.size(245.dp,500.dp)) {
            GraphiteDrawer(245.dp,null,"COCO QA","test",0,false,RootDestination.CHATS,3,true,true,
                {},{},{},{},{},{},{},{},{},{logout++})
        }}}
        val node=compose.onNodeWithText(compose.activity.getString(R.string.settings_home_sign_out))
        node.assertIsDisplayed() // Footer is fixed outside the scrolling menu body.
        val results=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
        assertEquals(CocoDarkColors.danger,results.single().layoutInput.style.color)
        node.performTouchInput {click()}
        compose.runOnIdle {assertEquals(1,logout)}
    }

    @Test fun incomingAnswerAndEndKeepDistinctSemanticColoursAndWorkingTaps() {
        var accepted=0;var ended=0
        compose.setContent {CocoTheme {Row {
            Box(Modifier.testTag("accept-colour")) {CallActionButton(CocoIcons.AudioCall,"QA Accept",
                CocoDarkColors.onAccent,CocoDarkColors.success,{accepted++})}
            Spacer(Modifier.width(16.dp))
            Box(Modifier.testTag("end-colour")) {CallActionButton(CocoIcons.EndCall,"QA End",
                CocoDarkColors.onDanger,CocoDarkColors.danger,{ended++},endCall=true)}
        }}}
        val accept=compose.onNodeWithTag("accept-colour").captureToImage().toPixelMap()
        val end=compose.onNodeWithTag("end-colour").captureToImage().toPixelMap()
        val a=accept[accept.width/2,accept.height/6];val e=end[end.width/2,end.height/6]
        assertTrue("Answer keeps green",a.green>a.red*1.5f&&a.green>a.blue)
        assertTrue("End keeps red",e.red>e.green*1.5f&&e.red>e.blue)
        compose.onNodeWithContentDescription("QA Accept").performTouchInput {click()}
        compose.onNodeWithContentDescription("QA End").performTouchInput {click()}
        compose.runOnIdle {assertEquals(1,accepted);assertEquals(1,ended)}
    }
}
