package com.messenger.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import androidx.lifecycle.viewModelScope
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DrawerPresenceControllerTest {
    @Test fun queuedPrivacyTransformRejectsNewOwnerAndPreservesOtherPrivacyChoices() {
        val current = com.messenger.app.data.profile.PrivacySnapshot(lastSeenVisibility = "nobody",
            alwaysAllow = listOf("trusted"), sendReadReceipts = false, revision = 9)
        assertEquals(current.copy(onlineVisibility = "nobody"), drawerPresenceUpdate(current, "a", "a", "nobody"))
        assertFailsWith<IllegalStateException> { drawerPresenceUpdate(current, "a", "b", "nobody") }
        assertFailsWith<IllegalStateException> { drawerPresenceUpdate(current, null, null, "everyone") }
    }

    @Test fun closingTheDrawerUiDoesNotCancelAnAcceptedViewModelCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var visibility = "everyone"
        var writes = 0
        val model = DrawerPresenceViewModel(DrawerPresenceController("a", { "a" }, {
            writes++; release.await(); visibility = it; Result.success(Unit)
        }, {}), { true })
        try {
            val visibleUi = launch { model.choose(DrawerPresence.INVISIBLE); awaitCancellation() }
            runCurrent()
            model.choose(DrawerPresence.ONLINE) // A second reader/tap must share the same command gate.
            visibleUi.cancelAndJoin()
            assertTrue(model.controller.busy)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, writes)
            assertEquals("nobody", visibility)
            assertFalse(model.controller.busy)
            assertFalse(model.failed)
        } finally { model.viewModelScope.coroutineContext.job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun failureSurvivesUiDisposalAndIsNotErasedByReopeningTheDrawer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val model = DrawerPresenceViewModel(DrawerPresenceController("a", { "a" }, {
            release.await(); Result.failure(IllegalStateException("offline"))
        }, {}), { true })
        try {
            val visibleUi = launch { model.choose(DrawerPresence.INVISIBLE); awaitCancellation() }
            runCurrent(); visibleUi.cancelAndJoin(); release.complete(Unit); advanceUntilIdle()
            assertTrue(model.failed)
            model.refreshIfNeeded(); advanceUntilIdle()
            assertTrue(model.failed)
            assertFalse(model.controller.busy)
        } finally { model.viewModelScope.coroutineContext.job.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun statusUsesAlertPreferenceBeforePrivacyAndTransitionsBothOwners() = runTest {
        var visibility = "everyone"
        var messages = true
        val controller = DrawerPresenceController("a", { "a" }, { visibility = it; Result.success(Unit) }, { messages = it })
        assertTrue(controller.choose(DrawerPresence.INVISIBLE))
        assertEquals(DrawerPresence.INVISIBLE, drawerPresence(messages, visibility))
        assertTrue(controller.choose(DrawerPresence.DO_NOT_DISTURB))
        assertEquals("everyone", visibility)
        assertEquals(DrawerPresence.DO_NOT_DISTURB, drawerPresence(messages, "nobody"))
        assertTrue(controller.choose(DrawerPresence.ONLINE))
        assertTrue(messages)
        assertEquals(DrawerPresence.ONLINE, drawerPresence(messages, visibility))
    }

    @Test fun failedPrivacyWriteKeepsLocalDoNotDisturbAndAllowsRetry() = runTest {
        var writes = 0
        var messages = true
        val controller = DrawerPresenceController("a", { "a" }, {
            if (++writes == 1) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
        }, { messages = it })
        assertFalse(controller.choose(DrawerPresence.DO_NOT_DISTURB))
        assertFalse(messages)
        assertFalse(controller.busy)
        assertTrue(controller.choose(DrawerPresence.DO_NOT_DISTURB))
        assertFalse(messages)
    }

    @Test fun offlineDoNotDisturbNeedsNoRequestWhenVisibilityAlreadyMatches() = runTest {
        var messages = true
        val controller = DrawerPresenceController("a", { "a" }, { error("No redundant request while offline") },
            { messages = it }, { "everyone" })
        assertTrue(controller.choose(DrawerPresence.DO_NOT_DISTURB))
        assertFalse(messages)
        assertEquals(DrawerPresence.DO_NOT_DISTURB, drawerPresence(messages, "everyone"))
        assertTrue(controller.choose(DrawerPresence.ONLINE))
        assertTrue(messages)
    }

    @Test fun onlyOneWriteMayBeInFlightAndCancellationReleasesGate() = runTest {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var alerts = 0
        val controller = DrawerPresenceController("a", { "a" }, {
            writes++; release.await(); Result.success(Unit)
        }, { alerts++ })
        val first = launch { controller.choose(DrawerPresence.INVISIBLE) }
        runCurrent()
        assertTrue(controller.busy)
        assertFalse(controller.choose(DrawerPresence.ONLINE))
        assertEquals(1, writes)
        first.cancelAndJoin()
        assertFalse(controller.busy)
        assertEquals(1, alerts)
        release.complete(Unit)
        assertTrue(controller.choose(DrawerPresence.ONLINE))
    }

    @Test fun changedAccountCannotReceiveThePreviousAccountsAlertChange() = runTest {
        var owner: String? = "a"
        val release = CompletableDeferred<Unit>()
        val alertOwners = mutableListOf<String?>()
        val controller = DrawerPresenceController(owner, { owner }, { release.await(); Result.success(Unit) }, { alertOwners += owner })
        val result = async { controller.choose(DrawerPresence.DO_NOT_DISTURB) }
        runCurrent()
        owner = "b"
        release.complete(Unit)
        assertFalse(result.await())
        assertEquals(listOf<String?>("a"), alertOwners)
        assertFalse(controller.choose(DrawerPresence.ONLINE))
        val signedOut = DrawerPresenceController(null, { null }, { error("Must not request while signed out") }, { error("Must not write") })
        assertFalse(signedOut.choose(DrawerPresence.ONLINE))
    }
}
