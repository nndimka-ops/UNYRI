package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ChatControlsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun messageMarkerCentersOnFilledBodyWithoutReceiptOrReactionOffset() {
        val selecting = mutableStateOf(false)
        val outgoing = mutableStateOf(true)
        val text = mutableStateOf("Привет")
        var body = androidx.compose.ui.geometry.Rect.Zero
        val message = com.messenger.app.domain.model.Message("center", "server-center", "fixture-chat", "me", "", 1L,
            "2026-11-01T12:00:00Z", com.messenger.app.domain.model.MessageStatus.SENT,
            reactions = listOf(com.messenger.app.domain.model.ReactionSummary("❤️", 1, false)))
        compose.setContent { CocoTheme {
            Box(Modifier.width(320.dp).fillMaxHeight().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                MessageBubble(message = message.copy(text = text.value), attachment = null, isOwnMessage = outgoing.value,
                    onBoundsChanged = { body = it }, playingAttachmentId = null, selectionMode = selecting.value,
                    onRetry = {}, onDownload = {}, onTogglePlay = { _, _ -> }, onOpenFile = { _, _ -> }, onOpenImage = {},
                    onLongPress = {}, onSelectionTap = {}, onToggleReaction = {}, onQuickReaction = null, onScrollToReply = {})
            }
        } }
        for (own in listOf(true, false)) {
            for (content in listOf("Привет", "Длинное сообщение с несколькими строками и реакцией под ним")) {
                compose.runOnIdle { selecting.value = false; outgoing.value = own; text.value = content }
                compose.waitForIdle()
                val original = body
                compose.runOnIdle { selecting.value = true }
                compose.waitForIdle()
                assertEquals("Selection must preserve the filled body", original, body)
                val target = compose.onNode(isToggleable()).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                assertEquals("Receipt and reactions must not shift the mark", body.center.y, target.center.y, 1f)
                assertTrue(target.width / compose.activity.resources.displayMetrics.density >= 47.5f)
            }
        }
        saveScreenshot("message-marker-body-center.png")
    }

    @Test fun oneAlbumMarkerSelectsEveryTileWithoutMovingTheAlbumOrOpeningMedia() {
        val selecting = mutableStateOf(false)
        val selected = mutableStateOf(emptySet<String>())
        var opened = 0
        val file = java.io.File(compose.activity.cacheDir, "whole-group-selection.png")
        val bitmap = android.graphics.Bitmap.createBitmap(96, 72, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(80, 110, 130))
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val messages = (1..2).map { id -> com.messenger.app.domain.model.Message("group-$id", "server-$id", "fixture-chat", "me", "", id.toLong(),
            "2026-11-01T12:00:00Z", com.messenger.app.domain.model.MessageStatus.SENT, albumId = "fixture-group") }
        val media = messages.map { message -> com.messenger.app.domain.model.Attachment("attachment-${message.clientMessageId}", message.clientMessageId,
            com.messenger.app.domain.model.AttachmentMediaType.IMAGE, "image/png", file.name, file.length(), 96, 72, null, null, file.path,
            com.messenger.app.domain.model.AttachmentDownloadState.DOWNLOADED, com.messenger.app.domain.model.AttachmentUploadState.SENT, 100) }
        compose.setContent { CocoTheme {
            val colors = com.messenger.app.ui.theme.LocalCocoColors.current
            Box(Modifier.width(320.dp).fillMaxSize().background(colors.background).padding(12.dp)) {
                CapsuleChatAlbum(messages, true, media.associateBy { it.clientMessageId }, selectionMark = if (!selecting.value) null else ({
                    ChatSelectionMark(messages.all { it.clientMessageId in selected.value }, { selected.value = toggleChatMessageGroup(selected.value, messages) })
                })) { message ->
                    MessageBubble(message = message, attachment = media.first { it.clientMessageId == message.clientMessageId }, isOwnMessage = true,
                        playingAttachmentId = null, selectionMode = selecting.value, selected = message.clientMessageId in selected.value,
                        onRetry = {}, onDownload = {}, onTogglePlay = { _, _ -> }, onOpenFile = { _, _ -> opened++ }, onOpenImage = { opened++ },
                        onLongPress = {}, onSelectionTap = { selected.value = toggleChatMessageGroup(selected.value, messages) },
                        onToggleReaction = {}, onQuickReaction = null, onScrollToReply = {})
                }
            }
        } }
        val album = compose.onNodeWithTag("chat-album")
        val bounds = album.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { selecting.value = true }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        assertEquals(bounds, album.fetchSemanticsNode().boundsInRoot)
        val filledBody = compose.onNodeWithTag("chat-album-body").fetchSemanticsNode().boundsInRoot
        val marker = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
        assertEquals("Album marker must ignore external metadata", filledBody.center.y, marker.center.y, 1f)
        album.performTouchInput { click(androidx.compose.ui.geometry.Offset(bounds.width * .25f, bounds.height * .25f)) }
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(messages.map { it.clientMessageId }.toSet(), selected.value); assertEquals(0, opened) }
        saveScreenshot("album-one-marker-whole-group.png")
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(selected.value.isEmpty()); assertEquals(0, opened); selecting.value = false }
        assertEquals(bounds, album.fetchSemanticsNode().boundsInRoot)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test fun selectionActionsStayCompactWithoutLosingTargetsOrDisabledState() {
        val enabled = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        var forwards = 0
        var deletes = 0
        compose.setContent { CocoTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale.value)) {
                Box(Modifier.width(320.dp)) { ChatSelectionActionsBar(enabled.value, { forwards++ }, { deletes++ }) }
            }
        } }
        val density = compose.activity.resources.displayMetrics.density
        val bar = compose.onNodeWithTag("chat_selection_actions")
        assertTrue(bar.fetchSemanticsNode().boundsInRoot.height / density <= 56.5f)
        val forward = compose.onNodeWithTag("chat_selection_forward")
        val delete = compose.onNodeWithTag("chat_selection_delete")
        for (target in listOf(forward, delete)) {
            val bounds = target.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height / density >= 47.5f && bounds.width / density >= 47.5f)
        }
        forward.assertIsNotEnabled()
        delete.performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(0, forwards); enabled.value = true }
        forward.performClick()
        compose.runOnIdle { assertEquals(1, forwards) }
        saveScreenshot("selection-actions-compact.png")
        compose.runOnIdle { scale.value = 2f }
        forward.assertIsDisplayed().assertIsEnabled()
        delete.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, deletes) }
        saveScreenshot("selection-actions-large-text.png")
    }

    @Test fun mediaTransferShowsOnlyCancelAndPreservesAccessibleProgress() {
        val progress = mutableStateOf(27)
        val waiting = mutableStateOf(false)
        var cancelled = 0
        compose.setContent { CocoTheme {
            FileTransferControl(progress.value, waiting.value, "Отменить отправку", onCancel = { cancelled++ },
                mediaOverlay = true, statusDescription = if (waiting.value) "Ожидание сети" else "Отправка ${progress.value}%")
        } }
        val control = compose.onNodeWithTag("pending_media_cancel")
        control.assertIsDisplayed().assertHasClickAction()
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(48f, bounds.width / density, .5f)
        assertEquals(48f, bounds.height / density, .5f)
        compose.onAllNodes(hasText("Отправка", substring = true)).assertCountEquals(0)
        control.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Отправка 27%"))
        compose.runOnIdle { progress.value = 86 }
        control.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Отправка 86%"))
        assertEquals(bounds, control.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { waiting.value = true }
        compose.onAllNodes(hasText("Ожидание сети")).assertCountEquals(0)
        control.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Ожидание сети"))
        control.performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }
    @Test fun selectedAttachmentsShareComposerAndReleaseItAfterSending() {
        val context = compose.activity
        val folder = java.io.File(context.cacheDir, "composer-uiqa").apply { mkdirs() }
        val photos = (1..2).map { index ->
            val file = java.io.File(folder, "photo-$index.png")
            val bitmap = android.graphics.Bitmap.createBitmap(96, 72, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (index == 1) android.graphics.Color.rgb(35, 110, 165) else android.graphics.Color.rgb(115, 75, 160))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            android.net.Uri.fromFile(file)
        }
        val pdf = java.io.File(folder, "Plan.pdf").apply { writeText("COCO synthetic UI fixture") }
        val selected = mutableStateOf(photos)
        val document = mutableStateOf<android.net.Uri?>(android.net.Uri.fromFile(pdf))
        val caption = mutableStateOf("")
        var sent = 0
        compose.setContent { CocoTheme { Box(Modifier.width(320.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            ComposerBar(attachmentCount = selected.value.size + if (document.value == null) 0 else 1,
                attachments = if (selected.value.isNotEmpty() || document.value != null) ({
                    ComposerAttachmentTray(selected.value, document.value, { uri -> selected.value -= uri }, { document.value = null })
                }) else null,
                keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {}, input = caption.value, submitting = false,
                onInputChange = { caption.value = it }, isRecording = false, onAttach = {}, onMicToggle = {},
                onSend = { sent++; selected.value = emptyList(); document.value = null; caption.value = "" })
        } } }
        compose.onNodeWithContentDescription(context.getString(R.string.voice_record)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled()
        val removeBounds = compose.onNodeWithTag("composer-remove-0").fetchSemanticsNode().boundsInRoot
        assertTrue("Visible thumbnails stay compact without losing the remove target", removeBounds.width >= 36 * context.resources.displayMetrics.density - 1)
        compose.onNode(hasSetTextAction()).performTextInput("Photo and video from our walk")
        compose.waitForIdle()
        saveScreenshot("composer-selected.png")
        compose.onNodeWithTag("composer-remove-0").performClick()
        compose.runOnIdle { assertEquals(1, selected.value.size); assertEquals("Photo and video from our walk", caption.value) }
        compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        compose.runOnIdle { assertEquals(1, sent); assertTrue(caption.value.isEmpty()) }
        compose.onNodeWithTag("composer-attachments").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Next message")
        compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled()
        saveScreenshot("composer-next-message.png")
    }

    @Test fun compactReplyKeepsInputAndDismissUsable() {
        val replying = mutableStateOf(true)
        val input = mutableStateOf("")
        compose.setContent { CocoTheme { Box(Modifier.width(320.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            ComposerBar(replyHeader = if (replying.value) ({
                ReplyComposerBanner("Ксения", "В 19:00 удобно?", null, null, false) { replying.value = false }
            }) else null, keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = input.value, submitting = false, onInputChange = { input.value = it },
                isRecording = false, onAttach = {}, onMicToggle = {}, onSend = {})
        } } }
        compose.onNodeWithText("Ксения").assertIsDisplayed()
        compose.onNodeWithText("В 19:00 удобно?").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Да")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reply_cancel)).performClick()
        compose.onNodeWithText("Ксения").assertDoesNotExist()
        compose.onNodeWithText("В 19:00 удобно?").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Да", input.value) }
    }

    @Test fun attachmentMenuKeepsRealImeOnOpenOutsideAndBackWithoutChangingDraft() {
        val draft = mutableStateOf("Existing draft")
        var selected = 0
        var samplePhase = "baseline"
        var sampleIme = false
        val imeSamples = mutableListOf<Triple<String, Boolean, Int>>()
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent { CocoTheme {
            DisposableEffect(Unit) {
                val decor = compose.activity.window.decorView
                val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                    if (sampleIme) {
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                        imeSamples += Triple(samplePhase,
                            insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true,
                            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                    }
                    true
                }
                decor.viewTreeObserver.addOnPreDrawListener(listener)
                onDispose { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
            Box(Modifier.fillMaxSize().background(CapsuleChatStyle.background)
                .testTag("composer-menu-host")) {
                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .then(Modifier.imePadding())) {
                    ComposerBar(keyboardVisible = true, onFocusChanged = {}, onHideKeyboard = {},
                        input = draft.value, submitting = false, onInputChange = { draft.value = it },
                        isRecording = false, onAttach = { selected++ }, onAttachCamera = { selected++ },
                        onAttachFiles = { selected++ }, onMicToggle = {}, onSend = {})
                }
            }
        } }
        fun imeShown() = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        var lastImeBottom = -1
        var stableSince = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(8_000) {
            val current = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val now = android.os.SystemClock.elapsedRealtime()
            if (current != lastImeBottom) { lastImeBottom = current; stableSince = now }
            imeShown() && current > 0 && now - stableSince >= 400
        }
        field.assertIsFocused()
        val fieldBefore = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInWindow
        fun assertImeUnchanged(phase: String) {
            assertTrue("$phase must retain the real keyboard", imeShown())
            assertEquals("$phase keeps the complete composer frame", fieldBefore,
                compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInWindow)
            compose.runOnIdle {
                val bottom = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
                assertEquals("$phase keeps the real IME frame", lastImeBottom, bottom)
                assertTrue("IME must never hide or resize and restore between checks",
                    imeSamples.all { it.second && it.third == lastImeBottom })
            }
        }
        val windowOrigin = IntArray(2)
        compose.runOnIdle {
            val screen = IntArray(2); val window = IntArray(2)
            compose.activity.window.decorView.getLocationOnScreen(screen)
            compose.activity.window.decorView.getLocationInWindow(window)
            windowOrigin[0] = screen[0] - window[0]; windowOrigin[1] = screen[1] - window[1]
        }
        val toggle = compose.onNodeWithTag("composer-attachment-toggle")
        saveScreenshot("composer-menu-ime-before.png")
        compose.runOnIdle { samplePhase = "open"; sampleIme = true }
        toggle.performTouchInput { click() }
        compose.onNodeWithTag("composer-attachment-photos").assertIsDisplayed()
        compose.waitForIdle()
        assertImeUnchanged("open")
        val lastRow = requireNotNull(nativeMenuLabelBounds(compose.activity.getString(R.string.media_files))) {
            "Native menu exposes Files to accessibility"
        }
        assertTrue("Menu rows must stay above the whole composer", lastRow.bottom <= fieldBefore.top + windowOrigin[1] + 1)
        saveScreenshot("composer-menu-ime-open.png")
        val host = compose.onNodeWithTag("composer-menu-host").fetchSemanticsNode().boundsInWindow
        compose.runOnIdle { samplePhase = "outside" }
        var statusTop=0
        var nativePopup=android.graphics.Rect()
        compose.runOnIdle {
            statusTop=androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())?.top?:0
            val popup=android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.javaClass.name.contains("PopupLayout") }
            val origin=IntArray(2);popup.getLocationOnScreen(origin)
            nativePopup=android.graphics.Rect(origin[0],origin[1],origin[0]+popup.width,origin[1]+popup.height)
        }
        val outsideX=(host.center.x+windowOrigin[0]).toInt()
        val outsideY=maxOf((host.top+windowOrigin[1]+30*compose.activity.resources.displayMetrics.density).toInt(),
            statusTop+(16*compose.activity.resources.displayMetrics.density).toInt())
        assertTrue("Outside tap is inside actual app content below system bars",outsideY>statusTop&&outsideY>host.top+windowOrigin[1]&&outsideY<host.bottom+windowOrigin[1])
        assertTrue("Outside tap is above the entire editor",outsideY<fieldBefore.top+windowOrigin[1])
        assertFalse("Outside tap is outside the actual native popup",nativePopup.contains(outsideX,outsideY))
        java.io.File(compose.activity.getExternalFilesDir(null),"composer-menu-outside-point.json").writeText(org.json.JSONObject()
            .put("oldTapY",(host.top+windowOrigin[1]+30*compose.activity.resources.displayMetrics.density).toInt())
            .put("statusCutoutTop",statusTop).put("actualTapX",outsideX).put("actualTapY",outsideY)
            .put("nativePopup",nativePopup.toShortString()).put("fieldTop",fieldBefore.top+windowOrigin[1]).toString(2))
        nativeMenuClick(outsideX,outsideY)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-attachment-menu").fetchSemanticsNodes().isEmpty() }
        assertImeUnchanged("outside")
        field.assertIsFocused()
        saveScreenshot("composer-menu-ime-outside.png")
        compose.runOnIdle { samplePhase = "reopen" }
        toggle.performTouchInput { click() }
        compose.onNodeWithTag("composer-attachment-files").assertIsDisplayed()
        assertImeUnchanged("reopen")
        compose.runOnIdle { samplePhase = "back" }
        nativeMenuBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-attachment-menu").fetchSemanticsNodes().isEmpty() }
        assertImeUnchanged("back")
        field.assertIsFocused()
        compose.runOnIdle {
            assertEquals("Existing draft", draft.value); assertEquals(0, selected)
            assertTrue("Native frame sampler must have observed actual frames", imeSamples.isNotEmpty())
            val trace = org.json.JSONArray()
            imeSamples.forEach { trace.put(org.json.JSONObject().put("phase", it.first).put("visible", it.second).put("imeBottomPx", it.third)) }
            java.io.File(compose.activity.getExternalFilesDir(null), "composer-menu-ime-frames.json").writeText(trace.toString(2))
            sampleIme = false
        }
        saveScreenshot("composer-menu-ime-dismissed.png")
    }

    @Test fun attachmentMenuKeepsNarrowLargeTextAndRtlTargetsAboveWholeComposer() {
        val scale = mutableStateOf(1f)
        val rtl = mutableStateOf(false)
        compose.setContent { CocoTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides android.content.res.Configuration(configuration).apply { screenWidthDp = 320 },
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale.value),
                androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl.value) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                Box(Modifier.width(320.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                        input = "Draft\nwith two lines", submitting = false, onInputChange = {},
                        isRecording = false, onAttach = {}, onMicToggle = {}, onSend = {})
                }
            }
        } }
        val density = compose.activity.resources.displayMetrics.density
        for (fontScale in listOf(1f, 2f)) for (rightToLeft in listOf(false, true)) {
            compose.runOnIdle { scale.value = fontScale; rtl.value = rightToLeft }
            compose.onNodeWithTag("composer-attachment-toggle").performTouchInput { click() }
            val field = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInWindow
            val menu = compose.onNodeWithTag("composer-attachment-menu").fetchSemanticsNode().boundsInWindow
            assertTrue("320dp screen retains the native horizontal inset", menu.width / density <= 288.5f)
            var originY = 0
            compose.runOnIdle {
                val screen = IntArray(2); val window = IntArray(2)
                compose.activity.window.decorView.getLocationOnScreen(screen)
                compose.activity.window.decorView.getLocationInWindow(window)
                originY = screen[1] - window[1]
            }
            for ((tag, label) in listOf("composer-attachment-camera" to R.string.attach_camera,
                "composer-attachment-photos" to R.string.gallery_media, "composer-attachment-files" to R.string.media_files)) {
                val target = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInWindow
                val native = requireNotNull(nativeMenuLabelBounds(compose.activity.getString(label))) {
                    "Each native menu row is accessible"
                }
                assertTrue("Narrow menu rows must stay above multiline composer", native.bottom <= field.top + originY + 1)
                assertTrue("Narrow menu must fit screen", native.left >= 0 && native.right <= nativeMenuDisplayWidth() + 1)
                assertTrue("Menu targets retain at least 48dp", target.width / density >= 47.5f && target.height / density >= 47.5f)
            }
            saveScreenshot("composer-menu-320-font-$fontScale-rtl-$rightToLeft.png")
            nativeMenuBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-attachment-menu").fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun nativeMenuLabelBounds(label:String):android.graphics.Rect? {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val info=requireNotNull(automation.serviceInfo);val originalFlags=info.flags
        info.flags=originalFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=info
        try {
            val deadline=android.os.SystemClock.uptimeMillis()+1_000
            do {
                val roots=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                automation.rootInActiveWindow?.let { roots.add(it) }
                automation.windows.forEach { window -> try { window.root?.let { roots.add(it) } } finally { window.recycle() } }
                try {
                    for(root in roots) {
                        if(root.packageName?.toString()!=instrumentation.targetContext.packageName)continue
                        fun exactOwnedBounds(node:android.view.accessibility.AccessibilityNodeInfo,depth:Int):android.graphics.Rect? {
                            if(depth>32)return null
                            if(node.isVisibleToUser&&node.text?.toString()==label&&node.packageName?.toString()==instrumentation.targetContext.packageName)
                                return android.graphics.Rect().also { node.getBoundsInScreen(it) }
                            for(index in 0 until node.childCount) {
                                val child=node.getChild(index)?:continue
                                val found=try { exactOwnedBounds(child,depth+1) } finally { child.recycle() }
                                if(found!=null)return found
                            }
                            return null
                        }
                        exactOwnedBounds(root,0)?.let { return it }
                    }
                } finally { roots.forEach { it.recycle() } }
                android.os.SystemClock.sleep(20)
            } while(android.os.SystemClock.uptimeMillis()<deadline)
            val report=org.json.JSONObject().put("requestedLabel",label).put("targetPackage",instrumentation.targetContext.packageName)
            val ownRows=org.json.JSONArray();val windowRoots=org.json.JSONArray()
            val knownLabels=setOf("Files","Файлы","Файли","Camera","Камера","Фото и видео","Фото і відео","Photos and videos")
            fun describe(node:android.view.accessibility.AccessibilityNodeInfo,depth:Int) {
                if(depth>20)return
                val text=node.text?.toString()
                if(text in knownLabels)ownRows.put(org.json.JSONObject().put("label",text).put("package",node.packageName?.toString())
                    .put("visible",node.isVisibleToUser).put("class",node.className?.toString()))
                for(index in 0 until node.childCount)node.getChild(index)?.let { child -> try { describe(child,depth+1) } finally { child.recycle() } }
            }
            val roots=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let { roots.add(it) }
            automation.windows.forEach { window -> try { window.root?.let { roots.add(it) } } finally { window.recycle() } }
            try { roots.forEach { root -> windowRoots.put(org.json.JSONObject().put("package",root.packageName?.toString()).put("class",root.className?.toString()));describe(root,0) } }
            finally { roots.forEach { it.recycle() } }
            report.put("roots",windowRoots).put("knownMenuRowsOnly",ownRows)
            java.io.File(compose.activity.getExternalFilesDir(null),"composer-menu-ax-failure.json").writeText(report.toString(2))
            return null
        } finally {
            val restored=requireNotNull(automation.serviceInfo);restored.flags=originalFlags;automation.serviceInfo=restored
        }
    }

    private fun nativeMenuClick(x:Int,y:Int) {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val downAt=android.os.SystemClock.uptimeMillis()
        val property=android.view.MotionEvent.PointerProperties().apply { id=0;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER }
        val coordinates=android.view.MotionEvent.PointerCoords().apply { this.x=x.toFloat();this.y=y.toFloat();pressure=1f;size=1f }
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(downAt,android.os.SystemClock.uptimeMillis(),action,1,arrayOf(property),arrayOf(coordinates),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try { check(automation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
    }

    private fun nativeMenuBack() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)

    private fun nativeMenuDisplayWidth():Int {
        val screenshot=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try { screenshot.width } finally { screenshot.recycle() }
    }


    private fun saveScreenshot(name: String) {
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun compactComposerKeepsActionsAndBlankEditingCannotStartVoice() {
        val draft = mutableStateOf("")
        val editing = mutableStateOf(false)
        var attached = 0
        var recorded = 0
        compose.setContent { CocoTheme { Box(Modifier.width(320.dp)) {
            ComposerBar(editing = editing.value, keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = draft.value, submitting = false, onInputChange = { draft.value = it },
                isRecording = false, onAttach = { attached++ }, onMicToggle = { recorded++ }, onSend = {})
        } } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_attach_title)).performClick()
        compose.onNodeWithTag("composer-attachment-photos").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.voice_record)).performClick()
        compose.runOnIdle { assertEquals(1, attached); assertEquals(1, recorded) }
        val field = compose.onNode(hasSetTextAction())
        val height = field.fetchSemanticsNode().boundsInRoot.height / compose.activity.resources.displayMetrics.density
        assertTrue("Input must no longer inherit the 56dp Material field minimum", height < 52)
        compose.runOnIdle { editing.value = true }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.voice_record)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.send)).assertIsNotEnabled()
        field.performTextInput("A longer draft\nwith a second line")
        compose.runOnIdle { assertEquals("A longer draft\nwith a second line", draft.value) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.send)).assertIsEnabled()
    }
    @Test fun emojiUsesCursorAndSendKeepsControlBounds() {
        val draft = mutableStateOf("AB")
        var sent = false
        compose.setContent { CocoTheme { Box(Modifier.width(320.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = draft.value, submitting = false, onInputChange = { draft.value = it },
                isRecording = false, onAttach = {}, onMicToggle = {}, onSend = { sent = true })
        } } }
        val input = compose.onNode(hasSetTextAction())
        input.performTextInputSelection(TextRange(1))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_emoji)).performClick()
        compose.onNodeWithText("😊").performClick()
        compose.runOnIdle { assertEquals("A😊B", draft.value) }
        compose.onNodeWithText("😂").performClick()
        compose.onNodeWithText("😂").performClick()
        compose.runOnIdle { assertEquals("A😊😂😂B", draft.value) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        val send = compose.onNodeWithContentDescription(compose.activity.getString(R.string.send))
        send.assertIsDisplayed()
        val bounds = send.fetchSemanticsNode().boundsInRoot
        send.performClick()
        compose.runOnIdle { assertTrue(sent); draft.value = "" }
        val mic = compose.onNodeWithContentDescription(compose.activity.getString(R.string.voice_record))
        mic.assertIsDisplayed()
        assertEquals(bounds, mic.fetchSemanticsNode().boundsInRoot)
    }
}
