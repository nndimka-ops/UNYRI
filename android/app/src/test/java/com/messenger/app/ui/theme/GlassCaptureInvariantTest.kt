package com.messenger.app.ui.theme

import org.junit.Assert.*
import org.junit.Test

class GlassCaptureInvariantTest {
    @Test fun capturesCannotSampleAnySourceIncludingAnIndirectSelfReference() {
        // A contains a panel sampling A, or B which could contain A: both would
        // add a render-node dependency from inside a source and must be opaque.
        assertFalse(glassSamplingAllowed(sourceEnabled = true, insideSource = true))
        assertFalse(glassSamplingAllowed(sourceEnabled = false, insideSource = true))
        assertFalse(glassSamplingAllowed(sourceEnabled = false, insideSource = false))
        assertTrue(glassSamplingAllowed(sourceEnabled = true, insideSource = false))
    }
}
