package com.messenger.app.data.directtransfer

import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

/** One authenticated attempt, one serialized file writer and a <=256KiB application send window. */
internal class DirectFilePump(
    private val record: DirectTransferRecord,
    private val storage: DirectFileStorage,
    private val transport: DirectPeerTransport,
    private val ensureCurrent: () -> Unit,
    private val persistDurableOffset: suspend (Long, DirectAckStats?) -> Unit,
    private val completed: suspend () -> Unit,
    private val verifyFile: suspend (java.io.File, () -> Unit) -> String = { file, check -> storage.hash(file, checkCurrent = check) },
) {
    private val sender = record.role == "sender"
    private val sent = AtomicLong(record.durableOffset)
    private val acknowledged = MutableStateFlow(record.durableOffset)
    private val finishSent = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val verifying = AtomicBoolean(false)
    private var verifier: Job? = null
    private var runningScope: CoroutineScope? = null
    private val receiveLock = Mutex()
    private var partial: RandomAccessFile? = null
    private var received = record.durableOffset
    private var durable = record.durableOffset
    private val expectedHash = DirectFileStorage.rawHash(record.offer.sha256)
    private var rate: DirectRateEstimator? = null
    private val ackSequence = DirectAckSequence(record.durableOffset)

    suspend fun run() = coroutineScope {
        runningScope = this
        val ready = CompletableDeferred<Unit>()
        var outgoing: Job? = null
        val readyTimeout = launch { if (withTimeoutOrNull(90_000) { ready.await(); true } != true) throw DirectStorageError("direct_unavailable") }
        val syncTimer = if (!sender) launch {
            while (true) {
                delay(1000)
                receiveLock.withLock {
                    if (verifying.get() && !finished.get()) {
                        val stats = rateEstimator().verification(durable, System.nanoTime() / 1_000_000)
                        ensureCurrent(); persistDurableOffset(durable, stats)
                        transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.ACK, durable, stats.encode()), record.offer.size))
                    } else if (partial != null && received > durable) commitReceiverPrefix()
                    else if (partial != null && !finished.get()) rate?.idle(durable, System.nanoTime() / 1_000_000)?.let { stats ->
                        ensureCurrent(); persistDurableOffset(durable, stats)
                        transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.ACK, durable, stats.encode()), record.offer.size))
                    }
                }
            }
        } else null
        try {
            transport.events.collect { event ->
                currentCoroutineContext().ensureActive(); ensureCurrent()
                when (event) {
                    DirectPeerEvent.Ready -> {
                        ready.complete(Unit)
                        if (sender && outgoing == null) outgoing = launch {
                            try { sendOriginal() } catch (_: TimeoutCancellationException) { throw DirectStorageError("connection_lost") }
                        }
                        if (!sender) receiveLock.withLock { ensurePartial(); rateEstimator() }
                    }
                    is DirectPeerEvent.Frame -> {
                        require(event.binary) { "Unexpected transfer text frame" }
                        val frame = DirectFileWire.decode(event.bytes, record.offer.size, event.binary)
                        if (sender) receiveSenderControl(frame)
                        else receiveLock.withLock { receiveOriginal(frame) }
                    }
                    is DirectPeerEvent.Closed -> {
                        if (!finished.get()) throw DirectStorageError(if (event.reason == DirectPeerFailure.DIRECT_PATH_UNAVAILABLE) "direct_unavailable" else "connection_lost")
                    }
                }
            }
            if (!finished.get()) throw DirectStorageError("connection_lost")
        } finally {
            readyTimeout.cancel(); syncTimer?.cancel(); outgoing?.cancel(); verifier?.cancel(); runningScope = null
            receiveLock.withLock { partial?.close(); partial = null }
        }
    }

    private suspend fun sendOriginal() {
        val original = storage.file(record.offer.transferId, "source")
        if (original.length() != record.offer.size) throw DirectStorageError("source_missing")
        FileInputStream(original).use { input ->
            input.channel.position(record.durableOffset)
            var offset = record.durableOffset
            val buffer = ByteArray(DirectFileWire.CHUNK_BYTES)
            while (offset < record.offer.size) {
                currentCoroutineContext().ensureActive(); ensureCurrent()
                withTimeout(30_000) {
                    acknowledged.first { durable -> offset - durable < 256 * 1024 }
                }
                val requested = minOf(buffer.size.toLong(), record.offer.size - offset).toInt()
                var count = 0
                while (count < requested) {
                    val read = input.read(buffer, count, requested - count)
                    if (read <= 0) throw DirectStorageError("source_changed")
                    count += read
                }
                ensureCurrent()
                val frame = DirectFileWire.encode(DirectFileFrame(DirectFileWire.CHUNK, offset, buffer.copyOf(count)), record.offer.size)
                // ACK can arrive immediately from another native thread after send returns.
                sent.set(offset + count)
                transport.send(frame)
                offset += count
            }
        }
        ensureCurrent()
        finishSent.set(true)
        transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.FINISH, record.offer.size, expectedHash), record.offer.size))
        // Compute once. ACK heartbeats never renew this deadline.
        withTimeout(DirectTransferTiming.verificationMillis(record.offer.size)) { while (!finished.get()) { ensureCurrent(); delay(50) } }
    }

    private suspend fun receiveSenderControl(frame: DirectFileFrame) {
        when (frame.type) {
            DirectFileWire.ACK -> {
                val stats = frame.payload.takeIf { it.isNotEmpty() }?.let(DirectAckStats::decode)
                ackSequence.accept(frame.offset, sent.get(), stats)
                if (frame.offset > acknowledged.value || stats != null) {
                    persistDurableOffset(frame.offset, stats)
                    acknowledged.value = frame.offset
                }
            }
            DirectFileWire.COMPLETE_ACK -> {
                require(finishSent.get() && frame.offset == record.offer.size && MessageDigest.isEqual(frame.payload, expectedHash)) { "Invalid final acknowledgment" }
                if (finished.compareAndSet(false, true)) completed()
                transport.close()
            }
            else -> error("Unexpected sender transfer frame")
        }
    }

    private fun ensurePartial() {
        if (partial == null && !finished.get()) partial = storage.openPartial(record.offer.transferId, record.durableOffset, record.offer.size)
    }

    private suspend fun receiveOriginal(frame: DirectFileFrame) {
        when (frame.type) {
            DirectFileWire.CHUNK -> {
                require(!finished.get() && !verifying.get() && frame.offset == received) { "Unexpected transfer chunk offset" }
                ensurePartial(); ensureCurrent()
                partial!!.write(frame.payload)
                received += frame.payload.size
                if (received - durable >= 256 * 1024 || received == record.offer.size) commitReceiverPrefix()
            }
            DirectFileWire.FINISH -> {
                require(frame.offset == record.offer.size && MessageDigest.isEqual(frame.payload, expectedHash)) { "Invalid finish digest" }
                if (!finished.get() && verifying.compareAndSet(false, true)) {
                    ensurePartial()
                    require(received == record.offer.size) { "Transfer file is incomplete" }
                    commitReceiverPrefix()
                    partial!!.close(); partial = null
                    // Hash outside the writer lock: a bounded zero-rate ACK can keep the
                    // peer informed, and cancellation/call ownership is checked per64KiB.
                    verifier = checkNotNull(runningScope).launch {
                        val hash = verifyFile(storage.file(record.offer.transferId, "partial"), ensureCurrent)
                        if (hash != record.offer.sha256) throw DirectStorageError("integrity_failed")
                        currentCoroutineContext().ensureActive(); ensureCurrent()
                        receiveLock.withLock {
                            currentCoroutineContext().ensureActive(); ensureCurrent()
                            storage.promote(record.offer.transferId)
                            finished.set(true)
                            completed()
                            ensureCurrent()
                            transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.COMPLETE_ACK, record.offer.size, expectedHash), record.offer.size))
                        }
                    }
                    return
                }
                if (finished.get()) {
                    ensureCurrent()
                    transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.COMPLETE_ACK, record.offer.size, expectedHash), record.offer.size))
                }
            }
            else -> error("Unexpected receiver transfer frame")
        }
    }

    private suspend fun commitReceiverPrefix() {
        if (received == durable) return
        ensureCurrent()
        partial!!.fd.sync()
        val stats = rateEstimator().observe(received, System.nanoTime() / 1_000_000)
        persistDurableOffset(received, stats)
        durable = received
        transport.send(DirectFileWire.encode(DirectFileFrame(DirectFileWire.ACK, durable, stats.encode()), record.offer.size))
    }

    private fun rateEstimator(): DirectRateEstimator = rate ?: DirectRateEstimator(durable, System.nanoTime() / 1_000_000).also { rate = it }
}
