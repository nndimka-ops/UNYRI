package com.messenger.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootNavigationTest {
    @Test fun `hidden destinations always have a valid fallback and preserve settings`() {
        for (calls in listOf(false, true)) for (search in listOf(false, true)) {
            val visible = RootNavigation.visibleDestinations(calls, search)
            assertEquals(listOf(RootDestination.CHATS, RootDestination.SETTINGS), visible.filter { it == RootDestination.CHATS || it == RootDestination.SETTINGS })
            assertEquals(search, RootDestination.SEARCH in visible)
            assertEquals(calls, RootDestination.CALLS in visible)
            assertTrue(RootDestination.ROOMS in visible)
            assertTrue(RootDestination.TRANSFERS in visible)
            assertEquals(RootDestination.TRANSFERS, RootNavigation.fallbackSelected(RootDestination.TRANSFERS, calls, search))
            assertEquals(RootDestination.ROOMS, RootNavigation.fallbackSelected(RootDestination.ROOMS, calls, search))
            for (selected in RootDestination.entries) {
                val next = RootNavigation.fallbackSelected(selected, calls, search)
                assertTrue(next in visible)
                assertEquals(if (selected in visible) selected else RootDestination.CHATS, next)
            }
        }
    }

    @Test
    fun `show calls defaults to true`() {
        assertTrue(RootNavigation.DEFAULT_SHOW_CALLS)
    }

    @Test
    fun `visible destinations with calls on`() {
        assertEquals(
            listOf(RootDestination.CHATS, RootDestination.CALLS, RootDestination.ROOMS, RootDestination.TRANSFERS, RootDestination.SEARCH, RootDestination.SETTINGS),
            RootNavigation.visibleDestinations(showCalls = true),
        )
    }

    @Test
    fun `visible destinations with calls off`() {
        assertEquals(
            listOf(RootDestination.CHATS, RootDestination.ROOMS, RootDestination.TRANSFERS, RootDestination.SEARCH, RootDestination.SETTINGS),
            RootNavigation.visibleDestinations(showCalls = false),
        )
    }

    @Test
    fun `selected calls falls back to chats when hidden`() {
        assertEquals(RootDestination.CHATS, RootNavigation.fallbackSelected(RootDestination.CALLS, showCalls = false))
    }

    @Test
    fun `other selected destinations are preserved when calls hidden`() {
        assertEquals(RootDestination.SEARCH, RootNavigation.fallbackSelected(RootDestination.SEARCH, showCalls = false))
        assertEquals(RootDestination.SETTINGS, RootNavigation.fallbackSelected(RootDestination.SETTINGS, showCalls = false))
        assertEquals(RootDestination.CHATS, RootNavigation.fallbackSelected(RootDestination.CHATS, showCalls = false))
    }

    @Test
    fun `root order is stable`() {
        assertEquals(
            listOf(RootDestination.CHATS, RootDestination.CALLS, RootDestination.ROOMS, RootDestination.TRANSFERS, RootDestination.SEARCH, RootDestination.SETTINGS),
            RootNavigation.visibleDestinations(showCalls = true),
        )
    }
}
