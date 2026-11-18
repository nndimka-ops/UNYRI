package com.messenger.app.ui.chats

import kotlin.test.*

class ChatMenuParityTest {
    private fun entries(peer: Boolean = true, saved: Boolean = false, blocked: Boolean = false,
        draft: String = "", editing: Boolean = false, replying: Boolean = false) =
        headerMenuEntries(peer, saved, blocked, draft, editing, replying)

    @Test fun `direct chat has the observed order and no blank schedule`() {
        assertEquals(listOf(HeaderMenuEntry.Search, HeaderMenuEntry.Media, HeaderMenuEntry.Pins,
            HeaderMenuEntry.Reminders, HeaderMenuEntry.Video, HeaderMenuEntry.Typing,
            HeaderMenuEntry.Mute, HeaderMenuEntry.Block, HeaderMenuEntry.Report), entries())
        assertFalse(HeaderMenuEntry.Schedule in entries(draft = " \n\t"))
    }

    @Test fun `schedule follows draft transitions and is unavailable during edit or reply`() {
        val ordinary = entries()
        val composed = entries(draft = "A draft")
        assertEquals(ordinary, composed.filterNot { it == HeaderMenuEntry.Schedule })
        assertTrue(composed.indexOf(HeaderMenuEntry.Schedule) < composed.indexOf(HeaderMenuEntry.Typing))
        assertEquals(ordinary, entries(draft = "A draft", editing = true))
        assertEquals(ordinary, entries(draft = "A draft", replying = true))
        assertEquals(composed, entries(draft = "A draft", editing = false, replying = false))
    }

    @Test fun `blocking removes video without removing the recovery and safety actions`() {
        assertEquals(entries().filterNot { it == HeaderMenuEntry.Video }, entries(blocked = true))
        assertEquals(entries(), entries(blocked = false))
    }

    @Test fun `saved and group chats expose only applicable actions`() {
        val saved = entries(peer = false, saved = true, draft = "Note")
        assertEquals(listOf(HeaderMenuEntry.Search, HeaderMenuEntry.Media, HeaderMenuEntry.Pins,
            HeaderMenuEntry.Reminders, HeaderMenuEntry.Schedule), saved)
        val group = entries(peer = false)
        assertTrue(HeaderMenuEntry.Typing in group)
        assertTrue(group.none { it in listOf(HeaderMenuEntry.Video, HeaderMenuEntry.Mute, HeaderMenuEntry.Block, HeaderMenuEntry.Report) })
    }

    @Test fun `message actions stay reachable across viewport and font size budgets`() {
        for (height in listOf(0f, 40f, 80f, 120f, 176f, 240f, 342f, 600f, 784f))
            for (rows in 1..12) for (reactions in listOf(0f, 48f, 68f, 100f)) for (preview in listOf(false, true)) {
                val budget = parityMessageMenuBudget(height, rows, reactions, preview)
                assertTrue(budget.total(reactions) <= height + .01f, "height=$height budget=$budget")
                assertTrue(budget.menuHeight >= 0 && budget.previewLimit >= 0)
                assertFalse(budget.showClose)
                if (height >= 80) assertTrue(budget.menuHeight >= 48f)
            }
    }

    @Test fun `short message menus use a reaction shortcut and retain space for actions`() {
        val small = parityMessageMenuBudget(120f, 7, 68f, true)
        assertFalse(small.showInlineReactions)
        assertEquals(0f, small.previewLimit)
        val normal = parityMessageMenuBudget(784f, 7, 48f, true)
        assertTrue(normal.showInlineReactions)
        assertTrue(normal.previewLimit > 0f)
        assertTrue(normal.menuHeight > normal.previewLimit)
    }
}
