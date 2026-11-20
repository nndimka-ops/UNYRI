package com.messenger.app.data.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CallDataSavingTest {
    @Test fun `stored mode wins over legacy quality and invalid modes retain previous economy preference`() {
        for (mode in CallDataSaving.entries) {
            assertEquals(mode, CallDataSaving.resolved(mode.wire, "economy", "economy"))
        }
        for (stored in listOf(null, "invalid")) {
            assertEquals(CallDataSaving.ALWAYS, CallDataSaving.resolved(stored, "economy", "auto"))
            assertEquals(CallDataSaving.ALWAYS, CallDataSaving.resolved(stored, "high", "economy"))
            assertEquals(CallDataSaving.NEVER, CallDataSaving.resolved(stored, "high", "auto"))
            assertEquals(CallDataSaving.NEVER, CallDataSaving.resolved(stored, null, null))
        }
    }

    @Test fun `network and data-saving choices select the existing media ladder`() {
        for (mode in CallDataSaving.entries) {
            val unmetered = mode.tier(true, false, false, false)
            assertEquals(if (mode == CallDataSaving.ALWAYS) CallNetworkTier.SAVING else CallNetworkTier.WIFI, unmetered)
            for ((available, cellular, metered) in listOf(Triple(true, true, true), Triple(true, false, true), Triple(false, false, false))) {
                val tier = mode.tier(available, cellular, metered, false)
                assertEquals(if (mode == CallDataSaving.NEVER) CallNetworkTier.CELLULAR else CallNetworkTier.SAVING, tier)
            }
            assertEquals(CallNetworkTier.SAVING, mode.tier(true, false, true, true))
        }
    }

    @Test fun `saving caps audio and video and never keeps network adaptation`() {
        val wifi = CallMediaLadder.profile(CallDataSaving.NEVER.tier(true, false, false, false))
        val cellular = CallMediaLadder.profile(CallDataSaving.NEVER.tier(true, true, true, false))
        val saving = CallMediaLadder.profile(CallDataSaving.ALWAYS.tier(true, false, false, false))
        assertEquals(720, wifi.height)
        assertEquals(540, cellular.height)
        assertEquals(360, saving.height)
        assertEquals(350_000, saving.maxVideoBitrate)
        assertEquals(20_000, saving.audioMaxBitrate)
    }

    @Test fun `cellular saving preserves full source capture for a later Wi-Fi upgrade`() {
        val mode = CallDataSaving.CELLULAR
        val captureAtCallStart = mode.captureProfile()
        val cellularOutput = CallMediaLadder.profile(mode.tier(true, true, true, false))
        val wifiOutput = CallMediaLadder.profile(mode.tier(true, false, false, false))
        assertEquals(720, captureAtCallStart.height)
        assertEquals(30, captureAtCallStart.fps)
        assertEquals(360, cellularOutput.height)
        assertEquals(captureAtCallStart, wifiOutput)
        assertEquals(CallMediaLadder.wifi, CallDataSaving.NEVER.captureProfile())
        assertEquals(CallMediaLadder.saving, CallDataSaving.ALWAYS.captureProfile())
    }
}
