package com.messenger.app.ui.components

import com.messenger.app.data.sync.SyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatConnectionStatusTest {
    @Test fun `cached content keeps online header during background loading and sync`() {
        assertEquals(HeaderConnectionStatus.ONLINE, headerConnectionStatus(true, true, false, SyncPhase.RUNNING, false, false, contentReady = true))
        assertEquals(HeaderConnectionStatus.ONLINE, headerConnectionStatus(true, true, false, SyncPhase.IDLE, true, false, contentReady = true))
    }
    @Test fun `cold loading still communicates that initial content is syncing`() {
        assertEquals(HeaderConnectionStatus.SYNCING, headerConnectionStatus(true, true, false, SyncPhase.RUNNING, false, false, contentReady = false))
        assertEquals(HeaderConnectionStatus.SYNCING, headerConnectionStatus(true, true, false, SyncPhase.IDLE, true, false, contentReady = false))
    }
    @Test fun `cached content cannot hide a real failed sync or disconnected route`() {
        assertEquals(HeaderConnectionStatus.SYNC_FAILED, headerConnectionStatus(true, true, false, SyncPhase.FAILED, false, false, contentReady = true))
        assertEquals(HeaderConnectionStatus.SYNC_FAILED, headerConnectionStatus(true, true, false, SyncPhase.IDLE, false, true, contentReady = true))
        assertEquals(HeaderConnectionStatus.OFFLINE, headerConnectionStatus(false, false, false, SyncPhase.RUNNING, true, false, contentReady = true))
        assertEquals(HeaderConnectionStatus.UNAVAILABLE, headerConnectionStatus(false, true, true, SyncPhase.IDLE, false, false, contentReady = true))
    }
    @Test fun `network route alone never claims the server is online`() {
        assertEquals(HeaderConnectionStatus.CONNECTING, headerConnectionStatus(false, true, false, SyncPhase.IDLE, false, false))
        assertEquals(HeaderConnectionStatus.UNAVAILABLE, headerConnectionStatus(false, true, true, SyncPhase.IDLE, false, false))
        assertEquals(HeaderConnectionStatus.OFFLINE, headerConnectionStatus(false, false, true, SyncPhase.IDLE, false, false))
    }
    @Test fun `actual socket connection overrides missing network callback and expired grace`() {
        assertEquals(HeaderConnectionStatus.ONLINE, headerConnectionStatus(true, false, true, SyncPhase.IDLE, false, false))
    }
    @Test fun `sync work and failures are distinct from offline server`() {
        assertEquals(HeaderConnectionStatus.SYNCING, headerConnectionStatus(true, true, false, SyncPhase.RUNNING, false, false))
        assertEquals(HeaderConnectionStatus.SYNCING, headerConnectionStatus(true, true, false, SyncPhase.IDLE, true, false))
        assertEquals(HeaderConnectionStatus.SYNC_FAILED, headerConnectionStatus(true, true, false, SyncPhase.FAILED, false, false))
        assertEquals(HeaderConnectionStatus.SYNC_FAILED, headerConnectionStatus(true, true, false, SyncPhase.IDLE, false, true))
    }
}
