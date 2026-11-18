package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.*
import com.messenger.app.ui.theme.CocoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Actual ChatScreen on hardware; deterministic repository fixture, not an E2E transport test. */
class ChatStressInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chat = ChatSummary("qa-render-only", "saved", null, "2026-09-27T00:00:00Z", null)
    private fun row(i: Int) = Message("qa-$i", "qa-server-$i", chat.chatId, "qa-peer",
        "QA row %05d ".format(i) + "readable text ".repeat(1 + i % 5), i.toLong(),
        Instant.parse("2026-09-27T00:00:00Z").plusSeconds(i.toLong()).toString(), MessageStatus.DELIVERED)
    private fun show(rows: MutableStateFlow<List<Message>>, bookmarks: MutableMap<String, ChatScrollBookmark>,
                     attachments: StateFlow<List<Attachment>> = MutableStateFlow(emptyList()),
                     load: suspend () -> Result<Boolean>): ViewModelStore {
        val store = ViewModelStore()
        lateinit var vm: ChatDetailViewModel
        compose.runOnUiThread {
            vm = createViewModel(rows, attachments, load)
            store.put("qa", vm)
        }
        compose.setContent { CocoTheme { ChatScreen(vm, {}, readReceiptsAllowed = false, scrollBookmarks = bookmarks) } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("chat-timeline").fetchSemanticsNodes().isNotEmpty() }
        return store
    }
    private fun createViewModel(rows: MutableStateFlow<List<Message>>, attachments: StateFlow<List<Attachment>>,
                                load: suspend () -> Result<Boolean>): ChatDetailViewModel {
        val app = compose.activity.application as MessengerApp
        val chats = object : ChatRepository by app.chatRepository {
            override fun observeChat(chatId: String) = flowOf(chat)
            override suspend fun getChat(chatId: String) = Result.success(chat)
        }
        val messages = object : MessageRepository by app.messageRepository {
            override val socketState = MutableStateFlow(SocketConnectionState.CONNECTED)
            override fun observeMessages(chatId: String) = rows
            override fun observeAttachments(chatId: String) = attachments
            override suspend fun syncLatest(chatId: String) = Result.success(Unit)
            override suspend fun loadOlderMessages(chatId: String) = load()
            override suspend fun reconcileReceipts(chatId: String, otherMemberDeliveredSequence: Long, otherMemberReadSequence: Long) {}
            override suspend fun markRead(chatId: String, sequence: Long) = Result.success(Unit)
            override suspend fun chatValue(chatId: String, name: String): String? = null
            override suspend fun saveChatValue(chatId: String, name: String, value: String?) {}
        }
        val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("qa-owner", "qa", "QA")))
        }
        return ChatDetailViewModel(chats, messages, auth, app.attachmentManager, app.voicePlayerController, chat.chatId, chat)
    }

    @Test fun photoHistoryForwardReverseFrameProbe() {
        val root = java.io.File(compose.activity.cacheDir, "photo-scroll-probe").apply { mkdirs() }
        val bitmap = android.graphics.Bitmap.createBitmap(4000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        try {
            for (i in 1..60) {
                canvas.drawColor(android.graphics.Color.rgb(30 + i, 60, 90))
                for (line in 0..70) {
                    paint.color = android.graphics.Color.rgb((line * 17 + i) % 255, (line * 7) % 255, 180)
                    canvas.drawRect(0f, line * 43f, 4000f, line * 43f + 20f, paint)
                }
                java.io.File(root, "$i.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } finally { bitmap.recycle() }
        val rows = MutableStateFlow((1..60).map { row(it).copy(text = "") })
        val photos = MutableStateFlow((1..60).map { i -> Attachment("photo-probe-$i", "qa-$i",
            AttachmentMediaType.IMAGE, "image/jpeg", "$i.jpg", java.io.File(root, "$i.jpg").length(), 4000, 3000, null,
            null, java.io.File(root, "$i.jpg").path, AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.SENT, 100) })
        val store = show(rows, mutableMapOf(), photos) { Result.success(false) }
        val phase = java.util.concurrent.atomic.AtomicReference("setup")
        val frames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val handlerThread = android.os.HandlerThread("qa-frame-probe").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frames.add("${phase.get()},${metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)},${metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION)},$dropped")
        }
        compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(handlerThread.looper)) }
        try {
            val list = compose.onNodeWithTag("chat-timeline")
            list.performScrollToIndex(0)
            for ((label, direction) in listOf("forward" to true, "reverse" to false, "warm-forward" to true, "warm-reverse" to false)) {
                phase.set(label)
                repeat(20) {
                    list.performTouchInput { if (direction) swipeUp(durationMillis = 200) else swipeDown(durationMillis = 200) }
                }
                compose.waitForIdle()
            }
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener); store.clear() }
            handlerThread.quitSafely(); handlerThread.join()
            java.io.File(compose.activity.filesDir, "photo-scroll-frames.csv").writeText("phase,total_ns,draw_ns,dropped_callbacks\n" + frames.joinToString("\n"))
            com.messenger.app.data.local.ChatImageCache.clear()
            root.listFiles()?.forEach { it.delete() }; root.delete()
        }
    }
    @Test fun tenThousandRowsCanSeekAndKeepPositionOnIncoming() {
        val rows = MutableStateFlow((1..10000).map(::row))
        val bookmarks = mutableMapOf<String, ChatScrollBookmark>()
        val store = show(rows, bookmarks) { Result.success(false) }
        try {
            val list = compose.onNodeWithTag("chat-timeline")
            list.performScrollToIndex(5000)
            compose.waitForIdle()
            val before = bookmarks.getValue(chat.chatId)
            compose.runOnIdle { rows.value = rows.value + row(10001) }
            compose.waitForIdle()
            val after = bookmarks.getValue(chat.chatId)
            assertEquals(before.key, after.key)
            assertEquals(before.offset, after.offset)
            for (index in listOf(0, 9999, 2000, 8000, 4000, 9990)) list.performScrollToIndex(index)
            repeat(6) {
                list.performTouchInput { swipeDown(durationMillis = 250) }
                list.performTouchInput { swipeUp(durationMillis = 250) }
            }
            compose.waitForIdle()
            val composed = compose.onAllNodes(hasText("QA row", substring = true)).fetchSemanticsNodes().size
            assertTrue("The lazy list must not compose 10000 text rows", composed in 1..100)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.filesDir, "chat-stress-10000.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            println("QA composed text rows after gestures: $composed")
            assertEquals(10001, rows.value.size)
            println("QA 10000-row actual ChatScreen: seek and incoming anchor passed")
        } finally { compose.runOnUiThread { store.clear() } }
    }
    @Test fun incomingWhileOlderPageLoadsPreservesAnchor() {
        val rows = MutableStateFlow((101..200).map(::row))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bookmarks = mutableMapOf<String, ChatScrollBookmark>()
        val store = show(rows, bookmarks) {
            started.complete(Unit)
            release.await()
            rows.value = (1..100).map(::row) + rows.value
            Result.success(false)
        }
        try {
            compose.onNodeWithTag("chat-timeline").performScrollToIndex(0)
            compose.waitUntil(10000) { started.isCompleted }
            compose.waitForIdle()
            val before = bookmarks.getValue(chat.chatId)
            compose.runOnIdle { rows.value = rows.value + row(201) }
            compose.waitForIdle()
            assertEquals(before.key, bookmarks.getValue(chat.chatId).key)
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil(10000) { rows.value.size == 201 }
            compose.waitForIdle()
            assertEquals(before.key, bookmarks.getValue(chat.chatId).key)
            assertEquals(before.offset, bookmarks.getValue(chat.chatId).offset)
            assertEquals(201, rows.value.map { it.clientMessageId }.distinct().size)
            println("QA concurrent incoming + prepend: anchor and uniqueness passed")
        } finally { compose.runOnUiThread { store.clear() } }
    }
    @Test fun receiptsReactionsAndSixUploadsDuringScrolling() {
        val rows = MutableStateFlow((1..5000).map { row(it).copy(senderId = "qa-owner",
            reactions = listOf(ReactionSummary("👍", 1, false))) })
        val uploads = MutableStateFlow((2500..2505).map { i -> Attachment("upload-$i", "qa-$i",
            AttachmentMediaType.FILE, "application/octet-stream", "QA-$i.bin", 1000000, null, null, null,
            null, null, AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.UPLOADING, 0) })
        val bookmarks = mutableMapOf<String, ChatScrollBookmark>()
        val store = show(rows, bookmarks, uploads) { Result.success(false) }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        var worker: Thread? = null
        try {
            val list = compose.onNodeWithTag("chat-timeline")
            list.performScrollToIndex(2499)
            compose.waitForIdle()
            compose.onAllNodesWithText(" · 0%", substring = true).onFirst().assertIsDisplayed()
            val before = bookmarks.getValue(chat.chatId)
            compose.runOnIdle {
                uploads.value = uploads.value.map { it.copy(progress = 40) }
                rows.value = rows.value.map { it.copy(status = MessageStatus.READ, reactions = listOf(ReactionSummary("👍", 2, true))) }
            }
            compose.waitForIdle()
            assertEquals(before.key, bookmarks.getValue(chat.chatId).key)
            assertEquals(before.offset, bookmarks.getValue(chat.chatId).offset)
            compose.onAllNodesWithText(" · 40%", substring = true).onFirst().assertIsDisplayed()
            worker = Thread {
                try {
                    repeat(180) { tick ->
                        uploads.value = uploads.value.map { it.copy(progress = 41 + tick * 58 / 179) }
                        rows.value = rows.value.map { it.copy(status = if (tick % 2 == 0) MessageStatus.DELIVERED else MessageStatus.READ,
                            reactions = listOf(ReactionSummary("👍", 2 + tick % 3, tick % 2 == 0))) }
                        Thread.sleep(30)
                    }
                } catch (error: Throwable) { failure.set(error) }
            }.also { it.start() }
            repeat(8) {
                list.performTouchInput { swipeDown(durationMillis = 250) }
                list.performTouchInput { swipeUp(durationMillis = 250) }
            }
            worker.join(15000)
            assertFalse("Update worker must finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Update worker failed", it) }
            compose.waitForIdle()
            assertEquals(5000, rows.value.size)
            assertEquals(6, uploads.value.size)
            assertTrue(uploads.value.all { it.progress == 99 })
            val index = bookmarks.getValue(chat.chatId).key!!.removePrefix("qa-").toInt()
            assertTrue("No jump to tail during updates: $index", index in 1800..3200)
            println("QA 5000 rows / 180 update batches / 6 uploads / 16 swipes passed, anchor=$index")
        } finally {
            worker?.interrupt(); worker?.join(1000)
            compose.runOnUiThread { store.clear() }
        }
    }

    // Keep referent temporaries out of the test's long-lived stack frame during GC.
    private fun liveModels(refs: List<java.lang.ref.WeakReference<ChatDetailViewModel>>): Int = refs.count { it.get() != null }

    @Test fun repeatedChatScreensReleaseTheirViewModels() {
        val rows = MutableStateFlow((1..1000).map(::row))
        val attachments = MutableStateFlow(emptyList<Attachment>())
        val current = androidx.compose.runtime.mutableStateOf<ChatDetailViewModel?>(null)
        val refs = mutableListOf<java.lang.ref.WeakReference<ChatDetailViewModel>>()
        val report = StringBuilder("cycle,pss_kb,java_used_bytes,live_viewmodels\n")
        fun sample(cycle: Int) {
            Runtime.getRuntime().gc(); System.runFinalization(); android.os.SystemClock.sleep(250)
            val info = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            report.append("$cycle,${info.totalPss},${runtime.totalMemory()-runtime.freeMemory()},${liveModels(refs)}\n")
        }
        compose.setContent { CocoTheme {
            current.value?.let { vm -> androidx.compose.runtime.key(vm) { ChatScreen(vm, {}, readReceiptsAllowed = false) } }
        } }
        sample(0)
        repeat(20) { cycle ->
            val store = ViewModelStore()
            try {
                compose.runOnIdle {
                    val vm = createViewModel(rows, attachments) { Result.success(false) }
                    refs += java.lang.ref.WeakReference(vm)
                    store.put("qa", vm); current.value = vm
                }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("chat-timeline").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chat-timeline").performScrollToIndex(500)
                compose.waitForIdle()
            } finally {
                compose.runOnIdle { current.value = null }
                compose.waitForIdle()
                compose.runOnUiThread { store.clear() }
            }
            if ((cycle + 1) % 5 == 0) sample(cycle + 1)
        }
        java.io.File(compose.activity.filesDir, "chat-memory-cycles.csv").writeText(report.toString())
        println("QA retained viewmodel count: " + liveModels(refs))
        // Compose's positioned-node cache retains the most recently measured tree
        // while this synthetic destination is empty. It must not accumulate screens.
        assertTrue("Closed screen models must not accumulate", liveModels(refs) <= 1)
        val files = compose.activity.filesDir
        compose.activityRule.scenario.close()
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (liveModels(refs) > 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            Runtime.getRuntime().gc(); System.runFinalization(); android.os.SystemClock.sleep(100)
        }
        if (liveModels(refs) > 0) android.os.Debug.dumpHprofData(java.io.File(files, "chat-owner-retained.hprof").absolutePath)
        assertTrue("Activity destruction must release every screen model", liveModels(refs) == 0)
        sample(21)
        java.io.File(files, "chat-memory-cycles.csv").writeText(report.toString())
        println("QA chat memory cycles: " + report.toString())
        assertEquals(20, refs.size)
    }

}
