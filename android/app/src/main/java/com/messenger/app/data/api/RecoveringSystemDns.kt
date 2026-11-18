package com.messenger.app.data.api

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns

/** A system resolver operation, never an alternate DNS provider or a socket route. */
internal interface SystemDnsRecoveryQuery {
    fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit)
    fun cancel()
}

/**
 * Normal lookups keep OkHttp's Dns.SYSTEM behavior. Android can cache a transient
 * UID-blocked lookup as UnknownHost even after FCM grants network access. Only
 * that failure gets one bounded query through Android's own resolver.
 *
 * No answer/TTL cache: the map contains only shared, unfinished operations. All
 * clones of the shared HTTP client use the same two-operation budget. Callback
 * work stays off main; only an OkHttp caller waits for the result.
 */
internal class RecoveringSystemDns(
    private val primary: Dns,
    private val networkIdentity: () -> Any?,
    private val isMainThread: () -> Boolean,
    private val queryFactory: (() -> SystemDnsRecoveryQuery)?,
    private val executor: ExecutorService = newExecutor(),
    private val timeoutMillis: Long = 2_000,
    private val report: (String) -> Unit = {},
) : Dns {
    init { require(timeoutMillis in 1L..2_000L) }
    private data class Key(val hostname: String, val network: Any)
    private val lock = Any()
    private val pending = mutableMapOf<Key, Attempt>()
    private val permits = Semaphore(2)

    private inner class Attempt(val key: Key, val query: SystemDnsRecoveryQuery) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val done = CountDownLatch(1)
        val waiters = AtomicInteger(1)
        val completed = AtomicBoolean()
        val cleaned = AtomicBoolean()
        @Volatile var addresses: List<InetAddress>? = null
        @Volatile var starter: FutureTask<Unit>? = null
        fun complete(value: List<InetAddress>?, outcome: String) {
            if (!completed.compareAndSet(false, true)) return
            addresses = value?.takeIf { it.isNotEmpty() }?.toList()
            synchronized(lock) { if (pending[key] === this) pending.remove(key) }
            done.countDown()
            diagnostic(outcome)
        }
        fun cleanup() {
            if (!cleaned.compareAndSet(false, true)) return
            // Always called on a lookup worker, never a system main-loop callback.
            try {
                starter?.cancel(true)
                (executor as? ThreadPoolExecutor)?.let { pool -> starter?.let(pool::remove) }
                runCatching { query.cancel() }
            } finally {
                synchronized(lock) { if (pending[key] === this) pending.remove(key) }
                permits.release()
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isMainThread()) {
            diagnostic("main_guard")
            throw UnknownHostException("DNS lookup requested on the main thread")
        }
        val original = try { return primary.lookup(hostname) } catch (error: UnknownHostException) { error }
        val network = runCatching(networkIdentity).getOrNull()
        val factory = queryFactory
        if (network == null || factory == null) { diagnostic("unavailable"); throw original }
        val key = Key(hostname, network)
        var created = false
        val attempt = synchronized(lock) {
            pending[key]?.also { it.waiters.incrementAndGet(); diagnostic("shared") } ?: run {
                if (!permits.tryAcquire()) { diagnostic("saturated"); throw original }
                val query = try { factory() } catch (_: Exception) { permits.release(); throw original }
                Attempt(key, query).also { pending[key] = it; created = true }
            }
        }
        try {
            if (created) start(attempt)
            val remaining = attempt.deadline - System.nanoTime()
            if (remaining <= 0 || !attempt.done.await(remaining, TimeUnit.NANOSECONDS)) {
                attempt.complete(null, "timeout")
                attempt.cleanup()
                throw original
            }
            if (runCatching(networkIdentity).getOrNull() != network) {
                diagnostic("network_changed")
                throw original
            }
            return attempt.addresses ?: throw original
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw original
        } finally {
            if (attempt.waiters.decrementAndGet() == 0) {
                attempt.complete(null, "interrupted")
                attempt.cleanup()
            }
        }
    }

    private fun start(attempt: Attempt) {
        // DnsResolver FD readiness may be signalled by the main looper. Never
        // use CallerRunsPolicy/direct execution for its native parsing callback.
        // Android's combined A/AAAA resolver accumulates both answers in one
        // callback object. Its parsing commands must not run concurrently.
        val callbackLock = Any()
        val callbackExecutor = Executor { command ->
            try { executor.execute { synchronized(callbackLock) { command.run() } } }
            catch (_: RejectedExecutionException) { attempt.complete(null, "executor_rejected") }
        }
        val task = FutureTask<Unit> {
            if (!attempt.completed.get() && System.nanoTime() < attempt.deadline) {
                try {
                    attempt.query.start(attempt.key.hostname, callbackExecutor) { answers ->
                        attempt.complete(answers, if (answers.isNullOrEmpty()) "failed" else "recovered")
                    }
                } catch (_: Exception) { attempt.complete(null, "failed") }
            } else attempt.complete(null, "timeout")
        }
        attempt.starter = task
        try { executor.execute(task) }
        catch (_: RejectedExecutionException) { attempt.complete(null, "executor_rejected") }
    }

    private fun diagnostic(outcome: String) { runCatching { report(outcome) } }

    companion object {
        private fun newExecutor(): ExecutorService = ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            ThreadFactory { task -> Thread(task, "coco-dns-recovery").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
