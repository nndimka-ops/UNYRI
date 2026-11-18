package com.messenger.app

import kotlin.test.*

class OrientationOwnersTest {
    @Test fun nestedOwnersRestoreTheRemainingOwnerAndThenOriginalRequest() {
        val registry = OrientationOwners(1)
        val viewer = Any(); val call = Any(); val conference = Any()
        assertTrue(registry.acquire(viewer, 2))
        assertFalse(registry.acquire(viewer, 2))
        assertTrue(registry.acquire(call, 3))
        assertTrue(registry.acquire(conference, 4))
        assertTrue(registry.release(call))
        assertEquals(4, registry.requested)
        assertTrue(registry.release(conference))
        assertEquals(2, registry.requested)
        assertFalse(registry.release(call))
        assertTrue(registry.release(viewer))
        assertTrue(registry.empty)
        assertEquals(1, registry.requested)
    }

    @Test fun inactiveCallCannotChangeViewerRequestAndActivitiesAreIndependent() {
        val first = OrientationOwners(1); val second = OrientationOwners(5)
        val viewer = Any(); val call = Any()
        first.acquire(viewer, 2)
        repeat(10) { assertFalse(first.release(call)); assertEquals(2, first.requested) }
        second.acquire(call, 3)
        assertEquals(2, first.requested)
        second.release(call)
        assertEquals(5, second.requested)
    }
}
