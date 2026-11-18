package com.messenger.app.ui.components

import com.messenger.app.data.sync.SyncPhase
import com.messenger.app.ui.chats.ChatMotionSettings
import com.messenger.app.ui.chats.chatAnimatorScale
import kotlin.test.*

class ConnectionFeedbackContractsTest {
    @Test fun `last ping survives pause and stale completions cannot overwrite resume`() {
        val state = HeaderPingState()
        val first = state.start(true, true, true)!!
        state.accept(first, 96)
        assertNull(state.start(true, true, false))
        assertEquals(96L, state.value)
        state.accept(first, 10)
        assertEquals(96L, state.value)
        val resumed = state.start(true, true, true)!!
        state.accept(resumed, 105)
        state.stop(first)
        state.accept(resumed, 106)
        assertEquals(106L, state.value)
        state.stop(resumed)
        state.accept(resumed, 1)
        assertEquals(106L, state.value)
    }

    @Test fun `disconnect and preference off clear ping even while hidden`() {
        for ((enabled, connected) in listOf(false to true, true to false, false to false)) {
            val state = HeaderPingState()
            val request = state.start(true, true, true)!!
            state.accept(request, 96)
            assertNull(state.start(enabled, connected, false))
            assertNull(state.value)
            state.accept(request, 20)
            assertNull(state.value)
        }
    }

    @Test fun `unavailable or invalid ping reserves the same three digit footprint`() {
        for (value in listOf(null, 1L, 12L, 123L)) {
            assertEquals(3, (value?.toString() ?: "—").length + headerPingFigurePadding(value).length)
        }
        assertEquals("", headerPingFigurePadding(1234))
        val state = HeaderPingState()
        state.accept(state.start(true, true, true)!!, -1)
        assertNull(state.value)
    }

    @Test fun `flow and work stop in every calm mode and resume without animating idle failures`() {
        val normal = ChatMotionSettings(active = true)
        assertEquals(ConnectionStripMotion.Still, connectionStripMotion(true, false, normal))
        assertEquals(ConnectionStripMotion.Sweep, connectionStripMotion(false, true, normal))
        assertEquals(ConnectionStripMotion.Still, connectionStripMotion(false, false, normal))
        for (calm in listOf(normal.copy(active = false), normal.copy(lowPower = true), normal.copy(durationScale = 0f))) {
            assertEquals(ConnectionStripMotion.Still, connectionStripMotion(true, false, calm))
            assertEquals(ConnectionStripMotion.Still, connectionStripMotion(false, true, calm))
        }
        for (scale in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(0f, chatAnimatorScale(scale))
        assertEquals(2f, chatAnimatorScale(2f))
    }

    @Test fun `content ready never shows initial syncing and recovery does not hide an offline route`() {
        assertEquals(HeaderConnectionStatus.ONLINE, headerConnectionStatus(true, true, false, SyncPhase.RUNNING, true, false, true))
        assertEquals(HeaderConnectionStatus.SYNCING, headerConnectionStatus(true, true, false, SyncPhase.RUNNING, true, false))
        assertEquals(HeaderConnectionStatus.SYNC_FAILED, headerConnectionStatus(true, true, false, SyncPhase.FAILED, false, false, true))
        assertEquals(HeaderConnectionStatus.OFFLINE, headerConnectionStatus(false, false, true, SyncPhase.RUNNING, true, false))
        assertEquals(HeaderConnectionStatus.UNAVAILABLE, headerConnectionStatus(false, true, true, SyncPhase.RUNNING, true, false))
    }
}
