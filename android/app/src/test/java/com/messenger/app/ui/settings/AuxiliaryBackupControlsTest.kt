package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuxiliaryBackupControlsTest {
    @Test fun dailyRequiresBothKeyAndUploadsButWifiCanBePreparedBeforeSetup() {
        for (key in listOf(false, true)) for (uploads in listOf(false, true)) {
            val controls = historyBackupControls(key, uploads, automatic = false, busy = false)
            if (key && uploads) assertTrue(controls.dailyEnabled) else assertFalse(controls.dailyEnabled)
            assertTrue(controls.wifiEnabled)
        }
    }

    @Test fun anExistingScheduleCanBeStoppedEvenWhenPrerequisitesDisappear() {
        assertTrue(historyBackupControls(false, false, automatic = true, busy = false).dailyEnabled)
    }

    @Test fun activeWorkDisablesChangesUntilCompletionOrFailure() {
        for (key in listOf(false, true)) for (uploads in listOf(false, true)) for (automatic in listOf(false, true)) {
            val working = historyBackupControls(key, uploads, automatic, busy = true)
            assertFalse(working.dailyEnabled)
            assertFalse(working.wifiEnabled)
            assertTrue(historyBackupControls(key, uploads, automatic, busy = false).wifiEnabled)
        }
    }
}
