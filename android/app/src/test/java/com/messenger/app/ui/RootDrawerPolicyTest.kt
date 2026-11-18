package com.messenger.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.mutableStateOf

class RootDrawerPolicyTest {
    @Test fun rootDestinationsAllowNavigationWithoutAChildOrOverlay() {
        RootDestination.entries.forEach { root ->
            assertTrue(RootDrawerPolicy.allowsOpening(root, hostAvailable = true, resumed = true, blocked = false))
        }
    }

    @Test fun nestedFormAndFullscreenOrBackgroundHostCannotOpenDrawer() {
        RootDestination.entries.forEach { root ->
            assertFalse(RootDrawerPolicy.allowsOpening(root, hostAvailable = true, resumed = true, blocked = true))
            assertFalse(RootDrawerPolicy.allowsOpening(root, hostAvailable = false, resumed = true, blocked = false))
            assertFalse(RootDrawerPolicy.allowsOpening(root, hostAvailable = true, resumed = false, blocked = false))
        }
    }

    @Test fun disposingAnOldChildDoesNotUnblockItsSuccessor() {
        val blockers = RootDrawerBlockers()
        val oldForm = Any(); val nextModal = Any()
        blockers.acquire(oldForm); blockers.acquire(nextModal)
        blockers.release(oldForm)
        assertTrue(blockers.blocked)
        assertFalse(RootDrawerPolicy.allowsOpening(RootDestination.SETTINGS, true, true, blockers.blocked))
        blockers.release(nextModal)
        assertTrue(RootDrawerPolicy.allowsOpening(RootDestination.CHATS, true, true, blockers.blocked))
    }

    @Test fun anotherScopesCleanupAndRepeatedReleaseCannotEraseABlocker() {
        val blockers = RootDrawerBlockers(); val modal = Any(); val stale = Any()
        blockers.acquire(modal); blockers.acquire(modal)
        blockers.release(stale); blockers.release(stale)
        assertTrue(blockers.blocked)
        blockers.release(modal)
        assertFalse(blockers.blocked)
        blockers.release(modal)
        assertFalse(blockers.blocked)
    }

    @Test fun retainedDestinationCallbackReadsCurrentHostAndBlockers() {
        val hostAvailable = mutableStateOf(false)
        val resumed = mutableStateOf(true)
        val blockers = RootDrawerBlockers()
        val child = Any()
        // Keep this exact callback alive while its inputs change. This tests
        // the gate contract with live State, not NavHost lifecycle delivery.
        val retainedDestination = {
            RootDrawerPolicy.allowsOpening(RootDestination.CHATS,
                hostAvailable.value, resumed.value, blockers.blocked)
        }
        assertFalse(retainedDestination())
        hostAvailable.value = true
        assertTrue(retainedDestination())
        blockers.acquire(child)
        assertFalse(retainedDestination())
        blockers.release(child)
        assertTrue(retainedDestination())
        resumed.value = false
        assertFalse(retainedDestination())
        resumed.value = true
        hostAvailable.value = false
        assertFalse(retainedDestination())
    }
}
