package com.messenger.app.ui.chats

import kotlin.test.*

class MessageMenuBudgetTest {
    @Test fun `all menu states stay inside the viewport including landscape and large text`() {
        for(height in listOf(0f,40f,80f,120f,176f,240f,342f,600f,784f))
            for(rows in 1..10) for(reactions in listOf(0f,60f,76f,120f,228f)) for(preview in listOf(false,true)) {
                val budget=messageMenuBudget(height,rows,reactions,preview)
                assertTrue(budget.total(reactions)<=height+.01f,"height=$height budget=$budget")
                assertTrue(budget.menuHeight>=0&&budget.previewLimit>=0)
            }
    }
    @Test fun `short windows prioritize reachable actions and move reactions to menu`() {
        val budget=messageMenuBudget(120f,8,76f,true)
        assertFalse(budget.showInlineReactions)
        assertTrue(budget.menuHeight>=48f)
    }
    @Test fun `normal window retains preview and quick reactions`() {
        val budget=messageMenuBudget(784f,7,76f,true)
        assertTrue(budget.showClose&&budget.showInlineReactions)
        assertEquals(220f,budget.previewLimit)
        assertTrue(budget.menuHeight>=7*48)
    }
}
