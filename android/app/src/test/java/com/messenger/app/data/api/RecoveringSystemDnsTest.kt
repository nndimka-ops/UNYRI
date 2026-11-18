package com.messenger.app.data.api

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Dns
import org.junit.After
import org.junit.Test
import kotlin.test.*

class RecoveringSystemDnsTest {
    private val executors = mutableListOf<ExecutorService>()
    private val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val original = UnknownHostException("synthetic negative cache")
    private fun pool(size: Int = 2): ExecutorService = Executors.newFixedThreadPool(size).also(executors::add)
    private fun dns(resolve: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    }
    private fun negative() = dns { throw original }
    private fun success(cancelled: AtomicInteger = AtomicInteger()) = object : SystemDnsRecoveryQuery {
        override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) {
            callbackExecutor.execute { answer(listOf(address)) }
        }
        override fun cancel() { cancelled.incrementAndGet() }
    }
    @After fun closePools() { executors.forEach { it.shutdownNow() } }

    @Test fun cachedNegativeCanRecoverWithoutCreatingAnAnswerCache() {
        val creates = AtomicInteger(); val cancelled = AtomicInteger()
        val dns = RecoveringSystemDns(negative(), { "vpn-default" }, { false }, { creates.incrementAndGet(); success(cancelled) }, pool())
        assertEquals(listOf(address), dns.lookup("server.invalid"))
        assertEquals(listOf(address), dns.lookup("server.invalid"))
        assertEquals(2, creates.get(), "A completed answer must not become a persistent app cache")
        assertEquals(2, cancelled.get())
        assertNull(original.cause, "Keep the original cached UnknownHost diagnostic intact")
    }

    @Test fun successfulSystemResolutionDoesNotUseRecovery() {
        val first = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        val expected = listOf(first, address)
        val dns = RecoveringSystemDns(dns { expected }, { throw AssertionError("No network probe for success") }, { false },
            { throw AssertionError("No fallback for success") }, pool())
        assertSame(expected, dns.lookup("server.invalid"))
    }

    @Test fun offlineAndLegacyKeepTheOriginalFailureWithoutStartingAQuery() {
        val offline = RecoveringSystemDns(negative(), { null }, { false }, { throw AssertionError("Offline") }, pool())
        assertSame(original, assertFailsWith<UnknownHostException> { offline.lookup("offline.invalid") })
        val legacy = RecoveringSystemDns(negative(), { "default" }, { false }, null, pool())
        assertSame(original, assertFailsWith<UnknownHostException> { legacy.lookup("legacy.invalid") })
    }

    @Test fun mainThreadNeverRunsEvenThePrimaryBlockingLookup() {
        val dns = RecoveringSystemDns(dns { throw AssertionError("Blocking SYSTEM on main") }, { "default" }, { true },
            { throw AssertionError("No recovery on main") }, pool())
        assertTrue(assertFailsWith<UnknownHostException> { dns.lookup("main.invalid") }.message.orEmpty().contains("main"))
    }

    @Test fun onlyUnknownHostCanTriggerRecovery() {
        val failure = IllegalStateException("synthetic different failure")
        val dns = RecoveringSystemDns(dns { throw failure }, { "default" }, { false },
            { throw AssertionError("Only UnknownHost") }, pool())
        assertSame(failure, assertFailsWith<IllegalStateException> { dns.lookup("server.invalid") })
    }

    @Test fun concurrentNegativeLookupsShareOneUnfinishedQuery() {
        val shared = CountDownLatch(1); val creates = AtomicInteger(); val cancelled = AtomicInteger()
        val factory = {
            creates.incrementAndGet()
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) {
                    callbackExecutor.execute { assertTrue(shared.await(2, TimeUnit.SECONDS)); answer(listOf(address)) }
                }
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }
        val dns = RecoveringSystemDns(negative(), { "vpn-default" }, { false }, factory, pool(), report = { if (it == "shared") shared.countDown() })
        val callers = pool()
        val first = callers.submit<List<InetAddress>> { dns.lookup("same.invalid") }
        val second = callers.submit<List<InetAddress>> { dns.lookup("same.invalid") }
        assertEquals(listOf(address), first.get(3, TimeUnit.SECONDS))
        assertEquals(listOf(address), second.get(3, TimeUnit.SECONDS))
        assertEquals(1, creates.get()); assertEquals(1, cancelled.get())
    }

    @Test fun atMostTwoDifferentHostsCanHaveAnActiveRecovery() {
        val started = CountDownLatch(2); val creates = AtomicInteger()
        val callbacks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, {
            creates.incrementAndGet()
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) {
                    callbacks += { callbackExecutor.execute { answer(listOf(address)) } }; started.countDown()
                }
                override fun cancel() = Unit
            }
        }, pool(), report = outcomes::add)
        val callers = pool()
        val first = callers.submit<List<InetAddress>> { dns.lookup("a.invalid") }
        val second = callers.submit<List<InetAddress>> { dns.lookup("b.invalid") }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertSame(original, assertFailsWith<UnknownHostException> { dns.lookup("c.invalid") })
        assertEquals(2, creates.get()); assertContains(outcomes, "saturated")
        callbacks.toList().forEach { it() }
        assertEquals(listOf(address), first.get(3, TimeUnit.SECONDS))
        assertEquals(listOf(address), second.get(3, TimeUnit.SECONDS))
    }

    @Test fun timeoutCancelsAndReleasesItsSlotAndIgnoresLateAnswer() {
        val cancelled = AtomicInteger(); val creates = AtomicInteger()
        val late = AtomicReference<((List<InetAddress>?) -> Unit)?>()
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, {
            if (creates.incrementAndGet() > 1) success() else object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) { late.set(answer) }
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }, pool(), timeoutMillis = 80)
        val before = System.nanoTime()
        assertSame(original, assertFailsWith<UnknownHostException> { dns.lookup("timeout.invalid") })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_500)
        assertEquals(1, cancelled.get())
        late.get()?.invoke(listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 3))))
        assertEquals(listOf(address), dns.lookup("timeout.invalid"))
        assertEquals(2, creates.get())
    }

    @Test fun changingDefaultNetworkDiscardsTheOldNetworkAnswer() {
        val network = AtomicReference<Any>("vpn-default")
        val started = CountDownLatch(1)
        val answer = AtomicReference<((List<InetAddress>?) -> Unit)?>()
        val dns = RecoveringSystemDns(negative(), network::get, { false }, {
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, reply: (List<InetAddress>?) -> Unit) { answer.set(reply); started.countDown() }
                override fun cancel() = Unit
            }
        }, pool())
        val result = pool(1).submit<Boolean> {
            assertSame(original, assertFailsWith<UnknownHostException> { dns.lookup("network.invalid") }); true
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        network.set("cellular-default")
        answer.get()!!.invoke(listOf(address))
        assertTrue(result.get(3, TimeUnit.SECONDS))
    }

    @Test fun cancellingOneSharedWaiterDoesNotCancelTheIncomingCallsResolution() {
        val started = CountDownLatch(1); val shared = CountDownLatch(1)
        val callback = AtomicReference<((List<InetAddress>?) -> Unit)?>()
        val cancelled = AtomicInteger(); val firstFailure = AtomicReference<Throwable?>(); val interrupted = AtomicReference(false)
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, {
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) { callback.set(answer); started.countDown() }
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }, pool(), report = { if (it == "shared") shared.countDown() })
        val first = Thread {
            try { dns.lookup("shared.invalid") } catch (error: Throwable) { firstFailure.set(error); interrupted.set(Thread.currentThread().isInterrupted) }
        }
        first.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val incoming = pool(1).submit<List<InetAddress>> { dns.lookup("shared.invalid") }
            assertTrue(shared.await(2, TimeUnit.SECONDS))
            first.interrupt(); first.join(1_000)
            assertSame(original, firstFailure.get()); assertTrue(interrupted.get())
            assertEquals(0, cancelled.get(), "The remaining incoming-call waiter still owns this query")
            callback.get()!!.invoke(listOf(address))
            assertEquals(listOf(address), incoming.get(2, TimeUnit.SECONDS))
            assertEquals(1, cancelled.get())
        } finally { first.interrupt(); first.join(2_500) }
    }

    @Test fun executorRejectionFailsClosedAndCancelsWithoutRunningInline() {
        val cancelled = AtomicInteger()
        val rejected = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { throw RejectedExecutionException("synthetic full pool") }
            override fun shutdown() = Unit
            override fun shutdownNow() = mutableListOf<Runnable>()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, { success(cancelled) }, rejected)
        repeat(3) { assertSame(original, assertFailsWith<UnknownHostException> { dns.lookup("rejected.invalid") }) }
        assertEquals(3, cancelled.get(), "Rejected submissions must not consume recovery slots")
    }

    @Test fun anEmptySystemReplyKeepsTheOriginalUnknownHost() {
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, {
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) { callbackExecutor.execute { answer(emptyList()) } }
                override fun cancel() = Unit
            }
        }, pool())
        assertSame(original, assertFailsWith<UnknownHostException> { dns.lookup("empty.invalid") })
    }

    @Test fun androidAdapterUsesDefaultNetworkAndUnmodifiedOsCacheFlags() {
        var invoked = false; var returned: List<InetAddress>? = null
        val query = AndroidResolverQuery(AndroidDnsQueryRunner { network, hostname, flags, executor, _, callback ->
            assertNull(network, "Must use per-UID default/VPN, never bind Wi-Fi/cellular directly")
            assertEquals(0, flags, "FLAG_EMPTY preserves Android's native DNS policy/cache")
            assertEquals("vpn.invalid", hostname)
            invoked = true
            executor.execute { callback.onAnswer(listOf(address), 0) }
        })
        query.start("vpn.invalid", Executor { it.run() }) { returned = it }
        assertTrue(invoked); assertEquals(listOf(address), returned)
        query.cancel()
    }

    @Test fun combinedAddressFamilyCallbacksAreSerializedPerQuery() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val finished = AtomicInteger()
        val dns = RecoveringSystemDns(negative(), { "default" }, { false }, {
            object : SystemDnsRecoveryQuery {
                override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) {
                    repeat(2) {
                        callbackExecutor.execute {
                            val running = active.incrementAndGet()
                            maximum.accumulateAndGet(running, ::maxOf)
                            try { Thread.sleep(40) } finally { active.decrementAndGet() }
                            if (finished.incrementAndGet() == 2) answer(listOf(address))
                        }
                    }
                }
                override fun cancel() = Unit
            }
        }, pool(4))
        assertEquals(listOf(address), dns.lookup("families.invalid"))
        assertEquals(1, maximum.get(), "The system resolver shares state between A and AAAA commands")
    }

    @Test fun asyncOkHttpCanReachTheOriginalHostnameAfterACachedNegative() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("recovered"))
            val testMain = Thread.currentThread()
            val dns = RecoveringSystemDns(negative(), { "default-vpn" }, { Thread.currentThread() === testMain }, { success() }, pool())
            val client = OkHttpClient.Builder().dns(dns).callTimeout(3, TimeUnit.SECONDS).build()
            val url = server.url("/calls/fake-id").newBuilder().host("server.invalid").build()
            val complete = CountDownLatch(1); val body = AtomicReference<String?>(); val failure = AtomicReference<Throwable?>()
            client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { failure.set(e); complete.countDown() }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.use { body.set(it.body?.string()) }; complete.countDown() }
            })
            assertTrue(complete.await(4, TimeUnit.SECONDS)); assertNull(failure.get()); assertEquals("recovered", body.get())
            assertEquals("server.invalid:${server.port}", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Host"))
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
