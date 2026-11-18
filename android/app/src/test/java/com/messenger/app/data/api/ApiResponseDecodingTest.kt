package com.messenger.app.data.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.test.*

/** A serializer held at a controlled barrier models a large history response.
 * It proves the actual decoding work cannot occupy the caller's UI executor. */
class ApiResponseDecodingTest {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
    }

    @Serializable(with = ValueSerializer::class)
    data class Value(val text: String)

    object ValueSerializer : KSerializer<Value> {
        override val descriptor = PrimitiveSerialDescriptor("BlockingApiValue", PrimitiveKind.STRING)
        private var gate: Gate? = null
        override fun serialize(encoder: Encoder, value: Value) = encoder.encodeString(value.text)
        override fun deserialize(decoder: Decoder): Value {
            gate?.let {
                it.thread.set(Thread.currentThread())
                it.entered.countDown()
                check(it.release.await(5, TimeUnit.SECONDS)) { "Decoder barrier was not released" }
            }
            return Value(decoder.decodeString())
        }
        internal fun configure(value: Any?) { gate = value as Gate? }
    }

    @Test fun `history decoding leaves UI free until decoded response is ready`() = runBlocking<Unit> {
        val gate = Gate()
        val uiThread = AtomicReference<Thread>()
        val ui = Executors.newSingleThreadExecutor { Thread(it, "coco-decode-test-ui").also(uiThread::set) }.asCoroutineDispatcher()
        ValueSerializer.configure(gate)
        try {
            val response = async(ui) { Json.decodeApiResponse<Value>("\"history decoded\"") }
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS), "Actual serializer must run")
            assertNotSame(uiThread.get(), gate.thread.get(), "Response parsing ran on the UI thread")
            assertEquals("UI responsive", withTimeout(1000) { withContext(ui) { "UI responsive" } })
            gate.release.countDown()
            assertEquals(Value("history decoded"), response.await())
        } finally {
            gate.release.countDown()
            ValueSerializer.configure(null)
            ui.close()
        }
    }

    @Test fun `cancelled caller cannot publish a decoded history result`() = runBlocking<Unit> {
        val gate = Gate()
        ValueSerializer.configure(gate)
        try {
            val response = async(start = CoroutineStart.UNDISPATCHED) { Json.decodeApiResponse<Value>("\"stale history\"") }
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
            response.cancel()
            gate.release.countDown()
            assertFailsWith<CancellationException> { response.await() }
        } finally {
            gate.release.countDown()
            ValueSerializer.configure(null)
        }
    }

    @Test fun `malformed history response retains serializer failure`() = runBlocking<Unit> {
        assertFailsWith<SerializationException> { Json.decodeApiResponse<List<String>>("not json") }
    }
}
