package com.messenger.app.push

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in device QA only. No sends during an ordinary instrumentation run. */
class MessageParityDeviceActions {
    @Test fun measureIdleForQa() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaIdle") == "true")
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
        check(owner?.username in setOf("test", "Dima2"))
        val peer = if (owner!!.username == "test") "Dima2" else "test"
        val chat = withTimeout(15_000) { app.chatRepository.chats.first { list -> list.any { it.otherUser?.username == peer } } }
            .first { it.otherUser?.username == peer }
        val db = MessengerDatabase.create(app)
        val message = try { db.messageDao().observeMessages(chat.chatId).first().last { it.serverId != null } }
            finally { db.close() }
        // The owner's enabled diagnostics intentionally request every display frame.
        // Suspend that owner only in this QA process; keep its saved preference intact.
        app.performanceDiagnostics.close()
        val target = NotificationTapTarget(app.accountWorkspaces.activeKey, owner.id,
            app.authRepository.currentDeviceId()!!, chat.chatId, message.serverId!!)
        ActivityScenario.launch<MainActivity>(NotificationIntents.open(app, target).apply { data = null }).use {
            kotlinx.coroutines.delay(5_000)
            val start = android.os.SystemClock.elapsedRealtime()
            val cpu = android.os.Process.getElapsedCpuTime()
            kotlinx.coroutines.delay(20_000)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putLong("qaIdleElapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                putLong("qaIdleCpuMs", android.os.Process.getElapsedCpuTime() - cpu)
                putString("qaDiagnostics", "suspended in QA process only; saved preference unchanged")
            })
        }
        Unit
    }

    @Test fun sendAndObserveReceipt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val peer = args.getString("qaPeer")
        val marker = args.getString("qaText")
        assumeTrue(peer in setOf("test", "Dima2") && marker?.startsWith("parity-") == true)
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
        check(owner?.username in setOf("test", "Dima2"))
        app.chatRepository.refreshChats().getOrThrow()
        val chats = withTimeout(15_000) { app.chatRepository.chats.first { list -> list.any { it.otherUser?.username == peer } } }
        val chat = chats.first { it.otherUser?.username == peer }
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("qaSendReady", true) })
        kotlinx.coroutines.delay((args.getString("qaSendDelayMs")?.toLongOrNull() ?: 0).coerceIn(0, 20_000))
        app.messageRepository.sendMessage(chat.chatId, marker!!).getOrThrow()
        val expected = args.getString("qaExpected") ?: "read"
        var last: MessageStatus? = null
        withTimeout((args.getString("qaTimeout")?.toLongOrNull() ?: 45) * 1000) {
            app.messageRepository.observeMessages(chat.chatId).first { messages ->
                val row = messages.lastOrNull { it.text == marker } ?: return@first false
                if (row.status != last) {
                    last = row.status
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("qaReceipt", row.status.name)
                        putString("qaChat", row.chatId); putString("qaMessage", row.serverId)
                        putString("qaClientMessage", row.clientMessageId)
                        putLong("qaUptimeMs", android.os.SystemClock.elapsedRealtime())
                        putLong("qaWallMs", System.currentTimeMillis())
                    })
                }
                when (expected) {
                    "sent" -> row.status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)
                    "delivered" -> row.status in setOf(MessageStatus.DELIVERED, MessageStatus.READ)
                    else -> row.status == MessageStatus.READ
                }
            }
        }
        Unit
    }
    @Test fun setPreviewForQa() {
        val args = InstrumentationRegistry.getArguments()
        val value = args.getString("qaPreviews")
        assumeTrue(value in setOf("true", "false"))
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.username
        check(owner in setOf("test", "Dima2"))
        app.notificationSettings.update { it.copy(previews = value == "true") }
        // Instrumentation terminates this process without a normal Activity stop.
        // Flush the QA preference before the runner can kill the async apply writer.
        check(app.getSharedPreferences("coco-notifications", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("previews", value == "true").commit())
    }
}
