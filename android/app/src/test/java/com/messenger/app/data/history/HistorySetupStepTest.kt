package com.messenger.app.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySetupStepTest {
    @Test fun `first backup journey and cancelled draft have a concrete next step`() {
        assertEquals(HistorySetupStep.prepareKey, HistorySetupStep.next(false, false, false, false, false))
        assertEquals(HistorySetupStep.confirmKey, HistorySetupStep.next(false, true, false, false, false))
        assertEquals(HistorySetupStep.prepareKey, HistorySetupStep.next(false, false, false, false, false))
        assertEquals(HistorySetupStep.allowUploads, HistorySetupStep.next(true, false, false, false, false))
        assertEquals(HistorySetupStep.createBackup, HistorySetupStep.next(true, false, false, false, true))
        assertEquals(HistorySetupStep.ready, HistorySetupStep.next(true, false, true, false, true))
    }

    @Test fun `existing or incomplete server copy does not suggest making a replacement key`() {
        for (uploads in listOf(false, true)) {
            assertEquals(HistorySetupStep.enterExistingKey, HistorySetupStep.next(false, false, true, false, uploads))
            assertEquals(HistorySetupStep.enterExistingKey, HistorySetupStep.next(false, false, true, true, uploads))
            assertEquals(HistorySetupStep.unfinishedUpload, HistorySetupStep.next(false, false, false, true, uploads))
        }
    }

    @Test fun `paused upload is separate from existing copy and interrupted replacement`() {
        assertEquals(HistorySetupStep.allowUploads, HistorySetupStep.next(true, false, true, false, false))
        assertEquals(HistorySetupStep.ready, HistorySetupStep.next(true, false, true, true, true))
        assertEquals(HistorySetupStep.createBackup, HistorySetupStep.next(true, false, false, true, true))
    }
}
