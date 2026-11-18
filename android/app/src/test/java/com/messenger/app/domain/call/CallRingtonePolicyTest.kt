package com.messenger.app.domain.call

import kotlin.test.*

class CallRingtonePolicyTest {
    private val symbolic = "content://settings/system/ringtone"
    private val actual = "content://media/internal/audio/media/42"

    private fun resolve(selected: String?, actualDefault: String?): String? = CallRingtonePolicy.resolve(
        selected, { if (it == symbolic) 1 else -1 }, { type ->
            assertEquals(1, type) // RingtoneManager.TYPE_RINGTONE
            actualDefault
        })

    @Test fun `symbolic default with None selected cannot confirm despite normal mode and positive volume`() {
        // Platform-derived state: channel.sound remains DEFAULT_RINGTONE_URI;
        // getActualDefaultRingtoneUri(TYPE_RINGTONE) returns null for None.
        assertNotNull(symbolic)
        val resolved = resolve(symbolic, null)
        assertNull(resolved)
        for (importance in listOf(3, 4)) {
            assertFalse(CallRingConfirmationPolicy.wouldAlert(true, true, importance,
                resolved != null, false, true))
            assertTrue(CallRingConfirmationPolicy.wouldAlert(true, true, importance,
                resolved != null, true, true))
            assertFalse(CallRingConfirmationPolicy.wouldAlert(true, true, importance,
                resolved != null, true, false))
        }
    }

    @Test fun `audible default resolves to the actual playback URI`() {
        assertEquals(actual, resolve(symbolic, actual))
        assertTrue(CallRingConfirmationPolicy.wouldAlert(true, true, 4,
            resolve(symbolic, actual) != null, false, true))
    }

    @Test fun `custom sound bypasses default lookup and explicit silence stays silent`() {
        assertEquals(actual, CallRingtonePolicy.resolve(actual, { -1 }, { error("not a default") }))
        assertNull(CallRingtonePolicy.resolve<String>(null, { error("no sound") }, { error("no sound") }))
    }
}
