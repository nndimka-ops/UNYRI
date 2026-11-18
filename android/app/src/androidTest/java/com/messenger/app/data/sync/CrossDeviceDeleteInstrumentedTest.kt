package com.messenger.app.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.signal.MessageDecryptor
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicitly invoked test-account scenario; never resets crypto keys or edits database rows. */
@RunWith(AndroidJUnit4::class)
class CrossDeviceDeleteInstrumentedTest {
    @Test fun scenario() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("allowTestAccount") == "yes")
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        app.historyReady.await()
        app.startActivity(android.content.Intent(app, com.messenger.app.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val action = args.getString("action") ?: error("action required")
        if (action == "register") {
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
            if (app.authRepository.authState.value is AuthSessionState.LoggedIn) app.authRepository.logout()
            val suffix = UUID.randomUUID().toString().take(8)
            app.authRepository.register("delete_qa_$suffix", UUID.randomUUID().toString(), "Deletion QA $suffix").getOrThrow()
            app.authRepository.startWithoutRestoredHistory().getOrThrow()
        }
        val state = withTimeout(30_000) { app.authRepository.authState.first { it is AuthSessionState.LoggedIn } } as AuthSessionState.LoggedIn
        val repo = app.messageRepository
        fun field(name: String): Any = repo.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repo)!!
        val pipeline = field("ingestionPipeline") as MessageIngestionPipeline
        val dao = field("dao") as MessageDao
        delay(1500) // Let the app's initial auth emission finish cancelling stale work.
        val output = JSONObject().put("action", action).put("userId", state.user.id)
        if (action == "register") {
            val signal = field("signalSessionManager") as SignalSessionManager
            signal.bootstrapKeysIfNeeded(app.authRepository.currentAccessToken()!!)
        } else if (action != "info") {
            val peer = args.getString("peer") ?: error("peer required")
            val chat = app.chatRepository.createOrOpenDirectChat(peer).getOrThrow().chatId
            val marker = args.getString("marker") ?: error("marker required")
            val saved = File(app.filesDir, "delete-qa-$marker.json")
            output.put("chatId", chat)
            suspend fun capture(): com.messenger.app.domain.model.Message = withTimeout(45_000) {
                while (true) {
                    val row = repo.observeMessages(chat).first().find { it.text == marker && it.serverId != null }
                    if (row != null) return@withTimeout row
                    delay(200)
                }
                error("unreachable")
            }
            when (action) {
                "send", "receive" -> {
                    if (action == "send") {
                        repo.sendMessage(chat, marker).getOrThrow()
                        withTimeout(30_000) { (field("outbox") as com.messenger.app.data.message.MessageOutboxProcessor).flush() }
                    }
                    if (action == "receive") repo.syncLatest(chat).getOrThrow()
                    val row = capture()
                    output.put("clientId", row.clientMessageId).put("serverId", row.serverId).put("status", row.status.name)
                    saved.writeText(output.toString())
                }
                "delete" -> {
                    val id = JSONObject(saved.readText()).getString("clientId")
                    repo.deleteForEveryone(id).getOrThrow()
                    withTimeout(30_000) { (field("outbox") as com.messenger.app.data.message.MessageOutboxProcessor).flush() }
                    withTimeout(45_000) {
                        while (dao.findByClientMessageId(id)?.isDeletedForEveryone != true) delay(200)
                    }
                    output.put("deleted", true)
                }
                "verify", "faultWatch" -> {
                    val id = JSONObject(saved.readText()).getString("clientId")
                    val serverId = JSONObject(saved.readText()).getString("serverId")
                    if (action == "faultWatch") {
                        val f = pipeline.javaClass.getDeclaredField("decryptor").apply { isAccessible = true }
                        val original = f.get(pipeline) as MessageDecryptor
                        val blocked = object : MessageDecryptor by original {
                            override suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray {
                                if (userId == peer) throw IllegalStateException("QA transient decrypt failure")
                                return original.decrypt(userId, signalDeviceId, messageType, ciphertextBase64)
                            }
                        }
                        f.set(pipeline, blocked)
                        File(app.filesDir, "delete-qa-ready").writeText(marker)
                        try {
                            withTimeout(60_000) {
                                while (dao.pendingDecryptsForSender(peer).none { it.kind == "delete" && it.targetMessageId == serverId }) {
                                    repo.syncLatest(chat).getOrThrow()
                                    delay(200)
                                }
                            }
                            assertFalse(dao.findByClientMessageId(id)!!.isDeletedForEveryone)
                            output.put("retainedBeforeRecovery", true)
                        } finally { f.set(pipeline, original) }
                        // End the process with the authentic envelope retained.
                        // A separate verify invocation must recover it after restart.
                        output.put("pendingDelete", true).put("deleted", false)
                    } else {
                        (field("globalSyncManager") as GlobalSyncManager).runCatchUpSync()
                    }
                    if (action != "faultWatch") {
                        withTimeout(45_000) {
                            while (dao.findByClientMessageId(id)?.isDeletedForEveryone != true) delay(200)
                        }
                        assertTrue(dao.pendingDecryptsForSender(peer).none { it.kind == "delete" && it.targetMessageId == serverId })
                        assertTrue(repo.observeMessages(chat).first().none { it.clientMessageId == id })
                        output.put("deleted", true).put("pendingDelete", false).put("visible", false)
                    }
                }
                else -> error("Unknown action")
            }
        }
        File(app.filesDir, "delete-qa-result.json").writeText(output.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "QA_RESULT=$output\n") })
    }
}
