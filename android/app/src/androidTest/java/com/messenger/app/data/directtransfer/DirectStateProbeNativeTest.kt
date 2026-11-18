package com.messenger.app.data.directtransfer

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.chat.MessengerDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Emits only protocol state/counters, never an offer nonce, ciphertext, key or credential. */
class DirectStateProbeNativeTest {
    @Test fun safeOwnedState(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.messenger.app.directqa")
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("run_id")).also { require(canonicalUuid(it)) }
        val role = requireNotNull(args.getString("role")).also { require(it in setOf("a", "b", "x")) }
        val name = "direct-service-$run-$role.db"
        require(context.getDatabasePath(name).isFile)
        val database = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
        try {
            val states = JSONArray()
            for (value in database.workspaceDao().allDirectTransfers()) {
                val record = DirectTransferCoordinator.decodeRecord(value.value) ?: continue
                states.put(JSONObject().put("id", record.offer.transferId).put("role", record.role).put("state", record.state)
                    .put("offset", record.durableOffset).put("size", record.offer.size).put("attempt", record.attemptId)
                    .put("request", record.requestId).put("cursor", record.cursor).put("completed", record.completed)
                    .put("completionReceived", record.completionReceived).put("completionAcknowledged", record.completionAcknowledged)
                    .put("mailboxClosed", record.mailboxClosed).put("closePending", record.closePending)
                    .put("reason", record.reason).put("pendingTypes", JSONArray(record.pending.map { it.control.type }))
                    .put("probeCount", record.completionRecoveryCount).put("probeAt", record.completionRecoveryAt)
                    .put("connectionFailures", record.connectionFailures).put("requestRetryCount", record.requestRetryCount)
                    .put("nextAttemptAt", record.nextAttemptAt).put("pausedForLocalCall", record.pausedForLocalCall)
                    .put("processing_type", record.processingSignal?.messageType ?: 0))
            }
            val safe = JSONObject().put("run", run).put("role", role).put("states", states)
            File(context.filesDir, "direct-safe-$run-$role.json").writeText(safe.toString())
            android.util.Log.i("DirectNativeQA", "safe-state=$safe")
        } finally { database.close() }
    }
}
