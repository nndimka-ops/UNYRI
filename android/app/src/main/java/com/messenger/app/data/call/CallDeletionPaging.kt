package com.messenger.app.data.call

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Demand-driven admission only: a late HTTP response is rejected before storage. */
internal suspend fun syncCallDeletionPages(
    initialCursor: Long,
    fetch: suspend (Long) -> CallDeletionPage,
    persist: suspend (CallDeletionPage, Long) -> Unit,
    requireSession: () -> Unit = {},
    nanoTime: () -> Long = System::nanoTime,
) {
    val started = nanoTime()
    var cursor = initialCursor
    var pages = 0
    var records = 0
    suspend fun checkWork() {
        currentCoroutineContext().ensureActive()
        requireSession()
        val elapsed = nanoTime() - started
        check(elapsed >= 0 && elapsed < 10_000_000_000L && pages < 20 && records < 4000) {
            "Call deletion refresh budget exhausted"
        }
    }
    while (true) {
        checkWork()
        val page = fetch(cursor)
        checkWork()
        check(cursor >= 0 && page.items.size <= 200) { "Invalid call deletion page" }
        var next = cursor
        for (item in page.items) {
            check(item.sequence > next) { "Non-progressing call deletion page" }
            next = item.sequence
        }
        check(!page.hasMore || page.items.isNotEmpty()) { "Empty call deletion page with more" }
        check(page.items.size <= 4000 - records) { "Call deletion record budget exhausted" }
        if (page.items.isEmpty()) return
        // The durable page contains its cursor; never publish progress before this succeeds.
        persist(page, next)
        currentCoroutineContext().ensureActive()
        requireSession()
        cursor = next
        pages++
        records += page.items.size
        if (!page.hasMore) return
    }
}
