package com.messenger.app.ui.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADMIN PRODUCT PACK 2.1 - cursor pagination contract (pure, no Android).
class AdminPagingTest {

    private data class Row(val id: String)

    private val idOf: (Row) -> String? = { it.id }

    @Test
    fun `the request path always bounds the page size`() {
        assertEquals("reports?limit=25", AdminPaging.path("reports"))
        assertEquals("reports?limit=25&cursor=abc", AdminPaging.path("reports", cursor = "abc"))
        assertEquals(
            "reports?limit=25&status=open",
            AdminPaging.path("reports", AdminPaging.query("status" to "open")),
        )
    }

    @Test
    fun `filters are encoded and blank values are omitted entirely`() {
        val query = AdminPaging.query(
            "status" to "in_progress",
            "query" to "  anna  ",
            "actor" to "",
            "target" to null,
        )
        assertEquals("status=in_progress&query=anna", query)
        assertTrue(query.contains("in_progress"))
        // A value that looks like SQL is percent-encoded, never interpolated.
        val injected = AdminPaging.query("query" to "' OR 1=1--")
        assertFalse(injected.contains("'"))
        assertTrue(injected.startsWith("query="))
    }

    @Test
    fun `first page and next page append without duplicates`() {
        val first = AdminPage(listOf(Row("a"), Row("b")), nextCursor = "c1", hasMore = true)
        val second = AdminPage(listOf(Row("c"), Row("d")), nextCursor = null, hasMore = false)

        val afterFirst = first
        val afterSecond = AdminPaging.append(afterFirst, second, idOf)

        assertEquals(listOf("a", "b", "c", "d"), afterSecond.rows.map { it.id })
        assertFalse(afterSecond.hasMore)
        assertEquals(null, afterSecond.nextCursor)
    }

    @Test
    fun `a row that reappears across a page boundary is kept once`() {
        val first = AdminPage(listOf(Row("a"), Row("b")), nextCursor = "c1", hasMore = true)
        // A boundary overlap: the server repeats "b" as the first row of page 2.
        val second = AdminPage(listOf(Row("b"), Row("c")), nextCursor = "c2", hasMore = true)

        val merged = AdminPaging.append(first, second, idOf)

        assertEquals(listOf("a", "b", "c"), merged.rows.map { it.id })
        assertEquals("c2", merged.nextCursor)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `a row without an identity is never dropped`() {
        data class Anonymous(val label: String)
        val merged = AdminPaging.merge(
            listOf(Anonymous("x")),
            listOf(Anonymous("y"), Anonymous("y")),
        ) { null }
        assertEquals(3, merged.size)
    }

    @Test
    fun `a stale response from a previous filter or panel is rejected`() {
        // Same generation and filter: current.
        assertTrue(AdminPaging.isCurrent(4, 4, "status=open", "status=open"))
        // The filter changed while the request was in flight.
        assertFalse(AdminPaging.isCurrent(4, 4, "status=open", "status=resolved"))
        // The panel moved on (lock, account switch, newer request).
        assertFalse(AdminPaging.isCurrent(3, 4, "status=open", "status=open"))
    }
}
