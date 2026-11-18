package com.messenger.app.data.websocket

import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.SocketConnectionState
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class MessagingSocketClientTest {
    @Test fun `new account reconnects even if logged out notification was skipped`() = runBlocking {
        withTimeout(5000) {
            val epoch = AtomicInteger(1)
            val firstEntered = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val auth = object : AuthRepository by FakeAuthRepository() {
                override fun currentSessionEpoch() = epoch.get()
                override suspend fun currentAccessToken(): String? {
                    if (epoch.get() == 1) {
                        firstEntered.complete(Unit)
                        try { kotlinx.coroutines.awaitCancellation() }
                        finally { firstCancelled.complete(Unit) }
                    }
                    secondEntered.complete(Unit)
                    return null
                }
            }
            val client = MessagingSocketClient(OkHttpClient(), "ws://localhost/ws", auth, Json)
            try {
                client.start()
                firstEntered.await()
                epoch.set(3) // Logout/login can be conflated by StateFlow.
                client.start()
                secondEntered.await()
                firstCancelled.await()
                assertEquals(false, client.sendRaw("private message"))
            } finally { client.stop() }
        }
    }

    @Test fun `token lookup failure retries and repeated start does not duplicate connection`() = runBlocking {
        withTimeout(5000) {
            val attempts = AtomicInteger()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val retried = CompletableDeferred<Unit>()
            val auth = object : AuthRepository by FakeAuthRepository() {
                override suspend fun currentAccessToken(): String? {
                    if (attempts.incrementAndGet() == 1) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        throw IOException("temporary token store failure")
                    }
                    retried.complete(Unit)
                    return null
                }
            }
            val client = MessagingSocketClient(OkHttpClient(), "ws://localhost/ws", auth, Json)
            try {
                client.start(); firstEntered.await(); client.start()
                assertEquals(1, attempts.get())
                releaseFirst.complete(Unit); retried.await()
                assertEquals(2, attempts.get())
            } finally { client.stop() }
            assertEquals(SocketConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }
}
