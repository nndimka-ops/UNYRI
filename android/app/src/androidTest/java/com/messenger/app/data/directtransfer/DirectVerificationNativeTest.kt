package com.messenger.app.data.directtransfer

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Slow-verifier cancellation over actual native RTC; no server/Signal/UI claim. */
class DirectVerificationNativeTest {
    @Test fun cancellationInterruptsSlowVerifierAndKeepsOnlyUnverifiedPartial() = runBlocking(Dispatchers.IO) { coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.messenger.app.directqa", context.packageName)
        val id = UUID.randomUUID().toString()
        val alive = AtomicBoolean(true)
        val current = { check(alive.get()) { "Owned QA cancellation" }; Unit }
        val senderStorage = DirectFileStorage(context, "verify-$id-s", current)
        val receiverStorage = DirectFileStorage(context, "verify-$id-r", current)
        val input = File(context.cacheDir, "verify-$id.bin")
        input.outputStream().use { stream -> repeat(16) { stream.write(ByteArray(64 * 1024) { it.toByte() }) } }
        val staged = senderStorage.stage(id, Uri.fromFile(input))
        val offer = DirectFileOffer(transferId = id, offerClientMessageId = UUID.randomUUID().toString(), senderUserId = UUID.randomUUID().toString(),
            senderDeviceId = UUID.randomUUID().toString(), recipientUserId = UUID.randomUUID().toString(), offerNonce = DirectOfferNonce.create(),
            filename = staged.filename, mimeType = staged.mimeType, size = staged.size, sha256 = staged.sha256, expiresAt = System.currentTimeMillis() / 1000 + 3600)
        val record = DirectTransferRecord(offer, UUID.randomUUID().toString(), "sender", consent = true, attemptId = UUID.randomUUID().toString())
        val a = WebRtcDirectPeerTransport(context, DirectPeerIceConfig(emptyList(), relayOnly = false))
        val b = WebRtcDirectPeerTransport(context, DirectPeerIceConfig(emptyList(), relayOnly = false))
        val entered = CompletableDeferred<Unit>()
        val receiverZero = CompletableDeferred<DirectAckStats>()
        val senderZero = CompletableDeferred<DirectAckStats>()
        val completed = AtomicBoolean(false)
        try {
            val sender = async { runCatching { DirectFilePump(record, senderStorage, a, current, { offset, stats ->
                if (offset == offer.size && entered.isCompleted && stats?.speedBytesPerSecond == 0L) senderZero.complete(stats)
            }, { completed.set(true) }).run() } }
            val receiver = async { runCatching { DirectFilePump(record.copy(role = "receiver"), receiverStorage, b, current, { offset, stats ->
                if (offset == offer.size && entered.isCompleted && stats?.speedBytesPerSecond == 0L) receiverZero.complete(stats)
            }, { completed.set(true) }, verifyFile = { file, check ->
                entered.complete(Unit)
                repeat(100) { delay(100); check() }
                receiverStorage.hash(file, checkCurrent = check)
            }).run() } }
            val offerSdp = a.createOffer(); val answer = b.acceptOffer(offerSdp); a.applyAnswer(answer)
            withTimeout(30_000) { entered.await() }
            val same = withTimeout(5000) { receiverZero.await() to senderZero.await() }
            assertEquals(same.first, same.second)
            alive.set(false); a.close(); b.close()
            withTimeout(5000) { sender.await(); receiver.await() }
            assertFalse(completed.get())
            assertFalse(receiverStorage.file(id, "complete").exists())
            assertEquals(offer.size, receiverStorage.file(id, "partial").length())
        } finally {
            alive.set(false); a.close(); b.close(); input.delete()
            senderStorage.deleteIncomplete(id); receiverStorage.deleteIncomplete(id)
        }
    } }
}
